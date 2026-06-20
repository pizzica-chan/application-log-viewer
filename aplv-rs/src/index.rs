//! SQLite インデックスの構築・参照。
//!
//! エントリのメタデータ（日時・レベル・logger 等）と元ファイル上の byte 範囲を DB に保存する。
//! スタックトレース本文は DB に載せず、[`read_entry_raw`] でオンデマンド読み出し。

use crate::path_util::normalize_path;
use crate::index_store;
use crate::parser::{parse_line, ParsedLine};
use chrono::NaiveDateTime;
use rusqlite::{params, Connection, OptionalExtension};
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

/// INSERT バッチサイズ（トランザクション中のメモリ使用量と速度のバランス）。
const BATCH_SIZE: usize = 2000;

/// DB から取得した 1 エントリ（一覧・詳細 API 用）。
#[derive(Debug, Clone)]
pub struct EntryRow {
    pub id: i64,
    pub file_id: i64,
    pub line_no: i64,
    pub byte_offset: i64,
    pub end_byte_offset: Option<i64>,
    pub timestamp: NaiveDateTime,
    pub logger: String,
    pub level: String,
    pub thread: String,
    pub message: String,
    pub source: String,
}

/// インデックス保存ディレクトリ `{repo}/tmp/aplv`。
pub fn index_dir(_log_root: &Path) -> PathBuf {
    index_store::tmp_index_dir()
}

/// SQLite DB ファイルパス。
pub fn index_db_path(log_root: &Path) -> PathBuf {
    index_store::index_db_path(log_root)
}

