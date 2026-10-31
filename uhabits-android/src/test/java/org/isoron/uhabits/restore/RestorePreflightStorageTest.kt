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

package org.isoron.uhabits.restore

import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.backup.BackupRestoreException
import org.isoron.uhabits.core.containers.backup.BackupCreationResult
import org.isoron.uhabits.core.containers.backup.ContainerBackupService
import org.isoron.uhabits.core.containers.backup.ContainerRestoreService
import org.isoron.uhabits.core.containers.backup.RestoreResult
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.production.migration.MigrationFailurePoint
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult
import org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit test suite for Sprint 03 Phase F: GAP-12 Storage/Restore/Backup/Migration Resilience.
 *
 * Explicitly separates and validates all 5 scenarios:
 * 1. Preflight check before restore: 0L available space, -1L unavailable space, requiredBytes - 1L,
 *    exact boundary requiredBytes, negative DB size (CorruptedArchiveException), and 64-bit overflow (InsufficientStorageException).
 * 2. Real SQLite write failure (SQLITE_FULL / disk write error during restore): Verify that ContainerRestoreService.restore
 *    fails closed (RestoreResult.Failure) rather than reporting partial success.
 * 3. Failure / insufficient space during backup creation: Verify that ContainerBackupService.createBackup returns
 *    BackupCreationResult.Failure cleanly without crashing.
 * 4. Failure during migration: Verify that if a SQLite migration step fails mid-execution inside a transaction,
 *    the transaction rolls back cleanly and preserves the prior schema version and existing rows intact.
 * 5. Preservation of the last valid database copy: Verify that when ContainerRestoreService.restore fails during
 *    staging validation or copy, the existing valid data in destinationDb is preserved and not left corrupted or empty.
 */
class RestorePreflightStorageTest {

    // =========================================================================
    // Scenario 1: Preflight check before restore (GAP-12 Space Decisions)
    // =========================================================================

    @Test
    fun computeRequiredRestoreBytes_calculatesCorrectOverhead() {
        val tenMb = 10 * 1024 * 1024L
        assertEquals(tenMb, DatabaseUtils.computeRequiredRestoreBytes(0L))
        assertEquals(tenMb + 3000L, DatabaseUtils.computeRequiredRestoreBytes(1000L))
        assertEquals(tenMb + 30_000_000L, DatabaseUtils.computeRequiredRestoreBytes(10_000_000L))
    }

    @Test
    fun computeRequiredRestoreBytes_failsClosedOnNegativeDatabaseSize() {
        val ex1 = assertThrows(BackupRestoreException.CorruptedArchiveException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(-1L)
        }
        assertTrue(ex1.message!!.contains("Invalid negative database size"))

        val ex2 = assertThrows(BackupRestoreException.CorruptedArchiveException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(-5000L)
        }
        assertTrue(ex2.message!!.contains("-5000"))
    }

    @Test
    fun computeRequiredRestoreBytes_failsClosedOnArithmeticOverflow() {
        val ex1 = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(Long.MAX_VALUE)
        }
        assertTrue(ex1.message!!.contains("overflow"))

