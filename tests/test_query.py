"""query モジュールのテスト."""

from __future__ import annotations

import re
from pathlib import Path

from aplv.index import build_index, fts_available, open_or_create
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

    # logger + grep: Bar 行は stack を含むが logger 不一致のため除外
    filt = QueryFilter(
        levels={"ERROR", "INFO"},
        logger_re=re.compile("Foo", re.IGNORECASE),
        grep_re=re.compile("stack line", re.IGNORECASE),
    )
    total, page = query_logs(conn, filt, 0, 10)
    assert total == 1
    assert page[0].logger == "com.example.Foo"


def test_build_query_filter() -> None:
    filt = build_query_filter(level="error,warn", logger_pat="Foo")
    assert filt.levels == {"ERROR", "WARN"}
    assert filt.logger_re is not None
    assert filt.logger_re.search("com.example.Foo")


def _stacktrace_log(tmp_path: Path) -> Path:
    log = tmp_path / "app.log"
    log.write_text(
        "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - boom\n"
        "java.lang.NullPointerException: bad\n"
        "\tat com.example.Foo.run(Foo.java:10)\n"
        "2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - ok\n",
        encoding="utf-8",
    )
    return log


def test_fts_table_created_on_build(tmp_path: Path) -> None:
    log = _sample_log(tmp_path)
    conn = open_or_create(tmp_path)
    build_index(conn, [log])
    assert fts_available(conn)


def test_grep_uses_fts_and_matches_stacktrace(tmp_path: Path) -> None:
    log = _stacktrace_log(tmp_path)
    conn = open_or_create(tmp_path)
    build_index(conn, [log])

    # スタックトレース本文に対する全文検索（FTS 経由）。
    filt = build_query_filter(grep="NullPointerException")
    assert filt.grep_text == "NullPointerException"
    total, page = query_logs(conn, filt, 0, 10)
    assert total == 1
    assert page[0].logger == "com.example.Foo"


def test_grep_fts_result_identical_to_scan(tmp_path: Path) -> None:
    """FTS あり（build_query_filter）と素朴な正規表現スキャンで結果が一致する。"""
    log = _stacktrace_log(tmp_path)
    conn = open_or_create(tmp_path)
    build_index(conn, [log])

    fts_total, _ = query_logs(conn, build_query_filter(grep="Foo.run"), 0, 50)
    scan_total, _ = query_logs(
        conn, QueryFilter(grep_re=re.compile("Foo.run", re.IGNORECASE)), 0, 50
    )
    assert fts_total == scan_total == 1


def test_grep_no_false_positive(tmp_path: Path) -> None:
    log = _stacktrace_log(tmp_path)
    conn = open_or_create(tmp_path)
    build_index(conn, [log])

    filt = build_query_filter(grep="zzzznotfound")
    total, _ = query_logs(conn, filt, 0, 10)
    assert total == 0
