"""SQLite インデックスの構築・参照."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable

from .parser import parse_line
from .path_util import normalize_path

BATCH_SIZE = 2000

_SCHEMA = """
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS files (
    id INTEGER PRIMARY KEY,
    path TEXT NOT NULL UNIQUE,
    mtime_secs INTEGER NOT NULL,
    size INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS entries (
    id INTEGER PRIMARY KEY,
    file_id INTEGER NOT NULL,
    line_no INTEGER NOT NULL,
    byte_offset INTEGER NOT NULL,
    end_byte_offset INTEGER,
    timestamp TEXT NOT NULL,
    logger TEXT NOT NULL,
    level TEXT NOT NULL,
    thread TEXT NOT NULL,
    message TEXT NOT NULL,
    FOREIGN KEY (file_id) REFERENCES files(id)
);
CREATE INDEX IF NOT EXISTS idx_entries_ts ON entries(timestamp, file_id, line_no);
CREATE INDEX IF NOT EXISTS idx_entries_level ON entries(level);
"""


@dataclass(slots=True)
class EntryRow:
    id: int
    file_id: int
    line_no: int
    byte_offset: int
    end_byte_offset: int | None
    timestamp: datetime
    logger: str
    level: str
    thread: str
    message: str
    source: str

    def to_row_dict(self) -> dict:
        return {
            "timestamp": self.timestamp.isoformat(),
            "level": self.level,
            "logger": self.logger,
            "thread": self.thread,
            "message": self.message,
            "source": self.source,
            "line_no": self.line_no,
        }


def index_dir(log_root: Path) -> Path:
    return log_root / ".aplv"


def index_db_path(log_root: Path) -> Path:
    return index_dir(log_root) / "index.db"


def open_or_create(log_root: Path) -> sqlite3.Connection:
    index_dir(log_root).mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(index_db_path(log_root)), check_same_thread=False)
    conn.executescript(_SCHEMA)
    return conn


def open_memory() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:", check_same_thread=False)
    conn.executescript(_SCHEMA)
    return conn


def _file_fingerprint(paths: list[Path]) -> str:
    parts: list[str] = []
    for path in paths:
        meta = path.stat()
        mtime = int(meta.st_mtime)
        parts.append(f"{normalize_path(path)}:{mtime}:{meta.st_size}")
    parts.sort()
    return "\n".join(parts)


def needs_rebuild(conn: sqlite3.Connection, paths: list[Path]) -> bool:
    if not paths:
        return False
    fp = _file_fingerprint(paths)
    row = conn.execute(
        "SELECT value FROM meta WHERE key = 'fingerprint'"
    ).fetchone()
    stored = row[0] if row else None
    return stored != fp


def clear_index(conn: sqlite3.Connection) -> None:
    conn.execute("DELETE FROM entries")
    conn.execute("DELETE FROM files")
    conn.commit()


def _ts_to_str(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f")


def _parse_ts(ts_str: str) -> datetime:
    for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S.%3f"):
        try:
            return datetime.strptime(ts_str, fmt)
        except ValueError:
            continue
    raise ValueError(f"timestamp を解釈できません: {ts_str}")


def _flush_batch(
    conn: sqlite3.Connection,
    batch: list[tuple],
) -> None:
    if not batch:
        return
    conn.executemany(
        """
        INSERT INTO entries (
            file_id, line_no, byte_offset, end_byte_offset,
            timestamp, logger, level, thread, message
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        batch,
    )
    batch.clear()


def build_index(
    conn: sqlite3.Connection,
    paths: list[Path],
    on_progress: Callable[[int], None] | None = None,
) -> int:
    clear_index(conn)
    fp = _file_fingerprint(paths)
    total = 0

    with conn:
        for file_id, path in enumerate(paths, start=1):
            meta = path.stat()
            mtime = int(meta.st_mtime)
            conn.execute(
                "INSERT INTO files (id, path, mtime_secs, size) VALUES (?, ?, ?, ?)",
                (file_id, normalize_path(path), mtime, meta.st_size),
            )

            batch: list[tuple] = []
            line_no = 0
            pending: tuple[int, int, object] | None = None

            with path.open("rb") as fh:
                while True:
                    offset = fh.tell()
                    line_bytes = fh.readline()
                    if not line_bytes:
                        break
                    line_no += 1
                    if not line_bytes.strip():
                        continue
                    line = line_bytes.decode("utf-8", errors="replace")
                    parsed = parse_line(line)
                    if parsed is None:
                        continue
                    if pending is not None:
                        p_line_no, p_offset, p_parsed = pending
                        batch.append(
                            (
                                file_id,
                                p_line_no,
                                p_offset,
                                offset,
                                _ts_to_str(p_parsed.timestamp),
                                p_parsed.logger,
                                p_parsed.level,
                                p_parsed.thread,
                                p_parsed.message,
                            )
                        )
                        total += 1
                        if total % 50_000 == 0 and on_progress:
                            on_progress(total)
                        if len(batch) >= BATCH_SIZE:
                            _flush_batch(conn, batch)
                    pending = (line_no, offset, parsed)

                if pending is not None:
                    end = fh.tell()
                    p_line_no, p_offset, p_parsed = pending
                    batch.append(
                        (
                            file_id,
                            p_line_no,
                            p_offset,
                            end,
                            _ts_to_str(p_parsed.timestamp),
                            p_parsed.logger,
                            p_parsed.level,
                            p_parsed.thread,
                            p_parsed.message,
                        )
                    )
                    total += 1
                    _flush_batch(conn, batch)

        conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES ('fingerprint', ?)",
            (fp,),
        )

    if on_progress:
        on_progress(total)
    return total


