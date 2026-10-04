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

package org.isoron.uhabits.core.userTasks.sqlite

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SQLiteUserTaskRepositoryTest {

    @Test
    fun testSQLiteUserTaskRepository_crud() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)

        val task1 = UserTask(
            id = TaskId("task-1"),
            title = "Buy Groceries",
            description = "Apples, Milk, Bread",
            containerId = ContainerId("home-cid"),
            status = TaskStatus.PENDING,
            plannedDate = LocalDate(2026, 10, 4),
            deadline = 1791105000000L,
            completedAt = null,
            parentTaskId = null,
            sortOrder = 0,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        repo.save(task1)

        val loaded = repo.getById(TaskId("task-1"))
        assertNotNull(loaded)
        assertEquals("Buy Groceries", loaded.title)
        assertEquals("Apples, Milk, Bread", loaded.description)
        assertEquals(ContainerId("home-cid"), loaded.containerId)
        assertEquals(TaskStatus.PENDING, loaded.status)
        assertEquals(LocalDate(2026, 10, 4), loaded.plannedDate)
        assertEquals(1791105000000L, loaded.deadline)
        assertNull(loaded.completedAt)

        // Subtask
        val subtask = UserTask(
            id = TaskId("sub-1"),
            title = "Organic Apples",
            containerId = ContainerId("home-cid"),
            status = TaskStatus.COMPLETED,
            completedAt = 2000L,
            parentTaskId = task1.id,
            sortOrder = 0,
            createdAt = 1500L,
            updatedAt = 2000L,
        )
        repo.save(subtask)

        val roots = repo.getRoots()
        assertEquals(1, roots.size)
        assertEquals(task1.id, roots[0].id)

        val subtasks = repo.getSubtasks(task1.id)
        assertEquals(1, subtasks.size)
        assertEquals(subtask.id, subtasks[0].id)
        assertEquals(TaskStatus.COMPLETED, subtasks[0].status)
        assertEquals(2000L, subtasks[0].completedAt)

        // Update
        val updated = task1.copy(title = "Buy Fresh Groceries", status = TaskStatus.COMPLETED, completedAt = 3000L)
        repo.save(updated)
        val reloaded = repo.getById(task1.id)
        assertNotNull(reloaded)
        assertEquals("Buy Fresh Groceries", reloaded.title)
        assertEquals(TaskStatus.COMPLETED, reloaded.status)
        assertEquals(3000L, reloaded.completedAt)

        // Delete
        repo.delete(subtask.id)
        assertNull(repo.getById(subtask.id))
        assertEquals(0, repo.getSubtasks(task1.id).size)

        repo.clear()
        assertEquals(0, repo.getAll().size)
    }
}
