"""query モジュールのテスト."""

from __future__ import annotations

import re
from pathlib import Path

from aplv.index import build_index, open_or_create
from aplv.query import (
    QueryFilter,
    build_query_filter,
    parse_datetime,
    query_logs,
)


def _sample_log(tmp_path: Path) -> Path:
    log = tmp_path / "app.log"
    log.write_text(
        "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - error one\n"
        "stack line\n"
        "2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - info two\n",
        encoding="utf-8",
    )
    return log


def test_parse_datetime_accepts_space_and_t() -> None:
    assert parse_datetime("2026-06-15 00:00:01.000")
    assert parse_datetime("2026-06-15T00:00:01.000")


def test_query_filters_by_level_and_grep(tmp_path: Path) -> None:
    log = _sample_log(tmp_path)
    conn = open_or_create(tmp_path)
    build_index(conn, [log])

    filt = QueryFilter(levels={"ERROR"})
    total, page = query_logs(conn, filt, 0, 10)
    assert total == 1
    assert page[0].level == "ERROR"

    filt = QueryFilter(grep_re=re.compile("stack line", re.IGNORECASE))
    total, _ = query_logs(conn, filt, 0, 10)
    assert total == 1


def test_build_query_filter() -> None:
    filt = build_query_filter(level="error,warn", logger_pat="Foo")
    assert filt.levels == {"ERROR", "WARN"}
    assert filt.logger_re is not None
    assert filt.logger_re.search("com.example.Foo")
