//! SQLite インデックスに対するフィルタリング・ページング。
//!
//! レベル・日時は SQL で絞り込み、logger / thread / message / source / grep は
//! Rust 側で正規表現マッチ（Python 版と同じ挙動）。

use crate::index::{self, EntryRow};
use chrono::NaiveDateTime;
use regex::Regex;
use rusqlite::Connection;
use std::collections::HashSet;

/// `/api/logs` のクエリパラメータを表現。
pub struct QueryFilter {
    pub levels: Option<HashSet<String>>,
    pub logger_re: Option<Regex>,
    pub thread_re: Option<Regex>,
    pub message_re: Option<Regex>,
    pub source_re: Option<Regex>,
    pub grep_re: Option<Regex>,
    pub since: Option<NaiveDateTime>,
    pub until: Option<NaiveDateTime>,
}

/// `ERROR` / `WARN,ERROR` 形式を解釈する。
pub fn parse_level_filter(value: &str) -> Option<HashSet<String>> {
    let set: HashSet<String> = value
        .split(',')
        .filter_map(|p| {
            let p = p.trim();
            if p.is_empty() {
                None
            } else {
                Some(p.to_ascii_uppercase())
            }
        })
        .collect();
    if set.is_empty() {
        None
    } else {
        Some(set)
    }
}

/// UI から渡される日時文字列を解析する。
pub fn parse_datetime(value: &str) -> Result<NaiveDateTime, String> {
    let formats = [
        "%Y-%m-%dT%H:%M:%S%.3f",
        "%Y-%m-%d %H:%M:%S%.3f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d",
    ];
    for fmt in formats {
        if let Ok(dt) = NaiveDateTime::parse_from_str(value, fmt) {
            return Ok(dt);
        }
    }
    Err(format!("日時形式を解釈できません: {value}"))
}

fn ts_to_iso(dt: NaiveDateTime) -> String {
    dt.format("%Y-%m-%dT%H:%M:%S%.6f").to_string()
}

/// SQL 事前フィルタ後の行に、正規表現・grep 条件を適用する。
fn matches_row(entry: &EntryRow, filter: &QueryFilter, raw: Option<&str>) -> bool {
    if let Some(levels) = &filter.levels {
        if !levels.contains(&entry.level.to_ascii_uppercase()) {
            return false;
        }
    }
    if let Some(since) = filter.since {
        if entry.timestamp < since {
            return false;
        }
    }
    if let Some(until) = filter.until {
        if entry.timestamp > until {
            return false;
        }
    }
    if let Some(re) = &filter.source_re {
        if !re.is_match(&entry.source) {
            return false;
        }
    }
    if let Some(re) = &filter.logger_re {
        if !re.is_match(&entry.logger) {
            return false;
        }
    }
    if let Some(re) = &filter.thread_re {
        if !re.is_match(&entry.thread) {
            return false;
        }
    }
    if let Some(re) = &filter.message_re {
        if !re.is_match(&entry.message) {
            return false;
        }
    }
    if let Some(re) = &filter.grep_re {
        let text = raw.unwrap_or("");
        if !re.is_match(text) {
            return false;
        }
    }
    true
}

/// フィルタに一致するエントリを走査し、(総件数, ページ) を返す。
pub fn query_logs(
    conn: &Connection,
    filter: &QueryFilter,
    offset: u64,
    limit: u64,
) -> rusqlite::Result<(u64, Vec<EntryRow>)> {
    let mut sql = String::from(
        "SELECT e.id, e.file_id, e.line_no, e.byte_offset, e.end_byte_offset,
                e.timestamp, e.logger, e.level, e.thread, e.message, f.path
         FROM entries e
         JOIN files f ON e.file_id = f.id
         WHERE 1=1",
    );
    let mut sql_params: Vec<Box<dyn rusqlite::types::ToSql>> = Vec::new();

    if let Some(levels) = &filter.levels {
        let placeholders: Vec<String> = levels.iter().map(|_| "?".to_string()).collect();
        sql.push_str(&format!(" AND e.level IN ({})", placeholders.join(", ")));
        for level in levels {
            sql_params.push(Box::new(level.clone()));
        }
    }
    if let Some(since) = filter.since {
        sql.push_str(" AND e.timestamp >= ?");
        sql_params.push(Box::new(ts_to_iso(since)));
    }
    if let Some(until) = filter.until {
        sql.push_str(" AND e.timestamp <= ?");
        sql_params.push(Box::new(ts_to_iso(until)));
    }
    sql.push_str(" ORDER BY e.timestamp, e.file_id, e.line_no");

    let mut stmt = conn.prepare(&sql)?;
    let param_refs: Vec<&dyn rusqlite::types::ToSql> =
        sql_params.iter().map(|p| p.as_ref()).collect();

    let mut rows = stmt.query(param_refs.as_slice())?;
    let mut total: u64 = 0;
    let mut page = Vec::new();
    let needs_raw = filter.grep_re.is_some();

    while let Some(row) = rows.next()? {
        let entry = index_row(row)?;
        let raw = if needs_raw {
            Some(index::read_entry_raw(
                std::path::Path::new(&entry.source),
                entry.byte_offset as u64,
                entry.end_byte_offset.map(|v| v as u64),
            )
            .unwrap_or_default())
        } else {
            None
        };
        if !matches_row(&entry, filter, raw.as_deref()) {
            continue;
        }
        if total >= offset && page.len() < limit as usize {
            page.push(entry);
        }
        total += 1;
    }

    Ok((total, page))
}

