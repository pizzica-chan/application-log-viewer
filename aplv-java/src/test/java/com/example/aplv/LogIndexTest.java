package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogIndexTest {

    private Path writeLog(Path dir, String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return PathUtil.resolve(path);
    }

    @Test
    void buildIndexWithStackTrace(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - failed\n"
                        + "java.lang.RuntimeException: boom\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Foo] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            long total = LogIndex.buildIndex(conn, Collections.singletonList(log), null, false);
            assertEquals(2, total);

            LogIndex.EntryRow err = LogIndex.findEntry(conn, PathUtil.normalizePath(log), 1);
            assertNotNull(err);
            String raw = LogIndex.readEntryRaw(log, err.byteOffset, err.endByteOffset);
            assertTrue(raw.contains("RuntimeException"));
            assertTrue(raw.contains("Foo.run"));
        }
    }

    @Test
    void needsRebuildAfterContentChange(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - one\n");
        List<Path> paths = Collections.singletonList(log);

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, paths, null, false);
            assertFalse(LogIndex.needsRebuild(conn, paths));

            Files.write(log,
                    "2026-06-15 00:00:02.000[main][INFO][com.example.B] - two\n"
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            assertTrue(LogIndex.needsRebuild(conn, paths));
        }
    }

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

            // スタックトレース本文を FTS 経由で検索。
            QueryFilter hit = new QueryFilter();
            hit.grepRe = QueryFilter.compileRegex("NullPointerException");
            hit.grepText = "NullPointerException";
            LogQuery.Result r = LogQuery.queryLogs(conn, hit, 0, 10);
            assertEquals(1, r.total);
            assertEquals("com.example.Foo", r.page.get(0).logger);

            // 存在しない語は 0 件（偽陽性なし）。
            QueryFilter miss = new QueryFilter();
            miss.grepRe = QueryFilter.compileRegex("zzzznotfound");
            miss.grepText = "zzzznotfound";
            LogQuery.Result r2 = LogQuery.queryLogs(conn, miss, 0, 10);
            assertEquals(0, r2.total);
        }
    }

    @Test
    void grepWorksWithoutFts(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                "2026-06-15 00:00:01.000[main][ERROR][com.example.Foo] - boom\n"
                        + "java.lang.NullPointerException: bad\n"
                        + "\tat com.example.Foo.run(Foo.java:10)\n"
                        + "2026-06-15 00:00:02.000[main][INFO][com.example.Bar] - ok\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            // FTS 無効でも全件スキャンで grep が機能する。
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

    @Test
    void parallelIndexAcrossMultipleFiles(@TempDir Path tmp) throws Exception {
        Path a = writeLog(tmp, "application.log",
                "2026-06-15 00:00:01.000[main][INFO][com.example.A] - a1\n"
                        + "2026-06-15 00:00:03.000[main][INFO][com.example.A] - a2\n");
        Path b = writeLog(tmp, "server.log",
                "2026-06-15 00:00:02.000[main][WARN][com.example.B] - b1\n");

        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            long total = LogIndex.buildIndex(conn, Arrays.asList(a, b), null, false);
            assertEquals(3, total);

            QueryFilter all = new QueryFilter();
            LogQuery.Result r = LogQuery.queryLogs(conn, all, 0, 10);
            // ts_millis 昇順で統合ソートされること
            assertEquals(3, r.page.size());
            assertEquals("a1", r.page.get(0).message);
            assertEquals("b1", r.page.get(1).message);
            assertEquals("a2", r.page.get(2).message);
        }
    }
}
