/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.migrateTo
import org.isoron.uhabits.HabitsApplication.Companion.isTestMode
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.database.AndroidDatabase
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object DatabaseUtils {
    private var opener: HabitsDatabaseOpener? = null

    @JvmStatic
    fun getDatabaseFile(context: Context): File {
        val databaseFilename = databaseFilename
        val root = context.filesDir.path
        return File("$root/../databases/$databaseFilename")
    }

    private val databaseFilename: String
        get() {
            var databaseFilename: String = DATABASE_FILENAME
            if (isTestMode()) databaseFilename = "test.db"
            return databaseFilename
        }

    fun initializeDatabase(context: Context?) {
        opener = HabitsDatabaseOpener(
            context!!,
            databaseFilename,
            DATABASE_VERSION
        )
    }

    fun closeDatabase() {
        opener?.close()
        opener = null
    }

    fun createDatabaseSnapshot(context: Context, tempFile: File): SnapshotInfo {
        val db = openDatabase()
        tempFile.parentFile?.mkdirs()
        if (tempFile.exists()) tempFile.delete()

        checkpointDatabase(db)

        try {
            db.execSQL("VACUUM INTO '${escapeSqlString(tempFile.absolutePath)}'")
        } catch (e: Exception) {
            Log.w("DatabaseUtils", "VACUUM INTO failed, falling back to validated file copy", e)
            val source = getDatabaseFile(context)
            source.copyTo(tempFile, overwrite = true)
        }

        val validation = validateDatabaseBackup(tempFile)
        if (!validation.isValid || validation.databaseVersion == null) {
            tempFile.delete()
            throw IOException(validation.errorMessage ?: "Invalid backup snapshot")
        }

        return SnapshotInfo(
            sizeBytes = tempFile.length(),
            databaseVersion = validation.databaseVersion
        )
    }

    fun publishLocalBackup(tempFile: File, finalFile: File) {
        finalFile.parentFile?.mkdirs()
        if (finalFile.exists()) finalFile.delete()
        Files.move(
            tempFile.toPath(),
            finalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    fun copyFileToDocument(context: Context, source: File, target: DocumentFile) {
        Log.i("DatabaseUtils", "Writing: ${target.uri}")
        FileInputStream(source).use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            } ?: throw IOException("Unable to open target output stream")
        }
    }

    fun validateDatabaseBackup(file: File): org.isoron.uhabits.backup.BackupValidationResult {
        if (!file.exists() || file.length() <= 0L) {
            return org.isoron.uhabits.backup.BackupValidationResult(
                isValid = false,
                errorMessage = "Backup file is empty or missing"
            )
        }
        val expectedHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val actualHeader = ByteArray(expectedHeader.size)
        val bytesRead = file.inputStream().use { input ->
            var offset = 0
            while (offset < actualHeader.size) {
                val count = input.read(actualHeader, offset, actualHeader.size - offset)
                if (count <= 0) break
                offset += count
            }
            offset
        }
        if (bytesRead != expectedHeader.size || !actualHeader.contentEquals(expectedHeader)) {
            return org.isoron.uhabits.backup.BackupValidationResult(
                isValid = false,
                errorMessage = "Backup file is not a SQLite database"
            )
        }

        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            val version = db.version
            if (version > DATABASE_VERSION) {
                return org.isoron.uhabits.backup.BackupValidationResult(
                    isValid = false,
                    errorMessage = "Backup uses newer database version"
                )
            }

            val tables = db.rawQuery(
                "select count(*) from sqlite_master where name='Habits' or name='Repetitions'",
                null
            ).use {
                it.moveToFirst()
                it.getInt(0)
            }
            if (tables != 2) {
                org.isoron.uhabits.backup.BackupValidationResult(
                    isValid = false,
                    errorMessage = "Backup is missing required tables"
                )
            } else {
                org.isoron.uhabits.backup.BackupValidationResult(
                    isValid = true,
                    databaseVersion = version
                )
            }
        } finally {
            db.close()
        }
    }

    fun restoreDatabaseFromBackup(context: Context, stagedBackupFile: File) {
        val validation = validateDatabaseBackup(stagedBackupFile)
        require(validation.isValid) { validation.errorMessage ?: "Invalid backup" }

        val dbFile = getDatabaseFile(context)
        val parent = requireNotNull(dbFile.parentFile)
        val restoreTemp = File(parent, "${dbFile.name}.restore.tmp")
        val rollbackFile = File(parent, "${dbFile.name}.rollback")
        cleanupSidecars(dbFile)
        cleanupSidecars(restoreTemp)
        if (restoreTemp.exists()) restoreTemp.delete()
        if (rollbackFile.exists()) rollbackFile.delete()

        stagedBackupFile.copyTo(restoreTemp, overwrite = true)

        if ((validation.databaseVersion ?: DATABASE_VERSION) < DATABASE_VERSION) {
            val migrated = SQLiteDatabase.openDatabase(
                restoreTemp.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            try {
                val wrappedDb = AndroidDatabase(migrated)
                runBlocking {
                    wrappedDb.migrateTo(DATABASE_VERSION) { version ->
                        val filename = "%02d.sql".format(version)
                        context.assets.open("migrations/$filename").bufferedReader().readText()
                    }
                }
            } finally {
                migrated.close()
            }
        }

        closeDatabase()

        try {
            if (dbFile.exists()) {
                Files.move(
                    dbFile.toPath(),
                    rollbackFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
            Files.move(
                restoreTemp.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            initializeDatabase(context)
            openDatabase().close()
            rollbackFile.delete()
        } catch (e: Exception) {
            if (!dbFile.exists() && rollbackFile.exists()) {
                Files.move(
                    rollbackFile.toPath(),
                    dbFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
            initializeDatabase(context)
            throw e
        } finally {
            cleanupSidecars(dbFile)
            cleanupSidecars(restoreTemp)
            restoreTemp.delete()
            rollbackFile.delete()
        }
    }

    fun openDatabase(): SQLiteDatabase {
        checkNotNull(opener)
        return opener!!.writableDatabase
    }

    private fun checkpointDatabase(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use {
            while (it.moveToNext()) {
                // Drain cursor to ensure checkpoint is executed.
            }
        }
    }

    private fun escapeSqlString(value: String): String {
        return value.replace("'", "''")
    }

    private fun cleanupSidecars(file: File) {
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    data class SnapshotInfo(
        val sizeBytes: Long,
        val databaseVersion: Int
    )
}