        val ex2 = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(Long.MAX_VALUE / 2)
        }
        assertTrue(ex2.message!!.contains("overflow"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_reproducesAndRejectsZeroAvailableSpace() {
        val requiredBytes = DatabaseUtils.computeRequiredRestoreBytes(1000L)

        val ex = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.checkRestoreFreeSpace(availableBytes = 0L, requiredBytes = requiredBytes)
        }
        assertTrue(ex.message!!.contains("required $requiredBytes bytes"))
        assertTrue(ex.message!!.contains("available 0 bytes"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_rejectsNegativeOrUnavailableSpace() {
        val requiredBytes = DatabaseUtils.computeRequiredRestoreBytes(1000L)

        val ex = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.checkRestoreFreeSpace(availableBytes = -1L, requiredBytes = requiredBytes)
        }
        assertTrue(ex.message!!.contains("available -1 bytes"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_rejectsStrictlyInsufficientSpace() {
        val requiredBytes = 10_000_000L

        val ex = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.checkRestoreFreeSpace(availableBytes = requiredBytes - 1L, requiredBytes = requiredBytes)
        }
        assertTrue(ex.message!!.contains("available ${requiredBytes - 1L} bytes"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_acceptsExactBoundaryAndGreaterSpace() {
        val requiredBytes = 10_000_000L

        // Exact boundary: available == requiredBytes -> passes without throwing
        DatabaseUtils.checkRestoreFreeSpace(availableBytes = requiredBytes, requiredBytes = requiredBytes)

        // Above boundary: available == requiredBytes + 1L -> passes without throwing
        DatabaseUtils.checkRestoreFreeSpace(availableBytes = requiredBytes + 1L, requiredBytes = requiredBytes)
    }

    // =========================================================================
    // Scenario 2: Real SQLite write failure (SQLITE_FULL / disk error)
    // =========================================================================

    @Test
    fun restore_realSqliteWriteFailure_failsClosedWithFailureResult() {
        val (sourceDb, backupDb) = createValidBackupDb()
        val stagingDb = JavaDatabaseOpener().open(":memory:")
        val destinationDb = JavaDatabaseOpener().open(":memory:")
        try {
            // Simulate real SQLite write failure (SQLITE_FULL) on staging during restore
            seedLegacyDatabaseTables(stagingDb)
            stagingDb.run(
                """
                CREATE TRIGGER simulate_sqlite_full BEFORE INSERT ON Habits
                BEGIN
                    SELECT RAISE(ABORT, 'database or disk is full');
                END;
                """.trimIndent()
            )

            val restoreResult = ContainerRestoreService.restore(
                backupDb = backupDb,
                stagingDb = stagingDb,
                destinationDb = destinationDb,
            )

            assertTrue("Restore must fail closed on SQLITE_FULL", restoreResult is RestoreResult.Failure)
            val failure = restoreResult as RestoreResult.Failure
            assertTrue(
                "Actual failure reason: '${failure.reason}', cause: '${failure.cause?.message}'",
                failure.reason.contains("database or disk is full", ignoreCase = true) ||
                    failure.cause?.message?.contains("database or disk is full", ignoreCase = true) == true
            )
        } finally {
            sourceDb.close()
            backupDb.close()
            stagingDb.close()
            destinationDb.close()
        }
    }

    // =========================================================================
    // Scenario 3: Failure / insufficient space during backup creation
    // =========================================================================

    @Test
    fun backupCreation_insufficientSpaceOrWriteFailure_returnsFailureResultCleanly() {
        val sourceDb = JavaDatabaseOpener().open(":memory:")
        seedValidExperimentalDatabase(sourceDb)
        val targetDb = JavaDatabaseOpener().open(":memory:")
        try {
            // Simulate disk write error / insufficient storage during backup creation
            targetDb.run("CREATE TABLE IF NOT EXISTS Habits (id INTEGER PRIMARY KEY, name TEXT)")
            targetDb.run(
                """
                CREATE TRIGGER simulate_backup_write_failure BEFORE INSERT ON Habits
                BEGIN
                    SELECT RAISE(ABORT, 'disk I/O error: disk full during backup creation');
                END;
                """.trimIndent()
            )

            val backupResult = ContainerBackupService.createBackup(
                sourceDb = sourceDb,
                targetDb = targetDb,
                timestamp = 1700000000L,
            )

            assertTrue("Backup creation must return Failure cleanly without crashing", backupResult is BackupCreationResult.Failure)
            val failure = backupResult as BackupCreationResult.Failure
            assertTrue(
                "Error message or cause must reflect write failure",
                failure.error.contains("failed") || failure.cause?.message?.contains("disk full") == true || failure.cause != null
            )
        } finally {
            sourceDb.close()
            targetDb.close()
        }
    }

    // =========================================================================
    // Scenario 4: Failure during migration (Transaction Rollback & Invariant Integrity)
    // =========================================================================

    @Test
    fun migration_midExecutionFailureInsideTransaction_rollsBackCleanlyPreservingPriorSchemaAndRows() {
        val db = JavaDatabaseOpener().open(":memory:")
        try {
            db.setVersion(29)
            seedLegacyDatabaseTables(db)
            db.run("INSERT INTO Habits(id, uuid, name, position, archived, type) VALUES (1, 'habit-pre-1', 'Pre-migration Habit', 0, 0, 0)")
            db.run("INSERT INTO Repetitions(habit, timestamp, value) VALUES (1, 1000000, 2)")

            assertEquals(29, db.getVersion())
            assertEquals(1, db.querySingle("SELECT count(*) FROM Habits") { it.getInt(0) })

            // 1. Simulate a migration step failing mid-execution inside runInTransaction
            var caughtException = false
            try {
                db.runInTransaction {
                    db.run("INSERT INTO Habits(id, uuid, name, position, archived, type) VALUES (2, 'habit-mid-2', 'Uncommitted Habit', 1, 0, 0)")
                    db.run("CREATE TABLE PartialMigrationStep (id INTEGER PRIMARY KEY)")
                    throw IllegalStateException("Simulated SQLite migration failure mid-transaction")
                }
            } catch (_: Exception) {
                caughtException = true
            }

            assertTrue("Exception should have been caught", caughtException)
            assertEquals("Prior schema version 29 must be preserved", 29, db.getVersion())
            assertEquals("Existing valid rows in Habits must remain intact", 1, db.querySingle("SELECT count(*) FROM Habits") { it.getInt(0) })
            assertEquals("Existing habit name must remain unchanged", "Pre-migration Habit", db.querySingle("SELECT name FROM Habits WHERE id = 1") { it.getText(0) })
            assertEquals("Existing repetition row must remain intact", 1, db.querySingle("SELECT count(*) FROM Repetitions WHERE habit = 1") { it.getInt(0) })

            // 2. Also verify ProductionNextSchemaMigrator rollback on failure injection
            val migratorResult = ProductionNextSchemaMigrator.migrate(
                db = db,
                cutoverTimestamp = 1700000000L,
                failureInjectionPoint = MigrationFailurePoint.AFTER_DDL,
            )
            assertTrue("Production migration with failure injection must fail", migratorResult is ProductionNextMigrationResult.Failure)
            assertEquals("Schema version must remain 29 after migrator failure", 29, db.getVersion())
            assertEquals("Habits count must remain 1 after migrator rollback", 1, db.querySingle("SELECT count(*) FROM Habits") { it.getInt(0) })
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // Scenario 5: Preservation of the last valid database copy
    // =========================================================================

    @Test
    fun restore_whenValidationOrCopyFails_preservesExistingValidDatabaseIntact() {
        val destinationDb = JavaDatabaseOpener().open(":memory:")
        val stagingDb = JavaDatabaseOpener().open(":memory:")
        val corruptBackupDb = JavaDatabaseOpener().open(":memory:")

        try {
            // 1. Destination has existing valid data
            destinationDb.setVersion(30)
            seedLegacyDatabaseTables(destinationDb)
            destinationDb.run(
                "INSERT INTO Habits (id, uuid, name, position, archived, type) VALUES (99, 'valid-uuid-99', 'Last Valid Habit', 0, 0, 0)"
            )
            assertEquals("Last Valid Habit", destinationDb.querySingle("SELECT name FROM Habits WHERE id = 99") { it.getText(0) })

            // 2. Corrupt backup with invalid schema / missing manifest
            corruptBackupDb.setVersion(30)
            corruptBackupDb.run("CREATE TABLE Habits (id INTEGER PRIMARY KEY, name TEXT)")
            corruptBackupDb.run("INSERT INTO Habits (id, name) VALUES (1, 'Corrupted Habit')")

            val restoreResult = ContainerRestoreService.restore(
                backupDb = corruptBackupDb,
                stagingDb = stagingDb,
                destinationDb = destinationDb,
            )

            assertTrue("Restore must fail on corrupted/invalid backup", restoreResult is RestoreResult.Failure)

            // 3. Verify destinationDb was not corrupted, emptied, or partially overwritten
            assertEquals("Schema version must remain intact", 30, destinationDb.getVersion())
            val preservedHabitCount = destinationDb.querySingle("SELECT count(*) FROM Habits") { it.getInt(0) }
            assertEquals("Original valid habit count must be preserved", 1, preservedHabitCount)
            val preservedHabitName = destinationDb.querySingle("SELECT name FROM Habits WHERE id = 99") { it.getText(0) }
            assertEquals("Original habit record must remain completely intact", "Last Valid Habit", preservedHabitName)
            val corruptedHabitCount = destinationDb.querySingle("SELECT count(*) FROM Habits WHERE name = 'Corrupted Habit'") { it.getInt(0) }
            assertEquals("Corrupted data must not leak into destination database", 0, corruptedHabitCount)
        } finally {
            destinationDb.close()
            stagingDb.close()
            corruptBackupDb.close()
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun seedLegacyDatabaseTables(db: Database) {
        db.run("CREATE TABLE IF NOT EXISTS Habits (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', question TEXT NOT NULL DEFAULT '', freq_num INTEGER NOT NULL DEFAULT 1, freq_den INTEGER NOT NULL DEFAULT 1, color INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0, reminder_hour INTEGER, reminder_min INTEGER, reminder_days INTEGER NOT NULL DEFAULT 0, highlight INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 0, target_value REAL NOT NULL DEFAULT 0.0, target_type INTEGER NOT NULL DEFAULT 0, unit TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
        db.run("CREATE TABLE IF NOT EXISTS Repetitions (habit INTEGER NOT NULL, timestamp INTEGER NOT NULL, value INTEGER NOT NULL, notes TEXT, uuid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
        db.run("CREATE TABLE IF NOT EXISTS HabitBlocks (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL, icon TEXT, position INTEGER NOT NULL, is_archived INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
        db.run("CREATE TABLE IF NOT EXISTS HabitGoals (id INTEGER PRIMARY KEY AUTOINCREMENT, habit_id INTEGER NOT NULL, effective_timestamp INTEGER NOT NULL, freq_num INTEGER NOT NULL, freq_den INTEGER NOT NULL, target_type INTEGER NOT NULL, target_value REAL NOT NULL, unit TEXT NOT NULL DEFAULT '', uuid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
        db.run("CREATE TABLE IF NOT EXISTS HabitExtensions (habit_id INTEGER PRIMARY KEY, day_tier TEXT NOT NULL DEFAULT 'NORMAL', timer_enabled INTEGER NOT NULL DEFAULT 0, block_id INTEGER, stats_start_timestamp INTEGER)")
        db.run("CREATE TABLE IF NOT EXISTS AppSettings (key TEXT PRIMARY KEY, long_value INTEGER)")
        db.run("CREATE TABLE IF NOT EXISTS SyncQueue (queue_id INTEGER PRIMARY KEY AUTOINCREMENT, op_uuid TEXT NOT NULL UNIQUE, entity_type TEXT NOT NULL, entity_uuid TEXT NOT NULL, operation_type TEXT NOT NULL, payload_json TEXT NOT NULL, created_at INTEGER NOT NULL, device_id TEXT NOT NULL, pushed_at INTEGER, failed_at INTEGER, failure_reason TEXT)")
        db.run("CREATE TABLE IF NOT EXISTS EntryOps (op_uuid TEXT PRIMARY KEY, habit_uuid TEXT NOT NULL, entry_timestamp INTEGER NOT NULL, delta_value INTEGER NOT NULL, op_type TEXT NOT NULL, notes TEXT, device_id TEXT NOT NULL, created_at INTEGER NOT NULL, deleted_at INTEGER)")
    }

    private fun seedValidExperimentalDatabase(db: Database) {
        db.setVersion(30)
        seedLegacyDatabaseTables(db)
        db.runInTransaction {
            OrganizationSchema.createSchema(db, targetVersion = 30)
            UserTaskSchema.createSchema(db, targetVersion = 30)
            FocusSessionSchema.createSchema(db)
            ContributionSchema.createSchema(db)

            val baseTime = 1700000000L
            db.run(
                "INSERT OR IGNORE INTO OrganizationChanges (revision, op_uuid, recorded_at, operation_type, origin, command_payload) " +
                    "VALUES (1, 'baseline-cutover', $baseTime, 'BASELINE', 'BASELINE', 'BASELINE')"
            )
            db.run(
                "INSERT OR REPLACE INTO OrganizationState (" +
                    "id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, cutover_at, source_schema_version, source_snapshot_sha256" +
                    ") VALUES (1, 'dataset-uuid-1', 1, 'CONTAINER_LOCAL', 1, 1, $baseTime, 29, 'sha-1')"
            )
            db.run(
                """
                INSERT OR IGNORE INTO Habits(id, uuid, name, position, archived, type)
                VALUES (1, 'baseline-habit-uuid', 'Baseline Habit', 0, 0, 0)
                """.trimIndent()
            )
            db.run(
                """
                INSERT OR IGNORE INTO Repetitions(habit, timestamp, value)
                VALUES (1, 1700000000, 2)
                """.trimIndent()
            )
            db.run(
                """
                INSERT OR IGNORE INTO Containers(id, uuid, name, sibling_order, is_archived, updated_at, revision)
                VALUES (1, 'root-container-uuid', 'Root Container', 0, 0, $baseTime, 1)
                """.trimIndent()
            )
            db.run(
                """
                INSERT OR IGNORE INTO HabitPlacements(habit_id, container_id, local_order, placement_origin, revision)
                VALUES (1, 1, 0, 'USER_CHANGE', 1)
                """.trimIndent()
            )
            db.run(
                """
                INSERT OR IGNORE INTO ContainerHistory(container_id, revision, parent_id, name, color, icon, sibling_order, is_archived, created_at, updated_at, deleted_at)
                VALUES (1, 1, NULL, 'Root Container', NULL, NULL, 0, 0, $baseTime, $baseTime, NULL)
                """.trimIndent()
            )
            db.run(
                """
                INSERT OR IGNORE INTO HabitPlacementHistory(habit_id, revision, container_id, local_order, placement_origin)
                VALUES (1, 1, 1, 0, 'USER_CHANGE')
                """.trimIndent()
            )
            db.run(
                """
                INSERT OR IGNORE INTO HabitBlocks(id, uuid, name, color, position, is_archived, updated_at)
                VALUES (1, 'block-uuid-1', 'Root Block', 0, 0, 0, $baseTime)
                """.trimIndent()
            )
            db.run(
                """
                INSERT OR IGNORE INTO LegacyBlockMap(legacy_block_id, legacy_block_uuid, container_id)
                VALUES (1, 'block-uuid-1', 1)
                """.trimIndent()
            )
        }
    }

    private fun createValidBackupDb(): Pair<Database, Database> {
        val sourceDb = JavaDatabaseOpener().open(":memory:")
        seedValidExperimentalDatabase(sourceDb)
        val backupDb = JavaDatabaseOpener().open(":memory:")
        val backupRes = ContainerBackupService.createBackup(sourceDb, backupDb, 1700000000L)
        val failureDetail = (backupRes as? BackupCreationResult.Failure)?.let { "error=${it.error}, cause=${it.cause?.message}" } ?: ""
        assertTrue("Initial backup creation must succeed: $failureDetail", backupRes is BackupCreationResult.Success)
        return Pair(sourceDb, backupDb)
    }
}
