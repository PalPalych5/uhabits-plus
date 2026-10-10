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

import android.database.sqlite.SQLiteDatabase
import androidx.preference.PreferenceManager
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseAndroidTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveInstrumentationTest : BaseAndroidTest() {

    @Test
    fun testCreateAndValidateVersionedZipBackup() {
        fixtures.purgeHabits(habitList)
        val habit = fixtures.createEmptyHabit()
        habit.originalEntries.add(Entry(getToday(), Entry.YES_MANUAL, "done"))
        habit.recompute()

        val targetZip = File(targetContext.cacheDir, "test_backup.zip")
        if (targetZip.exists()) targetZip.delete()

        try {
            val result = BackupArchiveCreator.createArchive(targetContext, targetZip)
            assertTrue(targetZip.exists())
            assertTrue(targetZip.length() > 0)
            assertEquals(29, result.databaseVersion)

            // Validate format
            val format = BackupArchiveValidator.detectFormat(targetZip)
            assertEquals(BackupFormat.VERSIONED_ZIP, format)

            // Validate integrity and compatibility
            val validation = BackupArchiveValidator.validate(targetZip, targetVersion = 29)
            assertTrue("Validation failed: ${validation.errorMessage}", validation.isValid)
            assertEquals(BackupCompatibilityStatus.COMPATIBLE, validation.compatibilityStatus)
            assertEquals(29, validation.databaseVersion)
            assertNotNull(validation.manifest)

            val manifest = validation.manifest!!
            assertEquals("LEGACY_PRODUCTION", manifest.runtimeAuthorityMode)
            assertEquals(29, manifest.databaseVersion)
            assertTrue(manifest.tableInventory.any { it.tableName == "Habits" && it.rowCount > 0 })
            assertTrue(manifest.tableInventory.any { it.tableName == "Repetitions" && it.rowCount > 0 })

            // Test extraction
            val extractedDb = File(targetContext.cacheDir, "extracted.db")
            if (extractedDb.exists()) extractedDb.delete()
            try {
                val extractedManifest = BackupArchiveExtractor.extractDatabase(targetZip, extractedDb)
                assertEquals(manifest.databaseSha256, extractedManifest.databaseSha256)
                assertTrue(extractedDb.exists())

                // Verify SQLite readability
                SQLiteDatabase.openDatabase(extractedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT name FROM Habits", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("Meditate", cursor.getString(0))
                    }
                }
            } finally {
                extractedDb.delete()
            }
        } finally {
            targetZip.delete()
        }
    }

    @Test
    fun testValidateRejectsV30ArchiveWhenTargetVersionIsV29() {
        val v30Zip = File(targetContext.cacheDir, "v30_archive.zip")
        if (v30Zip.exists()) v30Zip.delete()

        val tempDb = File(targetContext.cacheDir, "v30_dummy.db")
        if (tempDb.exists()) tempDb.delete()

        try {
            // Create dummy v30 DB
            val db = SQLiteDatabase.openOrCreateDatabase(tempDb, null)
            db.version = 30
            db.execSQL("CREATE TABLE Habits (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE Repetitions (id INTEGER PRIMARY KEY, habit INTEGER, timestamp INTEGER)")
            db.execSQL("CREATE TABLE HabitExtensions (id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE HabitBlocks (id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE HabitGoals (id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE AppSettings (id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE SyncQueue (id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE EntryOps (id INTEGER PRIMARY KEY)")
            for (table in DatabaseUtils.NEXT_TABLE_NAMES) {
                db.execSQL("CREATE TABLE `$table` (id INTEGER PRIMARY KEY)")
            }
            db.execSQL("INSERT INTO Habits(name) VALUES('Next Habit')")

            val inventory = mutableListOf<TableInventoryEntry>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val tName = cursor.getString(0)
                    val count = db.rawQuery("SELECT COUNT(*) FROM \"$tName\"", null).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else 0L
                    }
                    inventory.add(TableInventoryEntry(tName, count))
                }
            }
            db.close()

            val dbBytes = tempDb.readBytes()
            val md = MessageDigest.getInstance("SHA-256")
            val dbSha = md.digest(dbBytes).joinToString("") { "%02x".format(it) }
            val createdAt = System.currentTimeMillis()
            val canonical = BackupArchiveManifest.computeCanonicalContentSha256(30, dbSha, createdAt, inventory)
            val manifest = BackupArchiveManifest(
                archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
                formatVersion = 1,
                appId = "org.isoron.uhabits",
                appVersionCode = 300,
                createdAt = createdAt,
                databaseVersion = 30,
                databaseSizeBytes = dbBytes.size.toLong(),
                databaseSha256 = dbSha,
                datasetMode = "NEXT_PRODUCTION",
                tableInventory = inventory,
                canonicalContentSha256 = canonical,
                runtimeAuthorityMode = "NEXT_PRODUCTION"
            )

            ZipOutputStream(FileOutputStream(v30Zip)).use { zos ->
                zos.putNextEntry(ZipEntry(BackupArchiveFormat.SQLITE_ENTRY_NAME))
                zos.write(dbBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(BackupArchiveFormat.MANIFEST_ENTRY_NAME))
                zos.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // Target is 29 (LEGACY_PRODUCTION) -> MUST fail closed!
            val validation = BackupArchiveValidator.validate(v30Zip, targetVersion = 29)
            assertFalse(validation.isValid)
            assertEquals(BackupCompatibilityStatus.REQUIRES_NEXT_AUTHORITY, validation.compatibilityStatus)
            assertTrue(validation.errorMessage?.contains("cannot be restored into legacy production") == true)

            // Attempt restore -> MUST throw IncompatibleAuthorityException
            try {
                DatabaseUtils.restoreDatabaseFromBackup(targetContext, v30Zip)
                fail("Expected IncompatibleAuthorityException when restoring v30 archive into v29 runtime")
            } catch (e: BackupRestoreException.IncompatibleAuthorityException) {
                // Expected fail-closed behavior!
                assertTrue(e.message?.contains("cannot be restored into legacy production") == true)
            }
        } finally {
            tempDb.delete()
            v30Zip.delete()
        }
    }

    @Test
    fun testLegacyRawSqliteBackupRestorationStillWorksSeamlessly() {
        fixtures.purgeHabits(habitList)
        val habit = fixtures.createEmptyHabit()

        val rawDbFile = File(targetContext.cacheDir, "legacy_raw.db")
        if (rawDbFile.exists()) rawDbFile.delete()

        try {
            // Snapshot current DB as raw SQLite file
            DatabaseUtils.createDatabaseSnapshot(targetContext, rawDbFile)
            assertTrue(rawDbFile.exists())
            assertEquals(BackupFormat.LEGACY_RAW_SQLITE, BackupArchiveValidator.detectFormat(rawDbFile))

            val validation = BackupArchiveValidator.validate(rawDbFile, targetVersion = 29)
            assertTrue("Validation failed: ${validation.errorMessage}", validation.isValid)
            assertEquals(BackupCompatibilityStatus.COMPATIBLE, validation.compatibilityStatus)
            assertEquals(BackupFormat.LEGACY_RAW_SQLITE, validation.format)

            // Purge habits
            fixtures.purgeHabits(habitList)
            assertEquals(0, habitList.size())

            // Restore from raw SQLite .db file
            DatabaseUtils.restoreDatabaseFromBackup(targetContext, rawDbFile)

            // Verify habit restored
            val restoredDb = DatabaseUtils.openDatabase()
            try {
                restoredDb.rawQuery("SELECT name FROM Habits", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Meditate", cursor.getString(0))
                }
            } finally {
                restoredDb.close()
            }
        } finally {
            rawDbFile.delete()
        }
    }

    @Test
    fun testFailureInjectionDuringArchiveCreationCleansUpTempFiles() {
        val targetZip = File(targetContext.cacheDir, "failed_backup.zip")
        if (targetZip.exists()) targetZip.delete()

        val injections = listOf(
            BackupArchiveCreator.CreationFailureInjection.DURING_SNAPSHOT,
            BackupArchiveCreator.CreationFailureInjection.DURING_VALIDATION_PRE_ZIP,
            BackupArchiveCreator.CreationFailureInjection.DURING_ZIP_WRITE,
            BackupArchiveCreator.CreationFailureInjection.DURING_PUBLICATION
        )

        for (injection in injections) {
            BackupArchiveCreator.failureInjection = injection
            try {
                BackupArchiveCreator.createArchive(targetContext, targetZip)
                fail("Expected exception for injection $injection")
            } catch (e: IOException) {
                // Expected failure
                assertFalse("Target file should not exist on failure: $injection", targetZip.exists())
            } finally {
                BackupArchiveCreator.failureInjection = BackupArchiveCreator.CreationFailureInjection.NONE
            }
        }
    }

    @Test
    fun testCreateAndRestoreBackupWithPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
        prefs.edit()
            .putInt("pref_theme", 2)
            .putBoolean("pref_pure_black", true)
            .putString("pref_app_language", "ru")
            .putString("pref_sync_key", "secret-password")
            .putBoolean("pref_sync_review_required", false)
            .commit()

        fixtures.purgeHabits(habitList)
        val habit = fixtures.createEmptyHabit()
        habit.name = "Language Habit"
        habitList.update(listOf(habit))
        habit.recompute()

        val targetZip = File(targetContext.cacheDir, "test_prefs_backup.zip")
        if (targetZip.exists()) targetZip.delete()

        try {
            val result = BackupArchiveCreator.createArchive(targetContext, targetZip)
            assertTrue(targetZip.exists())
            assertEquals(29, result.databaseVersion)

            val validation = BackupArchiveValidator.validate(targetZip, targetVersion = 29)
            assertTrue(validation.isValid)
            assertNotNull(validation.manifest?.preferencesSha256)
            assertEquals(true, (validation.manifest?.preferencesCount ?: 0) > 0)

            // Modify preferences and habits before restore
            prefs.edit()
                .putInt("pref_theme", 0)
                .putBoolean("pref_pure_black", false)
                .putString("pref_app_language", "en")
                .commit()
            fixtures.purgeHabits(habitList)

            // Restore from backup
            DatabaseUtils.restoreDatabaseFromBackup(targetContext, targetZip)

            // Verify preferences restored
            assertEquals(2, prefs.getInt("pref_theme", -1))
            assertTrue(prefs.getBoolean("pref_pure_black", false))
            assertEquals("ru", prefs.getString("pref_app_language", null))

            // Verify secret was preserved
            assertEquals("secret-password", prefs.getString("pref_sync_key", null))

            // Verify sync quarantine invariant unconditionally applied
            assertTrue("pref_sync_review_required must be true after restore", prefs.getBoolean("pref_sync_review_required", false))

            // Verify database restored
            val db = DatabaseUtils.openDatabase()
            try {
                db.rawQuery("SELECT name FROM Habits", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Language Habit", cursor.getString(0))
                }
            } finally {
                db.close()
            }
        } finally {
            targetZip.delete()
        }
    }

    @Test
    fun testRestoreFailureInjectionRollsBackCleanly() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
        prefs.edit()
            .putInt("pref_theme", 1)
            .putBoolean("pref_pure_black", false)
            .commit()

        fixtures.purgeHabits(habitList)
        val habit = fixtures.createEmptyHabit()
        habit.name = "Original Habit"
        habitList.update(listOf(habit))
        habit.recompute()

        val backupZip = File(targetContext.cacheDir, "rollback_test.zip")
        if (backupZip.exists()) backupZip.delete()

        try {
            BackupArchiveCreator.createArchive(targetContext, backupZip)

            // Change state before failed restore
            prefs.edit().putInt("pref_theme", 3).commit()
            fixtures.purgeHabits(habitList)
            val newHabit = fixtures.createEmptyHabit()
            newHabit.name = "Unrestored Habit"
            habitList.update(listOf(newHabit))
            newHabit.recompute()

            // Inject failure during preferences apply
            DatabaseUtils.restoreFailureInjection = DatabaseUtils.RestoreFailureInjection.BEFORE_PREFERENCES_APPLY
            try {
                DatabaseUtils.restoreDatabaseFromBackup(targetContext, backupZip)
                fail("Expected IOException from injected failure")
            } catch (e: IOException) {
                // Expected failure
            } finally {
                DatabaseUtils.restoreFailureInjection = DatabaseUtils.RestoreFailureInjection.NONE
            }

            // Verify preferences rolled back to state prior to restore attempt
            assertEquals(3, prefs.getInt("pref_theme", -1))
            // Verify sync quarantine invariant set upon rollback
            assertTrue(prefs.getBoolean("pref_sync_review_required", false))

            // Verify database rolled back to state prior to restore attempt
            val db = DatabaseUtils.openDatabase()
            try {
                db.rawQuery("SELECT name FROM Habits", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Unrestored Habit", cursor.getString(0))
                }
            } finally {
                db.close()
            }
        } finally {
            backupZip.delete()
        }
    }
}
