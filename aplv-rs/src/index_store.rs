//! SQLite インデックスファイルの保存場所とクリーンアップ。

use crate::path_util::normalize_path;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

const DEFAULT_MAX_AGE_SECS: u64 = 7 * 24 * 3600;
const DEFAULT_MAX_COUNT: usize = 20;

static CLEANUP_DONE: AtomicBool = AtomicBool::new(false);

pub fn repo_root() -> PathBuf {
    if let Ok(home) = std::env::var("APLV_HOME") {
        return PathBuf::from(home);
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(".."))
}

pub fn tmp_index_dir() -> PathBuf {
    repo_root().join("tmp").join("aplv")
}

pub fn log_root_key(log_root: &Path) -> String {
    let normalized = normalize_path(log_root);
    let mut hasher = Sha256::new();
    hasher.update(normalized.as_bytes());
    format!("{:x}", hasher.finalize())
}

pub fn index_db_path(log_root: &Path) -> PathBuf {
    tmp_index_dir().join(format!("{}.db", log_root_key(log_root)))
}

fn sidecar_paths(base: &Path) -> [PathBuf; 4] {
    let s = base.to_string_lossy();
    [
        base.to_path_buf(),
        PathBuf::from(format!("{s}-wal")),
        PathBuf::from(format!("{s}-shm")),
        PathBuf::from(format!("{s}-journal")),
    ]
}

pub fn delete_index_files(log_root: &Path) {
    delete_by_path(&index_db_path(log_root));
}

fn delete_by_path(base: &Path) {
    for p in sidecar_paths(base) {
        let _ = std::fs::remove_file(p);
    }
}

fn list_db_files(index_dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(index_dir)
        .into_iter()
        .flatten()
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|ext| ext == "db"))
        .filter(|p| p.is_file())
        .collect()
}

fn mtime_secs(path: &Path) -> u64 {
    path.metadata()
        .ok()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn cleanup_stale_indexes(active_log_root: Option<&Path>) {
    let index_dir = tmp_index_dir();
    if !index_dir.is_dir() {
        return;
    }

    let active_key = active_log_root.map(|p| log_root_key(p));
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    for db in list_db_files(&index_dir) {
        let stem = db.file_stem().and_then(|s| s.to_str()).unwrap_or("");
        if active_key.as_deref() == Some(stem) {
            continue;
        }
        if now.saturating_sub(mtime_secs(&db)) > DEFAULT_MAX_AGE_SECS {
            delete_by_path(&db);
        }
    }

    let db_files = list_db_files(&index_dir);
    let mut active_db: Option<PathBuf> = None;
    let mut others: Vec<PathBuf> = Vec::new();
    for db in db_files {
        let stem = db.file_stem().and_then(|s| s.to_str()).unwrap_or("");
        if active_key.as_deref() == Some(stem) {
            active_db = Some(db);
        } else {
            others.push(db);
        }
    }
    others.sort_by_key(|p| mtime_secs(p));
    others.reverse();

    let limit = if active_db.is_some() {
        DEFAULT_MAX_COUNT.saturating_sub(1)
    } else {
        DEFAULT_MAX_COUNT
    };
    for db in others.into_iter().skip(limit) {
        delete_by_path(&db);
    }
}

pub fn ensure_tmp_dir_for(log_root: Option<&Path>) {
    let dir = tmp_index_dir();
    let _ = std::fs::create_dir_all(&dir);
    if !CLEANUP_DONE.swap(true, Ordering::SeqCst) {
        cleanup_stale_indexes(log_root);
    } else {
        cleanup_stale_indexes(log_root);
    }
}
