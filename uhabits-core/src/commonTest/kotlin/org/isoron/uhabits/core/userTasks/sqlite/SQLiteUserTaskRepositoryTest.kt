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
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.StepResult
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskServiceImpl
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.isoron.uhabits.core.sync.SyncManager
import org.isoron.uhabits.core.sync.SyncQueueRepository
import org.isoron.uhabits.core.sync.EntryOpRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFails

class SQLiteUserTaskRepositoryTest {

    @Test
    fun legacySaveAndSaveAllPreserveUnknownColumnsAndDependentRows() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            db.run("PRAGMA foreign_keys=ON")
            db.run("ALTER TABLE UserTasks ADD COLUMN proposed_due_v1 TEXT")
            db.run("CREATE TABLE FutureTaskMetadata(task_id TEXT PRIMARY KEY REFERENCES UserTasks(id) ON DELETE CASCADE, value TEXT)")
            val repo = SQLiteUserTaskRepository(db)
            val task = UserTask(TaskId("future"), "Original", createdAt = 1, updatedAt = 1)
            repo.save(task)
            db.run("UPDATE UserTasks SET proposed_due_v1='explicit:NONE' WHERE id='future'")
            db.run("INSERT INTO FutureTaskMetadata VALUES ('future','immutable evidence')")
            repeat(2) { pass ->
                val updated = task.copy(title = "Edit $pass", updatedAt = pass + 2L)
                if (pass == 0) repo.save(updated) else repo.saveAll(listOf(updated))
                assertEquals(updated, repo.getById(task.id))
                assertEquals("explicit:NONE", db.querySingle("SELECT proposed_due_v1 FROM UserTasks WHERE id='future'") { it.getTextOrNull(0) })
                assertEquals("immutable evidence", db.querySingle("SELECT value FROM FutureTaskMetadata WHERE task_id='future'") { it.getText(0) })
            }
        } finally { db.close() }
    }

    @Test
    fun testCreationMaxMatchesIndependentSiblingOracleAndPersistedDates() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        for (repo in listOf(SQLiteUserTaskRepository(db), MemoryUserTaskRepository())) {
            var id = 0
            val service = UserTaskServiceImpl(repo, { 1L }, { "created-${id++}" })
            val root = service.createTask(title = "Root", sortOrder = -3)
            service.createTask(title = "Completed root", sortOrder = 7).also { service.setCompleted(it.id, true) }
            val child = service.createTask(title = "Child", parentTaskId = root.id, sortOrder = 40)
            service.createTask(title = "Other child", parentTaskId = child.id, sortOrder = 99)
            for (parent in listOf(null, root.id, child.id)) {
                val expected = repo.getAll().filter { it.parentTaskId == parent }.maxOf { it.sortOrder } + 1
                val created = service.createTask(
                    title = "Appended",
                    parentTaskId = parent,
                    due = TaskDue.Timed.of(1709251200000L),
                )
                assertEquals(expected, created.sortOrder)
                assertEquals(created, repo.getById(created.id))
            }
        }
        db.close()
    }

    @Test
    fun testReorderQueueFollowsInputAndOnlyRunsAfterSuccessfulBatch() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)
        val queue = SyncQueueRepository(db)
        val sync = SyncManager(queue, EntryOpRepository(db), deviceIdProvider = { "dev" }, nowProvider = { 1000L })
        var gen = 0
        val service = UserTaskServiceImpl(repo, clock = { 1000L }, idGenerator = { "gen-${gen++}" }, syncManager = sync)
        val a = service.createTask(title = "A")
        val b = service.createTask(title = "B")
        db.run("DELETE FROM SyncQueue")
        service.reorderTasks(listOf(b.id, a.id))
        assertEquals(listOf(b.id.value, a.id.value), queue.findPending().map { it.entityUuid })
        db.close()
    }

    @Test
    fun testSavePreservesSortOrderUnlessExplicitlyUpdated() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)
        val task = UserTask(TaskId("t1"), "Original", sortOrder = 42, createdAt = 1000L, updatedAt = 1000L)
        repo.save(task)
        assertEquals(42, repo.getById(task.id)?.sortOrder)

        val updated = task.copy(title = "Edited", updatedAt = 2000L)
        repo.save(updated)
        val reloaded = repo.getById(task.id)
        assertNotNull(reloaded)
        assertEquals("Edited", reloaded.title)
        assertEquals(42, reloaded.sortOrder)
        db.close()
    }

    @Test
    fun testBulkReorderIsAtomicWithinSingleTransaction() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)
        var gen = 0
        val service = UserTaskServiceImpl(repo, clock = { 1000L }, idGenerator = { "gen-${gen++}" })

        val t1 = service.createTask(title = "T1")
        val t2 = service.createTask(title = "T2")

        assertEquals(0, repo.getById(t1.id)?.sortOrder)
        assertEquals(1, repo.getById(t2.id)?.sortOrder)

        // Normal reorder
        service.reorderTasks(listOf(t2.id, t1.id))
        assertEquals(0, repo.getById(t2.id)?.sortOrder)
        assertEquals(1, repo.getById(t1.id)?.sortOrder)

        // Invalid task in reorder list throws, state preserved
        assertFails {
            service.reorderTasks(listOf(t1.id, TaskId("non-existent")))
        }
        assertEquals(0, repo.getById(t2.id)?.sortOrder)
        assertEquals(1, repo.getById(t1.id)?.sortOrder)
        db.close()
    }

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
            due = TaskDue.Timed.of(1791105000000L),
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
        assertEquals(TaskDue.Timed.of(1791105000000L), loaded.due)
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

    @Test
    fun testSQLiteUserTaskRepository_getMaxSortOrder() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)

        // Empty repository
        assertNull(repo.getMaxSortOrder(null))
        assertNull(repo.getMaxSortOrder(TaskId("parent-1")))

        // Add roots with various sort orders
        val r1 = UserTask(
            id = TaskId("r-1"),
            title = "Root 1",
            sortOrder = 5,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val r2 = UserTask(
            id = TaskId("r-2"),
            title = "Root 2",
            sortOrder = 12,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val r3 = UserTask(
            id = TaskId("r-3"),
            title = "Root 3",
            sortOrder = -2,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        repo.saveAll(listOf(r1, r2, r3))
        assertEquals(12, repo.getMaxSortOrder(null))

        // Add subtasks
        val s1 = UserTask(
            id = TaskId("s-1"),
            title = "Sub 1",
            parentTaskId = r1.id,
            sortOrder = 3,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val s2 = UserTask(
            id = TaskId("s-2"),
            title = "Sub 2",
            parentTaskId = r1.id,
            sortOrder = 9,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        repo.saveAll(listOf(s1, s2))

        assertEquals(9, repo.getMaxSortOrder(r1.id))
        assertNull(repo.getMaxSortOrder(r2.id))
        // Root max sort order remains unchanged
        assertEquals(12, repo.getMaxSortOrder(null))
    }

    @Test
    fun testSQLiteUserTaskRepository_saveAll() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)

        val batch = (1..10).map { i ->
            UserTask(
                id = TaskId("batch-$i"),
                title = "Batch Task $i",
                sortOrder = i,
                createdAt = 1000L,
                updatedAt = 1000L,
            )
        }
        repo.saveAll(batch)
        assertEquals(10, repo.getAll().size)
        assertEquals(10, repo.getMaxSortOrder(null))

        // Update batch in transaction
        val updatedBatch = batch.map { it.copy(title = "${it.title} updated", status = TaskStatus.COMPLETED) }
        repo.saveAll(updatedBatch)
        assertEquals(10, repo.getAll().size)
        for (i in 1..10) {
            val task = repo.getById(TaskId("batch-$i"))
            assertNotNull(task)
            assertEquals("Batch Task $i updated", task.title)
            assertEquals(TaskStatus.COMPLETED, task.status)
        }
    }
}
