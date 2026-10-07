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

package org.isoron.uhabits.core.containers.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.localDateAt
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.focus.FocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.userTasks.UserTaskRepository

/**
 * Service that builds Container-level activity reports across Focus work,
 * Task outcomes, and Habit progress.
 *
 * Distinct architectural principles:
 * - Focus work time is derived strictly from finalized FocusSession facts,
 *   governed by frozen historical container context (HistoricalContainerMatcher / HistoricalPathParser).
 * - Task metrics are outcome metrics, evaluated according to current container placement
 *   (TaskPlacementMode.CURRENT) as historical placement snapshots are not recorded on task completion.
 * - Habit metrics reuse canonical EffectiveHabitValueService and preserve goal semantics.
 * - Focus work duration and Contribution values are NEVER summed into a single work total.
 * - Queries are batched to avoid N+1 per-container/per-habit query loops.
 */
class ContainerStatisticsService(
    private val containerQueries: ContainerQueries,
    private val placementQueries: HabitPlacementQueries,
    private val habitList: HabitList,
    private val userTaskRepository: UserTaskRepository,
    private val focusSessionRepository: FocusSessionRepository,
    private val effectiveHabitValueService: EffectiveHabitValueService,
    private val firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
    private val hourOffset: Int = 0,
    private val minuteOffset: Int = 0,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun getReport(
        containerId: ContainerId,
        range: ContainerStatsRange,
        scopeMode: ContainerScopeMode = ContainerScopeMode.SUBTREE,
    ): ContainerActivityReport {
        val container = containerQueries.find(containerId, includeDeleted = true)
            ?: throw IllegalArgumentException("Container not found: ${containerId.value}")
        val containerName = container.name

        val currentSubtreeIds = containerQueries.subtreeIds(containerId)
        val targetScopeContainerIds = when (scopeMode) {
            ContainerScopeMode.HERE -> setOf(containerId)
            ContainerScopeMode.SUBTREE -> setOf(containerId) + currentSubtreeIds
        }

        // Canonical range timestamp boundaries
        val startTs = startOfLocalDay(range.from, hourOffset, minuteOffset)
        val endTs = startOfLocalDay(range.to.plus(1), hourOffset, minuteOffset)

        // --------------------------------------------------------------------
        // 1. Focus statistics (Historical placement invariance)
        // --------------------------------------------------------------------
        val sessionsInRange = focusSessionRepository.getByTimeRange(startTs, endTs)
        var totalDurationMs = 0L
        var sessionCount = 0
        var directDurationMs = 0L
        var descendantDurationMs = 0L
        val dailyDurationMap = mutableMapOf<LocalDate, Long>()
        val dailyCountMap = mutableMapOf<LocalDate, Int>()
        val childDurationMap = mutableMapOf<ContainerId, Pair<String, Long>>()
        val childCountMap = mutableMapOf<ContainerId, Int>()

        for (session in sessionsInRange) {
            if (session.durationMs <= 0) continue

            val match = HistoricalPathParser.matchSession(
                session = session,
                targetContainer = containerId,
                currentSubtreeIds = currentSubtreeIds,
                containerQueries = containerQueries,
            ) ?: continue

            val qualifies = when (scopeMode) {
                ContainerScopeMode.HERE -> match.isDirect
                ContainerScopeMode.SUBTREE -> match.isDirect || match.isDescendant
            }
            if (!qualifies) continue

            sessionCount++
            totalDurationMs += session.durationMs

            if (match.isDirect) {
                directDurationMs += session.durationMs
            } else if (match.isDescendant) {
                descendantDurationMs += session.durationMs
                val branch = match.immediateChildBranch
                if (branch != null) {
                    val branchName = branch.name
                        ?: containerQueries.find(branch.id, includeDeleted = true)?.name
                        ?: branch.id.value
                    val currentVal = childDurationMap[branch.id]?.second ?: 0L
                    childDurationMap[branch.id] = branchName to (currentVal + session.durationMs)
                    childCountMap[branch.id] = (childCountMap[branch.id] ?: 0) + 1
                }
            }

            val sessionDate = localDateAt(session.endedAt, hourOffset, minuteOffset)
            if (sessionDate in range.from..range.to) {
                dailyDurationMap[sessionDate] = (dailyDurationMap[sessionDate] ?: 0L) + session.durationMs
                dailyCountMap[sessionDate] = (dailyCountMap[sessionDate] ?: 0) + 1
            }
        }

        val dailyBuckets = mutableListOf<FocusDailyBucket>()
        var curDate = range.from
        while (curDate <= range.to) {
            dailyBuckets.add(
                FocusDailyBucket(
                    date = curDate,
                    durationMs = dailyDurationMap[curDate] ?: 0L,
                    sessionCount = dailyCountMap[curDate] ?: 0,
                )
            )
            curDate = curDate.plus(1)
        }

        val childBreakdown = childDurationMap.map { (cId, info) ->
            ChildContainerFocusBreakdown(
                containerId = cId,
                containerName = info.first,
                durationMs = info.second,
                sessionCount = childCountMap[cId] ?: 1,
            )
        }.sortedByDescending { it.durationMs }

        val focusSummary = FocusSummary(
            totalDurationMs = totalDurationMs,
            sessionCount = sessionCount,
            directDurationMs = directDurationMs,
            descendantDurationMs = descendantDurationMs,
            dailyBuckets = dailyBuckets,
            childBreakdown = childBreakdown,
        )

        // --------------------------------------------------------------------
        // 2. Task statistics (Current placement outcome metrics)
        // --------------------------------------------------------------------
        val allTasks = userTaskRepository.getAll()
        val scopeTasks = allTasks.filter { it.containerId != null && it.containerId in targetScopeContainerIds }
        val nowMillis = clock()

        val completedCount = scopeTasks.count { task ->
            task.isCompleted && task.completedAt != null && task.completedAt in startTs until endTs
        }
        val pendingCount = scopeTasks.count { !it.isCompleted }
        val overdueCount = scopeTasks.count { it.isHardOverdue(nowMillis) }

        val taskSummary = TaskSummary(
            completedCount = completedCount,
            pendingCount = pendingCount,
            overdueCount = overdueCount,
            placementMode = TaskPlacementMode.CURRENT,
        )

        // --------------------------------------------------------------------
        // 3. Habit statistics (Effective progress, no duration conversion)
        // --------------------------------------------------------------------
        val habitRefs = when (scopeMode) {
            ContainerScopeMode.HERE -> placementQueries.directHabits(containerId)
            ContainerScopeMode.SUBTREE -> placementQueries.subtreeHabits(containerId)
        }
        val habits: List<Habit> = habitRefs.mapNotNull { ref ->
            habitList.getByUUID(ref.uuid)?.takeUnless { it.isArchived }
        }

        val valuesByRef = if (habits.isNotEmpty()) {
            runCatching {
                effectiveHabitValueService.valuesForRange(habits, range.from, range.to)
            }.getOrDefault(emptyMap())
        } else emptyMap()

        var reachedCount = 0
        var incompleteCount = 0
        var unknownCount = 0
        val habitRows = mutableListOf<HabitActivityRow>()

        val rangeDates = mutableListOf<LocalDate>()
        var d = range.from
        while (d <= range.to) {
            rangeDates.add(d)
            d = d.plus(1)
        }

        for (habit in habits) {
            val ref = HabitRef(requireNotNull(habit.uuid))
            val effectiveVal = valuesByRef[ref]

            val goalResult = runCatching {
                effectiveHabitValueService.goalProgressOn(habit, range.to)
            }.getOrNull()

            val goalStatus = goalResult?.status ?: HabitGoalStatus.UNKNOWN
            when (goalStatus) {
                HabitGoalStatus.SATISFIED -> reachedCount++
                HabitGoalStatus.NOT_SATISFIED -> incompleteCount++
                HabitGoalStatus.UNKNOWN,
                HabitGoalStatus.SKIPPED,
                HabitGoalStatus.EXCLUDED -> unknownCount++
            }

            val completedDays = if (!habit.isNumerical) {
                rangeDates.count {
                    val entry = habit.originalEntries.get(it)
                    entry.value == Entry.YES_MANUAL || entry.value == Entry.YES_AUTO || habit.isCompletedOn(it)
                }
            } else 0

            habitRows.add(
                HabitActivityRow(
                    habitRef = ref,
                    name = habit.name,
                    isNumerical = habit.isNumerical,
                    goalStatus = goalStatus,
                    manualValue = effectiveVal?.manualValue,
                    contributionValue = effectiveVal?.contributionValue ?: 0L,
                    totalValue = effectiveVal?.totalValue,
                    unit = habit.unit,
                    targetValue = goalResult?.goal?.targetValue,
                    targetType = goalResult?.goal?.targetType,
                    completedDaysCount = completedDays,
                    totalDaysCount = rangeDates.size,
                )
            )
        }

        val habitSummary = HabitSummary(
            totalHabitCount = habits.size,
            reachedCount = reachedCount,
            incompleteCount = incompleteCount,
            unknownCount = unknownCount,
            habitRows = habitRows,
        )

        return ContainerActivityReport(
            containerId = containerId,
            containerName = containerName,
            range = range,
            scopeMode = scopeMode,
            focus = focusSummary,
            tasks = taskSummary,
            habits = habitSummary,
        )
    }
}
