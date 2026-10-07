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
import org.isoron.platform.time.localDateAt
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import kotlin.math.roundToInt

class TodayEvaluationService(
    private val userTaskRepository: UserTaskRepository,
    private val habitList: HabitList,
    private val effectiveHabitValueService: EffectiveHabitValueService,
    private val containerQueries: ContainerQueries? = null,
    private val placementQueries: HabitPlacementQueries? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val hourOffset: Int = 0,
    private val minuteOffset: Int = 0,
) {

    fun evaluateToday(activeFocus: TodayFocusItem? = null): TodayScreenData {
        val nowMillis = clock()
        val today = localDateAt(nowMillis, hourOffset, minuteOffset)
        val startOfToday = startOfLocalDay(today, hourOffset, minuteOffset)
        val endOfToday = startOfLocalDay(today.plus(1), hourOffset, minuteOffset)

        // --------------------------------------------------------------------
        // 1. Tasks section (bounded: exactly 1 database query)
        // --------------------------------------------------------------------
        val allTasks = userTaskRepository.getAll()
        val subtaskMap = mutableMapOf<TaskId, Pair<Int, Int>>()
        for (task in allTasks) {
            val parentId = task.parentTaskId ?: continue
            val current = subtaskMap[parentId] ?: (0 to 0)
            val completed = if (task.isCompleted) current.first + 1 else current.first
            subtaskMap[parentId] = completed to (current.second + 1)
        }

        val rootTasks = allTasks.filter { it.parentTaskId == null }

        // Container name resolver cache
        val containerNameCache = mutableMapOf<ContainerId, String>()
        fun resolveContainerName(id: ContainerId?): String? {
            if (id == null) return null
            return containerNameCache.getOrPut(id) {
                containerQueries?.find(id, includeDeleted = true)?.name ?: id.value
            }
        }

        fun toTaskItem(task: UserTask, urgency: TodayTaskUrgency): TodayTaskItem {
            val subcounts = subtaskMap[task.id] ?: (0 to 0)
            return TodayTaskItem(
                task = task,
                urgency = urgency,
                containerName = resolveContainerName(task.containerId),
                containerId = task.containerId,
                subtasksCompleted = subcounts.first,
                subtasksTotal = subcounts.second,
            )
        }

        // A. Hard overdue tasks: deadline < nowMillis && !isCompleted
        val hardOverdue = rootTasks
            .filter { it.isHardOverdue(nowMillis) }
            .sortedWith(compareBy<UserTask> { it.deadline ?: Long.MAX_VALUE }.thenBy { it.createdAt })
            .map { toTaskItem(it, TodayTaskUrgency.HARD_OVERDUE) }

        // B. Missed plan tasks: plannedDate < today && !isCompleted && !isHardOverdue
        // Invariant: plannedDate != deadline. Missed plan is NOT hard overdue!
        val missedPlan = rootTasks
            .filter { it.isMissedPlan(today, nowMillis) }
            .sortedWith(compareBy<UserTask> { it.plannedDate?.daysSince2000 ?: Int.MAX_VALUE }.thenBy { it.createdAt })
            .map { toTaskItem(it, TodayTaskUrgency.MISSED_PLAN) }

        // C. Planned today tasks: plannedDate == today && !isCompleted && !isHardOverdue
        val plannedToday = rootTasks
            .filter { !it.isCompleted && !it.isHardOverdue(nowMillis) && it.plannedDate == today }
            .sortedWith(compareBy<UserTask> { it.sortOrder }.thenBy { it.createdAt })
            .map { toTaskItem(it, TodayTaskUrgency.PLANNED_TODAY) }

        // Completed today tasks: isCompleted && completedAt in [startOfToday, endOfToday)
        val completedToday = rootTasks
            .filter { it.isCompleted && it.completedAt != null && it.completedAt in startOfToday until endOfToday }
            .sortedByDescending { it.completedAt }
            .map { toTaskItem(it, TodayTaskUrgency.COMPLETED_TODAY) }

        // Optional undated inbox tasks: root, incomplete, no container, no dates
        val undatedInbox = rootTasks
            .filter { !it.isCompleted && it.containerId == null && it.plannedDate == null && it.deadline == null }
            .sortedWith(compareBy<UserTask> { it.sortOrder }.thenBy { it.createdAt })
            .map { toTaskItem(it, TodayTaskUrgency.INBOX_UNDATED) }

        // --------------------------------------------------------------------
        // 2. Habits section (bounded: batched query, no O(Habits x days x tasks))
        // --------------------------------------------------------------------
        val activeHabits = habitList.filter { !it.isArchived }
        val numericalActive = activeHabits.filter { it.isNumerical }
        val booleanActive = activeHabits.filter { !it.isNumerical }

        // Batch query numerical values for today
        val valuesByRef = if (numericalActive.isNotEmpty()) {
            runCatching {
                effectiveHabitValueService.valuesForRange(numericalActive, today, today)
            }.getOrDefault(emptyMap())
        } else emptyMap()

        // Cache habit container placements
        val habitContainerMap = mutableMapOf<String, Pair<ContainerId?, String?>>()
        for (habit in activeHabits) {
            val uuid = habit.uuid ?: continue
            val ref = HabitRef(uuid)
            val containerId = placementQueries?.current(ref)?.containerId
            val name = resolveContainerName(containerId)
            habitContainerMap[uuid] = containerId to name
        }

        // Process boolean habits
        val booleanItems = booleanActive.map { habit ->
            val entry = habit.originalEntries.get(today)
            val placement = habit.uuid?.let { habitContainerMap[it] }
            TodayHabitItem(
                habit = habit,
                isNumerical = false,
                todayEntryValue = entry.value,
                todayNotes = entry.notes ?: "",
                goalStatus = when (entry.value) {
                    Entry.YES_MANUAL, Entry.YES_AUTO -> HabitGoalStatus.SATISFIED
                    Entry.SKIP -> HabitGoalStatus.SKIPPED
                    Entry.NO -> HabitGoalStatus.NOT_SATISFIED
                    else -> HabitGoalStatus.UNKNOWN
                },
                progressFormatted = when (entry.value) {
                    Entry.YES_MANUAL, Entry.YES_AUTO -> "Выполнено"
                    Entry.SKIP -> "Пропущено"
                    Entry.NO -> "Не выполнено"
                    else -> "Ожидает отметки"
                },
                unit = "",
                containerName = placement?.second,
                containerId = placement?.first,
            )
        }

        // Process numerical habits with EffectiveHabitValueService
        val numericalItems = numericalActive.map { habit ->
            val ref = HabitRef(requireNotNull(habit.uuid))
            val effectiveVal = valuesByRef[ref]
            val goalResult = runCatching {
                effectiveHabitValueService.goalProgressOn(habit, today)
            }.getOrNull()

            val entry = habit.originalEntries.get(today)
            val placement = habit.uuid?.let { habitContainerMap[it] }

            val status = goalResult?.status ?: HabitGoalStatus.UNKNOWN
            val targetVal = goalResult?.goal?.targetValue
            val targetType = goalResult?.goal?.targetType
            val denominator = goalResult?.goal?.frequency?.denominator ?: 1

            val currentPeriodVal = goalResult?.value?.totalValue
            val manualVal = goalResult?.value?.manualValue
            val contribVal = goalResult?.value?.contributionValue ?: 0L

            val progressStr = formatNumericalProgress(
                currentPeriodTotal = currentPeriodVal,
                targetValue = targetVal,
                targetType = targetType,
                unit = habit.unit,
                denominator = denominator,
                status = status,
            )

            TodayHabitItem(
                habit = habit,
                isNumerical = true,
                todayEntryValue = entry.value,
                todayNotes = entry.notes ?: "",
                goalStatus = status,
                progressFormatted = progressStr,
                manualValue = manualVal,
                contributionValue = contribVal,
                totalValue = currentPeriodVal,
                targetValue = targetVal,
                targetType = targetType,
                unit = habit.unit,
                containerName = placement?.second,
                containerId = placement?.first,
            )
        }

        return TodayScreenData(
            todayDate = today,
            nowMillis = nowMillis,
            hardOverdueTasks = hardOverdue,
            missedPlanTasks = missedPlan,
            plannedTodayTasks = plannedToday,
            completedTodayTasks = completedToday,
            undatedInboxTasks = undatedInbox,
            booleanHabits = booleanItems,
            numericalHabits = numericalItems,
            activeFocus = activeFocus,
        )
    }

    private fun formatNumericalProgress(
        currentPeriodTotal: Long?,
        targetValue: Double?,
        targetType: NumericalHabitType?,
        unit: String,
        denominator: Int,
        status: HabitGoalStatus,
    ): String {
        val totalDouble = (currentPeriodTotal ?: 0L) / 1000.0
        val targetDouble = targetValue ?: 0.0

        val formattedTotal = formatDouble(totalDouble)
        val formattedTarget = formatDouble(targetDouble)

        val periodSuffix = when (denominator) {
            7 -> "на этой неделе"
            30 -> "в этом месяце"
            else -> "сегодня"
        }

        val prefix = if (targetType == NumericalHabitType.AT_MOST) "макс. " else ""
        val unitStr = if (unit.isNotBlank()) " $unit" else ""

        return when {
            currentPeriodTotal == null && status == HabitGoalStatus.UNKNOWN ->
                "Цель: $prefix$formattedTarget$unitStr ($periodSuffix)"
            status == HabitGoalStatus.SKIPPED ->
                "Пропущено ($periodSuffix)"
            else ->
                "$formattedTotal / $prefix$formattedTarget$unitStr ($periodSuffix)"
        }
    }

    private fun formatDouble(v: Double): String {
        return if (v % 1.0 == 0.0) {
            v.toLong().toString()
        } else {
            // Keep at most 2 decimal places cleanly
            val rounded = (v * 100.0).roundToInt() / 100.0
            rounded.toString()
        }
    }
}
