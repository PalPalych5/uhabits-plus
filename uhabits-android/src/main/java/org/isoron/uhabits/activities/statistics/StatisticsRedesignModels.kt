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

import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.HabitCycleStatisticsResult
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure, unit-testable presentation mappers for the redesigned Statistics screen.
 * Keeps cadence aggregation strictly separated (daily vs weekly vs monthly vs limits).
 */
object StatisticsRedesignModels {

    enum class ProgressTone {
        SUCCESS,
        ACCENT,
        WARNING,
        DANGER,
        NEUTRAL,
    }

    data class OverviewPulseModel(
        val dailyCompletionPercent: Int?,
        val dailyFractionLabel: String?,
        val tasksCompletedCount: Int,
        val tasksOverdueCount: Int,
        val tasksCreatedCount: Int,
        val focusDurationFormatted: String?,
        val focusHasOverflow: Boolean,
        val focusSessionCount: Int,
        val focusAvgPerDayFormatted: String?,
    )

    data class HabitProgressRowModel(
        val cycleKey: String,
        val habitName: String,
        val dateRangeLabel: String,
        val showDateRange: Boolean,
        val headlineValue: String,
        val statusLabel: String,
        val tone: ProgressTone,
        val isAtMost: Boolean,
        val progressRatio: Float, // actual / target (can exceed 1f for exceeded limits)
        val isOverLimit: Boolean,
    )

    data class TasksEmptyStateModel(
        val title: String,
        val subtitle: String,
        val showResetScopeButton: Boolean,
    )

    fun buildOverviewPulse(
        report: UnifiedStatisticsReport,
        isRussian: Boolean,
    ): OverviewPulseModel {
        val habits = report.habits
        val daily = habits?.daily
        val dailyPct = if (daily != null && daily.knownCount > 0) {
            ((daily.satisfiedCount.toDouble() / daily.knownCount.toDouble()) * 100.0).roundToInt()
        } else null
        val dailyFraction = if (daily != null && daily.knownCount > 0) {
            if (isRussian) "${daily.satisfiedCount} из ${daily.knownCount}"
            else "${daily.satisfiedCount}/${daily.knownCount}"
        } else null

        val tasks = report.tasks
        val tasksCompleted = tasks?.completedCount ?: 0
        val tasksOverdue = tasks?.dueSummary?.pendingOverdueNowCount ?: 0
        val tasksCreated = tasks?.createdCount ?: 0

        val focus = report.focus
        val focusOverflow = focus != null && (
            focus.totalDurationMs == Long.MAX_VALUE ||
                focus.issues.any { it.code.name == "OVERFLOW" }
            )
        val focusFormatted = if (focus != null && !focusOverflow) {
            UnifiedStatisticsStateUtils.formatDurationMs(focus.totalDurationMs, isRussian)
        } else null
        val focusAvgFormatted = if (focus != null && !focusOverflow && focus.totalDurationMs > 0L) {
            val effectiveEnd = minOf(report.context.to, report.context.habitToday)
            val startJava = java.time.LocalDate.of(report.context.from.year, report.context.from.month, report.context.from.day)
            val endJava = java.time.LocalDate.of(effectiveEnd.year, effectiveEnd.month, effectiveEnd.day)
            val days = max(1, ChronoUnit.DAYS.between(startJava, endJava).toInt() + 1)
            val avgMs = focus.totalDurationMs / days
            if (avgMs >= 60_000L) {
                val formatted = UnifiedStatisticsStateUtils.formatDurationMs(avgMs, isRussian)
                if (isRussian) "~$formatted / день" else "~$formatted / day"
            } else null
        } else null

        return OverviewPulseModel(
            dailyCompletionPercent = dailyPct,
            dailyFractionLabel = dailyFraction,
            tasksCompletedCount = tasksCompleted,
            tasksOverdueCount = tasksOverdue,
            tasksCreatedCount = tasksCreated,
            focusDurationFormatted = focusFormatted,
            focusHasOverflow = focusOverflow,
            focusSessionCount = focus?.sessionCount ?: 0,
            focusAvgPerDayFormatted = focusAvgFormatted,
        )
    }

