package org.isoron.uhabits.core.database.migrations

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.query
import org.isoron.platform.io.setVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class Version26Test {
    @Test
    fun migrationCreatesDefaultsAndEnablesMinuteTimers() = runTest {
        val db = JavaDatabaseOpener().open(":memory:")
        db.setVersion(8)
        db.migrateTo(25) { TestDatabaseHelper.loadMigrationSQL(it) }
        insertHabit(db, "Reading", type = 1, unit = "мин")
        insertHabit(db, "Sets", type = 1, unit = "sets")
        insertHabit(db, "Checkbox", type = 0, unit = "min")
        val before = value(db, "select count(*) from Habits")

        db.migrateTo(26) { TestDatabaseHelper.loadMigrationSQL(it) }

        assertEquals(before, value(db, "select count(*) from Habits"))
        assertEquals(before, value(db, "select count(*) from HabitExtensions"))
        assertEquals(0, value(db, "select count(*) from HabitExtensions where day_tier != 'NORMAL'"))
        assertEquals(1, timerEnabled(db, "Reading"))
        assertEquals(0, timerEnabled(db, "Sets"))
        assertEquals(0, timerEnabled(db, "Checkbox"))
        db.close()
    }

    private fun insertHabit(db: Database, name: String, type: Int, unit: String) {
        val statement = db.prepareStatement(
            """insert into Habits(name, description, question, freq_num, freq_den, color,
               position, reminder_days, highlight, archived, type, target_value, target_type, unit, uuid)
               values (?, '', '', 1, 1, 0, 0, 0, 0, 0, ?, 0, 0, ?, ?)"""
        )
        statement.bindText(1, name)
        statement.bindInt(2, type)
        statement.bindText(3, unit)
        statement.bindText(4, name)
        statement.step()
        statement.finalize()
    }

    private fun timerEnabled(db: Database, name: String): Int = value(
        db,
        "select timer_enabled from HabitExtensions where habit_id = (select id from Habits where name = '$name')"
    )

    private fun value(db: Database, sql: String): Int {
        var result = -1
        db.query(sql) { result = it.getInt(0) }
        return result
    }
}
