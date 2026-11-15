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

package org.isoron.uhabits.core.database.migrations

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.io.setVersion
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.backup.BackupCreationResult
import org.isoron.uhabits.core.containers.backup.ContainerBackupService
import org.isoron.uhabits.core.containers.backup.ContainerRestoreService
import org.isoron.uhabits.core.containers.backup.RestoreResult
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.database.SQLParser
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.projection.HabitDayAdjustment
import org.isoron.uhabits.core.models.projection.SQLiteHabitDayAdjustmentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HabitDayAdjustmentsMigrationV35ToV36Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val opener = JavaDatabaseOpener()

    private fun getColumns(db: Database, table: String): Set<String> {
        val cols = mutableSetOf<String>()
        val stmt = db.prepareStatement("PRAGMA table_info($table)")
        try {
            while (stmt.step() == StepResult.ROW) cols.add(stmt.getText(1))
        } finally {
            stmt.finalize()
        }
        return cols
    }

    private fun tableExists(db: Database, table: String): Boolean =
        db.queryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$table'") > 0L

    @Test
    fun upgrade35To36AndBackupRestorePreservesExistingFactsAndAdjustments() = runTest {
        val dbFile = tempFolder.newFile("uhabits-v35-upgrade.db")
        val db = opener.open(dbFile.absolutePath)
        try {
            db.run("PRAGMA foreign_keys = ON")
            db.setVersion(8)
            db.migrateTo(35) { TestDatabaseHelper.loadMigrationSQL(it) }
            assertEquals(35, db.getVersion())

            // Seed Habits, Repetitions, HabitExtensions, Containers, UserTasks, FocusSessions, Contributions at v35
            db.run(
                "INSERT INTO Habits(id, name, freq_num, freq_den, color, position, archived, type, target_value, target_type, unit, uuid) " +
                    "VALUES (101, 'Focus Habit', 1, 1, 1, 0, 0, 1, 60.0, 0, 'min', 'h-focus-uuid')"
            )
            db.run(
                "INSERT INTO HabitExtensions(habit_id, day_tier, timer_enabled) " +
                    "VALUES (101, 'NORMAL', 1)"
            )
            val day = LocalDate(2026, 11, 10)
            db.run("INSERT INTO Repetitions(habit, timestamp, value, notes) VALUES (101, ${day.unixTime}, 10000, 'manual part')")
            db.run(
                "INSERT INTO OrganizationChanges(revision, op_uuid, recorded_at, operation_type, origin, command_payload) " +
                    "VALUES (1, 'op-v35-1', 1000, 'CREATE', 'MIGRATION', '{}')"
            )
            db.run(
                "INSERT INTO OrganizationState(" +
                    "id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, " +
                    "cutover_at, source_schema_version, source_snapshot_sha256, source_file_sha256, source_schema_sha256, " +
                    "runtime_authority, authority_epoch, conversion_op_uuid) VALUES (" +
                    "1, 'dataset-v35-test', 1, 'CONTAINER_LOCAL', 1, 1, 1000, 29, 'sha-snap', NULL, NULL, 'NEXT', 1, 'op-v35-1')"
            )
            db.run(
                "INSERT INTO Containers(id, uuid, parent_id, name, sibling_order, is_archived, updated_at, revision) " +
                    "VALUES (1, 'c-work', NULL, 'Work', 0, 0, 1000, 1)"
            )
            db.run(
                "INSERT INTO ContainerHistory(container_id, revision, parent_id, name, sibling_order, is_archived, updated_at) " +
                    "VALUES (1, 1, NULL, 'Work', 0, 0, 1000)"
            )
            db.run(
                "INSERT INTO HabitPlacements(habit_id, container_id, local_order, placement_origin, revision) " +
                    "VALUES (101, 1, 0, 'USER_CHANGE', 1)"
            )
            db.run(
                "INSERT INTO HabitPlacementHistory(habit_id, revision, container_id, local_order, placement_origin) " +
                    "VALUES (101, 1, 1, 0, 'USER_CHANGE')"
            )
            db.run(
                "INSERT INTO UserTasks(id, title, description, container_id, status, planned_date, deadline, due_kind, due_date, due_time_millis, due_zone, sort_order, created_at, updated_at) " +
                    "VALUES ('task-1', 'Existing Task', 'Body', 'c-work', 'PENDING', NULL, NULL, 'NONE', NULL, NULL, NULL, 0, 1000, 1000)"
            )
            FocusSessionSchema.createSchema(db)
            ContributionSchema.createSchema(db)
            val focusRepo = SQLiteFocusSessionRepository(db)
            focusRepo.record(
                FocusSession(
                    id = FocusSessionId("fs-1"),
                    startedAt = 1_762_732_800_000L,
                    endedAt = 1_762_734_300_000L,
                    durationMs = 1_500_000L,
                    source = FocusSessionSource.TIMER,
                    createdAt = 1_762_732_800_000L,
                    updatedAt = 1_762_734_300_000L,
                    primaryTargetType = FocusPrimaryTargetType.HABIT,
                    habit = HabitRef("h-focus-uuid"),
                )
            )
            val contribRepo = SQLiteContributionRepository(db)
            contribRepo.record(
                Contribution(
                    id = ContributionId("contrib-1"),
                    targetHabit = HabitRef("h-focus-uuid"),
                    sourceType = ContributionSourceType.FOCUS_SESSION,
                    sourceId = "fs-1",
                    contributedValue = 25_000L,
                    effectiveTimestamp = 1_762_734_300_000L,
                    createdAt = 1_762_734_300_000L,
                    updatedAt = 1_762_734_300_000L,
                )
            )

            // Simulate interrupted v35->v36 upgrade and verify atomic rollback
            val v36Sql = TestDatabaseHelper.loadMigrationSQL(36)
            val cmds = SQLParser.parse(v36Sql)
            assertTrue(cmds.size >= 2)
            val failingV36Sql = cmds.take(2).joinToString(";\n") + ";\nINVALID_SQL_FOR_ROLLBACK_TEST;"
            try {
                db.runInTransaction {
                    db.migrateTo(36) { version -> if (version == 36) failingV36Sql else TestDatabaseHelper.loadMigrationSQL(version) }
                }
                fail("Interrupted 35->36 migration must fail")
            } catch (_: Exception) {
                // expected
            }
            assertEquals(35, db.getVersion())
            assertFalse(tableExists(db, "HabitDayAdjustments"))
            assertFalse("missing_data_policy" in getColumns(db, "HabitExtensions"))

            // Now apply real 35 -> 36 upgrade in-place
            db.runInTransaction {
                db.migrateTo(36) { TestDatabaseHelper.loadMigrationSQL(it) }
            }
            assertEquals(DATABASE_VERSION, db.getVersion())
            assertTrue(tableExists(db, "HabitDayAdjustments"))
            assertTrue("missing_data_policy" in getColumns(db, "HabitExtensions"))
            assertEquals("ok", db.queryString("PRAGMA integrity_check"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM pragma_foreign_key_check()"))

            // Verify all existing facts untouched
            assertEquals(10000L, db.queryLong("SELECT value FROM Repetitions WHERE habit = 101"))
            assertEquals(1L, db.queryLong("SELECT timer_enabled FROM HabitExtensions WHERE habit_id = 101"))
            assertEquals(25_000L, contribRepo.getActiveTotalForHabit(HabitRef("h-focus-uuid")))
            assertNotNull(focusRepo.getById(FocusSessionId("fs-1")))
            assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = 'task-1'"))

            // Write an adjustment row + missing_data_policy override
            val adjRepo = SQLiteHabitDayAdjustmentRepository(db)
            adjRepo.upsert(HabitDayAdjustment(HabitRef("h-focus-uuid"), day, -15_000L, 9_000L))
            db.run("UPDATE HabitExtensions SET missing_data_policy = 1 WHERE habit_id = 101")
        } finally {
            db.close()
        }

        // Reopen from disk and verify backup + restore round-trip
        val reopened = opener.open(dbFile.absolutePath)
        val backupDb = TestDatabaseHelper.createEmptyDatabase()
        val restoreStageDb = TestDatabaseHelper.createEmptyDatabase()
        val restoredDb = TestDatabaseHelper.createEmptyDatabase()
        try {
            val adjRepoSrc = SQLiteHabitDayAdjustmentRepository(reopened)
            val day = LocalDate(2026, 11, 10)
            assertEquals(-15_000L, adjRepoSrc.getByHabitAndDate(HabitRef("h-focus-uuid"), day)?.adjustmentMillis)
            assertEquals(1L, reopened.queryLong("SELECT missing_data_policy FROM HabitExtensions WHERE habit_id = 101"))

            val backupRes = ContainerBackupService.createBackup(reopened, backupDb, 12_345L)
            assertTrue("Backup failed: $backupRes", backupRes is BackupCreationResult.Success)
            val adjRepoBackup = SQLiteHabitDayAdjustmentRepository(backupDb)
            assertEquals(-15_000L, adjRepoBackup.getByHabitAndDate(HabitRef("h-focus-uuid"), day)?.adjustmentMillis)
            assertEquals(1L, backupDb.queryLong("SELECT missing_data_policy FROM HabitExtensions WHERE habit_id = 101"))

            val restoreRes = ContainerRestoreService.restore(backupDb, restoreStageDb, restoredDb)
            assertTrue("Restore failed: $restoreRes", restoreRes is RestoreResult.Success)

            val adjRepoRestored = SQLiteHabitDayAdjustmentRepository(restoredDb)
            assertEquals(-15_000L, adjRepoRestored.getByHabitAndDate(HabitRef("h-focus-uuid"), day)?.adjustmentMillis)
            assertEquals(1L, restoredDb.queryLong("SELECT missing_data_policy FROM HabitExtensions WHERE habit_id = 101"))
            assertEquals(25_000L, SQLiteContributionRepository(restoredDb).getActiveTotalForHabit(HabitRef("h-focus-uuid")))
            assertEquals(10_000L, restoredDb.queryLong("SELECT value FROM Repetitions WHERE habit = 101"))
        } finally {
            reopened.close()
            backupDb.close()
            restoreStageDb.close()
            restoredDb.close()
        }
    }
}
