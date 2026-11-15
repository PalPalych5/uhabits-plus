/*
 * Copyright (C) 2026 Álinson Santos Xavier and contributors.
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package org.isoron.uhabits.activities.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.NumericalHabitType
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
import org.isoron.uhabits.core.statistics.StatisticsDataState
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.TaskDueCohortSummary
import org.isoron.uhabits.core.statistics.TaskNoDueCohortSummary
import org.isoron.uhabits.core.statistics.TaskStatisticsResult
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsRedesignModelsTest {

    private val start = LocalDate(2026, 11, 9)
    private val end = LocalDate(2026, 11, 15)
    private val ctx = StatisticsQueryContext(
        from = start,
        to = end,
        timezoneId = "UTC",
        firstWeekday = DayOfWeek.MONDAY,
        datasetId = "test-ds",
        revision = 1L,
        now = start.plus(4).unixTime,
    )

    private val palette = SettingsThemePalette(
        background = 0xFF000000.toInt(),
        surface = 0xFF121212.toInt(),
        surfaceVariant = 0xFF1E1E1E.toInt(),
        border = 0xFF282828.toInt(),
        divider = 0xFF1C1C1C.toInt(),
        groupDivider = 0xFF1C1C1C.toInt(),
        onSurface = 0xFFFFFFFF.toInt(),
        onSurfaceVariant = 0xFF9E9E9E.toInt(),
        accent = 0xFF68C37A.toInt(),
        isDark = true,
        isPureBlack = true,
    )

    private fun makeLimitCycle(
        uuid: String,
        name: String,
        actualMinutes: Long,
        limitMinutes: Long,
    ): HabitCycle {
        return HabitCycle(
            key = HabitCycleKey(uuid, "g1", start, end),
            habitUuid = uuid,
            habitName = name,
            cadence = HabitCadence.WEEKLY,
            lifecycle = HabitCycleLifecycle.ONGOING,
            outcome = HabitCycleOutcome.UNKNOWN,
            goal = HabitGoal(
                effectiveDate = start,
                frequency = Frequency.WEEKLY,
                targetType = NumericalHabitType.AT_MOST,
                targetValue = limitMinutes.toDouble(),
                unit = "мин"
            ),
            startDate = start,
            endDate = end,
            quantity = HabitCycleQuantity(
                isNumerical = true,
                lowerBound = actualMinutes * 1000L,
                manualValue = actualMinutes * 1000L,
                activeContributionValue = 0L,
                targetValue = limitMinutes.toDouble(),
                targetValueNative = limitMinutes * 1000L,
                unit = "мин",
                totalDays = 7,
                knownDays = 5,
                unknownDays = 2,
                hasCompleteCoverage = false
            ),
            days = emptyList(),
        )
    }

    @Test
    fun buildOverviewPulse_keepsDailyWeeklyAndLimitsStrictlySeparate() {
        val dailyAgg = HabitCadenceAggregate(
            cadence = HabitCadence.DAILY,
            satisfiedCount = 3,
            notSatisfiedCount = 1,
            unknownCount = 5,
        )
        val weeklyCycle = makeLimitCycle("vid", "Видеоигры", actualMinutes = 60L, limitMinutes = 180L)
        val weeklyAgg = HabitCadenceAggregate(
            cadence = HabitCadence.WEEKLY,
            satisfiedCount = 0,
            notSatisfiedCount = 1,
            ongoingCount = 1,
            cycles = listOf(weeklyCycle)
        )
        val habitsResult = HabitCycleStatisticsResult(
            context = ctx,
            dataState = StatisticsDataState.PARTIAL,
            daily = dailyAgg,
            weekly = weeklyAgg,
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = listOf(weeklyCycle),
            allCycles = listOf(weeklyCycle),
            habitSummaries = emptyList(),
            dateBuckets = emptyMap(),
        )
        val tasksResult = TaskStatisticsResult(
            completedCount = 2,
            completedTasks = emptyList(),
            dailyCompletedBuckets = emptyList(),
            createdCount = 3,
            createdTasks = emptyList(),
            dailyCreatedBuckets = emptyList(),
            dueSummary = TaskDueCohortSummary(1, 0, 1, 0),
            dailyDueBuckets = emptyList(),
            noDueSummary = TaskNoDueCohortSummary(2, 2, 0),
            dailyNoDueBuckets = emptyList(),
            dataState = StatisticsDataState.COMPLETE,
        )
        val focusResult = FocusStatisticsResult(
            totalDurationMs = 120L * 60_000L,
            sessionCount = 4,
            averageSessionDurationMs = 30L * 60_000L,
            sessions = emptyList(),
            dailyBuckets = emptyList(),
            sectionBreakdowns = emptyList(),
            targetBreakdowns = emptyList(),
            targetTypeSummaries = emptyList(),
            dataState = StatisticsDataState.COMPLETE,
        )
        val report = UnifiedStatisticsReport(ctx, habitsResult, tasksResult, focusResult, emptyList())

        val pulse = StatisticsRedesignModels.buildOverviewPulse(report, isRussian = true)

        // Daily completion must NOT mix in weekly 0/1 failure; Overview stays free of secondary clutter (R02)
        assertEquals(75, pulse.dailyCompletionPercent)
        assertEquals("3 из 4", pulse.dailyFractionLabel)
        assertEquals(2, pulse.tasksCompletedCount)
        assertEquals(1, pulse.tasksOverdueCount)
        assertEquals(4, pulse.focusSessionCount)
        assertTrue(pulse.focusAvgPerDayFormatted!!.contains("/ день"))
    }

    @Test
    fun buildHabitProgressRow_handlesOngoingWarningExceededAndClosedAtMost() {
        val comfortablyWithin = makeLimitCycle("vid", "Видеоигры", actualMinutes = 60L, limitMinutes = 180L)
        val ongoingModel = StatisticsRedesignModels.buildHabitProgressRow(
            cycle = comfortablyWithin,
            context = ctx.copy(now = end.unixTime),
            dateFormatter = ::formatStatisticsDate,
            isRussian = true,
        )
        assertEquals("60 мин / 180 мин", ongoingModel.headlineValue)
        assertEquals("Осталось 120 мин · период идёт", ongoingModel.statusLabel)
        assertFalse(ongoingModel.isOverLimit)
        assertEquals(StatisticsRedesignModels.ProgressTone.NEUTRAL, ongoingModel.tone)
        assertFalse(ongoingModel.showDateRange)
        assertEquals(1f / 3f, ongoingModel.progressRatio, 0.01f)

        val nearCap = makeLimitCycle("vid", "Видеоигры", actualMinutes = 160L, limitMinutes = 180L)
        val nearCapModel = StatisticsRedesignModels.buildHabitProgressRow(
            cycle = nearCap,
            context = ctx.copy(now = end.unixTime),
            dateFormatter = ::formatStatisticsDate,
            isRussian = true,
        )
        assertEquals("Близко к лимиту · осталось 20 мин · период идёт", nearCapModel.statusLabel)
        assertFalse(nearCapModel.isOverLimit)
        assertEquals(StatisticsRedesignModels.ProgressTone.WARNING, nearCapModel.tone)

        val exceeded = makeLimitCycle("vid", "Видеоигры", actualMinutes = 200L, limitMinutes = 180L)
        val exceededModel = StatisticsRedesignModels.buildHabitProgressRow(
            cycle = exceeded,
            context = ctx.copy(now = end.unixTime),
            dateFormatter = ::formatStatisticsDate,
            isRussian = true,
        )
        assertEquals("200 мин / 180 мин", exceededModel.headlineValue)
        assertEquals("Лимит превышен (+20 мин) · период идёт", exceededModel.statusLabel)
        assertTrue(exceededModel.isOverLimit)
        assertEquals(StatisticsRedesignModels.ProgressTone.DANGER, exceededModel.tone)
        assertTrue(exceededModel.progressRatio > 1f)

        val closedSatisfied = comfortablyWithin.copy(
            lifecycle = HabitCycleLifecycle.CLOSED,
            outcome = HabitCycleOutcome.SATISFIED,
        )
        val closedSatisfiedModel = StatisticsRedesignModels.buildHabitProgressRow(
            cycle = closedSatisfied,
            context = ctx.copy(now = end.plus(2).unixTime),
            dateFormatter = ::formatStatisticsDate,
            isRussian = true,
        )
        assertEquals("Лимит соблюдён · запас 120 мин", closedSatisfiedModel.statusLabel)
        assertFalse(closedSatisfiedModel.isOverLimit)
        assertEquals(StatisticsRedesignModels.ProgressTone.SUCCESS, closedSatisfiedModel.tone)
        assertFalse(closedSatisfiedModel.showDateRange)

        val closedExceeded = exceeded.copy(
            lifecycle = HabitCycleLifecycle.CLOSED,
            outcome = HabitCycleOutcome.NOT_SATISFIED,
        )
        val closedExceededModel = StatisticsRedesignModels.buildHabitProgressRow(
            cycle = closedExceeded,
            context = ctx.copy(now = end.plus(2).unixTime),
            dateFormatter = ::formatStatisticsDate,
            isRussian = true,
        )
        assertEquals("Лимит превышен (+20 мин)", closedExceededModel.statusLabel)
        assertTrue(closedExceededModel.isOverLimit)
        assertEquals(StatisticsRedesignModels.ProgressTone.DANGER, closedExceededModel.tone)
    }

    @Test
    fun selectCyclesForProgressSection_omitsDailyAtLeastAndLimitsToFour() {
        val dailyBool = HabitCycle(
            key = HabitCycleKey("d1", "g1", start, start),
            habitUuid = "d1",
            habitName = "Вода",
            cadence = HabitCadence.DAILY,
            lifecycle = HabitCycleLifecycle.ONGOING,
            outcome = HabitCycleOutcome.UNKNOWN,
            goal = HabitGoal(start, Frequency.DAILY, NumericalHabitType.AT_LEAST, 1.0, ""),
            startDate = start,
            endDate = start,
            quantity = HabitCycleQuantity(
                isNumerical = false,
                lowerBound = 0L,
                manualValue = 0L,
                activeContributionValue = 0L,
                targetValue = 1.0,
                booleanManualCount = 1
            ),
            days = emptyList(),
        )
        val weeklyLimit = makeLimitCycle("w1", "Видеоигры", actualMinutes = 60L, limitMinutes = 180L)
        val weeklyGoals = (2..5).map { idx ->
            weeklyLimit.copy(
                key = HabitCycleKey("w$idx", "g1", start, end),
                habitUuid = "w$idx",
                habitName = "Цель $idx",
                goal = HabitGoal(start, Frequency.WEEKLY, NumericalHabitType.AT_LEAST, 3.0, ""),
            )
        }
        val result = HabitCycleStatisticsResult(
            context = ctx,
            dataState = StatisticsDataState.COMPLETE,
            daily = HabitCadenceAggregate(HabitCadence.DAILY),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = listOf(dailyBool, weeklyLimit) + weeklyGoals,
            allCycles = listOf(dailyBool, weeklyLimit) + weeklyGoals,
            habitSummaries = emptyList(),
            dateBuckets = emptyMap(),
        )

        val selected = StatisticsRedesignModels.selectCyclesForProgressSection(
            result, selectedCadence = HabitCadence.DAILY, limit = 4
        )
        assertEquals(4, selected.size)
        assertFalse(selected.any { it.habitUuid == "d1" })
        assertEquals("Видеоигры", selected.first().habitName)
    }

    @Test
    fun buildHabitsTrendSummary_reportsPeriodCompletion() {
        val dailyAgg = HabitCadenceAggregate(HabitCadence.DAILY, satisfiedCount = 9, notSatisfiedCount = 3)
        val habits = HabitCycleStatisticsResult(
            context = ctx,
            dataState = StatisticsDataState.COMPLETE,
            daily = dailyAgg,
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY, satisfiedCount = 1, notSatisfiedCount = 1),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = emptyList(),
            allCycles = emptyList(),
            habitSummaries = emptyList(),
            dateBuckets = mapOf(
                HabitCadence.DAILY to listOf(
                    HabitCycleDateBucket(start, HabitCadence.DAILY, satisfiedCount = 3, notSatisfiedCount = 0, unknownCount = 0, cycles = emptyList()),
                    HabitCycleDateBucket(start.plus(1), HabitCadence.DAILY, satisfiedCount = 6, notSatisfiedCount = 2, unknownCount = 0, cycles = emptyList()),
                ),
                HabitCadence.WEEKLY to listOf(
                    HabitCycleDateBucket(start, HabitCadence.WEEKLY, satisfiedCount = 1, notSatisfiedCount = 1, unknownCount = 0, cycles = emptyList()),
                    HabitCycleDateBucket(start.plus(7), HabitCadence.WEEKLY, satisfiedCount = 1, notSatisfiedCount = 0, unknownCount = 0, cycles = emptyList()),
                )
            ),
        )
        val summaryRu = StatisticsRedesignModels.buildHabitsTrendSummary(
            HabitCadence.DAILY, habits, end, isRussian = true
        )
        assertEquals("Дн. без срывов: 1 из 2", summaryRu)

        val summaryWeeklyEn = StatisticsRedesignModels.buildHabitsTrendSummary(
            HabitCadence.WEEKLY, habits, start.plus(14), isRussian = false
        )
        assertEquals("All-hit weeks: 1 of 2", summaryWeeklyEn)
    }

    @Test
    fun shouldShowFocusBreakdownCard_hidesSingleCategory() {
        assertFalse(StatisticsRedesignModels.shouldShowFocusBreakdownCard(0))
        assertFalse(StatisticsRedesignModels.shouldShowFocusBreakdownCard(1))
        assertTrue(StatisticsRedesignModels.shouldShowFocusBreakdownCard(2))
    }

    @Test
    fun buildTasksEmptyState_adaptsToAllAndScopedContexts() {
        val allWeek = StatisticsRedesignModels.buildTasksEmptyState(
            scope = UnifiedScope.All,
            period = UnifiedPeriod.WEEK,
            isRussian = true,
        )
        assertFalse(allWeek.showResetScopeButton)
        assertFalse(allWeek.subtitle.contains("все разделы", ignoreCase = true))
        assertTrue(allWeek.subtitle.contains("неделю"))

        val allTime = StatisticsRedesignModels.buildTasksEmptyState(
            scope = UnifiedScope.All,
            period = UnifiedPeriod.ALL,
            isRussian = true,
        )
        assertEquals("Задач пока нет", allTime.title)
        assertFalse(allTime.showResetScopeButton)

        val scoped = StatisticsRedesignModels.buildTasksEmptyState(
            scope = UnifiedScope.Unassigned,
            period = UnifiedPeriod.WEEK,
            isRussian = false,
        )
        assertTrue(scoped.showResetScopeButton)
        assertTrue(scoped.subtitle.contains("all sections"))
    }

    @Test
    fun formatPeriodHeaderLabel_combinesPeriodAndDateCleanly() {
        assertEquals("Неделя · 9–15 нояб.", StatisticsRedesignModels.formatPeriodHeaderLabel("Неделя", "9–15 нояб."))
        assertEquals("Весь период", StatisticsRedesignModels.formatPeriodHeaderLabel("Весь период", null))
        assertEquals("All", StatisticsRedesignModels.formatPeriodHeaderLabel("All", ""))
    }

    @Test
    fun buildHabitColumns_excludeUnknownSegmentFlagOmitsUnknownCaps() {
        val bucket = HabitCycleDateBucket(
            date = start,
            cadence = HabitCadence.DAILY,
            satisfiedCount = 2,
            notSatisfiedCount = 1,
            unknownCount = 4,
            cycles = emptyList(),
        )
        val result = HabitCycleStatisticsResult(
            context = ctx,
            dataState = StatisticsDataState.PARTIAL,
            daily = HabitCadenceAggregate(HabitCadence.DAILY, satisfiedCount = 2, notSatisfiedCount = 1, unknownCount = 4),
            weekly = HabitCadenceAggregate(HabitCadence.WEEKLY),
            monthly = HabitCadenceAggregate(HabitCadence.MONTHLY),
            unsupported = HabitCadenceAggregate(HabitCadence.UNSUPPORTED),
            closedCycles = emptyList(),
            ongoingCycles = emptyList(),
            allCycles = emptyList(),
            habitSummaries = emptyList(),
            dateBuckets = mapOf(HabitCadence.DAILY to listOf(bucket)),
        )

        val weekWithoutUnknown = DiscreteChartBucketer.buildHabitColumns(
            UnifiedPeriod.WEEK, HabitCadence.DAILY, result, end, palette,
            isRussian = true, includeUnknownSegment = false
        )
        assertEquals(3L, weekWithoutUnknown.first().value)
        assertEquals(2, weekWithoutUnknown.first().segments.size)

        val weekWithUnknown = DiscreteChartBucketer.buildHabitColumns(
            UnifiedPeriod.WEEK, HabitCadence.DAILY, result, end, palette,
            isRussian = true, includeUnknownSegment = true
        )
        assertEquals(7L, weekWithUnknown.first().value)
        assertEquals(3, weekWithUnknown.first().segments.size)

        val yearWithoutUnknown = DiscreteChartBucketer.buildHabitColumns(
            UnifiedPeriod.YEAR, HabitCadence.DAILY, result, end, palette,
            isRussian = true, includeUnknownSegment = false
        )
        val novCol = yearWithoutUnknown[10]
        assertEquals(3L, novCol.value)
        assertEquals(2, novCol.segments.size)
    }

    @Test
    fun formatFocusTargetSourceLabel_returnsCleanHumanReadableLabels() {
        assertEquals("Привычки", StatisticsRedesignModels.formatFocusTargetSourceLabel(FocusPrimaryTargetType.HABIT, true))
        assertEquals("Задачи", StatisticsRedesignModels.formatFocusTargetSourceLabel(FocusPrimaryTargetType.TASK, true))
        assertEquals("Свободный фокус", StatisticsRedesignModels.formatFocusTargetSourceLabel(null, true))
        assertEquals("Habits", StatisticsRedesignModels.formatFocusTargetSourceLabel(FocusPrimaryTargetType.HABIT, false))
    }

    @Test
    fun formatCompactDateRange_avoidsRepetitiveYearAndTruncation() {
        assertEquals("9–15 нояб.", StatisticsRedesignModels.formatCompactDateRange(start, end, 2026, isRussian = true))
        assertEquals("15 нояб.", StatisticsRedesignModels.formatCompactDateRange(end, end, 2026, isRussian = true))
        assertEquals("9 — 15 нояб. 2026", StatisticsRedesignModels.formatCompactDateRange(start, end, 2026, isRussian = true, alwaysIncludeYear = true))
    }
}
