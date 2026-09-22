package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LogIndex} / {@link LogQuery} の結合試験（一時ディレクトリ + SQLite）。
 *
 * <p>試験内容:
 * <ul>
 *   <li>ログファイルからのインデックス構築（スタックトレース付きエントリの byte 範囲記録）</li>
 *   <li>ファイル更新・FTS フラグ変更時の再インデックス判定（{@link LogIndex#needsRebuild}）</li>
 *   <li>レベル・grep・logger・日時範囲・thread・message・source 各フィルタと複合条件</li>
 *   <li>FTS5 有効/無効それぞれでの全文 grep（スタックトレース内文字列の検索）</li>
 *   <li>複数ファイルの並列インデックスと ts_millis 昇順マージ</li>
 *   <li>offset / limit によるページング</li>
 *   <li>SQL 押し下げ経路と全件走査経路が同一結果を返すこと</li>
 *   <li>取込後の索引構成と統計（旧構成からの移行を含む）</li>
 *   <li>索引作成に失敗したときの後始末（中途半端な状態を残さないこと）</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>Web UI の検索・フィルタ・ページングがインデックス上で正しく動作する</li>
 *   <li>スタックトレース本文は DB に載せず byte 範囲からオンデマンド読み出しできる</li>
 *   <li>ログ追記後や FTS 設定変更時に stale インデックスを検知できる</li>
 *   <li>FTS 非対応環境でも grep が全件スキャンで同等の結果を返す</li>
 *   <li>正規表現の有無でクエリ経路が変わっても件数・並び・ページ境界が変わらない</li>
 *   <li>索引は取込後に作られ、参照時に必要な構成が揃っている</li>
 *   <li>索引作成が失敗しても、レベル絞り込みに効く索引か再構築の余地のどちらかは残る</li>
 * </ul>
 */
class LogIndexTest {

    private Path writeLog(Path dir, String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return PathUtil.resolve(path);
    }

    /**
     * 利用者定義の書式で<strong>日時だけ読めない行</strong>を、本文に混ぜず読み飛ばしとして
     * 数えること。
     *
     * <p>正規表現に一致したのに日時を読めない行は、直すべき書式がある行だ。継続行
     * （スタックトレース）と同じ扱いで直前のエントリの本文へ足してしまうと、読み飛ばし
     * 件数は 0 のまま、日時書式の間違いが画面のどこにも出ない。
     *
     * <p>正規表現にそもそも一致しない行は、今までどおり本文として扱う。
     */
    @Test
    void countsLinesWhoseTimestampIsUnreadable(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026/06/15 00:00:01.000 INFO (main) com.example.Foo : ok\n"
                        // 日時だけ壊れている（月が 13）。正規表現には一致する
                        + "2026/13/15 00:00:02.000 INFO (main) com.example.Foo : broken\n"
                        // 正規表現にも一致しない行は継続行として本文に入る
                        + "\tat com.example.Foo.run(Foo.java:10)\n");
        CustomLogFormat custom = LogFormatStore.create("my-app", "自社",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (?<level>\\w+) "
                        + "\\((?<thread>[^)]*)\\) (?<logger>\\S+) : (?<message>.*)$",
                "yyyy/MM/dd HH:mm:ss.SSS");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.BuildResult built = LogIndex.buildIndex(conn,
                    Collections.singletonList(log), null, false, LogFormatSpec.of(custom));
            assertEquals(1, built.entryCount);
            assertEquals(1, built.skippedLines,
                    "日時を読めなかった 1 行だけを数える（継続行は数えない）");
        }
    }

    /** スタックトレース行を同一エントリの byte 範囲に含め、readEntryRaw で復元できること。 */
    @Test
    void buildIndexWithStackTrace(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - failed\n"
                        + "java.lang.RuntimeException: boom\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.BuildResult built = LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);
            assertEquals(2, built.entryCount);
            assertEquals(0, built.skippedLines);

            LogIndex.EntryRow err = LogIndex.findEntry(conn, PathUtil.normalizePath(log), 1);
            assertNotNull(err);
            String raw = LogIndex.readEntryRaw(log, err.byteOffset, err.endByteOffset);
            assertTrue(raw.contains("RuntimeException"));
            assertTrue(raw.contains("Foo.run"));
            assertEquals(0, LogIndex.getSkippedLineCount(conn));
        }
    }

    /** ログファイル追記後に needsRebuild が true になること（差分再構築のトリガー）。 */
    @Test
    void needsRebuildAfterContentChange(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n");
        List<Path> paths = Collections.singletonList(log);

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, paths, null, false, LogFormatSpec.DEFAULT);
            assertFalse(LogIndex.needsRebuild(conn, paths, false, LogFormatSpec.DEFAULT));

            Files.write(log,
                    "2026-06-15 00:00:02.000[main][INFO][com.example.B] - two\n"
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            assertTrue(LogIndex.needsRebuild(conn, paths, false, LogFormatSpec.DEFAULT));
        }
    }

    /**
     * ログ書式を切り替えたら needsRebuild が true になること。
     *
     * <p>書式が変われば解析結果そのものが変わるため、古い索引を使い回してはいけない。
     * フィンガープリントに書式が入っていることを、この試験で担保する。
     */
    @Test
    void needsRebuildAfterLogFormatChange(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:19:11.705[main][INFO][com.example.Hoge] - メッセージ\n");
        List<Path> paths = Collections.singletonList(log);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, paths, null, false, LogFormatSpec.DEFAULT);
            assertFalse(LogIndex.needsRebuild(conn, paths, false, LogFormatSpec.DEFAULT),
                    "同じ書式なら索引を再利用する");
            for (LogFormat other : LogFormat.values()) {
                if (other == LogFormat.DEFAULT) {
                    continue;
                }
                assertTrue(LogIndex.needsRebuild(conn, paths, false, LogFormatSpec.of(other)),
                        other.id() + " に変えたら作り直す");
            }
        }
    }

    /** 取り込みに使った書式が meta に残り、読み戻せること（再利用時の表示に使う）。 */
    @Test
    void storesLogFormatInMeta(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15T00:19:11.705 INFO [main] com.example.Hoge - メッセージ\n");
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormatSpec.of(LogFormat.ISO8601));
            assertEquals("iso8601", LogIndex.getLogFormatId(conn));
        }
    }

    /**
     * 利用者定義の書式でも取り込めること。組み込み書式と同じく、継続行は
     * 直前のエントリの本文になり、認識できない行だけが skipped に数えられる。
     */
    @Test
    void buildsIndexWithCustomFormat(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : 開始\n"
                        + "2026/06/15 00:19:11.706 ERROR (main) com.example.Hoge : 失敗\n"
                        + "java.lang.IllegalStateException: だめ\n"
                        + "\tat com.example.Hoge.run(Hoge.java:12)\n");
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormatSpec.of(customFormat()));
            assertEquals("my-app", LogIndex.getLogFormatId(conn));
            assertEquals(0, LogIndex.getSkippedLineCount(conn));

            LogQuery.Result all = LogQuery.queryLogs(conn, new QueryFilter(), 0, 10);
            assertEquals(2, all.total);
            assertEquals("INFO", all.page.get(0).level);
            assertEquals("main", all.page.get(0).thread);
            assertEquals("com.example.Hoge", all.page.get(0).logger);
            assertEquals("開始", all.page.get(0).message);

            // 継続行は 2 件目の本文に付く（DB には持たず元ファイルから読み直す）
            QueryFilter byGrep = new QueryFilter();
            byGrep.grepRe = QueryFilter.compileRegex("IllegalStateException");
            byGrep.grepText = "IllegalStateException";
            LogQuery.Result hit = LogQuery.queryLogs(conn, byGrep, 0, 10);
            assertEquals(1, hit.total);
            assertEquals("失敗", hit.page.get(0).message);
        }
    }

    /**
     * 書式の中身を変えたら索引を作り直すこと。
     * id だけをフィンガープリントに入れていると、正規表現を直したのに
     * 古い解析結果がそのまま使われてしまう。
     */
    @Test
    void needsRebuildAfterCustomPatternChange(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : 開始\n");
        List<Path> paths = Collections.singletonList(log);
        LogFormatSpec before = LogFormatSpec.of(customFormat());
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, paths, null, false, before);
            assertFalse(LogIndex.needsRebuild(conn, paths, false, before),
                    "同じ定義なら索引を再利用する");

            LogFormatSpec sameIdOtherPattern = LogFormatSpec.of(new CustomLogFormat(
                    "my-app", "自社アプリ形式", CUSTOM_PATTERN.replace(" : ", " - "), CUSTOM_TS));
            assertTrue(LogIndex.needsRebuild(conn, paths, false, sameIdOtherPattern),
                    "id が同じでも正規表現を変えたら作り直す");

            LogFormatSpec sameIdOtherTs = LogFormatSpec.of(new CustomLogFormat(
                    "my-app", "自社アプリ形式", CUSTOM_PATTERN, "yyyy/MM/dd HH:mm:ss"));
            assertTrue(LogIndex.needsRebuild(conn, paths, false, sameIdOtherTs),
                    "日時書式を変えたら作り直す");
        }
    }

    /**
     * 暴走する正規表現は取り込みを止めること。返ってこないまま固まるより、
     * どの書式のどこで止めたかが分かる失敗にする。
     */
    @Test
    void failsLoudlyOnCatastrophicCustomPattern(@TempDir Path tmp) throws Exception {
        StringBuilder line = new StringBuilder();
        // 20 文字で十分に打ち切り基準を超える。長くすると、保護を外す変異を入れたときに
        // 試験が赤くならず返ってこなくなる。
        for (int i = 0; i < 20; i++) {
            line.append('a');
        }
        Path log = writeLog(tmp, "app.log", line + "!\n");
        LogFormatSpec spec = LogFormatSpec.of(
                new CustomLogFormat("bad", "暴走", "^(?<ts>(a+)+b)$", "yyyy"));
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            IOException e = assertThrows(IOException.class,
                    () -> LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                            spec));
            assertTrue(e.getMessage().contains("bad"), e.getMessage());
            assertTrue(e.getMessage().contains("1 行目"), e.getMessage());
        }
    }

    private static final String CUSTOM_PATTERN =
            "^(?<ts>\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (?<level>\\w+) "
                    + "\\((?<thread>[^)]*)\\) (?<logger>\\S+) : (?<message>.*)$";
    private static final String CUSTOM_TS = "yyyy/MM/dd HH:mm:ss.SSS";

    private static CustomLogFormat customFormat() {
        return new CustomLogFormat("my-app", "自社アプリ形式", CUSTOM_PATTERN, CUSTOM_TS);
    }

    /** FTS 有効/無効の切り替え時に needsRebuild が true になること。 */
    @Test
    void needsRebuildWhenFtsFlagChanges(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n");
        List<Path> paths = Collections.singletonList(log);

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, paths, null, false, LogFormatSpec.DEFAULT);
            assertFalse(LogIndex.needsRebuild(conn, paths, false, LogFormatSpec.DEFAULT));
            assertTrue(LogIndex.needsRebuild(conn, paths, true, LogFormatSpec.DEFAULT));
        }
    }

    /** レベル・grep・logger の単独/複合フィルタが期待件数・内容で返ること。 */
    @Test
    void queryFiltersByLevelAndGrep(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - error one\n"
                        + "stack line here\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - info two\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            QueryFilter byLevel = new QueryFilter();
            byLevel.levels = QueryFilter.parseLevelFilter("ERROR");
            LogQuery.Result r1 = LogQuery.queryLogs(conn, byLevel, 0, 10);
            assertEquals(1, r1.total);
            assertEquals("ERROR", r1.page.get(0).level);

            QueryFilter byGrep = new QueryFilter();
            byGrep.grepRe = QueryFilter.compileRegex("stack line");
            byGrep.grepText = "stack line";
            LogQuery.Result r2 = LogQuery.queryLogs(conn, byGrep, 0, 10);
            assertEquals(1, r2.total);

            QueryFilter combined = new QueryFilter();
            combined.levels = QueryFilter.parseLevelFilter("ERROR,INFO");
            combined.loggerRe = QueryFilter.compileRegex("Foo");
            combined.grepRe = QueryFilter.compileRegex("stack line");
            combined.grepText = "stack line";
            LogQuery.Result r3 = LogQuery.queryLogs(conn, combined, 0, 10);
            assertEquals(1, r3.total);
            assertEquals("com.example.Foo", r3.page.get(0).logger);
        }
    }

    /** FTS5 有効時、スタックトレース内の文字列を grep でき、存在しない語は 0 件となること。 */
    @Test
    void grepUsesFtsForStacktrace(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - boom\n"
                        + "java.lang.NullPointerException: bad\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, true, LogFormatSpec.DEFAULT);
            assertTrue(LogIndex.ftsAvailable(conn));

            QueryFilter hit = new QueryFilter();
            hit.grepRe = QueryFilter.compileRegex("NullPointerException");
            hit.grepText = "NullPointerException";
            LogQuery.Result r = LogQuery.queryLogs(conn, hit, 0, 10);
            assertEquals(1, r.total);
            assertEquals("com.example.Foo", r.page.get(0).logger);

            QueryFilter miss = new QueryFilter();
            miss.grepRe = QueryFilter.compileRegex("zzzznotfound");
            miss.grepText = "zzzznotfound";
            LogQuery.Result r2 = LogQuery.queryLogs(conn, miss, 0, 10);
            assertEquals(0, r2.total);
        }
    }

    /** FTS5 無効時も全件スキャンでスタックトレース内 grep が機能すること。 */
    @Test
    void grepWorksWithoutFts(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - boom\n"
                        + "java.lang.NullPointerException: bad\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);
            assertFalse(LogIndex.ftsAvailable(conn));

            QueryFilter hit = new QueryFilter();
            hit.grepRe = QueryFilter.compileRegex("NullPointerException");
            hit.grepText = "NullPointerException";
            LogQuery.Result r = LogQuery.queryLogs(conn, hit, 0, 10);
            assertEquals(1, r.total);
            assertEquals("com.example.Foo", r.page.get(0).logger);
        }
    }

    /** 複数ファイルをインデックスし、ts_millis 昇順で統合ソートされること。 */
    @Test
    void parallelIndexAcrossMultipleFiles(@TempDir Path tmp) throws Exception {
        Path a = writeLog(tmp, "application.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - a1\n"
                        + "2026-06-15 00:00:03.000[main][INFO][com.example.A] - a2\n");
        Path b = writeLog(tmp, "server.log",
                "2026-06-15 00:00:02.000[main][WARN][com.example.B] - b1\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            assertEquals(3, LogIndex.buildIndex(conn, Arrays.asList(a, b), null, false, LogFormatSpec.DEFAULT).entryCount);

            QueryFilter all = new QueryFilter();
            LogQuery.Result r = LogQuery.queryLogs(conn, all, 0, 10);
            assertEquals(3, r.page.size());
            assertEquals("a1", r.page.get(0).message);
            assertEquals("b1", r.page.get(1).message);
            assertEquals("a2", r.page.get(2).message);
        }
    }

    /** sinceMillis / untilMillis による日時範囲フィルタが機能すること。 */
    @Test
    void queryFiltersByTimeRange(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - early\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.B] - mid\n"
                        + "2026-06-15 00:00:03.000[main][INFO][com.example.C] - late\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            QueryFilter range = new QueryFilter();
            range.sinceMillis = TimeUtil.parseUiDatetime("2026-06-15 00:00:02.000");
            range.untilMillis = TimeUtil.parseUiDatetime("2026-06-15 00:00:02.000");
            LogQuery.Result r = LogQuery.queryLogs(conn, range, 0, 10);
            assertEquals(1, r.total);
            assertEquals("mid", r.page.get(0).message);
        }
    }

    /** thread / message / source の正規表現フィルタが機能すること。 */
    @Test
    void queryFiltersByThreadMessageAndSource(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[pool-1-thread-1][WARN][com.example.Foo] - retry now\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            QueryFilter byThread = new QueryFilter();
            byThread.threadRe = QueryFilter.compileRegex("pool-1");
            LogQuery.Result r1 = LogQuery.queryLogs(conn, byThread, 0, 10);
            assertEquals(1, r1.total);
            assertEquals("com.example.Foo", r1.page.get(0).logger);

            QueryFilter byMessage = new QueryFilter();
            byMessage.messageRe = QueryFilter.compileRegex("retry");
            LogQuery.Result r2 = LogQuery.queryLogs(conn, byMessage, 0, 10);
            assertEquals(1, r2.total);

            QueryFilter bySource = new QueryFilter();
            bySource.sourceRe = QueryFilter.compileRegex("app\\.log");
            LogQuery.Result r3 = LogQuery.queryLogs(conn, bySource, 0, 10);
            assertEquals(2, r3.total);
        }
    }

    /** total は全ヒット数、page は offset/limit で分割されること。 */
    @Test
    void queryPagination(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.A] - two\n"
                        + "2026-06-15 00:00:03.000[main][INFO][com.example.A] - three\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            LogQuery.Result page1 = LogQuery.queryLogs(conn, new QueryFilter(), 0, 2);
            assertEquals(3, page1.total);
            assertEquals(2, page1.page.size());
            assertEquals("one", page1.page.get(0).message);

            LogQuery.Result page2 = LogQuery.queryLogs(conn, new QueryFilter(), 2, 2);
            assertEquals(3, page2.total);
            assertEquals(1, page2.page.size());
            assertEquals("three", page2.page.get(0).message);
        }
    }

    /** ファイル先頭の孤立行のみ skipped にカウントし、スタックトレース行は含めないこと。 */
    @Test
    void countsOrphanSkippedLinesOnly(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "# rotation marker\n"
                        + "not a log line\n"
                        + "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - failed\n"
                        + "java.lang.RuntimeException: boom\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.BuildResult built = LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);
            assertEquals(2, built.entryCount);
            assertEquals(2, built.skippedLines);
            assertEquals(2, built.skippedSamples.size());
            assertEquals(1, built.skippedSamples.get(0).lineNo);
            assertEquals("# rotation marker", built.skippedSamples.get(0).preview);

            assertEquals(2, LogIndex.getSkippedLineCount(conn));
            assertFalse(LogIndex.getSkippedLineSamples(conn).isEmpty());
            assertFalse(LogIndex.needsRebuild(conn, Collections.singletonList(log), false, LogFormatSpec.DEFAULT));
            assertEquals(2, LogIndex.getSkippedLineCount(conn));
        }
    }

    /** 5 行のログ。SQL 押し下げ経路と全件走査経路の比較に使う。 */
    private Path writeFiveLines(Path dir) throws IOException {
        return writeLog(dir, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n"
                        + "2026-06-15 00:00:02.000[main][ERROR][com.example.A] - two\n"
                        + "2026-06-15 00:00:03.000[main][INFO][com.example.A] - three\n"
                        + "2026-06-15 00:00:04.000[main][ERROR][com.example.A] - four\n"
                        + "2026-06-15 00:00:05.000[main][INFO][com.example.A] - five\n");
    }

    private List<String> messagesOf(List<LogIndex.EntryRow> rows) {
        List<String> out = new ArrayList<>();
        for (LogIndex.EntryRow r : rows) {
            out.add(r.message);
        }
        return out;
    }

    /** 同じ絞り込みを SQL 押し下げ経路と全件走査経路の双方で行い、結果が一致すること。 */
    private void assertSamePage(Connection conn, QueryFilter pushdown, long offset, long limit)
            throws Exception {
        QueryFilter scan = new QueryFilter();
        scan.levels = pushdown.levels;
        scan.sinceMillis = pushdown.sinceMillis;
        scan.untilMillis = pushdown.untilMillis;
        // 何にでも一致する正規表現を足すだけで走査経路に入る（絞り込み結果は変わらない）。
        scan.messageRe = QueryFilter.compileRegex(".");

        LogQuery.Result a = LogQuery.queryLogs(conn, pushdown, offset, limit);
        LogQuery.Result b = LogQuery.queryLogs(conn, scan, offset, limit);
        assertEquals(b.total, a.total, "総ヒット数");
        assertEquals(messagesOf(b.page), messagesOf(a.page), "ページ内容と並び");
    }

    /**
     * 正規表現・grep がないときの SQL 押し下げ経路（COUNT + LIMIT/OFFSET）が、
     * 全件走査経路と同じ件数・並び・ページ境界を返すこと。
     */
    @Test
    void pushdownMatchesScanPath(@TempDir Path tmp) throws Exception {
        Path log = writeFiveLines(tmp);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            for (long offset : new long[] {0, 2, 4, 10}) {
                assertSamePage(conn, new QueryFilter(), offset, 2);
            }

            QueryFilter byLevel = new QueryFilter();
            byLevel.levels = QueryFilter.parseLevelFilter("ERROR");
            assertSamePage(conn, byLevel, 0, 10);
            assertSamePage(conn, byLevel, 1, 10);

            QueryFilter byRange = new QueryFilter();
            byRange.sinceMillis = TimeUtil.parseUiDatetime("2026-06-15 00:00:02.000");
            byRange.untilMillis = TimeUtil.parseUiDatetime("2026-06-15 00:00:04.000");
            assertSamePage(conn, byRange, 0, 10);
            assertSamePage(conn, byRange, 1, 1);

            // 押し下げ経路でも総ヒット数はページ内件数ではなく全体を返すこと。
            LogQuery.Result page = LogQuery.queryLogs(conn, new QueryFilter(), 0, 2);
            assertEquals(5, page.total);
            assertEquals(2, page.page.size());
        }
    }

    /**
     * grep 経路では判定に使った生テキストを {@link LogIndex.EntryRow#raw} に残し、
     * 一覧 API が同じ内容を読み直さずに済むこと（grep なしでは設定しない）。
     */
    @Test
    void grepKeepsRawForPageRows(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - failed\n"
                        + "java.lang.RuntimeException: boom\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            QueryFilter grep = new QueryFilter();
            grep.grepText = "boom";
            grep.grepRe = QueryFilter.compileRegex("boom");
            LogQuery.Result hit = LogQuery.queryLogs(conn, grep, 0, 10);
            assertEquals(1, hit.total);

            LogIndex.EntryRow e = hit.page.get(0);
            assertNotNull(e.raw, "grep 判定で読んだ生テキストが保持されていること");
            assertTrue(e.raw.contains("java.lang.RuntimeException: boom"));
            assertTrue(e.raw.contains("at com.example.Foo.run(Foo.java:10)"));
            // byte 範囲から読み直した内容と一致すること（末尾の改行有無だけが差）。
            String reread = LogIndex.readEntryRaw(Paths.get(e.source), e.byteOffset, e.endByteOffset);
            assertEquals(reread, e.raw.trim());

            LogQuery.Result noGrep = LogQuery.queryLogs(conn, new QueryFilter(), 0, 10);
            assertNull(noGrep.page.get(0).raw, "grep なしでは raw を読まないこと");
        }
    }

    private List<String> indexNames(Connection conn) throws Exception {
        List<String> names = new ArrayList<>();
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'"
                             + " ORDER BY name")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private List<String> analyzedIndexes(Connection conn) throws Exception {
        List<String> names = new ArrayList<>();
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT idx FROM sqlite_stat1 WHERE tbl='entries' ORDER BY idx")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /**
     * 取込後に索引と統計が揃うこと。
     *
     * <p>索引は取込中ではなく取込後にまとめて作るため、buildIndex を通らずに
     * 索引が消えたままにならないことを担保する。
     */
    @Test
    void buildCreatesIndexesAndStatistics(@TempDir Path tmp) throws Exception {
        Path log = writeFiveLines(tmp);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            // スキーマ作成直後は索引を持たない（取込後にまとめて作るため）。
            assertTrue(indexNames(conn).isEmpty(), "初期状態では索引を作らない");

            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            assertEquals(Arrays.asList("idx_entries_level_ts", "idx_entries_ts"), indexNames(conn));
            assertEquals(Arrays.asList("idx_entries_level_ts", "idx_entries_ts"),
                    analyzedIndexes(conn), "作った索引すべての統計があること");
        }
    }

    /**
     * 旧構成の索引を持つ DB を開いても、参照に必要な構成へ揃えられること。
     *
     * <p>幅の狭い idx_entries_level が残っていると SQLite がそちらを選び、
     * 並べ直しが入って遅くなるため削除する。
     */
    @Test
    void ensureIndexesMigratesLegacyLayout(@TempDir Path tmp) throws Exception {
        Path log = writeFiveLines(tmp);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            // 旧バージョン相当の構成に戻す（level 単独索引あり・複合索引なし）。
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("DROP INDEX idx_entries_level_ts");
                st.execute("CREATE INDEX idx_entries_level ON entries(level)");
            }
            assertEquals(Arrays.asList("idx_entries_level", "idx_entries_ts"), indexNames(conn));

            LogIndex.ensureIndexes(conn);

            assertEquals(Arrays.asList("idx_entries_level_ts", "idx_entries_ts"), indexNames(conn));

            // 索引を入れ替えても検索結果は変わらないこと。
            QueryFilter byLevel = new QueryFilter();
            byLevel.levels = QueryFilter.parseLevelFilter("ERROR");
            LogQuery.Result r = LogQuery.queryLogs(conn, byLevel, 0, 10);
            assertEquals(2, r.total);
            assertEquals(Arrays.asList("two", "four"), messagesOf(r.page));
        }
    }

    /**
     * 索引の作成を失敗させる。
     *
     * <p>同名のテーブルを先に作っておくと {@code CREATE INDEX IF NOT EXISTS} は
     * 「同名のテーブルが既にある」で失敗する。SQLITE_BUSY / SQLITE_FULL を再現せずに
     * 「作成が失敗したときの後始末」だけを検証できる。
     */
    private void blockIndexCreation(Connection conn, String indexName) throws Exception {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + indexName + " (blocker INTEGER)");
        }
    }

    /**
     * 索引の張り直しが途中で失敗しても、レベル絞り込みに効く索引が消えないこと。
     *
     * <p>DDL は文ごとに確定するため、削除を作成より先に置くと CREATE が失敗した時点で
     * 「level に効く索引が一つもない」状態が残ってしまう。
     */
    @Test
    void ensureIndexesKeepsLevelIndexWhenCreateFails(@TempDir Path tmp) throws Exception {
        Path log = writeFiveLines(tmp);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false, LogFormatSpec.DEFAULT);

            // 旧バージョン相当の構成に戻したうえで、複合索引の作成を失敗させる。
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("DROP INDEX idx_entries_level_ts");
                st.execute("CREATE INDEX idx_entries_level ON entries(level)");
            }
            blockIndexCreation(conn, "idx_entries_level_ts");

            assertThrows(java.sql.SQLException.class, () -> LogIndex.ensureIndexes(conn));

            assertTrue(indexNames(conn).contains("idx_entries_level"),
                    "複合索引を作れなかったときは、幅の狭い方を消さずに残すこと");

            // 索引が残っているので検索結果も従来どおり。
            QueryFilter byLevel = new QueryFilter();
            byLevel.levels = QueryFilter.parseLevelFilter("ERROR");
            LogQuery.Result r = LogQuery.queryLogs(conn, byLevel, 0, 10);
            assertEquals(Arrays.asList("two", "four"), messagesOf(r.page));
        }
    }

    /**
     * 取込を確定した後で索引作成に失敗しても、entries を残さず後始末すること。
     *
     * <p>ここを素通りすると数百 MB の行が fingerprint なしで DB に残る。
     * 他の失敗経路と同じく、次回に再構築される状態へ戻す必要がある。
     */
    @Test
    void buildAbortsWhenIndexCreationFails(@TempDir Path tmp) throws Exception {
        Path log = writeFiveLines(tmp);
        List<Path> paths = Collections.singletonList(log);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            blockIndexCreation(conn, "idx_entries_level_ts");

            assertThrows(java.sql.SQLException.class,
                    () -> LogIndex.buildIndex(conn, paths, null, false, LogFormatSpec.DEFAULT));

            assertEquals(0, LogIndex.entryCount(conn), "取り込んだ行を残さないこと");
            assertTrue(LogIndex.needsRebuild(conn, paths, false, LogFormatSpec.DEFAULT),
                    "fingerprint を消して次回に再構築させること");
        }
    }

    /**
     * 前方向にまとめ読みするリーダが、都度読みと同じ内容を返すこと。
     * 戻る要求、窓（64 KiB）に収まらない大きさ、ファイル末尾を確かめる。
     */
    @Test
    void sequentialRawReaderMatchesDirectReads(@TempDir Path tmp) throws Exception {
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        Path file = tmp.resolve("raw.bin");
        Files.write(file, data);

        try (LogIndex.SequentialRawReader reader =
                new LogIndex.SequentialRawReader(file.toString())) {
            byte[] buf = new byte[300_000];
            // 前方向に少しずつ
            for (long offset = 0; offset < 150_000; offset += 1000) {
                int n = reader.read(offset, 500, buf);
                assertEquals(500, n);
                assertArrayEquals(Arrays.copyOfRange(data, (int) offset, (int) offset + 500),
                        Arrays.copyOf(buf, n));
            }
            // 戻る（窓の外）
            int n = reader.read(10, 100, buf);
            assertEquals(100, n);
            assertArrayEquals(Arrays.copyOfRange(data, 10, 110), Arrays.copyOf(buf, n));
            // 窓に収まらない大きさ
            n = reader.read(1000, 150_000, buf);
            assertEquals(150_000, n);
            assertArrayEquals(Arrays.copyOfRange(data, 1000, 151_000), Arrays.copyOf(buf, n));
            // ファイル末尾は読めたぶんだけ返す
            n = reader.read(data.length - 10, 100, buf);
            assertEquals(10, n);
            assertArrayEquals(Arrays.copyOfRange(data, data.length - 10, data.length),
                    Arrays.copyOf(buf, n));
        }
    }

    /** バイト列の部分一致が、デコードしてからの String#contains と一致すること。 */
    @Test
    void containsBytesMatchesStringContains() {
        String[] haystacks = {
            "sessionId=8F3A2C91D4E6B7A0.node1 items=3",
            "セッションID=あいうえお かきくけこ",
            "no match here",
            "末尾に置く 8F3A",
        };
        String[] needles = {"8F3A2C91D4E6B7A0.node1", "あいうえお", "いう", "8F3A", "zzz"};
        for (String h : haystacks) {
            byte[] hay = h.getBytes(StandardCharsets.UTF_8);
            for (String n : needles) {
                byte[] needle = n.getBytes(StandardCharsets.UTF_8);
                assertEquals(h.contains(n), LogIndex.containsBytes(hay, hay.length, needle),
                        h + " / " + n);
            }
        }
    }

    /**
     * 大文字小文字を無視するバイト列照合が、既存の grep（ASCII だけ畳む正規表現）と
     * 同じ判定になること。多バイト文字は畳まれない。
     */
    @Test
    void containsBytesIgnoreAsciiCaseMatchesRegex() {
        String[] haystacks = {
            "sessionId=8F3A2C91D4E6B7A0 items=3",
            "SESSIONID=ABC",
            "決済が拒否されました PaymentException",
            "ＡＢＣ 全角",
            // 「ぢ」(E3 81 A2) は「あ」(E3 81 82) と 0x20 しか違わない。多バイトまで畳むと誤検知する
            "ぢから",
        };
        String[] needles = {"sessionid", "SESSIONID", "PaymentException", "paymentexception",
            "決済", "ＡＢＣ", "abc", "見つからない", "あ", "ぢ"};
        for (String h : haystacks) {
            byte[] hay = h.getBytes(StandardCharsets.UTF_8);
            for (String n : needles) {
                boolean byRegex = java.util.regex.Pattern
                        .compile(java.util.regex.Pattern.quote(n),
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(h).find();
                byte[] lower = LogIndex.toLowerAscii(n.getBytes(StandardCharsets.UTF_8));
                assertEquals(byRegex,
                        LogIndex.containsBytesIgnoreAsciiCase(hay, hay.length, lower),
                        h + " / " + n);
            }
        }
    }

    /**
     * grep のリテラル経路（バイト列照合）と正規表現経路が同じ結果を返すこと。
     * 大文字小文字・多バイト・3 文字未満・スタックトレース内の語で確かめる。
     */
    @Test
    void grepLiteralPathMatchesRegexPath(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - 決済に失敗 sessionId=ABC123\n"
                        + "java.lang.RuntimeException: PaymentException session=ABC123\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - SESSIONID=xyz\n"
                        + "2026-06-15 00:00:03.000[main][INFO][com.example.Foo] - 無関係な行\n");
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormatSpec.DEFAULT);
            String[] words = {"sessionId", "SESSIONID", "ABC123", "決済", "ID",
                "PaymentException", "見つからない語"};
            for (String word : words) {
                QueryFilter literal = new QueryFilter();
                literal.grepRe = QueryFilter.compileRegex(word);
                literal.grepText = word;
                // 正規表現として扱わせる（メタ文字を足しても同じ範囲に一致する形にする）
                QueryFilter regex = new QueryFilter();
                regex.grepRe = QueryFilter.compileRegex("(?:" + java.util.regex.Pattern.quote(word) + ")");
                regex.grepText = null;

                LogQuery.Result byLiteral = LogQuery.queryLogs(conn, literal, 0, 10);
                LogQuery.Result byRegex = LogQuery.queryLogs(conn, regex, 0, 10);
                assertEquals(byRegex.total, byLiteral.total, word);
                assertEquals(byRegex.page.size(), byLiteral.page.size(), word);
                for (int i = 0; i < byRegex.page.size(); i++) {
                    assertEquals(byRegex.page.get(i).lineNo, byLiteral.page.get(i).lineNo, word);
                    assertEquals(byRegex.page.get(i).raw, byLiteral.page.get(i).raw, word);
                }
            }
        }
    }

    /**
     * メタ文字を含む指定は、リテラルのバイト照合ではなく正規表現として扱うこと。
     * {@code .} を 1 文字として解釈するかどうかで結果が変わる入力で確かめる。
     */
    @Test
    void grepWithMetaCharsUsesRegexPath(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.Foo] - code=ABC123\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - code=ABC.23\n");
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormatSpec.DEFAULT);
            // API と同じく、grepText には入力そのものが入る
            QueryFilter f = new QueryFilter();
            f.grepRe = QueryFilter.compileRegex("ABC.23");
            f.grepText = "ABC.23";
            // 正規表現なら . が任意の 1 文字なので 2 件、リテラル照合なら 1 件になる
            assertEquals(2, LogQuery.queryLogs(conn, f, 0, 10).total);

            QueryFilter literal = new QueryFilter();
            literal.grepRe = QueryFilter.compileRegex("ABC123");
            literal.grepText = "ABC123";
            assertEquals(1, LogQuery.queryLogs(conn, literal, 0, 10).total);
        }
    }
}
