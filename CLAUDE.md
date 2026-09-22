# application-log-viewer (aplv)

Java アプリケーションのログを Web UI で閲覧・検索する障害調査ツール。
Java 8 / JDK 内蔵 HTTP サーバ / SQLite インデックス。実装は `aplv-java/`。

## ルール

`.cursor/rules/` のルールは Cursor 用に書かれているが、**Claude Code でも同じ内容を適用する**。
各ファイル冒頭の `description` / `globs` / `alwaysApply` は Cursor 用のメタ情報なので、
そこは読み飛ばして本文だけを適用する。

@.cursor/rules/performance-claims.mdc
@.cursor/rules/powershell-encoding.mdc

ルールを追加・変更するときは `.cursor/rules/` 側を正本として直し、この一覧にも追記する。

## ビルドとテスト

`pom.xml` はリポジトリ直下ではなく `aplv-java/` にある。直下から実行するときは `-f` を付ける。

```bash
mvn -f aplv-java/pom.xml test           # テスト
mvn -q -f aplv-java/pom.xml package     # 実行可能 JAR → aplv-java/target/aplv-java.jar
java -jar aplv-java/target/aplv-java.jar --dir samples --port 8766
```

`cd aplv-java && mvn test` でもよいが、シェルの作業ディレクトリが戻る環境では
`-f` を使うほうが確実。

## 押さえておくこと

- 索引は `tmp/aplv/{sha256(ログディレクトリの絶対パス)}.db`。`APLV_HOME` で保存先を
  変えられるので、**新旧のビルドを同じログに対して同時に動かして比較できる**
- ログファイルの mtime + サイズの指紋が一致すれば索引を再利用する。作り直させたいときは
  該当の `tmp/aplv/*.db` を削除する
- パースは複数ファイルを並列、SQLite への書き込みは単一スレッドに集約している
- 取り込むログの書式は `LogFormatSpec`（組み込みの `LogFormat` か、利用者が
  `aplv-log-formats.txt` に正規表現で定義した `CustomLogFormat`）で切り替える。
  **取り込み開始時に 1 つへ確定させる**ため、書式を増やしても 1 行あたりの判定は
  1 書式ぶんで済む。この前提を崩さないこと（行ごとに複数書式を試すと継続行の扱いが
  重くなる）。実測は [docs/performance-report.md](docs/performance-report.md) の 9・11 章
- 利用者定義の書式は画面の「書式の管理」からも、`aplv-log-formats.txt` の手編集でも
  入れられる。**どちらも `LogFormatStore.create` で同じ検査を通すこと**（別々に検査すると、
  画面では登録できるのにファイルからは読めない書式ができる）
- 利用者定義の書式は正規表現で 1 行ずつ照合するぶん重い（取り込み全体で +5.3%）。
  **この重さを払うのは、その書式を選んだ取り込みだけ**にすること。組み込み書式の
  経路を変えない（`LogIndex` の取り込みループの分岐はループの外で 1 回だけ材料を取る）
- 利用者定義の書式では、`CustomLogFormat.parse` が「正規表現が当たったか」も返す。
  **当たったのに読めなかった行（日時が壊れている）は読み飛ばしとして数える**こと ――
  直前のエントリの本文へ混ぜると、日時書式の間違いが画面のどこにも出ない。
  正規表現がそもそも当たらない行は、今までどおり継続行として本文に入れる
- 利用者が書いた正規表現は後戻りが爆発しうるので、照合させる文字数に上限を置いて
  取り込みごと失敗させる（`CustomLogFormat.BoundedCharSequence`）。固まらせない。
  **照合する経路はすべて `CustomLogFormat.guarded` を通すこと**（取り込みだけ包んで
  試し打ちを素通しにすると、同じ書式が試し打ちでだけ原因不明の 500 になる）
- 書式は索引のフィンガープリントに含まれる。変えると索引は作り直される。
  組み込み書式のフィンガープリントは `format:<id>` のまま変えないこと
  （変えると利用者の既存の索引がすべて作り直しになる）
- エントリ本文（スタックトレース）は DB に持たず、元ファイルの byte offset から都度読む。
  一覧 API の `raw` は 4096 文字で切り詰める（全文は詳細 API が返す）
- 索引は取込中ではなく **取込後にまとめて作る**。理由と実測は `LogIndex` のコメントと
  [docs/performance-report.md](docs/performance-report.md) を参照

## Windows 環境での注意

- コンソールは cp932。日本語を含むファイルを生成・読み込みするスクリプトは
  UTF-8 を明示する（Python なら `open(..., encoding="utf-8")`）。
  コミットメッセージ自体は UTF-8 で正しく保存されるので、**ターミナル表示が
  文字化けしても内容は壊れていない**（`git log` の出力をバイト列で確認すればよい）
- `tmp/` は `.gitignore` 済み。ベンチマーク用の大きなログはリポジトリ内に置かない
