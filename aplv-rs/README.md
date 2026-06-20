# Application Log Viewer (Rust + SQLite)

Python 版 (`aplv`) と同じ Web UI で Java アプリログを閲覧・検索する **Rust 実装** です。  
解析結果はログディレクトリ配下の `.aplv/index.db`（SQLite）に保存し、2 回目以降の読み込みを高速化します。

## 前提

- [Rust ツールチェイン](https://www.rust-lang.org/tools/install)（`cargo`）
- Python 版の静的ファイル `../aplv/static` を共有利用

## ビルド

```powershell
cd D:\workspace\application-log-viewer\aplv-rs
cargo build --release
```

## 起動

```powershell
cargo run --release -- --dir ..\samples
# または
cargo run --release -- --dir C:\logs\app --port 8767
```

ブラウザ: http://127.0.0.1:8767

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--dir` | 起動時に読み込むログディレクトリ | — |
| `--host` | 待ち受けアドレス | `127.0.0.1` |
| `--port` | 待ち受けポート | `8767`（Python 版 8766 と競合しない） |

## SQLite インデックス

- 保存先: `{ログディレクトリ}/.aplv/index.db`
- ログファイルのパス・更新時刻・サイズが変わらなければ **再インデックスをスキップ**
- エントリ本体（スタックトレース）は DB に載せず、元ファイルの byte offset から読み出し

## Python 版との違い

| | Python 版 | Rust 版 |
|---|-----------|---------|
| 保存 | メモリのみ | SQLite インデックス |
| ポート | 8766 | 8767 |
| 大容量ログ | OOM しやすい | インデックス再利用で再読み込みが速い |

API は Python 版と互換のため、同じ Web UI をそのまま利用できます。

## テスト

```powershell
cargo test
```
