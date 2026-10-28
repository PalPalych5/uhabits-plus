/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.statistics.FocusDailyBucket
import org.isoron.uhabits.core.statistics.FocusStatisticsResult
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCadenceAggregate
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleDateBucket
import org.isoron.uhabits.core.statistics.HabitCycleKey
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.HabitCycleQuantity
import org.isoron.uhabits.core.statistics.HabitCycleStatisticsResult
import org.isoron.uhabits.core.statistics.HabitSummary
import org.isoron.uhabits.core.statistics.StatisticsDataState
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.statistics.TaskDailyBucket
import org.isoron.uhabits.core.statistics.TaskDueCohortSummary
import org.isoron.uhabits.core.statistics.TaskNoDueCohortSummary
import org.isoron.uhabits.core.statistics.TaskStatisticsResult
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PR14.4 Habits C1-C7 models, bucketing, drill-down resolution,
 * and Overview dynamics charts.
 */
class UnifiedStatisticsHabitsOverviewTest {

    private val startDate = LocalDate(2026, 10, 19)
    private val endDate = LocalDate(2026, 10, 25)
    private val today = LocalDate(2026, 10, 26)

    private val queryContext = StatisticsQueryContext(
        from = startDate,
        to = endDate,
        timezoneId = "UTC",
        firstWeekday = DayOfWeek.MONDAY,
        habitHourOffset = 0,
        habitMinuteOffset = 0,
        datasetId = "test_dataset",
        revision = 1L,
        now = 1_800_000_000_000L,
        sectionScope = StatisticsSectionScope.All
    )

    @Test
    fun sheetQuerySignatureChangesWithDatasetAndCalendarContext() {
        val signature = statisticsQuerySignature(queryContext)
        assertEquals(signature, statisticsQuerySignature(queryContext.copy()))
        assertFalse(signature == statisticsQuerySignature(queryContext.copy(datasetId = "replacement")))
        assertFalse(signature == statisticsQuerySignature(queryContext.copy(firstWeekday = DayOfWeek.SUNDAY)))
        assertFalse(signature == statisticsQuerySignature(queryContext.copy(habitHourOffset = 3)))
        assertFalse(signature == statisticsQuerySignature(queryContext.copy(timezoneId = "Europe/Berlin")))
    }

    @Test
    fun nextDayBoundaryReflectsChangedHabitOffset() {
        val now = java.time.Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
        val base = queryContext.copy(now = now, timezoneId = "UTC", habitHourOffset = 0)
        val shifted = base.copy(habitHourOffset = 3)
        assertEquals(java.time.Instant.parse("2026-10-27T00:00:00Z").toEpochMilli(),
            UnifiedStatisticsStateUtils.nextDayBoundary(base, now))
        assertEquals(java.time.Instant.parse("2026-10-26T03:00:00Z").toEpochMilli(),
            UnifiedStatisticsStateUtils.nextDayBoundary(shifted, now))
    }

    @Test
    fun nextDayBoundaryObservesBothOccurrencesAndClockRollback() {
        fun time(value: String) = java.time.Instant.parse(value).toEpochMilli()
        val base = queryContext.copy(timezoneId = "Europe/Berlin", habitHourOffset = 2, habitMinuteOffset = 30,
            now = time("2026-10-25T00:00:00Z"))
        assertEquals(time("2026-10-25T00:30:00Z"), UnifiedStatisticsStateUtils.nextDayBoundary(base, base.now))
        val afterFirst = base.copy(now = time("2026-10-25T00:45:00Z"))
        assertEquals(time("2026-10-25T01:00:00Z"), UnifiedStatisticsStateUtils.nextDayBoundary(afterFirst, afterFirst.now))
        val afterRollback = base.copy(now = time("2026-10-25T01:00:00Z"))
        assertEquals(time("2026-10-25T01:30:00Z"), UnifiedStatisticsStateUtils.nextDayBoundary(afterRollback, afterRollback.now))
    }

