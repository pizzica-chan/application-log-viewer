//! パス表示の正規化ユーティリティ。
//!
//! Windows では [`std::path::Path::canonicalize`] が `\\?\` プレフィックス付きの
//! extended-length path を返す。UI / SQLite 保存時は通常のパス表記に戻す。

use std::path::{Path, PathBuf};

/// 文字列パスから `\\?\` / `\\?\UNC\` プレフィックスを除去する。
pub fn normalize_path_str(path: &str) -> String {
    let s = path.to_string();
    if let Some(rest) = s.strip_prefix(r"\\?\") {
        if let Some(unc) = rest.strip_prefix("UNC\\") {
            return format!(r"\\{unc}");
        }
        return rest.to_string();
    }
    s
}

/// [`Path`] を表示用文字列に変換する。
pub fn normalize_path(path: &Path) -> String {
    normalize_path_str(&path.to_string_lossy())
}

/// 正規化した [`PathBuf`] を返す。
pub fn normalize_path_buf(path: PathBuf) -> PathBuf {
    PathBuf::from(normalize_path(&path))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn strips_verbatim_prefix() {
        assert_eq!(
            normalize_path_str(r"\\?\D:\workspace\samples"),
            r"D:\workspace\samples"
        );
    }

    #[test]
    fn strips_unc_verbatim_prefix() {
        assert_eq!(
            normalize_path_str(r"\\?\UNC\server\share\logs"),
            r"\\server\share\logs"
        );
    }

    #[test]
    fn normal_path_unchanged() {
        assert_eq!(
            normalize_path_str(r"D:\workspace\samples"),
            r"D:\workspace\samples"
        );
    }

    #[test]
    fn normalize_path_from_path_buf() {
        let p = PathBuf::from(r"\\?\D:\foo\bar.log");
        assert_eq!(normalize_path(&p), r"D:\foo\bar.log");
    }
}
