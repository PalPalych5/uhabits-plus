/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitCycleStatisticsTest {

    @Test
    fun futureDailyCyclesAreNotReportedAsOngoing() {
        val today = LocalDate(2026, 10, 26)
        val habit = factory.buildHabit().apply {
            uuid = "future-daily"
            frequency = Frequency.DAILY
        }
        val result = HabitCycleStatisticsAggregator.aggregate(
            createQueryContext(from = today, to = today.plus(5)),
            listOf(StatisticsHabitInput(habit, listOf(buildDay(today, Entry.YES_MANUAL))))
        )
        assertEquals(listOf(today), result.ongoingCycles.map { it.startDate })
        assertEquals(1, result.daily.ongoingCount)
        assertTrue(result.allCycles.none { it.startDate > today })
    }

    @Test
    fun malformedBooleanObservationIsUnavailableInsteadOfFailure() {
        val date = LocalDate(2026, 10, 19)
        val result = HabitCycleStatisticsAggregator.aggregate(createQueryContext(from = date, to = date),
            listOf(StatisticsHabitInput(factory.buildHabit(), listOf(buildDay(date, 99)))))
        assertEquals(HabitCycleOutcome.UNAVAILABLE, result.closedCycles.single().outcome)
        assertEquals(0, result.daily.notSatisfiedCount)
        assertTrue(result.issues.any { it.code == StatisticsIssueCode.INVALID_OBSERVATION })
    }

    @Test
    fun fractionalNativeThresholdPreservesTargetInequality() {
        val date = LocalDate(2026, 10, 19)
        for ((type, target, actual) in listOf(
            Triple(NumericalHabitType.AT_LEAST, 1.0004, 1000L),
            Triple(NumericalHabitType.AT_MOST, 1.0006, 1001L),
        )) {
            val habit = factory.buildHabit().apply {
                this.type = org.isoron.uhabits.core.models.HabitType.NUMERICAL
                targetType = type
                targetValue = target
            }
            val result = HabitCycleStatisticsAggregator.aggregate(createQueryContext(from = date, to = date),
                listOf(StatisticsHabitInput(habit, listOf(buildDay(date, actual.toInt(),
                    manualValue = actual, isNumerical = true)))))
            assertEquals(HabitCycleOutcome.NOT_SATISFIED, result.closedCycles.single().outcome)
            assertEquals(1, result.daily.notSatisfiedCount)
            assertEquals(result.closedCycles.map { it.key },
                result.dateBuckets.getValue(HabitCadence.DAILY).flatMap { it.cycles }.map { it.key })
        }
    }

    private val factory = MemoryModelFactory()

    private fun createQueryContext(
        from: LocalDate = LocalDate(2026, 10, 19),
        to: LocalDate = LocalDate(2026, 10, 25),
        timezoneId: String = "Asia/Novosibirsk",
        firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
        now: Long = 1793019600000L, // 2026-10-26 09:00:00 UTC+7 (habitToday = 2026-10-26)
    ): StatisticsQueryContext {
        return StatisticsQueryContext(
            from = from,
            to = to,
            timezoneId = timezoneId,
            firstWeekday = firstWeekday,
            habitHourOffset = 0,
            habitMinuteOffset = 0,
            datasetId = "test-dataset",
            revision = 1L,
            now = now,
        )
    }

    private fun buildDay(
        date: LocalDate,
        value: Int,
        manualValue: Long? = null,
        contributionValue: Long = 0L,
        isNumerical: Boolean = false,
        includedInStatistics: Boolean = true,
        tombstonedContributionValue: Long = 0L,
    ): EffectiveHabitDay {
        return EffectiveHabitDay(
            manualEntry = Entry(date, value),
            manualValue = manualValue,
            contributionValue = contributionValue,
            totalValue = if (manualValue != null || contributionValue > 0) (manualValue ?: 0L) + contributionValue else null,
            contributions = emptyList(),
            isNumerical = isNumerical,
            includedInStatistics = includedInStatistics,
            tombstonedContributions = emptyList(),
            tombstonedContributionValue = tombstonedContributionValue,
        )
    }

    @Test
    fun syntheticFixture_PR14Section8() {
        val context = createQueryContext() // 19-25 Oct 2026, habitToday = 2026-10-26

        // 1. "Вода", daily: [S, S, F, S, U, F, S]
        val habitWater = factory.buildHabit().apply {
            uuid = "water-uuid"
            name = "Вода"
            frequency = Frequency.DAILY
        }
        val waterValues = listOf(
            Entry.YES_MANUAL, // 19 (S)
            Entry.YES_MANUAL, // 20 (S)
            Entry.NO,         // 21 (F)
            Entry.YES_MANUAL, // 22 (S)
            Entry.UNKNOWN,    // 23 (U)
            Entry.NO,         // 24 (F)
            Entry.YES_MANUAL, // 25 (S)
        )
        val waterDays = waterValues.mapIndexed { idx, v ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), v)
        }

        // 2. "Учёба", weekly 19-25 Oct: 1S (target 1/7)
        val habitStudy = factory.buildHabit().apply {
            uuid = "study-uuid"
            name = "Учёба"
            frequency = Frequency.WEEKLY
        }
        val studyDays = (0..6).map { idx ->
            val v = if (idx == 1) Entry.YES_MANUAL else Entry.NO
            buildDay(LocalDate(2026, 10, 19).plus(idx), v)
        }

        // 3. "Чтение", monthly 1-31 Oct: numerical target 300 pages, >=200 by 25 Oct
        val habitReading = factory.buildHabit().apply {
            uuid = "reading-uuid"
            name = "Чтение"
            type = HabitType.NUMERICAL
            frequency = Frequency(1, 30)
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 300.0
            unit = "pages"
        }
        val readingDays = (1..25).map { d ->
            val pageAmount = if (d <= 20) 10_000L else 0L // 20 days * 10 = 200 pages
            buildDay(
                date = LocalDate(2026, 10, d),
                value = 0,
                manualValue = pageAmount,
                isNumerical = true,
            )
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(
                StatisticsHabitInput(habitWater, waterDays),
                StatisticsHabitInput(habitStudy, studyDays),
                StatisticsHabitInput(habitReading, readingDays),
            )
        )

        // Verify Daily: 4S + 2F + 1U, known = 6, eligible = 7
        assertEquals(4, result.daily.satisfiedCount)
        assertEquals(2, result.daily.notSatisfiedCount)
        assertEquals(1, result.daily.unknownCount)
        assertEquals(6, result.daily.knownCount)
        assertEquals(7, result.daily.eligibleCount)
        assertEquals(4.0 / 6.0, result.habitSummaries.single { it.habitUuid == "water-uuid" }.successRate)
        assertEquals(6.0 / 7.0, result.daily.coverageRate)

        // Verify Weekly: 1S
        assertEquals(1, result.weekly.satisfiedCount)
        assertEquals(0, result.weekly.notSatisfiedCount)
        assertEquals(0, result.weekly.unknownCount)
        assertEquals(1.0, result.habitSummaries.single { it.habitUuid == "study-uuid" }.successRate)

        // Verify Monthly: 0 closed cycles in period (it ends 31 Oct), 1 ongoing cycle
        assertEquals(0, result.monthly.satisfiedCount)
        assertEquals(0, result.monthly.notSatisfiedCount)
        assertEquals(0, result.monthly.unknownCount)
        assertNull(result.habitSummaries.single { it.habitUuid == "reading-uuid" }.successRate)
        assertEquals(1, result.monthly.ongoingCount)

        // Ongoing cycles contains Reading truncated to 25 Oct
        assertEquals(1, result.ongoingCycles.size)
        val readingOngoing = result.ongoingCycles.single()
        assertEquals("reading-uuid", readingOngoing.habitUuid)
        assertEquals(HabitCadence.MONTHLY, readingOngoing.cadence)
        assertEquals(HabitCycleLifecycle.ONGOING, readingOngoing.lifecycle)
        assertEquals(200_000L, readingOngoing.quantity.lowerBound)
        assertEquals(LocalDate(2026, 10, 25), readingOngoing.contextTruncatedTo)

        // Verify date buckets for daily
        assertEquals(7, result.dateBuckets[HabitCadence.DAILY]?.size)
        val day19Bucket = result.dateBuckets[HabitCadence.DAILY]?.first()
        assertEquals(1, day19Bucket?.satisfiedCount)

        // Verify date buckets for weekly: only Oct 25 has a cycle
        val weeklyBuckets = result.dateBuckets[HabitCadence.WEEKLY]!!
        assertEquals(1, weeklyBuckets.size)
        assertEquals(LocalDate(2026, 10, 25), weeklyBuckets.single().date)
        assertTrue(result.dateBuckets[HabitCadence.MONTHLY]!!.isEmpty())
        for (bucket in weeklyBuckets) {
            if (bucket.date == LocalDate(2026, 10, 25)) {
                assertEquals(1, bucket.satisfiedCount)
            } else {
                assertEquals(0, bucket.satisfiedCount)
            }
        }
    }

    @Test
    fun boolean_rawManualOnly_yesAutoAndNoDistinction() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency(3, 7) // 3 times a week
        }

        // 1 YES_MANUAL, 1 YES_AUTO, 4 NO, 1 UNKNOWN
        // Total actions L = 1 (YES_AUTO is not an action)
        // u = 1 (UNKNOWN)
        // Upper bound U = L + u = 2 < 3 (proves failure even with unknown!)
        val days = listOf(
            buildDay(LocalDate(2026, 10, 19), Entry.YES_MANUAL),
            buildDay(LocalDate(2026, 10, 20), Entry.YES_AUTO),
            buildDay(LocalDate(2026, 10, 21), Entry.NO),
            buildDay(LocalDate(2026, 10, 22), Entry.NO),
            buildDay(LocalDate(2026, 10, 23), Entry.NO),
            buildDay(LocalDate(2026, 10, 24), Entry.NO),
            buildDay(LocalDate(2026, 10, 25), Entry.UNKNOWN),
        )

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.weekly.cycles.single()
        assertEquals(1, cycle.quantity.booleanManualCount)
        assertEquals(1, cycle.quantity.booleanUnknownDays)
        assertEquals(2, cycle.quantity.booleanUpperBound)
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, cycle.outcome)
        assertEquals(1, result.weekly.notSatisfiedCount)
    }

    @Test
    fun boolean_unknownDays_canReachGoal_isUnknown() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency(3, 7)
        }

        // 1 YES_MANUAL, 2 UNKNOWN, 4 NO -> U = 1 + 2 = 3 >= 3
        val days = listOf(
            buildDay(LocalDate(2026, 10, 19), Entry.YES_MANUAL),
            buildDay(LocalDate(2026, 10, 20), Entry.UNKNOWN),
            buildDay(LocalDate(2026, 10, 21), Entry.UNKNOWN),
            buildDay(LocalDate(2026, 10, 22), Entry.NO),
            buildDay(LocalDate(2026, 10, 23), Entry.NO),
            buildDay(LocalDate(2026, 10, 24), Entry.NO),
            buildDay(LocalDate(2026, 10, 25), Entry.NO),
        )

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.weekly.cycles.single()
        assertEquals(HabitCycleOutcome.UNKNOWN, cycle.outcome)
        assertEquals(1, result.weekly.unknownCount)
    }

    @Test
    fun skip_allDaysSkip_exempt() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency.WEEKLY
        }
        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), Entry.SKIP)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.weekly.cycles.single()
        assertEquals(HabitCycleOutcome.EXEMPT, cycle.outcome)
        assertEquals(1, result.weekly.exemptCount)
        assertEquals(0, result.weekly.satisfiedCount)
        assertEquals(0, result.weekly.notSatisfiedCount)
    }

    @Test
    fun skip_quotaCapacityExcluded() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency(3, 7) // T = 3
        }
        // 5 SKIP, 2 NO -> capacity = 2 < 3
        val days = listOf(
            buildDay(LocalDate(2026, 10, 19), Entry.SKIP),
            buildDay(LocalDate(2026, 10, 20), Entry.SKIP),
            buildDay(LocalDate(2026, 10, 21), Entry.SKIP),
            buildDay(LocalDate(2026, 10, 22), Entry.SKIP),
            buildDay(LocalDate(2026, 10, 23), Entry.SKIP),
            buildDay(LocalDate(2026, 10, 24), Entry.NO),
            buildDay(LocalDate(2026, 10, 25), Entry.NO),
        )

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.weekly.cycles.single()
        assertEquals(2, cycle.quantity.booleanCapacity)
        assertEquals(HabitCycleOutcome.CAPACITY_EXCLUDED, cycle.outcome)
        assertEquals(1, result.weekly.capacityExcludedCount)
        assertEquals(0, result.weekly.notSatisfiedCount)
    }

    @Test
    fun numerical_lowerBound_achievedWithIncompleteCoverage() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 50.0 // 50,000L
            unit = "min"
        }
        // Day 1: manual 30,000L
        // Day 2: contribution 25,000L, manual UNKNOWN (null)
        // Day 3..7: manual 0L
        // L = 55,000L >= 50,000L -> SATISFIED even with incomplete coverage!
        val days = (0..6).map { idx ->
            val date = LocalDate(2026, 10, 19).plus(idx)
            when (idx) {
                0 -> buildDay(date, 0, manualValue = 30_000L, isNumerical = true)
                1 -> buildDay(date, Entry.UNKNOWN, manualValue = null, contributionValue = 25_000L, isNumerical = true)
                else -> buildDay(date, 0, manualValue = 0L, isNumerical = true)
            }
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.weekly.cycles.single()
        assertEquals(55_000L, cycle.quantity.lowerBound)
        assertEquals(1, cycle.quantity.unknownDays)
        assertFalse(cycle.quantity.hasCompleteCoverage)
        assertEquals(HabitCycleOutcome.SATISFIED, cycle.outcome)
        assertEquals(1, result.weekly.satisfiedCount)
    }

    @Test
    fun numerical_atMost_limitExceeded_evenWithUnknown() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_MOST
            targetValue = 10.0 // 10,000L
            unit = "cups"
        }
        // Day 1: manual 15,000L
        // Day 2..7: UNKNOWN
        // L = 15,000L > 10,000L -> NOT_SATISFIED (limit exceeded) even with unknown!
        val days = (0..6).map { idx ->
            val date = LocalDate(2026, 10, 19).plus(idx)
            if (idx == 0) buildDay(date, 0, manualValue = 15_000L, isNumerical = true)
            else buildDay(date, Entry.UNKNOWN, manualValue = null, isNumerical = true)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.weekly.cycles.single()
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, cycle.outcome)
        assertEquals(1, result.weekly.notSatisfiedCount)
    }

    @Test
    fun numerical_atMost_withinLimit_coverageDependent() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_MOST
            targetValue = 10.0
            unit = "cups"
        }

        // Case A: L = 5,000L with complete coverage -> SATISFIED
        val completeDays = (0..6).map { idx ->
            val date = LocalDate(2026, 10, 19).plus(idx)
            val v = if (idx == 0) 5_000L else 0L
            buildDay(date, 0, manualValue = v, isNumerical = true)
        }
        val resultA = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, completeDays))
        )
        assertEquals(HabitCycleOutcome.SATISFIED, resultA.weekly.cycles.single().outcome)

        // Case B: L = 5,000L with unknown days -> UNKNOWN
        val incompleteDays = (0..6).map { idx ->
            val date = LocalDate(2026, 10, 19).plus(idx)
            if (idx == 0) buildDay(date, 0, manualValue = 5_000L, isNumerical = true)
            else buildDay(date, Entry.UNKNOWN, manualValue = null, isNumerical = true)
        }
        val resultB = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, incompleteDays))
        )
        assertEquals(HabitCycleOutcome.UNKNOWN, resultB.weekly.cycles.single().outcome)
    }

    @Test
    fun zeroT_noDivision() {
        val context = createQueryContext()

        // Numerical AT_LEAST T = 0.0 -> SATISFIED
        val habitAtLeastZero = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 0.0
            unit = "km"
        }
        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), 0, manualValue = 0L, isNumerical = true)
        }
        val result1 = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habitAtLeastZero, days))
        )
        assertEquals(HabitCycleOutcome.SATISFIED, result1.weekly.cycles.single().outcome)

        // Numerical AT_MOST T = 0.0, L = 0L, complete -> SATISFIED
        val habitAtMostZero = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_MOST
            targetValue = 0.0
            unit = "km"
        }
        val result2 = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habitAtMostZero, days))
        )
        assertEquals(HabitCycleOutcome.SATISFIED, result2.weekly.cycles.single().outcome)

        // Numerical AT_MOST T = 0.0, L > 0 -> NOT_SATISFIED
        val daysWithFact = (0..6).map { idx ->
            val date = LocalDate(2026, 10, 19).plus(idx)
            val v = if (idx == 0) 1000L else 0L
            buildDay(date, 0, manualValue = v, isNumerical = true)
        }
        val result3 = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habitAtMostZero, daysWithFact))
        )
        assertEquals(HabitCycleOutcome.NOT_SATISFIED, result3.weekly.cycles.single().outcome)
    }

    @Test
    fun goalChange_midcycle_transition() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency.WEEKLY
            goalHistory = mutableListOf(
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 19),
                    frequency = Frequency(3, 7),
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 3.0,
                    unit = "",
                ),
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 22), // midcycle change!
                    frequency = Frequency(5, 7),
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 5.0,
                    unit = "",
                )
            )
        }
        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), Entry.YES_MANUAL)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.allCycles.single { it.cadence == HabitCadence.WEEKLY }
        assertEquals(HabitCycleLifecycle.TRANSITION, cycle.lifecycle)
        assertEquals(HabitCycleOutcome.UNAVAILABLE, cycle.outcome)
        assertEquals(1, result.weekly.transitionCount)
        assertEquals(0, result.weekly.satisfiedCount)
    }

    @Test
    fun initialCutoff_observationStart_partial() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency.WEEKLY
            statisticsStartDate = LocalDate(2026, 10, 21) // cutoff inside week 19-25 Oct
        }
        val days = (0..6).map { idx ->
            val date = LocalDate(2026, 10, 19).plus(idx)
            buildDay(date, Entry.YES_MANUAL)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.allCycles.single { it.cadence == HabitCadence.WEEKLY }
        assertEquals(HabitCycleLifecycle.PARTIAL, cycle.lifecycle)
        assertEquals(HabitCycleOutcome.UNAVAILABLE, cycle.outcome)
        assertEquals(1, result.weekly.partialCount)
        assertEquals(0, result.weekly.satisfiedCount)
    }

    @Test
    fun adjacentPeriods_uniqueness() {
        val p1 = createQueryContext(
            from = LocalDate(2026, 10, 12),
            to = LocalDate(2026, 10, 18),
        )
        val p2 = createQueryContext(
            from = LocalDate(2026, 10, 19),
            to = LocalDate(2026, 10, 25),
        )

        val habit = factory.buildHabit().apply {
            uuid = "unique-test-habit"
            frequency = Frequency.WEEKLY
        }

        val daysP1 = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 12).plus(idx), Entry.YES_MANUAL)
        }
        val daysP2 = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), Entry.YES_MANUAL)
        }

        val result1 = HabitCycleStatisticsAggregator.aggregate(p1, listOf(StatisticsHabitInput(habit, daysP1)))
        val result2 = HabitCycleStatisticsAggregator.aggregate(p2, listOf(StatisticsHabitInput(habit, daysP2)))

        val key1 = result1.weekly.cycles.single().key
        val key2 = result2.weekly.cycles.single().key

        assertEquals(LocalDate(2026, 10, 12), key1.startDate)
        assertEquals(LocalDate(2026, 10, 18), key1.endDate)

        assertEquals(LocalDate(2026, 10, 19), key2.startDate)
        assertEquals(LocalDate(2026, 10, 25), key2.endDate)

        assertTrue(key1 != key2, "Adjacent periods must have distinct cycle keys")
    }

    @Test
    fun endedMonth_cohort() {
        val context = createQueryContext(
            from = LocalDate(2026, 10, 28),
            to = LocalDate(2026, 11, 5),
            now = 1793500000000L, // after 31 Oct
        )
        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency(1, 30)
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            unit = "pages"
        }
        val days = (1..31).map { d ->
            buildDay(LocalDate(2026, 10, d), 0, manualValue = 5000L, isNumerical = true)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        // October ended on 31 Oct, which is within [28 Oct, 5 Nov]!
        assertEquals(1, result.monthly.satisfiedCount)
        val cycle = result.monthly.cycles.single()
        assertEquals(LocalDate(2026, 10, 1), cycle.startDate)
        assertEquals(LocalDate(2026, 10, 31), cycle.endDate)
    }

    @Test
    fun weekFirstDay_sensitivity() {
        // Test with firstWeekday = SUNDAY
        val contextSunday = createQueryContext(
            from = LocalDate(2026, 10, 18), // Sunday
            to = LocalDate(2026, 10, 24),   // Saturday
            firstWeekday = DayOfWeek.SUNDAY,
        )
        val habit = factory.buildHabit().apply {
            frequency = Frequency.WEEKLY
        }
        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 18).plus(idx), Entry.YES_MANUAL)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            contextSunday,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.weekly.cycles.single()
        assertEquals(LocalDate(2026, 10, 18), cycle.startDate) // Sunday
        assertEquals(LocalDate(2026, 10, 24), cycle.endDate)   // Saturday
    }

    @Test
    fun unsupportedFrequency_retained() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency(3, 10) // denominator 10 is unsupported
        }
        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), Entry.YES_MANUAL)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        assertEquals(HabitCadence.UNSUPPORTED, result.unsupported.cadence)
        assertTrue(result.unsupported.unsupportedCount > 0)
        assertTrue(result.issues.any { it.code == StatisticsIssueCode.UNSUPPORTED_FREQUENCY })
    }

    @Test
    fun invalidGoal_and_incompatibleUnit() {
        val context = createQueryContext()

        // Negative target
        val habitNegative = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = -5.0
            unit = "km"
        }
        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), 0, manualValue = 1000L, isNumerical = true)
        }
        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habitNegative, days))
        )
        val cycle = result.allCycles.single { it.habitUuid == habitNegative.uuid }
        assertEquals(HabitCycleOutcome.UNAVAILABLE, cycle.outcome)
        assertTrue(cycle.issues.any { it.code == StatisticsIssueCode.INVALID_GOAL })
    }

    @Test
    fun noMutableCalls_pureNormalization() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            frequency = Frequency.DAILY
            goalHistory = mutableListOf() // empty
        }

        val days = listOf(buildDay(LocalDate(2026, 10, 19), Entry.YES_MANUAL))

        // Aggregate
        HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        // Must NOT mutate input habit's goalHistory
        assertTrue(habit.goalHistory.isEmpty(), "Input Habit goalHistory must not be mutated")
    }

    @Test
    fun readerSeam_projectionIntegrityIssueForOneHabit_retainedUnavailableAndPreservesOtherHabits() {
        val context = createQueryContext()

        // Valid habit
        val validHabit = factory.buildHabit().apply {
            uuid = "valid-habit"
            frequency = Frequency.WEEKLY
        }
        val validDays = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), Entry.YES_MANUAL)
        }

        // Broken habit with projection-integrity issue and empty days
        val brokenHabit = factory.buildHabit().apply {
            uuid = "broken-habit"
            frequency = Frequency.WEEKLY
        }
        val brokenIssue = StatisticsIssue(
            code = StatisticsIssueCode.INCOMPATIBLE_UNIT,
            entityId = "broken-habit",
            message = "Projection integrity failure: cannot aggregate different historical units",
        )

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(
                StatisticsHabitInput(validHabit, validDays),
                StatisticsHabitInput(brokenHabit, emptyList(), issues = listOf(brokenIssue)),
            )
        )

        // Valid habit is counted as satisfied
        assertEquals(1, result.weekly.satisfiedCount)

        // Broken habit is retained as UNAVAILABLE, not dropped and not falsely UNKNOWN
        assertEquals(1, result.weekly.unavailableCount)
        assertTrue(result.issues.any { it.entityId == "broken-habit" && it.code == StatisticsIssueCode.INCOMPATIBLE_UNIT })
        val brokenCycle = result.allCycles.single { it.habitUuid == "broken-habit" }
        assertEquals(HabitCycleOutcome.UNAVAILABLE, brokenCycle.outcome)
        assertEquals(listOf(brokenIssue), brokenCycle.issues)
    }

    @Test
    fun frequencyTransition_weeklyToDaily_generatesBothCandidateWindows_weeklyTransitionExcluded_dailyClosed() {
        val context = createQueryContext(
            from = LocalDate(2026, 10, 19),
            to = LocalDate(2026, 10, 25),
        )
        val habit = factory.buildHabit().apply {
            frequency = Frequency.DAILY
            goalHistory = mutableListOf(
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 1),
                    frequency = Frequency(3, 7), // Weekly until Oct 21
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 3.0,
                    unit = "",
                ),
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 22), // Daily starting Oct 22
                    frequency = Frequency.DAILY,
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 1.0,
                    unit = "",
                )
            )
        }
        val days = (0..6).map { idx ->
            val date = LocalDate(2026, 10, 19).plus(idx)
            val v = if (idx >= 3) Entry.YES_MANUAL else Entry.NO
            buildDay(date, v)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        // Weekly cycle (Oct 19..25) intersects frequency transition on Oct 22 -> TRANSITION (excluded from closed)
        assertEquals(1, result.weekly.transitionCount)
        assertEquals(0, result.weekly.satisfiedCount)
        assertEquals(0, result.weekly.notSatisfiedCount)

        // Candidate daily cycles under new goal for Oct 22, 23, 24, 25 are generated and closed
        assertEquals(4, result.daily.satisfiedCount)
        assertEquals(0, result.daily.notSatisfiedCount)
        assertEquals(4, result.daily.knownCount)
    }

    @Test
    fun frequencyTransition_monthlyToWeekly_generatesBothCandidateWindows_monthlyAndIntersectingWeekTransition_subsequentWeekClosed() {
        // Query covers entire month: 1 Oct .. 31 Oct 2026, habitToday = 26 Oct 2026
        val context = createQueryContext(
            from = LocalDate(2026, 10, 1),
            to = LocalDate(2026, 10, 31),
            now = 1793019600000L, // 2026-10-26 09:00:00 (habitToday = 2026-10-26)
        )
        val habit = factory.buildHabit().apply {
            frequency = Frequency.WEEKLY
            goalHistory = mutableListOf(
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 1),
                    frequency = Frequency(1, 30), // Monthly until 15 Oct
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 1.0,
                    unit = "",
                ),
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 16), // Weekly starting 16 Oct
                    frequency = Frequency.WEEKLY,
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 1.0,
                    unit = "",
                )
            )
        }
        val days = (1..31).map { d ->
            val date = LocalDate(2026, 10, d)
            val v = if (d in 19..25) Entry.YES_MANUAL else Entry.NO
            buildDay(date, v)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        // Monthly window (1..31 Oct) has frequency transition midcycle -> TRANSITION
        assertEquals(1, result.monthly.transitionCount)
        assertEquals(0, result.monthly.satisfiedCount)

        // Week 12..18 Oct intersects transition date (16 Oct) -> TRANSITION
        // Week 19..25 Oct is pure weekly under new goal, ends 25 Oct < habitToday (26 Oct) -> CLOSED SATISFIED
        assertEquals(1, result.weekly.transitionCount)
        assertEquals(1, result.weekly.satisfiedCount)
    }

    @Test
    fun explicitCutoff_earlierThanObservationStart_interveningMissingDaysUnknownNotExcluded() {
        val context = createQueryContext() // 19..25 Oct 2026
        val habit = factory.buildHabit().apply {
            uuid = "cutoff-priority-habit"
            frequency = Frequency(6, 7)
            statisticsStartDate = LocalDate(2026, 10, 15) // Explicit cutoff earlier than observations
        }
        // First observation starts on 20 Oct (missing 19 Oct)
        val observationStart = LocalDate(2026, 10, 20)
        val days = (20..25).map { d ->
            buildDay(LocalDate(2026, 10, d), if (d == 25) Entry.NO else Entry.YES_MANUAL)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days, observationStart = observationStart))
        )

        // Week 19..25 Oct has startDate (19 Oct) >= effectiveStatisticsStartDate (15 Oct), so NOT partial.
        // The intervening day (19 Oct) is UNKNOWN, not excluded.
        // With 6 YES_MANUAL and 1 UNKNOWN, upper bound is 7/7, so outcome is UNKNOWN.
        val cycle = result.weekly.cycles.single()
        assertEquals(HabitCycleLifecycle.CLOSED, cycle.lifecycle)
        assertEquals(HabitCycleOutcome.UNKNOWN, cycle.outcome)
        assertEquals(5, cycle.quantity.booleanManualCount)
        assertEquals(1, cycle.quantity.booleanUnknownDays)
        assertEquals(6, cycle.quantity.booleanUpperBound)
        assertEquals(1, result.weekly.unknownCount)
        assertEquals(0, result.weekly.satisfiedCount)
    }

    @Test
    fun whollyBeforeSelectedCutoff_explicitlyExcluded_partialUnavailable() {
        // Query covers 5 Oct .. 25 Oct 2026
        val context = createQueryContext(
            from = LocalDate(2026, 10, 5),
            to = LocalDate(2026, 10, 25),
        )
        val habit = factory.buildHabit().apply {
            frequency = Frequency.WEEKLY
            statisticsStartDate = LocalDate(2026, 10, 20) // cutoff is 20 Oct
        }
        val days = (5..25).map { d ->
            buildDay(LocalDate(2026, 10, d), Entry.YES_MANUAL)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        // Weeks:
        // 5..11 Oct: startDate < cutoff (wholly before) -> PARTIAL
        // 12..18 Oct: startDate < cutoff (wholly before) -> PARTIAL
        // 19..25 Oct: startDate < cutoff (intersects cutoff) -> PARTIAL
        // All cycles starting before cutoff are PARTIAL and UNAVAILABLE, never S/F/U!
        assertEquals(0, result.weekly.satisfiedCount)
        assertEquals(0, result.weekly.notSatisfiedCount)
        assertEquals(0, result.weekly.unknownCount)
        assertEquals(3, result.weekly.partialCount)
        assertTrue(result.weekly.cycles.isEmpty(), "No cycles should be closed before/across cutoff")
    }

    @Test
    fun numerical_overflowInLongSums_yieldsOverflowIssueAndUnavailable() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            unit = "ml"
        }
        // Manual value near Long.MAX_VALUE
        val days = listOf(
            buildDay(LocalDate(2026, 10, 19), 0, manualValue = Long.MAX_VALUE - 10L, isNumerical = true),
            buildDay(LocalDate(2026, 10, 20), 0, manualValue = 50L, isNumerical = true),
        )

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.allCycles.single { it.cadence == HabitCadence.WEEKLY }
        assertEquals(HabitCycleOutcome.UNAVAILABLE, cycle.outcome)
        assertTrue(cycle.issues.any { it.code == StatisticsIssueCode.OVERFLOW })
        assertEquals(1, result.weekly.unavailableCount)
    }

    @Test
    fun numerical_unrepresentableScaledTarget_yieldsInvalidGoalNotRoundToLongSaturation() {
        val context = createQueryContext()
        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency.WEEKLY
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 1e19 // unrepresentable in Long when multiplied by 1000
            unit = "steps"
        }
        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), 0, manualValue = 10_000L, isNumerical = true)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val cycle = result.allCycles.single { it.cadence == HabitCadence.WEEKLY }
        assertEquals(HabitCycleOutcome.UNAVAILABLE, cycle.outcome)
        assertNull(cycle.quantity.targetValueNative, "Unrepresentable target must not saturate to Long.MAX_VALUE")
        assertTrue(cycle.issues.any { it.code == StatisticsIssueCode.INVALID_GOAL })
    }

    @Test
    fun summaries_preserveExcludedOnlyAndUnsupportedHabits_withCorrectPartialAndTransitionCounts() {
        val context = createQueryContext()

        // Habit 1: Excluded-only (cutoff creates a PARTIAL cycle)
        val habitPartial = factory.buildHabit().apply {
            uuid = "partial-habit"
            name = "Partial Only"
            frequency = Frequency.WEEKLY
            statisticsStartDate = LocalDate(2026, 10, 23) // cutoff inside week
        }

        // Habit 2: Transition-only (midcycle frequency edit)
        val habitTransition = factory.buildHabit().apply {
            uuid = "transition-habit"
            name = "Transition Only"
            frequency = Frequency.WEEKLY
            goalHistory = mutableListOf(
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 1),
                    frequency = Frequency.WEEKLY,
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 1.0,
                    unit = "",
                ),
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 22),
                    frequency = Frequency(5, 7),
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 5.0,
                    unit = "",
                )
            )
        }

        // Habit 3: Unsupported frequency
        val habitUnsupported = factory.buildHabit().apply {
            uuid = "unsupported-habit"
            name = "Unsupported Only"
            frequency = Frequency(3, 11) // denominator 11
        }

        val days = (0..6).map { idx ->
            buildDay(LocalDate(2026, 10, 19).plus(idx), Entry.YES_MANUAL)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(
                StatisticsHabitInput(habitPartial, days),
                StatisticsHabitInput(habitTransition, days),
                StatisticsHabitInput(habitUnsupported, days),
            )
        )

        val summaries = result.habitSummaries.associateBy { it.habitUuid }
        assertEquals(3, summaries.size, "All 3 habits must be preserved in habitSummaries")

        val summaryPartial = summaries["partial-habit"]!!
        assertEquals(1, summaryPartial.partialCount)
        assertEquals(0, summaryPartial.satisfiedCount)

        val summaryTransition = summaries["transition-habit"]!!
        assertEquals(1, summaryTransition.transitionCount)
        assertEquals(0, summaryTransition.satisfiedCount)

        val summaryUnsupported = summaries["unsupported-habit"]!!
        assertEquals(HabitCadence.UNSUPPORTED, summaryUnsupported.cadence)
    }

    @Test
    fun contextualCycle_truncationAtMinOfToAndHabitToday_noFutureLeak() {
        // Query covers 19..25 Oct 2026, habitToday is 26 Oct
        val context = createQueryContext(
            from = LocalDate(2026, 10, 19),
            to = LocalDate(2026, 10, 25),
            now = 1793019600000L, // 2026-10-26 09:00:00 (habitToday = 2026-10-26)
        )
        val habit = factory.buildHabit().apply {
            type = HabitType.NUMERICAL
            frequency = Frequency(1, 30) // monthly cycle 1..31 Oct
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            unit = "pages"
        }
        // Input has days up to 30 Oct (future facts past query end!)
        val days = (1..30).map { d ->
            buildDay(LocalDate(2026, 10, d), 0, manualValue = 10_000L, isNumerical = true)
        }

        val result = HabitCycleStatisticsAggregator.aggregate(
            context,
            listOf(StatisticsHabitInput(habit, days))
        )

        val monthlyOngoing = result.ongoingCycles.single { it.cadence == HabitCadence.MONTHLY }
        assertEquals(LocalDate(2026, 10, 25), monthlyOngoing.contextTruncatedTo)
        // Days must be truncated to 25 Oct (25 days), NOT 30 days!
        assertEquals(25, monthlyOngoing.days.size)
        assertTrue(monthlyOngoing.days.all { it.manualEntry.date <= LocalDate(2026, 10, 25) })
        // lowerBound is 25 * 10,000L = 250,000L, NOT 300,000L!
        assertEquals(250_000L, monthlyOngoing.quantity.lowerBound)
    }
}
