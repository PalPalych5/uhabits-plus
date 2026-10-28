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
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.statistics.FocusSectionCategory
import org.isoron.uhabits.core.statistics.FocusStatisticsResult
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleDateBucket
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.HabitCycleStatisticsResult
import org.isoron.uhabits.core.statistics.HabitSummary
import org.isoron.uhabits.core.statistics.StatisticsIssue
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.TaskStatisticsResult
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.isoron.uhabits.core.userTasks.UserTask

/** The four analytic tabs for PR14 Unified Statistics. */
enum class UnifiedAnalyticsTab {
    OVERVIEW,
    HABITS,
    TASKS,
    FOCUS
}

internal fun statisticsQuerySignature(context: StatisticsQueryContext): String =
    "${context.datasetId}|${context.from.daysSince2000}|${context.to.daysSince2000}|" +
        "${context.timezoneId}|${context.sectionScope}|${context.firstWeekday}|" +
        "${context.habitHourOffset}|${context.habitMinuteOffset}"

internal fun formatStatisticsDate(date: LocalDate): String =
    java.time.LocalDate.of(date.year, date.month, date.day).format(
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withLocale(java.util.Locale.getDefault())
    )

/** Supported periods across all analytic domains. */
enum class UnifiedPeriod {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ALL
}

/** Scope selection for Unified Statistics. */
sealed class UnifiedScope {
    data object All : UnifiedScope()
    data object Unassigned : UnifiedScope()
    data class Container(
        val id: ContainerId,
        val includeDescendants: Boolean = true,
    ) : UnifiedScope()

    fun toStatisticsSectionScope(): StatisticsSectionScope = when (this) {
        All -> StatisticsSectionScope.All
        Unassigned -> StatisticsSectionScope.Unassigned
        is Container -> StatisticsSectionScope.Container(
            id = id,
            includeDescendants = includeDescendants,
        )
    }

    companion object {
        fun fromStatisticsSectionScope(scope: StatisticsSectionScope): UnifiedScope = when (scope) {
            is StatisticsSectionScope.All -> All
            is StatisticsSectionScope.Unassigned -> Unassigned
            is StatisticsSectionScope.Container -> Container(scope.id, scope.includeDescendants)
        }
    }
}

/**
 * Revision tuple capturing all dataset and revision sources.
 * Comparisons compare all fields explicitly to prevent counter-summing or hash collision.
 */
data class StatisticsRevisionTuple(
    val datasetId: String,
    val effectiveRevision: Long,
    val workspaceRevision: Long,
    val organizationRevision: Long,
    val habitListVersion: Long = 0L,
) {
    fun isMatching(other: StatisticsRevisionTuple): Boolean {
        return datasetId == other.datasetId &&
            effectiveRevision == other.effectiveRevision &&
            workspaceRevision == other.workspaceRevision &&
            organizationRevision == other.organizationRevision &&
            habitListVersion == other.habitListVersion
    }
}

/** Cache and invalidation key for a single report load. */
data class UnifiedReportKey(
    val period: UnifiedPeriod,
    val from: LocalDate,
    val to: LocalDate,
    val scope: UnifiedScope,
    val revisionTuple: StatisticsRevisionTuple,
    val calendarKey: String = "",
)

/** UI state for the unified statistics report view. */
sealed class UnifiedReportUiState {
    data object InitialLoading : UnifiedReportUiState()

    data class Success(
        val report: UnifiedStatisticsReport,
        val isRefreshing: Boolean,
        val renderedKey: UnifiedReportKey,
    ) : UnifiedReportUiState()

    data class Failure(
        val message: String,
        val issues: List<StatisticsIssue> = emptyList(),
        val previousReport: UnifiedStatisticsReport? = null,
    ) : UnifiedReportUiState()
}

/** Pure mathematical and date range calculations for unified statistics. */
object UnifiedStatisticsStateUtils {
    fun nextDayBoundary(context: org.isoron.uhabits.core.statistics.StatisticsQueryContext, now: Long): Long {
        val zone = java.time.ZoneId.of(context.timezoneId)
        val instant = java.time.Instant.ofEpochMilli(now)
        val date = instant.atZone(zone).toLocalDate()
        val boundaries = mutableListOf(context.civilDayStart(context.today.plus(1)))
        // During an overlap, the same local boundary occurs twice. The
        // wall-clock-based habit date can also move backwards at the transition.
        // Wake at both occurrences and at the transition so it is never stale.
        for (day in listOf(date, date.plusDays(1))) {
            val wall = day.atTime(context.habitHourOffset, context.habitMinuteOffset)
            val offsets = zone.rules.getValidOffsets(wall)
            if (offsets.isEmpty()) {
                zone.rules.getTransition(wall)?.let { boundaries.add(it.instant.toEpochMilli()) }
            } else {
                offsets.forEach { boundaries.add(wall.toInstant(it).toEpochMilli()) }
            }
        }
        zone.rules.nextTransition(instant)?.let { boundaries.add(it.instant.toEpochMilli()) }
        return boundaries.filter { it > now }.minOrNull()!!
    }

    fun canReuseAt(report: UnifiedStatisticsReport, now: Long): Boolean {
        if (now < report.context.now) return false
        return report.tasks?.dueSummary?.tasks.orEmpty().none { task ->
            val due = task.due as? org.isoron.uhabits.core.userTasks.due.TaskDue.Timed
            due != null && due.epochMillis >= report.context.now && due.epochMillis <= now
        }
    }

