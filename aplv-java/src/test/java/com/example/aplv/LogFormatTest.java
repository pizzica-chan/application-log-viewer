package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LogFormat} と、書式を指定した {@link LogParser} の試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>4 書式それぞれの解析（日時・レベル・ロガー・スレッド・メッセージ）</li>
 *   <li>logback / log4j でレベルとスレッドの並びが逆でも解析できること</li>
 *   <li>書式どうしが取り違えられないこと（ある書式の行が他の書式では解析されない）</li>
 *   <li>先頭ファイルのサンプリングによる自動判定</li>
 *   <li>コメント行が先頭にあっても判定できること</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>書式を増やしても、各行は指定した 1 書式としてのみ解釈される（誤検出しない）</li>
 *   <li>自動判定が外れても既定書式に落ちるだけで、例外にはならない</li>
 * </ul>
 */
class LogFormatTest {

    private static final String DEFAULT_LINE =
            "2026-06-15 00:19:11.705[main][INFO][com.example.Hoge] - メッセージ";
    private static final String SPRING_LINE =
            "2026-06-15 00:19:11.705  INFO 12345 --- [nio-8080-exec-1] c.e.Hoge"
                    + "                 : メッセージ";
    private static final String LOGBACK_LINE =
            "2026-06-15 00:19:11,705 INFO  [main] com.example.Hoge - メッセージ";
    private static final String LOGBACK_THREAD_FIRST_LINE =
            "2026-06-15 00:19:11.705 [main] INFO  com.example.Hoge - メッセージ";
    private static final String ISO_LINE =
            "2026-06-15T00:19:11.705 INFO [main] com.example.Hoge - メッセージ";

