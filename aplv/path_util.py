"""パス表示の正規化ユーティリティ."""

from __future__ import annotations

from pathlib import Path


def normalize_path_str(path: str) -> str:
    """Windows の `\\\\?\\` / `\\\\?\\UNC\\` プレフィックスを除去する."""
    if path.startswith("\\\\?\\"):
        rest = path[4:]
        if rest.startswith("UNC\\"):
            return "\\\\" + rest[4:]
        return rest
    return path


def normalize_path(path: Path) -> str:
    return normalize_path_str(str(path.resolve()))
