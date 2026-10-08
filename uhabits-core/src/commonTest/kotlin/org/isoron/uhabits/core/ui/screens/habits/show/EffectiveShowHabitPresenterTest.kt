package org.isoron.uhabits.core.ui.screens.habits.show

import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.*
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.views.LightTheme
import kotlin.test.*

class EffectiveShowHabitPresenterTest : BaseUnitTest() {
    private fun habit() = modelFactory.buildHabit().apply {
        uuid = "detail-habit"; type = HabitType.NUMERICAL; unit = "min"
        frequency = Frequency.DAILY; targetValue = 100.0
    }
    private fun snapshot(h: Habit, repo: MemoryContributionRepository) =
        EffectiveHabitReadSnapshotProvider(repo).prepare(
            listOf(h), getToday().minus(365), getToday(), "test", 0,
            EffectiveReadCalendarContext("UTC", DayOfWeek.MONDAY, 0, 0)
        ).getValue(HabitRef(h.uuid!!))

    @Test fun noCreditPreservesEveryExistingCard() {
        val h = habit()
        h.originalEntries.add(Entry(getToday(), 30000, "manual note")); h.recompute()
        val prefs = Preferences(MemoryStorage()); val theme = LightTheme()
        val legacy = ShowHabitPresenter.buildState(h, prefs, theme)
        val effective = ShowHabitPresenter.buildState(h, prefs, theme, snapshot(h, MemoryContributionRepository()))
        assertEquals(legacy.history, effective.history)
        assertEquals(legacy.target, effective.target)
        assertEquals(legacy.scores, effective.scores)
        assertEquals(legacy.streaks, effective.streaks)
        assertEquals(legacy.bar, effective.bar)
        assertEquals(legacy.frequency.frequency.keys, effective.frequency.frequency.keys)
        legacy.frequency.frequency.forEach { (date, values) ->
            assertContentEquals(values, effective.frequency.frequency[date])
        }
        assertEquals(legacy.notes, effective.notes)
        assertEquals(legacy.overview, effective.overview)
        assertFalse(effective.hasIncompleteCoverage)
    }

    @Test fun automaticOnlyHistoryIsVisibleAndScoreIsConservativelyUnavailable() {
        val h = habit(); val repo = MemoryContributionRepository(); val date = getToday().minus(1)
        val timestamp = startOfLocalDay(date) + 1000
        repo.record(Contribution(ContributionId("detail-credit"), HabitRef(h.uuid!!),
            ContributionSourceType.FOCUS_SESSION, "detail-focus", 45000, timestamp, timestamp, timestamp))
        val state = ShowHabitPresenter.buildState(h, Preferences(MemoryStorage()), LightTheme(), snapshot(h, repo))
        assertEquals(2, state.history.series.size)
        assertEquals(org.isoron.uhabits.core.ui.views.HistoryChart.Square.GREY, state.history.series[1])
        assertTrue(state.hasAutomaticQuantity)
        assertTrue(state.hasIncompleteCoverage)
        assertTrue(state.scoreUnavailable)
        assertTrue(state.streakUnavailable)
        assertTrue(state.scores.scores.isEmpty())
        assertEquals(Entry.UNKNOWN, h.originalEntries.get(date).value)
        assertTrue(h.originalEntries.getKnown().isEmpty())
    }

    @Test fun manualHistoricalUnitBoundaryOnlyGatesCalendarGoal() {
        val h = habit(); h.frequency = Frequency(1, 7); h.targetValue = 300.0
        h.goalHistory.add(HabitGoal(getToday().minus(6), Frequency(1, 7), NumericalHabitType.AT_LEAST, 300.0, "pages"))
        h.goalHistory.add(HabitGoal(getToday(), Frequency(1, 7), NumericalHabitType.AT_LEAST, 300.0, "min"))
        h.originalEntries.add(Entry(getToday().minus(1), 60000)); h.originalEntries.add(Entry(getToday(), 30000))
        h.recompute()
        val prefs = Preferences(MemoryStorage()); val theme = LightTheme()
        val legacy = ShowHabitPresenter.buildState(h, prefs, theme)
        val effective = ShowHabitPresenter.buildState(h, prefs, theme, snapshot(h, MemoryContributionRepository()))
        assertTrue(effective.goalAchievementUnavailable)
        assertNull(effective.goalAchievement)
        assertEquals(legacy.history, effective.history)
        assertEquals(legacy.scores, effective.scores)
        assertEquals(legacy.streaks, effective.streaks)
        assertEquals(legacy.target, effective.target)
        assertFalse(effective.hasAutomaticQuantity)
    }
}