    /**
     * Selects multi-day goals (weekly/monthly) and AT_MOST limits for concise Habits tab display.
     * Routine ongoing daily AT_LEAST / binary habits belong on the Habits home tab or full-cycle drilldown,
     * not duplicated as a long stack under the daily dynamics chart.
     */
    fun selectCyclesForProgressSection(
        habits: HabitCycleStatisticsResult,
        selectedCadence: HabitCadence,
        limit: Int = 4,
    ): List<HabitCycle> {
        val candidates = if (habits.ongoingCycles.isNotEmpty()) {
            habits.ongoingCycles.filter { cycle ->
                cycle.goal.targetType == NumericalHabitType.AT_MOST ||
                    (cycle.cadence != HabitCadence.DAILY && cycle.cadence != HabitCadence.UNSUPPORTED)
            }
        } else {
            // Historical view with no active ongoing cycles: surface only closed limits or multi-day cycles
            // rather than repeating individual daily habits.
            habits.closedCycles.filter { cycle ->
                cycle.goal.targetType == NumericalHabitType.AT_MOST ||
                    (cycle.cadence != HabitCadence.DAILY && cycle.cadence != HabitCadence.UNSUPPORTED)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        return candidates.sortedWith(
            compareByDescending<HabitCycle> {
                val targetNative = resolveTargetNative(it)
                it.goal.targetType == NumericalHabitType.AT_MOST &&
                    it.quantity.isNumerical &&
                    targetNative != null &&
                    it.quantity.lowerBound > targetNative
            }
                .thenByDescending { it.goal.targetType == NumericalHabitType.AT_MOST }
                .thenByDescending { it.cadence == selectedCadence }
                .thenByDescending { it.cadence == HabitCadence.WEEKLY }
                .thenByDescending { it.quantity.isNumerical }
                .thenByDescending { it.endDate.daysSince2000 }
                .thenBy { it.habitName.lowercase() }
        ).take(limit)
    }

    /**
     * Summarizes factual cadence dynamics across populated non-future buckets without inventing rankings.
     */
    fun buildHabitsTrendSummary(
        cadence: HabitCadence,
        habits: HabitCycleStatisticsResult,
        today: org.isoron.platform.time.LocalDate,
        isRussian: Boolean,
    ): String? {
        val buckets = habits.dateBuckets[cadence].orEmpty()
            .filter { !it.date.isNewerThan(today) && (it.satisfiedCount + it.notSatisfiedCount) > 0 }
            .sortedBy { it.date.daysSince2000 }
        if (buckets.size < 2) return null

        val perfectBuckets = buckets.count { it.notSatisfiedCount == 0 && it.satisfiedCount > 0 }
        if (buckets.size >= 4) {
            val mid = buckets.size / 2
            val firstHalf = buckets.subList(0, mid)
            val secondHalf = buckets.subList(mid, buckets.size)
            val firstKnown = firstHalf.sumOf { it.satisfiedCount + it.notSatisfiedCount }
            val secondKnown = secondHalf.sumOf { it.satisfiedCount + it.notSatisfiedCount }
            if (firstKnown > 0 && secondKnown > 0) {
                val firstPct = ((firstHalf.sumOf { it.satisfiedCount }.toDouble() / firstKnown.toDouble()) * 100.0).roundToInt()
                val secondPct = ((secondHalf.sumOf { it.satisfiedCount }.toDouble() / secondKnown.toDouble()) * 100.0).roundToInt()
                val diff = secondPct - firstPct
                if (diff >= 8) {
                    return if (isRussian) "Тренд: +$diff% ко второй половине периода"
                    else "Trend: +$diff% in second half of period"
                } else if (diff <= -8) {
                    return if (isRussian) "Тренд: $diff% ко второй половине периода"
                    else "Trend: $diff% in second half of period"
                }
            }
        }
        val bucketWord = when (cadence) {
            HabitCadence.WEEKLY -> if (isRussian) "нед. без срывов" else "all-hit weeks"
            HabitCadence.MONTHLY -> if (isRussian) "мес. без срывов" else "all-hit months"
            else -> if (isRussian) "дн. без срывов" else "all-hit days"
        }
        return if (isRussian) "${bucketWord.replaceFirstChar { it.uppercase() }}: $perfectBuckets из ${buckets.size}"
        else "${bucketWord.replaceFirstChar { it.uppercase() }}: $perfectBuckets of ${buckets.size}"
    }

    fun shouldShowFocusBreakdownCard(itemCount: Int): Boolean = itemCount > 1

    fun buildTasksEmptyState(
        scope: UnifiedScope,
        period: UnifiedPeriod,
        isRussian: Boolean,
    ): TasksEmptyStateModel {
        if (scope !is UnifiedScope.All) {
            val title = if (isRussian) {
                if (period == UnifiedPeriod.ALL) "В этом разделе задач нет" else "Задач в периоде нет"
            } else {
                if (period == UnifiedPeriod.ALL) "No tasks in this section" else "No tasks in this period"
            }
            val subtitle = if (isRussian) {
                "Выберите другой период или переключите фильтр на все разделы."
            } else {
                "Try another period or switch the filter back to all sections."
            }
            return TasksEmptyStateModel(title = title, subtitle = subtitle, showResetScopeButton = true)
        }
        if (period == UnifiedPeriod.ALL) {
            return TasksEmptyStateModel(
                title = if (isRussian) "Задач пока нет" else "No tasks yet",
                subtitle = if (isRussian) {
                    "Завершённые и запланированные задачи появятся здесь по мере работы со списком задач."
                } else {
                    "Completed and scheduled tasks will appear here once you start working with your task list."
                },
                showResetScopeButton = false,
            )
        }
        val periodPhrase = when (period) {
            UnifiedPeriod.DAY -> if (isRussian) "За выбранный день" else "The selected day"
            UnifiedPeriod.WEEK -> if (isRussian) "За выбранную неделю" else "The selected week"
            UnifiedPeriod.MONTH -> if (isRussian) "За выбранный месяц" else "The selected month"
            UnifiedPeriod.YEAR -> if (isRussian) "За выбранный год" else "The selected year"
            UnifiedPeriod.ALL -> ""
        }
        return TasksEmptyStateModel(
            title = if (isRussian) "Задач в периоде нет" else "No tasks in this period",
            subtitle = if (isRussian) {
                "$periodPhrase не было ни завершённых, ни запланированных задач — попробуйте другой период."
            } else {
                "$periodPhrase had no completed or scheduled tasks — try an adjacent period or All time."
            },
            showResetScopeButton = false,
        )
    }

    fun formatPeriodHeaderLabel(
        periodName: String,
        rangeText: String?,
    ): String {
        if (rangeText.isNullOrBlank()) return periodName
        return "$periodName · $rangeText"
    }

    private val RU_SHORT_MONTHS = arrayOf(
        "янв.", "февр.", "мар.", "апр.", "мая", "июн.",
        "июл.", "авг.", "сент.", "окт.", "нояб.", "дек."
    )
    private val EN_SHORT_MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    fun formatCompactDateRange(
        start: org.isoron.platform.time.LocalDate,
        end: org.isoron.platform.time.LocalDate,
        referenceYear: Int,
        isRussian: Boolean,
        alwaysIncludeYear: Boolean = false,
    ): String {
        val months = if (isRussian) RU_SHORT_MONTHS else EN_SHORT_MONTHS
        val m1 = months[(start.month - 1).coerceIn(0, 11)]
        val m2 = months[(end.month - 1).coerceIn(0, 11)]
        if (start == end) {
            return if (!alwaysIncludeYear && start.year == referenceYear) {
                "${start.day} $m1"
            } else {
                "${start.day} $m1 ${start.year}"
            }
        }
        if (start.year == end.year && start.month == end.month) {
            val dash = if (alwaysIncludeYear) " — " else "–"
            return if (!alwaysIncludeYear && end.year == referenceYear) {
                "${start.day}$dash${end.day} $m2"
            } else {
                "${start.day}$dash${end.day} $m2 ${end.year}"
            }
        }
        if (start.year == end.year) {
            return if (!alwaysIncludeYear && end.year == referenceYear) {
                "${start.day} $m1 — ${end.day} $m2"
            } else {
                "${start.day} $m1 — ${end.day} $m2 ${end.year}"
            }
        }
        return "${start.day} $m1 ${start.year} — ${end.day} $m2 ${end.year}"
    }

    fun buildHabitProgressRow(
        cycle: HabitCycle,
        context: StatisticsQueryContext,
        dateFormatter: (org.isoron.platform.time.LocalDate) -> String = { _ -> "" },
        isRussian: Boolean,
    ): HabitProgressRowModel {
        val q = cycle.quantity
        val goal = cycle.goal
        val isHistorical = isHistoricalCycleSlice(cycle, context)
        val isClosed = cycle.lifecycle != HabitCycleLifecycle.ONGOING &&
            context.habitToday.isNewerThan(cycle.endDate) &&
            !cycle.endDate.isNewerThan(context.to)
        val dateLabel = formatCompactDateRange(
            start = cycle.startDate,
            end = cycle.endDate,
            referenceYear = context.habitToday.year,
            isRussian = isRussian,
            alwaysIncludeYear = false,
        )
        val showDateRange = !(cycle.startDate == context.from && cycle.endDate == context.to) &&
            !(cycle.cadence == HabitCadence.DAILY && cycle.startDate == context.habitToday && !isHistorical)
        val isAtMost = q.isNumerical && goal.targetType == NumericalHabitType.AT_MOST

        if (cycle.outcome == HabitCycleOutcome.UNAVAILABLE) {
            return HabitProgressRowModel(
                cycleKey = cycle.key.toString(),
                habitName = cycle.habitName,
                dateRangeLabel = dateLabel,
                showDateRange = showDateRange,
                headlineValue = "—",
                statusLabel = if (isRussian) "Недоступно" else "Unavailable",
                tone = ProgressTone.NEUTRAL,
                isAtMost = isAtMost,
                progressRatio = 0f,
                isOverLimit = false,
            )
        }

        if (q.isNumerical) {
            val targetNative = resolveTargetNative(cycle)
            val actualNative = q.lowerBound.coerceAtLeast(0L)
            val unit = q.unit.trim()
            val actualStr = formatMilliValue(actualNative, unit)
            val targetStr = if (targetNative != null) formatMilliValue(targetNative, unit) else "?"
            val ratio = if (targetNative != null && targetNative > 0L) {
                (actualNative.toDouble() / targetNative.toDouble()).toFloat()
            } else if (actualNative > 0L) 1f else 0f

            if (isAtMost) {
                val isOver = (targetNative != null && actualNative > targetNative) ||
                    cycle.outcome == HabitCycleOutcome.NOT_SATISFIED
                val status: String
                val tone: ProgressTone
                when {
                    isHistorical -> {
                        status = if (isRussian) "На конец периода" else "At period end"
                        tone = ProgressTone.NEUTRAL
                    }
                    isOver -> {
                        val diffStr = formatMilliValue((actualNative - (targetNative ?: 0L)).coerceAtLeast(0L), unit)
                        status = if (isClosed) {
                            if (isRussian) "Лимит превышен (+$diffStr)" else "Over limit (+$diffStr)"
                        } else {
                            if (isRussian) "Лимит превышен (+$diffStr) · период идёт"
                            else "Over limit (+$diffStr) · ongoing"
                        }
                        tone = ProgressTone.DANGER
                    }
                    isClosed && cycle.outcome == HabitCycleOutcome.SATISFIED -> {
                        val leftStr = if (targetNative != null) {
                            formatMilliValue((targetNative - actualNative).coerceAtLeast(0L), unit)
                        } else null
                        status = when {
                            targetNative != null && actualNative == targetNative ->
                                if (isRussian) "Лимит соблюдён · ровно по норме" else "Within limit · exact cap"
                            leftStr != null ->
                                if (isRussian) "Лимит соблюдён · запас $leftStr" else "Kept under limit · $leftStr under"
                            else -> if (isRussian) "Лимит соблюдён" else "Kept under limit"
                        }
                        tone = ProgressTone.SUCCESS
                    }
                    isClosed && cycle.outcome == HabitCycleOutcome.EXEMPT -> {
                        status = if (isRussian) "Освобождён (SKIP)" else "Exempt (SKIP)"
                        tone = ProgressTone.NEUTRAL
                    }
                    isClosed && cycle.outcome == HabitCycleOutcome.CAPACITY_EXCLUDED -> {
                        status = if (isRussian) "Не оценивается" else "Not evaluated"
                        tone = ProgressTone.NEUTRAL
                    }
                    isClosed && cycle.outcome == HabitCycleOutcome.UNKNOWN -> {
                        status = if (isRussian) "Недостаточно данных" else "Insufficient data"
                        tone = ProgressTone.NEUTRAL
                    }
                    ratio >= 0.85f && targetNative != null -> {
                        val leftStr = formatMilliValue((targetNative - actualNative).coerceAtLeast(0L), unit)
                        status = if (isRussian) {
                            "Близко к лимиту · осталось $leftStr · период идёт"
                        } else {
                            "Near limit · $leftStr left · ongoing"
                        }
                        tone = ProgressTone.WARNING
                    }
                    targetNative != null -> {
                        val leftStr = formatMilliValue((targetNative - actualNative).coerceAtLeast(0L), unit)
                        status = if (isRussian) {
                            "Осталось $leftStr · период идёт"
                        } else {
                            "$leftStr left · ongoing"
                        }
                        // Consumption comfortably under cap during an active period must use neutral fill,
                        // never achievement green before the period closes.
                        tone = ProgressTone.NEUTRAL
                    }
                    else -> {
                        status = if (isRussian) "В пределах лимита · период идёт" else "Within limit · ongoing"
                        tone = ProgressTone.NEUTRAL
                    }
                }
                return HabitProgressRowModel(
                    cycleKey = cycle.key.toString(),
                    habitName = cycle.habitName,
                    dateRangeLabel = dateLabel,
                    showDateRange = showDateRange,
                    headlineValue = "$actualStr / $targetStr",
                    statusLabel = status,
                    tone = tone,
                    isAtMost = true,
                    progressRatio = ratio.coerceAtLeast(0f),
                    isOverLimit = isOver,
                )
            } else {
                val reached = targetNative != null && actualNative >= targetNative
                val status = when {
                    isHistorical -> if (isRussian) "На конец периода" else "At period end"
                    isClosed && cycle.outcome == HabitCycleOutcome.SATISFIED ->
                        if (isRussian) "Цель достигнута" else "Goal achieved"
                    isClosed && cycle.outcome == HabitCycleOutcome.NOT_SATISFIED ->
                        if (isRussian) "Цель не достигнута" else "Goal missed"
                    cycle.lifecycle == HabitCycleLifecycle.PARTIAL ->
                        if (isRussian) "Неполный цикл" else "Partial cycle"
                    reached -> if (isRussian) "Цель выполнена" else "Goal reached"
                    else -> if (isRussian) "Идёт сейчас" else "Ongoing"
                }
                val tone = when {
                    isHistorical -> ProgressTone.NEUTRAL
                    isClosed && cycle.outcome == HabitCycleOutcome.NOT_SATISFIED -> ProgressTone.DANGER
                    reached || cycle.outcome == HabitCycleOutcome.SATISFIED -> ProgressTone.SUCCESS
                    else -> ProgressTone.ACCENT
                }
                return HabitProgressRowModel(
                    cycleKey = cycle.key.toString(),
                    habitName = cycle.habitName,
                    dateRangeLabel = dateLabel,
                    showDateRange = showDateRange,
                    headlineValue = "$actualStr / $targetStr",
                    statusLabel = status,
                    tone = tone,
                    isAtMost = false,
                    progressRatio = ratio.coerceIn(0f, 1f),
                    isOverLimit = false,
                )
            }
        } else {
            val quota = goal.frequency.numerator.coerceAtLeast(1)
            val actual = q.booleanManualCount.coerceAtLeast(0)
            val reached = actual >= quota
            val ratio = (actual.toFloat() / quota.toFloat()).coerceIn(0f, 1f)
            val cadenceSuffix = when (cycle.cadence) {
                HabitCadence.DAILY -> if (isRussian) "за день" else "today"
                HabitCadence.WEEKLY -> if (isRussian) "за нед." else "this wk"
                HabitCadence.MONTHLY -> if (isRussian) "за мес." else "this mo"
                else -> ""
            }
            val headline = if (cadenceSuffix.isNotEmpty()) "$actual / $quota $cadenceSuffix" else "$actual / $quota"
            val status = when {
                isHistorical -> if (isRussian) "На конец периода" else "At period end"
                isClosed && cycle.outcome == HabitCycleOutcome.SATISFIED ->
                    if (isRussian) "Цель достигнута" else "Goal achieved"
                isClosed && cycle.outcome == HabitCycleOutcome.NOT_SATISFIED ->
                    if (isRussian) "Цель не достигнута" else "Goal missed"
                reached -> if (isRussian) "Цель выполнена" else "Goal reached"
                else -> if (isRussian) "Идёт сейчас" else "Ongoing"
            }
            return HabitProgressRowModel(
                cycleKey = cycle.key.toString(),
                habitName = cycle.habitName,
                dateRangeLabel = dateLabel,
                showDateRange = showDateRange,
                headlineValue = headline,
                statusLabel = status,
                tone = when {
                    isHistorical -> ProgressTone.NEUTRAL
                    isClosed && cycle.outcome == HabitCycleOutcome.NOT_SATISFIED -> ProgressTone.DANGER
                    reached || cycle.outcome == HabitCycleOutcome.SATISFIED -> ProgressTone.SUCCESS
                    else -> ProgressTone.ACCENT
                },
                isAtMost = false,
                progressRatio = ratio,
                isOverLimit = false,
            )
        }
    }

    fun formatFocusTargetSourceLabel(
        targetType: FocusPrimaryTargetType?,
        isRussian: Boolean,
    ): String {
        return when (targetType) {
            FocusPrimaryTargetType.HABIT -> if (isRussian) "Привычки" else "Habits"
            FocusPrimaryTargetType.TASK -> if (isRussian) "Задачи" else "Tasks"
            FocusPrimaryTargetType.CONTAINER -> if (isRussian) "Разделы" else "Sections"
            null -> if (isRussian) "Свободный фокус" else "Unassigned"
        }
    }

    private fun resolveTargetNative(cycle: HabitCycle): Long? {
        val native = cycle.quantity.targetValueNative
        if (native != null) return native
        val raw = cycle.goal.targetValue
        if (!raw.isFinite() || raw < 0.0) return null
        return BigDecimal.valueOf(raw).multiply(BigDecimal.valueOf(1000L))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    internal fun formatMilliValue(valueNative: Long, unit: String): String {
        val bd = BigDecimal.valueOf(valueNative, 3).stripTrailingZeros()
        val numStr = bd.toPlainString()
        return if (unit.isNotBlank()) "$numStr $unit" else numStr
    }
}
