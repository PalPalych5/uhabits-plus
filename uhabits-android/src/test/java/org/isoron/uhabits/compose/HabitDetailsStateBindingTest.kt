/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.compose

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.activities.habits.show.FocusProgressPresentation
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.LightTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HabitDetailsStateBindingTest {

    private val factory = MemoryModelFactory()
    private val preferences = Preferences(MemoryStorage())
    private val theme = LightTheme()

    @Before
    fun setUp() {
        setToday(LocalDate(2026, 11, 7))
    }

    @Test
    fun testBinaryHabitUsesOverviewCardMetricsAndExcludesTargetCard() {
        val habit = factory.buildHabit().apply {
            name = "Morning Meditation"
            type = HabitType.YES_NO
            frequency = Frequency.DAILY
            question = "Did you meditate?"
            repeat(7) { day ->
                val date = LocalDate(2026, 11, day + 1)
                originalEntries.add(Entry(date, Entry.YES_MANUAL))
                computedEntries.add(Entry(date, Entry.YES_MANUAL))
            }
        }

        val state = ShowHabitPresenter.buildState(habit, preferences, theme)
        assertFalse(state.subtitle.isNumerical)
        assertThat(state.overview.totalCount, equalTo(7L))
    }

    @Test
    fun testNumericalHabitUsesTargetCardAndExcludesBinaryTotalCount() {
        val habit = factory.buildHabit().apply {
            name = "Read Book"
            type = HabitType.NUMERICAL
            unit = "pages"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 30.0
            frequency = Frequency.DAILY
            question = "How many pages did you read?"
            // 25 pages today (milli-units = 25000)
            originalEntries.add(Entry(LocalDate(2026, 11, 7), 25000))
            computedEntries.add(Entry(LocalDate(2026, 11, 7), 25000))
        }

        val state = ShowHabitPresenter.buildState(habit, preferences, theme)
        assertTrue(state.subtitle.isNumerical)
        assertThat(state.target.values.firstOrNull(), equalTo(25.0))
        assertThat(state.target.targets.firstOrNull(), equalTo(30.0))
        assertThat(state.subtitle.unit, equalTo("pages"))

        // Goal not reached (25.0 < 30.0)
        val isGoalReached = state.target.values[0] >= state.target.targets[0]
        assertFalse("25 pages is less than 30 pages target", isGoalReached)

        // Verify OverviewCard totalCount is NOT reused (for numerical it is 0 because no Entry.YES_MANUAL)
        assertThat(state.overview.totalCount, equalTo(0L))
    }

    @Test
    fun testNumericalHabitExplicitZeroNotTreatedAsAbsentData() {
        val habit = factory.buildHabit().apply {
            name = "Limit Coffee"
            type = HabitType.NUMERICAL
            unit = "cups"
            targetType = NumericalHabitType.AT_MOST
            targetValue = 2.0
            frequency = Frequency.DAILY
            question = "How many cups of coffee?"
            // Explicit 0 cups entered today (0 milli-units)
            originalEntries.add(Entry(LocalDate(2026, 11, 7), 0))
            computedEntries.add(Entry(LocalDate(2026, 11, 7), 0))
        }

        val state = ShowHabitPresenter.buildState(habit, preferences, theme)
        val achievedVal = state.target.values.firstOrNull() ?: 0.0
        val targetVal = state.target.targets.firstOrNull() ?: state.subtitle.targetValue

        assertThat(achievedVal, equalTo(0.0))
        assertThat(targetVal, equalTo(2.0))

        // History series for today is not OFF (it is ON because 0 <= 2 for AT_MOST)
        val todaySquare = state.history.series.firstOrNull()
        assertThat(todaySquare, equalTo(HistoryChart.Square.ON))

        // Explicit 0 <= 2 cups is a satisfied goal
        val isGoalReached = achievedVal <= targetVal
        assertTrue("0 cups satisfies at-most 2 cups target", isGoalReached)
    }

    @Test
    fun testNumericalHabitWithFocusProgressPresentationSeparatesManualAndFocus() {
        val presentation = FocusProgressPresentation(
            total = 54_000L,
            lowerBound = false,
            manual = 12_000L,
            focus = 42_000L,
            excludedFocus = 0L,
            status = HabitGoalStatus.SATISFIED,
            unit = "min",
            target = 30.0,
            atMost = false
        )

        assertThat(presentation.total, equalTo(54_000L))
        assertThat(presentation.manual, equalTo(12_000L))
        assertThat(presentation.focus, equalTo(42_000L))
        assertThat(presentation.status, equalTo(HabitGoalStatus.SATISFIED))
        assertThat(presentation.unit, equalTo("min"))
        assertThat(presentation.target, equalTo(30.0))
        assertFalse(presentation.lowerBound)
        assertFalse(presentation.atMost)
    }

    @Test
    fun testNumericalHabitWithLowerBoundRetainsCoverageMarker() {
        val presentation = FocusProgressPresentation(
            total = 20_000L,
            lowerBound = true,
            manual = 0L,
            focus = 20_000L,
            excludedFocus = 0L,
            status = HabitGoalStatus.NOT_SATISFIED,
            unit = "min",
            target = 30.0,
            atMost = false
        )

        assertTrue(presentation.lowerBound)
        assertThat(presentation.status, equalTo(HabitGoalStatus.NOT_SATISFIED))
        assertThat(presentation.manual, equalTo(0L))
        assertThat(presentation.focus, equalTo(20_000L))
    }

    @Test
    fun testScoreCardStateDensityResponsiveSizingLogic() {
        val habitWithoutRecords = factory.buildHabit().apply {
            name = "Fresh Habit"
            type = HabitType.YES_NO
            frequency = Frequency.DAILY
        }
        val emptyState = ShowHabitPresenter.buildState(habitWithoutRecords, preferences, theme)
        // A habit with no records has exactly 1 score point (today at 0%)
        assertThat(emptyState.scores.scores.size, equalTo(1))

        val denseHabit = factory.buildHabit().apply {
            name = "Long-standing Habit"
            type = HabitType.YES_NO
            frequency = Frequency.DAILY
            repeat(14) { day ->
                val date = LocalDate(2026, 11, day + 1)
                computedEntries.add(Entry(date, Entry.YES_MANUAL))
            }
        }
        val denseState = ShowHabitPresenter.buildState(denseHabit, preferences, theme)
        assertTrue("Dense habit should have multiple score buckets", denseState.scores.scores.size > 1)
    }

    @Test
    fun testNumericalAtMostWithZeroTargetAndNoEntryDistinction() {
        val habit = factory.buildHabit().apply {
            name = "No Smoking"
            type = HabitType.NUMERICAL
            unit = "cigarettes"
            targetType = NumericalHabitType.AT_MOST
            targetValue = 0.0
            frequency = Frequency.DAILY
        }

        // Case A: No entry today (absent record)
        val noEntryState = ShowHabitPresenter.buildState(habit, preferences, theme)
        assertThat(noEntryState.history.series.firstOrNull(), equalTo(HistoryChart.Square.OFF))

        // Case B: Explicit 0 recorded today
        habit.originalEntries.add(Entry(LocalDate(2026, 11, 7), 0))
        habit.computedEntries.add(Entry(LocalDate(2026, 11, 7), 0))
        val withZeroState = ShowHabitPresenter.buildState(habit, preferences, theme)
        assertThat(withZeroState.history.series.firstOrNull(), equalTo(HistoryChart.Square.ON))
    }
}
