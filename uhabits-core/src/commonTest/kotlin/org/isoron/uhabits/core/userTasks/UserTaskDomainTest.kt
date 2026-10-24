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
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
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
    fun testUnifiedDueEvaluation() {
        // Task A: Day due yesterday -> OVERDUE
        val taskA = UserTask(
            id = TaskId("task-a"),
            title = "Read Chapter 3",
            due = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 3))),
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        assertTrue(taskA.isOverdue(today, nowMillis))
        assertFalse(taskA.isDueToday(today, nowMillis))
        assertFalse(taskA.isDueUpcoming(today, nowMillis))

        // Task B: Day due today -> DUE TODAY
        val taskB = UserTask(
            id = TaskId("task-b"),
            title = "Submit Paper Draft",
            due = TaskDue.Day(TaskDueDate.from(today)),
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        assertFalse(taskB.isOverdue(today, nowMillis))
        assertTrue(taskB.isDueToday(today, nowMillis))
        assertFalse(taskB.isDueUpcoming(today, nowMillis))

        // Task C: Timed due 2 hours ago -> OVERDUE
        val taskC = UserTask(
            id = TaskId("task-c"),
            title = "Pay Tuition Fee",
            due = TaskDue.Timed.of(nowMillis - 7200000L),
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        assertTrue(taskC.isOverdue(today, nowMillis))
        assertFalse(taskC.isDueToday(today, nowMillis))

        // Task D: completed task is never overdue or due today
        val taskD = taskC.copy(status = TaskStatus.COMPLETED, completedAt = nowMillis)
        assertFalse(taskD.isOverdue(today, nowMillis))
        assertFalse(taskD.isDueToday(today, nowMillis))
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
            due = TaskDue.Day(TaskDueDate.from(today)),
        )
        assertEquals(TaskId("t-1"), task.id)
        assertEquals("Math homework", task.title)
        assertEquals(TaskDue.Day(TaskDueDate.from(today)), task.due)
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
    fun testReorderMissingIdsPreservesInputPositions() {
        val repo = MemoryUserTaskRepository()
        var id = 0
        val service = UserTaskServiceImpl(repo, clock = { 1000L }, idGenerator = { "${id++}" })
        val first = service.createTask(title = "First")
        val second = service.createTask(title = "Second")
        service.reorderTasks(listOf(TaskId("missing"), second.id, first.id))
        assertEquals(1, repo.getById(second.id)?.sortOrder)
        assertEquals(2, repo.getById(first.id)?.sortOrder)
    }

    @Test
    fun testFailedLegacyAcceptanceKeepsRawAndAllowsManualDueOrDismissal() {
        val repo = MemoryUserTaskRepository()
        val service = UserTaskServiceImpl(repo, clock = { 2000L })
        val original = UserTask(TaskId("malformed"), "Malformed plan", legacyPlanDate = "not-a-date",
            createdAt = 1, updatedAt = 1)
        repo.save(original)
        assertFailsWith<IllegalArgumentException> { service.resolveLegacyPlan(original.id, accept = true) }
        assertEquals(original, repo.getById(original.id))
        assertTrue(repo.getById(original.id)!!.legacyPlanReviewPending)
        val manuallySet = service.updateTask(original.id, due = TaskDue.Timed.of(nowMillis, "Asia/Novosibirsk"))
        assertEquals("not-a-date", manuallySet.legacyPlanDate)
        assertFalse(manuallySet.legacyPlanReviewPending)
        val cleared = service.updateTask(original.id, due = TaskDue.None)
        assertFalse(cleared.legacyPlanReviewPending)
        assertEquals("not-a-date", cleared.legacyPlanDate)

        repo.save(original)
        val dismissed = service.resolveLegacyPlan(original.id, accept = false)
        assertEquals("not-a-date", dismissed.legacyPlanDate)
        assertFalse(dismissed.legacyPlanReviewPending)
        assertEquals(TaskDue.None, dismissed.due)
    }

    @Test
    fun testAcceptedLegacyPlanKeepsHistoryWithoutRenewingReviewWhenCleared() {
        val repo = MemoryUserTaskRepository()
        val service = UserTaskServiceImpl(repo, clock = { 2000L })
        val original = UserTask(TaskId("legacy"), "Legacy plan", legacyPlanDate = "2026-10-22",
            createdAt = 1, updatedAt = 1)
        repo.save(original)
        val accepted = service.resolveLegacyPlan(original.id, accept = true)
        assertEquals(TaskDue.Day(TaskDueDate.parse("2026-10-22")), accepted.due)
        assertEquals(original.legacyPlanDate, accepted.legacyPlanDate)
        assertFalse(accepted.legacyPlanReviewPending)
        val cleared = service.updateTask(original.id, due = TaskDue.None)
        assertEquals(original.legacyPlanDate, cleared.legacyPlanDate)
        assertFalse(cleared.legacyPlanReviewPending)
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

    @Test
    fun testRestoreTaskSnapshotRestoresExactPreviousCompletionAndLegacyPlanState() {
        val repo = MemoryUserTaskRepository()
        var currentClock = 1000L
        val service = UserTaskServiceImpl(repo, clock = { currentClock })
        val original = UserTask(
            id = TaskId("t-restore"),
            title = "Review report",
            description = "Detailed notes",
            containerId = ContainerId("work"),
            status = TaskStatus.COMPLETED,
            due = TaskDue.Timed.of(1792753200000L, "Asia/Novosibirsk"),
            legacyPlanDate = "2026-10-20",
            legacyPlanReviewPending = true,
            completedAt = 950L,
            sortOrder = 3,
            createdAt = 500L,
            updatedAt = 950L,
        )
        repo.save(original)

        // Reopen task (clears completedAt)
        currentClock = 2000L
        val reopened = service.setCompleted(original.id, false)
        assertEquals(TaskStatus.PENDING, reopened.status)
        assertNull(reopened.completedAt)
        assertEquals(2000L, reopened.updatedAt)

        // Undo reopen -> restores exact completedAt = 950L and monotonic updatedAt
        currentClock = 2000L // Even if clock has not advanced, updatedAt must advance monotonically
        val restored = service.restoreTaskSnapshot(expectedCurrent = reopened, previousState = original)
        assertNotNull(restored)
        assertEquals(TaskStatus.COMPLETED, restored.status)
        assertEquals(950L, restored.completedAt)
        assertEquals(original.due, restored.due)
        assertEquals(original.containerId, restored.containerId)
        assertEquals("2026-10-20", restored.legacyPlanDate)
        assertTrue(restored.legacyPlanReviewPending)
        assertTrue(restored.updatedAt > reopened.updatedAt)
        assertEquals(restored, repo.getById(original.id))
    }

    @Test
    fun testRestoreTaskSnapshotDoesNotOverwriteNewerUserModification() {
        val repo = MemoryUserTaskRepository()
        var currentClock = 1000L
        val service = UserTaskServiceImpl(repo, clock = { currentClock })
        val beforeComplete = service.createTask(
            title = "Prepare slides",
            containerId = ContainerId("work"),
            due = TaskDue.Day(TaskDueDate.parse("2026-10-23")),
            explicitId = TaskId("t-stale-undo"),
        )

        currentClock = 2000L
        val afterComplete = service.setCompleted(beforeComplete.id, true)
        assertTrue(afterComplete.isCompleted)

        // Newer user edit happens before Undo is clicked
        currentClock = 3000L
        val newerEdit = service.updateTask(
            id = beforeComplete.id,
            title = "Prepare slides (updated)",
            containerId = ContainerId("personal"),
        )

        currentClock = 4000L
        val undoResult = service.restoreTaskSnapshot(
            expectedCurrent = afterComplete,
            previousState = beforeComplete,
        )
        assertNull(undoResult)
        assertEquals(newerEdit, repo.getById(beforeComplete.id))
    }
}