    private val dummyPalette = SettingsThemePalette(
        background = 0xFFFFFFFF.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceVariant = 0xFFF5F5F5.toInt(),
        border = 0xFFBDBDBD.toInt(),
        divider = 0x1F000000,
        groupDivider = 0x1F000000,
        onSurface = 0xFF000000.toInt(),
        onSurfaceVariant = 0xFF757575.toInt(),
        accent = 0xFF2196F3.toInt(),
        isDark = false,
        isPureBlack = false,
    )

    private fun dummyGoal(
        target: Double = 1.0,
        unit: String = "",
        effectiveDate: LocalDate = startDate,
        frequency: Frequency = Frequency.DAILY,
    ) = HabitGoal(
        effectiveDate = effectiveDate,
        frequency = frequency,
        targetType = NumericalHabitType.AT_LEAST,
        targetValue = target,
        unit = unit
    )

    private fun dummyQuantity(isNumerical: Boolean = false) = HabitCycleQuantity(
        isNumerical = isNumerical,
        lowerBound = 1000L,
        manualValue = 1000L,
        activeContributionValue = 0L,
        targetValue = 1.0,
        booleanManualCount = 1,
        totalDays = 7,
        knownDays = 6,
        unknownDays = 1,
        skippedDays = 0,
        hasCompleteCoverage = false
    )

    private fun dummyCycle(
        uuid: String,
        name: String,
        cadence: HabitCadence,
        lifecycle: HabitCycleLifecycle = HabitCycleLifecycle.CLOSED,
        outcome: HabitCycleOutcome = HabitCycleOutcome.SATISFIED,
        cycleStart: LocalDate = startDate,
        cycleEnd: LocalDate = endDate,
    ): HabitCycle {
        val key = HabitCycleKey(uuid, "v1", cycleStart, cycleEnd)
        return HabitCycle(
            key = key,
            habitUuid = uuid,
            habitName = name,
            cadence = cadence,
            lifecycle = lifecycle,
            outcome = outcome,
            goal = dummyGoal(),
            startDate = cycleStart,
            endDate = cycleEnd,
            quantity = dummyQuantity(),
            days = emptyList()
        )
    }

    @Test
    fun historicalOngoingSliceIsDistinctFromCurrentAndPartialCycles() {
        val ongoing = dummyCycle("h", "Reading", HabitCadence.MONTHLY,
            lifecycle = HabitCycleLifecycle.ONGOING).copy(isContextual = true, contextTruncatedTo = endDate)
        assertTrue(isHistoricalCycleSlice(ongoing, queryContext))
        val currentContext = queryContext.copy(to = queryContext.habitToday)
        assertFalse(isHistoricalCycleSlice(ongoing, currentContext))
        assertFalse(isHistoricalCycleSlice(ongoing.copy(lifecycle = HabitCycleLifecycle.PARTIAL), queryContext))
        assertFalse(isHistoricalCycleSlice(ongoing.copy(isContextual = false), queryContext))
    }

