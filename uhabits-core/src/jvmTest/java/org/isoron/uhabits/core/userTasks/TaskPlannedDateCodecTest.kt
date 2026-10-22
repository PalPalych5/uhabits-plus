/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks

import org.isoron.platform.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class TaskPlannedDateCodecTest {
    @Test fun legacyUtcEpochDaysMatchIndependentCalendarAcrossTimezones() {
        val oldZone = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Pacific/Pago_Pago", "Pacific/Kiritimati", "America/New_York", "Asia/Novosibirsk")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (iso in listOf("1600-01-01", "1969-12-31", "1970-01-01", "2000-01-01", "2024-02-29", "2026-03-08", "2026-11-01", "9999-12-31")) {
                    val oracle = java.time.LocalDate.parse(iso)
                    val millis = oracle.toEpochDay() * 86400000L
                    val date = parseTaskPlannedDate(millis.toString())!!
                    assertEquals(iso, date.toIsoDateString())
                    assertEquals(millis, date.unixTime)
                }
            }
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun isoAndUnsetSemanticsRemainUnchanged() {
        assertNull(parseTaskPlannedDate(null))
        assertNull(parseTaskPlannedDate(""))
        assertEquals(LocalDate(1970, 1, 1), parseTaskPlannedDate("0"))
        assertEquals(LocalDate(1969, 12, 31), parseTaskPlannedDate("-86400000"))
        for (iso in listOf("2026-10-19", "2024-02-29", "2026-02-30", "+2026-1-1")) {
            assertEquals(parseIsoDateString(iso), parseTaskPlannedDate(iso))
            assertEquals("Do not silently rewrite legacy ISO raw", iso, normalizeTaskPlannedDateForSync(iso))
        }
        assertEquals("2026-10-19", normalizeTaskPlannedDateForSync("1792368000000"))
    }

    @Test fun unsupportedNumericValuesAreRejectedRatherThanRoundedOrNarrowed() {
        for (raw in listOf("1792393200000", "1792368000", "0.0", "1e12", "not-a-date",
            Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(),
            (Long.MAX_VALUE / 86400000L * 86400000L).toString(),
            (Long.MIN_VALUE / 86400000L * 86400000L).toString(),
            (java.time.LocalDate.of(1599, 12, 31).toEpochDay() * 86400000L).toString(),
            (java.time.LocalDate.of(10000, 1, 1).toEpochDay() * 86400000L).toString())) {
            org.junit.Assert.assertThrows(raw, IllegalArgumentException::class.java) { parseTaskPlannedDate(raw) }
        }
    }
}
