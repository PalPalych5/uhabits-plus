package org.isoron.uhabits.core.userTasks

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.*
import org.isoron.uhabits.core.sync.*
import org.isoron.uhabits.core.userTasks.sqlite.*
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.junit.Assert.*
import org.junit.Test

/** Exercises the existing service and nested SQL writer inside the editor-owned transaction. */
class TaskDraftTransactionTest {
    @Test fun childInsertFailureRollsBackParentChildrenAndQueueThenRetryCommitsOnce() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            val queue = SyncQueueRepository(db)
            val sync = SyncManager(queue, EntryOpRepository(db), { "qa-device" }, { 123L })
            var nextId = 0
            val service = UserTaskServiceImpl(SQLiteUserTaskRepository(db), { 123L }, { "draft-${nextId++}" }, sync)
            val due = TaskDue.Timed.local(1792753200000L, "Asia/Novosibirsk")
            fun submit(): UserTask = db.runInTransaction {
                val parent = service.createTask("Draft parent", description = "Notes", due = due)
                service.createTask("First child", parentTaskId = parent.id)
                val child = service.createTask("Fail child", parentTaskId = parent.id)
                service.setCompleted(child.id, true)
                parent
            }
            assertEquals(0, db.queryInt("SELECT COUNT(*) FROM UserTasks"))
            val queueBefore = db.queryInt("SELECT COUNT(*) FROM SyncQueue")
            db.run("CREATE TRIGGER fail_draft_child BEFORE INSERT ON UserTasks WHEN NEW.title='Fail child' BEGIN SELECT RAISE(ABORT, 'injected subtask failure'); END")
            try { submit(); fail("Expected injected subtask failure") } catch (_: java.sql.SQLException) { }
            assertEquals(0, db.queryInt("SELECT COUNT(*) FROM UserTasks"))
            assertEquals(queueBefore, db.queryInt("SELECT COUNT(*) FROM SyncQueue"))
            db.run("DROP TRIGGER fail_draft_child")
            val parent = submit()
            assertEquals(due, SQLiteUserTaskRepository(db).getById(parent.id)!!.due)
            assertEquals(3, db.queryInt("SELECT COUNT(*) FROM UserTasks"))
            assertEquals(2, SQLiteUserTaskRepository(db).getSubtasks(parent.id).size)
            assertEquals(queueBefore + 4, db.queryInt("SELECT COUNT(*) FROM SyncQueue"))
        } finally { db.close() }
    }

    @Test fun bulkMutationAndGuardedUndoMaintainTransactionalAndSyncIntegrity() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            val repo = SQLiteUserTaskRepository(db)
            val queue = SyncQueueRepository(db)
            var now = 1000L
            val sync = SyncManager(queue, EntryOpRepository(db), { "qa-device" }, { now })
            var nextId = 1
            val service = UserTaskServiceImpl(repo, { now }, { "task-${nextId++}" }, sync)

            val t1 = service.createTask("Task 1", due = TaskDue.None)
            val t2 = service.createTask("Task 2", due = TaskDue.Timed.local(1792753200000L, "Asia/Novosibirsk"))
            val queueAfterCreate = db.queryInt("SELECT COUNT(*) FROM SyncQueue")

            // Inject failure on second task update inside bulk transaction -> must roll back both tasks & SyncQueue
            now = 2000L
            db.run("CREATE TRIGGER fail_bulk_update BEFORE UPDATE ON UserTasks WHEN NEW.id='${t2.id.value}' BEGIN SELECT RAISE(ABORT, 'injected bulk failure'); END")
            try {
                db.runInTransaction {
                    service.setCompleted(t1.id, true)
                    service.setCompleted(t2.id, true)
                }
                fail("Expected injected bulk failure")
            } catch (_: java.sql.SQLException) {
            }
            assertEquals(t1, repo.getById(t1.id))
            assertEquals(t2, repo.getById(t2.id))
            assertEquals(queueAfterCreate, db.queryInt("SELECT COUNT(*) FROM SyncQueue"))
            db.run("DROP TRIGGER fail_bulk_update")

            // Commit bulk completion
            val afterSnapshots = db.runInTransaction {
                listOf(
                    t1 to service.setCompleted(t1.id, true),
                    t2 to service.setCompleted(t2.id, true),
                )
            }
            assertTrue(repo.getById(t1.id)!!.isCompleted)
            assertTrue(repo.getById(t2.id)!!.isCompleted)
            assertEquals(queueAfterCreate + 2, db.queryInt("SELECT COUNT(*) FROM SyncQueue"))

            // Modify t2 before Undo -> Undo must restore t1 to exact prior state and leave t2's newer edit untouched
            now = 3000L
            val t2Edited = service.updateTask(t2.id, title = "Task 2 edited after complete")
            assertEquals(queueAfterCreate + 3, db.queryInt("SELECT COUNT(*) FROM SyncQueue"))

            now = 4000L
            val restored = db.runInTransaction {
                afterSnapshots.mapNotNull { (before, after) ->
                    service.restoreTaskSnapshot(expectedCurrent = after, previousState = before)
                }
            }
            assertEquals(1, restored.size)
            assertEquals(t1.id, restored.single().id)
            assertEquals(TaskStatus.PENDING, repo.getById(t1.id)!!.status)
            assertNull(repo.getById(t1.id)!!.completedAt)
            assertEquals(t2Edited, repo.getById(t2.id))
            assertEquals(queueAfterCreate + 4, db.queryInt("SELECT COUNT(*) FROM SyncQueue"))
        } finally {
            db.close()
        }
    }
}