    @Test
    fun testThreeCadenceSummaries_remainSeparateWithoutPooledAverage() {
        val dailyCycles = listOf(
            dummyCycle("h1", "Water", HabitCadence.DAILY, outcome = HabitCycleOutcome.SATISFIED, cycleStart = startDate, cycleEnd = startDate),
            dummyCycle("h1", "Water", HabitCadence.DAILY, outcome = HabitCycleOutcome.NOT_SATISFIED, cycleStart = startDate.plus(1), cycleEnd = startDate.plus(1)),
            dummyCycle("h1", "Water", HabitCadence.DAILY, outcome = HabitCycleOutcome.UNKNOWN, cycleStart = startDate.plus(2), cycleEnd = startDate.plus(2))
        )
        val weeklyCycles = listOf(
            dummyCycle("h2", "Study", HabitCadence.WEEKLY, outcome = HabitCycleOutcome.SATISFIED)
        )
        val monthlyCycles = emptyList<HabitCycle>()

        val dailyAgg = HabitCadenceAggregate(
            cadence = HabitCadence.DAILY,
            satisfiedCount = 1,
            notSatisfiedCount = 1,
            unknownCount = 1,
            cycles = dailyCycles
        )
        val weeklyAgg = HabitCadenceAggregate(
            cadence = HabitCadence.WEEKLY,
            satisfiedCount = 1,
            notSatisfiedCount = 0,
            unknownCount = 0,
            cycles = weeklyCycles
        )
        val monthlyAgg = HabitCadenceAggregate(
            cadence = HabitCadence.MONTHLY,
            satisfiedCount = 0,
            notSatisfiedCount = 0,
            unknownCount = 0,
            cycles = monthlyCycles
        )

        // Cadence summaries maintain their own independent known/eligible counts
        assertEquals(2, dailyAgg.knownCount)
        assertEquals(3, dailyAgg.eligibleCount)
        assertEquals(1, weeklyAgg.knownCount)
        assertEquals(1, weeklyAgg.eligibleCount)
        assertEquals(0, monthlyAgg.eligibleCount)

        // No mixed-cadence percentage or pooled rate is claimed
        assertEquals(0.5, dailyAgg.satisfiedCount.toDouble() / dailyAgg.knownCount, 0.001)
        assertEquals(1.0, weeklyAgg.satisfiedCount.toDouble() / weeklyAgg.knownCount, 0.001)
        assertNull(monthlyAgg.coverageRate)
    }

    @Test
    fun testAdaptiveHabitColumns_dailyBuildsDayColumnsWithSFUSegments() {
        val date1 = startDate
        val date2 = startDate.plus(1)
        val dateBuckets = mapOf(
            HabitCadence.DAILY to listOf(
                HabitCycleDateBucket(
                    date = date1,
                    cadence = HabitCadence.DAILY,
                    satisfiedCount = 4,
                    notSatisfiedCount = 2,
                    unknownCount = 1,
                    cycles = listOf(dummyCycle("h1", "Water", HabitCadence.DAILY, cycleStart = date1, cycleEnd = date1))
                ),
                HabitCycleDateBucket(
                    date = date2,
                    cadence = HabitCadence.DAILY,
                    satisfiedCount = 1,
                    notSatisfiedCount = 0,
                    unknownCount = 0,
                    cycles = listOf(dummyCycle("h1", "Water", HabitCadence.DAILY, cycleStart = date2, cycleEnd = date2))
                )
            )
        )

        val habitResult = HabitCycleStatisticsResult(
            context = queryContext,
            dataState = StatisticsDataState.COMPLETE,
            daily = HabitCadenceAggregate(HabitCadence.DAILY),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = emptyList(),
            allCycles = emptyList(),
            habitSummaries = emptyList(),
            dateBuckets = dateBuckets
        )

        val columns = DiscreteChartBucketer.buildHabitColumns(
            UnifiedPeriod.WEEK, HabitCadence.DAILY, habitResult, today, dummyPalette
        )

        assertEquals(2, columns.size)
        val col1 = columns[0]
        assertEquals(7L, col1.value) // 4 + 2 + 1
        assertEquals("7", col1.formattedValue)
        assertEquals(3, col1.segments.size)
        assertEquals(4L, col1.segments[0].value)
        assertEquals(2L, col1.segments[1].value)
        assertEquals(1L, col1.segments[2].value)

        val col2 = columns[1]
        assertEquals(1L, col2.value)
        assertEquals("S", col2.formattedValue) // Single goal satisfied
        assertEquals(1, col2.segments.size)
    }

