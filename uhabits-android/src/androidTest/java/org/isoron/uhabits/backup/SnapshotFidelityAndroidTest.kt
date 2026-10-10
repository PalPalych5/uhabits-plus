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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.sqlite.SQLiteHabitVisualIdentityStore
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitGoalData
import org.isoron.uhabits.core.database.HabitGoalRepository
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SnapshotFidelityAndroidTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var canonicalDbFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.cacheDir, "snapshot-fidelity-${UUID.randomUUID()}").apply { mkdirs() }
        canonicalDbFile = context.getDatabasePath(DATABASE_FILENAME)
        DatabaseUtils.closeDatabase()
        if (canonicalDbFile.exists()) canonicalDbFile.delete()
        File("${canonicalDbFile.path}-wal").delete()
        File("${canonicalDbFile.path}-shm").delete()
        DatabaseUtils.forceSqliteCloneForTesting = true
        DatabaseUtils.snapshotFailureInjection = DatabaseUtils.SnapshotFailureInjection.NONE
        org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.setExplicitCutoverAuthorizedForTesting(false)
    }

    @After
    fun tearDown() {
        org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.setExplicitCutoverAuthorizedForTesting(false)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove("pref_dataset_mode")
            .commit()
        DatabaseUtils.forceSqliteCloneForTesting = false
        DatabaseUtils.snapshotFailureInjection = DatabaseUtils.SnapshotFailureInjection.NONE
        DatabaseUtils.closeDatabase()
        testDir.deleteRecursively()
        if (canonicalDbFile.exists()) canonicalDbFile.delete()
        File("${canonicalDbFile.path}-wal").delete()
        File("${canonicalDbFile.path}-shm").delete()
    }

    @Test
    fun testSnapshotFidelityLegacyV29Dataset() {
        canonicalDbFile.parentFile?.mkdirs()
        val sourceDb = SQLiteDatabase.openOrCreateDatabase(canonicalDbFile, null)
        try {
            sourceDb.enableWriteAheadLogging()
            populateLegacyV29Database(sourceDb)
            sourceDb.execSQL("PRAGMA application_id = 1430806850") // 0x55484142
            sourceDb.version = LEGACY_DATABASE_VERSION

            // Delete high-ID habit so autoincrement sequence > max(id)
            sourceDb.execSQL("INSERT INTO Habits(name, freq_num, freq_den, color, position, archived, type) VALUES ('ToDelete', 1, 1, 0, 99, 0, 0)")
            val deletedId = queryLong(sourceDb, "SELECT last_insert_rowid()")
            sourceDb.execSQL("DELETE FROM Habits WHERE id = $deletedId")

            // Verify sequence has the deleted ID
            val expectedHabitSeq = queryLong(sourceDb, "SELECT seq FROM sqlite_sequence WHERE name = 'Habits'")
            assertTrue(expectedHabitSeq >= deletedId)

            DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)

            val snapshotFile = File(testDir, "v29_snapshot.db")
            val info = DatabaseUtils.createDatabaseSnapshot(context, snapshotFile)
            assertTrue(snapshotFile.exists())
            assertEquals(LEGACY_DATABASE_VERSION, info.databaseVersion)

            // Validate parity
            val snapshotDb = SQLiteDatabase.openDatabase(snapshotFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                assertDatabaseParity(sourceDb, snapshotDb)

                // Verify sequence preservation and subsequent insert behavior
                snapshotDb.execSQL("INSERT INTO Habits(name, freq_num, freq_den, color, position, archived, type) VALUES ('AfterRestore', 1, 1, 0, 0, 0, 0)")
                val newId = queryLong(snapshotDb, "SELECT last_insert_rowid()")
                assertEquals(expectedHabitSeq + 1, newId)
            } finally {
                snapshotDb.close()
            }
        } finally {
            sourceDb.close()
        }
    }

    @Test
    fun testSnapshotFidelityNextV30Dataset() {
        canonicalDbFile.parentFile?.mkdirs()
        val sourceDb = SQLiteDatabase.openOrCreateDatabase(canonicalDbFile, null)
        try {
            sourceDb.enableWriteAheadLogging()
            populateNextV30Database(sourceDb)
            sourceDb.execSQL("PRAGMA application_id = 1430806850")
            sourceDb.version = 30

            // Delete high-ID container to test sequence gap preservation
            sourceDb.execSQL("INSERT INTO Containers(uuid, name, sibling_order, is_archived, updated_at, revision) VALUES ('temp-uuid', 'TempContainer', 0, 0, 1000, 1)")
            val deletedContainerId = queryLong(sourceDb, "SELECT id FROM Containers WHERE uuid = 'temp-uuid'")
            assertTrue("Temporary container ID must be > 1", deletedContainerId > 1)
            sourceDb.execSQL("DELETE FROM Containers WHERE uuid = 'temp-uuid'")
            val expectedContainerSeq = queryLong(sourceDb, "SELECT seq FROM sqlite_sequence WHERE name = 'Containers'")
            assertTrue(expectedContainerSeq >= deletedContainerId)
            assertEquals("Containers must have only 1 row after deletion", 1, queryLong(sourceDb, "SELECT count(*) FROM Containers"))

            val wrappedSource = org.isoron.uhabits.database.AndroidDatabase(sourceDb)
            val manifest = org.isoron.uhabits.core.containers.backup.DatabaseSnapshotter.buildManifest(wrappedSource, 1000L)
            org.isoron.uhabits.core.containers.backup.DatabaseSnapshotter.writeManifest(wrappedSource, manifest)

            org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.setExplicitCutoverAuthorizedForTesting(true)
            DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)

            val snapshotFile = File(testDir, "v30_snapshot.db")
            val info = DatabaseUtils.createDatabaseSnapshot(context, snapshotFile)
            assertTrue(snapshotFile.exists())
            assertEquals(30, info.databaseVersion)

            val snapshotDb = SQLiteDatabase.openDatabase(snapshotFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                assertDatabaseParity(sourceDb, snapshotDb)

                // Subsequent insert in snapshot gets sequence + 1 without reusing deletedContainerId
                snapshotDb.execSQL("INSERT INTO Containers(uuid, name, sibling_order, is_archived, updated_at, revision) VALUES ('after-restore-uuid', 'RestoredContainer', 0, 0, 2000, 1)")
                val newContainerId = queryLong(snapshotDb, "SELECT last_insert_rowid()")
                assertEquals(expectedContainerSeq + 1, newContainerId)
            } finally {
                snapshotDb.close()
            }
        } finally {
            sourceDb.close()
        }
    }

    @Test
    fun testUnsupportedVirtualTableFailsClosed() {
        canonicalDbFile.parentFile?.mkdirs()
        val sourceDb = SQLiteDatabase.openOrCreateDatabase(canonicalDbFile, null)
        try {
            populateLegacyV29Database(sourceDb)
            sourceDb.version = LEGACY_DATABASE_VERSION
            // Add virtual table
            sourceDb.execSQL("CREATE VIRTUAL TABLE test_fts USING fts4(content)")
            sourceDb.execSQL("INSERT INTO test_fts(content) VALUES ('test search')")

            DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)

            val snapshotFile = File(testDir, "fts_snapshot.db")
            try {
                DatabaseUtils.createDatabaseSnapshot(context, snapshotFile)
                fail("Expected snapshot creation to fail closed on virtual table")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("Virtual tables are unsupported") == true)
            }
            assertFalse("Incomplete snapshot file must not exist", snapshotFile.exists())
            // Source DB remains intact
            assertEquals("test search", queryString(sourceDb, "SELECT content FROM test_fts LIMIT 1"))
        } finally {
            sourceDb.close()
        }
    }

    @Test
    fun testCrashAndFailureInjectionsPreserveSourceAndCleanTempFiles() {
        canonicalDbFile.parentFile?.mkdirs()
        val sourceDb = SQLiteDatabase.openOrCreateDatabase(canonicalDbFile, null)
        try {
            sourceDb.enableWriteAheadLogging()
            populateLegacyV29Database(sourceDb)
            sourceDb.version = LEGACY_DATABASE_VERSION
            DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)

            val failurePoints = listOf(
                DatabaseUtils.SnapshotFailureInjection.DURING_SCHEMA_CREATION,
                DatabaseUtils.SnapshotFailureInjection.DURING_ROW_COPYING,
                DatabaseUtils.SnapshotFailureInjection.DURING_INDEX_CREATION,
                DatabaseUtils.SnapshotFailureInjection.DURING_COMMIT,
                DatabaseUtils.SnapshotFailureInjection.DURING_DETACH,
                DatabaseUtils.SnapshotFailureInjection.DURING_VALIDATION
            )

            for (point in failurePoints) {
                DatabaseUtils.snapshotFailureInjection = point
                val tempSnapshot = File(testDir, "failed_${point.name}.db")

                try {
                    DatabaseUtils.createDatabaseSnapshot(context, tempSnapshot)
                    fail("Expected failure injection at $point to throw IOException")
                } catch (e: IOException) {
                    // Expected
                }

                assertFalse("Temp snapshot must be cleaned up on failure at $point", tempSnapshot.exists())

                // Verify source database remains healthy
                val habitCount = queryLong(sourceDb, "SELECT count(*) FROM Habits")
                assertTrue("Source DB records must be intact after failure at $point", habitCount > 0)
                assertEquals("Source DB must not have lingering attachments", 0, countAttachedDatabases(sourceDb, "snapshot"))
            }

            // Test failure during publication
            DatabaseUtils.snapshotFailureInjection = DatabaseUtils.SnapshotFailureInjection.NONE
            val validTemp = File(testDir, "valid_temp.db")
            DatabaseUtils.createDatabaseSnapshot(context, validTemp)
            assertTrue(validTemp.exists())

            DatabaseUtils.snapshotFailureInjection = DatabaseUtils.SnapshotFailureInjection.DURING_PUBLICATION
            val finalTarget = File(testDir, "final_published.db")
            try {
                DatabaseUtils.publishLocalBackup(validTemp, finalTarget)
                fail("Expected failure during publication to throw IOException")
            } catch (e: IOException) {
                // Expected
            }
            assertFalse("Final backup must not exist if publication fails", finalTarget.exists())
        } finally {
            sourceDb.close()
        }
    }

    @Test
    fun testConcurrentWritersDuringSnapshotCreation() {
        canonicalDbFile.parentFile?.mkdirs()
        val sourceDb = SQLiteDatabase.openOrCreateDatabase(canonicalDbFile, null)
        try {
            sourceDb.enableWriteAheadLogging()
            populateLegacyV29Database(sourceDb)
            sourceDb.version = LEGACY_DATABASE_VERSION
            DatabaseUtils.initializeDatabase(context, DATABASE_FILENAME)

            val running = AtomicBoolean(true)
            val writesCompleted = AtomicInteger(0)
            val latch = CountDownLatch(1)

            val writerThread = Thread {
                latch.countDown()
                val writerDb = SQLiteDatabase.openDatabase(canonicalDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    var counter = 50000L
                    while (running.get()) {
                        writerDb.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES (1, $counter, 2, 'concurrent', 'rep-concur-$counter')")
                        counter++
                        writesCompleted.incrementAndGet()
                        Thread.sleep(5)
                    }
                } finally {
                    writerDb.close()
                }
            }
            writerThread.start()
            assertTrue(latch.await(5, TimeUnit.SECONDS))

            // Allow writer to start
            Thread.sleep(30)

            val snapshotFile = File(testDir, "concurrent_snapshot.db")
            val startTime = System.currentTimeMillis()
            val info = DatabaseUtils.createDatabaseSnapshot(context, snapshotFile)
            val elapsedMs = System.currentTimeMillis() - startTime

            running.set(false)
            writerThread.join(5000)

            assertTrue("Concurrent snapshot must succeed", snapshotFile.exists())
            assertEquals(LEGACY_DATABASE_VERSION, info.databaseVersion)
            assertTrue("Concurrent writes occurred during snapshot", writesCompleted.get() > 0)
            assertTrue("Snapshot should complete without deadlock (elapsed: ${elapsedMs}ms)", elapsedMs < 10000)

            val snapshotDb = SQLiteDatabase.openDatabase(snapshotFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                snapshotDb.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            } finally {
                snapshotDb.close()
            }
        } finally {
            sourceDb.close()
        }
    }

    @Test
    fun testAndroid9UpsertCompatibilityOnDevice() {
        canonicalDbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(canonicalDbFile, null)
        try {
            populateNextV30Database(db)
            val wrappedDb = AndroidDatabase(db)

            // 1. HabitGoalRepository compatibility test
            val goalRepo = HabitGoalRepository(wrappedDb)
            val initialGoal = HabitGoalData(
                habitId = 1,
                effectiveTimestamp = 1000,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 10.0,
                unit = "pages",
                uuid = "goal-uuid-1"
            )
            goalRepo.upsert(initialGoal)
            val savedGoal = goalRepo.findByUuid("goal-uuid-1")
            assertNotNull(savedGoal)
            val originalId = savedGoal!!.id

            // Upsert with modified value must update existing row and preserve original ID
            goalRepo.upsert(initialGoal.copy(targetValue = 20.0, unit = "chapters"))
            val updatedGoal = goalRepo.findByUuid("goal-uuid-1")
            assertNotNull(updatedGoal)
            assertEquals("ID must be preserved on update", originalId, updatedGoal!!.id)
            assertEquals(20.0, updatedGoal.targetValue, 0.001)
            assertEquals("chapters", updatedGoal.unit)

            // 2. EntryRepository compatibility test
            val entryRepo = EntryRepository(wrappedDb)
            val initialEntry = EntryData(
                habitId = 1,
                timestamp = 50000,
                value = 2,
                notes = "initial note"
            )
            val entryId1 = entryRepo.upsert(initialEntry)
            assertTrue(entryId1 > 0)

            // Upsert with modified value/notes on same (habit, timestamp)
            val updatedEntry = EntryData(
                habitId = 1,
                timestamp = 50000,
                value = 1,
                notes = "updated note"
            )
            val entryId2 = entryRepo.upsert(updatedEntry)
            assertEquals("Entry ID must be preserved on upsert", entryId1, entryId2)

            val loadedEntry = entryRepo.findAllByHabitId(1).first { it.timestamp == 50000L }
            assertEquals(entryId1, loadedEntry.id)
            assertEquals(1, loadedEntry.value)
            assertEquals("updated note", loadedEntry.notes)

            // 3. SQLiteHabitVisualIdentityStore compatibility test
            val visualStore = SQLiteHabitVisualIdentityStore(wrappedDb)
            visualStore.set("habit-uuid-test", IconRef.Vector("star"))
            assertEquals(IconRef.Vector("star"), visualStore.get("habit-uuid-test"))

            // Update icon on same habit uuid
            visualStore.set("habit-uuid-test", IconRef.Emoji("🔥"))
            assertEquals(IconRef.Emoji("🔥"), visualStore.get("habit-uuid-test"))
        } finally {
            db.close()
        }
    }

    // --- Helper Methods ---

    private fun populateLegacyV29Database(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE Habits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT,
                name TEXT NOT NULL,
                description TEXT,
                question TEXT,
                freq_num INTEGER NOT NULL,
                freq_den INTEGER NOT NULL,
                color INTEGER NOT NULL,
                position INTEGER NOT NULL,
                reminder_hour INTEGER,
                reminder_min INTEGER,
                reminder_days INTEGER,
                highlight INTEGER,
                archived INTEGER NOT NULL DEFAULT 0,
                type INTEGER NOT NULL DEFAULT 0,
                target_value REAL NOT NULL DEFAULT 0.0,
                target_type INTEGER NOT NULL DEFAULT 0,
                unit TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX idx_habits_uuid ON Habits(uuid)")

        db.execSQL("""
            CREATE TABLE Repetitions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                habit INTEGER NOT NULL REFERENCES Habits(id) ON DELETE CASCADE,
                timestamp INTEGER NOT NULL,
                value INTEGER NOT NULL DEFAULT 2,
                notes TEXT,
                uuid TEXT,
                updated_at INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX idx_repetitions_habit_timestamp ON Repetitions(habit, timestamp)")
        db.execSQL("CREATE UNIQUE INDEX idx_repetitions_uuid ON Repetitions(uuid)")

        db.execSQL("""
            CREATE TABLE HabitGoals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                habit_id INTEGER NOT NULL REFERENCES Habits(id) ON DELETE CASCADE,
                effective_timestamp INTEGER NOT NULL,
                freq_num INTEGER NOT NULL,
                freq_den INTEGER NOT NULL,
                target_type INTEGER NOT NULL,
                target_value REAL NOT NULL,
                unit TEXT NOT NULL DEFAULT '',
                uuid TEXT,
                updated_at INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX idx_habit_goals_uuid ON HabitGoals(uuid)")
        db.execSQL("CREATE UNIQUE INDEX idx_habit_goals_unique_effective ON HabitGoals(habit_id, effective_timestamp)")

        db.execSQL("""
            CREATE TABLE HabitBlocks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT,
                name TEXT NOT NULL,
                color INTEGER NOT NULL DEFAULT 0,
                icon TEXT,
                position INTEGER NOT NULL,
                is_archived INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER
            )
        """)

        db.execSQL("""
            CREATE TABLE HabitExtensions (
                habit_id INTEGER PRIMARY KEY REFERENCES Habits(id) ON DELETE CASCADE,
                day_tier TEXT NOT NULL DEFAULT 'NORMAL',
                timer_enabled INTEGER NOT NULL DEFAULT 0,
                block_id INTEGER,
                stats_start_timestamp INTEGER
            )
        """)

        db.execSQL("""
            CREATE TABLE AppSettings (
                key TEXT PRIMARY KEY,
                long_value INTEGER
            )
        """)

        db.execSQL("""
            CREATE TABLE SyncQueue (
                queue_id INTEGER PRIMARY KEY AUTOINCREMENT,
                op_uuid TEXT NOT NULL UNIQUE,
                entity_type TEXT NOT NULL,
                entity_uuid TEXT NOT NULL,
                operation_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                pushed_at INTEGER,
                failed_at INTEGER,
                failure_reason TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE EntryOps (
                op_uuid TEXT PRIMARY KEY,
                habit_uuid TEXT NOT NULL,
                entry_timestamp INTEGER NOT NULL,
                delta_value INTEGER NOT NULL,
                op_type TEXT NOT NULL,
                notes TEXT,
                device_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                deleted_at INTEGER
            )
        """)

        // Seed data
        db.execSQL("INSERT INTO Habits(name, freq_num, freq_den, color, position, archived, type, uuid) VALUES ('Meditate', 1, 1, 1, 0, 0, 0, 'h-1')")
        db.execSQL("INSERT INTO Habits(name, freq_num, freq_den, color, position, archived, type, uuid) VALUES ('Exercise', 1, 1, 2, 1, 0, 0, 'h-2')")

        db.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES (1, 1000, 2, 'note 1', 'rep-1')")
        db.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES (1, 2000, 2, 'note 2', 'rep-2')")
        db.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES (2, 1000, 1, 'half', 'rep-3')")

        db.execSQL("INSERT INTO HabitGoals(habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit, uuid) VALUES (1, 0, 1, 1, 0, 10.0, 'min', 'g-1')")
        db.execSQL("INSERT INTO HabitGoals(habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit, uuid) VALUES (2, 0, 1, 1, 0, 30.0, 'min', 'g-2')")

        db.execSQL("INSERT INTO HabitBlocks(name, position, uuid) VALUES ('DefaultBlock', 0, 'block-uuid-1')")
        db.execSQL("INSERT INTO HabitExtensions(habit_id, day_tier, timer_enabled, stats_start_timestamp) VALUES (1, 'NORMAL', 0, 500)")
        db.execSQL("INSERT INTO AppSettings(key, long_value) VALUES ('first_run', 1)")
        db.execSQL("INSERT INTO SyncQueue(op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id) VALUES ('op-1', 'Habit', 'h-1', 'UPSERT', '{}', 1000, 'dev-1')")
        db.execSQL("INSERT INTO EntryOps(op_uuid, habit_uuid, entry_timestamp, delta_value, op_type, notes, device_id, created_at) VALUES ('op-entry-1', 'h-1', 1000, 2, 'UPSERT', 'note 1', 'dev-1', 1000)")
    }

    private fun populateNextV30Database(db: SQLiteDatabase) {
        populateLegacyV29Database(db)

        db.execSQL("""
            CREATE TABLE OrganizationState (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                dataset_uuid TEXT NOT NULL UNIQUE,
                foundation_version INTEGER NOT NULL,
                mode TEXT NOT NULL,
                cutover_revision INTEGER NOT NULL DEFAULT 1,
                current_revision INTEGER NOT NULL DEFAULT 1,
                cutover_at INTEGER NOT NULL,
                source_schema_version INTEGER NOT NULL,
                source_snapshot_sha256 TEXT NOT NULL,
                source_file_sha256 TEXT,
                source_schema_sha256 TEXT,
                runtime_authority TEXT NOT NULL DEFAULT 'LEGACY',
                authority_epoch INTEGER NOT NULL DEFAULT 0,
                conversion_op_uuid TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE OrganizationChanges (
                revision INTEGER PRIMARY KEY,
                op_uuid TEXT NOT NULL UNIQUE,
                recorded_at INTEGER NOT NULL,
                operation_type TEXT NOT NULL,
                origin TEXT NOT NULL,
                command_payload TEXT NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE Containers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                parent_id INTEGER NULL REFERENCES Containers(id),
                name TEXT NOT NULL,
                color INTEGER NULL,
                icon TEXT NULL,
                sibling_order INTEGER NOT NULL,
                is_archived INTEGER NOT NULL,
                created_at INTEGER NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER NULL,
                revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision)
            )
        """)

        db.execSQL("""
            CREATE TABLE HabitPlacements (
                habit_id INTEGER PRIMARY KEY REFERENCES Habits(id),
                container_id INTEGER NULL REFERENCES Containers(id),
                local_order INTEGER NOT NULL,
                placement_origin TEXT NOT NULL,
                revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision)
            )
        """)

        db.execSQL("""
            CREATE TABLE ContainerHistory (
                container_id INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                parent_id INTEGER NULL,
                name TEXT NOT NULL,
                color INTEGER NULL,
                icon TEXT NULL,
                sibling_order INTEGER NOT NULL,
                is_archived INTEGER NOT NULL,
                created_at INTEGER NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER NULL,
                PRIMARY KEY(container_id, revision)
            )
        """)

        db.execSQL("""
            CREATE TABLE HabitPlacementHistory (
                habit_id INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                container_id INTEGER NULL,
                local_order INTEGER NOT NULL,
                placement_origin TEXT NOT NULL,
                PRIMARY KEY(habit_id, revision)
            )
        """)

        db.execSQL("""
            CREATE TABLE LegacyBlockMap (
                legacy_block_id INTEGER PRIMARY KEY REFERENCES HabitBlocks(id),
                legacy_block_uuid TEXT NOT NULL UNIQUE,
                container_id INTEGER NOT NULL UNIQUE REFERENCES Containers(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE HabitVisualIdentities (
                habit_uuid TEXT PRIMARY KEY,
                icon_type TEXT NOT NULL,
                icon_value TEXT NOT NULL
            )
        """)

        db.execSQL("""
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
        """)

        db.execSQL("""
            CREATE TABLE FocusSessions (
                id TEXT PRIMARY KEY NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                task_id TEXT,
                habit_uuid TEXT,
                container_id TEXT,
                source TEXT NOT NULL,
                device_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                primary_target_type TEXT,
                organization_context TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE FocusCaptureJournal (
                slot INTEGER PRIMARY KEY CHECK(slot = 1),
                timer_id INTEGER,
                timer_name TEXT NOT NULL,
                mode TEXT NOT NULL,
                phase TEXT NOT NULL,
                running INTEGER NOT NULL,
                elapsed INTEGER NOT NULL,
                display INTEGER NOT NULL,
                focus_duration INTEGER NOT NULL,
                break_duration INTEGER NOT NULL,
                started_at INTEGER NOT NULL,
                accumulated INTEGER NOT NULL,
                overtime INTEGER NOT NULL,
                completion INTEGER NOT NULL,
                auto_switch INTEGER NOT NULL,
                observed_at INTEGER NOT NULL,
                target_kind TEXT,
                target_id TEXT,
                target_container_id TEXT,
                organization_context TEXT,
                active_id TEXT,
                active_started_at INTEGER,
                active_elapsed_offset INTEGER,
                active_device_id TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE Contributions (
                id TEXT PRIMARY KEY NOT NULL,
                target_habit_uuid TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_id TEXT NOT NULL,
                contributed_value INTEGER NOT NULL,
                effective_timestamp INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                removed_at INTEGER,
                source_rule_id TEXT,
                UNIQUE (source_type, source_id, target_habit_uuid)
            )
        """)

        db.execSQL("""
            CREATE TABLE SourceRules (
                id TEXT PRIMARY KEY NOT NULL,
                source_kind TEXT NOT NULL,
                source_container_id TEXT NOT NULL,
                include_descendants INTEGER NOT NULL,
                target_habit_uuid TEXT NOT NULL,
                value_mapping TEXT NOT NULL,
                is_enabled INTEGER NOT NULL,
                effective_from INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                removed_at INTEGER
            )
        """)

        // Next seed records
        db.execSQL("INSERT INTO OrganizationChanges(revision, op_uuid, recorded_at, operation_type, origin, command_payload) VALUES (1, 'op-seed', 1000, 'INIT', 'SYSTEM', '{}')")
        db.execSQL("INSERT INTO OrganizationState(id, dataset_uuid, foundation_version, mode, cutover_at, source_schema_version, source_snapshot_sha256) VALUES (1, 'ds-1', 1, 'STAGED', 1000, 29, 'hash')")
        db.execSQL("INSERT INTO Containers(uuid, name, sibling_order, is_archived, updated_at, revision) VALUES ('c-1', 'Health', 0, 0, 1000, 1)")
        db.execSQL("INSERT INTO HabitPlacements(habit_id, container_id, local_order, placement_origin, revision) VALUES (1, 1, 0, 'USER_CHANGE', 1)")
        db.execSQL("INSERT INTO HabitPlacements(habit_id, container_id, local_order, placement_origin, revision) VALUES (2, 1, 1, 'USER_CHANGE', 1)")
        db.execSQL("INSERT INTO ContainerHistory(container_id, revision, name, sibling_order, is_archived, updated_at) VALUES (1, 1, 'Health', 0, 0, 1000)")
        db.execSQL("INSERT INTO HabitPlacementHistory(habit_id, revision, container_id, local_order, placement_origin) VALUES (1, 1, 1, 0, 'USER_CHANGE')")
        db.execSQL("INSERT INTO HabitPlacementHistory(habit_id, revision, container_id, local_order, placement_origin) VALUES (2, 1, 1, 1, 'USER_CHANGE')")
        db.execSQL("INSERT INTO LegacyBlockMap(legacy_block_id, legacy_block_uuid, container_id) VALUES (1, 'block-uuid', 1)")
        db.execSQL("INSERT INTO HabitVisualIdentities(habit_uuid, icon_type, icon_value) VALUES ('h-1', 'VECTOR', 'heart')")
        db.execSQL("INSERT INTO UserTasks(id, title, status, created_at, updated_at) VALUES ('task-1', 'Buy vegetables', 'PENDING', 1000, 1000)")
        db.execSQL("INSERT INTO FocusSessions(id, started_at, ended_at, duration_ms, source, created_at, updated_at, revision) VALUES ('fs-1', 1000, 2000, 1000, 'TIMER', 1000, 2000, 1)")
        db.execSQL("INSERT INTO Contributions(id, target_habit_uuid, source_type, source_id, contributed_value, effective_timestamp, created_at, updated_at) VALUES ('contrib-1', 'h-1', 'FOCUS_SESSION', 'fs-1', 1000, 1000, 1000, 1000)")
        db.execSQL("INSERT INTO SourceRules(id, source_kind, source_container_id, include_descendants, target_habit_uuid, value_mapping, is_enabled, effective_from, created_at, updated_at) VALUES ('rule-1', 'FOCUS_SESSION', 'c-1', 1, 'h-1', 'FOCUS_DURATION', 1, 0, 1000, 1000)")
    }

    private fun assertDatabaseParity(source: SQLiteDatabase, snapshot: SQLiteDatabase) {
        assertEquals("User version mismatch", source.version, snapshot.version)

        val sourceAppId = queryLong(source, "PRAGMA application_id")
        val snapshotAppId = queryLong(snapshot, "PRAGMA application_id")
        assertEquals("Application ID mismatch", sourceAppId, snapshotAppId)

        val sourceTables = getTables(source)
        val snapshotTables = getTables(snapshot)
        assertEquals("Table inventory mismatch", sourceTables, snapshotTables)

        for (table in sourceTables) {
            val sourceCount = queryLong(source, "SELECT count(*) FROM `$table`")
            val snapshotCount = queryLong(snapshot, "SELECT count(*) FROM `$table`")
            assertEquals("Row count mismatch in table $table", sourceCount, snapshotCount)
        }

        // Sequences
        val sourceSeq = getSequences(source)
        val snapshotSeq = getSequences(snapshot)
        for ((name, seq) in sourceSeq) {
            assertEquals("Sequence mismatch for table $name", seq, snapshotSeq[name])
        }

        // Integrity check
        snapshot.rawQuery("PRAGMA integrity_check", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }

        // Foreign key check
        snapshot.rawQuery("PRAGMA foreign_key_check", null).use {
            assertFalse("Foreign key violation in snapshot", it.moveToFirst())
        }
    }

    private fun getTables(db: SQLiteDatabase): List<String> {
        val list = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use {
            while (it.moveToNext()) list.add(it.getString(0))
        }
        return list
    }

    private fun getSequences(db: SQLiteDatabase): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        val hasSeq = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'", null).use { it.moveToFirst() }
        if (hasSeq) {
            db.rawQuery("SELECT name, seq FROM sqlite_sequence", null).use {
                while (it.moveToNext()) map[it.getString(0)] = it.getLong(1)
            }
        }
        return map
    }

    private fun countAttachedDatabases(db: SQLiteDatabase, schemaName: String): Int {
        var count = 0
        db.rawQuery("PRAGMA database_list", null).use {
            while (it.moveToNext()) {
                if (it.getString(1) == schemaName) count++
            }
        }
        return count
    }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long {
        db.rawQuery(sql, null).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return -1L
    }

    private fun queryString(db: SQLiteDatabase, sql: String): String {
        db.rawQuery(sql, null).use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return ""
    }
}
