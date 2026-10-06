package org.isoron.uhabits.core.models.projection

import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.rules.*
import org.isoron.uhabits.core.rules.memory.MemorySourceRuleRepository
import kotlin.test.*

class EffectiveHabitValueServiceTest : BaseUnitTest() {
    private val monday = LocalDate(2026, 10, 5)
    private lateinit var repository: MemoryContributionRepository
    private lateinit var service: EffectiveHabitValueService
    private var sequence = 0

    @BeforeTest fun initializeProjection() {
        repository = MemoryContributionRepository()
        service = EffectiveHabitValueService(repository, firstWeekday = DayOfWeek.MONDAY)
        sequence = 0
    }

    private fun habit(unit: String = "min", frequency: Frequency = Frequency.DAILY,
                      target: Double = 45.0, type: NumericalHabitType = NumericalHabitType.AT_LEAST): Habit =
        modelFactory.buildHabit().apply {
            uuid = "habit-${++sequence}"
            this.type = HabitType.NUMERICAL
            this.unit = unit
            this.frequency = frequency
            targetValue = target
            targetType = type
        }

    private fun manual(habit: Habit, value: Int, date: LocalDate = monday) {
        habit.originalEntries.add(Entry(date, value))
    }

    private fun credit(habit: Habit, value: Long, date: LocalDate = monday,
                       timestamp: Long = startOfLocalDay(date) + 1000,
                       rule: SourceRuleId? = null): Contribution {
        val id = "credit-${++sequence}"
        return Contribution(ContributionId(id), HabitRef(habit.uuid!!), ContributionSourceType.FOCUS_SESSION,
            "focus-$id", value, timestamp, timestamp, timestamp, sourceRuleId = rule)
            .also { repository.record(it) }
    }

    @Test fun sumPreservesBreakdownAndOriginalEntry() {
        val h = habit(); manual(h, 12000); val c = credit(h, 33000)
        val day = service.valueForDay(h, monday)
        assertEquals(12000L, day.manualValue); assertEquals(33000L, day.contributionValue)
        assertEquals(45000L, day.totalValue); assertEquals(listOf(c), day.contributions)
        assertEquals(12000, h.originalEntries.get(monday).value)
    }

    @Test fun manualOnlyMatchesLegacyQuantity() {
        val h = habit(); manual(h, 17000)
        assertEquals(17000L, service.valueForDay(h, monday).totalValue)
    }

    @Test fun unknownPlusCreditHasQuantityButIncompleteCoverage() {
        val h = habit(); credit(h, 30000)
        val value = service.valueForRange(h, monday, monday)
        assertEquals(30000L, value.totalValue); assertEquals(listOf(monday), value.unknownDates)
        assertNull(value.days.single().manualValue); assertFalse(value.hasCompleteCoverage)
        assertEquals(Entry.UNKNOWN, h.originalEntries.get(monday).value)
    }

    @Test fun missingDiffersFromExplicitManualZero() {
        val h = habit(); assertNull(service.valueForDay(h, monday).totalValue)
        manual(h, 0)
        val day = service.valueForDay(h, monday)
        assertEquals(0L, day.totalValue); assertTrue(day.hasCompleteCoverage)
    }

    @Test fun explicitDerivedZeroIsObservedButDoesNotCompleteCoverage() {
        val h = habit(); credit(h, 0)
        val day = service.valueForDay(h, monday)
        assertEquals(0L, day.totalValue); assertFalse(day.hasCompleteCoverage)
        assertEquals(1, day.contributionCount)
    }

    @Test fun manualZeroPlusCreditAndCreditZeroPlusManual() {
        val h = habit(); manual(h, 0); credit(h, 10000)
        assertEquals(10000L, service.valueForDay(h, monday).totalValue)
        val other = habit(); manual(other, 5000); credit(other, 0)
        assertEquals(5000L, service.valueForDay(other, monday).totalValue)
    }

    @Test fun skipExcludesCreditButRetainsProvenance() {
        val h = habit(); manual(h, Entry.SKIP); val c = credit(h, 30000)
        val value = service.valueForRange(h, monday, monday)
        assertNull(value.totalValue); assertEquals(0L, value.contributionValue)
        assertEquals(30000L, value.excludedContributionValue)
        assertEquals(listOf(c), value.days.single().contributions)
        assertEquals(listOf(monday), value.skippedDates)
        assertEquals(HabitGoalStatus.SKIPPED, service.goalProgressOn(h, monday).status)
    }

