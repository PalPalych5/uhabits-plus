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

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayEvaluationServiceTest {

    private val orgStore = MemoryOrganizationStore()
    private val modelFactory = MemoryModelFactory()
    private val habitList = modelFactory.buildHabitList()
    private val taskRepo = MemoryUserTaskRepository()
    private val contributionRepo = MemoryContributionRepository()
    private val habitService = EffectiveHabitValueService(
        repository = contributionRepo,
        hourOffset = 0,
        minuteOffset = 0,
        firstWeekday = DayOfWeek.MONDAY
    )

    // Wednesday, 2026-10-07 at 12:00:00 UTC
    private val todayDate = LocalDate(2026, 10, 7)
    private val nowMillisValue = startOfLocalDay(todayDate) + 12 * 3600 * 1000L

    private val service = TodayEvaluationService(
        userTaskRepository = taskRepo,
        habitList = habitList,
        effectiveHabitValueService = habitService,
        containerQueries = orgStore,
        placementQueries = orgStore,
        clock = { nowMillisValue },
        hourOffset = 0,
        minuteOffset = 0
    )

    private fun addContainer(id: String, name: String, parentId: String? = null): Container {
        val c = Container(
            id = ContainerId(id),
            parentId = parentId?.let(::ContainerId),
            name = name,
            color = null,
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1)
        )
        orgStore.executeInTransaction { tx -> tx.saveContainer(c) }
        return c
    }

    private fun placeHabit(habitUuid: String, containerId: String?) {
        val placement = HabitPlacement(
            habit = HabitRef(habitUuid),
            containerId = containerId?.let(::ContainerId),
            localOrder = 0,
            origin = PlacementOrigin.USER_CHANGE,
            revision = OrganizationRevision(1)
        )
        orgStore.executeInTransaction { tx -> tx.saveHabitPlacement(placement) }
    }

    private fun addHabit(
        name: String,
        uuid: String,
        isNumerical: Boolean = false,
        targetValue: Double = 1.0,
        targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
        unit: String = "",
        frequency: Frequency = Frequency(1, 1),
        containerId: String? = null
    ): Habit {
        val h = modelFactory.buildHabit().apply {
            this.name = name
            this.uuid = uuid
            this.type = if (isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
            this.targetValue = targetValue
            this.targetType = targetType
            this.unit = unit
            this.frequency = frequency
        }
        habitList.add(h)
        if (containerId != null) {
            placeHabit(uuid, containerId)
        }
        return h
    }

    private fun addTask(
        id: String,
        title: String,
        containerId: String? = null,
        plannedDate: LocalDate? = null,
        deadline: Long? = null,
        status: TaskStatus = TaskStatus.PENDING,
        completedAt: Long? = null,
        parentTaskId: TaskId? = null,
    ): UserTask {
        val t = UserTask(
            id = TaskId(id),
            title = title,
            containerId = containerId?.let(::ContainerId),
            plannedDate = plannedDate,
            deadline = deadline,
            status = status,
            completedAt = completedAt,
            parentTaskId = parentTaskId,
            createdAt = nowMillisValue - 86400000L,
            updatedAt = nowMillisValue,
        )
        taskRepo.save(t)
        return t
    }

    @Test
    fun testTaskCategories_hardOverdue_missedPlan_plannedToday_and_futureExclusion() {
        addContainer("study", "Учёба")

        // 1. Hard overdue: deadline has passed (yesterday)
        val tOverdue = addTask(
            id = "t1",
            title = "Сдать МУСы",
            containerId = "study",
            deadline = nowMillisValue - 3600000L // 1 hour ago
        )

        // 2. Missed plan: planned yesterday, deadline tomorrow (NOT hard overdue!)
        val tMissed = addTask(
            id = "t2",
            title = "Оформить отчёт",
            containerId = "study",
            plannedDate = todayDate.minus(1),
            deadline = nowMillisValue + 86400000L // tomorrow
        )

        // 3. Planned today
        val tToday = addTask(
            id = "t3",
            title = "Сделать запросы БД",
            containerId = "study",
            plannedDate = todayDate
        )

        // 4. Future planned task: planned in 3 days, no overdue deadline -> MUST NOT APPEAR
        addTask(
            id = "t4",
            title = "Подготовка к экзамену",
            containerId = "study",
            plannedDate = todayDate.plus(3)
        )

        // 5. Completed task today
        val tCompleted = addTask(
            id = "t5",
            title = "Прочитать методичку",
            containerId = "study",
            status = TaskStatus.COMPLETED,
            completedAt = nowMillisValue - 1800000L // 30 mins ago
        )

        val data = service.evaluateToday()

        assertEquals(1, data.hardOverdueTasks.size)
        assertEquals("t1", data.hardOverdueTasks[0].task.id.value)
        assertEquals(TodayTaskUrgency.HARD_OVERDUE, data.hardOverdueTasks[0].urgency)
        assertEquals("Учёба", data.hardOverdueTasks[0].containerName)

        assertEquals(1, data.missedPlanTasks.size)
        assertEquals("t2", data.missedPlanTasks[0].task.id.value)
        assertEquals(TodayTaskUrgency.MISSED_PLAN, data.missedPlanTasks[0].urgency)
        assertEquals("Учёба", data.missedPlanTasks[0].containerName)

        assertEquals(1, data.plannedTodayTasks.size)
        assertEquals("t3", data.plannedTodayTasks[0].task.id.value)
        assertEquals(TodayTaskUrgency.PLANNED_TODAY, data.plannedTodayTasks[0].urgency)

        assertEquals(1, data.completedTodayTasks.size)
        assertEquals("t5", data.completedTodayTasks[0].task.id.value)

        // Verify total actionable count (overdue + missed + today) = 3 (future task excluded)
        assertEquals(3, data.totalActionableTasksCount)
    }

    @Test
    fun testPlannedDateNotEqualToDeadlineInvariant() {
        // A task whose plannedDate was yesterday but deadline is next week is MISSED_PLAN, NOT HARD_OVERDUE
        addTask(
            id = "t_plan",
            title = "Task with missed plan",
            plannedDate = todayDate.minus(2),
            deadline = nowMillisValue + 7 * 86400000L
        )

        val data = service.evaluateToday()
        assertEquals(0, data.hardOverdueTasks.size)
        assertEquals(1, data.missedPlanTasks.size)
        assertEquals("t_plan", data.missedPlanTasks[0].task.id.value)
        assertEquals(TodayTaskUrgency.MISSED_PLAN, data.missedPlanTasks[0].urgency)
    }

    @Test
    fun testSubtaskCountsForRootTasks() {
        val root = addTask("root1", "Root Task", plannedDate = todayDate)
        addTask("sub1", "Subtask 1", parentTaskId = root.id, status = TaskStatus.COMPLETED)
        addTask("sub2", "Subtask 2", parentTaskId = root.id, status = TaskStatus.PENDING)
        addTask("sub3", "Subtask 3", parentTaskId = root.id, status = TaskStatus.COMPLETED)

        val data = service.evaluateToday()
        assertEquals(1, data.plannedTodayTasks.size)
        val item = data.plannedTodayTasks[0]
        assertEquals(2, item.subtasksCompleted)
        assertEquals(3, item.subtasksTotal)
    }

    @Test
    fun testHabitsSection_booleanAndNumericalWithFocusCredit() {
        addContainer("study", "Учёба")

        // 1. Boolean habit: "Утренняя зарядка", marked as YES today
        val hBoolean = addHabit("Утренняя зарядка", "h_bool")
        hBoolean.originalEntries.add(Entry(todayDate, Entry.YES_MANUAL))

        // 2. Numerical weekly habit: "Учёба" (300 min/week target), with 75 min focus credit
        val hNumerical = addHabit(
            name = "Учёба",
            uuid = "h_study",
            isNumerical = true,
            targetValue = 300.0,
            targetType = NumericalHabitType.AT_LEAST,
            unit = "мин",
            frequency = Frequency(1, 7), // weekly
            containerId = "study"
        )
        // Add 75 min contribution to h_study
        contributionRepo.record(
            Contribution(
                id = ContributionId("c1"),
                targetHabit = HabitRef("h_study"),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "s1",
                contributedValue = 75_000L, // 75.0 in x1000 units
                effectiveTimestamp = nowMillisValue - 3600000L,
                createdAt = nowMillisValue - 3600000L,
                updatedAt = nowMillisValue - 3600000L,
            )
        )

        // 3. Numerical daily habit: "Вода" (2000 ml/day target), 1500 ml manual
        val hWater = addHabit(
            name = "Вода",
            uuid = "h_water",
            isNumerical = true,
            targetValue = 2000.0,
            targetType = NumericalHabitType.AT_LEAST,
            unit = "мл",
            frequency = Frequency(1, 1) // daily
        )
        hWater.originalEntries.add(Entry(todayDate, 1500_000)) // 1500.0 x1000

        val data = service.evaluateToday()

        // Verify boolean habit
        assertEquals(1, data.booleanHabits.size)
        val boolItem = data.booleanHabits[0]
        assertEquals("Утренняя зарядка", boolItem.habit.name)
        assertEquals(Entry.YES_MANUAL, boolItem.todayEntryValue)
        assertEquals(HabitGoalStatus.SATISFIED, boolItem.goalStatus)

        // Verify numerical habits
        assertEquals(2, data.numericalHabits.size)
        val studyItem = data.numericalHabits.first { it.habit.name == "Учёба" }
        assertEquals("Учёба", studyItem.containerName)
        assertEquals(75_000L, studyItem.contributionValue)
        assertEquals(75_000L, studyItem.totalValue)
        assertTrue(studyItem.progressFormatted.contains("75"))
        assertTrue(studyItem.progressFormatted.contains("300"))
        assertTrue(studyItem.progressFormatted.contains("на этой неделе"))

        val waterItem = data.numericalHabits.first { it.habit.name == "Вода" }
        assertEquals(1500_000L, waterItem.manualValue)
        assertEquals(1500_000L, waterItem.totalValue)
        assertTrue(waterItem.progressFormatted.contains("1500"))
        assertTrue(waterItem.progressFormatted.contains("2000"))
        assertTrue(waterItem.progressFormatted.contains("сегодня"))
    }

    @Test
    fun testActiveFocusIntegration() {
        val focusItem = TodayFocusItem(
            targetTitle = "Сделать запросы БД",
            mode = TimerMode.STOPWATCH,
            elapsedMillis = 24 * 60 * 1000L + 18 * 1000L,
            displayTimeMillis = 24 * 60 * 1000L + 18 * 1000L,
            isRunning = true,
            isBreak = false,
            targetType = FocusPrimaryTargetType.TASK,
            taskId = TaskId("t_focus"),
            habitId = null
        )

        val data = service.evaluateToday(activeFocus = focusItem)
        assertNotNull(data.activeFocus)
        assertEquals("Сделать запросы БД", data.activeFocus?.targetTitle)
        assertEquals(true, data.activeFocus?.isRunning)
        assertEquals(24 * 60 * 1000L + 18 * 1000L, data.activeFocus?.elapsedMillis)
    }

    @Test
    fun testEmptyState() {
        val data = service.evaluateToday()
        assertTrue(data.isEmpty)
        assertEquals(0, data.totalActionableTasksCount)
        assertEquals(0, data.allHabitsOrdered.size)
        assertNull(data.activeFocus)
    }
}
