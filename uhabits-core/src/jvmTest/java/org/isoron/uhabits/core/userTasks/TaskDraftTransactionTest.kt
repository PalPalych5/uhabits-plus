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
}
