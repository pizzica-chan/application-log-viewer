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
 * <p>対応形式（いずれも LEVEL は 2 番目の {@code []}）:
 * <ul>
 *   <li>Tomcat: {@code YYYY-MM-DD HH:MM:SS.mmm[Thread][LEVEL][Logger(FQCN)] - Message}</li>
 *   <li>その他: {@code YYYY-MM-DD HH:MM:SS.mmm[Logger][LEVEL][Thread] - Message}
 *       （Thread に {@code []} を含む場合も可。例: {@code main:[12345] ch[00]}）</li>
 * </ul>
 *
 * <p>大容量ログ向けに、まずバイト列だけでヘッダ行らしさを判定し（{@link #looksLikeHeader}）、
 * 一致した行だけを UTF-8 デコードして本解析する。スタックトレース等の継続行は
 * デコードを行わずスキップするため、I/O とアロケーションを大幅に削減する。
 */
public final class LogParser {

    private LogParser() {
    }

    /** ヘッダ行のタイムスタンプ部の固定長（{@code 2026-06-15 00:19:11.705} = 23 文字）。 */
    public static final int TS_LEN = 23;

    private static final Set<String> KNOWN_LEVELS = new HashSet<>(Arrays.asList(
            "TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "SEVERE"));

    /** 3 番目フィールド末尾とメッセージの区切り（{@code ] - message}）。 */
    private static final String FIELD3_END = "] - ";

    private static final Pattern THREAD_HINT = Pattern.compile(
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
     * タイムスタンプ部の固定書式と直後の {@code '['} を確認する。
     */
    public static boolean looksLikeHeader(byte[] b, int len) {
        if (len < TS_LEN + 1) {
            return false;
        }
        // 2026-06-15 00:19:11.705[
        return isDigit(b[0]) && isDigit(b[1]) && isDigit(b[2]) && isDigit(b[3])
                && b[4] == '-' && isDigit(b[5]) && isDigit(b[6])
                && b[7] == '-' && isDigit(b[8]) && isDigit(b[9])
                && b[10] == ' ' && isDigit(b[11]) && isDigit(b[12])
                && b[13] == ':' && isDigit(b[14]) && isDigit(b[15])
                && b[16] == ':' && isDigit(b[17]) && isDigit(b[18])
                && b[19] == '.' && isDigit(b[20]) && isDigit(b[21]) && isDigit(b[22])
                && b[23] == '[';
    }

    /**
     * バイト列を 1 ヘッダ行として解析する。ヘッダでなければ {@code null}。
     *
     * @param b   行バイト列（改行を含んでいてよい）
     * @param len 有効長
     */
    public static ParsedLine parse(byte[] b, int len) {
        if (!looksLikeHeader(b, len)) {
            return null;
        }
        long ts = TimeUtil.parseLogTimestamp(b, 0);
        if (ts == Long.MIN_VALUE) {
            return null;
        }
        // 末尾の CR/LF を除外
        int end = len;
        while (end > TS_LEN && (b[end - 1] == '\n' || b[end - 1] == '\r')) {
            end--;
        }
        // タイムスタンプ直後（'[' の位置）から本文をデコード
        String rest = new String(b, TS_LEN, end - TS_LEN, StandardCharsets.UTF_8);
        return parseRest(ts, rest);
    }

    private static ParsedLine parseRest(long ts, String rest) {
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
     * {@link #THREAD_HINT} に一致するか。正規表現を呼ぶ前に安い条件でふるい落とす。
     *
     * <p>{@code ^main} 以外の選択肢はすべて {@code '-'} を必ず含むため、{@code '-'} が無く
     * "main" でも始まらない文字列は一致しえない。ログ行の 1 つは FQCN の logger で
     * ほぼ必ずこれに当たるため、失敗すると分かっている走査を丸ごと省ける。
     * 判定結果は正規表現をそのまま呼んだ場合と同一。
     *
     * <p>実測（90 万行、ヘッダ解析のみ）: 1.72 秒 → 0.25 秒。
     * 正規表現を一切呼ばない下限が 0.15 秒なので、ほぼ限界まで削れている。
     */
    private static boolean looksLikeThread(String s) {
        if (s.indexOf('-') < 0 && !s.regionMatches(true, 0, "main", 0, 4)) {
            return false;
        }
        return THREAD_HINT.matcher(s).find();
    }

    /** テスト・利便用の文字列版。 */
    public static ParsedLine parseLine(String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return parse(b, b.length);
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }
}
