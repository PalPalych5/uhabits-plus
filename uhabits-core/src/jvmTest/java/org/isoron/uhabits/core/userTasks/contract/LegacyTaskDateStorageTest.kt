/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.contract

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.containers.backup.DatabaseSnapshotter
import org.isoron.uhabits.core.containers.backup.BackupCreationResult
import org.isoron.uhabits.core.containers.backup.ContainerBackupService
import org.isoron.uhabits.core.containers.backup.ContainerRestoreService
import org.isoron.uhabits.core.containers.backup.RestoreResult
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.MigrationExecutionResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.parseIsoDateString
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.junit.Assert.*
import org.junit.Test

/** SQLite/snapshot tests verifying that unified task due survives backup, restore, and snapshot copying. */
class LegacyTaskDateStorageTest {
    private fun rows(db: Database): List<Triple<String, String, String?>> {
        val stmt = db.prepareStatement("SELECT id, due_kind, due_date FROM UserTasks ORDER BY id")
        return try {
            buildList { while (stmt.step() == StepResult.ROW) add(Triple(stmt.getText(0), stmt.getText(1), stmt.getTextOrNull(2))) }
        } finally { stmt.finalize() }
    }

    private fun columns(db: Database): Set<String> {
        val stmt = db.prepareStatement("PRAGMA table_info(UserTasks)")
        return try { buildSet { while (stmt.step() == StepResult.ROW) add(stmt.getText(1)) } }
        finally { stmt.finalize() }
    }

