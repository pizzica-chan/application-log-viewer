//! ログファイルの再帰探索。
//!
//! Python 版 [`aplv.discovery`] と同じファイル名パターン・スキップディレクトリに対応。

use std::path::{Path, PathBuf};

/// `find_log_files` が対象とするファイル名 glob（小文字比較）。
const LOG_FILE_PATTERNS: &[&str] = &[
    "application*.log*",
    "server*.log*",
    "spring*.log*",
    "catalina*.log*",
    "localhost*.log*",
    "app*.log*",
    "*.log",
    "*.out",
];

/// 探索をスキップするディレクトリ名（パス成分のいずれかに一致したら除外）。
const SKIP_DIR_NAMES: &[&str] = &[
    ".git",
    "__pycache__",
    "node_modules",
    ".venv",
    "venv",
    ".tox",
    ".mypy_cache",
    ".pytest_cache",
    ".aplv",
];

/// ファイル名がログファイルパターンに一致するか（圧縮ファイルは除外）。
pub fn is_log_file(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    if lower.ends_with(".gz") || lower.ends_with(".bz2") || lower.ends_with(".xz") {
        return false;
    }
    LOG_FILE_PATTERNS
        .iter()
        .any(|pat| glob_match(pat, &lower))
}

/// 簡易 glob マッチ（`*` と `?` のみ。Python `fnmatch` 相当）。
fn glob_match(pattern: &str, name: &str) -> bool {
    glob_match_bytes(pattern.as_bytes(), name.as_bytes())
}

fn glob_match_bytes(pat: &[u8], name: &[u8]) -> bool {
    if pat.is_empty() {
        return name.is_empty();
    }
    if pat[0] == b'*' {
        if pat.len() == 1 {
            return true;
        }
        for i in 0..=name.len() {
            if glob_match_bytes(&pat[1..], &name[i..]) {
                return true;
            }
        }
        return false;
    }
    if name.is_empty() {
        return false;
    }
    if pat[0] == b'?' || pat[0] == name[0] {
        return glob_match_bytes(&pat[1..], &name[1..]);
    }
    false
}

fn should_skip(path: &Path) -> bool {
    path.components().any(|c| {
        c.as_os_str()
            .to_str()
            .is_some_and(|part| SKIP_DIR_NAMES.contains(&part))
    })
}

/// 指定ディレクトリ配下を再帰探索し、ログファイルのパス一覧を返す。
pub fn find_log_files(root: &Path) -> std::io::Result<Vec<PathBuf>> {
    let root = root.canonicalize()?;
    if !root.is_dir() {
        return Ok(Vec::new());
    }

    let mut found = Vec::new();
    for entry in walkdir_paths(&root)? {
        if should_skip(&entry) {
            continue;
        }
        if !entry.is_file() {
            continue;
        }
        let name = entry.file_name().and_then(|n| n.to_str()).unwrap_or("");
        if is_log_file(name) {
            found.push(entry);
        }
    }
    found.sort();
    Ok(found)
}

/// スタックベースの深さ優先走査（追加依存なし）。
fn walkdir_paths(root: &Path) -> std::io::Result<Vec<PathBuf>> {
    let mut stack = vec![root.to_path_buf()];
    let mut files = Vec::new();
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_dir() {
                if should_skip(&path) {
                    continue;
                }
                stack.push(path);
            } else {
                files.push(path);
            }
        }
    }
    Ok(files)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn log_file_patterns() {
        assert!(is_log_file("application.log"));
        assert!(is_log_file("application.log.1"));
        assert!(is_log_file("server.log"));
        assert!(is_log_file("catalina.out"));
        assert!(!is_log_file("application.log.gz"));
        assert!(!is_log_file("readme.txt"));
    }

    #[test]
    fn glob_wildcards() {
        assert!(glob_match("*.log", "application.log"));
        assert!(glob_match("application*.log*", "application.log.2"));
        assert!(!glob_match("*.out", "application.log"));
    }

    #[test]
    fn find_skips_git_and_aplv() {
        let tmp = std::env::temp_dir().join("aplv-rs-discovery-test");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(tmp.join(".git").join("objects")).unwrap();
        fs::create_dir_all(tmp.join(".aplv")).unwrap();
        fs::create_dir_all(tmp.join("logs")).unwrap();
        fs::write(tmp.join(".git/objects/fake.log"), "x").unwrap();
        fs::write(tmp.join(".aplv/index.db"), "x").unwrap();
        fs::write(tmp.join("logs/app.log"), "log").unwrap();

        let found = find_log_files(&tmp).unwrap();
        assert_eq!(found.len(), 1);
        assert!(found[0].ends_with("app.log"));

        let _ = fs::remove_dir_all(&tmp);
    }
}
