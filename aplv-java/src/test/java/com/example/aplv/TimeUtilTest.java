package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimeUtilTest {

    @Test
    void parseAndFormatRoundTrip() {
        byte[] b = "2026-06-15 00:19:11.705".getBytes(StandardCharsets.US_ASCII);
        long millis = TimeUtil.parseLogTimestamp(b, 0);
        assertEquals("2026-06-15T00:19:11.705", TimeUtil.formatIso(millis));
    }

    @Test
    void parseUiDatetimeVariants() {
        long a = TimeUtil.parseUiDatetime("2026-06-15 00:00:01.000");
        long b = TimeUtil.parseUiDatetime("2026-06-15T00:00:01.000");
        assertEquals(a, b);
        long c = TimeUtil.parseUiDatetime("2026-06-15");
        assertEquals("2026-06-15T00:00:00.000", TimeUtil.formatIso(c));
    }

    @Test
    void parseUiDatetimeRejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiDatetime("invalid"));
    }

    @Test
    void ordersChronologically() {
        long early = TimeUtil.parseUiDatetime("2026-05-27 00:00:03.965");
        long late = TimeUtil.parseUiDatetime("2026-06-15 00:19:11.705");
        org.junit.jupiter.api.Assertions.assertTrue(early < late);
    }
}
