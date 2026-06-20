"""index モジュールのテスト."""

from __future__ import annotations

from pathlib import Path

from aplv.index import (
    build_index,
    find_entry,
    list_file_paths,
    needs_rebuild,
    open_or_create,
    read_entry_raw,
)
from aplv.path_util import normalize_path


def test_build_index_with_stack_trace(tmp_path: Path) -> None:
    log = tmp_path / "app.log"
    log.write_text(
        "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - failed\n"
        "java.lang.RuntimeException: boom\n"
        "\tat com.example.Foo.run(Foo.java:10)\n"
        "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - ok\n",
        encoding="utf-8",
    )

    conn = open_or_create(tmp_path)
    total = build_index(conn, [log])
    assert total == 2

    err = find_entry(conn, normalize_path(log), 1)
    assert err is not None
    raw = read_entry_raw(log, err.byte_offset, err.end_byte_offset)
    assert "RuntimeException" in raw
    assert "Foo.run" in raw


def test_needs_rebuild_after_content_change(tmp_path: Path) -> None:
    log = tmp_path / "app.log"
    log.write_text(
        "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n",
        encoding="utf-8",
    )

    conn = open_or_create(tmp_path)
    build_index(conn, [log])
    assert not needs_rebuild(conn, [log])

    with log.open("a", encoding="utf-8") as fh:
        fh.write("2026-06-15 00:00:02.000[main][INFO][com.example.B] - two\n")
    assert needs_rebuild(conn, [log])


def test_stored_paths_are_normalized(tmp_path: Path) -> None:
    log = tmp_path / "app.log"
    log.write_text(
        "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n",
        encoding="utf-8",
    )

    conn = open_or_create(tmp_path)
    build_index(conn, [log])
    paths = list_file_paths(conn)
    assert len(paths) == 1
    assert not paths[0].startswith("\\\\?\\")