    /**
     * Calculates the inclusive [from, to] date range for a given period and anchor date.
     * When period is ALL, uses [earliestObservation] (bounded by [today]).
     */
    fun calculatePeriodRange(
        period: UnifiedPeriod,
        anchorDate: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
        earliestObservation: LocalDate? = null,
    ): Pair<LocalDate, LocalDate> = when (period) {
        UnifiedPeriod.DAY -> Pair(anchorDate, anchorDate)

        UnifiedPeriod.WEEK -> {
            val start = anchorDate.startOfWeek(firstWeekday)
            Pair(start, start.plus(6))
        }

        UnifiedPeriod.MONTH -> {
            val start = anchorDate.startOfMonth()
            val end = start.plus(start.monthLength - 1)
            Pair(start, end)
        }

        UnifiedPeriod.YEAR -> {
            val start = LocalDate(anchorDate.year, 1, 1)
            val end = LocalDate(anchorDate.year, 12, 31)
            Pair(start, end)
        }

        UnifiedPeriod.ALL -> {
            val start = earliestObservation?.let { if (it > today) today else it } ?: today
            Pair(start, today)
        }
    }

    /**
     * Navigates the anchor date by [direction] (-1 for previous, +1 for next).
     * ALL does not navigate.
     */
    fun navigatePeriod(
        period: UnifiedPeriod,
        anchorDate: LocalDate,
        direction: Int,
        firstWeekday: DayOfWeek,
    ): LocalDate = when (period) {
        UnifiedPeriod.DAY -> anchorDate.plus(direction)

        UnifiedPeriod.WEEK -> anchorDate.plus(direction * 7)

        UnifiedPeriod.MONTH -> {
            val totalMonths = anchorDate.year * 12 + (anchorDate.month - 1) + direction
            val newYear = totalMonths / 12
            val newMonth = (totalMonths % 12) + 1
            val maxDays = LocalDate(newYear, newMonth, 1).monthLength
            val newDay = anchorDate.day.coerceAtMost(maxDays)
            LocalDate(newYear, newMonth, newDay)
        }

        UnifiedPeriod.YEAR -> {
            val newYear = anchorDate.year + direction
            val maxDays = LocalDate(newYear, anchorDate.month, 1).monthLength
            val newDay = anchorDate.day.coerceAtMost(maxDays)
            LocalDate(newYear, anchorDate.month, newDay)
        }

        UnifiedPeriod.ALL -> anchorDate
    }

    /**
     * Returns true if [anchorDate] is in the current or future period relative to [today],
     * which bounds the "next" button.
     */
    fun isLatestAllowedPeriod(
        period: UnifiedPeriod,
        anchorDate: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
    ): Boolean = when (period) {
        UnifiedPeriod.DAY -> anchorDate >= today

        UnifiedPeriod.WEEK -> {
            val anchorWeekStart = anchorDate.startOfWeek(firstWeekday)
            val todayWeekStart = today.startOfWeek(firstWeekday)
            anchorWeekStart >= todayWeekStart
        }

        UnifiedPeriod.MONTH -> {
            anchorDate.year > today.year ||
                (anchorDate.year == today.year && anchorDate.month >= today.month)
        }

        UnifiedPeriod.YEAR -> anchorDate.year >= today.year

        UnifiedPeriod.ALL -> true
    }

    /**
     * Clamps the anchor date to [today] if it extends into the future beyond the allowed period.
     */
    fun clampAnchorDateToLatest(
        period: UnifiedPeriod,
        anchorDate: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
    ): LocalDate {
        return if (isLatestAllowedPeriod(period, anchorDate, today, firstWeekday) && anchorDate > today) {
            today
        } else {
            anchorDate
        }
    }

    /**
     * Formats duration in milliseconds into a friendly string (hours and minutes).
     */
    fun formatDurationMs(durationMs: Long, isRussian: Boolean = false): String {
        if (durationMs <= 0L) {
            return if (isRussian) "0 мин" else "0 min"
        }
        if (durationMs < 60_000L) {
            val seconds = (durationMs / 1000L).coerceAtLeast(1L)
            return if (isRussian) "$seconds с" else "$seconds s"
        }
        val totalMinutes = durationMs / 60_000L + if (durationMs % 60_000L >= 30_000L) 1 else 0
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> {
                if (isRussian) "$hours ч $minutes мин" else "$hours h $minutes min"
            }
            hours > 0 -> {
                if (isRussian) "$hours ч" else "$hours h"
            }
            else -> {
                if (isRussian) "$minutes мин" else "$minutes min"
            }
        }
    }

    /** Exact display for a recorded session or a numerical chart axis. */
    fun formatExactDurationMs(durationMs: Long, isRussian: Boolean = false): String {
        val value = durationMs.coerceAtLeast(0)
        val hours = value / 3_600_000
        val minutes = value % 3_600_000 / 60_000
        val seconds = value % 60_000
        return buildList {
            if (hours > 0) add("$hours ${if (isRussian) "ч" else "h"}")
            if (minutes > 0) add("$minutes ${if (isRussian) "мин" else "min"}")
            if (seconds > 0 || isEmpty()) {
                val text = java.math.BigDecimal.valueOf(seconds, 3).stripTrailingZeros().toPlainString()
                add("$text ${if (isRussian) "с" else "s"}")
            }
        }.joinToString(" ")
    }
}

/** Selection descriptor for Tasks tab drilldowns. */
sealed class TasksDrilldownDescriptor {
    data object None : TasksDrilldownDescriptor()

