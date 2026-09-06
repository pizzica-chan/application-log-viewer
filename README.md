# Application Log Viewer (aplv)

Java アプリケーションのログ（複数ファイル）を **Web UI** で閲覧・検索する障害調査用ツールです。

指定ディレクトリ配下のログファイルを **再帰的に探索** し、複数ファイルを **時系列順に統合** して表示します。

## 機能

- ディレクトリを UI から選択し、配下のログファイルを再帰的に読み込み
- 複数ファイルを時系列ソートして一覧表示
- 各ログ行に **ログファイル名** と行番号を保持
- レベル / ロガー / スレッド / メッセージ / 日時 / 全文 / ログファイルでのフィルタ
- 行クリックで生ログ（スタックトレース含む）とメタ情報を表示
- ページング対応

大容量ログ向けに **SQLite インデックス** をリポジトリ直下の `tmp/aplv/` に保存します。2 回目以降はファイル変更がなければインデックスを再利用するため、起動が速くなります。7 日以上未使用または件数超過の古い DB は自動削除されます。

全文検索（grep）は **SQLite FTS5（trigram トークナイザ）** で高速化できます（**起動時に `--fts` を指定したときのみ有効**）。検索語が正規表現メタ文字を含まないプレーンな 3 文字以上の文字列のときは、FTS5 でヒット候補を一括で絞り込んでから生ログを照合します（正規表現パターンや 2 文字以下の場合は従来どおり全件スキャンに自動フォールバック）。FTS5 索引は本文の複製を持たない contentless 構成のため、ディスク使用量の増加を抑えつつ、結果は従来の正規表現検索と完全に一致します。

`--fts` を指定しない既定動作では FTS5 索引を作らないため**初回のインデックス構築が高速**です（grep は全件スキャンになります）。検索を多用しインデックス構築時間を許容できる場合に `--fts` を付けてください。

## 前提

