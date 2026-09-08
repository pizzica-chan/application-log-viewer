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
mvn -f aplv-java/pom.xml test           # テスト（72 件）
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