    @Test
    void parsesDefaultFormat() {
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.DEFAULT, DEFAULT_LINE);
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("com.example.Hoge", p.logger);
        assertEquals("main", p.thread);
        assertEquals("メッセージ", p.message);
    }

    @Test
    void parsesSpringBootFormat() {
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.SPRING_BOOT, SPRING_LINE);
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("c.e.Hoge", p.logger, "空白詰めされたロガー名は前後を落とす");
        assertEquals("nio-8080-exec-1", p.thread);
        assertEquals("メッセージ", p.message);
    }

    @Test
    void parsesSpringBootWithoutPid() {
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.SPRING_BOOT,
                "2026-06-15 00:19:11.705  INFO --- [main] c.e.Hoge : メッセージ");
        assertNotNull(p, "プロセス ID を出力しない設定でも解析できること");
        assertEquals("main", p.thread);
    }

    @Test
    void parsesLogbackFormatWithCommaMillis() {
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.LOGBACK, LOGBACK_LINE);
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("com.example.Hoge", p.logger);
        assertEquals("main", p.thread);
        assertEquals("メッセージ", p.message);
        assertEquals(TimeUtil.parseUiDatetime("2026-06-15 00:19:11.705"), p.tsMillis,
                "ミリ秒の区切りが , でも時刻は同じに解釈される");
    }

    @Test
    void parsesLogbackFormatWithThreadBeforeLevel() {
        // logback の既定パターンはスレッドがレベルより前に来る。log4j は逆。両方受ける。
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.LOGBACK, LOGBACK_THREAD_FIRST_LINE);
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("main", p.thread);
        assertEquals("com.example.Hoge", p.logger);
    }

    @Test
    void parsesIso8601Format() {
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.ISO8601, ISO_LINE);
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("main", p.thread);
        assertEquals("com.example.Hoge", p.logger);
        assertEquals("メッセージ", p.message);
    }

    /**
     * log4j2 の {@code %d{ISO8601}} は {@code yyyy-MM-dd'T'HH:mm:ss,SSS} で、
     * T 区切りかつミリ秒がカンマになる。log4j2 では最も使われる日時指定。
     */
    @Test
    void parsesLog4j2Iso8601WithCommaMillis() {
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.ISO8601,
                "2026-06-15T00:19:11,705 INFO  [main] com.example.Hoge - メッセージ");
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("main", p.thread);
        assertEquals(TimeUtil.parseUiDatetime("2026-06-15 00:19:11.705"), p.tsMillis);
    }

    /**
     * タイムスタンプ直後にタイムゾーンオフセットが付く形。
     * オフセットの値は使わない（このアプリは書かれた暦の値をそのまま扱うため）。
     */
    @Test
    void parsesIso8601WithZoneOffset() {
        for (String ts : new String[] {
                "2026-06-15T00:19:11.705+09:00",
                "2026-06-15T00:19:11.705+0900",
                "2026-06-15T00:19:11.705-05:00",
                "2026-06-15T00:19:11.705Z"}) {
            LogParser.ParsedLine p = LogParser.parseLine(LogFormat.ISO8601,
                    ts + " INFO [main] com.example.Hoge - メッセージ");
            assertNotNull(p, ts + " を解析できること");
            assertEquals("com.example.Hoge", p.logger, ts + " のオフセットを読み飛ばすこと");
            assertEquals(TimeUtil.parseUiDatetime("2026-06-15 00:19:11.705"), p.tsMillis,
                    ts + " はタイムゾーン変換せず、書かれた値をそのまま使うこと");
        }
    }

    /** Spring Boot 3.4 以降の既定は ISO 日時 + オフセットになる。 */
    @Test
    void parsesSpringBootWithIsoTimestamp() {
        LogParser.ParsedLine p = LogParser.parseLine(LogFormat.SPRING_BOOT,
                "2026-06-15T00:19:11.705+09:00  INFO 12345 --- [           main] "
                        + "c.e.Hoge                                 : メッセージ");
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("main", p.thread);
        assertEquals("c.e.Hoge", p.logger);
        assertEquals("メッセージ", p.message);
    }

    /**
     * オフセットに見えるが桁が揃っていないものは読み飛ばさない。
     * 読み飛ばしてしまうと本文の先頭が欠けるため。
     */
    @Test
    void doesNotSkipMalformedZoneOffset() {
        assertNull(LogParser.parseLine(LogFormat.ISO8601,
                "2026-06-15T00:19:11.705+9 INFO [main] com.example.Hoge - メッセージ"),
                "桁が足りないオフセットは本文として扱われ、結果として解析できない");
    }

    /**
     * 自動判定が成立する前提。ある書式の行が、他の書式としては解析されないこと。
     * ここが崩れると判定が票割れし、誤った書式が選ばれうる。
     */
    @Test
    void formatsDoNotMatchEachOther() {
        String[][] cases = {
                {LogFormat.DEFAULT.id(), DEFAULT_LINE},
                {LogFormat.SPRING_BOOT.id(), SPRING_LINE},
                {LogFormat.LOGBACK.id(), LOGBACK_LINE},
                {LogFormat.LOGBACK.id(), LOGBACK_THREAD_FIRST_LINE},
                {LogFormat.ISO8601.id(), ISO_LINE},
        };
        for (String[] c : cases) {
            LogFormat owner = LogFormat.byId(c[0]);
            for (LogFormat other : LogFormat.values()) {
                if (other == owner) {
                    assertNotNull(LogParser.parseLine(other, c[1]),
                            owner.id() + " の行は " + other.id() + " として解析できるはず");
                } else {
                    assertNull(LogParser.parseLine(other, c[1]),
                            owner.id() + " の行が " + other.id() + " として解析されてはいけない: " + c[1]);
                }
            }
        }
    }

    @Test
    void detectsEachFormatFromFirstFile(@TempDir Path dir) throws IOException {
        assertEquals(LogFormat.DEFAULT, detectOf(dir, "a.log", DEFAULT_LINE));
        assertEquals(LogFormat.SPRING_BOOT, detectOf(dir, "b.log", SPRING_LINE));
        assertEquals(LogFormat.LOGBACK, detectOf(dir, "c.log", LOGBACK_LINE));
        assertEquals(LogFormat.ISO8601, detectOf(dir, "d.log", ISO_LINE));
    }

    @Test
    void detectionSkipsLeadingCommentsAndStackTraces(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("mixed.log");
        Files.write(log, Arrays.asList(
                "# rotated at 2026-06-15",
                "--- not a log line ---",
                LOGBACK_LINE,
                "java.lang.RuntimeException: boom",
                "\tat com.example.Hoge.run(Hoge.java:1)",
                LOGBACK_LINE), StandardCharsets.UTF_8);
        assertEquals(LogFormat.LOGBACK, LogFormat.detect(Collections.singletonList(log)),
                "先頭がコメントでも、サンプル内で最も多く解析できた書式を選ぶ");
    }

    @Test
    void detectionFallsBackToDefaultWhenNothingMatches(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("none.log");
        Files.write(log, Arrays.asList("hello", "world"), StandardCharsets.UTF_8);
        assertEquals(LogFormat.DEFAULT, LogFormat.detect(Collections.singletonList(log)),
                "どの書式でも解析できないときは既定へ落とす（例外にしない）");
    }

    @Test
    void detectionOnEmptyInputReturnsDefault() {
        assertEquals(LogFormat.DEFAULT, LogFormat.detect(Collections.<Path>emptyList()));
        assertEquals(LogFormat.DEFAULT, LogFormat.detect(null));
    }

    @Test
    void byIdRejectsUnknownValues() {
        assertNull(LogFormat.byId("nope"));
        assertNull(LogFormat.byId(null));
        for (LogFormat f : LogFormat.values()) {
            assertEquals(f, LogFormat.byId(f.id()));
        }
    }

    private static LogFormat detectOf(Path dir, String name, String line) throws IOException {
        Path log = dir.resolve(name);
        List<String> lines = Arrays.asList(line, line, line);
        Files.write(log, lines, StandardCharsets.UTF_8);
        return LogFormat.detect(Collections.singletonList(log));
    }
}
