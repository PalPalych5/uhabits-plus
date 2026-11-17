/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.core.ui.screens.habits.show.views

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.ui.views.LightTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreakCartPresenterTest : BaseUnitTest() {
    @Test
    fun testBuildState_excludesLatestFromBestStreaks() {
        val habit = fixtures.createLongHabit()
        val state = StreakCartPresenter.buildState(habit, LightTheme())

        assertFalse(state.bestStreaks.contains(state.latestStreak))
        assertEquals(
            habit.streaks.getBest(11).filterNot { it == state.latestStreak }.take(10),
            state.bestStreaks
        )
    }

    @Test
    fun testBuildState_withSingleStreak() {
        val habit = fixtures.createLongHabit()
        val today = getToday()
        habit.originalEntries.clear()
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit.recompute()

        val state = StreakCartPresenter.buildState(habit, LightTheme())

        assertEquals(habit.streaks.getLatest(), state.latestStreak)
        assertTrue(state.bestStreaks.isEmpty())
    }

    @Test
    fun testBuildState_withoutStreaks() {
        val habit = fixtures.createLongHabit()
        habit.originalEntries.clear()
        habit.recompute()

        val state = StreakCartPresenter.buildState(habit, LightTheme())

        assertNull(state.latestStreak)
        assertTrue(state.bestStreaks.isEmpty())
    }

    @Test
    fun testForPeriod_respectsFirstWeekdayAndPreservesFullHistoricalStreaks() {
        // 2026-11-10 is Tuesday.
        // Mon-first week = 2026-11-09..2026-11-15
        // Sun-first week = 2026-11-08..2026-11-14
        val today = LocalDate(2026, 11, 10)
        val crossWeekOct = Streak(LocalDate(2026, 10, 28), LocalDate(2026, 11, 8)) // 12 days, ends Sunday Nov 8
        val midNov = Streak(LocalDate(2026, 11, 10), LocalDate(2026, 11, 10)) // 1 day, inside both weeks
        val earlyOct = Streak(LocalDate(2026, 10, 1), LocalDate(2026, 10, 15)) // 15 days, Oct only

        val streaks = listOf(earlyOct, crossWeekOct, midNov)
        val baseState = StreakCardState(
            color = PaletteColor(1),
            latestStreak = midNov,
            bestStreaks = listOf(earlyOct, crossWeekOct),
            theme = LightTheme(),
            allStreaks = streaks,
            firstWeekday = DayOfWeek.MONDAY,
            today = today
        )

        val monWeek = baseState.forPeriod(StreakPeriod.WEEK)
        assertEquals(midNov, monWeek.latestStreak)
        assertTrue(monWeek.bestStreaks.isEmpty())

        val sunWeek = baseState.copy(firstWeekday = DayOfWeek.SUNDAY).forPeriod(StreakPeriod.WEEK)
        assertEquals(midNov, sunWeek.latestStreak)
        assertEquals(listOf(crossWeekOct), sunWeek.bestStreaks)
        assertEquals(12, sunWeek.bestStreaks.single().length)
        assertEquals(LocalDate(2026, 10, 28), sunWeek.bestStreaks.single().start)
        assertEquals(LocalDate(2026, 11, 8), sunWeek.bestStreaks.single().end)

        val monthSlice = baseState.forPeriod(StreakPeriod.MONTH)
        assertEquals(midNov, monthSlice.latestStreak)
        assertEquals(listOf(crossWeekOct), monthSlice.bestStreaks)
        assertEquals(12, monthSlice.bestStreaks.single().length)
    }

    @Test
    fun testForPeriod_capsBestAtFiveAndExcludesLatestAndHandlesEmptyWindow() {
        val today = LocalDate(2026, 11, 10)
        val latestInNov = Streak(LocalDate(2026, 11, 10), LocalDate(2026, 11, 10))
        val historical = (1..7).map { idx ->
            val start = LocalDate(2026, 1 + idx, 1)
            Streak(start, start.plus(idx + 2))
        }
        val state = StreakCardState(
            color = PaletteColor(1),
            latestStreak = latestInNov,
            bestStreaks = historical,
            theme = LightTheme(),
            allStreaks = historical + latestInNov,
            firstWeekday = DayOfWeek.MONDAY,
            today = today
        )

        val allSlice = state.forPeriod(StreakPeriod.ALL)
        assertEquals(latestInNov, allSlice.latestStreak)
        assertEquals(5, allSlice.bestStreaks.size)
        assertFalse(allSlice.bestStreaks.contains(latestInNov))

        val emptyWeekState = state.copy(
            latestStreak = historical.last(),
            bestStreaks = historical.dropLast(1),
            allStreaks = historical
        )
        val weekSlice = emptyWeekState.forPeriod(StreakPeriod.WEEK)
        assertNull(weekSlice.latestStreak)
        assertTrue(weekSlice.bestStreaks.isEmpty())
    }
}
