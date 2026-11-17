/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.today

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask

/**
 * Categorization of task urgency for Today view.
 * Unified task due: Overdue vs Due Today.
 */
enum class TodayTaskUrgency {
    /** Objective overdue: due date/time has passed. */
    HARD_OVERDUE,
    /** Missed plan (deprecated, unified into overdue). */
    MISSED_PLAN,
    /** Planned / due for today. */
    PLANNED_TODAY,
    /** Completed today. */
    COMPLETED_TODAY,
    /** Optional undated inbox item. */
    INBOX_UNDATED;

    companion object {
        val OVERDUE = HARD_OVERDUE
        val DUE_TODAY = PLANNED_TODAY
    }
}

data class TodayTaskItem(
    val task: UserTask,
    val urgency: TodayTaskUrgency,
    val containerName: String?,
    val containerId: ContainerId?,
    val subtasksCompleted: Int = 0,
    val subtasksTotal: Int = 0,
)

data class TodayHabitItem(
    val habit: Habit,
    val isNumerical: Boolean,
    val todayEntryValue: Int,
    val todayNotes: String,
    val goalStatus: HabitGoalStatus,
    val progressFormatted: String,
    val manualValue: Long? = null,
    val contributionValue: Long = 0L,
    val totalValue: Long? = null,
    val targetValue: Double? = null,
    val targetType: NumericalHabitType? = null,
    val unit: String = "",
    val containerName: String? = null,
    val containerId: ContainerId? = null,
)

data class TodayFocusItem(
    val targetTitle: String,
    val mode: TimerMode,
    val elapsedMillis: Long,
    val displayTimeMillis: Long,
    val isRunning: Boolean,
    val isBreak: Boolean,
    val targetType: FocusPrimaryTargetType?,
    val taskId: TaskId?,
    val habitId: Long?,
)

enum class TodayGroupingMode {
    /** Grouped by urgency subsections: Hard Overdue, Planned Today. */
    SECTIONED,
    /** Flat list sorted deterministically by urgency. */
    UNIFIED_LIST,
}

data class TodayScreenData(
    val todayDate: LocalDate,
    val nowMillis: Long,
    val hardOverdueTasks: List<TodayTaskItem>,
    val plannedTodayTasks: List<TodayTaskItem>,
    val completedTodayTasks: List<TodayTaskItem>,
    val undatedInboxTasks: List<TodayTaskItem>,
    val booleanHabits: List<TodayHabitItem>,
    val numericalHabits: List<TodayHabitItem>,
    val activeFocus: TodayFocusItem? = null,
    val missedPlanTasks: List<TodayTaskItem> = emptyList(),
) {
    val overdueTasks: List<TodayTaskItem> get() = hardOverdueTasks
    val dueTodayTasks: List<TodayTaskItem> get() = plannedTodayTasks

    val totalActionableTasksCount: Int
        get() = hardOverdueTasks.size + missedPlanTasks.size + plannedTodayTasks.size

    val allPendingTasksOrdered: List<TodayTaskItem>
        get() = hardOverdueTasks + missedPlanTasks + plannedTodayTasks

    val allHabitsOrdered: List<TodayHabitItem>
        get() = booleanHabits + numericalHabits

    val isEmpty: Boolean
        get() = totalActionableTasksCount == 0 && allHabitsOrdered.isEmpty() && activeFocus == null
}
