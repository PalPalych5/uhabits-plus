package org.isoron.uhabits.activities.habits.show

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.resetToday
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FocusProgressPresentationTest {
    private val date = LocalDate(2026, 10, 6)
    private val repository = MemoryContributionRepository()
    private val habit = MemoryModelFactory().buildHabit().apply {
        uuid = "study"
        type = HabitType.NUMERICAL
        unit = "min"
        frequency = Frequency.DAILY
        targetValue = 300.0
    }
    private val service = EffectiveHabitValueService(repository)
    private fun state(day: LocalDate = date, today: LocalDate = getToday()) =
        FocusProgressPresentation.from(service.goalProgressOn(habit, day, today = today))
    private fun manual(value: Int, day: LocalDate = date) { habit.originalEntries.add(Entry(day, value)) }
    private fun focus(value: Long = 42000, day: LocalDate = date, id: String = "credit"): Contribution {
        val time = startOfLocalDay(day) + 1000
        return Contribution(ContributionId(id), HabitRef("study"),
            ContributionSourceType.FOCUS_SESSION, "session-$id", value, time, time, time)
            .also { repository.record(it) }
    }

    @Before fun initToday() {
        setToday(LocalDate(2026, 11, 15))
    }

    @After fun restoreToday() {
        resetToday()
    }

    @Test fun missingIsUnknownWhileExplicitZeroIsExact() {
        assertNull(state().total)
        manual(0)
        assertEquals(0L, state().total)
        assertFalse(state().lowerBound)
    }
    @Test fun automaticOnlyDisplaysLowerBoundWithoutInventingManualCoverage() {
        focus()
        val state = state()
        assertEquals(42000L, state.total)
        assertTrue(state.lowerBound)
        assertEquals(0L, state.manual)
        assertEquals(42000L, state.focus)
    }
    @Test fun manualOnlyThenCombinedRemainExactAndSeparated() {
        manual(12000)
        assertEquals(12000L, state().total)
        assertFalse(state().lowerBound)
        focus()
        assertEquals(54000L, state().total)
        assertEquals(12000L, state().manual)
        assertEquals(42000L, state().focus)
    }
    @Test fun correctedFocusDoesNotRemoveManual() {
        manual(12000)
        val credit = focus()
        repository.removeById(credit.id, credit.createdAt + 1000)
        assertEquals(12000L, state().total)
        assertEquals(0L, state().focus)
    }
    @Test fun skippedCreditIsOnlyVisibleAsExcludedAndDoesNotAdvanceGoal() {
        manual(Entry.SKIP)
        focus()
        val state = state()
        assertNull(state.total)
        assertEquals(0L, state.focus)
        assertEquals(42000L, state.excludedFocus)
        assertEquals(HabitGoalStatus.SKIPPED, state.status)
    }
    @Test fun atMostKeepsIncompleteCoverageUnknown() {
        setToday(date.plus(5))
        habit.targetType = NumericalHabitType.AT_MOST
        habit.missingDataPolicy = MissingDataPolicy.UNKNOWN
        focus()
        assertTrue(state().atMost)
        assertTrue(state().lowerBound)
        assertEquals(HabitGoalStatus.UNKNOWN, state().status)
        // Ongoing day with explicit UNKNOWN policy also stays UNKNOWN without manual coverage
        val ongoingDay = date.plus(5)
        focus(value = 15000, day = ongoingDay, id = "ongoing-credit")
        assertEquals(HabitGoalStatus.UNKNOWN, state(day = ongoingDay, today = ongoingDay).status)
        // Explicit manual entry completes coverage -> SATISFIED when <= target
        manual(0, day = ongoingDay)
        assertEquals(HabitGoalStatus.SATISFIED, state(day = ongoingDay, today = ongoingDay).status)
        // Exceeding the limit marks NOT_SATISFIED even with incomplete manual coverage
        focus(value = 300000, day = date, id = "exceed-credit")
        assertEquals(HabitGoalStatus.NOT_SATISFIED, state(day = date).status)
    }

    @Test fun atMostDefaultZeroDistinguishesOngoingAndCompletedPeriods() {
        val monday = LocalDate(2026, 10, 5)
        setToday(monday.plus(2))
        habit.targetType = NumericalHabitType.AT_MOST
        habit.missingDataPolicy = null // defaults to DEFAULT_ZERO
        assertEquals(MissingDataPolicy.DEFAULT_ZERO, habit.effectiveMissingDataPolicy())

        // Daily ongoing today with Focus-only (no manual entry) stays UNKNOWN until day ends or manual input
        val wed = monday.plus(2)
        focus(value = 42000, day = wed, id = "daily-ongoing")
        assertEquals(HabitGoalStatus.UNKNOWN, state(day = wed, today = wed).status)
        // Completed past day with Focus-only defaults missing manual entry to 0 -> SATISFIED (42 <= 300)
        focus(value = 42000, day = monday, id = "daily-completed")
        assertEquals(HabitGoalStatus.SATISFIED, state(day = monday, today = wed).status)
        // Explicit manual total on ongoing today <= target is SATISFIED immediately
        manual(0, day = wed)
        assertEquals(HabitGoalStatus.SATISFIED, state(day = wed, today = wed).status)

        // Weekly DEFAULT_ZERO: ongoing week stays UNKNOWN unless exceeded; ended week within limit is SATISFIED
        habit.frequency = Frequency.WEEKLY
        habit.originalEntries.remove(wed)
        assertEquals(HabitGoalStatus.UNKNOWN, state(day = monday, today = wed).status)
        assertEquals(HabitGoalStatus.SATISFIED, state(day = monday, today = monday.plus(7)).status)
        // Exceeded ongoing weekly limit (> 300) immediately becomes NOT_SATISFIED
        focus(value = 250000, day = wed, id = "weekly-exceed")
        assertEquals(HabitGoalStatus.NOT_SATISFIED, state(day = monday, today = wed).status)
    }

    @Test fun focusOnOneDayDoesNotReinterpretOrFabricateZerosOnUnrelatedDays() {
        val sunday = LocalDate(2026, 10, 11)
        val thursday = LocalDate(2026, 10, 8)
        habit.originalEntries.add(Entry(sunday, 34500))
        habit.originalEntries.add(Entry(thursday, Entry.UNKNOWN))
        val creditTime = startOfLocalDay(sunday) + 1000
        repository.record(Contribution(ContributionId("sunday-focus"), HabitRef("study"),
            ContributionSourceType.FOCUS_SESSION, "session", 1000, creditTime, creditTime, creditTime))

        val sundayDay = service.valueForDay(habit, sunday)
        assertEquals(35500L, sundayDay.totalValue)
        assertEquals(34500L, sundayDay.manualValue)
        assertEquals(1000L, sundayDay.contributionValue)
        assertTrue(sundayDay.hasCompleteCoverage)

        val thursdayDay = service.valueForDay(habit, thursday)
        assertNull(thursdayDay.manualValue)
        assertNull(thursdayDay.totalValue)
        assertFalse(thursdayDay.hasCompleteCoverage)
        assertEquals(Entry.UNKNOWN, thursdayDay.manualEntry.value)
        assertEquals(Entry.UNKNOWN, habit.computedEntries.get(thursday).value)
        assertEquals(Entry.UNKNOWN, habit.originalEntries.get(thursday).value)
    }
}
