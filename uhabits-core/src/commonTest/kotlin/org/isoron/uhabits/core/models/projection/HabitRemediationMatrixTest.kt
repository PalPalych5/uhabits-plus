/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.models.projection

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.LightTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R05 regression matrix across the 5 core habit profiles:
 * binary daily, binary 3/week, numerical AT_LEAST daily, Focus-linked numerical, and numerical AT_MOST weekly.
 */
class HabitRemediationMatrixTest : BaseUnitTest() {
    private val wednesday = LocalDate(2026, 11, 11) // Ongoing Mon Nov 9..Sun Nov 15 week
    private val calendar = EffectiveReadCalendarContext("UTC", DayOfWeek.MONDAY, 0, 0)
    private val repository = MemoryContributionRepository()

    @BeforeTest
    override fun setUp() {
        super.setUp()
        setToday(wednesday)
    }

    private fun readState(habit: Habit, from: LocalDate = wednesday.minus(40)) =
        EffectiveHabitReadSnapshotProvider(repository).prepare(
            listOf(habit), from, wednesday, "matrix", 1L, calendar
        ).getValue(HabitRef(habit.uuid!!)).let { snap ->
            snap to ShowHabitPresenter.buildState(habit, Preferences(MemoryStorage()), LightTheme(), snap)
        }

