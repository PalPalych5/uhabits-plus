/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.activities.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.BaseAndroidJVMTest
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsTierProgress
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.util.Locale

class StatisticsActivityTest : BaseAndroidJVMTest() {

    @Before
    override fun setUp() {
        super.setUp()
        setToday(LocalDate(2015, 1, 25)) // Sunday
    }

    @Test
    fun testIsLatestAllowedPeriod() {
        val today = LocalDate(2015, 1, 25)

        // Day view
        assertTrue(isLatestAllowedPeriod(today, StatisticsFragment.ReportTab.DAY, 1)) // 1 = Sunday
        assertTrue(isLatestAllowedPeriod(today.plus(1), StatisticsFragment.ReportTab.DAY, 1))
        assertFalse(isLatestAllowedPeriod(today.minus(1), StatisticsFragment.ReportTab.DAY, 1))

        // Week view (respecting first weekday)
        // If Sunday is first day (firstWeekdayNum = 1), today (2015-01-25) is the start of the week.
        assertTrue(isLatestAllowedPeriod(today, StatisticsFragment.ReportTab.WEEK, 1))
        assertTrue(isLatestAllowedPeriod(today.plus(6), StatisticsFragment.ReportTab.WEEK, 1))
        assertFalse(isLatestAllowedPeriod(today.minus(1), StatisticsFragment.ReportTab.WEEK, 1))

        // Month view
        assertTrue(isLatestAllowedPeriod(today, StatisticsFragment.ReportTab.MONTH, 1))
        assertFalse(isLatestAllowedPeriod(today.minus(30), StatisticsFragment.ReportTab.MONTH, 1))

        // Year view
        assertTrue(isLatestAllowedPeriod(today, StatisticsFragment.ReportTab.YEAR, 1))
        assertFalse(isLatestAllowedPeriod(today.minus(365), StatisticsFragment.ReportTab.YEAR, 1))
    }

    @Test
    fun testCurrentWeekRangeDoesNotIncludeFutureDates() {
        val today = LocalDate(2015, 1, 25)
        val range = statisticsActiveRange(today, StatisticsFragment.ReportTab.WEEK, 1)

        assertEquals(LocalDate(2015, 1, 25), range.first)
        assertEquals(today, range.second)
    }

    @Test
    fun testCurrentMonthRangeDoesNotIncludeFutureDates() {
        val today = LocalDate(2015, 1, 25)
        val range = statisticsActiveRange(today, StatisticsFragment.ReportTab.MONTH, 1)

        assertEquals(LocalDate(2015, 1, 1), range.first)
        assertEquals(today, range.second)
    }

    @Test
    fun testCurrentYearRangeDoesNotIncludeFutureDates() {
        val today = LocalDate(2015, 1, 25)
        val range = statisticsActiveRange(today, StatisticsFragment.ReportTab.YEAR, 1)

        assertEquals(LocalDate(2015, 1, 1), range.first)
        assertEquals(today, range.second)
    }

    @Test
    fun testStatisticsScoreChartDatesAreNewestFirst() {
        val today = LocalDate(2015, 1, 25)
        val dates = statisticsScoreChartDates(today, numChartPoints = 4, step = 7)

        assertEquals(
            listOf(
                LocalDate(2015, 1, 25),
                LocalDate(2015, 1, 18),
                LocalDate(2015, 1, 11),
                LocalDate(2015, 1, 4)
            ),
            dates
        )
    }

    @Test
    fun testWeekdayLabelsMatchActualDayOfWeek() {
        val formatter = JavaLocalDateFormatter(Locale.US)

        assertEquals("Monday", statisticsWeekdayLabel(formatter, DayOfWeek.MONDAY))
        assertEquals("Sunday", statisticsWeekdayLabel(formatter, DayOfWeek.SUNDAY))
    }

    @Test
    fun testRenderSignatureEquality() {
        val reportKey = StatisticsFragment.ReportKey(
            tab = StatisticsFragment.ReportTab.DAY,
            start = LocalDate(2015, 1, 25),
            end = LocalDate(2015, 1, 25),
            sphereId = null,
            statusFilter = "ALL",
            goalTypeFilter = "ALL",
            habitCount = 1
        )

        val stat1 = HabitCompletionStat(fixtures.createShortHabit(), 1, 1)

        val data1 = StatisticsData(
            start = LocalDate(2015, 1, 25),
            end = LocalDate(2015, 1, 25),
            totalDays = 1,
            completedDays = 1,
            totalFocusHours = 1.0,
            limitViolations = 0,
            missedGoals = 0,
            completionPercentage = 100,
            completedHabitsCount = 1,
            bestStreak = 1,
            bestSphereName = null,
            worstSphereName = null,
            scoreChartData = emptyList(),
            sphereFocusHours = emptyList(),
            habitStats = listOf(stat1),
            weekdayFrequency = emptyList(),
            heatmapRates = emptyMap(),
            tiers = emptyList(),
            skippedCount = 0,
            filteredHabits = emptyList()
        )

        val sig1 = StatisticsFragment.RenderSignature.from(reportKey, data1)

        // Same values, should be equal
        val sig2 = StatisticsFragment.RenderSignature.from(reportKey, data1)
        assertEquals(sig1, sig2)

        // Different habit stats (completed days changed) but same size list
        val stat2 = HabitCompletionStat(stat1.habit, 0, 1)
        val data2 = data1.copy(habitStats = listOf(stat2))
        val sig3 = StatisticsFragment.RenderSignature.from(reportKey, data2)
        assertNotEquals(sig1, sig3)
    }
}
