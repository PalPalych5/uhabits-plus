package org.isoron.uhabits.core.database.migrations

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.query
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Version29Test {
    @Test
    fun migrationBackfillsSyncMetadataAndCreatesQueueTables() = runTest {
        val db = JavaDatabaseOpener().open(":memory:")
        db.setVersion(8)
        db.migrateTo(28) { TestDatabaseHelper.loadMigrationSQL(it) }
        db.run(
            """insert into Habits(name, description, question, freq_num, freq_den, color, position, reminder_days, highlight, archived, type, target_value, target_type, unit, uuid)
               values ('Reading', '', '', 1, 1, 0, 0, 0, 0, 0, 1, 15.0, 0, 'min', 'reading-uuid')"""
        )
        db.run(
            """insert into Repetitions(habit, timestamp, value, notes)
               values (1, 86400000, 45000, 'timer')"""
        )

        db.migrateTo(29) { TestDatabaseHelper.loadMigrationSQL(it) }

        assertEquals(1, value(db, "select count(*) from sqlite_master where type='table' and name='SyncQueue'"))
        assertEquals(1, value(db, "select count(*) from sqlite_master where type='table' and name='EntryOps'"))
        assertEquals(1, value(db, "select count(*) from Habits where uuid = 'reading-uuid'"))
        assertTrue(textValue(db, "select uuid from Repetitions where habit = 1")!!.startsWith("reading-uuid:"))
        assertEquals(0, value(db, "select count(*) from Habits where deleted_at is not null"))
        db.close()
    }

    private fun value(db: org.isoron.platform.io.Database, sql: String): Int {
        var result = -1
        db.query(sql) { result = it.getInt(0) }
        return result
    }

    private fun textValue(db: org.isoron.platform.io.Database, sql: String): String? {
        var result: String? = null
        db.query(sql) { result = it.getTextOrNull(0) }
        return result
    }
}
