"""path_util モジュールのテスト."""

from __future__ import annotations

from pathlib import Path

from aplv.path_util import normalize_path, normalize_path_str


def test_strips_verbatim_prefix() -> None:
    assert normalize_path_str(r"\\?\D:\workspace\samples") == r"D:\workspace\samples"


def test_strips_unc_verbatim_prefix() -> None:
    assert normalize_path_str(r"\\?\UNC\server\share\logs") == r"\\server\share\logs"


def test_normal_path_unchanged() -> None:
    assert normalize_path_str(r"D:\workspace\samples") == r"D:\workspace\samples"


def test_normalize_path_from_path() -> None:
    p = Path(r"D:\workspace\samples")
    assert normalize_path(p).endswith("samples")
