"""index_store モジュールのテスト."""

from __future__ import annotations

import os
import time
from pathlib import Path

from aplv import index_store


def test_index_db_path_is_under_repo_tmp(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("APLV_HOME", str(tmp_path))
    log_root = tmp_path / "logs" / "app"
    log_root.mkdir(parents=True)
    db = index_store.index_db_path(log_root)
    assert db.parent == tmp_path / "tmp" / "aplv"
    assert db.suffix == ".db"
    assert db.stem == index_store.log_root_key(log_root)


def test_delete_and_cleanup_stale(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("APLV_HOME", str(tmp_path))
    index_store._cleanup_done = False  # noqa: SLF001
    index_store.ensure_tmp_dir_for(tmp_path / "active")
    old = index_store.tmp_index_dir() / "olddeadbeef.db"
    old.write_text("x", encoding="utf-8")
    old_time = time.time() - 10 * 86400
    os.utime(old, (old_time, old_time))

    index_store.cleanup_stale_indexes(
        active_log_root=tmp_path / "active",
        max_age_secs=86400,
        max_count=5,
    )
    assert not old.exists()