    @Test fun tombstoneLeavesManualAndSiblingCredit() {
        val h = habit(); manual(h, 12000); val first = credit(h, 30000); credit(h, 7000)
        repository.removeById(first.id, first.createdAt + 1000)
        assertEquals(19000L, service.valueForDay(h, monday).totalValue)
        assertEquals(12000, h.originalEntries.get(monday).value)
    }

    @Test fun multipleFocusFactsSumWithinDay() {
        val h = habit(); credit(h, 20000); credit(h, 40000)
        val day = service.valueForDay(h, monday)
        assertEquals(60000L, day.totalValue); assertEquals(2, day.contributionCount)
    }

    @Test fun daysRemainIndependentWithinRange() {
        val h = habit(); manual(h, 1000); credit(h, 2000, monday.plus(1)); credit(h, 4000, monday.plus(2))
        val value = service.valueForRange(h, monday, monday.plus(2))
        assertEquals(listOf(1000L, 2000L, 4000L), value.days.map { it.totalValue })
        assertEquals(7000L, value.totalValue)
    }

    @Test fun weeklyManualAndCreditProduceTwoHundredMinutes() {
        val h = habit(frequency = Frequency(1, 7), target = 300.0)
        manual(h, 30000); manual(h, 20000, monday.plus(1))
        credit(h, 90000, monday.plus(2)); credit(h, 60000, monday.plus(3))
        credit(h, 999000, monday.minus(1)); credit(h, 999000, monday.plus(7))
        val goal = service.goalProgressOn(h, monday.plus(3))
        assertEquals(monday, goal.from); assertEquals(monday.plus(6), goal.to)
        assertEquals(200000L, goal.value.totalValue)
        assertEquals(HabitGoalStatus.UNKNOWN, goal.status)
        (2..6).forEach { manual(h, 0, monday.plus(it)) }
        assertEquals(HabitGoalStatus.NOT_SATISFIED, service.goalProgressOn(h, monday.plus(3)).status)
    }

    @Test fun monthlyGoalUsesCalendarMonthBoundaries() {
        val h = habit(frequency = Frequency(1, 30), target = 200.0)
        manual(h, 50000, LocalDate(2026, 10, 1)); credit(h, 150000, LocalDate(2026, 10, 31))
        credit(h, 999000, LocalDate(2026, 9, 30)); credit(h, 999000, LocalDate(2026, 11, 1))
        val goal = service.goalProgressOn(h, monday)
        assertEquals(LocalDate(2026, 10, 1), goal.from); assertEquals(LocalDate(2026, 10, 31), goal.to)
        assertEquals(200000L, goal.value.totalValue); assertEquals(HabitGoalStatus.SATISFIED, goal.status)
    }

