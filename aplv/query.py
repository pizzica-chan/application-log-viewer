"""SQLite インデックスに対するフィルタリング・ページング."""

from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .filters import parse_level_filter
from .index import EntryRow, fts_available, read_entry_raw

# 正規表現メタ文字。grep がこれらを含まない（=プレーンなリテラル）場合のみ
# FTS5 trigram での候補絞り込みを使う。
_REGEX_META = set(".^$*+?()[]{}|\\")
# trigram は 3 文字以上でないと部分一致検索できない。
_FTS_MIN_LEN = 3


@dataclass(slots=True)
class QueryFilter:
    levels: set[str] | None = None
    logger_re: re.Pattern[str] | None = None
    thread_re: re.Pattern[str] | None = None
    message_re: re.Pattern[str] | None = None
    source_re: re.Pattern[str] | None = None
    grep_re: re.Pattern[str] | None = None
    grep_text: str | None = None
    since: datetime | None = None
    until: datetime | None = None


def _is_plain_literal(text: str) -> bool:
    """grep 文字列が FTS で扱えるプレーンなリテラルか。"""
    return len(text) >= _FTS_MIN_LEN and not any(c in _REGEX_META for c in text)


def _fts_match_expr(literal: str) -> str:
    """リテラルを FTS5 のフレーズ（部分一致）クエリ文字列に変換する。"""
    escaped = literal.replace('"', '""')
    return f'"{escaped}"'


def parse_datetime(value: str) -> datetime:
    """UI から渡される日時文字列を naive datetime に解析する."""
    for fmt in (
        "%Y-%m-%dT%H:%M:%S.%f",
        "%Y-%m-%d %H:%M:%S.%f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d",
    ):
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            continue
    raise ValueError(f"日時形式を解釈できません: {value}")


def _naive(dt: datetime | None) -> datetime | None:
    if dt is None:
        return None
    if dt.tzinfo is not None:
        return dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt


def _ts_to_str(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f")


def _matches_index_columns(entry: EntryRow, filt: QueryFilter) -> bool:
    """DB 列のみで判定（grep 前の高速フィルタ。ディスク読み不要）。"""
    if filt.levels is not None and entry.level.upper() not in filt.levels:
        return False
    if filt.since is not None and entry.timestamp < filt.since:
        return False
    if filt.until is not None and entry.timestamp > filt.until:
        return False
    if filt.source_re is not None and not filt.source_re.search(entry.source):
        return False
    if filt.logger_re is not None and not filt.logger_re.search(entry.logger):
        return False
    if filt.thread_re is not None and not filt.thread_re.search(entry.thread):
        return False
    if filt.message_re is not None and not filt.message_re.search(entry.message):
        return False
    return True


def _matches_grep(filt: QueryFilter, raw: str) -> bool:
    if filt.grep_re is None:
        return True
    return bool(filt.grep_re.search(raw))


def _matches_row(
    entry: EntryRow,
    filt: QueryFilter,
    raw: str | None,
) -> bool:
    if not _matches_index_columns(entry, filt):
        return False
    if filt.grep_re is not None:
        return _matches_grep(filt, raw or "")
    return True


def _row_to_entry(row: tuple) -> EntryRow:
    ts_str = row[5]
    for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S.%3f"):
        try:
            timestamp = datetime.strptime(ts_str, fmt)
            break
        except ValueError:
            continue
    else:
        raise ValueError(f"timestamp を解釈できません: {ts_str}")
    return EntryRow(
        id=row[0],
        file_id=row[1],
        line_no=row[2],
        byte_offset=row[3],
        end_byte_offset=row[4],
        timestamp=timestamp,
        logger=row[6],
        level=row[7],
        thread=row[8],
        message=row[9],
        source=row[10],
    )


def query_logs(
    conn: sqlite3.Connection,
    filt: QueryFilter,
    offset: int,
    limit: int,
) -> tuple[int, list[EntryRow]]:
    sql = """
        SELECT e.id, e.file_id, e.line_no, e.byte_offset, e.end_byte_offset,
               e.timestamp, e.logger, e.level, e.thread, e.message, f.path
        FROM entries e
        JOIN files f ON e.file_id = f.id
        WHERE 1=1
    """
    params: list = []

    if filt.levels:
        placeholders = ",".join("?" * len(filt.levels))
        sql += f" AND e.level IN ({placeholders})"
        params.extend(sorted(filt.levels))
    if filt.since is not None:
        sql += " AND e.timestamp >= ?"
        params.append(_ts_to_str(filt.since))
    if filt.until is not None:
        sql += " AND e.timestamp <= ?"
        params.append(_ts_to_str(filt.until))

    # grep がプレーンなリテラルかつ FTS5 が使えるなら、まず FTS で候補 id を絞り込む。
    # （最終的な合否は従来どおり下の正規表現検証で確定するので結果は同一。）
    if (
        filt.grep_re is not None
        and filt.grep_text
        and _is_plain_literal(filt.grep_text)
        and fts_available(conn)
    ):
        sql += (
            " AND e.id IN (SELECT rowid FROM entries_fts "
            "WHERE entries_fts MATCH ?)"
        )
        params.append(_fts_match_expr(filt.grep_text))

    sql += " ORDER BY e.timestamp, e.file_id, e.line_no"

    needs_raw = filt.grep_re is not None
    total = 0
    page: list[EntryRow] = []

    for row in conn.execute(sql, params):
        entry = _row_to_entry(row)
        if not _matches_index_columns(entry, filt):
            continue
        if needs_raw:
            try:
                raw = read_entry_raw(
                    Path(entry.source),
                    entry.byte_offset,
                    entry.end_byte_offset,
                )
            except OSError:
                raw = ""
            if not _matches_grep(filt, raw):
                continue
        if total >= offset and len(page) < limit:
            page.append(entry)
        total += 1

    return total, page


def build_query_filter(
    *,
    level: str = "",
    logger_pat: str = "",
    thread_pat: str = "",
    message_pat: str = "",
    grep: str = "",
    source_pat: str = "",
    since: datetime | None = None,
    until: datetime | None = None,
) -> QueryFilter:
    return QueryFilter(
        levels=parse_level_filter(level),
        logger_re=re.compile(logger_pat, re.IGNORECASE) if logger_pat else None,
        thread_re=re.compile(thread_pat, re.IGNORECASE) if thread_pat else None,
        message_re=re.compile(message_pat, re.IGNORECASE) if message_pat else None,
        source_re=re.compile(source_pat, re.IGNORECASE) if source_pat else None,
        grep_re=re.compile(grep, re.IGNORECASE) if grep else None,
        grep_text=grep or None,
        since=_naive(since),
        until=_naive(until),
    )
