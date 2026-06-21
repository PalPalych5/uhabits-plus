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

class Version27Test {
    @Test
    fun migrationCreatesBlocksAndMapsHabitsByColor() = runTest {
        val db = JavaDatabaseOpener().open(":memory:")
        db.setVersion(8)
        db.migrateTo(25) { TestDatabaseHelper.loadMigrationSQL(it) }

        // Insert habits with different colors to test mappings
        insertHabit(db, "H0", color = 0)
        insertHabit(db, "H1", color = 1)
        insertHabit(db, "H15", color = 15)
        insertHabit(db, "H2", color = 2)
        insertHabit(db, "H3", color = 3)
        insertHabit(db, "H4", color = 4)
        insertHabit(db, "H5", color = 5)
        insertHabit(db, "H6", color = 6)
        insertHabit(db, "H7", color = 7)
        insertHabit(db, "H8", color = 8)
        insertHabit(db, "H9", color = 9)
        insertHabit(db, "H10", color = 10)
        insertHabit(db, "H11", color = 11)
        insertHabit(db, "H12", color = 12)
        insertHabit(db, "H13", color = 13)
        insertHabit(db, "H14", color = 14)
        insertHabit(db, "H99", color = 99)

        db.migrateTo(26) { TestDatabaseHelper.loadMigrationSQL(it) }
        db.migrateTo(27) { TestDatabaseHelper.loadMigrationSQL(it) }

        // Assert: HabitBlocks contains 7 default blocks
        assertEquals(7, value(db, "select count(*) from HabitBlocks"))

        // Assert color mappings
        assertEquals(6, blockId(db, "H0"))
        assertEquals(6, blockId(db, "H1"))
        assertEquals(6, blockId(db, "H15"))
        assertEquals(5, blockId(db, "H2"))
        assertEquals(5, blockId(db, "H3"))
        assertEquals(5, blockId(db, "H4"))
        assertEquals(3, blockId(db, "H5"))
        assertEquals(3, blockId(db, "H6"))
        assertEquals(3, blockId(db, "H7"))
        assertEquals(4, blockId(db, "H8"))
        assertEquals(1, blockId(db, "H9"))
        assertEquals(1, blockId(db, "H10"))
        assertEquals(1, blockId(db, "H11"))
        assertEquals(1, blockId(db, "H12"))
        assertEquals(2, blockId(db, "H13"))
        assertEquals(2, blockId(db, "H14"))
        assertEquals(7, blockId(db, "H99"))

        db.close()
    }

    private fun insertHabit(db: Database, name: String, color: Int) {
        val statement = db.prepareStatement(
            """insert into Habits(name, description, question, freq_num, freq_den, color,
               position, reminder_days, highlight, archived, type, target_value, target_type, unit, uuid)
               values (?, '', '', 1, 1, ?, 0, 0, 0, 0, 1, 0, 0, 'min', ?)"""
        )
        statement.bindText(1, name)
        statement.bindInt(2, color)
        statement.bindText(3, name)
        statement.step()
        statement.finalize()
    }

    private fun blockId(db: Database, name: String): Int = value(
        db,
        "select block_id from HabitExtensions where habit_id = (select id from Habits where name = '$name')"
    )

    private fun value(db: Database, sql: String): Int {
        var result = -1
        db.query(sql) { result = it.getInt(0) }
        return result
    }
}
