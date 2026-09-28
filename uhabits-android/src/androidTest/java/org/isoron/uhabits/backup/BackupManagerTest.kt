package org.isoron.uhabits.backup

import android.database.sqlite.SQLiteDatabase
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseAndroidTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Test

class BackupManagerTest : BaseAndroidTest() {
    @Test
    fun testBackupAndRestoreRoundTripRestoresHabitsAndEntries() {
        fixtures.purgeHabits(habitList)
        val habit = fixtures.createEmptyHabit()
        habit.originalEntries.add(Entry(getToday(), Entry.YES_MANUAL, "done"))
        habit.recompute()

        val manager = appComponent.backupManager
        val backup = manager.backupNow()

        fixtures.purgeHabits(habitList)
        assertEquals(0, habitList.size())

        val entry = manager.listBackups().first { it.location == backup.location }
        manager.restoreFromBackup(entry)
        val restoredDb = SQLiteDatabase.openDatabase(
            DatabaseUtils.getDatabaseFile(targetContext).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        restoredDb.use { db ->
            db.rawQuery("SELECT id, name FROM Habits", null).use { habits ->
                assertTrue(habits.moveToFirst())
                assertEquals("Meditate", habits.getString(1))
                val habitId = habits.getLong(0)
                assertFalse(habits.moveToNext())
                db.rawQuery(
                    "SELECT value, notes FROM Repetitions WHERE habit = ? AND timestamp = ?",
                    arrayOf(habitId.toString(), getToday().unixTime.toString())
                ).use { entries ->
                    assertTrue(entries.moveToFirst())
                    assertEquals(Entry.YES_MANUAL, entries.getInt(0))
                    assertEquals("done", entries.getString(1))
                }
            }
        }
    }

    @Test
    fun testInvalidBackupIsRejected() {
        val temp = kotlin.io.path.createTempFile(suffix = ".db").toFile().apply {
            writeText("not a sqlite database")
        }
        val result = appComponent.backupManager.validateBackup(
            BackupEntry(
                name = temp.name,
                location = temp.absolutePath,
                modifiedAt = temp.lastModified(),
                sizeBytes = temp.length(),
                source = BackupSource.PRIVATE
            )
        )

        assertFalse(result.isValid)
        temp.delete()
    }

    @Test
    fun testVersionMismatchIsRejected() {
        val temp = kotlin.io.path.createTempFile(suffix = ".db").toFile()
        val db = SQLiteDatabase.openOrCreateDatabase(temp, null)
        db.execSQL("create table Habits(id integer primary key autoincrement)")
        db.execSQL("create table Repetitions(id integer primary key autoincrement, habit integer, timestamp integer)")
        db.version = 999
        db.close()

        val result = DatabaseUtils.validateDatabaseBackup(temp)

        assertFalse(result.isValid)
        temp.delete()
    }

    @Test
    fun testCurrentVersionWithoutExtensionTablesIsRejected() {
        val temp = kotlin.io.path.createTempFile(suffix = ".db").toFile()
        val db = SQLiteDatabase.openOrCreateDatabase(temp, null)
        db.execSQL("create table Habits(id integer primary key autoincrement)")
        db.execSQL("create table Repetitions(id integer primary key autoincrement)")
        db.version = org.isoron.uhabits.core.DATABASE_VERSION
        db.close()

        assertFalse(DatabaseUtils.validateDatabaseBackup(temp).isValid)
        temp.delete()
    }
}