- **Docker で動作確認（おすすめ）:** [Docker Desktop](https://www.docker.com/products/docker-desktop/)（ホストに JDK/Maven 不要）
- **ローカル開発:** JDK 8 以上（`javac` を含む JDK。実行のみなら JRE 8 でも可）、Maven 3.6 以上

## ビルド

```powershell
cd D:\workspace\application-log-viewer\aplv-java
mvn -q clean package
```

依存込みの実行可能 JAR `target/aplv-java.jar` が生成されます。

## 起動

```powershell
java -jar aplv-java\target\aplv-java.jar
```

またはリポジトリ直下の `start.bat` を実行します（JAR が無い場合は自動ビルド）。

ブラウザで http://127.0.0.1:8766 を開きます。

起動時にログディレクトリを指定する場合:

```powershell
java -jar aplv-java\target\aplv-java.jar --dir C:\logs\app
java -jar aplv-java\target\aplv-java.jar --dir samples --port 8766
```

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--dir` | 起動時に読み込むログディレクトリ（省略時は UI から選択） | — |
| `--host` | 待ち受けアドレス | `127.0.0.1` |
| `--port` | 待ち受けポート | `8766` |
| `--fts` | 全文検索を FTS5 で高速化（インデックス構築は遅くなる） | 無効 |

## Docker で動作確認（ローカル）

JDK/Maven をインストールせず、コンテナだけでサンプルログの閲覧まで試せます。

### ワンクリック起動（おすすめ）

1. [Docker Desktop](https://www.docker.com/products/docker-desktop/) をインストールする。
2. リポジトリ直下の **`docker-up.bat`** をダブルクリック（または `scripts\one-click-up.cmd`）。

Docker Desktop が止まっていれば **自動起動・待機**（最大約 3 分）→ **ビルド** → **起動** → **ブラウザで http://localhost:8766 を開く** まで一気に実行されます。`samples/` のログは自動読み込みされます。

| 操作 | コマンド |
|------|----------|
| 起動 | `docker-up.bat` |
| 停止 | `docker-down.bat` |
| ソース変更の反映 | `scripts\one-click-restart.cmd` |
| ログ追従 | `docker-up.bat` 相当を PowerShell から `-FollowLogs` 付きで実行（下記） |

PowerShell から起動する場合:

```powershell
.\scripts\one-click-up.ps1
.\scripts\one-click-up.ps1 -FollowLogs   # 起動後にログ追従
```

### 手動（docker compose のみ）

**Docker Desktop を先に起動**してから、リポジトリ直下で実行します。

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f app
docker compose down
```

### マウントと環境変数

| 項目 | 説明 |
|------|------|
| `./samples` → `/app/logs/samples` | サンプルログ（読み取り専用）。起動時に `--dir` で自動読み込み |
| `./tmp` → `/app/tmp` | SQLite インデックスの永続化 |
| `APLV_LOG_DIR` | ホスト側のログディレクトリを差し替え（例: `$env:APLV_LOG_DIR="C:\logs\app"`） |
| `APLV_HOME=/app` | コンテナ内のリポジトリルート（インデックス保存先の基準） |

**ホストポート:** 既定は **`8766`** で、`127.0.0.1` にのみ公開します（無認証のため LAN へ露出させない）。競合する場合は `docker-compose.yml` の `ports` を `"127.0.0.1:18766:8766"` のように変更し、ブラウザも合わせてください。

**注意:** ローカル検証専用です。インターネットに公開しないでください。

## Web UI の使い方

1. 画面上部の **ログディレクトリ** にパスを入力するか、**参照...** でディレクトリを選択
2. **読み込み** をクリックすると、配下のログファイルが再帰的に探索・読み込まれます
3. フィルタを設定して **検索**（Enter キーでも可）
4. 行をクリックすると、ログファイル・行番号・生ログ（スタックトレース含む）を表示

### 読み込み対象のログファイル

以下のファイル名パターンに一致するファイルを再帰的に探索します。

- `application*.log*`, `server*.log*`, `spring*.log*`
- `catalina*.log*`, `localhost*.log*`, `app*.log*`
- `*.log`, `*.out`

`.git` や `node_modules` などのディレクトリはスキップします。圧縮ファイル（`.gz` 等）は未対応です。

### フィルタ

| 項目 | 説明 | 例 |
|------|------|-----|
| レベル | ログレベル | `ERROR` / `WARN,ERROR` |
| ロガー | ロガー名（正規表現） | `HogeController` |
| スレッド | スレッド名（正規表現） | `main` |
| メッセージ | 1行目メッセージ（正規表現） | `NullPointer` |
| 開始 / 終了 | 日時範囲 | 日時ピッカー |
| 全文検索 | エントリ全体（スタックトレース含む）への正規表現 | `Exception` |
| ログファイル | 絶対パスへの正規表現 | `application.log` |

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

Thread に `[]` を含む場合（例: `main:[12345] ch[00]`）も対応します。

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
java -jar aplv-java\target\aplv-java.jar --dir samples

# ブラウザで以下のような調査を行う
# - レベル: ERROR
# - 開始 / 終了: 障害時間帯を指定
# - 全文検索: NullPointerException
# - ロガー: HogeController
```

## パフォーマンス設計

| 項目 | 内容 |
|------|------|
| メモリ | エントリ本文（スタックトレース）は DB に載せず、元ファイルの **byte offset** から都度読み出し |
| インデックス | 解析結果を `{リポジトリ}/tmp/aplv/`（SQLite）に **永続化** |
| 解析 | バイト列でヘッダ行を高速判定し、一致行のみ UTF-8 デコード |
| 並列化 | 複数ログファイルを **並列パース**、SQLite 書き込みは単一ライタースレッド |
| Web サーバ | JDK 内蔵 `com.sun.net.httpserver` による自前実装（Tomcat 等不要） |

詳細は [aplv-java/README.md](aplv-java/README.md) を参照してください。

## テスト

```powershell
cd aplv-java
mvn test
```

## ライセンス

MIT