    private fun credit(habit: Habit, date: LocalDate, millis: Long, id: String) {
        val ts = startOfLocalDay(date) + 3_600_000L
        repository.record(
            Contribution(
                id = ContributionId(id),
                targetHabit = HabitRef(habit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-$id",
                contributedValue = millis,
                effectiveTimestamp = ts,
                createdAt = ts,
                updatedAt = ts,
            )
        )
    }

    @Test
    fun profile1And2_binaryDailyAndWeeklyMaintainStreaksAndDedupLatest() {
        val daily = modelFactory.buildHabit().apply {
            uuid = "m2-binary-daily"
            type = HabitType.YES_NO
            frequency = Frequency.DAILY
            for (d in 0..2) originalEntries.add(Entry(wednesday.minus(d), Entry.YES_MANUAL))
            for (d in 5..8) originalEntries.add(Entry(wednesday.minus(d), Entry.YES_MANUAL))
            for (d in 12..14) originalEntries.add(Entry(wednesday.minus(d), Entry.YES_MANUAL))
            recompute()
        }
        val (dailySnap, dailyState) = readState(daily)
        assertEquals(Streak(wednesday.minus(2), wednesday), dailyState.streaks.latestStreak)
        assertFalse(dailyState.streaks.bestStreaks.contains(dailyState.streaks.latestStreak))
        assertEquals(listOf(4, 3), dailyState.streaks.bestStreaks.map { it.length })
        assertTrue((dailySnap.scoreResult.on(wednesday) ?: 0.0) > 0.0)

        val weekly3 = modelFactory.buildHabit().apply {
            uuid = "m2-binary-3w"
            type = HabitType.YES_NO
            frequency = Frequency(3, 7)
            val prevMon = LocalDate(2026, 11, 2)
            originalEntries.add(Entry(prevMon, Entry.YES_MANUAL))
            originalEntries.add(Entry(prevMon.plus(2), Entry.YES_MANUAL))
            originalEntries.add(Entry(prevMon.plus(5), Entry.YES_MANUAL))
            recompute()
        }
        val (wSnap, wState) = readState(weekly3)
        assertNotNull(wState.streaks.latestStreak)
        assertTrue(wState.streaks.latestStreak!!.length >= 6)
        assertTrue((wSnap.scoreResult.on(LocalDate(2026, 11, 7)) ?: 0.0) > 0.0)
    }

    @Test
    fun profile3_numericAtLeastDailyHandlesZeroSkipAndRetroEdit() {
        val readBook = modelFactory.buildHabit().apply {
            uuid = "m2-num-at-least"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 20.0
            unit = "pages"
            frequency = Frequency.DAILY
            originalEntries.add(Entry(wednesday.minus(2), 20_000))
            originalEntries.add(Entry(wednesday.minus(1), Entry.SKIP))
            originalEntries.add(Entry(wednesday, 0))
            recompute()
        }
        val (_, initialState) = readState(readBook)
        assertFalse(initialState.scoreUnavailable)
        assertFalse(initialState.streakUnavailable)
        assertEquals(Streak(wednesday.minus(2), wednesday.minus(2)), initialState.streaks.latestStreak)

        readBook.originalEntries.add(Entry(wednesday, 25_000))
        readBook.recompute()
        val (editedSnap, editedState) = readState(readBook)
        assertEquals(Streak(wednesday, wednesday), editedState.streaks.latestStreak)
        assertTrue((editedSnap.scoreResult.on(wednesday) ?: 0.0) > 0.0)
    }

    @Test
    fun profile4_focusLinkedNumericPreservesScoreAndStreaksWithoutManualZeroFill() {
        val focusHabit = modelFactory.buildHabit().apply {
            uuid = "m2-focus"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 25.0
            unit = "min"
            frequency = Frequency.DAILY
            recompute()
        }
        credit(focusHabit, wednesday.minus(2), 30_000, "f1")
        credit(focusHabit, wednesday.minus(1), 40_000, "f2")
        credit(focusHabit, wednesday, 288_480, "f3")

        val (snap, state) = readState(focusHabit)
        assertEquals(EffectiveReadExactness.INCOMPLETE, snap.scoreResult.exactness)
        assertFalse(state.scoreUnavailable)
        assertFalse(state.streakUnavailable)
        assertTrue((snap.scoreResult.on(wednesday) ?: 0.0) > 0.0)
        assertEquals(Streak(wednesday.minus(2), wednesday), state.streaks.latestStreak)
    }

    @Test
    fun profile5_weeklyAtMostAwardsCompletedWeeksOnlyAndHandlesLimitBoundaries() {
        val games = modelFactory.buildHabit().apply {
            uuid = "m2-at-most-weekly"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 180.0
            unit = "min"
            frequency = Frequency.WEEKLY
            val w1 = LocalDate(2026, 10, 19) // Mon..Sun completed at exact 180 boundary
            for (d in 0..5) originalEntries.add(Entry(w1.plus(d), 0))
            originalEntries.add(Entry(w1.plus(6), 180_000))
            val w2 = LocalDate(2026, 10, 26) // Mon..Sun completed > 180 -> failed
            for (d in 0..5) originalEntries.add(Entry(w2.plus(d), 0))
            originalEntries.add(Entry(w2.plus(6), 185_000))
            val w3 = LocalDate(2026, 11, 2) // Mon..Sun completed at 60 <= 180
            for (d in 0..5) originalEntries.add(Entry(w3.plus(d), 0))
            originalEntries.add(Entry(w3.plus(6), 60_000))
            val ongoing = LocalDate(2026, 11, 9) // Mon..Wed logged 0 (today = Wed Nov 11), Thu..Sun future/unlogged
            originalEntries.add(Entry(ongoing, 0))
            originalEntries.add(Entry(ongoing.plus(1), 0))
            originalEntries.add(Entry(ongoing.plus(2), 0))
            recompute()
        }
        val (snap, state) = readState(games)
        assertFalse(state.scoreUnavailable)
        assertFalse(state.streakUnavailable)
        // Ongoing week (Nov 9..15) MUST NOT prematurely earn a streak! Latest completed week is Nov 2..8.
        assertEquals(Streak(LocalDate(2026, 11, 2), LocalDate(2026, 11, 8)), state.streaks.latestStreak)
        assertEquals(listOf(Streak(LocalDate(2026, 10, 19), LocalDate(2026, 10, 25))), state.streaks.bestStreaks)
        assertEquals(HabitGoalStatus.UNKNOWN, snap.goalAchievementOn(wednesday).status)
        assertEquals(HistoryChart.Square.ON, state.history.series.first { it == HistoryChart.Square.ON })
    }
}
