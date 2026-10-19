/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.contract

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
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
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.junit.Assert.*
import org.junit.Test

/** Real current SQLite/snapshot adapters on in-memory fixtures, not a production migration. */
class LegacyTaskDateStorageTest {
    private fun rows(db: Database): List<Triple<String, String?, Long?>> {
        val stmt = db.prepareStatement("SELECT id, planned_date, deadline FROM UserTasks ORDER BY id")
        return try {
            buildList { while (stmt.step() == StepResult.ROW) add(Triple(stmt.getText(0), stmt.getTextOrNull(1), stmt.getLongOrNull(2))) }
        } finally { stmt.finalize() }
    }

    private fun columns(db: Database): Set<String> {
        val stmt = db.prepareStatement("PRAGMA table_info(UserTasks)")
        return try { buildSet { while (stmt.step() == StepResult.ROW) add(stmt.getText(1)) } }
        finally { stmt.finalize() }
    }

    @Test fun rawLegacyDateMatrixSurvivesSnapshotAndRepeatedCopy() = runBlocking {
        val source = SemanticMigrationFixtures.createAndSeedV29Database()
        val target = TestDatabaseHelper.createEmptyDatabase()
        val staging = TestDatabaseHelper.createEmptyDatabase()
        val destination = TestDatabaseHelper.createEmptyDatabase()
        try {
            // Existing fixture initialization only; no new due migration is executed.
            val plan = LegacyContainerMigrationPlanner.plan(RawLegacyInventoryReader.read(source), cutoverTimestamp = 1000000L)
            assertTrue(LegacyContainerMigrationExecutor.execute(source, plan) is MigrationExecutionResult.Success)
            UserTaskSchema.createSchema(source)
            val plans = listOf(null, "2026-10-19", "2026-10-18", "1792368000000", "2026-02-30", "")
            val deadlines = listOf(null, 1792368000000L, 1792432800000L, 1792432800123L)
            var index = 0
            for (plan in plans) for (deadline in deadlines) {
                val stmt = source.prepareStatement("INSERT INTO UserTasks (id,title,planned_date,deadline,created_at,updated_at) VALUES (?,?,?,?,1,1)")
                try {
                    stmt.bindText(1, "date-${index++}"); stmt.bindText(2, "Synthetic date fixture")
                    if (plan == null) stmt.bindNull(3) else stmt.bindText(3, plan)
                    if (deadline == null) stmt.bindNull(4) else stmt.bindLong(4, deadline)
                    stmt.step()
                } finally { stmt.finalize() }
            }
            val original = rows(source)
            DatabaseSnapshotter.initTargetSchema(source, target)
            repeat(2) {
                DatabaseSnapshotter.copyAllData(source, target)
                assertEquals(original, rows(target))
                assertEquals(original, rows(source))
                assertEquals(source.getVersion(), target.getVersion())
            }
            assertEquals(24, original.size)
            val numericRawId = original.first { it.second == "1792368000000" }.first
            assertNull("Current model cannot read preserved numeric plan text", SQLiteUserTaskRepository(target).getById(TaskId(numericRawId))!!.plannedDate)
            assertTrue(ContainerBackupService.createBackup(source, target, 3000000L) is BackupCreationResult.Success)
            repeat(2) {
                assertTrue(ContainerRestoreService.restore(target, staging, destination) is RestoreResult.Success)
                assertEquals(original, rows(destination))
                assertEquals(original, rows(source))
            }
        } finally { source.close(); target.close(); staging.close(); destination.close() }
    }

    @Test fun knownCompatibilityRisk_currentSnapshotDropsUnknownFutureColumnsDespiteVersion() = runBlocking {
        val source = TestDatabaseHelper.createEmptyDatabase()
        val target = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(source)
            source.run("ALTER TABLE UserTasks ADD COLUMN proposed_due_v1 TEXT")
            source.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at,proposed_due_v1) VALUES ('future','Synthetic future shape','2026-10-19',1,1,'date-only:2026-10-19')")
            source.setVersion(31) // Simulate a future schema only in this disposable in-memory fixture.
            DatabaseSnapshotter.initTargetSchema(source, target)
            DatabaseSnapshotter.copyAllData(source, target)
            assertTrue("Source extension exists", "proposed_due_v1" in columns(source))
            assertFalse("Current hard-coded target schema drops unknown columns", "proposed_due_v1" in columns(target))
            assertEquals(31, target.getVersion())
            assertEquals(rows(source), rows(target))
        } finally { source.close(); target.close() }
    }

    @Test fun knownLegacyParserNormalizesImpossibleDatesInsteadOfRejectingThem() {
        val normalized = parseIsoDateString("2026-02-30")
        assertNotNull(normalized)
        assertEquals(org.isoron.platform.time.LocalDate(2026, 3, 2), normalized)
        assertNull(parseIsoDateString("1792368000000"))
    }
}
