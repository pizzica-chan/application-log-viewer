"""Web UI 起動エントリポイント."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .discovery import find_log_files
from .web import serve


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="aplv",
        description="Java アプリケーションログを Web UI で閲覧・検索する",
    )
    parser.add_argument(
        "--dir",
        help="起動時に読み込むログディレクトリ（省略時は UI から選択）",
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument(
        "--fts",
        action="store_true",
        help="全文検索を SQLite FTS5 で高速化する（インデックス構築は遅くなる。"
        "未指定時は FTS5 を作らず全件スキャンで grep する）",
    )
    args = parser.parse_args(argv)

    paths: list[Path] = []
    log_root: Path | None = None
    if args.dir:
        log_root = Path(args.dir).resolve()
        if not log_root.is_dir():
            print(f"ディレクトリが見つかりません: {log_root}", file=sys.stderr)
            return 1
        paths = find_log_files(log_root)

    serve(paths, host=args.host, port=args.port, log_root=log_root, enable_fts=args.fts)
    return 0
