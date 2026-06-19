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

## インストール

```powershell
cd D:\workspace\application-log-viewer
pip install -e .
```

依存パッケージは不要（Python 3.10+、標準ライブラリのみ）です。

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