    /** T1: Specific completion date bucket. */
    data class CompletedDay(val date: LocalDate) : TasksDrilldownDescriptor()

    /** T1: Date range for month/aggregated buckets. */
    data class CompletedRange(val from: LocalDate, val to: LocalDate, val label: String) : TasksDrilldownDescriptor()

    /** T2: Created tasks in date range. */
    data class CreatedTasks(val from: LocalDate, val to: LocalDate, val label: String) : TasksDrilldownDescriptor()

    /** T3: Due cohort segment. */
    enum class DueSegmentFilter { ALL_DUE, COMPLETED, OVERDUE_NOW, PENDING_NOT_OVERDUE }
    data class DueCohort(val filter: DueSegmentFilter) : TasksDrilldownDescriptor()

    /** T4: No-due created cohort segment. */
    enum class NoDueSegmentFilter { ALL_NO_DUE, COMPLETED, PENDING }
    data class NoDueCohort(val filter: NoDueSegmentFilter) : TasksDrilldownDescriptor()
}

/** Selection descriptor for Focus tab drilldowns. */
sealed class FocusDrilldownDescriptor {
    data object None : FocusDrilldownDescriptor()
    data object AllSections : FocusDrilldownDescriptor()
    data class TargetType(val type: FocusPrimaryTargetType?) : FocusDrilldownDescriptor()

    /** F1: Specific day's finalized sessions. */
    data class DaySessions(val date: LocalDate) : FocusDrilldownDescriptor()

    /** F1: Date range for month/aggregated buckets. */
    data class SessionsRange(val from: LocalDate, val to: LocalDate, val label: String) : FocusDrilldownDescriptor()

    /** F3: Disjoint section branch. */
    data class SectionBranch(
        val category: FocusSectionCategory,
        val containerId: ContainerId?,
    ) : FocusDrilldownDescriptor()

    /** F4: Target object within branch or globally. */
    data class TargetObject(
        val primaryTargetType: FocusPrimaryTargetType?,
        val targetId: String?,
        val sectionCategory: FocusSectionCategory? = null,
        val sectionContainerId: ContainerId? = null,
    ) : FocusDrilldownDescriptor()

    /** Calendar mode allocation view for a specific date. */
    data class CalendarDay(val date: LocalDate) : FocusDrilldownDescriptor()
}

/** Selection descriptor for Habits tab drilldowns. */
sealed class HabitsDrilldownDescriptor {
    data object None : HabitsDrilldownDescriptor()
    data class CadenceCycles(val cadence: HabitCadence) : HabitsDrilldownDescriptor()
    data class BucketCycles(val date: LocalDate, val cadence: HabitCadence, val label: String) : HabitsDrilldownDescriptor()
    data class CadenceRangeCycles(val cadence: HabitCadence, val from: LocalDate, val to: LocalDate, val label: String) : HabitsDrilldownDescriptor()
    data object OngoingCycles : HabitsDrilldownDescriptor()
    data class HabitCycles(val habitUuid: String, val habitName: String = "") : HabitsDrilldownDescriptor()
    data class AllHabitsList(val searchQuery: String = "") : HabitsDrilldownDescriptor()
}

/** A period-end snapshot is not a statement about the cycle's state today. */
internal fun isHistoricalCycleSlice(
    cycle: HabitCycle,
    context: org.isoron.uhabits.core.statistics.StatisticsQueryContext,
): Boolean = cycle.lifecycle == org.isoron.uhabits.core.statistics.HabitCycleLifecycle.ONGOING &&
    cycle.isContextual && context.to < context.habitToday

private fun isHabitChartOutcome(cycle: HabitCycle): Boolean = when (cycle.outcome) {
    org.isoron.uhabits.core.statistics.HabitCycleOutcome.SATISFIED,
    org.isoron.uhabits.core.statistics.HabitCycleOutcome.NOT_SATISFIED,
    org.isoron.uhabits.core.statistics.HabitCycleOutcome.UNKNOWN -> true
    else -> false
}

/** Pure resolver functions binding descriptors dynamically to the active report. */
object DrilldownSelectionResolver {

    fun resolveTasks(
        report: UnifiedStatisticsReport,
        descriptor: TasksDrilldownDescriptor,
    ): List<UserTask> {
        val tasks = report.tasks ?: return emptyList()
        return when (descriptor) {
            TasksDrilldownDescriptor.None -> emptyList()
            is TasksDrilldownDescriptor.CompletedDay -> {
                tasks.dailyCompletedBuckets.firstOrNull { it.date == descriptor.date }?.tasks.orEmpty()
            }
            is TasksDrilldownDescriptor.CompletedRange -> {
                tasks.dailyCompletedBuckets
                    .filter { it.date >= descriptor.from && it.date <= descriptor.to }
                    .flatMap { it.tasks }
            }
            is TasksDrilldownDescriptor.CreatedTasks -> {
                tasks.createdTasks
            }
            is TasksDrilldownDescriptor.DueCohort -> when (descriptor.filter) {
                TasksDrilldownDescriptor.DueSegmentFilter.ALL_DUE -> tasks.dueSummary.tasks
                TasksDrilldownDescriptor.DueSegmentFilter.COMPLETED -> tasks.dueSummary.completedTasks
                TasksDrilldownDescriptor.DueSegmentFilter.OVERDUE_NOW -> tasks.dueSummary.pendingOverdueNowTasks
                TasksDrilldownDescriptor.DueSegmentFilter.PENDING_NOT_OVERDUE -> tasks.dueSummary.pendingNotOverdueNowTasks
            }
            is TasksDrilldownDescriptor.NoDueCohort -> when (descriptor.filter) {
                TasksDrilldownDescriptor.NoDueSegmentFilter.ALL_NO_DUE -> tasks.noDueSummary.tasks
                TasksDrilldownDescriptor.NoDueSegmentFilter.COMPLETED -> tasks.noDueSummary.completedTasks
                TasksDrilldownDescriptor.NoDueSegmentFilter.PENDING -> tasks.noDueSummary.pendingTasks
            }
        }
    }

