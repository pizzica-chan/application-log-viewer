# Application Log Viewer (aplv)

Java アプリケーションのログ（複数ファイル）を **Web UI** で閲覧・検索する障害調査用ツールです。

指定ディレクトリ配下のログファイルを **再帰的に探索** し、複数ファイルを **時系列順に統合** して表示します。

## 機能

- ディレクトリを UI から選択し、配下のログファイルを再帰的に読み込み
- 複数ファイルを時系列ソートして一覧表示
- 各ログ行に **ソースファイル名** と行番号を保持
- レベル / ロガー / スレッド / メッセージ / 日時 / 全文 / ソースファイルでのフィルタ
- 行クリックで生ログ（スタックトレース含む）とメタ情報を表示
- ページング対応

大容量ログ向けに **SQLite インデックス** をリポジトリ直下の `tmp/aplv/` に保存します。2 回目以降はファイル変更がなければインデックスを再利用するため、起動が速くなります。7 日以上未使用または件数超過の古い DB は自動削除されます。

全文検索（grep）は **SQLite FTS5（trigram トークナイザ）** で高速化できます（**起動時に `--fts` を指定したときのみ有効**）。検索語が正規表現メタ文字を含まないプレーンな 3 文字以上の文字列のときは、FTS5 でヒット候補を一括で絞り込んでから生ログを照合します（正規表現パターンや 2 文字以下の場合は従来どおり全件スキャンに自動フォールバック）。FTS5 索引は本文の複製を持たない contentless 構成のため、ディスク使用量の増加を抑えつつ、結果は従来の正規表現検索と完全に一致します。

`--fts` を指定しない既定動作では FTS5 索引を作らないため**初回のインデックス構築が高速**です（grep は全件スキャンになります）。検索を多用しインデックス構築時間を許容できる場合に `--fts` を付けてください。全文検索の挙動は 3 実装（Python / Rust / Java）で共通です。

## インストール

```powershell
cd D:\workspace\application-log-viewer
pip install -e .
```

依存パッケージは不要（Python 3.10+、標準ライブラリ + SQLite）です。

## 起動

```powershell
python -m aplv
```

ブラウザで http://127.0.0.1:8766 を開きます。

起動時にログディレクトリを指定する場合:

```powershell
python -m aplv --dir C:\logs\app
python -m aplv --dir samples --port 8766
```

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--dir` | 起動時に読み込むログディレクトリ（省略時は UI から選択） | — |
| `--host` | 待ち受けアドレス | `127.0.0.1` |
| `--port` | 待ち受けポート | `8766` |
| `--fts` | 全文検索を FTS5 で高速化（インデックス構築は遅くなる） | 無効 |

## Web UI の使い方

1. 画面上部の **ログディレクトリ** にパスを入力するか、**参照...** でディレクトリを選択
2. **読み込み** をクリックすると、配下のログファイルが再帰的に探索・読み込まれます
3. フィルタを設定して **検索**（Enter キーでも可）
4. 行をクリックすると、ソースファイル・行番号・生ログ（スタックトレース含む）を表示

### 読み込み対象のログファイル

以下のファイル名パターンに一致するファイルを再帰的に探索します。

- `application*.log*`, `server*.log*`, `spring*.log*`
- `catalina*.log*`, `localhost*.log*`, `app*.log*`
- `*.log`, `*.out`

`.git` や `__pycache__` などのディレクトリはスキップします。圧縮ファイル（`.gz` 等）は未対応です。

### フィルタ

| 項目 | 説明 | 例 |
|------|------|-----|
| レベル | ログレベル | `ERROR` / `WARN,ERROR` |
| ロガー | ロガー名（正規表現） | `HogeController` |
| スレッド | スレッド名（正規表現） | `main` |
| メッセージ | 1行目メッセージ（正規表現） | `NullPointer` |
| 開始 / 終了 | 日時範囲 | 日時ピッカー |
| 全文検索 | エントリ全体（スタックトレース含む）への正規表現 | `Exception` |
| ソースファイル | ファイル名への正規表現 | `application.log` |

## 対応ログ形式

### Tomcat アプリログ（主形式）

```
2026-06-15 00:19:11.705[ajp-nio-8009-exec-24][TRACE][org.hogehoge.jdbc.HogeUtil] - ログ本文
```

形式: `YYYY-MM-DD HH:MM:SS.mmm[Thread][LEVEL][Logger] - Message`

### その他の Java ログ

```
2026-05-27 00:00:03.965[HogeController][ERROR][main] - ログ本文
```

形式: `YYYY-MM-DD HH:MM:SS.mmm[Logger][LEVEL][Thread] - Message`

いずれも LEVEL は 2 番目の `[]` 内にあり、FQCN（`.` 含む）やスレッド名パターンから自動判別します。

```
java.lang.NullPointerException: null
	at com.example.HogeController.process(HogeController.java:42)
```

- 先頭行が上記形式に一致するエントリを1件として扱います
- 続く行（スタックトレース等）は同一エントリに含め、詳細表示・全文検索の対象にします
- 解析できない行はスキップされます（エラーにはしません）

## 障害調査の例

```powershell
# Web UI を起動してサンプルログを読み込み
python -m aplv --dir samples

# ブラウザで以下のような調査を行う
# - レベル: ERROR
# - 開始 / 終了: 障害時間帯を指定
# - 全文検索: NullPointerException
# - ロガー: HogeController
```

## ライセンス

MIT

## Rust + SQLite 版

大容量ログ向けに **Rust + SQLite** 実装（`aplv-rs/`）を同梱しています。Python 版はそのまま利用できます。

```powershell
cd aplv-rs
cargo run --release -- --dir ..\samples --port 8767
```

詳細は [aplv-rs/README.md](aplv-rs/README.md) を参照してください。

## Java 8 + SQLite 版

数 GB 級の大容量ログ向けに **Java 8 + SQLite** 実装（`aplv-java/`）を同梱しています。
依存関係管理は Maven、Web サーバ機能は JDK 内蔵 `com.sun.net.httpserver` による自前実装です（Tomcat 等不要）。

```powershell
cd aplv-java
mvn -q clean package
java -jar target\aplv-java.jar --dir ..\samples --port 8768
```

複数ファイルの並列パース・byte offset によるオンデマンド読み出し・SQLite インデックス永続化で大容量ログに対応します。
詳細は [aplv-java/README.md](aplv-java/README.md) を参照してください。

## 3 実装の比較

| | Python 版 | Rust 版 | Java 8 版 |
|---|-----------|---------|-----------|
| ディレクトリ | `aplv/` | `aplv-rs/` | `aplv-java/` |
| 依存管理 | pip（標準ライブラリのみ） | Cargo | Maven |
| Web サーバ | `http.server`（標準） | axum | `com.sun.net.httpserver`（自前） |
| インデックス | SQLite | SQLite | SQLite |
| 全文検索 | FTS5 trigram（`--fts`） | FTS5 trigram（`--fts`） | FTS5 trigram（`--fts`） |
| ポート | 8766 | 8767 | 8768 |
