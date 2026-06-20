//! Java アプリケーションログ行のパーサー。
//!
//! 対応形式（いずれも LEVEL は 2 番目の `[]`）:
//! - Tomcat: `YYYY-MM-DD HH:MM:SS.mmm[Thread][LEVEL][Logger(FQCN)] - Message`
//! - その他: `YYYY-MM-DD HH:MM:SS.mmm[Logger][LEVEL][Thread] - Message`
//!
//! スタックトレース等の続き行はパース対象外（[`index`] 側で byte 範囲として保持）。

use chrono::NaiveDateTime;
use regex::Regex;
use std::collections::HashSet;
use std::sync::OnceLock;

/// 2 番目の [] を LEVEL として認識するために許可する文字列。
const KNOWN_LEVELS: &[&str] = &[
    "TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "SEVERE",
];

fn log_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\[(?P<field1>[^\]]*)\]\[(?P<field2>[^\]]*)\]\[(?P<field3>[^\]]*)\] - (?P<message>.*)$")
            .expect("log regex")
    })
}

/// スレッド名らしきパターン（Tomcat / 旧形式の判別に使用）。
fn thread_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(
            r"(?i)(?:^main$|exec-\d+|pool-\d+-thread-\d+|scheduler-\d+|ajp-|http-nio-|catalina-)",
        )
        .expect("thread regex")
    })
}

fn known_levels() -> &'static HashSet<&'static str> {
    static SET: OnceLock<HashSet<&'static str>> = OnceLock::new();
    SET.get_or_init(|| KNOWN_LEVELS.iter().copied().collect())
}

/// 1 行分の解析結果（エントリ先頭行）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedLine {
    pub timestamp: NaiveDateTime,
    pub logger: String,
    pub level: String,
    pub thread: String,
    pub message: String,
}

/// `2026-05-27 00:00:03.965` 形式のタイムスタンプを解析する。
pub fn parse_timestamp(value: &str) -> Option<NaiveDateTime> {
    NaiveDateTime::parse_from_str(value, "%Y-%m-%d %H:%M:%S%.3f").ok()
}

/// field1/2/3 から (logger, level, thread) を推定する。
///
/// field2 は常に LEVEL。FQCN（`.` 含む）やスレッド名ヒントで Tomcat / 旧形式を判別。
fn assign_fields(field1: &str, field2: &str, field3: &str) -> Option<(String, String, String)> {
    let level = field2.to_ascii_uppercase();
    if !known_levels().contains(level.as_str()) {
        return None;
    }

    // Tomcat: [Thread][LEVEL][Logger(FQCN)]
    if field3.contains('.') {
        return Some((field3.to_string(), level, field1.to_string()));
    }
    // 旧形式: [Logger(FQCN)][LEVEL][Thread]
    if field1.contains('.') {
        return Some((field1.to_string(), level, field3.to_string()));
    }
    if thread_re().is_match(field1) {
        return Some((field3.to_string(), level, field1.to_string()));
    }
    if thread_re().is_match(field3) {
        return Some((field1.to_string(), level, field3.to_string()));
    }
    // 判別不能時は Tomcat 形式を優先
    Some((field3.to_string(), level, field1.to_string()))
}

/// ログ行 1 行を解析する。ヘッダ行に一致しない場合は `None`。
pub fn parse_line(line: &str) -> Option<ParsedLine> {
    let line = line.trim_end_matches(['\r', '\n']);
    let caps = log_re().captures(line)?;
    let timestamp = parse_timestamp(caps.name("timestamp")?.as_str())?;
    let field1 = caps.name("field1")?.as_str();
    let field2 = caps.name("field2")?.as_str();
    let field3 = caps.name("field3")?.as_str();
    let message = caps.name("message")?.as_str().to_string();
    let (logger, level, thread) = assign_fields(field1, field2, field3)?;
    Some(ParsedLine {
        timestamp,
        logger,
        level,
        thread,
        message,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_timestamp_ok() {
        let dt = parse_timestamp("2026-06-15 00:19:11.705").unwrap();
        assert_eq!(dt.format("%Y-%m-%d %H:%M:%S%.3f").to_string(), "2026-06-15 00:19:11.705");
    }

    #[test]
    fn tomcat_format() {
        let p = parse_line(
            "2026-06-15 00:19:11.705[ajp-nio-8009-exec-24][TRACE][org.hogehoge.jdbc.HogeUtil] - body",
        )
        .unwrap();
        assert_eq!(p.thread, "ajp-nio-8009-exec-24");
        assert_eq!(p.level, "TRACE");
        assert_eq!(p.logger, "org.hogehoge.jdbc.HogeUtil");
        assert_eq!(p.message, "body");
    }

    #[test]
    fn legacy_format() {
        let p = parse_line("2026-05-27 00:00:03.965[HogeController][ERROR][main] - msg").unwrap();
        assert_eq!(p.logger, "HogeController");
        assert_eq!(p.level, "ERROR");
        assert_eq!(p.thread, "main");
    }

    #[test]
    fn rejects_unknown_level() {
        assert!(parse_line("2026-06-15 00:00:00.000[a][UNKNOWN][b] - x").is_none());
    }

    #[test]
    fn rejects_non_log_line() {
        assert!(parse_line("java.lang.NullPointerException").is_none());
        assert!(parse_line("").is_none());
    }

    #[test]
    fn fqcn_logger_in_legacy_position() {
        let p = parse_line(
            "2026-06-15 00:00:00.000[com.example.Foo][WARN][main] - warn",
        )
        .unwrap();
        assert_eq!(p.logger, "com.example.Foo");
        assert_eq!(p.thread, "main");
    }
}
