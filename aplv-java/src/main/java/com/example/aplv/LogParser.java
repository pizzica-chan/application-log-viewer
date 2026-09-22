package com.example.aplv;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Java アプリケーションログ行のパーサー。
 *
 * <p>対応する書式は {@link LogFormat} を参照。既定は
 * {@code YYYY-MM-DD HH:MM:SS.mmm[Thread][LEVEL][Logger(FQCN)] - Message} と、
 * Logger と Thread が入れ替わった旧形式（Thread に {@code []} を含む場合も可。
 * 例: {@code main:[12345] ch[00]}）。
 *
 * <p>大容量ログ向けに、まずバイト列だけでヘッダ行らしさを判定し（{@link #looksLikeHeader}）、
 * 一致した行だけを UTF-8 デコードして本解析する。スタックトレース等の継続行は
 * デコードを行わずスキップするため、I/O とアロケーションを大幅に削減する。
 *
 * <p>書式は取り込み開始時に 1 つへ確定させる前提のため、書式を増やしても
 * <strong>1 行あたりの判定は 1 書式分だけ</strong>で、継続行の扱いは変わらない。
 */
public final class LogParser {

    private LogParser() {
    }

    /** ヘッダ行のタイムスタンプ部の固定長（{@code 2026-06-15 00:19:11.705} = 23 文字）。 */
    public static final int TS_LEN = 23;

    /** JULI のタイムスタンプ部の固定長（{@code 15-Jun-2026 00:19:11.705} = 24 文字）。 */
    static final int JULI_TS_LEN = 24;

    /**
     * 受け付けるログレベル。SLF4J / log4j 系に加え、java.util.logging（JULI）の
     * {@code CONFIG} 〜 {@code FINEST} も含める。Tomcat の catalina.out で実際に出るため。
     */
    private static final Set<String> KNOWN_LEVELS = new HashSet<>(Arrays.asList(
            "TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "SEVERE",
            "CONFIG", "FINE", "FINER", "FINEST"));

    /** 3 番目フィールド末尾とメッセージの区切り（{@code ] - message}）。 */
    private static final String FIELD3_END = "] - ";

    /** logback / log4j / ISO8601 でロガーとメッセージを分ける区切り。 */
    private static final String LOGGER_END_DASH = " - ";

    /** Spring Boot でロガーとメッセージを分ける区切り。 */
    private static final String LOGGER_END_COLON = " : ";

    /** Spring Boot のプロセス ID とスレッドの間に入る目印。 */
    private static final String SPRING_MARKER = "---";

    /** スレッド名らしさの判定。事前ふるいとの等価性を試験するためパッケージ可視。 */
    static final Pattern THREAD_HINT = Pattern.compile(
            "(?:^main(?:$|:)|exec-\\d+|pool-\\d+-thread-\\d+|scheduler-\\d+"
                    + "|ajp-|http-nio-|https-nio-|catalina-|-exec-\\d+$)",
            Pattern.CASE_INSENSITIVE);

    /** 解析結果（エントリ先頭行）。 */
    public static final class ParsedLine {
        public final long tsMillis;
        public final String logger;
        public final String level;
        public final String thread;
        public final String message;

        ParsedLine(long tsMillis, String logger, String level, String thread, String message) {
            this.tsMillis = tsMillis;
            this.logger = logger;
            this.level = level;
            this.thread = thread;
            this.message = message;
        }
    }

    /**
     * バイト列だけでヘッダ行の可能性を高速判定する（UTF-8 デコード前）。
     *
     * <p>書式ごとに違うのは 3 バイトだけ（日付と時刻の区切り・ミリ秒の区切り・
     * タイムスタンプ直後）なので、そこを先に見てから数字 20 文字を走査する。
     * 継続行はたいてい先頭の数バイトで落ちるため、この順序のほうが速い。
     */
    public static boolean looksLikeHeader(LogFormat fmt, byte[] b, int len) {
        if (len < TS_LEN + 1) {
            return false;
        }
        switch (fmt) {
            case DEFAULT:
                // 2026-06-15 00:19:11.705[
                return b[10] == ' ' && b[19] == '.' && b[23] == '[' && looksLikeTimestamp(b);
            case SPRING_BOOT:
                // 2026-06-15 00:19:11.705  INFO … / Spring Boot 3.4 以降の既定は
                // 2026-06-15T00:19:11.705+09:00  INFO … と ISO 日時 + オフセットになる。
                return (b[10] == ' ' || b[10] == 'T') && b[19] == '.'
                        && isBodyStart(b[23]) && looksLikeTimestamp(b);
            case LOGBACK:
                // 2026-06-15 00:19:11,705 INFO … / ミリ秒の区切りは . でも , でも可
                return b[10] == ' ' && (b[19] == '.' || b[19] == ',') && b[23] == ' '
                        && looksLikeTimestamp(b);
            case TOMCAT_JULI:
                // 15-Jun-2026 00:19:11.705 INFO …
                return len >= JULI_TS_LEN + 1 && b[JULI_TS_LEN] == ' ' && looksLikeJuliTimestamp(b);
            case ISO8601:
                // 2026-06-15T00:19:11.705 INFO … / 2026-06-15T00:19:11,705 …（log4j2 の %d{ISO8601}）
                // タイムスタンプ直後にタイムゾーンオフセット（+09:00 / Z）が続く形もある。
                return b[10] == 'T' && (b[19] == '.' || b[19] == ',')
                        && isBodyStart(b[23]) && looksLikeTimestamp(b);
            default:
                return false;
        }
    }

    /**
     * タイムスタンプ直後に来てよい文字か。空白のほか、タイムゾーンオフセットの開始も許す。
     *
     * <p>{@code 2026-06-15T00:19:11.705+09:00} のようにオフセットが付く形（Spring Boot 3.4 以降の
     * 既定など）があるため。オフセットの値は使わない ―― このアプリはログに書かれた暦の値を
     * そのまま扱う方針で、タイムゾーン変換をしないため。
     */
    private static boolean isBodyStart(byte c) {
        return c == ' ' || c == '+' || c == '-' || c == 'Z' || c == 'z';
    }

    /** 既定書式での判定。 */
    public static boolean looksLikeHeader(byte[] b, int len) {
        return looksLikeHeader(LogFormat.DEFAULT, b, len);
    }

    /**
     * JULI のタイムスタンプらしさを判定する（{@code 15-Jun-2026 00:19:11.705}）。
     * 月名の中身までは見ない。値の妥当性は {@link TimeUtil#parseJuliTimestamp} が確かめる。
     */
    private static boolean looksLikeJuliTimestamp(byte[] b) {
        return isDigit(b[0]) && isDigit(b[1]) && b[2] == '-'
                && isLetter(b[3]) && isLetter(b[4]) && isLetter(b[5]) && b[6] == '-'
                && isDigit(b[7]) && isDigit(b[8]) && isDigit(b[9]) && isDigit(b[10])
                && b[11] == ' ' && isDigit(b[12]) && isDigit(b[13])
                && b[14] == ':' && isDigit(b[15]) && isDigit(b[16])
                && b[17] == ':' && isDigit(b[18]) && isDigit(b[19])
                && b[20] == '.' && isDigit(b[21]) && isDigit(b[22]) && isDigit(b[23]);
    }

    /**
     * タイムスタンプ部の数字と固定の区切りを確認する。
     * 位置 10（日付と時刻）と 19（ミリ秒）は書式ごとに違うため、ここでは見ない。
     */
    private static boolean looksLikeTimestamp(byte[] b) {
        return isDigit(b[0]) && isDigit(b[1]) && isDigit(b[2]) && isDigit(b[3])
                && b[4] == '-' && isDigit(b[5]) && isDigit(b[6])
                && b[7] == '-' && isDigit(b[8]) && isDigit(b[9])
                && isDigit(b[11]) && isDigit(b[12])
                && b[13] == ':' && isDigit(b[14]) && isDigit(b[15])
                && b[16] == ':' && isDigit(b[17]) && isDigit(b[18])
                && isDigit(b[20]) && isDigit(b[21]) && isDigit(b[22]);
    }

    /**
     * バイト列を 1 ヘッダ行として解析する。ヘッダでなければ {@code null}。
     *
     * @param fmt 適用する書式
     * @param b   行バイト列（改行を含んでいてよい）
     * @param len 有効長
     */
    public static ParsedLine parse(LogFormat fmt, byte[] b, int len) {
        if (!looksLikeHeader(fmt, b, len)) {
            return null;
        }
        boolean juli = fmt == LogFormat.TOMCAT_JULI;
        // JULI 以外の 4 書式は数字の位置が同じで、parseLogTimestamp は区切り文字を
        // 見ないため共通に使える。JULI だけ月名が入るので専用の解析を呼ぶ。
        long ts = juli ? TimeUtil.parseJuliTimestamp(b, 0) : TimeUtil.parseLogTimestamp(b, 0);
        if (ts == Long.MIN_VALUE) {
            return null;
        }
        int tsLen = juli ? JULI_TS_LEN : TS_LEN;
        // 末尾の CR/LF を除外
        int end = len;
        while (end > tsLen && (b[end - 1] == '\n' || b[end - 1] == '\r')) {
            end--;
        }
        // タイムスタンプ直後から本文をデコード（オフセットが続く場合は読み飛ばす）
        int bodyStart = juli ? tsLen : skipZoneOffset(b, TS_LEN, end);
        String rest = new String(b, bodyStart, end - bodyStart, StandardCharsets.UTF_8);
        switch (fmt) {
            case DEFAULT:
                return parseDefaultRest(ts, rest);
            case SPRING_BOOT:
                return parseSpringBootRest(ts, rest);
            case LOGBACK:
            case ISO8601:
                return parseBracketThreadRest(ts, rest);
            case TOMCAT_JULI:
                return parseJuliRest(ts, rest);
            default:
                return null;
        }
    }

    /** 既定書式での解析。 */
    public static ParsedLine parse(byte[] b, int len) {
        return parse(LogFormat.DEFAULT, b, len);
    }

    private static ParsedLine parseDefaultRest(long ts, String rest) {
        if (rest.isEmpty() || rest.charAt(0) != '[') {
            return null;
        }
        int e1 = rest.indexOf(']', 1);
        if (e1 < 0 || e1 + 1 >= rest.length() || rest.charAt(e1 + 1) != '[') {
            return null;
        }
        int s2 = e1 + 2;
        int e2 = rest.indexOf(']', s2);
        if (e2 < 0 || e2 + 1 >= rest.length() || rest.charAt(e2 + 1) != '[') {
            return null;
        }
        int s3 = e2 + 2;
        // 3 番目フィールド内に [] がネストする場合があるため、単純な ']' ではなく
        // 固定区切り "] - " で末尾を特定する（O(n) の indexOf 1 回、括弧走査より軽量）。
        int e3 = rest.indexOf(FIELD3_END, s3);
        if (e3 < 0) {
            return null;
        }
        String field1 = rest.substring(1, e1);
        String field2 = rest.substring(s2, e2);
        String field3 = rest.substring(s3, e3);
        String message = rest.substring(e3 + FIELD3_END.length());

        String level = field2.toUpperCase(Locale.ROOT);
        if (!KNOWN_LEVELS.contains(level)) {
            return null;
        }
        String logger;
        String thread;
        boolean field1LooksLikeThread = looksLikeThread(field1);
        boolean field3LooksLikeThread = looksLikeThread(field3);
        if (field1LooksLikeThread != field3LooksLikeThread) {
            if (field1LooksLikeThread) {
                thread = field1;
                logger = field3;
            } else {
                thread = field3;
                logger = field1;
            }
        } else if (field3.indexOf('.') >= 0 && field1.indexOf('.') < 0) {
            // Tomcat: [Thread][LEVEL][Logger(FQCN)]
            logger = field3;
            thread = field1;
        } else if (field1.indexOf('.') >= 0 && field3.indexOf('.') < 0) {
            // 旧形式: [Logger(FQCN)][LEVEL][Thread]
            logger = field1;
            thread = field3;
        } else {
            // 判別不能時は Tomcat 形式 [thread][LEVEL][logger] を優先
            logger = field3;
            thread = field1;
        }
        return new ParsedLine(ts, logger, level, thread, message);
    }

    /**
     * Spring Boot 既定レイアウトの本文を解析する。
     *
     * <pre>
     *   INFO 12345 --- [nio-8080-exec-1] c.e.Hoge                 : Message
     * </pre>
     *
     * <p>プロセス ID は出力しない設定もあるため任意とし、{@code ---} を必須の目印にする。
     * ロガーは {@code %-40.40logger} で右側を空白詰めされるので、区切り {@code " : "} までを
     * 取って前後の空白を落とす。
     */
    private static ParsedLine parseSpringBootRest(long ts, String rest) {
        int i = skipSpaces(rest, 0);
        int levelEnd = wordEnd(rest, i);
        if (levelEnd == i) {
            return null;
        }
        String level = rest.substring(i, levelEnd).toUpperCase(Locale.ROOT);
        if (!KNOWN_LEVELS.contains(level)) {
            return null;
        }
        i = skipSpaces(rest, levelEnd);
        // プロセス ID（任意）
        while (i < rest.length() && rest.charAt(i) >= '0' && rest.charAt(i) <= '9') {
            i++;
        }
        i = skipSpaces(rest, i);
        if (!rest.startsWith(SPRING_MARKER, i)) {
            return null;
        }
        i = skipSpaces(rest, i + SPRING_MARKER.length());
        if (i >= rest.length() || rest.charAt(i) != '[') {
            return null;
        }
        int threadEnd = rest.indexOf(']', i + 1);
        if (threadEnd < 0) {
            return null;
        }
        String thread = rest.substring(i + 1, threadEnd).trim();
        int loggerStart = skipSpaces(rest, threadEnd + 1);
        int sep = rest.indexOf(LOGGER_END_COLON, loggerStart);
        if (sep < 0) {
            return null;
        }
        String logger = rest.substring(loggerStart, sep).trim();
        String message = rest.substring(sep + LOGGER_END_COLON.length());
        return new ParsedLine(ts, logger, level, thread, message);
    }

    /**
     * logback / log4j / ISO8601 の本文を解析する。レベルとスレッドはどちらが先でもよい。
     *
     * <pre>
     *  INFO  [main] com.example.Hoge - Message   （log4j に多い並び）
     *  [main] INFO  com.example.Hoge - Message   （logback 既定の並び）
     * </pre>
     */
    private static ParsedLine parseBracketThreadRest(long ts, String rest) {
        int i = skipSpaces(rest, 0);
        if (i >= rest.length()) {
            return null;
        }
        String level;
        String thread;
        if (rest.charAt(i) == '[') {
            // [thread] LEVEL logger - message
            int threadEnd = rest.indexOf(']', i + 1);
            if (threadEnd < 0) {
                return null;
            }
            thread = rest.substring(i + 1, threadEnd).trim();
            i = skipSpaces(rest, threadEnd + 1);
            int levelEnd = wordEnd(rest, i);
            if (levelEnd == i) {
                return null;
            }
            level = rest.substring(i, levelEnd).toUpperCase(Locale.ROOT);
            i = levelEnd;
        } else {
            // LEVEL [thread] logger - message
            int levelEnd = wordEnd(rest, i);
            if (levelEnd == i) {
                return null;
            }
            level = rest.substring(i, levelEnd).toUpperCase(Locale.ROOT);
            i = skipSpaces(rest, levelEnd);
            if (i >= rest.length() || rest.charAt(i) != '[') {
                return null;
            }
            int threadEnd = rest.indexOf(']', i + 1);
            if (threadEnd < 0) {
                return null;
            }
            thread = rest.substring(i + 1, threadEnd).trim();
            i = threadEnd + 1;
        }
        if (!KNOWN_LEVELS.contains(level)) {
            return null;
        }
        int loggerStart = skipSpaces(rest, i);
        int sep = rest.indexOf(LOGGER_END_DASH, loggerStart);
        if (sep < 0) {
            return null;
        }
        String logger = rest.substring(loggerStart, sep).trim();
        String message = rest.substring(sep + LOGGER_END_DASH.length());
        return new ParsedLine(ts, logger, level, thread, message);
    }

    /**
     * タイムスタンプ直後のタイムゾーンオフセットを読み飛ばし、本文の開始位置を返す。
     *
     * <p>{@code +09:00} / {@code +0900} / {@code Z} に対応する。値は使わない
     * （{@link #isBodyStart} の説明のとおり、書かれた暦の値をそのまま扱うため）。
     * オフセットが無ければ {@code from} をそのまま返す。
     */
    private static int skipZoneOffset(byte[] b, int from, int end) {
        if (from >= end) {
            return from;
        }
        byte c = b[from];
        if (c == 'Z' || c == 'z') {
            return from + 1;
        }
        if (c != '+' && c != '-') {
            return from;
        }
        // +HH:MM もしくは +HHMM。数字と ':' だけを最大 6 文字読む。
        int i = from + 1;
        int digits = 0;
        while (i < end && digits < 4) {
            byte d = b[i];
            if (d >= '0' && d <= '9') {
                digits++;
                i++;
            } else if (d == ':' && digits == 2) {
                i++;
            } else {
                break;
            }
        }
        // 桁が揃っていなければオフセットではないので元の位置に戻す。
        return digits == 4 ? i : from;
    }

    /**
     * Tomcat の catalina.out（JULI OneLineFormatter）の本文を解析する。
     *
     * <pre>
     *  INFO [main] org.apache.catalina.startup.Catalina.start Server startup in [1234] milliseconds
     * </pre>
     *
     * <p>ロガーは {@code クラス名.メソッド名} で空白を含まないため、ロガーとメッセージは
     * 空白 1 つで分かれる。他の書式のような {@code " - "} の区切りは無い。
     */
    private static ParsedLine parseJuliRest(long ts, String rest) {
        int i = skipSpaces(rest, 0);
        int levelEnd = wordEnd(rest, i);
        if (levelEnd == i) {
            return null;
        }
        String level = rest.substring(i, levelEnd).toUpperCase(Locale.ROOT);
        if (!KNOWN_LEVELS.contains(level)) {
            return null;
        }
        i = skipSpaces(rest, levelEnd);
        if (i >= rest.length() || rest.charAt(i) != '[') {
            return null;
        }
        int threadEnd = rest.indexOf(']', i + 1);
        if (threadEnd < 0) {
            return null;
        }
        String thread = rest.substring(i + 1, threadEnd).trim();
        int loggerStart = skipSpaces(rest, threadEnd + 1);
        int loggerEnd = wordEnd(rest, loggerStart);
        if (loggerEnd == loggerStart) {
            return null;
        }
        String logger = rest.substring(loggerStart, loggerEnd);
        // メッセージは空のこともある（区切りの空白すら無い場合を含む）。
        String message = loggerEnd >= rest.length() ? "" : rest.substring(loggerEnd + 1);
        return new ParsedLine(ts, logger, level, thread, message);
    }

    private static int skipSpaces(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** {@code from} から空白または文字列末尾までの位置を返す。 */
    private static int wordEnd(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != ' ') {
            i++;
        }
        return i;
    }

    /**
     * {@link #THREAD_HINT} に一致するか。正規表現を呼ぶ前に安い条件でふるい落とす。
     *
     * <p>{@code ^main(?:$|:)} 以外の選択肢はすべて {@code '-'} を必ず含むため、{@code '-'} が
     * 無く "main" でも始まらない文字列はどの選択肢にも一致しえない。ログ行の 1 つは
     * FQCN の logger ではほぼ必ずこの形式に該当するため、失敗すると分かっている走査を丸ごと省ける。
     *
     * <p>ふるいは「一致しうるか」だけを見る必要条件で、通す側には緩い。
     * 例えば "mainThread" は {@code ^main(?:$|:)} に一致しないがふるいは通過し、
     * そのあと正規表現が正しく false を返す。したがって判定結果は
     * 正規表現をそのまま呼んだ場合と常に同一になる。
     *
     * <p>実測（90 万行、ヘッダ解析のみ）: 1.72 秒 → 0.25 秒。
     * 正規表現を一切呼ばない下限が 0.15 秒なので、ほぼ限界まで削れている。
     */
    static boolean looksLikeThread(String s) {
        if (s.indexOf('-') < 0 && !s.regionMatches(true, 0, "main", 0, 4)) {
            return false;
        }
        return THREAD_HINT.matcher(s).find();
    }

    /** テスト・利便用の文字列版（既定書式）。 */
    public static ParsedLine parseLine(String line) {
        return parseLine(LogFormat.DEFAULT, line);
    }

    /** テスト・利便用の文字列版。 */
    public static ParsedLine parseLine(LogFormat fmt, String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return parse(fmt, b, b.length);
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(byte c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
