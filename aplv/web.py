"""Web UI サーバー."""

from __future__ import annotations

import json
import mimetypes
import re
import sqlite3
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .discovery import find_log_files
from . import index
from .query import build_query_filter, parse_datetime, query_logs
from .path_util import normalize_path

STATIC_DIR = Path(__file__).parent / "static"


class LogViewerHandler(BaseHTTPRequestHandler):
    log_paths: list[Path] = []
    log_root: Path | None = None
    _conn: sqlite3.Connection | None = None
    load_status: str = "idle"
    load_error: str | None = None
    load_progress: int = 0
    _load_lock = threading.Lock()
    _db_lock = threading.Lock()

    def log_message(self, format: str, *args) -> None:  # noqa: A003
        return

    def _send_json(self, payload: object, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path: Path) -> None:
        if not path.exists():
            self.send_error(404)
            return
        content = path.read_bytes()
        mime, _ = mimetypes.guess_type(str(path))
        self.send_response(200)
        self.send_header("Content-Type", mime or "application/octet-stream")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def _read_json_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("JSON オブジェクトを指定してください")
        return data

    @classmethod
    def _source_names(cls) -> list[str]:
        return [normalize_path(p) for p in cls.log_paths]

    @classmethod
    def _get_conn(cls) -> sqlite3.Connection:
        if cls._conn is None:
            cls._conn = index.open_memory()
        return cls._conn

    @classmethod
    def _replace_conn(cls, conn: sqlite3.Connection) -> None:
        with cls._db_lock:
            if cls._conn is not None and cls._conn is not conn:
                cls._conn.close()
            cls._conn = conn

    @classmethod
    def _start_load(cls) -> None:
        with cls._load_lock:
            if cls.load_status == "loading":
                return
            cls.load_status = "loading"
            cls.load_error = None
            cls.load_progress = 0

        root = cls.log_root
        paths = list(cls.log_paths)

        def worker() -> None:
            try:
                if root is None:
                    conn = index.open_memory()
                    index.clear_index(conn)
                    total = 0
                else:
                    index_store.ensure_tmp_dir_for(root)
                    conn = index.open_or_create(root)

                    def progress(count: int) -> None:
                        cls.load_progress = count

                    if not paths:
                        conn.close()
                        index_store.delete_index_files(root)
                        conn = index.open_or_create(root)
                        index.clear_index(conn)
                        total = 0
                    elif index.needs_rebuild(conn, paths):
                        conn.close()
                        index_store.delete_index_files(root)
                        conn = index.open_or_create(root)
                        total = index.build_index(conn, paths, progress)
                    else:
                        total = index.entry_count(conn)
                        cls.load_progress = total

                cls._replace_conn(conn)
                with cls._load_lock:
                    cls.load_status = "ready"
                    cls.load_progress = total
            except Exception as exc:  # noqa: BLE001
                with cls._load_lock:
                    cls.load_status = "error"
                    cls.load_error = str(exc)

        threading.Thread(target=worker, daemon=True).start()

    @classmethod
    def _ensure_load_started(cls) -> None:
        if cls.log_paths and cls.load_status == "idle":
            cls._start_load()

    @classmethod
    def _meta_payload(cls) -> dict:
        cls._ensure_load_started()
        loading = cls.load_status == "loading"
        progress = cls.load_progress

        if loading:
            total = progress
            first = None
            last = None
        elif cls.load_status == "ready":
            with cls._db_lock:
                conn = cls._get_conn()
                total = index.entry_count(conn)
                first, last = index.timestamp_bounds(conn)
        else:
            total = 0
            first = None
            last = None

        payload: dict = {
            "directory": str(cls.log_root) if cls.log_root else None,
            "files": cls._source_names(),
            "loading": loading,
            "load_status": cls.load_status,
            "load_progress": progress,
            "total": total,
            "first": first,
            "last": last,
        }
        if cls.load_error:
            payload["load_error"] = cls.load_error
        return payload

    def _browse_directory(self, raw_path: str) -> dict:
        if raw_path:
            current = Path(raw_path).expanduser().resolve()
        elif self.log_root is not None:
            current = self.log_root
        else:
            current = Path.cwd().resolve()

        if not current.is_dir():
            return {"error": f"ディレクトリが見つかりません: {current}"}

        parent = str(current.parent) if current.parent != current else None
        directories: list[str] = []
        try:
            for entry in sorted(current.iterdir(), key=lambda p: p.name.lower()):
                if entry.is_dir() and not entry.name.startswith("."):
                    directories.append(normalize_path(entry))
        except OSError as exc:
            return {"error": f"ディレクトリを読み取れません: {exc}"}

        return {
            "current": normalize_path(current),
            "parent": parent,
            "directories": directories,
        }

    def _load_directory(self, raw_path: str) -> tuple[dict, int]:
        if not raw_path:
            return {"error": "directory を指定してください"}, 400

        root = Path(raw_path).expanduser().resolve()
        if not root.is_dir():
            return {"error": f"ディレクトリが見つかりません: {root}"}, 400

        paths = find_log_files(root)
        LogViewerHandler.log_root = root
        LogViewerHandler.log_paths = paths
        LogViewerHandler.load_status = "idle"
        LogViewerHandler.load_error = None
        LogViewerHandler.load_progress = 0
        LogViewerHandler._start_load()
        return self._meta_payload(), 200

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/":
            return self._send_file(STATIC_DIR / "index.html")
        if parsed.path.startswith("/static/"):
            rel = parsed.path.removeprefix("/static/")
            return self._send_file(STATIC_DIR / rel)

        if parsed.path == "/api/meta":
            return self._send_json(self._meta_payload())

        if parsed.path == "/api/browse":
            raw_path = params.get("path", [""])[0]
            payload = self._browse_directory(raw_path)
            if "error" in payload:
                return self._send_json(payload, 400)
            return self._send_json(payload)

        if parsed.path == "/api/logs":
            if self.load_status == "loading":
                return self._send_json(
                    {
                        "loading": True,
                        "load_progress": self.load_progress,
                        "total": 0,
                        "offset": 0,
                        "limit": 0,
                        "items": [],
                    }
                )
            if self.load_status == "error":
                return self._send_json(
                    {"error": self.load_error or "読み込みに失敗しました"},
                    500,
                )

            level = params.get("level", [""])[0]
            logger_pat = params.get("logger", [""])[0]
            thread_pat = params.get("thread", [""])[0]
            message_pat = params.get("message", [""])[0]
            grep = params.get("grep", [""])[0]
            source_pat = params.get("source", [""])[0]
            try:
                since_raw = params.get("since", [""])[0] or None
                until_raw = params.get("until", [""])[0] or None
                since = parse_datetime(since_raw) if since_raw else None
                until = parse_datetime(until_raw) if until_raw else None
            except ValueError as exc:
                return self._send_json({"error": str(exc)}, 400)
            try:
                limit = min(int(params.get("limit", ["500"])[0]), 5000)
                offset = max(int(params.get("offset", ["0"])[0]), 0)
            except ValueError:
                return self._send_json({"error": "limit/offset は整数で指定してください"}, 400)

            try:
                filt = build_query_filter(
                    level=level,
                    logger_pat=logger_pat,
                    thread_pat=thread_pat,
                    message_pat=message_pat,
                    grep=grep,
                    source_pat=source_pat,
                    since=since,
                    until=until,
                )
            except re.error as exc:
                return self._send_json({"error": str(exc)}, 400)

            with self._db_lock:
                total, page = query_logs(self._get_conn(), filt, offset, limit)

            return self._send_json(
                {
                    "total": total,
                    "offset": offset,
                    "limit": limit,
                    "items": [e.to_row_dict() for e in page],
                }
            )

        if parsed.path == "/api/logs/detail":
            source = params.get("source", [""])[0]
            timestamp = params.get("timestamp", [""])[0] or None
            try:
                line_no = int(params.get("line_no", ["0"])[0])
            except ValueError:
                return self._send_json({"error": "line_no は整数で指定してください"}, 400)
            if not source:
                return self._send_json({"error": "source を指定してください"}, 400)

            with self._db_lock:
                entry = index.find_entry(self._get_conn(), source, line_no, timestamp)
            if entry is None:
                return self._send_json({"error": "該当行が見つかりません"}, 404)

            try:
                raw = index.read_entry_raw(
                    Path(entry.source),
                    entry.byte_offset,
                    entry.end_byte_offset,
                )
            except OSError as exc:
                return self._send_json({"error": str(exc)}, 500)

            return self._send_json(
                {
                    "source": entry.source,
                    "line_no": entry.line_no,
                    "timestamp": entry.timestamp.isoformat(),
                    "level": entry.level,
                    "logger": entry.logger,
                    "thread": entry.thread,
                    "raw": raw,
                }
            )

        self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/api/load":
            self.send_error(404)
            return

        try:
            body = self._read_json_body()
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as exc:
            return self._send_json({"error": str(exc)}, 400)

        payload, status = self._load_directory(body.get("directory", ""))
        return self._send_json(payload, status)


def serve(
    paths: list[Path],
    *,
    host: str = "127.0.0.1",
    port: int = 8766,
    log_root: Path | None = None,
) -> None:
    LogViewerHandler.log_paths = [p.resolve() for p in paths]
    LogViewerHandler.log_root = log_root.resolve() if log_root else None
    LogViewerHandler._conn = None
    LogViewerHandler.load_status = "idle"
    LogViewerHandler.load_error = None
    LogViewerHandler.load_progress = 0
    if paths:
        LogViewerHandler._start_load()
    server = ThreadingHTTPServer((host, port), LogViewerHandler)
    print(f"Application Log Viewer: http://{host}:{port}")
    if LogViewerHandler.log_root:
        print(f"ログディレクトリ: {LogViewerHandler.log_root}")
    print(f"読み込みファイル ({len(paths)}):")
    for p in paths:
        print(f"  - {p}")
    if not paths:
        print("  (未読み込み — ブラウザからディレクトリを選択してください)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n停止しました。")
    finally:
        server.server_close()
        with LogViewerHandler._db_lock:
            if LogViewerHandler._conn is not None:
                LogViewerHandler._conn.close()
                LogViewerHandler._conn = None
