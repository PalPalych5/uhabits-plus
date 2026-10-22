/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due.sqlite

import org.isoron.platform.io.*
import org.isoron.uhabits.core.userTasks.due.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SQLiteTaskDueRepositoryTest {
    private fun source(id: String = "task", plan: String? = null, deadline: Long? = null) =
        LegacyTaskDueSnapshot(id, plan, deadline, 123, "legacy-sqlite-v30", "revision-7", "operation-8")

    private fun withDb(block: (Database, SQLiteTaskDueRepository) -> Unit) {
        val db = JavaDatabaseOpener().open(":memory:")
        try { TaskDueSchema.createSchema(db); block(db, SQLiteTaskDueRepository(db)) }
        finally { db.close() }
    }

    @Test fun planOnlyNeverBecomesAutomaticDeadline() = withDb { _, repo ->
        repo.migrate(listOf(source(plan = "2001-01-01")))
        val result = repo.find("task")!!
        assertEquals(TaskDue.None, result.due)
        assertEquals("2001-01-01", result.proposedDay!!.isoDate)
        assertTrue(LegacyTaskDueReview.PLAN_REQUIRES_REVIEW in result.review)
    }

    @Test fun conflictingDatesKeepExactDeadlineAndRawPlan() = withDb { _, repo ->
        val raw = source(plan = "2026-10-23", deadline = 1792368000123)
        repo.migrate(listOf(raw))
        val result = repo.find("task")!!
        assertEquals(raw, result.source)
        assertEquals(TaskDue.Timed.legacy(1792368000123), result.due)
        assertEquals("2026-10-23", result.proposedDay!!.isoDate)
        assertNull((result.due as TaskDue.Timed).originZoneId)
    }

    @Test fun repeatedMigrationAndExplicitNoneNeverResurrectLegacy() = withDb { db, repo ->
        val raw = source(deadline = 777)
        repo.migrate(listOf(raw)); repo.setDue("task", TaskDue.None)
        repo.migrate(listOf(raw, raw))
        assertEquals(TaskDue.None, repo.find("task")!!.due)
        assertEquals(1, db.queryInt("SELECT count(*) FROM TaskDueLegacySourceV1"))
    }

    @Test fun changedSourcesAccumulateWithoutReplacingUserEditOrOriginal() = withDb { db, repo ->
        val original = source(plan = "")
        val changed = original.copy(rawPlannedDate = "null", updatedAt = 124)
        val changedAgain = changed.copy(rawPlannedDate = null, sourceOperationId = "another-op")
        repo.migrate(listOf(original))
        val due = TaskDue.Day(TaskDueDate.parse("2026-12-31"))
        repo.setDue("task", due)
        repo.migrate(listOf(changed, changedAgain, changed))
        val result = repo.find("task")!!
        assertEquals(original, result.source); assertEquals(due, result.due)
        assertEquals(setOf(changed, changedAgain), result.pendingSources)
        assertEquals(3, db.queryInt("SELECT count(*) FROM TaskDueLegacySourceV1"))
    }

    @Test fun batchFailureRollsBackEveryTaskAndSource() = withDb { db, repo ->
        val interrupted = Iterable {
            object : Iterator<LegacyTaskDueSnapshot> {
                var next = 0
                override fun hasNext(): Boolean { if (next > 0) error("simulated interruption before COMMIT"); return true }
                override fun next(): LegacyTaskDueSnapshot { next++; return source() }
            }
        }
        try { repo.migrate(interrupted); fail("Expected interruption") } catch (_: IllegalStateException) { }
        assertNull(repo.find("task"))
        assertEquals(0, db.queryInt("SELECT count(*) FROM TaskDueLegacySourceV1"))
    }

    @Test fun contradictoryStoredShapesFailWithoutRepairingRawRows() = withDb { db, repo ->
        repo.migrate(listOf(source()))
        // Disposable malformed fixture simulates a corrupt/imported database bypassing DDL checks.
        db.run("PRAGMA ignore_check_constraints = ON")
        val malformed = listOf(
            "kind='NONE',day='2026-10-22',instant_ms=NULL,origin_zone=NULL",
            "kind='NONE',day=NULL,instant_ms=123,origin_zone=NULL",
            "kind='NONE',day=NULL,instant_ms=NULL,origin_zone='UTC'",
            "kind='DAY',day='2026-10-22',instant_ms=123,origin_zone=NULL",
            "kind='DAY',day='2026-10-22',instant_ms=NULL,origin_zone='UTC'",
            "kind='DAY',day=NULL,instant_ms=NULL,origin_zone=NULL",
            "kind='TIMED',day='2026-10-22',instant_ms=123,origin_zone=NULL",
            "kind='TIMED',day=NULL,instant_ms=NULL,origin_zone=NULL"
        )
        for (shape in malformed) {
            db.run("UPDATE TaskDueV1 SET $shape")
            val before = db.queryString("SELECT quote(kind)||quote(day)||quote(instant_ms)||quote(origin_zone) FROM TaskDueV1")
            try { repo.find("task"); fail("Expected rejection of $shape") } catch (_: IllegalArgumentException) { }
            assertEquals(before, db.queryString("SELECT quote(kind)||quote(day)||quote(instant_ms)||quote(origin_zone) FROM TaskDueV1"))
            assertEquals(1, db.queryInt("SELECT count(*) FROM TaskDueLegacySourceV1"))
        }
    }

    @Test fun unknownStoredVersionAndKindFailWithoutChangingSource() = withDb { db, repo ->
        val raw = source(plan = "  invalid raw  ")
        repo.migrate(listOf(raw))
        db.run("PRAGMA ignore_check_constraints = ON")
        db.run("UPDATE TaskDueV1 SET version=2")
        try { repo.find("task"); fail("Expected unsupported version") } catch (_: IllegalArgumentException) { }
        assertEquals(2, db.queryInt("SELECT version FROM TaskDueV1"))
        db.run("UPDATE TaskDueV1 SET version=1,kind='FUTURE'")
        try { repo.find("task"); fail("Expected unsupported kind") } catch (_: IllegalStateException) { }
        assertEquals("FUTURE", db.queryString("SELECT kind FROM TaskDueV1"))
        assertEquals(raw.rawPlannedDate, db.queryString("SELECT raw_plan FROM TaskDueLegacySourceV1"))
    }

    @Test fun futurePhysicalColumnsRejectEveryWriteAndKeepRowsIntact() {
        for (table in listOf("TaskDueV1", "TaskDueLegacySourceV1")) withDb { db, repo ->
            val raw = source(plan = "raw original", deadline = 777)
            repo.migrate(listOf(raw))
            val before = repo.find("task")!!
            db.run("ALTER TABLE $table ADD COLUMN future_field TEXT")
            db.run("UPDATE $table SET future_field='future payload'")
            for (write in listOf<() -> Unit>(
                { repo.setDue("task", TaskDue.None) },
                { repo.migrate(listOf(raw.copy(updatedAt = 999))) },
                { TaskDueSchema.createSchema(db) }
            )) {
                try { write(); fail("Expected future physical schema rejection") } catch (_: IllegalArgumentException) { }
                assertEquals("future payload", db.queryString("SELECT future_field FROM $table"))
                assertEquals(before, repo.find("task"))
                assertEquals(1, db.queryInt("SELECT count(*) FROM TaskDueLegacySourceV1"))
            }
        }
    }

    @Test fun schemaRejectsUnknownExistingTableBeforeCreatingCompanion() {
        val db = JavaDatabaseOpener().open(":memory:")
        try {
            db.run("CREATE TABLE TaskDueV1 (future_field TEXT)")
            db.run("INSERT INTO TaskDueV1 VALUES ('preserve')")
            try { TaskDueSchema.createSchema(db); fail("Expected unsupported table") } catch (_: IllegalArgumentException) { }
            assertEquals("preserve", db.queryString("SELECT future_field FROM TaskDueV1"))
            assertEquals(0, db.queryInt("SELECT count(*) FROM sqlite_master WHERE name='TaskDueLegacySourceV1'"))
        } finally { db.close() }
    }

    @Test fun unknownSourceFormatRollsBackBatchWithoutWritingAnything() = withDb { db, repo ->
        try {
            repo.migrate(listOf(source("known"), source("unknown").copy(sourceFormat = "future-source-v99")))
            fail("Expected unsupported source format")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, db.queryInt("SELECT count(*) FROM TaskDueV1"))
        assertEquals(0, db.queryInt("SELECT count(*) FROM TaskDueLegacySourceV1"))
    }

    @Test fun oldReplaceAndDeleteCannotCascadeAwayDueProvenance() = withDb { db, repo ->
        db.run("PRAGMA foreign_keys = ON")
        db.run("CREATE TABLE UserTasks (uuid TEXT PRIMARY KEY, title TEXT)")
        db.run("INSERT INTO UserTasks VALUES ('task','before')")
        repo.migrate(listOf(source(deadline = 777)))
        db.run("INSERT OR REPLACE INTO UserTasks VALUES ('task','after')")
        db.run("DELETE FROM UserTasks WHERE uuid = 'task'")
        assertEquals(TaskDue.Timed.legacy(777), repo.find("task")!!.due)
        // Runtime wiring must explicitly delete/archive Due on task deletion; no runtime wiring exists.
    }

    @Test fun connectionCloseBeforeOuterCommitRollsBackOnReopen() {
        val directory = File("build/tmp/task-due-close").apply { mkdirs() }
        val file = File.createTempFile("due-", ".sqlite", directory)
        try {
            var db = JavaDatabaseOpener().open(file.absolutePath)
            TaskDueSchema.createSchema(db)
            db.beginTransaction()
            // The repository's nested transaction may succeed; the outer commit is never reached.
            SQLiteTaskDueRepository(db).migrate(listOf(source(deadline = 777)))
            db.close()
            db = JavaDatabaseOpener().open(file.absolutePath)
            try {
                assertNull(SQLiteTaskDueRepository(db).find("task"))
                assertEquals(0, db.queryInt("SELECT count(*) FROM TaskDueLegacySourceV1"))
            } finally { db.close() }
        } finally { file.delete(); directory.delete() }
    }

    @Test fun fileReopenPreservesAllKindsAndPendingRawProvenance() {
        // Uses Gradle's configured build folder (on D), never a global C temp directory.
        val directory = File("build/tmp/task-due-reopen").apply { mkdirs() }
        val file = File.createTempFile("due-", ".sqlite", directory)
        try {
            var db = JavaDatabaseOpener().open(file.absolutePath)
            try {
                TaskDueSchema.createSchema(db)
                val repo = SQLiteTaskDueRepository(db)
                repo.migrate(listOf(source("none"), source("day"), source("timed")))
                repo.setDue("day", TaskDue.Day(TaskDueDate.parse("2024-02-29")))
                repo.setDue("timed", TaskDue.Timed.local(1234567890123, "Asia/Novosibirsk"))
                repo.migrate(listOf(source("none", "  invalid  ").copy(sourceRevision = "revision-9")))
            } finally { db.close() }
            db = JavaDatabaseOpener().open(file.absolutePath)
            try {
                TaskDueSchema.createSchema(db)
                val repo = SQLiteTaskDueRepository(db)
                assertEquals(TaskDue.None, repo.find("none")!!.due)
                assertEquals("  invalid  ", repo.find("none")!!.pendingSources.single().rawPlannedDate)
                assertEquals(TaskDue.Day(TaskDueDate.parse("2024-02-29")), repo.find("day")!!.due)
                assertEquals(TaskDue.Timed.local(1234567890123, "Asia/Novosibirsk"), repo.find("timed")!!.due)
            } finally { db.close() }
        } finally { file.delete(); directory.delete() }
    }
}