    fun resolveFocus(
        report: UnifiedStatisticsReport,
        descriptor: FocusDrilldownDescriptor,
    ): List<FocusSession> {
        val focus = report.focus ?: return emptyList()
        return when (descriptor) {
            FocusDrilldownDescriptor.None -> emptyList()
            FocusDrilldownDescriptor.AllSections -> focus.sessions
            is FocusDrilldownDescriptor.TargetType -> focus.targetTypeSummaries.firstOrNull {
                it.targetType == descriptor.type
            }?.sessions.orEmpty()
            is FocusDrilldownDescriptor.DaySessions -> {
                focus.dailyBuckets.firstOrNull { it.date == descriptor.date }?.sessions.orEmpty()
            }
            is FocusDrilldownDescriptor.SessionsRange -> {
                focus.dailyBuckets
                    .filter { it.date >= descriptor.from && it.date <= descriptor.to }
                    .flatMap { it.sessions }
            }
            is FocusDrilldownDescriptor.SectionBranch -> {
                focus.sectionBreakdowns.firstOrNull {
                    it.category == descriptor.category && it.containerId == descriptor.containerId
                }?.sessions.orEmpty()
            }
            is FocusDrilldownDescriptor.TargetObject -> {
                val targetSessions = focus.targetBreakdowns.firstOrNull {
                    it.primaryTargetType == descriptor.primaryTargetType && it.targetId == descriptor.targetId
                }?.sessions.orEmpty()
                if (descriptor.sectionCategory != null) {
                    val branchSessions = focus.sectionBreakdowns.firstOrNull {
                        it.category == descriptor.sectionCategory && it.containerId == descriptor.sectionContainerId
                    }?.sessions.orEmpty().toSet()
                    targetSessions.filter { it in branchSessions }
                } else {
                    targetSessions
                }
            }
            is FocusDrilldownDescriptor.CalendarDay -> {
                focus.sessions.filter { session ->
                    org.isoron.uhabits.activities.tasks.FocusHistoryProjector.projectSessionToDays(session)
                        .any { it.day == descriptor.date }
                }
            }
        }
    }

    fun resolveHabitCycles(
        report: UnifiedStatisticsReport,
        descriptor: HabitsDrilldownDescriptor,
    ): List<HabitCycle> {
        val habits = report.habits ?: return emptyList()
        return when (descriptor) {
            HabitsDrilldownDescriptor.None -> emptyList()
            is HabitsDrilldownDescriptor.CadenceCycles -> when (descriptor.cadence) {
                HabitCadence.DAILY -> habits.daily.cycles
                HabitCadence.WEEKLY -> habits.weekly.cycles
                HabitCadence.MONTHLY -> habits.monthly.cycles
                HabitCadence.UNSUPPORTED -> habits.unsupported.cycles
            }
            is HabitsDrilldownDescriptor.BucketCycles -> {
                habits.dateBuckets[descriptor.cadence]?.firstOrNull { it.date == descriptor.date }?.cycles.orEmpty()
                    .filter(::isHabitChartOutcome)
            }
            is HabitsDrilldownDescriptor.CadenceRangeCycles -> {
                habits.dateBuckets[descriptor.cadence].orEmpty()
                    .filter { it.date >= descriptor.from && it.date <= descriptor.to }
                    .flatMap { it.cycles }.filter(::isHabitChartOutcome)
            }
            HabitsDrilldownDescriptor.OngoingCycles -> {
                habits.ongoingCycles
            }
            is HabitsDrilldownDescriptor.HabitCycles -> {
                habits.allCycles.filter { it.habitUuid == descriptor.habitUuid }
            }
            is HabitsDrilldownDescriptor.AllHabitsList -> emptyList()
        }
    }

    fun resolveHabitSummaries(
        report: UnifiedStatisticsReport,
        query: String = "",
    ): List<HabitSummary> {
        val habits = report.habits ?: return emptyList()
        val summaries = habits.habitSummaries.distinctBy { it.habitUuid }.sortedBy { it.habitName.lowercase() }
        if (query.isBlank()) return summaries
        return summaries.filter { it.habitName.contains(query, ignoreCase = true) }
    }
}

/** Snapshot dictionary of resolved names for entities in the active dataset off-main. */
data class StatisticsEntityNameSnapshot(
    val taskTitles: Map<String, String> = emptyMap(),
    val habitNames: Map<String, String> = emptyMap(),
    val containerNames: Map<String, String> = emptyMap(),
) {
    fun resolveTaskName(id: String?, isRussian: Boolean = false): String =
        if (id == null) {
            if (isRussian) "Без задачи" else "No task"
        } else {
            taskTitles[id] ?: if (isRussian) "Удалённая задача (${id.take(8)})" else "Deleted task (${id.take(8)})"
        }

    fun resolveHabitName(uuid: String?, isRussian: Boolean = false): String =
        if (uuid == null) {
            if (isRussian) "Без привычки" else "No habit"
        } else {
            habitNames[uuid] ?: if (isRussian) "Удалённая привычка (${uuid.take(8)})" else "Deleted habit (${uuid.take(8)})"
        }

    fun resolveContainerName(id: String?, isRussian: Boolean = false): String =
        if (id == null) {
            if (isRussian) "Без раздела" else "No section"
        } else {
            containerNames[id] ?: if (isRussian) "Удалённый раздел (${id.take(8)})" else "Deleted section (${id.take(8)})"
        }
}

