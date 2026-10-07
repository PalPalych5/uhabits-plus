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
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.containers.backup.*
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The old component has been shut down; restart even if the restored file was rolled back. */
class ExperimentalRestoreRestartRequiredException(cause: Throwable) : IOException("Restore failed; the previous dataset was recovered and the app must restart", cause)

object DatabaseUtils {
    private var opener: HabitsDatabaseOpener? = null
    private var currentDatabaseFilename: String? = null

    @JvmStatic
    fun getDatabaseFile(context: Context, filename: String? = null): File {
        val resolvedName = filename ?: currentDatabaseFilename ?: defaultDatabaseFilename
        val root = context.filesDir.path
        return File("$root/../databases/$resolvedName")
    }

    private val defaultDatabaseFilename: String
        get() {
            var databaseFilename: String = DATABASE_FILENAME
            if (isTestMode()) databaseFilename = "test.db"
            return databaseFilename
        }

    fun initializeDatabase(context: Context?, filename: String? = null) {
        val resolvedName = filename ?: defaultDatabaseFilename
        currentDatabaseFilename = resolvedName
        val targetVersion = if (org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.isCutoverAuthorized(resolvedName)) {
            DATABASE_VERSION
        } else {
            org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
        }
        opener = HabitsDatabaseOpener(
            context!!,
            resolvedName,
            targetVersion
        )
    }

    fun closeDatabase() {
        opener?.close()
        opener = null
        currentDatabaseFilename = null
    }

