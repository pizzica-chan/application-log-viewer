"""Java アプリケーションログパーサー."""

from __future__ import annotations

import heapq
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterator

# Tomcat: 2026-06-15 00:19:11.705[ajp-nio-8009-exec-24][TRACE][org.hogehoge.jdbc.HogeUtil] - ログ本文
# その他: 2026-05-27 00:00:03.965[HogeController][ERROR][main] - ログ本文
# いずれも [xxx][LEVEL][yyy] — LEVEL は常に 2 番目の [] 内
_LOG_RE = re.compile(
    r"^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})"
    r"\[(?P<field1>[^\]]*)\]"
    r"\[(?P<field2>[^\]]*)\]"
    r"\[(?P<field3>[^\]]*)\]"
    r" - (?P<message>.*)$"
)

_KNOWN_LEVELS = frozenset(
    {"TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "SEVERE"}
)

_THREAD_HINT = re.compile(
    r"(?:^main$|exec-\d+|pool-\d+-thread-\d+|scheduler-\d+|ajp-|http-nio-|catalina-)",
    re.IGNORECASE,
)


@dataclass(slots=True)
class LogEntry:
    file_id: int
    line_no: int
    byte_offset: int
    end_byte_offset: int | None
    timestamp: datetime
    logger: str
    level: str
    thread: str
    message: str

    def to_row_dict(self, source_name: str) -> dict:
        """一覧 API 用の軽量 dict."""
        return {
            "timestamp": self.timestamp.isoformat(),
            "level": self.level,
            "logger": self.logger,
            "thread": self.thread,
            "message": self.message,
            "source": source_name,
            "line_no": self.line_no,
        }


def is_log_header(line: str) -> bool:
    return parse_line(line) is not None


def parse_timestamp(value: str) -> datetime:
    """`2026-05-27 00:00:03.965` を解析する."""
    return datetime.strptime(value, "%Y-%m-%d %H:%M:%S.%f")


def _assign_fields(field1: str, field2: str, field3: str) -> tuple[str, str, str] | None:
    """2 番目の [] を LEVEL として、logger / thread を判別する."""
    level = field2.upper()
    if level not in _KNOWN_LEVELS:
        return None

    # Tomcat: [Thread][LEVEL][Logger(FQCN)]
    if "." in field3:
        return field3, level, field1
    # その他: [Logger(FQCN)][LEVEL][Thread]
    if "." in field1:
        return field1, level, field3

    if _THREAD_HINT.search(field1):
        return field3, level, field1
    if _THREAD_HINT.search(field3):
        return field1, level, field3

    # 判別不能時は Tomcat 形式（Thread, Level, Logger）を優先
    return field3, level, field1


def parse_line(
    raw: str, *, file_id: int = 0, line_no: int = 0, byte_offset: int = 0
) -> LogEntry | None:
    line = raw.rstrip("\n\r")
    match = _LOG_RE.match(line)
    if not match:
        return None

    groups = match.groupdict()
    fields = _assign_fields(groups["field1"], groups["field2"], groups["field3"])
    if fields is None:
        return None
    logger, level, thread = fields

    return LogEntry(
        file_id=file_id,
        line_no=line_no,
        byte_offset=byte_offset,
        end_byte_offset=None,
        timestamp=parse_timestamp(groups["timestamp"]),
        logger=logger,
        level=level,
        thread=thread,
        message=groups["message"],
    )


def _iter_file_entries(file_id: int, path: Path) -> Iterator[LogEntry]:
    with path.open("rb") as fh:
        line_no = 0
        pending: LogEntry | None = None
        while True:
            offset = fh.tell()
            line_bytes = fh.readline()
            if not line_bytes:
                break
            line_no += 1
            if not line_bytes.strip():
                continue
            line = line_bytes.decode("utf-8", errors="replace")
            entry = parse_line(
                line, file_id=file_id, line_no=line_no, byte_offset=offset
            )
            if entry is None:
                continue
            if pending is not None:
                pending.end_byte_offset = offset
                yield pending
            pending = entry

        if pending is not None:
            pending.end_byte_offset = fh.tell()
            yield pending


def iter_entries(paths: list[Path]) -> Iterator[LogEntry]:
    for file_id, path in enumerate(paths):
        yield from _iter_file_entries(file_id, path)


def load_entries(
    paths: list[Path],
    *,
    sort: bool = True,
    progress_callback: Callable[[int], None] | None = None,
) -> list[LogEntry]:
    if not sort:
        entries = list(iter_entries(paths))
        if progress_callback:
            progress_callback(len(entries))
        return entries

    heap: list[tuple[datetime, int, int, LogEntry, Iterator[LogEntry]]] = []
    for file_id, path in enumerate(paths):
        it = _iter_file_entries(file_id, path)
        try:
            entry = next(it)
        except StopIteration:
            continue
        heapq.heappush(heap, (entry.timestamp, entry.file_id, entry.line_no, entry, it))

    result: list[LogEntry] = []
    while heap:
        _, _, _, entry, it = heapq.heappop(heap)
        result.append(entry)
        if progress_callback and len(result) % 50000 == 0:
            progress_callback(len(result))
        try:
            next_entry = next(it)
            heapq.heappush(
                heap,
                (
                    next_entry.timestamp,
                    next_entry.file_id,
                    next_entry.line_no,
                    next_entry,
                    it,
                ),
            )
        except StopIteration:
            pass

    if progress_callback:
        progress_callback(len(result))
    return result