/// DB を開き、スキーマがなければ作成する。
pub fn open_or_create(log_root: &Path) -> rusqlite::Result<Connection> {
    index_store::ensure_tmp_dir_for(Some(log_root));
    let conn = Connection::open(index_db_path(log_root))?;
    conn.execute_batch(
        "
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS files (
            id INTEGER PRIMARY KEY,
            path TEXT NOT NULL UNIQUE,
            mtime_secs INTEGER NOT NULL,
            size INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS entries (
            id INTEGER PRIMARY KEY,
            file_id INTEGER NOT NULL,
            line_no INTEGER NOT NULL,
            byte_offset INTEGER NOT NULL,
            end_byte_offset INTEGER,
            timestamp TEXT NOT NULL,
            logger TEXT NOT NULL,
            level TEXT NOT NULL,
            thread TEXT NOT NULL,
            message TEXT NOT NULL,
            FOREIGN KEY (file_id) REFERENCES files(id)
        );
        CREATE INDEX IF NOT EXISTS idx_entries_ts ON entries(timestamp, file_id, line_no);
        CREATE INDEX IF NOT EXISTS idx_entries_level ON entries(level);
        ",
    )?;
    Ok(conn)
}

/// 対象ファイル集合のフィンガープリント（パス・mtime・サイズ）。
fn file_fingerprint(paths: &[PathBuf]) -> std::io::Result<String> {
    let mut parts: Vec<String> = Vec::with_capacity(paths.len());
    for path in paths {
        let meta = std::fs::metadata(path)?;
        let mtime = meta
            .modified()?
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        parts.push(format!("{}:{}:{}", normalize_path(path), mtime, meta.len()));
    }
    parts.sort();
    Ok(parts.join("\n"))
}

/// 保存済みフィンガープリントと異なれば `true`（再インデックスが必要）。
pub fn needs_rebuild(conn: &Connection, paths: &[PathBuf]) -> rusqlite::Result<bool> {
    if paths.is_empty() {
        return Ok(false);
    }
    let fp = file_fingerprint(paths).map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
    let stored: Option<String> = conn
        .query_row(
            "SELECT value FROM meta WHERE key = 'fingerprint'",
            [],
            |row| row.get(0),
        )
        .optional()?;
    Ok(stored.as_deref() != Some(fp.as_str()))
}

/// entries / files テーブルを空にする。
pub fn clear_index(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute("DELETE FROM entries", [])?;
    conn.execute("DELETE FROM files", [])?;
    Ok(())
}

/// パース済みだが end_byte_offset 未確定のエントリ。
struct PendingEntry {
    line_no: i64,
    byte_offset: u64,
    parsed: ParsedLine,
}

/// ログファイル群を走査し SQLite にインデックスを構築する。
///
/// 同一ファイル内で連続する非ヘッダ行（スタックトレース等）は、
/// 次のヘッダ行の開始 offset までを [`end_byte_offset`] として記録する。
pub fn build_index(
    conn: &Connection,
    paths: &[PathBuf],
    mut on_progress: impl FnMut(u64),
) -> rusqlite::Result<u64> {
    clear_index(conn)?;
    let fp = file_fingerprint(paths).map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;

    let mut total: u64 = 0;
    let tx = conn.unchecked_transaction()?;

    for (file_id, path) in paths.iter().enumerate() {
        let meta = std::fs::metadata(path).map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
        let mtime = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        tx.execute(
            "INSERT INTO files (id, path, mtime_secs, size) VALUES (?1, ?2, ?3, ?4)",
            params![file_id as i64 + 1, normalize_path(path), mtime, meta.len() as i64],
        )?;

        let mut file = File::open(path).map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
        let mut reader = BufReader::new(&mut file);
        let mut line_no: i64 = 0;
        let mut pending: Option<PendingEntry> = None;
        let mut batch = Vec::with_capacity(BATCH_SIZE);

        loop {
            let offset = reader
                .stream_position()
                .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
            let mut line = String::new();
            let bytes = reader.read_line(&mut line).map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
            if bytes == 0 {
                break;
            }
            line_no += 1;
            if line.trim().is_empty() {
                continue;
            }
            if let Some(parsed) = parse_line(&line) {
                if let Some(prev) = pending.take() {
                    flush_entry(&tx, file_id as i64 + 1, prev, offset, &mut batch)?;
                    total += 1;
                    if total % 50_000 == 0 {
                        on_progress(total);
                    }
                }
                pending = Some(PendingEntry {
                    line_no,
                    byte_offset: offset,
                    parsed,
                });
            }
        }

        if let Some(prev) = pending {
            let end = reader
                .stream_position()
                .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
            flush_entry(&tx, file_id as i64 + 1, prev, end, &mut batch)?;
            total += 1;
        }

        flush_batch(&tx, &mut batch)?;
    }

    tx.execute(
        "INSERT OR REPLACE INTO meta (key, value) VALUES ('fingerprint', ?1)",
        params![fp],
    )?;
    tx.commit()?;
    on_progress(total);
    Ok(total)
}

fn flush_entry(
    tx: &rusqlite::Transaction,
    file_id: i64,
    entry: PendingEntry,
    end_offset: u64,
    batch: &mut Vec<(i64, i64, i64, Option<i64>, String, String, String, String, String)>,
) -> rusqlite::Result<()> {
    let ts = entry.parsed.timestamp.format("%Y-%m-%dT%H:%M:%S%.6f").to_string();
    batch.push((
        file_id,
        entry.line_no,
        entry.byte_offset as i64,
        Some(end_offset as i64),
        ts,
        entry.parsed.logger,
        entry.parsed.level,
        entry.parsed.thread,
        entry.parsed.message,
    ));
    if batch.len() >= BATCH_SIZE {
        flush_batch(tx, batch)?;
    }
    Ok(())
}

fn flush_batch(
    tx: &rusqlite::Transaction,
    batch: &mut Vec<(i64, i64, i64, Option<i64>, String, String, String, String, String)>,
) -> rusqlite::Result<()> {
    if batch.is_empty() {
        return Ok(());
    }
    {
        let mut stmt = tx.prepare_cached(
            "INSERT INTO entries (file_id, line_no, byte_offset, end_byte_offset, timestamp, logger, level, thread, message)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        )?;
        for row in batch.drain(..) {
            stmt.execute(params![
                row.0, row.1, row.2, row.3, row.4, row.5, row.6, row.7, row.8
            ])?;
        }
    }
    Ok(())
}

pub fn entry_count(conn: &Connection) -> rusqlite::Result<u64> {
    conn.query_row("SELECT COUNT(*) FROM entries", [], |row| row.get(0))
}

pub fn timestamp_bounds(conn: &Connection) -> rusqlite::Result<(Option<String>, Option<String>)> {
    let first: Option<String> = conn
        .query_row("SELECT timestamp FROM entries ORDER BY timestamp ASC LIMIT 1", [], |r| r.get(0))
        .optional()?;
    let last: Option<String> = conn
        .query_row("SELECT timestamp FROM entries ORDER BY timestamp DESC LIMIT 1", [], |r| r.get(0))
        .optional()?;
    Ok((first, last))
}

/// エントリの生テキスト（スタックトレース含む）を byte 範囲から読み出す。
pub fn read_entry_raw(path: &Path, start: u64, end: Option<u64>) -> std::io::Result<String> {
    let mut file = File::open(path)?;
    file.seek(SeekFrom::Start(start))?;
    let text = if let Some(end) = end {
        let size = end.saturating_sub(start);
        if size == 0 {
            let mut line = String::new();
            file.read_to_string(&mut line)?;
            return Ok(line.trim_end_matches(['\r', '\n']).to_string());
        }
        let mut buf = vec![0u8; size as usize];
        file.read_exact(&mut buf)?;
        String::from_utf8_lossy(&buf).trim_end_matches(['\r', '\n']).to_string()
    } else {
        let mut buf = String::new();
        file.read_to_string(&mut buf)?;
        buf.trim_end_matches(['\r', '\n']).to_string()
    };
    Ok(text)
}

fn row_to_entry(row: &rusqlite::Row<'_>) -> rusqlite::Result<EntryRow> {
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

const SELECT_BASE: &str = "
    SELECT e.id, e.file_id, e.line_no, e.byte_offset, e.end_byte_offset,
           e.timestamp, e.logger, e.level, e.thread, e.message, f.path
    FROM entries e
    JOIN files f ON e.file_id = f.id
";

/// ソースパス・行番号（と任意の timestamp）で 1 件検索。
pub fn find_entry(
    conn: &Connection,
    source: &str,
    line_no: i64,
    timestamp: Option<&str>,
) -> rusqlite::Result<Option<EntryRow>> {
    let sql = if timestamp.is_some() {
        format!("{SELECT_BASE} WHERE f.path = ?1 AND e.line_no = ?2 AND e.timestamp = ?3")
    } else {
        format!("{SELECT_BASE} WHERE f.path = ?1 AND e.line_no = ?2")
    };
    let mut stmt = conn.prepare(&sql)?;
    let row = if let Some(ts) = timestamp {
        stmt.query_row(params![source, line_no, ts], row_to_entry)
    } else {
        stmt.query_row(params![source, line_no], row_to_entry)
    };
    match row {
        Ok(entry) => Ok(Some(entry)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e),
    }
}

pub fn list_file_paths(conn: &Connection) -> rusqlite::Result<Vec<String>> {
    let mut stmt = conn.prepare("SELECT path FROM files ORDER BY id")?;
    let rows = stmt.query_map([], |row| row.get(0))?;
    rows.collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;

    fn write_log(dir: &Path, name: &str, content: &str) -> PathBuf {
        let path = dir.join(name);
        fs::write(&path, content).unwrap();
        path
    }

    #[test]
    fn build_index_with_stack_trace() {
        let tmp = std::env::temp_dir().join("aplv-rs-index-test");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();
        let log = write_log(
            &tmp,
            "app.log",
            "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - failed\n\
             java.lang.RuntimeException: boom\n\
             \tat com.example.Foo.run(Foo.java:10)\n\
             2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - ok\n",
        );

        let conn = open_or_create(&tmp).unwrap();
        let total = build_index(&conn, &[log.clone()], |_| {}).unwrap();
        assert_eq!(total, 2);

        let err = find_entry(&conn, &normalize_path(&log), 1, None)
            .unwrap()
            .unwrap();
        let raw = read_entry_raw(
            &log,
            err.byte_offset as u64,
            err.end_byte_offset.map(|v| v as u64),
        )
        .unwrap();
        assert!(raw.contains("RuntimeException"));
        assert!(raw.contains("Foo.run"));

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn needs_rebuild_after_content_change() {
        let tmp = std::env::temp_dir().join("aplv-rs-rebuild-test");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();
        let log = write_log(
            &tmp,
            "app.log",
            "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n",
        );

        let conn = open_or_create(&tmp).unwrap();
        build_index(&conn, &[log.clone()], |_| {}).unwrap();
        assert!(!needs_rebuild(&conn, &[log.clone()]).unwrap());

        fs::OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all(b"2026-06-15 00:00:02.000[main][INFO][com.example.B] - two\n")
            .unwrap();
        assert!(needs_rebuild(&conn, &[log]).unwrap());

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn stored_paths_are_normalized() {
        let tmp = std::env::temp_dir().join("aplv-rs-path-test");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();
        let log = write_log(
            &tmp,
            "app.log",
            "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n",
        );

        let conn = open_or_create(&tmp).unwrap();
        build_index(&conn, &[log], |_| {}).unwrap();
        let paths = list_file_paths(&conn).unwrap();
        assert_eq!(paths.len(), 1);
        assert!(!paths[0].starts_with(r"\\?\"));

        let _ = fs::remove_dir_all(&tmp);
    }
}
