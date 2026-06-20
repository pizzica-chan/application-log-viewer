//! モジュール横断の結合テスト。

use aplv_rs::discovery;
use aplv_rs::index;
use aplv_rs::parser;
use aplv_rs::path_util::normalize_path;
use aplv_rs::query::{self, QueryFilter};
use std::path::PathBuf;

fn sample_log_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../samples/application.log")
}

fn sample_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../samples")
}

#[test]
fn parse_sample_log_lines() {
    assert!(parser::parse_line(
        "2026-06-15 00:19:11.705[ajp-nio-8009-exec-24][TRACE][org.hogehoge.jdbc.HogeUtil] - body"
    )
    .is_some());
    assert!(parser::parse_line(
        "2026-05-27 00:00:03.965[HogeController][ERROR][main] - legacy"
    )
    .is_some());
}

#[test]
fn index_sample_log() {
    let sample = sample_log_path();
    let tmp = std::env::temp_dir().join("aplv-rs-integration");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).unwrap();

    let conn = index::open_or_create(&tmp).unwrap();
    let paths = vec![sample];
    let total = index::build_index(&conn, &paths, |_| {}).unwrap();
    assert!(total >= 6);
    assert_eq!(index::entry_count(&conn).unwrap(), total);
    assert!(!index::needs_rebuild(&conn, &paths).unwrap());

    let paths_in_db = index::list_file_paths(&conn).unwrap();
    assert_eq!(paths_in_db.len(), 1);
    assert!(!paths_in_db[0].starts_with(r"\\?\"));
    assert!(paths_in_db[0].ends_with("application.log"));

    let _ = std::fs::remove_dir_all(&tmp);
}

#[test]
fn discovery_finds_sample_application_log() {
    let files = discovery::find_log_files(&sample_root()).unwrap();
    assert!(files.iter().any(|p| p.ends_with("application.log")));
}

#[test]
fn query_error_entries_from_sample_index() {
    let sample = sample_log_path();
    let tmp = std::env::temp_dir().join("aplv-rs-query-integration");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).unwrap();

    let conn = index::open_or_create(&tmp).unwrap();
    index::build_index(&conn, &[sample], |_| {}).unwrap();

    let filter = QueryFilter {
        levels: query::parse_level_filter("ERROR"),
        logger_re: None,
        thread_re: None,
        message_re: None,
        source_re: None,
        grep_re: None,
        since: None,
        until: None,
    };
    let (total, page) = query::query_logs(&conn, &filter, 0, 100).unwrap();
    assert!(total >= 2);
    assert!(page.iter().all(|e| e.level == "ERROR"));

    let _ = std::fs::remove_dir_all(&tmp);
}

#[test]
fn stack_trace_readable_via_grep() {
    let sample = sample_log_path();
    let tmp = std::env::temp_dir().join("aplv-rs-grep-integration");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).unwrap();

    let conn = index::open_or_create(&tmp).unwrap();
    index::build_index(&conn, &[sample], |_| {}).unwrap();

    let filter = QueryFilter {
        levels: None,
        logger_re: None,
        thread_re: None,
        message_re: None,
        source_re: None,
        grep_re: query::compile_regex("NullPointerException").ok(),
        since: None,
        until: None,
    };
    let (total, page) = query::query_logs(&conn, &filter, 0, 10).unwrap();
    assert_eq!(total, 1);
    assert!(page[0].message.contains("失敗") || page[0].level == "ERROR");

    let _ = std::fs::remove_dir_all(&tmp);
}

#[test]
fn normalize_path_strips_verbatim_on_canonical() {
    let root = sample_root().canonicalize().unwrap();
    let normalized = normalize_path(&root);
    assert!(!normalized.starts_with(r"\\?\"));
    assert!(normalized.ends_with("samples"));
}
