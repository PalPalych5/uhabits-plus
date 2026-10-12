package org.isoron.uhabits.activities.habits.show

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.*
import org.junit.Assert.*
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
    private fun state() = FocusProgressPresentation.from(service.goalProgressOn(habit, date))
    private fun manual(value: Int) { habit.originalEntries.add(Entry(date, value)) }
    private fun focus(): Contribution {
        val time = startOfLocalDay(date) + 1000
        return Contribution(ContributionId("credit"), HabitRef("study"),
            ContributionSourceType.FOCUS_SESSION, "session", 42000, time, time, time)
            .also { repository.record(it) }
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
        habit.targetType = NumericalHabitType.AT_MOST
        focus()
        assertTrue(state().atMost)
        assertEquals(HabitGoalStatus.UNKNOWN, state().status)
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
