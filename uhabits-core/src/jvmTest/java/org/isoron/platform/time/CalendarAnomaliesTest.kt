/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.platform.time

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarAnomaliesTest {

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
    fun testLeapYearDateArithmeticAndBoundaries() {
        // 2024 is a leap year (divisible by 4, not 100)
        val feb28_2024 = LocalDate(2024, 2, 28)
        val feb29_2024 = feb28_2024.plus(1)
        val mar01_2024 = feb29_2024.plus(1)

        assertEquals(2024, feb29_2024.year)
        assertEquals(2, feb29_2024.month)
        assertEquals(29, feb29_2024.day)
        assertEquals(29, feb29_2024.monthLength)
        assertEquals(366, feb29_2024.yearLength)
        assertEquals(DayOfWeek.THURSDAY, feb29_2024.dayOfWeek)

        assertEquals(2024, mar01_2024.year)
        assertEquals(3, mar01_2024.month)
        assertEquals(1, mar01_2024.day)
        assertEquals(feb29_2024, mar01_2024.minus(1))

        // 2025 is a common (non-leap) year
        val feb28_2025 = LocalDate(2025, 2, 28)
        val mar01_2025 = feb28_2025.plus(1)
        assertEquals(28, feb28_2025.monthLength)
        assertEquals(365, feb28_2025.yearLength)
        assertEquals(3, mar01_2025.month)
        assertEquals(1, mar01_2025.day)
        assertEquals(feb28_2025, mar01_2025.minus(1))
    }

    @Test
    fun testCenturyLeapYearRules() {
        // 2000 was a leap year (divisible by 400)
        val feb28_2000 = LocalDate(2000, 2, 28)
        val feb29_2000 = feb28_2000.plus(1)
        assertEquals(29, feb29_2000.day)
        assertEquals(29, feb29_2000.monthLength)
        assertEquals(366, feb29_2000.yearLength)

        // 1900 and 2100 are NOT leap years (divisible by 100 but not 400)
        val feb28_1900 = LocalDate(1900, 2, 28)
        val mar01_1900 = feb28_1900.plus(1)
        assertEquals(28, feb28_1900.monthLength)
        assertEquals(3, mar01_1900.month)
        assertEquals(1, mar01_1900.day)

        val feb28_2100 = LocalDate(2100, 2, 28)
        val mar01_2100 = feb28_2100.plus(1)
        assertEquals(28, feb28_2100.monthLength)
        assertEquals(3, mar01_2100.month)
        assertEquals(1, mar01_2100.day)
    }

    @Test
    fun testMidnightTransitionsAndShiftedHabitBoundaries() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+00:00"))

        val date = LocalDate(2026, 10, 15)
        val midnight = startOfLocalDay(date) // 2026-10-15T00:00:00Z

        // 1 ms before midnight belongs to previous date
        assertEquals(date.minus(1), localDateAt(midnight - 1))
        // Exactly at midnight belongs to current date
        assertEquals(date, localDateAt(midnight))
        // 1 ms after midnight belongs to current date
        assertEquals(date, localDateAt(midnight + 1))

        // Shifted cutoff: e.g. 04:00 AM
        val shiftedBoundary = startOfLocalDay(date, 4, 0)
        assertEquals(midnight + 4 * 3600000L, shiftedBoundary)
        // 1 ms before 04:00 AM belongs to previous habit day
        assertEquals(date.minus(1), localDateAt(shiftedBoundary - 1, 4, 0))
        // Exactly at 04:00 AM belongs to current habit day
        assertEquals(date, localDateAt(shiftedBoundary, 4, 0))

        // Year boundary transition with 03:00 cutoff:
        // 2027-01-01 at 02:59:59 still belongs to 2026-12-31 habit day
        val newYear = LocalDate(2027, 1, 1)
        val newYearCutoff = startOfLocalDay(newYear, 3, 0)
        assertEquals(LocalDate(2026, 12, 31), localDateAt(newYearCutoff - 1, 3, 0))
        assertEquals(newYear, localDateAt(newYearCutoff, 3, 0))
    }

    @Test
    fun testDaylightSavingTransitionsAmericaNewYork() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        // Spring Forward: 2026-03-08 (Clock springs from 02:00 to 03:00, 23 hours in day)
        val springDay = LocalDate(2026, 3, 8)
        val startSpring = startOfLocalDay(springDay)
        val startNext = startOfLocalDay(springDay.plus(1))
        val elapsedSpringHours = (startNext - startSpring) / 3600000L
        assertEquals(23L, elapsedSpringHours, "Spring forward day must have exactly 23 elapsed hours")

        // A habit offset at 02:30 (during the skipped gap) resolves forward without crashing
        val skippedOffset = startOfLocalDay(springDay, 2, 30)
        assertEquals(springDay, localDateAt(skippedOffset, 2, 30))
        assertEquals(springDay.minus(1), localDateAt(skippedOffset - 1, 2, 30))

        // Fall Back: 2026-11-01 (Clock falls back from 02:00 to 01:00, 25 hours in day)
        val fallDay = LocalDate(2026, 11, 1)
        val startFall = startOfLocalDay(fallDay)
        val startFallNext = startOfLocalDay(fallDay.plus(1))
        val elapsedFallHours = (startFallNext - startFall) / 3600000L
        assertEquals(25L, elapsedFallHours, "Fall back day must have exactly 25 elapsed hours")

        // Bijective round-trip through 25-hour day
        assertEquals(fallDay, localDateAt(startFall))
        assertEquals(fallDay, localDateAt(startFallNext - 1))
        assertEquals(fallDay.plus(1), localDateAt(startFallNext))
    }

    @Test
    fun testWeekdayStartPreferencesAcrossYearAndMonthBoundaries() {
        // 2026-10-25 is a Sunday
        val sunday = LocalDate(2026, 10, 25)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)

        // With MONDAY first: week started on Monday 2026-10-19
        val monStart = sunday.startOfWeek(DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 10, 19), monStart)
        assertEquals(DayOfWeek.MONDAY, monStart.dayOfWeek)

        // With SUNDAY first: week started on Sunday 2026-10-25 (today)
        val sunStart = sunday.startOfWeek(DayOfWeek.SUNDAY)
        assertEquals(sunday, sunStart)
        assertEquals(DayOfWeek.SUNDAY, sunStart.dayOfWeek)

        // With SATURDAY first: week started on Saturday 2026-10-24
        val satStart = sunday.startOfWeek(DayOfWeek.SATURDAY)
        assertEquals(LocalDate(2026, 10, 24), satStart)
        assertEquals(DayOfWeek.SATURDAY, satStart.dayOfWeek)

        // Crossing year boundary: 2027-01-01 is a Friday
        val newYearFri = LocalDate(2027, 1, 1)
        assertEquals(DayOfWeek.FRIDAY, newYearFri.dayOfWeek)

        // Under MONDAY: week started in previous year on 2026-12-28
        val crossYearMon = newYearFri.startOfWeek(DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 12, 28), crossYearMon)
        assertEquals(DayOfWeek.MONDAY, crossYearMon.dayOfWeek)

        // Under SUNDAY: week started on 2026-12-27
        val crossYearSun = newYearFri.startOfWeek(DayOfWeek.SUNDAY)
        assertEquals(LocalDate(2026, 12, 27), crossYearSun)
        assertEquals(DayOfWeek.SUNDAY, crossYearSun.dayOfWeek)
    }
}
