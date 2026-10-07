/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

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
 * Planned date != deadline: A missed planned date is NOT hard overdue!
 */
enum class TodayTaskUrgency {
    /** Objective hard overdue: deadline < now. */
    HARD_OVERDUE,
    /** Missed plan: plannedDate < today && not hard overdue. Requires replanning. */
    MISSED_PLAN,
    /** Planned for today. */
    PLANNED_TODAY,
    /** Completed today. */
    COMPLETED_TODAY,
    /** Optional undated inbox item. */
    INBOX_UNDATED,
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
    /** Grouped by urgency subsections: Hard Overdue, Missed Plan, Planned Today. */
    SECTIONED,
    /** Flat list sorted deterministically by urgency. */
    UNIFIED_LIST,
}

data class TodayScreenData(
    val todayDate: LocalDate,
    val nowMillis: Long,
    val hardOverdueTasks: List<TodayTaskItem>,
    val missedPlanTasks: List<TodayTaskItem>,
    val plannedTodayTasks: List<TodayTaskItem>,
    val completedTodayTasks: List<TodayTaskItem>,
    val undatedInboxTasks: List<TodayTaskItem>,
    val booleanHabits: List<TodayHabitItem>,
    val numericalHabits: List<TodayHabitItem>,
    val activeFocus: TodayFocusItem? = null,
) {
    val totalActionableTasksCount: Int
        get() = hardOverdueTasks.size + missedPlanTasks.size + plannedTodayTasks.size

    val allPendingTasksOrdered: List<TodayTaskItem>
        get() = hardOverdueTasks + missedPlanTasks + plannedTodayTasks

    val allHabitsOrdered: List<TodayHabitItem>
        get() = booleanHabits + numericalHabits

    val isEmpty: Boolean
        get() = totalActionableTasksCount == 0 && allHabitsOrdered.isEmpty() && activeFocus == null
}