    @Test
    fun testAdaptiveHabitColumns_weeklyOnlyHasColumnsOnEndDates() {
        // Weekly cycle ends on Sunday 25 Oct
        val dateBuckets = mapOf(
            HabitCadence.WEEKLY to listOf(
                HabitCycleDateBucket(
                    date = endDate,
                    cadence = HabitCadence.WEEKLY,
                    satisfiedCount = 1,
                    notSatisfiedCount = 0,
                    unknownCount = 0,
                    cycles = listOf(dummyCycle("h2", "Study", HabitCadence.WEEKLY, cycleStart = startDate, cycleEnd = endDate))
                )
            )
        )

        val habitResult = HabitCycleStatisticsResult(
            context = queryContext,
            dataState = StatisticsDataState.COMPLETE,
            daily = HabitCadenceAggregate(HabitCadence.DAILY),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = emptyList(),
            allCycles = emptyList(),
            habitSummaries = emptyList(),
            dateBuckets = dateBuckets
        )

        val columns = DiscreteChartBucketer.buildHabitColumns(
            UnifiedPeriod.WEEK, HabitCadence.WEEKLY, habitResult, today, dummyPalette
        )

        // Only one column exists for the week on endDate 25 Oct
        assertEquals(1, columns.size)
        assertEquals(endDate.toString(), columns[0].key)
        assertEquals(1L, columns[0].value)
        assertEquals("S", columns[0].formattedValue)
    }

    @Test
    fun testAdaptiveHabitColumns_yearCoarsensInto12MonthsSummingRawCounts() {
        val dateOct = LocalDate(2026, 10, 25)
        val dateNov = LocalDate(2026, 11, 15)
        val dateBuckets = mapOf(
            HabitCadence.DAILY to listOf(
                HabitCycleDateBucket(dateOct, HabitCadence.DAILY, 5, 2, 1, emptyList()),
                HabitCycleDateBucket(dateNov, HabitCadence.DAILY, 10, 0, 0, emptyList())
            )
        )

        val habitResult = HabitCycleStatisticsResult(
            context = queryContext,
            dataState = StatisticsDataState.COMPLETE,
            daily = HabitCadenceAggregate(HabitCadence.DAILY),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = emptyList(),
            allCycles = emptyList(),
            habitSummaries = emptyList(),
            dateBuckets = dateBuckets
        )

        val columns = DiscreteChartBucketer.buildHabitColumns(
            UnifiedPeriod.YEAR, HabitCadence.DAILY, habitResult, today, dummyPalette
        )

        assertEquals(12, columns.size) // 12 calendar months
        val octCol = columns[9] // Month 10
        assertEquals(8L, octCol.value) // 5 + 2 + 1
        assertEquals("8", octCol.formattedValue)

        val novCol = columns[10] // Month 11
        assertEquals(10L, novCol.value)
        assertEquals("10", novCol.formattedValue)

        val janCol = columns[0]
        assertEquals(0L, janCol.value)
        assertEquals("", janCol.formattedValue)
    }

