"""SQLite インデックスファイルの保存場所とクリーンアップ."""

from __future__ import annotations

import hashlib
import os
import time
from pathlib import Path

from .path_util import normalize_path

_REPO_ROOT = Path(__file__).resolve().parent.parent

DEFAULT_MAX_AGE_SECS = 7 * 24 * 3600
DEFAULT_MAX_COUNT = 20

_cleanup_done = False


def repo_root() -> Path:
    override = os.environ.get("APLV_HOME")
    if override:
        return Path(override).expanduser().resolve()
    return _REPO_ROOT


def tmp_index_dir() -> Path:
    return repo_root() / "tmp" / "aplv"


def log_root_key(log_root: Path) -> str:
    normalized = normalize_path(log_root.resolve())
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def index_db_path(log_root: Path) -> Path:
    return tmp_index_dir() / f"{log_root_key(log_root)}.db"


def delete_index_files(log_root: Path) -> None:
    base = index_db_path(log_root)
    for suffix in ("", "-wal", "-shm", "-journal"):
        path = Path(str(base) + suffix) if suffix else base
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass


def _list_db_files(index_dir: Path) -> list[Path]:
    if not index_dir.is_dir():
        return []
    return sorted(p for p in index_dir.glob("*.db") if p.is_file())


def cleanup_stale_indexes(
    *,
    active_log_root: Path | None = None,
    max_age_secs: int = DEFAULT_MAX_AGE_SECS,
    max_count: int = DEFAULT_MAX_COUNT,
) -> None:
    index_dir = tmp_index_dir()
    if not index_dir.is_dir():
        return

    active_key = log_root_key(active_log_root) if active_log_root else None
    now = time.time()

    for db in _list_db_files(index_dir):
        if active_key and db.stem == active_key:
            continue
        try:
            if now - db.stat().st_mtime > max_age_secs:
                delete_index_files_by_path(db)
        except OSError:
            pass

    db_files = _list_db_files(index_dir)
    active_db = None
    others: list[Path] = []
    for db in db_files:
        if active_key and db.stem == active_key:
            active_db = db
        else:
            others.append(db)

    others.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    limit = max_count - 1 if active_db else max_count
    for db in others[limit:]:
        delete_index_files_by_path(db)


def delete_index_files_by_path(db_path: Path) -> None:
    for suffix in ("", "-wal", "-shm", "-journal"):
        path = Path(str(db_path) + suffix) if suffix else db_path
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass


def ensure_tmp_dir_for(log_root: Path) -> Path:
    global _cleanup_done
    index_dir = tmp_index_dir()
    index_dir.mkdir(parents=True, exist_ok=True)
    if not _cleanup_done:
        cleanup_stale_indexes(active_log_root=log_root)
        _cleanup_done = True
    else:
        cleanup_stale_indexes(active_log_root=log_root)
    return index_dir