    @Test fun atLeastUsesKnownLowerBoundAtThreshold() {
        val h = habit(target = 30.0); credit(h, 30000)
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(h, monday).status)
        h.targetValue = 31.0
        assertEquals(HabitGoalStatus.UNKNOWN, service.goalProgressOn(h, monday).status)
        manual(h, 0)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, service.goalProgressOn(h, monday).status)
    }

    @Test fun atMostRequiresCompleteCoverageDespiteKnownCredit() {
        val h = habit(target = 30.0, type = NumericalHabitType.AT_MOST); credit(h, 20000)
        assertEquals(HabitGoalStatus.UNKNOWN, service.goalProgressOn(h, monday).status)
        manual(h, 0)
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(h, monday).status)
        manual(h, 20000)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, service.goalProgressOn(h, monday).status)
        h.originalEntries.remove(monday)
        credit(h, 20000)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, service.goalProgressOn(h, monday).status)
    }

    @Test fun weeklyAtMostRequiresEveryIncludedUnskippedDayKnown() {
        val h = habit(frequency = Frequency(1, 7), target = 30.0, type = NumericalHabitType.AT_MOST)
        manual(h, 0); credit(h, 30000)
        assertEquals(HabitGoalStatus.UNKNOWN, service.goalProgressOn(h, monday).status)
        (1..6).forEach { manual(h, 0, monday.plus(it)) }
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(h, monday).status)
    }

    @Test fun booleanWithoutCreditUsesExistingCompletion() {
        val h = habit().apply { type = HabitType.YES_NO }
        manual(h, Entry.YES_MANUAL); h.recompute()
        assertNull(service.valueForDay(h, monday).totalValue)
        assertEquals(if (h.isCompletedOn(monday)) HabitGoalStatus.SATISFIED else HabitGoalStatus.NOT_SATISFIED,
            service.goalProgressOn(h, monday).status)
    }

    @Test fun booleanMalformedCreditThrowsTypedIntegrityError() {
        val h = habit().apply { type = HabitType.YES_NO }; credit(h, 10000)
        assertFailsWith<HabitProjectionIntegrityException> { service.valueForDay(h, monday) }
    }

    @Test fun manualStepsAllowedButAutomaticStepsRejected() {
        val h = habit(unit = "steps"); manual(h, 5000000)
        assertEquals(5000000L, service.valueForDay(h, monday).totalValue)
        credit(h, 1000)
        assertFailsWith<HabitProjectionIntegrityException> { service.valueForDay(h, monday) }
    }

    @Test fun manualEditAndDeletionLeaveCreditUntouched() {
        val h = habit(); manual(h, 12000); val c = credit(h, 33000)
        manual(h, 20000); assertEquals(53000L, service.valueForDay(h, monday).totalValue)
        h.originalEntries.remove(monday)
        assertEquals(33000L, service.valueForDay(h, monday).totalValue)
        assertEquals(c, repository.getById(c.id))
    }

    @Test fun removedRuleDoesNotAffectHistoricalCreditAndExplicitCorrectionDoes() {
        val h = habit(); val rules = MemorySourceRuleRepository()
        val rule = SourceRule(SourceRuleId("historical"), sourceContainerId = ContainerId("container"),
            targetHabit = HabitRef(h.uuid!!), createdAt = 1, updatedAt = 1)
        rules.record(rule); val c = credit(h, 30000, rule = rule.id)
        rules.remove(rule.id, c.createdAt + 1000)
        assertEquals(30000L, service.valueForDay(h, monday).totalValue)
        assertEquals(rule.id, service.valueForDay(h, monday).contributions.single().sourceRuleId)
        repository.removeById(c.id, c.createdAt + 2000)
        assertNull(service.valueForDay(h, monday).totalValue)
    }

    @Test fun statisticsCutoffExcludesQuantityButPreservesBreakdown() {
        val h = habit().apply { statisticsStartDate = monday.plus(1) }
        manual(h, 20000); credit(h, 30000); credit(h, 40000, monday.plus(1))
        val value = service.valueForRange(h, monday, monday.plus(1))
        assertEquals(40000L, value.totalValue); assertEquals(30000L, value.excludedContributionValue)
        assertEquals(HabitGoalStatus.EXCLUDED, service.goalProgressOn(h, monday).status)
    }

    @Test fun historicalUnitMustBeCompatibleEvenWhenCurrentUnitIsMinutes() {
        val h = habit().apply { goalHistory = mutableListOf(
            HabitGoal(monday.minus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "steps"),
            HabitGoal(monday.plus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "min")) }
        credit(h, 1000)
        assertFailsWith<HabitProjectionIntegrityException> { service.valueForDay(h, monday) }
    }

    @Test fun rangeCannotMixHistoricalManualUnitsWithMinuteCreditOnAnotherDay() {
        val h = habit().apply { goalHistory = mutableListOf(
            HabitGoal(monday, Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "hours"),
            HabitGoal(monday.plus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "min")) }
        manual(h, 1000)
        credit(h, 1000, monday.plus(1))
        assertFailsWith<HabitProjectionIntegrityException> { service.valueForRange(h, monday, monday.plus(1)) }
        assertEquals(1000L, service.valueForDay(h, monday.plus(1)).totalValue)
    }

    @Test fun projectionDoesNotNormalizeOrRewriteGoalHistory() {
        val h = habit()
        credit(h, 1000)
        assertTrue(h.goalHistory.isEmpty())
        service.valueForDay(h, monday)
        service.goalProgressOn(h, monday)
        assertTrue(h.goalHistory.isEmpty())
        val original = HabitGoal(monday, Frequency(1, 7), NumericalHabitType.AT_MOST, 99.0, "steps")
        h.goalHistory.add(original)
        // Legacy normalizes a singleton to current metadata, but projection must only read that view.
        assertEquals(1000L, service.valueForDay(h, monday).totalValue)
        assertEquals(45.0, service.goalProgressOn(h, monday).goal.targetValue)
        assertEquals(listOf(original), h.goalHistory)
    }

    @Test fun duplicateEarliestGoalUsesLastDefinitionWithoutMutatingHistory() {
        val h = habit().apply { goalHistory = mutableListOf(
            HabitGoal(monday, Frequency.DAILY, NumericalHabitType.AT_LEAST, 10.0, "min"),
            HabitGoal(monday, Frequency.DAILY, NumericalHabitType.AT_LEAST, 20.0, "min")) }
        val history = h.goalHistory.toList()
        assertEquals(20.0, service.goalProgressOn(h, monday.minus(1)).goal.targetValue)
        assertEquals(history, h.goalHistory)
    }

    @Test fun manualOnlyHistoricalUnitChangeIsNotSilentlyAggregated() {
        val h = habit().apply { goalHistory = mutableListOf(
            HabitGoal(monday, Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "hours"),
            HabitGoal(monday.plus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "min")) }
        manual(h, 1000); manual(h, 1000, monday.plus(1))
        assertEquals(1000L, service.valueForDay(h, monday).totalValue)
        assertFailsWith<HabitProjectionIntegrityException> { service.valueForRange(h, monday, monday.plus(1)) }
    }

    @Test fun equivalentMinuteAliasesCanBeAggregatedAcrossGoalHistory() {
        val h = habit().apply { goalHistory = mutableListOf(
            HabitGoal(monday, Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "мин"),
            HabitGoal(monday.plus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, "min")) }
        manual(h, 1000); credit(h, 1000, monday.plus(1))
        assertEquals(2000L, service.valueForRange(h, monday, monday.plus(1)).totalValue)
    }

    @Test fun periodQuantityMustMatchGoalUnitEvenWhenNewUnitDayIsUnknown() {
        val h = habit(frequency = Frequency(1, 7), target = 1.0).apply { goalHistory = mutableListOf(
            HabitGoal(monday, Frequency(1, 7), NumericalHabitType.AT_LEAST, 1.0, "hours"),
            HabitGoal(monday.plus(1), Frequency(1, 7), NumericalHabitType.AT_LEAST, 1.0, "min")) }
        manual(h, 1000)
        assertEquals(1000L, service.valueForRange(h, monday, monday.plus(6)).totalValue)
        assertFailsWith<HabitProjectionIntegrityException> { service.goalProgressOn(h, monday.plus(1)) }
    }

    @Test fun batchFetchesOneRangeForMultipleHabitsAndSeparatesTargets() {
        var calls = 0
        val counted = object : ContributionRepository by repository {
            override fun getByHabitsInRange(targetHabits: Set<HabitRef>, fromTimestamp: Long,
                                           toTimestamp: Long, includeRemoved: Boolean): List<Contribution> {
                calls++; return repository.getByHabitsInRange(targetHabits, fromTimestamp, toTimestamp, includeRemoved)
            }
        }
        val first = habit(); val second = habit(); credit(first, 30000); credit(second, 70000)
        val values = EffectiveHabitValueService(counted).valuesForRange(listOf(first, second), monday, monday.plus(30))
        assertEquals(1, calls); assertEquals(30000L, values.getValue(HabitRef(first.uuid!!)).totalValue)
        assertEquals(70000L, values.getValue(HabitRef(second.uuid!!)).totalValue)
        assertTrue(EffectiveHabitValueService(counted).valuesForRange(emptyList(), monday, monday).isEmpty())
        assertEquals(1, calls)
    }

    @Test fun duplicateIdentityAndReversedRangeAreRejected() {
        val h = habit()
        assertFailsWith<IllegalArgumentException> { service.valuesForRange(listOf(h, h), monday, monday) }
        assertFailsWith<IllegalArgumentException> { service.valueForRange(h, monday.plus(1), monday) }
    }

    @Test fun overflowIsTypedInsteadOfWrapping() {
        val h = habit(); manual(h, 1); credit(h, Long.MAX_VALUE)
        assertFailsWith<HabitProjectionIntegrityException> { service.valueForDay(h, monday) }
        h.originalEntries.remove(monday); credit(h, 1)
        assertFailsWith<HabitProjectionIntegrityException> { service.valueForDay(h, monday) }
    }

    @Test fun exactLocalDayBoundaryUsesCanonicalHabitDate() {
        val h = habit(); credit(h, 10000, timestamp = startOfLocalDay(monday) - 1)
        credit(h, 20000, timestamp = startOfLocalDay(monday))
        val value = service.valueForRange(h, monday.minus(1), monday)
        assertEquals(listOf(10000L, 20000L), value.days.map { it.totalValue })
    }
}