    @Test
    fun testAdaptiveHabitColumns_oldYearBucketSelectedContext() {
        val oldYear = 2024
        val oldStartDate = LocalDate(oldYear, 1, 1)
        val oldEndDate = LocalDate(oldYear, 12, 31)
        val oldContext = queryContext.copy(from = oldStartDate, to = oldEndDate)

        val dateOct2024 = LocalDate(oldYear, 10, 25)
        val cycle2024 = dummyCycle("h1", "Water", HabitCadence.DAILY, cycleStart = dateOct2024, cycleEnd = dateOct2024)
        val dateBuckets = mapOf(
            HabitCadence.DAILY to listOf(
                HabitCycleDateBucket(dateOct2024, HabitCadence.DAILY, 5, 2, 1, listOf(cycle2024))
            )
        )
        val habitResult = HabitCycleStatisticsResult(
            context = oldContext,
            dataState = StatisticsDataState.COMPLETE,
            daily = HabitCadenceAggregate(HabitCadence.DAILY),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = listOf(cycle2024),
            ongoingCycles = emptyList(),
            allCycles = listOf(cycle2024),
            habitSummaries = emptyList(),
            dateBuckets = dateBuckets
        )
        val report = UnifiedStatisticsReport(
            context = oldContext,
            habits = habitResult,
            tasks = null,
            focus = null,
            readIssues = emptyList()
        )

        val columns = DiscreteChartBucketer.buildHabitColumns(
            UnifiedPeriod.YEAR, HabitCadence.DAILY, habitResult, today, dummyPalette
        )

        assertEquals(12, columns.size)
        val octCol = columns[9] // October
        assertEquals(8L, octCol.value)

        // Selected context descriptor for an old-year bucket
        val descriptor = octCol.descriptor
        assertTrue("Descriptor must be CadenceRangeCycles", descriptor is HabitsDrilldownDescriptor.CadenceRangeCycles)
        val rangeDescriptor = descriptor as HabitsDrilldownDescriptor.CadenceRangeCycles
        assertEquals(HabitCadence.DAILY, rangeDescriptor.cadence)

        // Old-year bucket selection context contract:
        // Range descriptor bounds must match the report context year (2024), not today.year (2026),
        // ensuring drill-down resolution returns the constituent cycles.
        assertEquals("Descriptor from date year must match report context year", oldYear, rangeDescriptor.from.year)
        assertEquals("Descriptor to date year must match report context year", oldYear, rangeDescriptor.to.year)

        val resolved = DrilldownSelectionResolver.resolveHabitCycles(report, rangeDescriptor)
        assertEquals("Old-year bucket drill-down must resolve constituent cycles", listOf(cycle2024), resolved)
    }