/** Pure representation of a segment within an interactive bar in DiscreteColumnBarChart. */
data class ChartColumnSegment(
    val value: Long,
    val color: Int,
    val label: String = "",
)

/** Pure representation of a single interactive bar in DiscreteColumnBarChart. */
data class ChartColumnItem(
    val key: String,
    val value: Long,
    val formattedValue: String,
    val label: String,
    val isToday: Boolean = false,
    val isSelected: Boolean = false,
    val contentDescription: String,
    val descriptor: Any? = null,
    val segments: List<ChartColumnSegment> = emptyList(),
)

/** Bucketing builder for DiscreteColumnBarChart across periods. */
object DiscreteChartBucketer {

    private val RU_MONTHS = listOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")
    private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun buildTaskColumns(
        period: UnifiedPeriod,
        result: TaskStatisticsResult,
        today: LocalDate,
        isRussian: Boolean = false,
    ): List<ChartColumnItem> {
        val buckets = result.dailyCompletedBuckets
        if (buckets.isEmpty()) return emptyList()

        return when (period) {
            UnifiedPeriod.DAY, UnifiedPeriod.WEEK, UnifiedPeriod.MONTH -> {
                buckets.map { b ->
                    val dayLabel = b.date.day.toString()
                    val valStr = b.count.toString()
                    val desc = if (isRussian) "${b.date}: $valStr задач" else "${b.date}: $valStr tasks"
                    ChartColumnItem(
                        key = b.date.toString(),
                        value = b.count.toLong(),
                        formattedValue = valStr,
                        label = dayLabel,
                        isToday = b.date == today,
                        contentDescription = desc,
                        descriptor = TasksDrilldownDescriptor.CompletedDay(b.date),
                    )
                }
            }

            UnifiedPeriod.YEAR -> {
                // Group 365/366 days into 12 calendar months
                val months = buckets.groupBy { it.date.month }
                (1..12).map { monthNum ->
                    val monthBuckets = months[monthNum].orEmpty()
                    val count = monthBuckets.sumOf { it.count }
                    val monthName = if (isRussian) RU_MONTHS[monthNum - 1] else EN_MONTHS[monthNum - 1]
                    val fromDate = monthBuckets.firstOrNull()?.date ?: LocalDate(today.year, monthNum, 1)
                    val toDate = monthBuckets.lastOrNull()?.date ?: LocalDate(today.year, monthNum, fromDate.monthLength)
                    val desc = if (isRussian) "$monthName: $count задач" else "$monthName: $count tasks"
                    ChartColumnItem(
                        key = "$monthNum",
                        value = count.toLong(),
                        formattedValue = count.toString(),
                        label = monthName,
                        isToday = today.year == fromDate.year && today.month == monthNum,
                        contentDescription = desc,
                        descriptor = TasksDrilldownDescriptor.CompletedRange(fromDate, toDate, monthName),
                    )
                }
            }

            UnifiedPeriod.ALL -> {
                val yearMonths = buckets.groupBy { Pair(it.date.year, it.date.month) }
                val keys = yearMonths.keys.sortedWith(compareBy({ it.first }, { it.second }))
                val years = buckets.map { it.date.year }.distinct().sorted()
                if (keys.size <= 24) {
                    keys.map { ym ->
                        val (y, m) = ym
                        val ymBuckets = yearMonths[ym].orEmpty()
                        val count = ymBuckets.sumOf { it.count }
                        val mName = if (isRussian) RU_MONTHS[m - 1] else EN_MONTHS[m - 1]
                        val label = "$mName '${y % 100}"
                        val fromDate = ymBuckets.first().date
                        val toDate = ymBuckets.last().date
                        val desc = if (isRussian) "$label: $count задач" else "$label: $count tasks"
                        ChartColumnItem(
                            key = "${y}_$m",
                            value = count.toLong(),
                            formattedValue = count.toString(),
                            label = label,
                            isToday = today.year == y && today.month == m,
                            contentDescription = desc,
                            descriptor = TasksDrilldownDescriptor.CompletedRange(fromDate, toDate, label),
                        )
                    }
                } else if (years.size <= 24) {
                    val byYear = buckets.groupBy { it.date.year }
                    byYear.keys.sorted().map { y ->
                        val yBuckets = byYear[y].orEmpty()
                        val count = yBuckets.sumOf { it.count }
                        val label = "$y"
                        val fromDate = yBuckets.first().date
                        val toDate = yBuckets.last().date
                        val desc = if (isRussian) "$y год: $count задач" else "Year $y: $count tasks"
                        ChartColumnItem(
                            key = "$y",
                            value = count.toLong(),
                            formattedValue = count.toString(),
                            label = label,
                            isToday = today.year == y,
                            contentDescription = desc,
                            descriptor = TasksDrilldownDescriptor.CompletedRange(fromDate, toDate, label),
                        )
                    }
                } else {
                    val chunkStep = (years.size + 23) / 24
                    years.chunked(chunkStep).map { chunkYears ->
                        val chunkBuckets = buckets.filter { it.date.year in chunkYears }
                        val count = chunkBuckets.sumOf { it.count }
                        val firstYear = chunkYears.first()
                        val lastYear = chunkYears.last()
                        val label = if (firstYear == lastYear) "$firstYear" else "'${firstYear % 100}-'${lastYear % 100}"
                        val fromDate = chunkBuckets.first().date
                        val toDate = chunkBuckets.last().date
                        val desc = "$label: $count"
                        ChartColumnItem(
                            key = "${firstYear}_$lastYear",
                            value = count.toLong(),
                            formattedValue = count.toString(),
                            label = label,
                            isToday = today.year in chunkYears,
                            contentDescription = desc,
                            descriptor = TasksDrilldownDescriptor.CompletedRange(fromDate, toDate, label),
                        )
                    }
                }
            }
        }
    }

