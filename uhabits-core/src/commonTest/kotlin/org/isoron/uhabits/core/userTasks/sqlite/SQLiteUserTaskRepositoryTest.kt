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
import org.isoron.platform.io.StepResult
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskServiceImpl
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
                val created = service.createTask(title = "Appended", parentTaskId = parent,
                    plannedDate = LocalDate(2024, 2, 29), deadline = 1709251200000L)
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
        db.run("""CREATE TABLE IF NOT EXISTS SyncQueue (
            queue_id INTEGER PRIMARY KEY AUTOINCREMENT, op_uuid TEXT NOT NULL UNIQUE,
            entity_type TEXT NOT NULL, entity_uuid TEXT NOT NULL, operation_type TEXT NOT NULL,
            payload_json TEXT NOT NULL, created_at INTEGER NOT NULL, device_id TEXT NOT NULL,
            pushed_at INTEGER, failed_at INTEGER, failure_reason TEXT)""")
        val repo = SQLiteUserTaskRepository(db)
        val queue = SyncQueueRepository(db)
        val sync = SyncManager(queue, EntryOpRepository(db), { "test-device" }, { 20L })
        val service = UserTaskServiceImpl(repo, { 20L }, { error("Unused") }, sync)
        val tasks = (0..3).map { UserTask(TaskId("task-$it"), "Task $it", sortOrder = it, createdAt = 1, updatedAt = 1) }
        repo.saveAll(tasks)
        sync.onQueueAppended = {
            assertEquals(tasks.reversed().map { it.id }, repo.getRoots().map { it.id })
        }
        service.reorderTasks(tasks.reversed().map { it.id })
        assertEquals(tasks.reversed().map { it.id.value }, queue.findPending().map { it.entityUuid })
        val successfulQueue = queue.findPending()
        db.run("CREATE TRIGGER fail_reorder BEFORE INSERT ON UserTasks WHEN NEW.id = 'task-1' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertFails { service.reorderTasks(tasks.map { it.id }) }
        assertEquals(tasks.reversed().map { it.id }, repo.getRoots().map { it.id })
        assertEquals(successfulQueue, queue.findPending())
        db.close()
    }

    @Test
    fun testBatchFailureRollsBackStandaloneAndNestedTransactions() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)
        val original = UserTask(TaskId("original"), "Original", createdAt = 1, updatedAt = 1)
        repo.save(original)
        // A trigger injects failure after an earlier valid write without relying on
        // domain validation to allow an invalid UserTask object.
        db.run("CREATE TRIGGER fail_task BEFORE INSERT ON UserTasks WHEN NEW.id = 'fail' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        val batch = listOf(original.copy(title = "Changed"), original.copy(id = TaskId("fail")))
        assertFails { repo.saveAll(batch) }
        assertEquals(listOf(original), repo.getAll())
        db.runInTransaction {
            repo.saveAll(listOf(original.copy(id = TaskId("outer"))))
            assertFails { repo.saveAll(batch) }
        }
        // A caught nested failure still marks the enclosing transaction for rollback.
        assertEquals(listOf(original), repo.getAll())
        db.close()
    }

    @Test
    fun testNestedBatchAndSqlTransactionRemainOwnedByCaller() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        val repo = SQLiteUserTaskRepository(db)
        val task = UserTask(TaskId("one"), "One", createdAt = 1, updatedAt = 1)
        db.beginTransaction()
        db.runInTransaction { repo.saveAll(listOf(task)) }
        assertTrue(db.inTransaction())
        assertEquals(task, repo.getById(task.id))
        db.endTransaction() // no success: the caller can still roll back the batch
        assertNull(repo.getById(task.id))
        db.run("BEGIN IMMEDIATE")
        repo.saveAll(listOf(task))
        assertTrue(db.inTransaction())
        db.run("COMMIT")
        assertEquals(task, repo.getById(task.id))
        db.close()
    }

    @Test
    fun testMaxSortOrderUsesExistingCoveringIndex() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        UserTaskSchema.createSchema(db)
        for (predicate in listOf("parent_task_id IS NULL", "parent_task_id = 'parent'")) {
            val statement = db.prepareStatement("EXPLAIN QUERY PLAN SELECT MAX(sort_order) FROM UserTasks WHERE $predicate")
            val details = mutableListOf<String>()
            try {
                while (statement.step() == StepResult.ROW) details.add(statement.getText(3))
            } finally { statement.finalize() }
            assertTrue(details.any { it.contains("COVERING INDEX idx_user_tasks_order") }, details.toString())
        }
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
