"""parser モジュールのテスト."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

import pytest

from aplv.line_reader import LineReader
from aplv.parser import is_log_header, load_entries, parse_line, parse_timestamp


SAMPLES = Path(__file__).resolve().parent.parent / "samples" / "application.log"


def test_parse_timestamp() -> None:
    dt = parse_timestamp("2026-06-15 00:19:11.705")
    assert dt == datetime(2026, 6, 15, 0, 19, 11, 705000)


def test_parse_line_tomcat_format() -> None:
    line = (
        "2026-06-15 00:19:11.705[ajp-nio-8009-exec-24][TRACE]"
        "[org.hogehoge.jdbc.HogeUtil] - ログ本文"
    )
    entry = parse_line(line)
    assert entry is not None
    assert entry.thread == "ajp-nio-8009-exec-24"
    assert entry.level == "TRACE"
    assert entry.logger == "org.hogehoge.jdbc.HogeUtil"
    assert entry.message == "ログ本文"


def test_parse_line_legacy_format() -> None:
    line = "2026-05-27 00:00:03.965[HogeController][ERROR][main] - エラー発生"
    entry = parse_line(line)
    assert entry is not None
    assert entry.logger == "HogeController"
    assert entry.level == "ERROR"
    assert entry.thread == "main"
    assert entry.message == "エラー発生"


def test_parse_line_invalid() -> None:
    assert parse_line("not a log line") is None
    assert parse_line("2026-06-15 00:19:11.705[a][UNKNOWN][b] - msg") is None


def test_is_log_header() -> None:
    assert is_log_header("[main][INFO][com.example.Foo] - x") is False
    assert is_log_header("2026-06-15 00:19:11.705[main][INFO][com.example.Foo] - x")


def test_load_entries_sample_file() -> None:
    entries = load_entries([SAMPLES])
    assert len(entries) == 7

    error = next(e for e in entries if e.message == "処理に失敗しました")
    assert error.message == "処理に失敗しました"
    assert error.end_byte_offset is not None
    assert error.end_byte_offset > error.byte_offset


def test_load_entries_includes_stack_trace_in_range() -> None:
    entries = load_entries([SAMPLES])
    error = next(e for e in entries if e.message == "処理に失敗しました")

    with LineReader([SAMPLES]) as reader:
        raw = reader.read_range(error.file_id, error.byte_offset, error.end_byte_offset)

    assert "NullPointerException" in raw
    assert "HogeController.process" in raw


def test_load_entries_multi_file_sort(tmp_path: Path) -> None:
    early = tmp_path / "a.log"
    late = tmp_path / "b.log"
    early.write_text(
        "2026-06-15 00:10:00.000[main][INFO][com.example.A] - early\n",
        encoding="utf-8",
    )
    late.write_text(
        "2026-06-15 00:20:00.000[main][INFO][com.example.B] - late\n",
        encoding="utf-8",
    )

    entries = load_entries([late, early], sort=True)
    assert [e.message for e in entries] == ["early", "late"]


def test_to_row_dict() -> None:
    entry = parse_line(
        "2026-06-15 00:19:11.705[main][INFO][com.example.Foo] - hello"
    )
    assert entry is not None
    row = entry.to_row_dict("/var/log/app.log")
    assert row["source"] == "/var/log/app.log"
    assert row["level"] == "INFO"
    assert row["logger"] == "com.example.Foo"
