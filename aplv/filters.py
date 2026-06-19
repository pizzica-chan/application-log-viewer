"""ログエントリのフィルタリング."""

from __future__ import annotations

import re
from datetime import datetime
from typing import Callable

from .parser import LogEntry


def _naive(dt: datetime | None) -> datetime | None:
    if dt is None:
        return None
    return dt.replace(tzinfo=None) if dt.tzinfo else dt


def parse_level_filter(value: str) -> set[str] | None:
    """ERROR / WARN,ERROR / error などを解釈する."""
    if not value:
        return None
    result: set[str] = set()
    for part in value.split(","):
        part = part.strip()
        if part:
            result.add(part.upper())
    return result or None


def parse_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    for fmt in (
        "%Y-%m-%dT%H:%M:%S.%f",
        "%Y-%m-%d %H:%M:%S.%f",
        "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%d %H:%M:%S%z",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d",
    ):
        try:
            dt = datetime.strptime(value, fmt)
            if dt.tzinfo is None:
                from datetime import timezone

                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except ValueError:
            continue
    raise ValueError(f"日時形式を解釈できません: {value}")


def match_entry(
    entry: LogEntry,
    *,
    source_name: str = "",
    level: set[str] | None = None,
    logger: re.Pattern[str] | None = None,
    thread: re.Pattern[str] | None = None,
    message: re.Pattern[str] | None = None,
    since: datetime | None = None,
    until: datetime | None = None,
    query: re.Pattern[str] | None = None,
    source: re.Pattern[str] | None = None,
    read_raw: Callable[[LogEntry], str] | None = None,
) -> bool:
    if since is not None and entry.timestamp < _naive(since):
        return False
    if until is not None and entry.timestamp > _naive(until):
        return False
    if level is not None and entry.level.upper() not in level:
        return False
    if source is not None and not source.search(source_name):
        return False
    if logger is not None and not logger.search(entry.logger):
        return False
    if thread is not None and not thread.search(entry.thread):
        return False
    if message is not None and not message.search(entry.message):
        return False
    if query is not None:
        if read_raw is None:
            return False
        if not query.search(read_raw(entry)):
            return False
    return True
