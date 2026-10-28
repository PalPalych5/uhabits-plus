package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatisticsQueryTest {
    private fun context(
        date: LocalDate,
        zone: String,
        offsetHour: Int = 0,
        offsetMinute: Int = 0,
    ) = StatisticsQueryContext(
        date, date, zone, DayOfWeek.MONDAY, offsetHour, offsetMinute,
        "synthetic-statistics", 7, Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(),
    )

    @Test
    fun dstUsesCivilBoundariesAndHalfOpenIntervals() {
        val spring = context(LocalDate(2026, 3, 29), "Europe/Berlin")
        val autumn = context(LocalDate(2026, 10, 25), "Europe/Berlin")
        assertEquals(23 * 3_600_000L, spring.endMillis - spring.startMillis)
        assertEquals(25 * 3_600_000L, autumn.endMillis - autumn.startMillis)
        assertTrue(autumn.containsInstant(autumn.startMillis))
        assertTrue(autumn.containsInstant(autumn.endMillis - 1))
        assertFalse(autumn.containsInstant(autumn.endMillis))
        assertFalse(autumn.containsInstant(autumn.startMillis - 1))
    }

    @Test
    fun explicitTimezoneIgnoresGlobalDefaultAndDoesNotChangeIt() {
        val before = TimeZone.getDefault().id
        val west = context(LocalDate(2026, 10, 25), "America/Los_Angeles")
        val east = context(LocalDate(2026, 10, 25), "Asia/Tokyo")
        val instant = Instant.parse("2026-10-25T01:00:00Z").toEpochMilli()
        assertEquals(LocalDate(2026, 10, 24), west.civilDateAt(instant))
        assertEquals(LocalDate(2026, 10, 25), east.civilDateAt(instant))
        assertEquals(before, TimeZone.getDefault().id)
    }

    @Test
    fun repeatedHourPreservesCanonicalOffsetDateReversal() {
        val context = context(LocalDate(2026, 11, 1), "America/New_York", 1, 30)
        assertEquals(LocalDate(2026, 10, 31), context.habitDateAt(ms("2026-11-01T05:29:59Z")))
        assertEquals(LocalDate(2026, 11, 1), context.habitDateAt(ms("2026-11-01T05:30:00Z")))
        assertEquals(LocalDate(2026, 10, 31), context.habitDateAt(ms("2026-11-01T06:00:00Z")))
        assertEquals(LocalDate(2026, 11, 1), context.habitDateAt(ms("2026-11-01T06:30:00Z")))
        assertEquals(ms("2026-11-01T05:30:00Z"), context.habitDayStart(LocalDate(2026, 11, 1)))
        assertEquals(LocalDate(2026, 11, 1), context.civilDateAt(ms("2026-11-01T06:00:00Z")))
    }

    @Test
    fun missingOffsetBoundaryStartsAtTheJumpRatherThanShiftedWallTime() {
        val context = context(LocalDate(2026, 3, 29), "Europe/Berlin", 2, 30)
        assertEquals(ms("2026-03-29T01:00:00Z"), context.habitDayStart(LocalDate(2026, 3, 29)))
        assertEquals(LocalDate(2026, 3, 28), context.habitDateAt(ms("2026-03-29T00:59:59Z")))
        assertEquals(LocalDate(2026, 3, 29), context.habitDateAt(ms("2026-03-29T01:00:00Z")))
    }

    @Test
    fun queryRejectsInvalidZoneIdentityAndOffset() {
        assertFailsWith<java.time.DateTimeException> { context(LocalDate(2026, 1, 1), "not/a/zone") }
        assertFailsWith<IllegalArgumentException> { context(LocalDate(2026, 1, 1), "UTC", 24) }
        assertFailsWith<IllegalArgumentException> {
            context(LocalDate(2026, 1, 1), "UTC").copy(from = LocalDate(2026, 1, 2))
        }
        assertFailsWith<IllegalArgumentException> {
            context(LocalDate(2026, 1, 1), "UTC").copy(datasetId = "")
        }
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
}