    fun buildFocusColumns(
        period: UnifiedPeriod,
        result: FocusStatisticsResult,
        today: LocalDate,
        isRussian: Boolean = false,
    ): List<ChartColumnItem> {
        val buckets = result.dailyBuckets
        if (buckets.isEmpty()) return emptyList()

        return when (period) {
            UnifiedPeriod.DAY, UnifiedPeriod.WEEK, UnifiedPeriod.MONTH -> {
                buckets.map { b ->
                    val dayLabel = b.date.day.toString()
                    val valStr = UnifiedStatisticsStateUtils.formatExactDurationMs(b.durationMs, isRussian)
                    val desc = "${b.date}: $valStr"
                    ChartColumnItem(
                        key = b.date.toString(),
                        value = b.durationMs,
                        formattedValue = valStr,
                        label = dayLabel,
                        isToday = b.date == today,
                        contentDescription = desc,
                        descriptor = FocusDrilldownDescriptor.DaySessions(b.date),
                    )
                }
            }

            UnifiedPeriod.YEAR -> {
                val months = buckets.groupBy { it.date.month }
                (1..12).map { monthNum ->
                    val monthBuckets = months[monthNum].orEmpty()
                    val totalDuration = monthBuckets.sumOf { it.durationMs }
                    val valStr = UnifiedStatisticsStateUtils.formatExactDurationMs(totalDuration, isRussian)
                    val monthName = if (isRussian) RU_MONTHS[monthNum - 1] else EN_MONTHS[monthNum - 1]
                    val fromDate = monthBuckets.firstOrNull()?.date ?: LocalDate(today.year, monthNum, 1)
                    val toDate = monthBuckets.lastOrNull()?.date ?: LocalDate(today.year, monthNum, fromDate.monthLength)
                    val desc = "$monthName: $valStr"
                    ChartColumnItem(
                        key = "$monthNum",
                        value = totalDuration,
                        formattedValue = valStr,
                        label = monthName,
                        isToday = today.year == fromDate.year && today.month == monthNum,
                        contentDescription = desc,
                        descriptor = FocusDrilldownDescriptor.SessionsRange(fromDate, toDate, monthName),
                    )
                }
            }

            UnifiedPeriod.ALL -> {
                val yearMonths = buckets.groupBy { Pair(it.date.year, it.date.month) }
                val keys = yearMonths.keys.sortedWith(compareBy({ it.first }, { it.second }))
                val years = buckets.map { it.date.year }.distinct().sorted()
                if (keys.size <= 24) {
                    keys.map { ym ->
                        val (y, m) = ym
                        val ymBuckets = yearMonths[ym].orEmpty()
                        val totalDuration = ymBuckets.sumOf { it.durationMs }
                        val valStr = UnifiedStatisticsStateUtils.formatExactDurationMs(totalDuration, isRussian)
                        val mName = if (isRussian) RU_MONTHS[m - 1] else EN_MONTHS[m - 1]
                        val label = "$mName '${y % 100}"
                        val fromDate = ymBuckets.first().date
                        val toDate = ymBuckets.last().date
                        val desc = "$label: $valStr"
                        ChartColumnItem(
                            key = "${y}_$m",
                            value = totalDuration,
                            formattedValue = valStr,
                            label = label,
                            isToday = today.year == y && today.month == m,
                            contentDescription = desc,
                            descriptor = FocusDrilldownDescriptor.SessionsRange(fromDate, toDate, label),
                        )
                    }
                } else if (years.size <= 24) {
                    val byYear = buckets.groupBy { it.date.year }
                    byYear.keys.sorted().map { y ->
                        val yBuckets = byYear[y].orEmpty()
                        val totalDuration = yBuckets.sumOf { it.durationMs }
                        val valStr = UnifiedStatisticsStateUtils.formatExactDurationMs(totalDuration, isRussian)
                        val label = "$y"
                        val fromDate = yBuckets.first().date
                        val toDate = yBuckets.last().date
                        val desc = if (isRussian) "$y год: $valStr" else "Year $y: $valStr"
                        ChartColumnItem(
                            key = "$y",
                            value = totalDuration,
                            formattedValue = valStr,
                            label = label,
                            isToday = today.year == y,
                            contentDescription = desc,
                            descriptor = FocusDrilldownDescriptor.SessionsRange(fromDate, toDate, label),
                        )
                    }
                } else {
                    // Cap ALL yearly grouping when > 24 years by grouping into chunks of N years
                    val chunkStep = (years.size + 23) / 24
                    years.chunked(chunkStep).map { chunkYears ->
                        val chunkBuckets = buckets.filter { it.date.year in chunkYears }
                        val totalDuration = chunkBuckets.sumOf { it.durationMs }
                        val valStr = UnifiedStatisticsStateUtils.formatExactDurationMs(totalDuration, isRussian)
                        val firstYear = chunkYears.first()
                        val lastYear = chunkYears.last()
                        val label = if (firstYear == lastYear) "$firstYear" else "'${firstYear % 100}-'${lastYear % 100}"
                        val fromDate = chunkBuckets.first().date
                        val toDate = chunkBuckets.last().date
                        val desc = "$label: $valStr"
                        ChartColumnItem(
                            key = "${firstYear}_$lastYear",
                            value = totalDuration,
                            formattedValue = valStr,
                            label = label,
                            isToday = today.year in chunkYears,
                            contentDescription = desc,
                            descriptor = FocusDrilldownDescriptor.SessionsRange(fromDate, toDate, label),
                        )
                    }
                }
            }
        }
    }

