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

package org.isoron.uhabits.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.production.cutover.ProductionCutoverGate
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreSafetyAndroidTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var canonicalDbFile: File
    private lateinit var rollbackFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.cacheDir, "restore-safety-${UUID.randomUUID()}").apply { mkdirs() }
        canonicalDbFile = context.getDatabasePath(DATABASE_FILENAME)
        rollbackFile = File(canonicalDbFile.parentFile, "${DATABASE_FILENAME}.rollback")
        // Ensure clean state before tests
        DatabaseUtils.closeDatabase()
        if (rollbackFile.exists()) rollbackFile.delete()
    }

    @After
    fun tearDown() {
        DatabaseUtils.closeDatabase()
        if (rollbackFile.exists()) rollbackFile.delete()
        testDir.deleteRecursively()
    }

    private fun createLegacyV29Backup(file: File, habitName: String = "Exercise"): File {
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE Habits (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, description TEXT, color INTEGER, freq_den INTEGER, freq_num INTEGER, position INTEGER, type INTEGER, target_value REAL, target_type INTEGER, unit TEXT, archived INTEGER DEFAULT 0, uuid TEXT)")
        db.execSQL("CREATE TABLE Repetitions (id INTEGER PRIMARY KEY AUTOINCREMENT, habit INTEGER, timestamp INTEGER, value INTEGER, notes TEXT)")
        db.execSQL("CREATE TABLE HabitExtensions (habit_id INTEGER PRIMARY KEY, day_tier TEXT, timer_enabled INTEGER, block_id INTEGER)")
        db.execSQL("CREATE TABLE HabitBlocks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, color INTEGER, icon TEXT, position INTEGER, is_archived INTEGER, uuid TEXT, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE HabitGoals (id INTEGER PRIMARY KEY AUTOINCREMENT, habit_id INTEGER, target_value REAL, target_type INTEGER, unit TEXT, frequency_num INTEGER, frequency_den INTEGER, effective_from INTEGER, uuid TEXT)")
        db.execSQL("CREATE TABLE AppSettings (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE SyncQueue (id INTEGER PRIMARY KEY AUTOINCREMENT, entity_type TEXT, entity_id TEXT, action TEXT, payload TEXT, created_at INTEGER)")
        db.execSQL("CREATE TABLE EntryOps (id INTEGER PRIMARY KEY AUTOINCREMENT, op_uuid TEXT, habit_uuid TEXT, timestamp INTEGER, op_type TEXT, prev_value INTEGER, new_value INTEGER, created_at INTEGER)")
        db.execSQL("INSERT INTO Habits (name, position, type) VALUES ('$habitName', 0, 0)")
        db.execSQL("INSERT INTO Repetitions (habit, timestamp, value, notes) VALUES (1, 1000000, 2, 'done')")
        db.version = 29
        db.close()
        return file
    }

    private fun createNextV30Backup(file: File): File {
        createLegacyV29Backup(file, "NextHabit")
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("CREATE TABLE OrganizationState (id INTEGER PRIMARY KEY, dataset_uuid TEXT UNIQUE, foundation_version INTEGER, mode TEXT, cutover_revision INTEGER, current_revision INTEGER, cutover_at INTEGER, source_schema_version INTEGER, source_snapshot_sha256 TEXT, source_file_sha256 TEXT, source_schema_sha256 TEXT, runtime_authority TEXT, authority_epoch INTEGER, conversion_op_uuid TEXT)")
        db.execSQL("CREATE TABLE OrganizationChanges (revision INTEGER PRIMARY KEY, op_uuid TEXT UNIQUE, recorded_at INTEGER, operation_type TEXT, origin TEXT, command_payload TEXT)")
        db.execSQL("CREATE TABLE Containers (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE, parent_id INTEGER, name TEXT, color INTEGER, icon TEXT, sibling_order INTEGER, is_archived INTEGER, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER, revision INTEGER)")
        db.execSQL("CREATE TABLE HabitPlacements (habit_id INTEGER PRIMARY KEY, container_id INTEGER, local_order INTEGER, placement_origin TEXT, revision INTEGER)")
        db.execSQL("CREATE TABLE ContainerHistory (id INTEGER PRIMARY KEY AUTOINCREMENT, container_id INTEGER, revision INTEGER, parent_id INTEGER, name TEXT, color INTEGER, icon TEXT, sibling_order INTEGER, is_archived INTEGER, deleted_at INTEGER, changed_at INTEGER)")
        db.execSQL("CREATE TABLE HabitPlacementHistory (id INTEGER PRIMARY KEY AUTOINCREMENT, habit_id INTEGER, revision INTEGER, container_id INTEGER, local_order INTEGER, changed_at INTEGER)")
        db.execSQL("CREATE TABLE LegacyBlockMap (block_id INTEGER PRIMARY KEY, container_id INTEGER, migrated_at INTEGER)")
        db.execSQL("CREATE TABLE HabitVisualIdentities (habit_id INTEGER PRIMARY KEY, icon TEXT, color INTEGER, style TEXT, updated_at INTEGER)")
        db.execSQL("CREATE TABLE UserTasks (id TEXT PRIMARY KEY, title TEXT, notes TEXT, status TEXT, priority INTEGER, container_id INTEGER, planned_date INTEGER, deadline INTEGER, completed_at INTEGER, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER, revision INTEGER)")
        db.execSQL("CREATE TABLE FocusSessions (id TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER, duration_ms INTEGER, target_type TEXT, target_id TEXT, mode TEXT, status TEXT, completed_naturally INTEGER, notes TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER, revision INTEGER)")
        db.execSQL("CREATE TABLE Contributions (id TEXT PRIMARY KEY, session_id TEXT, habit_id INTEGER, task_id TEXT, day_timestamp INTEGER, credited_value INTEGER, manual_override INTEGER, source_rule_id TEXT, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER, revision INTEGER)")
        db.execSQL("CREATE TABLE SourceRules (id TEXT PRIMARY KEY, habit_id INTEGER, target_type TEXT, target_id TEXT, rule_type TEXT, multiplier REAL, created_at INTEGER, updated_at INTEGER, deleted_at INTEGER, revision INTEGER)")
        db.version = 30
        db.close()
        return file
    }

    @Test
    fun testRestoreV29BackupInLegacyProductionDoesNotTriggerV30Migration() {
        // Canonical production is not cutover: target version is 29
        assertFalse(ProductionCutoverGate.isCutoverAuthorized(DATABASE_FILENAME))

        // Create initial canonical database at v29
        DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)
        createLegacyV29Backup(canonicalDbFile, "OriginalHabit")
        DatabaseUtils.closeDatabase()

        // Create a backup file at v29
        val backupFile = File(testDir, "backup_v29.db")
        createLegacyV29Backup(backupFile, "RestoredHabit")

        // Restore the v29 backup into canonical production
        DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)
        DatabaseUtils.restoreDatabaseFromBackup(context, backupFile)

        // Verify that the restored canonical database is at version 29 (NOT version 30)
        val reopenedDb = SQLiteDatabase.openDatabase(
            canonicalDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            assertEquals(29, reopenedDb.version)
            assertEquals(LEGACY_DATABASE_VERSION, reopenedDb.version)

            // Verify that Next tables were NOT created
            reopenedDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='Containers'", null).use {
                assertFalse("Containers table must not exist in legacy v29 restored database", it.moveToFirst())
            }
            reopenedDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='UserTasks'", null).use {
                assertFalse("UserTasks table must not exist in legacy v29 restored database", it.moveToFirst())
            }

            // Verify content restored
            reopenedDb.rawQuery("SELECT name FROM Habits", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("RestoredHabit", it.getString(0))
            }
        } finally {
            reopenedDb.close()
        }

        // Verify opener opens cleanly without downgrade exception
        DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)
        DatabaseUtils.openDatabase().use { db ->
            assertEquals(29, db.version)
        }
    }

    @Test
    fun testRestoringV30OrNextBackupIntoLegacyProductionFailsClosed() {
        assertFalse(ProductionCutoverGate.isCutoverAuthorized(DATABASE_FILENAME))

        // Setup canonical v29 database
        DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)
        createLegacyV29Backup(canonicalDbFile, "ProductionData")
        DatabaseUtils.closeDatabase()

        // Create a v30 backup containing Next entities
        val nextBackup = File(testDir, "backup_v30_next.db")
        createNextV30Backup(nextBackup)

        DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)

        // Restoring Next backup into legacy production must fail closed
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseUtils.restoreDatabaseFromBackup(context, nextBackup)
        }

        // Verify production database is untouched and still v29
        val checkDb = SQLiteDatabase.openDatabase(canonicalDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(29, checkDb.version)
            checkDb.rawQuery("SELECT name FROM Habits", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("ProductionData", it.getString(0))
            }
        } finally {
            checkDb.close()
        }
    }

    @Test
    fun testCrashDuringRestorePreservesRollbackAndRecoversOnStartup() {
        assertFalse(ProductionCutoverGate.isCutoverAuthorized(DATABASE_FILENAME))

        // Setup initial database with original data
        createLegacyV29Backup(canonicalDbFile, "OriginalUserHabit")

        // Simulate crash right during swapping phase:
        // canonicalDbFile was moved to rollbackFile, but restoreTemp was not yet moved to canonicalDbFile
        Files.move(canonicalDbFile.toPath(), rollbackFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertFalse("Canonical DB must be missing on simulated crash", canonicalDbFile.exists())
        assertTrue("Rollback file must exist on simulated crash", rollbackFile.exists())

        // Record SWAPPING state in journal
        val journal = RestoreJournal(canonicalDbFile.parentFile, DATABASE_FILENAME)
        journal.recordState(RestoreJournal.State.SWAPPING, 29, "/staged/test.db")

        // Simulate application cold-start reconciliation
        DatabaseUtils.reconcileInterruptedRestore(context, DATABASE_FILENAME)

        // Verify canonicalDbFile was safely recovered from rollbackFile
        assertTrue("Canonical DB must be restored after crash reconciliation", canonicalDbFile.exists())
        assertFalse("Journal must be cleared after successful reconciliation", journal.exists)

        val checkDb = SQLiteDatabase.openDatabase(canonicalDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(29, checkDb.version)
            checkDb.rawQuery("SELECT name FROM Habits", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("OriginalUserHabit", it.getString(0))
            }
        } finally {
            checkDb.close()
        }
    }

    @Test
    fun testEvidenceOfExistingDatasetRefusesToCreateEmptyDatabase() {
        val testDbName = "guarded_test.db"
        val testDbFile = context.getDatabasePath(testDbName)
        if (testDbFile.exists()) testDbFile.delete()

        // Place a rollback file as evidence of an interrupted restore / existing dataset
        val orphanRollback = File(testDbFile.parentFile, "$testDbName.rollback")
        orphanRollback.writeText("simulated-rollback-evidence")

        try {
            assertTrue(HabitsDatabaseOpener.hasEvidenceOfExistingDataset(context, testDbName))

            // Attempting to open/create this database must fail closed with IllegalStateException
            val opener = HabitsDatabaseOpener(context, testDbName, 29)
            assertThrows(IllegalStateException::class.java) {
                opener.writableDatabase
            }
        } finally {
            orphanRollback.delete()
        }
    }

    @Test
    fun testCleanFirstInstallCreatesEmptyDatabaseSuccessfully() {
        val cleanDbName = "clean_first_install_${UUID.randomUUID()}.db"
        val cleanDbFile = context.getDatabasePath(cleanDbName)
        if (cleanDbFile.exists()) cleanDbFile.delete()

        try {
            assertFalse(HabitsDatabaseOpener.hasEvidenceOfExistingDataset(context, cleanDbName))

            val opener = HabitsDatabaseOpener(context, cleanDbName, 29)
            val db = opener.writableDatabase
            assertEquals(29, db.version)

            // Verify the 8 legacy tables exist
            val tables = buildSet {
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use {
                    while (it.moveToNext()) add(it.getString(0))
                }
            }
            assertTrue(tables.containsAll(DatabaseUtils.LEGACY_TABLE_NAMES))
            opener.close()
        } finally {
            context.deleteDatabase(cleanDbName)
        }
    }

    @Test
    fun testRestoreSetsSyncReviewRequired() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("pref_sync_enabled", true).putBoolean("pref_sync_review_required", false).commit()

        createLegacyV29Backup(canonicalDbFile, "HabitBeforeRestore")
        val backupFile = File(testDir, "backup_sync_test.db")
        createLegacyV29Backup(backupFile, "HabitAfterRestore")

        DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)
        DatabaseUtils.restoreDatabaseFromBackup(context, backupFile)

        // Sync safety: must set pref_sync_review_required = true
        assertTrue("Restore must require sync review to prevent automatic remote uploads",
            prefs.getBoolean("pref_sync_review_required", false))
        assertEquals("Database restored from backup", prefs.getString("pref_sync_review_reason", ""))
    }

    @Test
    fun testBackupSnapshotCreationOnDevice() {
        createLegacyV29Backup(canonicalDbFile, "SnapshotTestHabit")
        DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)

        val tempBackupFile = File(testDir, "test_snapshot.db")
        val snapshotInfo = DatabaseUtils.createDatabaseSnapshot(context, tempBackupFile)
        assertTrue(tempBackupFile.exists())
        assertTrue(snapshotInfo.sizeBytes > 0)
        assertEquals(LEGACY_DATABASE_VERSION, snapshotInfo.databaseVersion)

        val validation = DatabaseUtils.validateDatabaseBackup(tempBackupFile)
        assertTrue(validation.isValid)
        assertEquals(LEGACY_DATABASE_VERSION, validation.databaseVersion)
    }
}
