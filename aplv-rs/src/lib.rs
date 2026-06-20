//! Application Log Viewer — Rust + SQLite 実装。
//!
//! Python 版 (`aplv`) と API 互換の Web UI を提供する。
//! ログ解析結果は `{log_root}/.aplv/index.db` に保存し、大容量ログでも
//! メモリに全件載せず検索できる。

pub mod discovery;
pub mod index;
pub mod parser;
pub mod path_util;
pub mod query;
pub mod web;
