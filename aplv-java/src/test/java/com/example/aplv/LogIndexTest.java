package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>Web UI の検索・フィルタ・ページングがインデックス上で正しく動作する</li>
 *   <li>スタックトレース本文は DB に載せず byte 範囲からオンデマンド読み出しできる</li>
 *   <li>ログ追記後や FTS 設定変更時に stale インデックスを検知できる</li>
 *   <li>FTS 非対応環境でも grep が全件スキャンで同等の結果を返す</li>
 *   <li>正規表現の有無でクエリ経路が変わっても件数・並び・ページ境界が変わらない</li>
 * </ul>
 */
class LogIndexTest {

    private Path writeLog(Path dir, String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return PathUtil.resolve(path);
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
            LogIndex.BuildResult built = LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);
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
            LogIndex.buildIndex(conn, paths, null, false);
            assertFalse(LogIndex.needsRebuild(conn, paths, false));

            Files.write(log,
                    "2026-06-15 00:00:02.000[main][INFO][com.example.B] - two\n"
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            assertTrue(LogIndex.needsRebuild(conn, paths, false));
        }
    }

    /** FTS 有効/無効の切り替え時に needsRebuild が true になること。 */
    @Test
    void needsRebuildWhenFtsFlagChanges(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n");
        List<Path> paths = Collections.singletonList(log);

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, paths, null, false);
            assertFalse(LogIndex.needsRebuild(conn, paths, false));
            assertTrue(LogIndex.needsRebuild(conn, paths, true));
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
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);

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
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, true);
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
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);
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
            assertEquals(3, LogIndex.buildIndex(conn, Arrays.asList(a, b), null, false).entryCount);

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
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);

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
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);

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
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);

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
            LogIndex.BuildResult built = LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);
            assertEquals(2, built.entryCount);
            assertEquals(2, built.skippedLines);
            assertEquals(2, built.skippedSamples.size());
            assertEquals(1, built.skippedSamples.get(0).lineNo);
            assertEquals("# rotation marker", built.skippedSamples.get(0).preview);

            assertEquals(2, LogIndex.getSkippedLineCount(conn));
            assertFalse(LogIndex.getSkippedLineSamples(conn).isEmpty());
            assertFalse(LogIndex.needsRebuild(conn, Collections.singletonList(log), false));
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
     * 正規表現・grep が無いときの SQL 押し下げ経路（COUNT + LIMIT/OFFSET）が、
     * 全件走査経路と同じ件数・並び・ページ境界を返すこと。
     */
    @Test
    void pushdownMatchesScanPath(@TempDir Path tmp) throws Exception {
        Path log = writeFiveLines(tmp);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);

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
     * 一覧 API が同じ内容を読み直さずに済むこと（grep 無しでは設定しない）。
     */
    @Test
    void grepKeepsRawForPageRows(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - failed\n"
                        + "java.lang.RuntimeException: boom\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);

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
            assertNull(noGrep.page.get(0).raw, "grep 無しでは raw を読まないこと");
        }
    }
}
