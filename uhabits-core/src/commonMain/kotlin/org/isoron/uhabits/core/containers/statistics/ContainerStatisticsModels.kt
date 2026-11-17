/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.HabitGoalStatus

enum class ContainerScopeMode {
    /** Direct facts/content belonging to selected Container only. */
    HERE,
    /** Selected Container + all its descendants. */
    SUBTREE,
}

enum class ContainerStatsRangeType {
    DAY,
    WEEK,
    MONTH,
    CUSTOM,
}

data class ContainerStatsRange(
    val type: ContainerStatsRangeType,
    val from: LocalDate,
    val to: LocalDate,
    val label: String,
) {
    init {
        require(from <= to) { "Range start must not follow end: $from > $to" }
    }

    companion object {
        fun day(date: LocalDate): ContainerStatsRange =
            ContainerStatsRange(ContainerStatsRangeType.DAY, date, date, "День")

        fun week(anchorDate: LocalDate, firstWeekday: DayOfWeek): ContainerStatsRange {
            val from = anchorDate.startOfWeek(firstWeekday)
            val to = from.plus(6)
            return ContainerStatsRange(ContainerStatsRangeType.WEEK, from, to, "Неделя")
        }

        fun month(anchorDate: LocalDate): ContainerStatsRange {
            val from = anchorDate.startOfMonth()
            val to = from.plus(from.monthLength - 1)
            return ContainerStatsRange(ContainerStatsRangeType.MONTH, from, to, "Месяц")
        }

        fun custom(from: LocalDate, to: LocalDate, label: String = "Период"): ContainerStatsRange =
            ContainerStatsRange(ContainerStatsRangeType.CUSTOM, from, to, label)
    }
}

data class FocusDailyBucket(
    val date: LocalDate,
    val durationMs: Long,
    val sessionCount: Int,
)

data class ChildContainerFocusBreakdown(
    val containerId: ContainerId,
    val containerName: String,
    val durationMs: Long,
    val sessionCount: Int,
)

data class FocusSummary(
    val totalDurationMs: Long,
    val sessionCount: Int,
    val directDurationMs: Long,
    val descendantDurationMs: Long,
    val dailyBuckets: List<FocusDailyBucket>,
    val childBreakdown: List<ChildContainerFocusBreakdown>,
)

enum class TaskPlacementMode {
    /**
     * Tasks are evaluated according to their current container placement,
     * as historical placement snapshots are not recorded on task completion.
     */
    CURRENT,
}

data class TaskSummary(
    val completedCount: Int,
    val pendingCount: Int,
    val overdueCount: Int,
    val placementMode: TaskPlacementMode = TaskPlacementMode.CURRENT,
)

data class HabitActivityRow(
    val habitRef: HabitRef,
    val name: String,
    val isNumerical: Boolean,
    val goalStatus: HabitGoalStatus,
    val manualValue: Long?,
    val contributionValue: Long,
    val totalValue: Long?,
    val unit: String?,
    val targetValue: Double?,
    val targetType: NumericalHabitType?,
    val completedDaysCount: Int = 0,
    val totalDaysCount: Int = 0,
)

data class HabitSummary(
    val totalHabitCount: Int,
    val reachedCount: Int,
    val incompleteCount: Int,
    val unknownCount: Int,
    val habitRows: List<HabitActivityRow>,
)

data class ContainerActivityReport(
    val containerId: ContainerId,
    val containerName: String,
    val range: ContainerStatsRange,
    val scopeMode: ContainerScopeMode,
    val focus: FocusSummary,
    val tasks: TaskSummary,
    val habits: HabitSummary,
)
