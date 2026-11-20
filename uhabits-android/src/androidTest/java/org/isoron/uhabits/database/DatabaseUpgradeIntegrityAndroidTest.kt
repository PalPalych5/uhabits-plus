/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.database

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.queryString
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.backup.RestoreJournal
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeIntegrityAndroidTest {
    private lateinit var context: Context

    @Before
    fun requireDatabaseOnlyRunner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is TaskDueMigrationRunner)
        context = instrumentation.targetContext
    }

    @Test
    fun v35UpgradeRollsBackLateFailureAndPreservesLinkedHistoryAfterRetry() {
        val filename = "integrity-${UUID.randomUUID()}.db"
        // This collision makes the last statement of the real v36 asset fail,
        // after ALTER TABLE and CREATE TABLE have both run.
        val blockingTable = "idx_habit_day_adjustments_habit_date"
        try {
            HabitsDatabaseOpener(context, filename, 35).use { opener ->
                val db = opener.writableDatabase
                db.execSQL("INSERT INTO Habits(id, uuid, name, freq_num, freq_den) VALUES (42, 'habit-42', 'Read', 3, 7)")
                db.execSQL("INSERT INTO HabitExtensions(habit_id, timer_enabled) VALUES (42, 1)")
                db.execSQL("INSERT INTO Repetitions(id, habit, timestamp, value, notes) VALUES (81, 42, 172800000, 2000, 'Chapter 1')")
                db.execSQL("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('parent', 'Read book', 1000, 2000)")
                db.execSQL(
                    """
                    INSERT INTO UserTasks(id, title, parent_task_id, status, sort_order, completed_at,
                        created_at, updated_at, linked_habit_uuid, habit_time_accrual_enabled,
                        habit_link_effective_from, due_kind, due_time_millis, due_zone)
                    VALUES ('child', 'Read chapter', 'parent', 'COMPLETED', 4, 5000, 1000, 5000,
                        'habit-42', 1, 2000, 'TIMED', 1792756800123, 'UTC')
                    """.trimIndent()
                )
                db.execSQL("CREATE TABLE $blockingTable(marker TEXT)")
            }

            HabitsDatabaseOpener(context, filename, DATABASE_VERSION).use { opener ->
                val failure = assertThrows(SQLiteException::class.java) { opener.writableDatabase }
                assertTrue(failure.message.orEmpty().contains(blockingTable))
            }

            HabitsDatabaseOpener(context, filename, 35).use { opener ->
                val db = AndroidDatabase(opener.writableDatabase)
                assertEquals(35, opener.writableDatabase.version)
                assertEquals(0L, db.queryLong("SELECT count(*) FROM sqlite_master WHERE name = 'HabitDayAdjustments'"))
                assertEquals(0L, db.queryLong("SELECT count(*) FROM pragma_table_info('HabitExtensions') WHERE name = 'missing_data_policy'"))
                assertLinkedFacts(db)
                opener.writableDatabase.execSQL("DROP TABLE $blockingTable")
            }

            repeat(2) {
                HabitsDatabaseOpener(context, filename, DATABASE_VERSION).use { opener ->
                    val db = AndroidDatabase(opener.writableDatabase)
                    assertEquals(DATABASE_VERSION, opener.writableDatabase.version)
                    assertEquals("ok", db.queryString("PRAGMA integrity_check"))
                    assertEquals(0L, db.queryLong("SELECT count(*) FROM pragma_foreign_key_check"))
                    assertEquals(1L, db.queryLong("SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = '$blockingTable'"))
                    assertEquals(1L, db.queryLong("SELECT count(*) FROM HabitExtensions WHERE habit_id = 42 AND missing_data_policy IS NULL"))
                    assertLinkedFacts(db)
                }
            }
        } finally {
            context.deleteDatabase(filename)
        }
    }

    @Test
    fun v29RestoreIntoNextConvertsBlockPlacementsAndPreservesHistory() {
        val fixture = "integrity-${UUID.randomUUID()}"
        val filename = "$fixture.db"
        val isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                baseContext.getSharedPreferences(fixture, mode)
        }
        val archive = File.createTempFile(fixture, ".db", context.cacheDir)
        try {
            HabitsDatabaseOpener(context, archive.absolutePath, LEGACY_DATABASE_VERSION).use { opener ->
                val db = opener.writableDatabase
                db.execSQL("INSERT INTO HabitBlocks(id, uuid, name, color, position) VALUES (70, 'legacy-block', 'Reading', 3, 8)")
                db.execSQL("INSERT INTO Habits(id, uuid, name, freq_num, freq_den) VALUES (42, 'habit-42', 'Read', 3, 7)")
                db.execSQL("INSERT INTO HabitExtensions(habit_id, block_id, timer_enabled) VALUES (42, 70, 1)")
                db.execSQL("INSERT INTO Repetitions(id, habit, timestamp, value, notes) VALUES (81, 42, 172800000, 2000, 'Chapter 1')")
            }
            val preferences = PreferenceManager.getDefaultSharedPreferences(isolatedContext)
            assertTrue(preferences.edit().putString("pref_first_weekday", "3").commit())
            DatabaseUtils.initializeDatabase(isolatedContext, filename)
            DatabaseUtils.openDatabase().execSQL(
                "INSERT INTO Habits(id, uuid, name) VALUES (97, 'pre-restore-habit', 'Before restore')"
            )

            DatabaseUtils.restoreDatabaseFromBackup(isolatedContext, archive)
            DatabaseUtils.closeDatabase()

            HabitsDatabaseOpener(isolatedContext, filename, DATABASE_VERSION).use { opener ->
                val sqliteDb = opener.writableDatabase
                val db = AndroidDatabase(sqliteDb)
                assertEquals(DATABASE_VERSION, sqliteDb.version)
                assertEquals("NEXT", db.queryString("SELECT runtime_authority FROM OrganizationState WHERE id = 1"))
                assertEquals("ok", db.queryString("PRAGMA integrity_check"))
                assertEquals(0L, db.queryLong("SELECT count(*) FROM pragma_foreign_key_check"))
                assertEquals(
                    "Reading",
                    db.queryString(
                        """
                        SELECT c.name FROM HabitPlacements p
                        JOIN Containers c ON c.id = p.container_id
                        JOIN LegacyBlockMap m ON m.container_id = c.id
                        JOIN HabitPlacementHistory hist
                          ON hist.habit_id = p.habit_id AND hist.container_id = c.id AND hist.revision = p.revision
                        WHERE p.habit_id = 42 AND m.legacy_block_id = 70 AND m.legacy_block_uuid = 'legacy-block'
                        """.trimIndent()
                    )
                )
                assertHabitHistory(db)
            }
            assertEquals("3", preferences.getString("pref_first_weekday", null))
            assertTrue(preferences.getBoolean("pref_sync_review_required", false))
            assertFalse(RestoreJournal(context.getDatabasePath(filename).parentFile!!, filename).exists)
        } finally {
            DatabaseUtils.closeDatabase()
            RestoreJournal(context.getDatabasePath(filename).parentFile!!, filename).clear()
            context.deleteDatabase("$filename.restore.tmp")
            context.deleteDatabase(filename)
            context.deleteSharedPreferences(fixture)
            archive.delete()
        }
    }

    private fun assertHabitHistory(db: AndroidDatabase) {
        assertEquals(
            listOf("habit-42", "Read", 3, 7, 1, 81, 172800000L, 2000, "Chapter 1"),
            db.querySingle(
                """
                SELECT h.uuid, h.name, h.freq_num, h.freq_den, e.timer_enabled, r.id, r.timestamp, r.value, r.notes
                FROM Habits h JOIN Repetitions r ON r.habit = h.id
                JOIN HabitExtensions e ON e.habit_id = h.id
                WHERE h.id = 42
                """.trimIndent()
            ) {
                listOf(it.getText(0), it.getText(1), it.getInt(2), it.getInt(3), it.getInt(4),
                    it.getInt(5), it.getLong(6), it.getInt(7), it.getText(8))
            }
        )
    }

    private fun assertLinkedFacts(db: AndroidDatabase) {
        assertHabitHistory(db)
        assertEquals(
            listOf("child", "parent", "habit-42", "COMPLETED", 4, 5000L, 1, 2000L, "TIMED", 1792756800123L, "UTC"),
            db.querySingle(
                """
                SELECT t.id, p.id, h.uuid, t.status, t.sort_order, t.completed_at, t.habit_time_accrual_enabled,
                       t.habit_link_effective_from, t.due_kind, t.due_time_millis, t.due_zone
                FROM UserTasks t JOIN UserTasks p ON p.id = t.parent_task_id
                JOIN Habits h ON h.uuid = t.linked_habit_uuid
                WHERE t.id = 'child'
                """.trimIndent()
            ) {
                listOf(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getInt(4), it.getLong(5),
                    it.getInt(6), it.getLong(7), it.getText(8), it.getLong(9), it.getText(10))
            }
        )
    }
}
