"""ログファイルの再帰探索."""

from __future__ import annotations

import fnmatch
from pathlib import Path

LOG_FILE_PATTERNS = (
    "application*.log*",
    "server*.log*",
    "spring*.log*",
    "catalina*.log*",
    "localhost*.log*",
    "app*.log*",
    "*.log",
    "*.out",
)

SKIP_DIR_NAMES = frozenset(
    {
        ".git",
        "__pycache__",
        "node_modules",
        ".venv",
        "venv",
        ".tox",
        ".mypy_cache",
        ".pytest_cache",
    }
)


def is_log_file(name: str) -> bool:
    lower = name.lower()
    if lower.endswith(".gz") or lower.endswith(".bz2") or lower.endswith(".xz"):
        return False
    return any(fnmatch.fnmatch(lower, pat.lower()) for pat in LOG_FILE_PATTERNS)


def find_log_files(root: Path) -> list[Path]:
    """指定ディレクトリ配下を再帰的に探索し、ログファイルのフルパス一覧を返す."""
    root = root.resolve()
    if not root.is_dir():
        return []

    found: set[Path] = set()
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIP_DIR_NAMES for part in path.parts):
            continue
        if is_log_file(path.name):
            found.add(path.resolve())
    return sorted(found)
