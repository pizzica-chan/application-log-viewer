package com.example.aplv;

/**
 * タイムスタンプの解析・整形ユーティリティ。
 *
 * <p>大容量ログでは 1 行ごとに呼ばれるため、{@code LocalDateTime} 等は使わず
 * civil calendar アルゴリズムで epoch millis（UTC 基準の単調な比較値）へ直接変換する。
 * 表示・範囲フィルタともこの millis を用いるため内部で一貫する。
 */
public final class TimeUtil {

    private TimeUtil() {
    }

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * {@code yyyy-MM-dd HH:mm:ss.SSS} を epoch millis へ解析する。
     *
     * @return 解析できない場合は {@link Long#MIN_VALUE}
     */
    public static long parseLogTimestamp(byte[] buf, int off) {
        // 位置は looksLikeHeader で検証済みの前提だが、安全のため数値変換のみ行う。
        int year = digit4(buf, off);
        int month = digit2(buf, off + 5);
        int day = digit2(buf, off + 8);
        int hour = digit2(buf, off + 11);
        int min = digit2(buf, off + 14);
        int sec = digit2(buf, off + 17);
        int milli = digit3(buf, off + 20);
        if (year < 0 || month < 0 || day < 0 || hour < 0 || min < 0 || sec < 0 || milli < 0) {
            return Long.MIN_VALUE;
        }
        return toMillis(year, month, day, hour, min, sec, milli);
    }

    /**
     * UI から渡される日時文字列を epoch millis へ解析する。
     *
     * <p>対応形式: {@code yyyy-MM-ddTHH:mm:ss.SSS} / {@code yyyy-MM-dd HH:mm:ss.SSS} /
     * {@code ...HH:mm:ss} / {@code ...HH:mm} / {@code yyyy-MM-dd}
     *
     * <p>存在しない日時（{@code 2025-13-45} や {@code 2025-02-30} 等）は拒否する。
     * {@link #toMillis} は暦を検証せず翌月・翌日へ繰り上げるため、検証しないと
     * 打ち間違いが「別の期間の検索結果」として黙って返ってしまう。
     * 末尾に余分な文字が付いた入力も、読み飛ばして通さないよう長さで形式を限定する。
     *
     * @throws IllegalArgumentException 解釈できない場合
     */
    public static long parseUiDatetime(String value) {
        String v = value.trim().replace('T', ' ');
        if (v.length() != 10 && v.length() != 16 && v.length() != 19 && v.length() != 23) {
            throw invalidDatetime(value);
        }
        int year;
        int month;
        int day;
        int hour = 0;
        int min = 0;
        int sec = 0;
        int milli = 0;
        // 桁位置の切り出しと数値化のみを try で囲む。妥当性判定を中に入れると
        // NumberFormatException（IllegalArgumentException のサブクラス）と区別できなくなる。
        try {
            year = Integer.parseInt(v.substring(0, 4));
            month = Integer.parseInt(v.substring(5, 7));
            day = Integer.parseInt(v.substring(8, 10));
            if (v.length() >= 16) {
                hour = Integer.parseInt(v.substring(11, 13));
                min = Integer.parseInt(v.substring(14, 16));
            }
            if (v.length() >= 19) {
                sec = Integer.parseInt(v.substring(17, 19));
            }
            if (v.length() == 23) {
                milli = Integer.parseInt(v.substring(20, 23));
            }
        } catch (RuntimeException e) {
            throw invalidDatetime(value);
        }
        if (!isValidDateTime(year, month, day, hour, min, sec, milli)) {
            throw invalidDatetime(value);
        }
        return toMillis(year, month, day, hour, min, sec, milli);
    }

    private static IllegalArgumentException invalidDatetime(String value) {
        return new IllegalArgumentException(
                "日時形式を解釈できません（yyyy-MM-dd[ HH:mm[:ss[.SSS]]]）: " + value);
    }

    /**
     * 年月日・時分秒が実在する値かどうか（うるう年を考慮した月末日まで判定）。
     *
     * <p>秒に 60（うるう秒）は許容しない。この検証は UI 入力に対するもので、
     * 一覧に表示される時刻は {@link #formatIso} が生成する 0〜59 秒に限られるため、
     * 60 を通しても翌分へ繰り上がるだけで利用者の意図とずれる。
     */
    static boolean isValidDateTime(int year, int month, int day, int hour, int min, int sec,
            int milli) {
        if (month < 1 || month > 12) {
            return false;
        }
        if (day < 1 || day > daysInMonth(year, month)) {
            return false;
        }
        return hour >= 0 && hour <= 23
                && min >= 0 && min <= 59
                && sec >= 0 && sec <= 59
                && milli >= 0 && milli <= 999;
    }

    /** 指定年月の日数。 */
    static int daysInMonth(int year, int month) {
        switch (month) {
            case 2:
                return isLeapYear(year) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    private static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    /** epoch millis を {@code yyyy-MM-ddTHH:mm:ss.SSS} へ整形する（フロント表示用）。 */
    public static String formatIso(long millis) {
        long days = Math.floorDiv(millis, MILLIS_PER_DAY);
        int msOfDay = (int) Math.floorMod(millis, MILLIS_PER_DAY);
        int[] ymd = civilFromDays(days);
        int hour = msOfDay / 3_600_000;
        int rem = msOfDay % 3_600_000;
        int min = rem / 60_000;
        rem %= 60_000;
        int sec = rem / 1000;
        int milli = rem % 1000;
        StringBuilder sb = new StringBuilder(23);
        pad(sb, ymd[0], 4);
        sb.append('-');
        pad(sb, ymd[1], 2);
        sb.append('-');
        pad(sb, ymd[2], 2);
        sb.append('T');
        pad(sb, hour, 2);
        sb.append(':');
        pad(sb, min, 2);
        sb.append(':');
        pad(sb, sec, 2);
        sb.append('.');
        pad(sb, milli, 3);
        return sb.toString();
    }

    static long toMillis(int year, int month, int day, int hour, int min, int sec, int milli) {
        long days = daysFromCivil(year, month, day);
        return days * MILLIS_PER_DAY
                + (hour * 3600L + min * 60L + sec) * 1000L
                + milli;
    }

    /**
     * Howard Hinnant の civil ⇄ days アルゴリズム。
     * 1970-01-01 を 0 とする経過日数を返す。
     */
    static long daysFromCivil(int y, int m, int d) {
        int yy = m <= 2 ? y - 1 : y;
        int era = (yy >= 0 ? yy : yy - 399) / 400;
        int yoe = yy - era * 400;
        int doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146_097 + doe - 719_468;
    }

    static int[] civilFromDays(long z) {
        z += 719_468;
        long era = (z >= 0 ? z : z - 146_096) / 146_097;
        long doe = z - era * 146_097;
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        return new int[] {(int) (m <= 2 ? y + 1 : y), (int) m, (int) d};
    }

    private static void pad(StringBuilder sb, int value, int width) {
        String s = Integer.toString(value);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
    }

    private static int digit(byte[] b, int i) {
        int c = b[i] & 0xFF;
        return (c >= '0' && c <= '9') ? c - '0' : -1;
    }

    private static int digit2(byte[] b, int i) {
        int a = digit(b, i);
        int c = digit(b, i + 1);
        return (a < 0 || c < 0) ? -1 : a * 10 + c;
    }

    private static int digit3(byte[] b, int i) {
        int a = digit(b, i);
        int c = digit(b, i + 1);
        int d = digit(b, i + 2);
        return (a < 0 || c < 0 || d < 0) ? -1 : a * 100 + c * 10 + d;
    }

    private static int digit4(byte[] b, int i) {
        int a = digit(b, i);
        int c = digit(b, i + 1);
        int d = digit(b, i + 2);
        int e = digit(b, i + 3);
        return (a < 0 || c < 0 || d < 0 || e < 0) ? -1 : a * 1000 + c * 100 + d * 10 + e;
    }
}
