"""filters モジュールのテスト."""

from __future__ import annotations

import re
from datetime import datetime, timezone

import pytest

from aplv.filters import match_entry, parse_datetime, parse_level_filter
from aplv.parser import parse_line


def _entry(line: str):
    entry = parse_line(line)
    assert entry is not None
    return entry


def test_parse_level_filter() -> None:
    assert parse_level_filter("") is None
    assert parse_level_filter("error,warn") == {"ERROR", "WARN"}


def test_parse_datetime() -> None:
    dt = parse_datetime("2026-06-15 00:19:11.705")
    assert dt == datetime(2026, 6, 15, 0, 19, 11, 705000, tzinfo=timezone.utc)

    with pytest.raises(ValueError, match="日時形式"):
        parse_datetime("invalid")


def test_match_entry_level_and_logger() -> None:
    entry = _entry(
        "2026-06-15 00:19:11.705[main][ERROR][com.example.Foo] - boom"
    )
    assert match_entry(entry, level={"ERROR"})
    assert not match_entry(entry, level={"INFO"})
    assert match_entry(entry, logger=re.compile(r"example\.Foo"))
    assert not match_entry(entry, logger=re.compile(r"Bar"))


def test_match_entry_datetime_range() -> None:
    entry = _entry(
        "2026-06-15 00:19:11.705[main][INFO][com.example.Foo] - ok"
    )
    since = datetime(2026, 6, 15, 0, 19, 0, tzinfo=timezone.utc)
    until = datetime(2026, 6, 15, 0, 20, 0, tzinfo=timezone.utc)
    assert match_entry(entry, since=since, until=until)
    assert not match_entry(
        entry, since=datetime(2026, 6, 15, 0, 20, 0, tzinfo=timezone.utc)
    )


def test_match_entry_grep_requires_read_raw() -> None:
    entry = _entry(
        "2026-06-15 00:19:11.705[main][ERROR][com.example.Foo] - boom"
    )
    grep = re.compile("stacktrace")
    assert not match_entry(entry, query=grep)
    assert match_entry(
        entry,
        query=grep,
        read_raw=lambda e: "full text with stacktrace detail",
    )