fn index_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<EntryRow> {
    let ts_str: String = row.get(5)?;
    let timestamp = NaiveDateTime::parse_from_str(&ts_str, "%Y-%m-%dT%H:%M:%S%.6f")
        .or_else(|_| NaiveDateTime::parse_from_str(&ts_str, "%Y-%m-%dT%H:%M:%S%.3f"))
        .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
    Ok(EntryRow {
        id: row.get(0)?,
        file_id: row.get(1)?,
        line_no: row.get(2)?,
        byte_offset: row.get(3)?,
        end_byte_offset: row.get(4)?,
        timestamp,
        logger: row.get(6)?,
        level: row.get(7)?,
        thread: row.get(8)?,
        message: row.get(9)?,
        source: row.get(10)?,
    })
}

/// 一覧 API 用 JSON オブジェクト。
pub fn entry_to_json(entry: &EntryRow) -> serde_json::Value {
    serde_json::json!({
        "timestamp": entry.timestamp.format("%Y-%m-%dT%H:%M:%S%.6f").to_string(),
        "level": entry.level,
        "logger": entry.logger,
        "thread": entry.thread,
        "message": entry.message,
        "source": entry.source,
        "line_no": entry.line_no,
    })
}

/// 大文字小文字を無視する正規表現（Python `re.IGNORECASE` 相当）。
pub fn compile_regex(pat: &str) -> Result<Regex, String> {
    Regex::new(&format!("(?i){pat}")).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index;
    use std::fs;
    use std::path::PathBuf;

    fn sample_log() -> (PathBuf, PathBuf) {
        let tmp = std::env::temp_dir().join("aplv-rs-query-test");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();
        let log = tmp.join("app.log");
        fs::write(
            &log,
            "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - error one\n\
             stack line\n\
             2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - info two\n",
        )
        .unwrap();
        (tmp, log)
    }

    #[test]
    fn parse_level_filter_comma_separated() {
        let levels = parse_level_filter("error,warn").unwrap();
        assert!(levels.contains("ERROR"));
        assert!(levels.contains("WARN"));
    }

    #[test]
    fn parse_datetime_accepts_space_and_t() {
        assert!(parse_datetime("2026-06-15 00:00:01.000").is_ok());
        assert!(parse_datetime("2026-06-15T00:00:01.000").is_ok());
        assert!(parse_datetime("invalid").is_err());
    }

    #[test]
    fn query_filters_by_level_and_grep() {
        let (tmp, log) = sample_log();
        let conn = index::open_or_create(&tmp).unwrap();
        index::build_index(&conn, &[log], |_| {}).unwrap();

        let filter = QueryFilter {
            levels: parse_level_filter("ERROR"),
            logger_re: None,
            thread_re: None,
            message_re: None,
            source_re: None,
            grep_re: None,
            since: None,
            until: None,
        };
        let (total, page) = query_logs(&conn, &filter, 0, 10).unwrap();
        assert_eq!(total, 1);
        assert_eq!(page[0].level, "ERROR");

        let filter = QueryFilter {
            levels: None,
            logger_re: None,
            thread_re: None,
            message_re: None,
            source_re: None,
            grep_re: compile_regex("stack line").ok(),
            since: None,
            until: None,
        };
        let (total, _) = query_logs(&conn, &filter, 0, 10).unwrap();
        assert_eq!(total, 1);

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn entry_to_json_has_expected_keys() {
        let entry = EntryRow {
            id: 1,
            file_id: 1,
            line_no: 1,
            byte_offset: 0,
            end_byte_offset: Some(10),
            timestamp: parse_datetime("2026-06-15 00:00:01.000").unwrap(),
            logger: "com.example.Foo".into(),
            level: "INFO".into(),
            thread: "main".into(),
            message: "hello".into(),
            source: "D:\\logs\\app.log".into(),
        };
        let json = entry_to_json(&entry);
        assert_eq!(json["level"], "INFO");
        assert_eq!(json["source"], "D:\\logs\\app.log");
    }
}
