package org.isoron.platform.time

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HabitDayBoundaryTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun saveTimeZone() {
        originalTimeZone = TimeZone.getDefault()
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun midnightInPositiveAndNegativeTimeZones() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+07:00"))
        assertBoundary(LocalDate(2026, 10, 1), "2026-09-30T17:00:00Z")
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"))
        assertBoundary(LocalDate(2026, 10, 1), "2026-10-01T05:00:00Z")
    }

    @Test
    fun shiftedBoundaryAndCalendarPeriods() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+07:00"))
        val boundary = Instant.parse("2026-10-01T20:30:00Z").toEpochMilli()
        val date = LocalDate(2026, 10, 2)
        assertEquals(date.minus(1), localDateAt(boundary - 1, 3, 30))
        assertEquals(date, localDateAt(boundary, 3, 30))
        assertEquals(boundary, startOfLocalDay(date, 3, 30))

        val monthBoundary = startOfLocalDay(LocalDate(2026, 10, 1), 3, 30)
        assertEquals(LocalDate(2026, 9, 1), localDateAt(monthBoundary - 1, 3, 30).startOfMonth())
        assertEquals(LocalDate(2026, 10, 1), localDateAt(monthBoundary, 3, 30).startOfMonth())
        val monday = LocalDate(2026, 10, 5)
        val weekBoundary = startOfLocalDay(monday, 3, 30)
        assertEquals(monday.minus(7), localDateAt(weekBoundary - 1, 3, 30).startOfWeek(DayOfWeek.MONDAY))
        assertEquals(monday, localDateAt(weekBoundary, 3, 30).startOfWeek(DayOfWeek.MONDAY))
    }

    @Test
    fun daylightSavingDaysHaveActualElapsedLengths() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertDayLength(LocalDate(2026, 3, 8), 23)
        assertDayLength(LocalDate(2026, 11, 1), 25)
        // A shifted boundary also observes the transition; it is not midnight plus elapsed hours.
        assertDayLength(LocalDate(2026, 3, 7), 23, 3, 30)
        assertDayLength(LocalDate(2026, 10, 31), 25, 3, 30)
    }

    @Test
    fun nonexistentBoundaryResolvesForward() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val date = LocalDate(2026, 3, 8)
        assertEquals(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), startOfLocalDay(date, 2, 30))
        assertEquals(date.minus(1), localDateAt(startOfLocalDay(date, 2, 30) - 1, 2, 30))
        assertEquals(date, localDateAt(startOfLocalDay(date, 2, 30), 2, 30))
    }

    @Test
    fun rejectsInvalidOffsets() {
        for ((hour, minute) in listOf(-1 to 0, 24 to 0, 0 to -1, 0 to 60)) {
            assertFailsWith<IllegalArgumentException> { localDateAt(0, hour, minute) }
            assertFailsWith<IllegalArgumentException> { startOfLocalDay(LocalDate(2026, 10, 1), hour, minute) }
            assertFailsWith<IllegalArgumentException> { computeToday(hour, minute) }
        }
    }

    private fun assertBoundary(date: LocalDate, instant: String) {
        val boundary = Instant.parse(instant).toEpochMilli()
        assertEquals(boundary, startOfLocalDay(date))
        assertEquals(date.minus(1), localDateAt(boundary - 1))
        assertEquals(date, localDateAt(boundary))
    }

    private fun assertDayLength(date: LocalDate, hours: Int, hourOffset: Int = 0, minuteOffset: Int = 0) {
        val start = startOfLocalDay(date, hourOffset, minuteOffset)
        val end = startOfLocalDay(date.plus(1), hourOffset, minuteOffset)
        assertEquals(hours * 3600000L, end - start)
        assertEquals(date, localDateAt(start, hourOffset, minuteOffset))
        assertEquals(date, localDateAt(end - 1, hourOffset, minuteOffset))
        assertEquals(date.plus(1), localDateAt(end, hourOffset, minuteOffset))
    }
}