    @Test
    fun testDrilldownSelectionResolver_cadenceAndHabitCycles() {
        val cycle1 = dummyCycle("h1", "Water", HabitCadence.DAILY, cycleStart = startDate, cycleEnd = startDate)
        val cycle2 = dummyCycle("h2", "Study", HabitCadence.WEEKLY, cycleStart = startDate, cycleEnd = endDate)
        val cycleOngoing = dummyCycle("h3", "Reading", HabitCadence.MONTHLY, lifecycle = HabitCycleLifecycle.ONGOING,
            cycleStart = LocalDate(2026, 10, 1), cycleEnd = LocalDate(2026, 10, 31))

        val habitResult = HabitCycleStatisticsResult(
            context = queryContext,
            dataState = StatisticsDataState.COMPLETE,
            daily = HabitCadenceAggregate(HabitCadence.DAILY, cycles = listOf(cycle1)),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY, cycles = listOf(cycle2)),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY, cycles = emptyList()),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = listOf(cycle1, cycle2),
            ongoingCycles = listOf(cycleOngoing),
            allCycles = listOf(cycle1, cycle2, cycleOngoing),
            habitSummaries = listOf(
                HabitSummary("h1", "Water", HabitCadence.DAILY, 1, 0, 0, 0, 0, 0, 0, listOf(cycle1)),
                HabitSummary("h1", "Water", HabitCadence.WEEKLY, 0, 1, 0, 0, 0, 0, 0, emptyList()),
                HabitSummary("h2", "Study", HabitCadence.WEEKLY, 1, 0, 0, 0, 0, 0, 0, listOf(cycle2)),
                HabitSummary("h3", "Reading", HabitCadence.MONTHLY, 0, 0, 0, 0, 0, 0, 0, emptyList())
            ),
            dateBuckets = mapOf(
                HabitCadence.DAILY to listOf(HabitCycleDateBucket(startDate, HabitCadence.DAILY, 1, 0, 0, listOf(cycle1))),
                HabitCadence.WEEKLY to listOf(HabitCycleDateBucket(endDate, HabitCadence.WEEKLY, 1, 0, 0, listOf(cycle2)))
            )
        )

        val report = UnifiedStatisticsReport(
            context = queryContext,
            habits = habitResult,
            tasks = null,
            focus = null,
            readIssues = emptyList()
        )

        // 1. Cadence cycles
        val dailyCycles = DrilldownSelectionResolver.resolveHabitCycles(report, HabitsDrilldownDescriptor.CadenceCycles(HabitCadence.DAILY))
        assertEquals(listOf(cycle1), dailyCycles)

        val weeklyCycles = DrilldownSelectionResolver.resolveHabitCycles(report, HabitsDrilldownDescriptor.CadenceCycles(HabitCadence.WEEKLY))
        assertEquals(listOf(cycle2), weeklyCycles)

        // 2. Bucket cycles
        val bucketCycles = DrilldownSelectionResolver.resolveHabitCycles(report, HabitsDrilldownDescriptor.BucketCycles(startDate, HabitCadence.DAILY, ""))
        assertEquals(listOf(cycle1), bucketCycles)

        // Exempt and unavailable cycles have no S/F/U segment and must not be
        // returned by the chart's drill-down, even if they share its date.
        val excluded = listOf(HabitCycleOutcome.EXEMPT, HabitCycleOutcome.CAPACITY_EXCLUDED, HabitCycleOutcome.UNAVAILABLE)
            .mapIndexed { i, outcome -> cycle1.copy(key = cycle1.key.copy(habitUuid = "excluded-$i"), outcome = outcome) }
        val mixedBucket = HabitCycleDateBucket(startDate, HabitCadence.DAILY, 1, 0, 0, listOf(cycle1) + excluded)
        val mixedResult = habitResult.copy(dateBuckets = mapOf(HabitCadence.DAILY to listOf(mixedBucket)))
        val mixedReport = report.copy(habits = mixedResult)
        val chart = DiscreteChartBucketer.buildHabitColumns(UnifiedPeriod.WEEK, HabitCadence.DAILY, mixedResult, today, dummyPalette).single()
        assertEquals(1L, chart.value)
        assertEquals(listOf(cycle1), DrilldownSelectionResolver.resolveHabitCycles(mixedReport, chart.descriptor as HabitsDrilldownDescriptor))
        assertEquals(listOf(cycle1), DrilldownSelectionResolver.resolveHabitCycles(mixedReport,
            HabitsDrilldownDescriptor.CadenceRangeCycles(HabitCadence.DAILY, startDate, endDate, "")))

        // 3. Ongoing cycles
        val ongoing = DrilldownSelectionResolver.resolveHabitCycles(report, HabitsDrilldownDescriptor.OngoingCycles)
        assertEquals(listOf(cycleOngoing), ongoing)

        // 4. Habit cycles by UUID
        val waterCycles = DrilldownSelectionResolver.resolveHabitCycles(report, HabitsDrilldownDescriptor.HabitCycles("h1"))
        assertEquals(listOf(cycle1), waterCycles)

        // 5. Alphabetical habit summaries
        val allSummaries = DrilldownSelectionResolver.resolveHabitSummaries(report, "")
        assertEquals(listOf("Reading", "Study", "Water"), allSummaries.map { it.habitName })

        // 6. Search filter on habit summaries
        val searched = DrilldownSelectionResolver.resolveHabitSummaries(report, "wat")
        assertEquals(1, searched.size)
        assertEquals("Water", searched[0].habitName)
    }

    @Test
    fun testOverviewDynamics_independentDomainFailureGracefulHandling() {
        val habitResult = HabitCycleStatisticsResult(
            context = queryContext,
            dataState = StatisticsDataState.COMPLETE,
            daily = HabitCadenceAggregate(HabitCadence.DAILY),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = emptyList(),
            allCycles = emptyList(),
            habitSummaries = emptyList(),
            dateBuckets = mapOf(
                HabitCadence.DAILY to listOf(HabitCycleDateBucket(startDate, HabitCadence.DAILY, 3, 1, 0, emptyList()))
            )
        )

        val taskResult = TaskStatisticsResult(
            completedCount = 12,
            completedTasks = emptyList(),
            dailyCompletedBuckets = listOf(TaskDailyBucket(startDate, 5, emptyList())),
            createdCount = 9,
            createdTasks = emptyList(),
            dailyCreatedBuckets = emptyList(),
            dueSummary = TaskDueCohortSummary(0, 0, 0, 0),
            dailyDueBuckets = emptyList(),
            noDueSummary = TaskNoDueCohortSummary(0, 0, 0),
            dailyNoDueBuckets = emptyList(),
            dataState = StatisticsDataState.COMPLETE
        )

        // 1. Report where focus is null (e.g. calculation failed)
        val reportNoFocus = UnifiedStatisticsReport(
            context = queryContext,
            habits = habitResult,
            tasks = taskResult,
            focus = null,
            readIssues = emptyList()
        )

        val habitCols = DiscreteChartBucketer.buildHabitColumns(UnifiedPeriod.WEEK, HabitCadence.DAILY, reportNoFocus.habits!!, today, dummyPalette)
        val taskCols = DiscreteChartBucketer.buildTaskColumns(UnifiedPeriod.WEEK, reportNoFocus.tasks!!, today)

        assertEquals(1, habitCols.size)
        assertEquals(4L, habitCols[0].value)
        assertEquals(1, taskCols.size)
        assertEquals(5L, taskCols[0].value)
        assertNull(reportNoFocus.focus)

        // 2. Report where habits is null
        val reportNoHabits = UnifiedStatisticsReport(
            context = queryContext,
            habits = null,
            tasks = taskResult,
            focus = null,
            readIssues = emptyList()
        )
        assertNull(reportNoHabits.habits)
        assertNotNull(reportNoHabits.tasks)
    }

    @Test
    fun testReportReuse_canReuseAtRespectsTimedDueBoundary() {
        val dueEpoch = queryContext.now + 60_000L
        val timedTask = org.isoron.uhabits.core.userTasks.UserTask(
            id = org.isoron.uhabits.core.userTasks.TaskId("timed_1"),
            title = "Deadline Task",
            due = org.isoron.uhabits.core.userTasks.due.TaskDue.Timed.of(dueEpoch),
            createdAt = queryContext.now,
            updatedAt = queryContext.now
        )
        val tasksWithDue = TaskStatisticsResult(
            completedCount = 0,
            completedTasks = emptyList(),
            dailyCompletedBuckets = emptyList(),
            createdCount = 1,
            createdTasks = listOf(timedTask),
            dailyCreatedBuckets = emptyList(),
            dueSummary = TaskDueCohortSummary(
                totalCount = 1,
                completedCount = 0,
                pendingOverdueNowCount = 0,
                pendingNotOverdueNowCount = 1,
                tasks = listOf(timedTask),
                completedTasks = emptyList(),
                pendingOverdueNowTasks = emptyList(),
                pendingNotOverdueNowTasks = listOf(timedTask)
            ),
            dailyDueBuckets = emptyList(),
            noDueSummary = TaskNoDueCohortSummary(0, 0, 0),
            dailyNoDueBuckets = emptyList(),
            dataState = StatisticsDataState.COMPLETE
        )
        val report = UnifiedStatisticsReport(queryContext, null, tasksWithDue, null, emptyList())

        // Before deadline boundary: can reuse
        assertTrue(UnifiedStatisticsStateUtils.canReuseAt(report, dueEpoch - 1000L))

        // After deadline boundary has crossed: must not reuse (due state changed to overdue!)
        assertFalse(UnifiedStatisticsStateUtils.canReuseAt(report, dueEpoch + 1000L))

        // Going backwards in time: must not reuse
        assertFalse(UnifiedStatisticsStateUtils.canReuseAt(report, queryContext.now - 1000L))
    }
}