    @Test fun unifiedDueTasksSurviveSnapshotAndBackupRestore() = runBlocking {
        val source = SemanticMigrationFixtures.createAndSeedV29Database()
        val target = TestDatabaseHelper.createEmptyDatabase()
        val staging = TestDatabaseHelper.createEmptyDatabase()
        val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            val plan = LegacyContainerMigrationPlanner.plan(RawLegacyInventoryReader.read(source), cutoverTimestamp = 1000000L)
            assertTrue(LegacyContainerMigrationExecutor.execute(source, plan) is MigrationExecutionResult.Success)
            source.setVersion(org.isoron.uhabits.core.DATABASE_VERSION)
            UserTaskSchema.createSchema(source)

            val repo = SQLiteUserTaskRepository(source)
            val tNone = org.isoron.uhabits.core.userTasks.UserTask(
                id = TaskId("task-none"),
                title = "No Due",
                due = TaskDue.None,
                createdAt = 1000L,
                updatedAt = 1000L,
            )
            val tDay = org.isoron.uhabits.core.userTasks.UserTask(
                id = TaskId("task-day"),
                title = "Day Due",
                due = TaskDue.Day(TaskDueDate.parse("2026-10-19")),
                createdAt = 1000L,
                updatedAt = 1000L,
            )
            val tTimed = org.isoron.uhabits.core.userTasks.UserTask(
                id = TaskId("task-timed"),
                title = "Timed Due",
                due = TaskDue.Timed.of(1792432800000L, "Asia/Novosibirsk"),
                createdAt = 1000L,
                updatedAt = 1000L,
            )
            repo.save(tNone)
            repo.save(tDay)
            repo.save(tTimed)

            val original = rows(source)
            DatabaseSnapshotter.initTargetSchema(source, target)
            repeat(2) {
                DatabaseSnapshotter.copyAllData(source, target)
                assertEquals(original, rows(target))
                assertEquals(original, rows(source))
                assertEquals(source.getVersion(), target.getVersion())
            }
            assertEquals(3, original.size)

            val targetRepo = SQLiteUserTaskRepository(target)
            assertEquals(TaskDue.None, targetRepo.getById(TaskId("task-none"))?.due)
            assertEquals(TaskDue.Day(TaskDueDate.parse("2026-10-19")), targetRepo.getById(TaskId("task-day"))?.due)
            assertEquals(tTimed.due, targetRepo.getById(TaskId("task-timed"))?.due)

            assertTrue(ContainerBackupService.createBackup(source, target, 3000000L) is BackupCreationResult.Success)
            repeat(2) {
                assertTrue(ContainerRestoreService.restore(target, staging, destination) is RestoreResult.Success)
                assertEquals(original, rows(destination))
                assertEquals(original, rows(source))
            }

            val destRepo = SQLiteUserTaskRepository(destination)
            assertEquals(TaskDue.None, destRepo.getById(TaskId("task-none"))?.due)
            assertEquals(TaskDue.Day(TaskDueDate.parse("2026-10-19")), destRepo.getById(TaskId("task-day"))?.due)
            assertEquals(tTimed.due, destRepo.getById(TaskId("task-timed"))?.due)
        } finally { source.close(); target.close(); staging.close(); destination.close() }
    }

    @Test fun olderDevDatabaseWithPlannedDateAndDeadlineMigratesNonDestructively() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            // Simulate older dev database table with planned_date and deadline
            db.run("""
                CREATE TABLE UserTasks (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    container_id TEXT,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    planned_date TEXT,
                    deadline INTEGER,
                    completed_at INTEGER,
                    parent_task_id TEXT,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())

            db.run("INSERT INTO UserTasks VALUES ('d1', 'Planned Only Future', '', NULL, 'PENDING', '2029-10-19', NULL, NULL, NULL, 0, 1000, 1000)")
            db.run("INSERT INTO UserTasks VALUES ('d2', 'Deadline Only', '', NULL, 'PENDING', NULL, 1792432800000, NULL, NULL, 0, 1000, 1000)")
            db.run("INSERT INTO UserTasks VALUES ('d3', 'Both Set', '', NULL, 'PENDING', '2029-10-19', 1792432800000, NULL, NULL, 0, 1000, 1000)")
            db.run("INSERT INTO UserTasks VALUES ('d4', 'Neither Set', '', NULL, 'PENDING', NULL, NULL, NULL, NULL, 0, 1000, 1000)")
            db.run("INSERT INTO UserTasks VALUES ('d5', 'Planned Only Past', '', NULL, 'PENDING', '2020-01-15', NULL, NULL, NULL, 0, 1000, 1000)")

            // Running createSchema inspects columns and runs the ALTER TABLE migration
            UserTaskSchema.createSchema(db)

            val repo = SQLiteUserTaskRepository(db)
            assertEquals(TaskDue.Day(TaskDueDate.parse("2029-10-19")), repo.getById(TaskId("d1"))?.due)
            assertEquals(TaskDue.Timed.of(1792432800000L), repo.getById(TaskId("d2"))?.due)
            // Deadline takes precedence as TIMED
            assertEquals(TaskDue.Timed.of(1792432800000L), repo.getById(TaskId("d3"))?.due)
            assertEquals(TaskDue.None, repo.getById(TaskId("d4"))?.due)
            // Plan-only in past must NOT automatically become overdue deadline
            assertEquals(TaskDue.None, repo.getById(TaskId("d5"))?.due)
            assertEquals("2020-01-15", db.queryString("SELECT planned_date FROM UserTasks WHERE id = 'd5'"))
        } finally { db.close() }
    }

    @Test fun snapshotRejectsNewerSchemaBeforeChangingTarget() = runBlocking {
        val source = TestDatabaseHelper.createEmptyDatabase()
        val target = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(source)
            source.setVersion(org.isoron.uhabits.core.DATABASE_VERSION + 1)
            UserTaskSchema.createSchema(target)
            target.setVersion(29)
            try {
                DatabaseSnapshotter.initTargetSchema(source, target)
                fail("Unsupported source version should be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Unsupported source database schema version"))
            }
        } finally { source.close(); target.close() }
    }

    @Test fun knownLegacyParserNormalizesImpossibleDatesInsteadOfRejectingThem() {
        val normalized = parseIsoDateString("2026-02-30")
        assertNotNull(normalized)
        assertEquals(org.isoron.platform.time.LocalDate(2026, 3, 2), normalized)
        assertNull(parseIsoDateString("1792368000000"))
    }
}