def entry_count(conn: sqlite3.Connection) -> int:
    row = conn.execute("SELECT COUNT(*) FROM entries").fetchone()
    return int(row[0]) if row else 0


def timestamp_bounds(conn: sqlite3.Connection) -> tuple[str | None, str | None]:
    first = conn.execute(
        "SELECT timestamp FROM entries ORDER BY timestamp ASC LIMIT 1"
    ).fetchone()
    last = conn.execute(
        "SELECT timestamp FROM entries ORDER BY timestamp DESC LIMIT 1"
    ).fetchone()
    return (
        first[0] if first else None,
        last[0] if last else None,
    )


def read_entry_raw(path: Path, start: int, end: int | None) -> str:
    with path.open("rb") as fh:
        fh.seek(start)
        if end is None:
            text = fh.read().decode("utf-8", errors="replace")
        else:
            size = end - start
            if size <= 0:
                text = fh.readline().decode("utf-8", errors="replace")
            else:
                text = fh.read(size).decode("utf-8", errors="replace")
    return text.rstrip("\r\n")


def _row_to_entry(row: tuple) -> EntryRow:
    return EntryRow(
        id=row[0],
        file_id=row[1],
        line_no=row[2],
        byte_offset=row[3],
        end_byte_offset=row[4],
        timestamp=_parse_ts(row[5]),
        logger=row[6],
        level=row[7],
        thread=row[8],
        message=row[9],
        source=row[10],
    )


_SELECT_BASE = """
    SELECT e.id, e.file_id, e.line_no, e.byte_offset, e.end_byte_offset,
           e.timestamp, e.logger, e.level, e.thread, e.message, f.path
    FROM entries e
    JOIN files f ON e.file_id = f.id
"""


def find_entry(
    conn: sqlite3.Connection,
    source: str,
    line_no: int,
    timestamp: str | None = None,
) -> EntryRow | None:
    if timestamp:
        sql = f"{_SELECT_BASE} WHERE f.path = ? AND e.line_no = ? AND e.timestamp = ?"
        params: tuple = (source, line_no, timestamp)
    else:
        sql = f"{_SELECT_BASE} WHERE f.path = ? AND e.line_no = ?"
        params = (source, line_no)
    row = conn.execute(sql, params).fetchone()
    return _row_to_entry(row) if row else None


def list_file_paths(conn: sqlite3.Connection) -> list[str]:
    rows = conn.execute("SELECT path FROM files ORDER BY id").fetchall()
    return [row[0] for row in rows]
