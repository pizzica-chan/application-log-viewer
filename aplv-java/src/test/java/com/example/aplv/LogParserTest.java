package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link LogParser} の単体試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>Tomcat 形式・旧形式（Logger/Thread 順序入れ替え）のログ行解析</li>
 *   <li>スレッド名に {@code []} がネストする行、日本語メッセージ、空メッセージ</li>
 *   <li>既知ログレベル（TRACE〜SEVERE）の受理と大文字正規化</li>
 *   <li>スレッド名ヒント（{@code pool-*}, {@code http-nio-*} 等）による logger/thread 判別</li>
 *   <li>スタックトレース行・未知レベル・区切り欠落など非ログ行の拒否</li>
 *   <li>UTF-8 デコード前の {@link LogParser#looksLikeHeader} 高速判定と {@link LogParser#parse(byte[], int)}</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>実運用で想定される 2 系統のログ形式から、日時・logger・レベル・スレッド・メッセージを正しく抽出できる</li>
 *   <li>継続行（スタックトレース等）をヘッダ行と誤認しない（{@code null} を返す）</li>
 *   <li>タイムスタンプ millis が {@link TimeUtil} と整合し、インデックスの時系列ソートの前提が満たされる</li>
 *   <li>大容量ログ向けのバイト列ベース判定が、デコード後の解析結果と矛盾しない</li>
 * </ul>
 */
class LogParserTest {

    /** Tomcat 形式 [Thread][LEVEL][Logger(FQCN)] のフィールド割り当て。 */
    @Test
    void tomcatFormat() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[ajp-nio-8009-exec-24][TRACE][org.hogehoge.jdbc.HogeUtil] - body");
        assertNotNull(p);
        assertEquals("ajp-nio-8009-exec-24", p.thread);
        assertEquals("TRACE", p.level);
        assertEquals("org.hogehoge.jdbc.HogeUtil", p.logger);
        assertEquals("body", p.message);
    }

    /** 旧形式 [Logger][LEVEL][Thread] のフィールド割り当て。 */
    @Test
    void legacyFormat() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-05-27 00:00:03.965[HogeController][ERROR][main] - msg");
        assertNotNull(p);
        assertEquals("HogeController", p.logger);
        assertEquals("ERROR", p.level);
        assertEquals("main", p.thread);
    }

    /** 旧形式で 1 番目フィールドが FQCN の場合に logger として認識されること。 */
    @Test
    void fqcnLoggerInLegacyPosition() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:00:00.000[com.example.Foo][WARN][main] - warn");
        assertNotNull(p);
        assertEquals("com.example.Foo", p.logger);
        assertEquals("main", p.thread);
    }

    /** 既知レベル以外はエントリとして扱わないこと。 */
    @Test
    void rejectsUnknownLevel() {
        assertNull(LogParser.parseLine("2026-06-15 00:00:00.000[a][UNKNOWN][b] - x"));
    }

    /** 例外行・空行・スタックトレース行をヘッダと誤認しないこと。 */
    @Test
    void rejectsNonLogLine() {
        assertNull(LogParser.parseLine("java.lang.NullPointerException"));
        assertNull(LogParser.parseLine(""));
        assertNull(LogParser.parseLine("\tat com.example.Foo.run(Foo.java:10)"));
    }

    /** 3 番目フィールド（Thread）内の {@code []} ネストを正しく切り出すこと。 */
    @Test
    void legacyFormatWithNestedBracketsInThread() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:10.100[HogeController][INFO][main:[12345] ch[00]] - メッセージ");
        assertNotNull(p);
        assertEquals("HogeController", p.logger);
        assertEquals("INFO", p.level);
        assertEquals("main:[12345] ch[00]", p.thread);
        assertEquals("メッセージ", p.message);
    }

    /** UTF-8 日本語メッセージが欠損なく保持されること。 */
    @Test
    void japaneseMessage() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:10.100[main][INFO][com.example.StartupRunner] - アプリケーションを起動しました");
        assertNotNull(p);
        assertEquals("アプリケーションを起動しました", p.message);
    }

    /** KNOWN_LEVELS に列挙された全レベルが受理されること。 */
    @Test
    void allKnownLevels() {
        for (String level : new String[] {"TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "SEVERE"}) {
            LogParser.ParsedLine p = LogParser.parseLine(
                    "2026-06-15 00:00:00.000[main][" + level + "][com.example.X] - msg");
            assertNotNull(p, level);
            assertEquals(level, p.level);
        }
    }

    /** レベル文字列が大文字に正規化され、フィルタ条件と一致可能であること。 */
    @Test
    void levelCaseNormalized() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:00:00.000[main][debug][com.example.X] - msg");
        assertNotNull(p);
        assertEquals("DEBUG", p.level);
    }

    /** スレッド名ヒントにより Tomcat 形式として logger/thread を判別できること。 */
    @Test
    void threadHintDetectsPoolAndHttpNio() {
        LogParser.ParsedLine pool = LogParser.parseLine(
                "2026-06-15 00:19:14.000[pool-1-thread-1][DEBUG][com.example.db.DatabasePool] - conn");
        assertNotNull(pool);
        assertEquals("pool-1-thread-1", pool.thread);
        assertEquals("com.example.db.DatabasePool", pool.logger);

        LogParser.ParsedLine http = LogParser.parseLine(
                "2026-06-15 00:19:11.200[http-nio-8080-exec-1][INFO][com.example.web.HogeController] - GET");
        assertNotNull(http);
        assertEquals("http-nio-8080-exec-1", http.thread);
    }

    /** 解析結果の tsMillis が UI 日時解析と一致すること（時系列フィルタの前提）。 */
    @Test
    void parsesTimestampMillis() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[main][INFO][com.example.X] - msg");
        assertNotNull(p);
        assertEquals(TimeUtil.parseUiDatetime("2026-06-15 00:19:11.705"), p.tsMillis);
    }

    /** {@code ] - } 直後が空でも有効なエントリとして解析されること。 */
    @Test
    void emptyMessage() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:00:00.000[main][INFO][com.example.X] - ");
        assertNotNull(p);
        assertEquals("", p.message);
    }

    /** 固定区切り {@code ] - } が無い行は拒否されること。 */
    @Test
    void rejectsMissingFieldSeparator() {
        assertNull(LogParser.parseLine("2026-06-15 00:00:00.000[main][INFO][com.example.X] msg"));
    }

    /** タイムスタンプ + {@code [} のバイト列プレフィックスをヘッダ候補と判定できること。 */
    @Test
    void looksLikeHeaderAcceptsValidPrefix() {
        byte[] b = "2026-06-15 00:19:11.705[main]".getBytes(StandardCharsets.US_ASCII);
        assertTrue(LogParser.looksLikeHeader(b, b.length));
    }

    /** 短すぎる行・{@code [} 欠落行をヘッダ候補から除外できること（デコード省略の前提）。 */
    @Test
    void looksLikeHeaderRejectsInvalid() {
        assertFalse(LogParser.looksLikeHeader(new byte[] {'j', 'a', 'v', 'a'}, 4));
        assertFalse(LogParser.looksLikeHeader("2026-06-15 00:19:11.705".getBytes(StandardCharsets.US_ASCII), 23));
        assertFalse(LogParser.looksLikeHeader("2026-06-15 00:19:11.705(".getBytes(StandardCharsets.US_ASCII), 24));
    }

    /** 行末 CR/LF がメッセージに混入しないこと。 */
    @Test
    void parseBytesWithCrLf() {
        byte[] b = "2026-06-15 00:00:00.000[main][INFO][com.example.X] - ok\r\n".getBytes(StandardCharsets.UTF_8);
        LogParser.ParsedLine p = LogParser.parse(b, b.length);
        assertNotNull(p);
        assertEquals("ok", p.message);
    }

    /** タイムスタンプ部が不完全な行は {@link LogParser#parse(byte[], int)} で拒否されること。 */
    @Test
    void parseBytesRejectsTooShortLine() {
        byte[] b = "2026-06-15 00:00:00".getBytes(StandardCharsets.UTF_8);
        assertNull(LogParser.parse(b, b.length));
    }

    /** FQCN/ヒントで判別不能な場合、Tomcat 形式（1=Thread, 3=Logger）を優先すること。 */
    @Test
    void ambiguousFieldsDefaultToTomcatLayout() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:00:00.000[WorkerA][INFO][MyLogger] - msg");
        assertNotNull(p);
        assertEquals("WorkerA", p.thread);
        assertEquals("MyLogger", p.logger);
    }

    /** IP アドレスを含む AJP スレッド名を logger と誤認しないこと。 */
    @Test
    void ajpThreadWithIpAddressIsNotLogger() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[ajp-nio-127.0.0.1-8009-exec-1][DEBUG][com.example.web.HogeController] - body");
        assertNotNull(p);
        assertEquals("ajp-nio-127.0.0.1-8009-exec-1", p.thread);
        assertEquals("com.example.web.HogeController", p.logger);
    }

    /** IP アドレス付きスレッド名が第3フィールドにある旧形式でも正しく判別すること。 */
    @Test
    void ajpThreadWithIpAddressInThirdField() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[com.example.web.HogeController][DEBUG][ajp-nio-127.0.0.1-8009-exec-1] - body");
        assertNotNull(p);
        assertEquals("com.example.web.HogeController", p.logger);
        assertEquals("ajp-nio-127.0.0.1-8009-exec-1", p.thread);
    }

    /** IP 付きスレッド + 短い logger 名でも Tomcat 形式として正しく判別すること。 */
    @Test
    void ajpThreadWithIpAddressAndShortLoggerName() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[ajp-nio-127.0.0.1-8009-exec-1][INFO][HogeController] - msg");
        assertNotNull(p);
        assertEquals("ajp-nio-127.0.0.1-8009-exec-1", p.thread);
        assertEquals("HogeController", p.logger);
    }
}
