package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link TimeUtil} の単体試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>ログ行先頭 23 文字（{@code yyyy-MM-dd HH:mm:ss.SSS}）のバイト列解析</li>
 *   <li>UI から渡される日時文字列の複数形式（T 区切り、日付のみ、ミリ秒省略）の解析</li>
 *   <li>epoch millis ↔ ISO 表示文字列の往復変換</li>
 *   <li>不正入力の拒否（{@link Long#MIN_VALUE} または {@link IllegalArgumentException}）</li>
 *   <li>存在しない日時・余分な文字を含む入力の拒否（繰り上げて通さないこと）</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>ログパーサー・インデックス・UI フィルタが同一の millis 基準で時刻を比較できる</li>
 *   <li>フロント表示用 ISO 形式（{@code yyyy-MM-ddTHH:mm:ss.SSS}）が一貫して生成される</li>
 *   <li>うるう年等の civil calendar 変換が破綻しない</li>
 *   <li>期間フィルタの打ち間違いが、別の期間の検索結果として黙って返らない</li>
 * </ul>
 */
class TimeUtilTest {

    /** ログ行タイムスタンプの parse → format 往復が一致すること。 */
    @Test
    void parseAndFormatRoundTrip() {
        byte[] b = "2026-06-15 00:19:11.705".getBytes(StandardCharsets.US_ASCII);
        long millis = TimeUtil.parseLogTimestamp(b, 0);
        assertEquals("2026-06-15T00:19:11.705", TimeUtil.formatIso(millis));
    }

    /** 非数字を含むタイムスタンプは {@link Long#MIN_VALUE} で失敗を表すこと。 */
    @Test
    void parseLogTimestampRejectsInvalid() {
        byte[] bad = "2026-06-15 00:19:11.70X".getBytes(StandardCharsets.US_ASCII);
        assertEquals(Long.MIN_VALUE, TimeUtil.parseLogTimestamp(bad, 0));
    }

    /** スペース区切り・T 区切り・日付のみが同一 millis に正規化されること。 */
    @Test
    void parseUiDatetimeVariants() {
        long a = TimeUtil.parseUiDatetime("2026-06-15 00:00:01.000");
        long b = TimeUtil.parseUiDatetime("2026-06-15T00:00:01.000");
        assertEquals(a, b);
        long c = TimeUtil.parseUiDatetime("2026-06-15");
        assertEquals("2026-06-15T00:00:00.000", TimeUtil.formatIso(c));
    }

    /** ミリ秒省略形式は 000 ミリ秒として解釈されること。 */
    @Test
    void parseUiDatetimeWithoutMillis() {
        long millis = TimeUtil.parseUiDatetime("2026-06-15 12:30:45");
        assertEquals("2026-06-15T12:30:45.000", TimeUtil.formatIso(millis));
    }

    /** 前後空白は trim され、同一日時として解釈されること。 */
    @Test
    void parseUiDatetimeTrimsInput() {
        long a = TimeUtil.parseUiDatetime("  2026-06-15 00:00:01.000  ");
        long b = TimeUtil.parseUiDatetime("2026-06-15 00:00:01.000");
        assertEquals(a, b);
    }

    /** 解釈不能な文字列は {@link IllegalArgumentException} となること（API エラー応答の前提）。 */
    @Test
    void parseUiDatetimeRejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiDatetime("invalid"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiDatetime(""));
    }

    /** millis 値が単調増加し、時系列ソート・範囲比較に使えること。 */
    @Test
    void ordersChronologically() {
        long early = TimeUtil.parseUiDatetime("2026-05-27 00:00:03.965");
        long late = TimeUtil.parseUiDatetime("2026-06-15 00:19:11.705");
        assertTrue(early < late);
    }

    /** うるう日（2024-02-29）の toMillis → formatIso 往復が正しいこと。 */
    @Test
    void civilCalendarRoundTrip() {
        long millis = TimeUtil.toMillis(2024, 2, 29, 23, 59, 59, 999);
        assertEquals("2024-02-29T23:59:59.999", TimeUtil.formatIso(millis));
    }

    /**
     * 存在しない日時を繰り上げて受理しないこと。
     *
     * <p>toMillis は暦を検証せず 2025-13-45 を 2026-02-14 として扱うため、
     * 検証が無いと打ち間違いが別の期間の検索結果として黙って返ってしまう。
     */
    @Test
    void parseUiDatetimeRejectsNonExistentDates() {
        String[] invalid = {
            "2025-13-45",              // 月・日とも範囲外
            "2025-00-10",              // 月が 0
            "2025-02-30",              // 2 月 30 日
            "2025-02-29",              // 平年の 2 月 29 日
            "2025-04-31",              // 30 日までの月
            "2025-06-15 24:00",        // 時が範囲外
            "2025-06-15 12:60",        // 分が範囲外
            "2025-06-15 12:34:60",     // 秒が範囲外
        };
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> TimeUtil.parseUiDatetime(value), value);
        }
    }

    /** うるう年の 2 月 29 日は受理すること。 */
    @Test
    void parseUiDatetimeAcceptsLeapDay() {
        assertEquals("2024-02-29T00:00:00.000",
                TimeUtil.formatIso(TimeUtil.parseUiDatetime("2024-02-29")));
        assertEquals("2000-02-29T00:00:00.000",
                TimeUtil.formatIso(TimeUtil.parseUiDatetime("2000-02-29")));
        assertThrows(IllegalArgumentException.class,
                () -> TimeUtil.parseUiDatetime("1900-02-29"));
    }

    /** 末尾に余分な文字が付いた入力を、読み飛ばして受理しないこと。 */
    @Test
    void parseUiDatetimeRejectsTrailingGarbage() {
        assertThrows(IllegalArgumentException.class,
                () -> TimeUtil.parseUiDatetime("2025-06-15 00:00:00.000zzz"));
        assertThrows(IllegalArgumentException.class,
                () -> TimeUtil.parseUiDatetime("2025-06-15 00:00:00.000+09:00"));
        assertThrows(IllegalArgumentException.class,
                () -> TimeUtil.parseUiDatetime("2025-06-15 12:34:5"));
    }

    /** UI が送る全形式（日付のみ / 分まで / 秒まで / ミリ秒まで）を受理すること。 */
    @Test
    void parseUiDatetimeAcceptsAllUiFormats() {
        assertEquals("2026-06-15T00:00:00.000",
                TimeUtil.formatIso(TimeUtil.parseUiDatetime("2026-06-15")));
        assertEquals("2026-06-15T12:34:00.000",
                TimeUtil.formatIso(TimeUtil.parseUiDatetime("2026-06-15 12:34")));
        assertEquals("2026-06-15T12:34:56.000",
                TimeUtil.formatIso(TimeUtil.parseUiDatetime("2026-06-15 12:34:56")));
        assertEquals("2026-06-15T12:34:56.789",
                TimeUtil.formatIso(TimeUtil.parseUiDatetime("2026-06-15T12:34:56.789")));
    }

    /** 月末日の判定が月ごとに正しいこと。 */
    @Test
    void daysInMonthCoversEveryMonth() {
        int[] expected = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        for (int month = 1; month <= 12; month++) {
            assertEquals(expected[month - 1], TimeUtil.daysInMonth(2025, month),
                    "month=" + month);
        }
        assertEquals(29, TimeUtil.daysInMonth(2024, 2));
    }
}
