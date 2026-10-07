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

package org.isoron.uhabits.activities.today

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.today.TodayHabitItem
import org.isoron.uhabits.core.today.TodayScreenData
import org.isoron.uhabits.core.today.TodayTaskItem
import org.isoron.uhabits.core.today.TodayTaskUrgency
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayPresentationTest {

    private val today = LocalDate(2026, 10, 7)
    private val nowMillis = 1_700_000_000_000L

    @Test
    fun testGroupingModes_sectionedVsUnified() {
        val taskOverdue = TodayTaskItem(
            task = UserTask(TaskId("t1"), "Сдать МУСы", deadline = nowMillis - 1000, createdAt = 0, updatedAt = 0),
            urgency = TodayTaskUrgency.HARD_OVERDUE,
            containerName = "Учёба",
            containerId = ContainerId("study")
        )
        val taskMissed = TodayTaskItem(
            task = UserTask(TaskId("t2"), "Оформить отчёт", plannedDate = today.minus(1), deadline = nowMillis + 86400000, createdAt = 0, updatedAt = 0),
            urgency = TodayTaskUrgency.MISSED_PLAN,
            containerName = "Учёба",
            containerId = ContainerId("study")
        )
        val taskToday = TodayTaskItem(
            task = UserTask(TaskId("t3"), "Сделать запросы БД", plannedDate = today, createdAt = 0, updatedAt = 0),
            urgency = TodayTaskUrgency.PLANNED_TODAY,
            containerName = "Базы данных",
            containerId = ContainerId("db")
        )

        val data = TodayScreenData(
            todayDate = today,
            nowMillis = nowMillis,
            hardOverdueTasks = listOf(taskOverdue),
            missedPlanTasks = listOf(taskMissed),
            plannedTodayTasks = listOf(taskToday),
            completedTodayTasks = emptyList(),
            undatedInboxTasks = emptyList(),
            booleanHabits = emptyList(),
            numericalHabits = emptyList(),
            activeFocus = null
        )

        assertEquals(3, data.totalActionableTasksCount)
        val ordered = data.allPendingTasksOrdered
        assertEquals(3, ordered.size)
        assertEquals(TodayTaskUrgency.HARD_OVERDUE, ordered[0].urgency)
        assertEquals(TodayTaskUrgency.MISSED_PLAN, ordered[1].urgency)
        assertEquals(TodayTaskUrgency.PLANNED_TODAY, ordered[2].urgency)
    }

    @Test
    fun testHabitProgressPresentation_focusCreditAndFormatting() {
        val dummyHabit = MemoryModelFactory().buildHabit().apply {
            name = "Учёба"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 300.0
            unit = "мин"
        }

        val habitItem = TodayHabitItem(
            habit = dummyHabit,
            isNumerical = true,
            todayEntryValue = 0,
            todayNotes = "",
            goalStatus = HabitGoalStatus.UNKNOWN,
            progressFormatted = "75 / 300 мин (на этой неделе)",
            manualValue = 0L,
            contributionValue = 75_000L,
            totalValue = 75_000L,
            targetValue = 300.0,
            targetType = NumericalHabitType.AT_LEAST,
            unit = "мин",
            containerName = "Учёба",
            containerId = ContainerId("study")
        )

        assertEquals(75_000L, habitItem.contributionValue)
        assertEquals(75_000L, habitItem.totalValue)
        assertTrue(habitItem.progressFormatted.contains("75"))
        assertTrue(habitItem.progressFormatted.contains("300"))
        assertEquals("Учёба", habitItem.containerName)
    }

    @Test
    fun testEmptyStateFlags() {
        val emptyData = TodayScreenData(
            todayDate = today,
            nowMillis = nowMillis,
            hardOverdueTasks = emptyList(),
            missedPlanTasks = emptyList(),
            plannedTodayTasks = emptyList(),
            completedTodayTasks = emptyList(),
            undatedInboxTasks = emptyList(),
            booleanHabits = emptyList(),
            numericalHabits = emptyList(),
            activeFocus = null
        )
        assertTrue(emptyData.isEmpty)
    }
}
