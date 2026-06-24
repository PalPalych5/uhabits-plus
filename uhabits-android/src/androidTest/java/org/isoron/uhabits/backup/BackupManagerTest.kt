package org.isoron.uhabits.backup

import android.database.sqlite.SQLiteDatabase
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseAndroidTest
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupManagerTest : BaseAndroidTest() {
    @Test
    fun backupAndRestoreRoundTripRestoresHabitsAndEntries() {
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
        (targetContext.applicationContext as HabitsApplication).reloadAfterDatabaseRestore()

        val reloadedHabits = (HabitsApplication.component.habitList as SQLiteHabitList)
        reloadedHabits.reload()
        assertEquals(1, reloadedHabits.size())
        val restored = reloadedHabits.iterator().next()
        assertEquals("Meditate", restored.name)
        assertEquals("done", restored.originalEntries.get(getToday()).notes)
        assertEquals(Entry.YES_MANUAL, restored.originalEntries.get(getToday()).value)
    }

    @Test
    fun invalidBackupIsRejected() {
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
    fun versionMismatchIsRejected() {
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
}