    fun buildHabitColumns(
        period: UnifiedPeriod,
        cadence: HabitCadence,
        result: HabitCycleStatisticsResult,
        today: LocalDate,
        palette: SettingsThemePalette,
        isRussian: Boolean = false,
    ): List<ChartColumnItem> {
        val buckets = result.dateBuckets[cadence].orEmpty()
        if (buckets.isEmpty()) return emptyList()

        val successColor = 0xFF4CAF50.toInt()
        val errorColor = 0xFFE53935.toInt()
        val unknownColor = 0xFF9E9E9E.toInt()

        return when (period) {
            UnifiedPeriod.DAY, UnifiedPeriod.WEEK, UnifiedPeriod.MONTH -> {
                buckets.map { b ->
                    val s = b.satisfiedCount.toLong()
                    val f = b.notSatisfiedCount.toLong()
                    val u = b.unknownCount.toLong()
                    val total = s + f + u

                    val segments = buildList {
                        if (s > 0L) add(ChartColumnSegment(s, successColor, if (isRussian) "Достигнута" else "Satisfied"))
                        if (f > 0L) add(ChartColumnSegment(f, errorColor, if (isRussian) "Не достигнута" else "Not satisfied"))
                        if (u > 0L) add(ChartColumnSegment(u, unknownColor, if (isRussian) "Без данных" else "Without data"))
                    }

                    val formattedVal = if (total == 0L) {
                        ""
                    } else if (total == 1L) {
                        when {
                            s == 1L -> "S"
                            f == 1L -> "F"
                            u == 1L -> "U"
                            else -> "0"
                        }
                    } else {
                        "$total"
                    }

                    val dayLabel = b.date.day.toString()
                    val desc = if (total == 0L) {
                        "${formatStatisticsDate(b.date)}: ${if (isRussian) "Нет завершённых циклов" else "No closed cycles"}"
                    } else if (isRussian) {
                        "${formatStatisticsDate(b.date)}: Достигнуто $s, Не достигнуто $f, Без данных $u"
                    } else {
                        "${formatStatisticsDate(b.date)}: Satisfied $s, Not satisfied $f, Without data $u"
                    }

                    ChartColumnItem(
                        key = b.date.toString(),
                        value = total,
                        formattedValue = formattedVal,
                        label = dayLabel,
                        isToday = b.date == today,
                        contentDescription = desc,
                        descriptor = HabitsDrilldownDescriptor.BucketCycles(b.date, cadence, formatStatisticsDate(b.date)),
                        segments = segments,
                    )
                }
            }

            UnifiedPeriod.YEAR -> {
                val months = buckets.groupBy { it.date.month }
                val targetYear = result.context.from.year
                (1..12).map { monthNum ->
                    val monthBuckets = months[monthNum].orEmpty()
                    val s = monthBuckets.sumOf { it.satisfiedCount }.toLong()
                    val f = monthBuckets.sumOf { it.notSatisfiedCount }.toLong()
                    val u = monthBuckets.sumOf { it.unknownCount }.toLong()
                    val total = s + f + u

                    val segments = buildList {
                        if (s > 0L) add(ChartColumnSegment(s, successColor, if (isRussian) "Достигнута" else "Satisfied"))
                        if (f > 0L) add(ChartColumnSegment(f, errorColor, if (isRussian) "Не достигнута" else "Not satisfied"))
                        if (u > 0L) add(ChartColumnSegment(u, unknownColor, if (isRussian) "Без данных" else "Without data"))
                    }

                    val monthName = if (isRussian) RU_MONTHS[monthNum - 1] else EN_MONTHS[monthNum - 1]
                    val fromDate = LocalDate(targetYear, monthNum, 1)
                    val toDate = LocalDate(targetYear, monthNum, fromDate.monthLength)
                    val desc = if (total == 0L) {
                        if (isRussian) "$monthName: нет данных" else "$monthName: No data"
                    } else if (isRussian) {
                        "$monthName: Достигнуто $s, Не достигнуто $f, Без данных $u"
                    } else {
                        "$monthName: Satisfied $s, Not satisfied $f, Without data $u"
                    }

                    ChartColumnItem(
                        key = "$monthNum",
                        value = total,
                        formattedValue = if (total == 0L) "" else "$total",
                        label = monthName,
                        isToday = today.year == targetYear && today.month == monthNum,
                        contentDescription = desc,
                        descriptor = HabitsDrilldownDescriptor.CadenceRangeCycles(cadence, fromDate, toDate, monthName),
                        segments = segments,
                    )
                }
            }

            UnifiedPeriod.ALL -> {
                val yearMonths = buckets.groupBy { Pair(it.date.year, it.date.month) }
                val keys = yearMonths.keys.sortedWith(compareBy({ it.first }, { it.second }))
                val years = buckets.map { it.date.year }.distinct().sorted()

                if (keys.size <= 24) {
                    keys.map { ym ->
                        val (y, m) = ym
                        val ymBuckets = yearMonths[ym].orEmpty()
                        val s = ymBuckets.sumOf { it.satisfiedCount }.toLong()
                        val f = ymBuckets.sumOf { it.notSatisfiedCount }.toLong()
                        val u = ymBuckets.sumOf { it.unknownCount }.toLong()
                        val total = s + f + u

                        val segments = buildList {
                            if (s > 0L) add(ChartColumnSegment(s, successColor, if (isRussian) "Достигнута" else "Satisfied"))
                            if (f > 0L) add(ChartColumnSegment(f, errorColor, if (isRussian) "Не достигнута" else "Not satisfied"))
                            if (u > 0L) add(ChartColumnSegment(u, unknownColor, if (isRussian) "Без данных" else "Without data"))
                        }

                        val mName = if (isRussian) RU_MONTHS[m - 1] else EN_MONTHS[m - 1]
                        val label = "$mName '${y % 100}"
                        val fromDate = ymBuckets.first().date
                        val toDate = ymBuckets.last().date
                        val desc = if (isRussian) {
                            "$label: Достигнуто $s, Не достигнуто $f, Без данных $u"
                        } else {
                            "$label: Satisfied $s, Not satisfied $f, Without data $u"
                        }

                        ChartColumnItem(
                            key = "${y}_$m",
                            value = total,
                            formattedValue = if (total == 0L) "" else "$total",
                            label = label,
                            isToday = today.year == y && today.month == m,
                            contentDescription = desc,
                            descriptor = HabitsDrilldownDescriptor.CadenceRangeCycles(cadence, fromDate, toDate, label),
                            segments = segments,
                        )
                    }
                } else if (years.size <= 24) {
                    val byYear = buckets.groupBy { it.date.year }
                    byYear.keys.sorted().map { y ->
                        val yBuckets = byYear[y].orEmpty()
                        val s = yBuckets.sumOf { it.satisfiedCount }.toLong()
                        val f = yBuckets.sumOf { it.notSatisfiedCount }.toLong()
                        val u = yBuckets.sumOf { it.unknownCount }.toLong()
                        val total = s + f + u

                        val segments = buildList {
                            if (s > 0L) add(ChartColumnSegment(s, successColor, if (isRussian) "Достигнута" else "Satisfied"))
                            if (f > 0L) add(ChartColumnSegment(f, errorColor, if (isRussian) "Не достигнута" else "Not satisfied"))
                            if (u > 0L) add(ChartColumnSegment(u, unknownColor, if (isRussian) "Без данных" else "Without data"))
                        }

                        val label = "$y"
                        val fromDate = yBuckets.first().date
                        val toDate = yBuckets.last().date
                        val desc = if (isRussian) {
                            "$y год: Достигнуто $s, Не достигнуто $f, Без данных $u"
                        } else {
                            "Year $y: Satisfied $s, Not satisfied $f, Without data $u"
                        }

                        ChartColumnItem(
                            key = "$y",
                            value = total,
                            formattedValue = if (total == 0L) "" else "$total",
                            label = label,
                            isToday = today.year == y,
                            contentDescription = desc,
                            descriptor = HabitsDrilldownDescriptor.CadenceRangeCycles(cadence, fromDate, toDate, label),
                            segments = segments,
                        )
                    }
                } else {
                    val chunkStep = (years.size + 23) / 24
                    years.chunked(chunkStep).map { chunkYears ->
                        val chunkBuckets = buckets.filter { it.date.year in chunkYears }
                        val s = chunkBuckets.sumOf { it.satisfiedCount }.toLong()
                        val f = chunkBuckets.sumOf { it.notSatisfiedCount }.toLong()
                        val u = chunkBuckets.sumOf { it.unknownCount }.toLong()
                        val total = s + f + u

                        val segments = buildList {
                            if (s > 0L) add(ChartColumnSegment(s, successColor, if (isRussian) "Достигнута" else "Satisfied"))
                            if (f > 0L) add(ChartColumnSegment(f, errorColor, if (isRussian) "Не достигнута" else "Not satisfied"))
                            if (u > 0L) add(ChartColumnSegment(u, unknownColor, if (isRussian) "Без данных" else "Without data"))
                        }

                        val firstYear = chunkYears.first()
                        val lastYear = chunkYears.last()
                        val label = if (firstYear == lastYear) "$firstYear" else "'${firstYear % 100}-'${lastYear % 100}"
                        val fromDate = chunkBuckets.first().date
                        val toDate = chunkBuckets.last().date
                        val desc = "$label: $s / $total"

                        ChartColumnItem(
                            key = "${firstYear}_$lastYear",
                            value = total,
                            formattedValue = if (total == 0L) "" else "$total",
                            label = label,
                            isToday = today.year in chunkYears,
                            contentDescription = desc,
                            descriptor = HabitsDrilldownDescriptor.CadenceRangeCycles(cadence, fromDate, toDate, label),
                            segments = segments,
                        )
                    }
                }
            }
        }
    }
}
