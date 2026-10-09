package org.isoron.uhabits.core.ui.widgets

import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.*
import org.isoron.uhabits.core.ui.screens.habits.show.views.*
import org.isoron.uhabits.core.ui.views.*
import kotlin.test.*

class EffectiveWidgetPresenterTest : BaseUnitTest() {
    private val monday = LocalDate(2026, 10, 5)
    private val repository = MemoryContributionRepository()
    private val theme = LightTheme()
    private var sequence = 0
    @BeforeTest fun today() { setToday(monday) }
    private fun habit() = modelFactory.buildHabit().apply {
        uuid = "widget-${++sequence}"; type = HabitType.NUMERICAL; unit = "min"
        frequency = Frequency(1, 7); targetValue = 300.0
    }
    private fun credit(h: Habit, date: LocalDate = monday, value: Long): Contribution {
        val id = "credit-${++sequence}"
        val timestamp = startOfLocalDay(date) + 1000
        return Contribution(ContributionId(id), HabitRef(h.uuid!!), ContributionSourceType.FOCUS_SESSION,
            id, value, timestamp, timestamp, timestamp).also(repository::record)
    }
    private fun read(h: Habit, calendar: EffectiveReadCalendarContext = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)) =
        EffectiveHabitReadSnapshotProvider(repository).prepare(listOf(h), monday.minus(30), getToday(), "widgets", 1, calendar).getValue(HabitRef(h.uuid!!))

    @Test fun weekly330Then240UsesCanonicalGoalWithoutInventingScoreOrStreak() {
        val h = habit(); h.originalEntries.add(Entry(monday, 60000)); h.recompute()
        credit(h, value = 180000); val correction = credit(h, value = 90000)
        val first = read(h)
        assertEquals(330000L, EffectiveWidgetPresenter.checkmark(h, first).quantity)
        assertEquals(HabitGoalStatus.SATISFIED, EffectiveWidgetPresenter.target(first).progress!!.status)
        assertNull(EffectiveWidgetPresenter.checkmark(h, first).score)
        assertNull(EffectiveWidgetPresenter.streak(h, first, theme))
        repository.removeById(correction.id, startOfLocalDay(monday) + 2000)
        val second = read(h)
        assertEquals(240000L, EffectiveWidgetPresenter.checkmark(h, second).quantity)
        assertEquals(HabitGoalStatus.UNKNOWN, EffectiveWidgetPresenter.target(second).progress!!.status)
        assertEquals(60000, h.originalEntries.get(monday).value)
    }

    @Test fun manualAndBooleanChartsKeepLegacyParityWithoutCredits() {
        for (boolean in listOf(false, true)) {
            val h = habit(); if (boolean) h.type = HabitType.YES_NO
            h.originalEntries.add(Entry(monday, if (boolean) Entry.YES_MANUAL else 30000)); h.recompute()
            val snapshot = read(h)
            assertEquals(HistoryCardPresenter.buildState(h, DayOfWeek.MONDAY, theme), EffectiveWidgetPresenter.history(h, snapshot, theme))
            assertEquals(ScoreCardPresenter.buildState(h, 2, 0, theme), EffectiveWidgetPresenter.score(h, snapshot, 2, 0, theme))
            assertEquals(StreakCartPresenter.buildState(h, theme), EffectiveWidgetPresenter.streak(h, snapshot, theme))
            val expected = FrequencyCardPresenter.buildState(h, DayOfWeek.MONDAY, theme).frequency
            val actual = EffectiveWidgetPresenter.frequency(h, snapshot, DayOfWeek.MONDAY, theme)!!.frequency
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (date, values) -> assertContentEquals(values, actual[date]) }
        }
    }

    @Test fun contributionOnlyHistorySurvivesAndFrequencyIsExplicitlyUnavailable() {
        val h = habit(); credit(h, monday.minus(2), 60000)
        val snapshot = read(h)
        assertEquals(3, EffectiveWidgetPresenter.history(h, snapshot, theme).series.size)
        assertEquals(HistoryChart.Square.GREY, EffectiveWidgetPresenter.history(h, snapshot, theme).series.last())
        assertNull(EffectiveWidgetPresenter.frequency(h, snapshot, DayOfWeek.MONDAY, theme))
    }

    @Test fun atMostIncompleteNeverAchievedAndAtLeastLowerBoundCanProveAchievement() {
        val h = habit(); h.targetType = NumericalHabitType.AT_MOST; credit(h, value = 180000)
        assertEquals(HabitGoalStatus.UNKNOWN, EffectiveWidgetPresenter.target(read(h)).progress!!.status)
        h.targetType = NumericalHabitType.AT_LEAST
        assertEquals(HabitGoalStatus.UNKNOWN, EffectiveWidgetPresenter.target(read(h)).progress!!.status)
        credit(h, value = 180000)
        assertEquals(HabitGoalStatus.SATISFIED, EffectiveWidgetPresenter.target(read(h)).progress!!.status)
        assertFalse(EffectiveWidgetPresenter.checkmark(h, read(h)).completeCoverage)
    }

    @Test fun skippedCreditRetainsProvenanceButIsExcludedFromGoal() {
        val h = habit(); h.originalEntries.add(Entry(monday, Entry.SKIP)); h.recompute(); credit(h, value = 330000)
        val snapshot = read(h)
        assertTrue(EffectiveWidgetPresenter.checkmark(h, snapshot).skipped)
        assertEquals(330000L, EffectiveWidgetPresenter.checkmark(h, snapshot).focusQuantity)
        assertNull(EffectiveWidgetPresenter.target(snapshot).progress!!.value.totalValue)
        assertEquals(HistoryChart.Square.HATCHED, EffectiveWidgetPresenter.history(h, snapshot, theme).series.first())
    }

    @Test fun explicitZeroAndSentinelCollisionStayQuantitiesAndNeverWriteEntries() {
        val h = habit(); h.frequency = Frequency.DAILY; h.originalEntries.add(Entry(monday, 0)); h.recompute()
        credit(h, value = 3)
        val snapshot = read(h)
        assertEquals(3L, EffectiveWidgetPresenter.checkmark(h, snapshot).quantity)
        assertEquals(0L, EffectiveWidgetPresenter.checkmark(h, snapshot).manualQuantity)
        assertTrue(EffectiveWidgetPresenter.checkmark(h, snapshot).completeCoverage)
        assertEquals(0, h.originalEntries.get(monday).value)
        assertEquals(HistoryChart.Square.GREY, EffectiveWidgetPresenter.history(h, snapshot, theme).series.first())
    }

    @Test fun frequencyKeepsQuantityUnitsAndGatesOverflow() {
        val h = habit(); h.originalEntries.add(Entry(monday, 30000)); h.recompute(); credit(h, value = 60000)
        val state = EffectiveWidgetPresenter.frequency(h, read(h), DayOfWeek.MONDAY, theme)!!
        assertEquals(90000, state.frequency[monday.startOfMonth()]!![2])
        credit(h, value = Int.MAX_VALUE.toLong())
        assertNull(EffectiveWidgetPresenter.frequency(h, read(h), DayOfWeek.MONDAY, theme))
        assertEquals(30000, h.originalEntries.get(monday).value)
    }

    @Test fun historicalGoalsUseSnapshotCalendarAuthority() {
        val h = habit(); h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2000, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 100.0, "min"),
            HabitGoal(monday, Frequency(1, 7), NumericalHabitType.AT_LEAST, 300.0, "min"))
        credit(h, monday.minus(1), 150000); credit(h, value = 180000)
        val snapshot = read(h)
        assertEquals(HabitGoalStatus.SATISFIED, EffectiveWidgetPresenter.target(snapshot, monday.minus(1)).progress!!.status)
        assertEquals(HabitGoalStatus.UNKNOWN, EffectiveWidgetPresenter.target(snapshot).progress!!.status)
    }

    @Test fun dayOffsetChangesContributionBucketThroughSharedCalendarContext() {
        val h = habit(); credit(h, value = 60000)
        val ordinary = read(h)
        val delayed = read(h, EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 3, 0))
        assertEquals(60000L, ordinary.day(monday).contributionValue)
        assertEquals(0L, delayed.day(monday).contributionValue)
        assertEquals(60000L, delayed.day(monday.minus(1)).contributionValue)
        assertEquals(monday.minus(1), delayed.observedFrom)
        assertNull(EffectiveWidgetPresenter.checkmark(h, delayed).quantity)
    }

    @Test fun incompatibleHistoricalUnitsGateTargetAndFrequency() {
        val h = habit(); h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2000, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 5.0, "km"),
            HabitGoal(monday.plus(2), Frequency(1, 7), NumericalHabitType.AT_LEAST, 300.0, "min"))
        h.originalEntries.add(Entry(monday, 5000)); h.recompute()
        credit(h, monday.plus(2), 180000); setToday(monday.plus(2))
        val snapshot = read(h)
        assertFalse(EffectiveWidgetPresenter.target(snapshot).available)
        assertNull(EffectiveWidgetPresenter.frequency(h, snapshot, DayOfWeek.MONDAY, theme))
        assertNull(EffectiveWidgetPresenter.score(h, snapshot, 2, 0, theme))
    }
}
