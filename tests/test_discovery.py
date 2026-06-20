"""discovery モジュールのテスト."""

from __future__ import annotations

from pathlib import Path

from aplv.discovery import find_log_files, is_log_file


def test_is_log_file() -> None:
    assert is_log_file("application.log")
    assert is_log_file("catalina.out")
    assert is_log_file("server.log.1")
    assert not is_log_file("application.log.gz")
    assert not is_log_file("readme.txt")


def test_find_log_files(tmp_path: Path) -> None:
    (tmp_path / "application.log").write_text("log", encoding="utf-8")
    (tmp_path / "notes.txt").write_text("note", encoding="utf-8")
    (tmp_path / "archived.log.gz").write_text("gz", encoding="utf-8")

    nested = tmp_path / "subdir"
    nested.mkdir()
    (nested / "server.log").write_text("log", encoding="utf-8")

    skip = tmp_path / ".git" / "objects"
    skip.mkdir(parents=True)
    (skip / "fake.log").write_text("skip", encoding="utf-8")

    found = find_log_files(tmp_path)
    names = {p.name for p in found}
    assert names == {"application.log", "server.log"}
    assert all(p.is_absolute() for p in found)
