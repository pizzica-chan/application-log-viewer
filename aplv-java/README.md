# Application Log Viewer (Java 8)

Python 版 (`aplv`) / Rust 版 (`aplv-rs`) と同じ Web UI で Java アプリログを閲覧・検索する **Java 8 実装** です。

- **依存関係管理は Maven**
- **Web サーバ機能は自前**（JDK 内蔵の `com.sun.net.httpserver` を利用。Tomcat 等のアプリケーションサーバ不要）
- **数 GB 級の大容量ログ向けにパフォーマンスを重視**

## パフォーマンス設計

| 項目 | 内容 |
|------|------|
| メモリ | エントリ本文（スタックトレース）は DB に載せず、元ファイルの **byte offset** から都度読み出し（全文をメモリに抱えないため OOM を回避） |
| インデックス | 解析結果を `{リポジトリ}/tmp/aplv/`（SQLite）に **永続化**。変更時は DB 再作成、古い DB は自動削除 |
| 解析 | まず **バイト列だけでヘッダ行を高速判定**し、一致行のみ UTF-8 デコード。スタックトレース等の継続行はデコードを省略 |
| 並列化 | 複数ログファイルを **並列パース**し、SQLite 書き込みは単一ライタースレッドに集約（producer/consumer） |
| I/O | 1 MiB バッファでまとめ読みし、改行走査で行を切り出して byte offset を保持 |
| 日時 | `LocalDateTime` を使わず civil calendar 計算で epoch millis へ直接変換。範囲フィルタ・整列も millis（数値）で実施 |
| クエリ | レベル・日時は SQL（インデックス利用）で事前絞り込み。正規表現・全文検索は Java 側で評価 |

## 前提

- JDK 8 以上（`javac` を含む JDK。実行のみなら JRE 8 でも可）
- Maven 3.6 以上

## ビルド

```powershell
cd D:\workspace\application-log-viewer\aplv-java
mvn -q clean package
```

依存込みの実行可能 JAR `target/aplv-java.jar` が生成されます。

## 起動

```powershell
java -jar target\aplv-java.jar --dir ..\samples
# または
java -jar target\aplv-java.jar --dir C:\logs\app --port 8768
```

ブラウザ: http://127.0.0.1:8768

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--dir`  | 起動時に読み込むログディレクトリ（省略時は UI から選択） | — |
| `--host` | 待ち受けアドレス | `127.0.0.1` |
| `--port` | 待ち受けポート（Python 8766 / Rust 8767 と競合しない） | `8768` |

## API（Python / Rust 版と互換）

- `GET /` — Web UI
- `GET /api/meta` — 読み込み状態・件数・期間
- `GET /api/browse?path=` — ディレクトリ一覧
- `POST /api/load` — `{"directory": "..."}` を受け取りインデックス構築開始
- `GET /api/logs?level=&logger=&thread=&message=&grep=&source=&since=&until=&limit=&offset=` — フィルタ付き一覧
- `GET /api/logs/detail?source=&line_no=&timestamp=` — スタックトレース含む生ログ

## 対応ログ形式

Python / Rust 版と同一です。

```
2026-06-15 00:19:11.705[ajp-nio-8009-exec-24][TRACE][org.hogehoge.jdbc.HogeUtil] - ログ本文
2026-05-27 00:00:03.965[HogeController][ERROR][main] - 旧形式（[Logger][LEVEL][Thread]）も対応
```

- 形式: `YYYY-MM-DD HH:MM:SS.mmm[f1][f2][f3] - Message`（LEVEL は常に 2 番目の `[]`）
- 先頭行が上記に一致するものを 1 エントリとし、続く行（スタックトレース等）は同一エントリに含める
- 解析できない行はスキップ（エラーにしない）

## テスト

```powershell
mvn test
```

## ライセンス

MIT
