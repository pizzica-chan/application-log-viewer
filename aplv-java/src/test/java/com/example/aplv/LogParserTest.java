package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LogParserTest {

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

    @Test
    void legacyFormat() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-05-27 00:00:03.965[HogeController][ERROR][main] - msg");
        assertNotNull(p);
        assertEquals("HogeController", p.logger);
        assertEquals("ERROR", p.level);
        assertEquals("main", p.thread);
    }

    @Test
    void fqcnLoggerInLegacyPosition() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:00:00.000[com.example.Foo][WARN][main] - warn");
        assertNotNull(p);
        assertEquals("com.example.Foo", p.logger);
        assertEquals("main", p.thread);
    }

    @Test
    void rejectsUnknownLevel() {
        assertNull(LogParser.parseLine("2026-06-15 00:00:00.000[a][UNKNOWN][b] - x"));
    }

    @Test
    void rejectsNonLogLine() {
        assertNull(LogParser.parseLine("java.lang.NullPointerException"));
        assertNull(LogParser.parseLine(""));
        assertNull(LogParser.parseLine("\tat com.example.Foo.run(Foo.java:10)"));
    }

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

    @Test
    void japaneseMessage() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:10.100[main][INFO][com.example.StartupRunner] - アプリケーションを起動しました");
        assertNotNull(p);
        assertEquals("アプリケーションを起動しました", p.message);
    }
}
