package org.enthusia.rep.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class RepDateFormatsTest {

    @Test
    void formatterUsesSystemZoneByDefaultAndMinutePrecision() {
        DateTimeFormatter formatter = RepDateFormats.dateTimeMinute();

        assertEquals(ZoneId.systemDefault(), formatter.getZone());
        assertEquals(
                "2026-09-23 15:04",
                formatter.withZone(ZoneOffset.UTC).format(Instant.parse("2026-09-23T15:04:59Z"))
        );
    }
}
