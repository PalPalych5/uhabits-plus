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

package org.isoron.uhabits.core.userTasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserTaskDomainTest {

    private val today = LocalDate(2026, 10, 4)
    private val nowMillis = 1791100000000L // arbitrary fixed time

    @Test
    fun testSemanticInvariant_plannedDateNotDeadline() {
        // Task A: planned yesterday, no deadline -> MISSED PLAN, NOT hard overdue
        val taskA = UserTask(
            id = TaskId("task-a"),
            title = "Read Chapter 3",
            plannedDate = LocalDate(2026, 10, 3), // yesterday
            deadline = null,
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        assertTrue(taskA.isMissedPlan(today, nowMillis), "Task with past plannedDate should be missed plan")
        assertFalse(taskA.isHardOverdue(nowMillis), "Missed plan must NOT be hard overdue")

        // Task B: planned yesterday, but deadline is next week -> MISSED PLAN, NOT hard overdue
        val taskB = UserTask(
            id = TaskId("task-b"),
            title = "Submit Paper Draft",
            plannedDate = LocalDate(2026, 10, 3), // yesterday
            deadline = nowMillis + 86400000L * 7, // 7 days in future
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        assertTrue(taskB.isMissedPlan(today, nowMillis))
        assertFalse(taskB.isHardOverdue(nowMillis))

        // Task C: deadline was 2 hours ago -> HARD OVERDUE, NOT missed plan
        val taskC = UserTask(
            id = TaskId("task-c"),
            title = "Pay Tuition Fee",
            plannedDate = LocalDate(2026, 10, 4), // today
            deadline = nowMillis - 7200000L, // 2 hours ago
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        assertTrue(taskC.isHardOverdue(nowMillis), "Past deadline must be hard overdue")
        assertFalse(taskC.isMissedPlan(today, nowMillis), "Hard overdue task should not report as missed plan")

        // Task D: completed task is neither hard overdue nor missed plan
        val taskD = taskC.copy(status = TaskStatus.COMPLETED, completedAt = nowMillis)
        assertFalse(taskD.isHardOverdue(nowMillis))
        assertFalse(taskD.isMissedPlan(today, nowMillis))
    }

    @Test
    fun testTaskService_createAndComplete() {
        val repo = MemoryUserTaskRepository()
        var currentClock = 1000L
        var idCounter = 1
        val service = UserTaskServiceImpl(repo, clock = { currentClock }, idGenerator = { "t-${idCounter++}" })

        val task = service.createTask(
            title = "Math homework",
            description = "Exercises 1-10",
            containerId = ContainerId("study-cid"),
            plannedDate = today,
        )
        assertEquals(TaskId("t-1"), task.id)
        assertEquals("Math homework", task.title)
        assertEquals(TaskStatus.PENDING, task.status)
        assertNull(task.completedAt)

        // Complete task
        currentClock = 2000L
        val completed = service.toggleComplete(task.id)
        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertEquals(2000L, completed.completedAt)

        // Uncomplete task
        currentClock = 3000L
        val reopened = service.toggleComplete(task.id)
        assertEquals(TaskStatus.PENDING, reopened.status)
        assertNull(reopened.completedAt)
    }

    @Test
    fun testTaskService_subtaskTreeAndCycleDetection() {
        val repo = MemoryUserTaskRepository()
        var idCounter = 1
        val service = UserTaskServiceImpl(repo, clock = { 1000L }, idGenerator = { "t-${idCounter++}" })

        val parent = service.createTask(title = "Parent Project")
        val child1 = service.createTask(title = "Subtask 1", parentTaskId = parent.id)
        val child2 = service.createTask(title = "Subtask 2", parentTaskId = parent.id)
        val grandChild = service.createTask(title = "Grandchild", parentTaskId = child1.id)

        assertEquals(parent.id, child1.parentTaskId)
        assertEquals(child1.id, grandChild.parentTaskId)

        val subtasks = repo.getSubtasks(parent.id)
        assertEquals(2, subtasks.size)
        assertEquals(listOf(child1.id, child2.id), subtasks.map { it.id })

        // Self parent cycle
        assertFailsWith<IllegalArgumentException> {
            service.updateTask(parent.id, parentTaskId = parent.id)
        }

        // Descendant cycle: cannot make parent a subtask of child1 or grandChild
        assertFailsWith<IllegalArgumentException> {
            service.updateTask(parent.id, parentTaskId = child1.id)
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateTask(parent.id, parentTaskId = grandChild.id)
        }
    }

    @Test
    fun testTaskService_cascadeDelete() {
        val repo = MemoryUserTaskRepository()
        var idCounter = 1
        val service = UserTaskServiceImpl(repo, clock = { 1000L }, idGenerator = { "t-${idCounter++}" })

        val parent = service.createTask(title = "Parent")
        val child = service.createTask(title = "Child", parentTaskId = parent.id)
        val grandChild = service.createTask(title = "GrandChild", parentTaskId = child.id)

        service.deleteTask(parent.id, cascadeSubtasks = true)
        assertNull(repo.getById(parent.id))
        assertNull(repo.getById(child.id))
        assertNull(repo.getById(grandChild.id))
    }

    @Test
    fun testParserMatchesOriginalIncludingMalformedPersistedValues() {
        fun original(value: String?): LocalDate? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("-")
            if (parts.size != 3) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2].toIntOrNull() ?: return null
            return LocalDate(year, month, day)
        }
        val values = mutableListOf<String?>(null, "", " ", "1-1-1", "+1-+1-+1", "-1-01-01",
            "2026-01--1", "2026-01-01-", "2026-01-01 ", "2026-4294967297-01",
            "4294969322-01-01", "2026-01-4294967297", "2147483647-01-01",
            "2147483648-01-01", "2026-01-2147483647", "0000000001-01-01")
        for (year in listOf("0", "1", "1900", "2000", "2024", "2026", "9999")) {
            for (month in listOf("0", "1", "2", "12", "13", "+2")) {
                for (day in listOf("0", "1", "28", "29", "30", "31", "32", "+1")) {
                    values.add("$year-$month-$day")
                }
            }
        }
        for (value in values) {
            val expected = runCatching { original(value) }
            val actual = runCatching { parseIsoDateString(value) }
            assertEquals(expected.getOrNull(), actual.getOrNull(), value)
            assertEquals(expected.exceptionOrNull()?.let { it::class }, actual.exceptionOrNull()?.let { it::class }, value)
        }
    }

    @Test
    fun testParseIsoDateString() {
        // Valid ISO strings
        val d1 = parseIsoDateString("2026-10-04")
        assertEquals(LocalDate(2026, 10, 4), d1)
        assertEquals("2026-10-04", d1?.toIsoDateString())

        val d2 = parseIsoDateString("2024-02-29") // Leap year
        assertEquals(LocalDate(2024, 2, 29), d2)

        val d3 = parseIsoDateString("0001-01-01")
        assertEquals(LocalDate(1, 1, 1), d3)

        // Invalid or edge case strings
        assertNull(parseIsoDateString(null))
        assertNull(parseIsoDateString(""))
        assertNull(parseIsoDateString("20261004"))
        assertNull(parseIsoDateString("2026-10"))
        assertNull(parseIsoDateString("-2026-10-04"))
        assertNull(parseIsoDateString("2026--04"))
        assertNull(parseIsoDateString("2026-10-"))
        assertNull(parseIsoDateString("abcd-ef-gh"))
        assertNull(parseIsoDateString("2026-10-0a"))
        assertNull(parseIsoDateString("2026-0a-04"))
    }

    @Test
    fun testReorderDuplicateIdsMatchesSequentialUpdates() {
        val repo = MemoryUserTaskRepository()
        var id = 0
        val service = UserTaskServiceImpl(repo, clock = { 1000L }, idGenerator = { "${id++}" })
        service.createTask(title = "First")
        val second = service.createTask(title = "Second")
        service.reorderTasks(listOf(second.id, second.id))
        assertEquals(1, repo.getById(second.id)?.sortOrder)
    }

    @Test
    fun testTaskService_reorderTasks() {
        val repo = MemoryUserTaskRepository()
        var idCounter = 1
        val service = UserTaskServiceImpl(repo, clock = { 1000L }, idGenerator = { "t-${idCounter++}" })

        val t1 = service.createTask(title = "Task 1")
        val t2 = service.createTask(title = "Task 2")
        val t3 = service.createTask(title = "Task 3")

        assertEquals(0, repo.getById(t1.id)?.sortOrder)
        assertEquals(1, repo.getById(t2.id)?.sortOrder)
        assertEquals(2, repo.getById(t3.id)?.sortOrder)

        // Reverse order
        service.reorderTasks(listOf(t3.id, t2.id, t1.id))
        assertEquals(0, repo.getById(t3.id)?.sortOrder)
        assertEquals(1, repo.getById(t2.id)?.sortOrder)
        assertEquals(2, repo.getById(t1.id)?.sortOrder)

        // Subtasks reordering
        val s1 = service.createTask(title = "Sub 1", parentTaskId = t1.id)
        val s2 = service.createTask(title = "Sub 2", parentTaskId = t1.id)
        assertEquals(0, repo.getById(s1.id)?.sortOrder)
        assertEquals(1, repo.getById(s2.id)?.sortOrder)

        service.reorderTasks(listOf(s2.id, s1.id))
        assertEquals(0, repo.getById(s2.id)?.sortOrder)
        assertEquals(1, repo.getById(s1.id)?.sortOrder)
    }
}
