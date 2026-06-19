"""ログファイルから行をオンデマンドで読み出す."""

from __future__ import annotations

from pathlib import Path


class LineReader:
    """byte_offset から生ログ行を読み出す（grep / 詳細表示用）."""

    def __init__(self, paths: list[Path]) -> None:
        self._paths = paths
        self._handles: dict[int, object] = {}

    def _get_handle(self, file_id: int):
        fh = self._handles.get(file_id)
        if fh is None:
            fh = self._paths[file_id].open("rb")
            self._handles[file_id] = fh
        return fh

    def read(self, file_id: int, byte_offset: int) -> str:
        fh = self._get_handle(file_id)
        fh.seek(byte_offset)
        return fh.readline().decode("utf-8", errors="replace").rstrip("\n\r")

    def read_range(self, file_id: int, start_offset: int, end_offset: int | None) -> str:
        """start_offset から end_offset 直前まで（None なら EOF）を読み出す."""
        fh = self._get_handle(file_id)
        fh.seek(start_offset)
        if end_offset is None:
            return fh.read().decode("utf-8", errors="replace").rstrip("\n\r")
        size = end_offset - start_offset
        if size <= 0:
            return self.read(file_id, start_offset)
        return fh.read(size).decode("utf-8", errors="replace").rstrip("\n\r")

    def close(self) -> None:
        for fh in self._handles.values():
            fh.close()
        self._handles.clear()

    def __enter__(self) -> LineReader:
        return self

    def __exit__(self, *args: object) -> None:
        self.close()