    fun createDatabaseSnapshot(context: Context, tempFile: File): SnapshotInfo {
        val db = openDatabase()
        tempFile.parentFile?.mkdirs()
        if (tempFile.exists()) tempFile.delete()

        val app = context.applicationContext as? HabitsApplication
        val session = app?.component?.session
        if (session?.isExperimental == true) {
            val target = SQLiteDatabase.openOrCreateDatabase(tempFile, null)
            try {
                val preferences = context.getSharedPreferences("uhabits_prefs_${session.preferencesNamespace}", Context.MODE_PRIVATE)
                    .all.mapValues { it.value.toString() }
                val result = ContainerBackupService.createBackup(AndroidDatabase(db), AndroidDatabase(target), System.currentTimeMillis(), preferences)
                if (result is BackupCreationResult.Failure) throw IOException(result.error, result.cause)
            } finally { target.close() }
            val validation = validateDatabaseBackup(tempFile)
            if (!validation.isValid) { tempFile.delete(); throw IOException(validation.errorMessage) }
            return SnapshotInfo(tempFile.length(), requireNotNull(validation.databaseVersion))
        }

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
            if (version <= 0 || version > DATABASE_VERSION) {
                return org.isoron.uhabits.backup.BackupValidationResult(
                    isValid = false,
                    errorMessage = "Backup has an unsupported database version"
                )
            }

            val integrity = db.rawQuery("PRAGMA quick_check", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (integrity != "ok") {
                return org.isoron.uhabits.backup.BackupValidationResult(
                    isValid = false,
                    errorMessage = "Backup database failed integrity check"
                )
            }

            val requiredTables = buildList {
                add("Habits")
                add("Repetitions")
                if (version >= 26) add("HabitExtensions")
                if (version >= 27) add("HabitBlocks")
                if (version >= 28) {
                    add("HabitGoals")
                    add("AppSettings")
                }
                if (version >= 29) {
                    add("SyncQueue")
                    add("EntryOps")
                }
            }
            val presentTables = db.rawQuery(
                "select name from sqlite_master where type='table'", null
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            if (!presentTables.containsAll(requiredTables)) {
                org.isoron.uhabits.backup.BackupValidationResult(
                    isValid = false,
                    errorMessage = "Backup is missing required tables"
                )
            } else {
                if (ContainerBackupRouter.REQUIRED_ORGANIZATION_TABLES.any(presentTables::contains) || presentTables.contains("FocusSessions")) {
                    val stage = SQLiteDatabase.create(null)
                    val target = SQLiteDatabase.create(null)
                    try {
                        val wrapped = AndroidDatabase(db)
                        val result = ContainerRestoreService.restore(wrapped, AndroidDatabase(stage), AndroidDatabase(target),
                            externalManifest = compatibleOldExperimentalManifest(wrapped))
                        if (result is org.isoron.uhabits.core.containers.backup.RestoreResult.Failure) {
                            return org.isoron.uhabits.backup.BackupValidationResult(false, errorMessage = result.reason)
                        }
                    } finally { stage.close(); target.close() }
                }
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
        val activeFilename = dbFile.name
        val app = context.applicationContext as? HabitsApplication
        val session = app?.component?.session
        val backup = SQLiteDatabase.openDatabase(stagedBackupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val hasOrganization = try {
            val tables = DatabaseSnapshotter.listTables(AndroidDatabase(backup))
            ContainerBackupRouter.REQUIRED_ORGANIZATION_TABLES.any(tables::contains) || "FocusSessions" in tables
        } finally { backup.close() }
        require(session?.isExperimental == true || !hasOrganization) { "Experimental backup cannot replace a production dataset" }
        val parent = requireNotNull(dbFile.parentFile)
        val restoreTemp = File(parent, "${dbFile.name}.restore.tmp")
        val rollbackFile = File(parent, "${dbFile.name}.rollback")
        cleanupSidecars(restoreTemp)
        if (restoreTemp.exists()) restoreTemp.delete()
        if (rollbackFile.exists()) {
            if (dbFile.exists()) {
                throw IOException("Previous database restore rollback requires manual recovery")
            }
            Files.move(rollbackFile.toPath(), dbFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        if (session?.isExperimental == true) {
            val source = SQLiteDatabase.openDatabase(stagedBackupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val stage = SQLiteDatabase.create(null)
            val target = SQLiteDatabase.openOrCreateDatabase(restoreTemp, null)
            try {
                val wrapped = AndroidDatabase(source)
                val result = ContainerRestoreService.restore(wrapped, AndroidDatabase(stage), AndroidDatabase(target),
                    externalManifest = compatibleOldExperimentalManifest(wrapped))
                require(result is org.isoron.uhabits.core.containers.backup.RestoreResult.Success) {
                    (result as? org.isoron.uhabits.core.containers.backup.RestoreResult.Failure)?.reason ?: "Invalid experimental backup"
                }
                require((result.kind as? DatasetKind.ExperimentalContainer)?.datasetUuid == session.datasetId) {
                    "Restore requires the same experimental dataset identity"
                }
            } catch (error: Throwable) { restoreTemp.delete(); throw error }
            finally { source.close(); stage.close(); target.close() }
        } else stagedBackupFile.copyTo(restoreTemp, overwrite = true)

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

        val migratedValidation = validateDatabaseBackup(restoreTemp)
        require(migratedValidation.isValid && migratedValidation.databaseVersion == DATABASE_VERSION) {
            migratedValidation.errorMessage ?: "Migrated backup is invalid"
        }

        if (session?.isExperimental == true) requireNotNull(app).shutdownForDatabaseRestoreRestart()
        closeDatabase()
        cleanupSidecars(dbFile)

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
            initializeDatabase(context, activeFilename)
            openDatabase().close()
            if (session?.isExperimental == true) {
                context.getSharedPreferences(session.preferencesNamespace + "_timer", Context.MODE_PRIVATE).edit().clear().commit()
            }
            rollbackFile.delete()
        } catch (e: Exception) {
            closeDatabase()
            if (rollbackFile.exists()) {
                cleanupSidecars(dbFile)
                Files.move(
                    rollbackFile.toPath(),
                    dbFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
            initializeDatabase(context, activeFilename)
            if (session?.isExperimental == true) throw ExperimentalRestoreRestartRequiredException(e)
            throw e
        } finally {
            cleanupSidecars(restoreTemp)
            restoreTemp.delete()
        }
    }

    fun openDatabase(): SQLiteDatabase {
        checkNotNull(opener)
        return opener!!.writableDatabase
    }

    /** Pre-Focus Android snapshots were raw files without a manifest; only fact-free ones can be adapted. */
    private fun compatibleOldExperimentalManifest(db: AndroidDatabase): ContainerBackupManifest? {
        val tables = DatabaseSnapshotter.listTables(db)
        if ("OrganizationState" !in tables || "BackupManifest" in tables || "FocusSessions" in tables) return null
        val inspection = ContainerBackupRouter.inspect(db)
        val kind = inspection.kind as? DatasetKind.ExperimentalContainer ?: return null
        val hash = RawLegacyInventoryReader.read(db).snapshotSha256
        return ContainerBackupManifest(formatVersion = 1, datasetUuid = kind.datasetUuid, createdAt = 0,
            foundationVersion = kind.foundationVersion, datasetMode = kind.mode, sourceSnapshotSha256 = hash,
            canonicalChecksum = ContainerBackupManifest.computeCanonicalChecksum(kind.datasetUuid, hash, 0, 1, kind.foundationVersion))
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
