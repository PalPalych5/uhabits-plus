/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks

import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.run
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DueReviewPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val opener = JavaDatabaseOpener()
    private fun withDatabase(path: String, block: (org.isoron.platform.io.Database) -> Unit) {
        val db = opener.open(path)
        try { block(db) } finally { db.close() }
    }

    @Test fun localTimedPreservesExactInstantAndKnownZoneAfterReopen() {
        val file = temporary.newFile("timed.db")
        for (zone in listOf("GMT", "Asia/Novosibirsk", "America/New_York", "GMT+07:00")) {
            val expected = TaskDue.Timed.local(1792756800123, zone)
            withDatabase(file.path) { db ->
                UserTaskSchema.createSchema(db)
                SQLiteUserTaskRepository(db).save(UserTask(TaskId(zone), zone, due = expected, createdAt = 1, updatedAt = 1))
            }
            withDatabase(file.path) { db ->
                assertEquals(expected, SQLiteUserTaskRepository(db).getById(TaskId(zone))!!.due)
            }
        }
    }

    @Test fun invalidAcceptancePreservesExactRawAndPendingAcrossReopen() {
        val file = temporary.newFile("invalid.db")
        val id = TaskId("invalid")
        for (raw in listOf("not-a-date", "2026-02-30", "", "1792393200000")) {
            withDatabase(file.path) { db ->
                UserTaskSchema.createSchema(db)
                val repo = SQLiteUserTaskRepository(db)
                val before = UserTask(id, "Invalid", legacyPlanDate = raw, createdAt = 1, updatedAt = 1)
                repo.save(before)
                val service = UserTaskServiceImpl(repo, clock = { 2 })
                assertThrows(InvalidLegacyPlanException::class.java) { service.resolveLegacyPlan(id, true) }
                assertEquals(before, repo.getById(id))
            }
            withDatabase(file.path) { db ->
                val repo = SQLiteUserTaskRepository(db)
                val reopened = repo.getById(id)!!
                assertEquals(raw, reopened.legacyPlanDate)
                assertTrue(reopened.hasPendingLegacyPlan)
                // A title-only edit must not implicitly dismiss review.
                val service = UserTaskServiceImpl(repo, clock = { 3 })
                assertTrue(service.updateTask(id, title = "Edited").hasPendingLegacyPlan)
                val repaired = service.updateTask(id, due = TaskDue.Day(TaskDueDate.parse("2026-10-22")))
                assertFalse(repaired.hasPendingLegacyPlan)
                assertEquals(raw, repaired.legacyPlanDate)
            }
        }
    }

    @Test fun decisionsManualReplacementAndClearNeverResurrectReview() {
        val file = temporary.newFile("decisions.db")
        for (action in listOf("accept", "dismiss", "manual")) {
            val id = TaskId(action)
            withDatabase(file.path) { db ->
                UserTaskSchema.createSchema(db)
                val repo = SQLiteUserTaskRepository(db)
                repo.save(UserTask(id, action, legacyPlanDate = "2026-10-22", createdAt = 1, updatedAt = 1))
                val service = UserTaskServiceImpl(repo, clock = { 2 })
                val decided = when (action) {
                    "accept" -> service.resolveLegacyPlan(id, true)
                    "dismiss" -> service.resolveLegacyPlan(id, false)
                    else -> service.updateTask(id, due = TaskDue.Timed.local(1792756800123, "Asia/Novosibirsk"))
                }
                assertFalse(decided.hasPendingLegacyPlan)
                service.updateTask(id, due = TaskDue.None)
            }
            withDatabase(file.path) { db ->
                val repo = SQLiteUserTaskRepository(db)
                val task = repo.getById(id)!!
                assertEquals("2026-10-22", task.legacyPlanDate)
                assertEquals(TaskDue.None, task.due)
                assertFalse(task.hasPendingLegacyPlan)
                assertEquals(task, UserTaskServiceImpl(repo).resolveLegacyPlan(id, true))
            }
        }
    }
}
