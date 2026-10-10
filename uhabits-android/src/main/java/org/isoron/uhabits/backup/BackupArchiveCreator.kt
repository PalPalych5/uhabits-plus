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
import android.os.Build
import android.util.Log
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ArchiveCreationResult(
    val archiveFile: File,
    val sizeBytes: Long,
    val databaseVersion: Int,
    val manifest: BackupArchiveManifest
)

object BackupArchiveCreator {

    var failureInjection: CreationFailureInjection = CreationFailureInjection.NONE

    enum class CreationFailureInjection {
        NONE,
        DURING_SNAPSHOT,
        DURING_VALIDATION_PRE_ZIP,
        DURING_PREFERENCES_EXPORT,
        DURING_ZIP_WRITE,
        DURING_POST_ZIP_VALIDATION,
        DURING_PUBLICATION
    }

    fun createArchive(context: Context, finalZipFile: File): ArchiveCreationResult {
        val parent = finalZipFile.parentFile ?: context.cacheDir
        parent.mkdirs()

        val tempSnapshotDb = File.createTempFile("snapshot-", ".db.tmp", parent)
        val tempZipFile = File.createTempFile("backup-", ".zip.tmp", parent)

        try {
            // Step 1: Create consistent SQLite snapshot
            val liveDb = DatabaseUtils.openDatabase()
            DatabaseUtils.checkpointDatabase(liveDb)

            if (failureInjection == CreationFailureInjection.DURING_SNAPSHOT) {
                throw IOException("Injected failure during snapshot creation")
            }

            val app = context.applicationContext as? HabitsApplication
            val session = app?.component?.session
            val isExperimental = session?.isExperimental == true

            if (isExperimental) {
                val target = SQLiteDatabase.openOrCreateDatabase(tempSnapshotDb, null)
                try {
                    val preferences = context.getSharedPreferences("uhabits_prefs_${session!!.preferencesNamespace}", Context.MODE_PRIVATE)
                        .all.mapValues { it.value.toString() }
                    val result = org.isoron.uhabits.core.containers.backup.ContainerBackupService.createBackup(
                        AndroidDatabase(liveDb),
                        AndroidDatabase(target),
                        System.currentTimeMillis(),
                        preferences
                    )
                    if (result is org.isoron.uhabits.core.containers.backup.BackupCreationResult.Failure) {
                        throw IOException(result.error, result.cause)
                    }
                } finally {
                    target.close()
                }
            } else {
                var cloned = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !DatabaseUtils.forceSqliteCloneForTesting) {
                    try {
                        liveDb.execSQL("VACUUM INTO '${DatabaseUtils.escapeSqlString(tempSnapshotDb.absolutePath)}'")
                        cloned = true
                    } catch (e: Exception) {
                        Log.w("BackupArchiveCreator", "VACUUM INTO failed, falling back to SQLite clone", e)
                        if (tempSnapshotDb.exists()) tempSnapshotDb.delete()
                    }
                }
                if (!cloned) {
                    DatabaseUtils.createSnapshotViaSqliteClone(liveDb, tempSnapshotDb)
                }
            }

            // Live database is now released; all remaining operations run on immutable snapshot
            if (failureInjection == CreationFailureInjection.DURING_VALIDATION_PRE_ZIP) {
                throw IOException("Injected failure during pre-zip validation")
            }

            // Step 2 & 3: Validate SQLite integrity and schema on snapshot
            val snapshotDb = SQLiteDatabase.openDatabase(tempSnapshotDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val dbVersion: Int
            val tableInventory: List<TableInventoryEntry>
            try {
                dbVersion = snapshotDb.version
                val quickCheck = snapshotDb.rawQuery("PRAGMA quick_check", null).use {
                    if (it.moveToFirst()) it.getString(0) else "failed"
                }
                if (quickCheck != "ok") {
                    throw IllegalStateException("Snapshot failed quick_check: $quickCheck")
                }

                val fkCheckCount = snapshotDb.rawQuery("PRAGMA foreign_key_check", null).use { it.count }
                if (fkCheckCount > 0) {
                    throw IllegalStateException("Snapshot failed foreign_key_check: $fkCheckCount violations")
                }

                // Step 4: Collect inventory & row counts from snapshot
                val inventory = mutableListOf<TableInventoryEntry>()
                snapshotDb.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val tableName = cursor.getString(0)
                        val count = snapshotDb.rawQuery("SELECT COUNT(*) FROM \"$tableName\"", null).use { countCursor ->
                            if (countCursor.moveToFirst()) countCursor.getLong(0) else 0L
                        }
                        inventory.add(TableInventoryEntry(tableName, count))
                    }
                }

                val requiredTables = buildList {
                    add("Habits")
                    add("Repetitions")
                    if (dbVersion >= 26) add("HabitExtensions")
                    if (dbVersion >= 27) add("HabitBlocks")
                    if (dbVersion >= 28) {
                        add("HabitGoals")
                        add("AppSettings")
                    }
                    if (dbVersion >= 29) {
                        add("SyncQueue")
                        add("EntryOps")
                    }
                    if (dbVersion >= 30) {
                        addAll(DatabaseUtils.NEXT_TABLE_NAMES)
                    }
                }
                val presentTableNames = inventory.map { it.tableName }.toSet()
                if (!presentTableNames.containsAll(requiredTables)) {
                    throw IllegalStateException("Snapshot missing required tables for schema version $dbVersion")
                }
                tableInventory = inventory
            } finally {
                snapshotDb.close()
            }

            // Step 4b: Compute SHA-256 of snapshot file
            val snapshotSha256 = computeFileSha256(tempSnapshotDb)
            val createdAt = System.currentTimeMillis()

            // Step 4c: Export allowlisted portable preferences
            if (failureInjection == CreationFailureInjection.DURING_PREFERENCES_EXPORT) {
                throw IOException("Injected failure during preferences export")
            }
            val sharedPrefs = if (isExperimental) {
                context.getSharedPreferences("uhabits_prefs_${session!!.preferencesNamespace}", Context.MODE_PRIVATE)
            } else {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            }
            val preferencesJsonStr = BackupPreferencesAdapter.exportPreferences(
                sharedPrefs = sharedPrefs,
                appId = context.packageName,
                createdAt = createdAt
            )
            val preferencesBytes = preferencesJsonStr.toByteArray(Charsets.UTF_8)
            val prefDigest = MessageDigest.getInstance("SHA-256")
            val preferencesSha256 = prefDigest.digest(preferencesBytes).joinToString("") { "%02x".format(it) }
            val preferencesValidation = BackupPreferencesAdapter.validatePreferencesJson(preferencesJsonStr)
            val preferencesCount = preferencesValidation.preferenceCount

            val canonicalContentSha256 = BackupArchiveManifest.computeCanonicalContentSha256(
                databaseVersion = dbVersion,
                databaseSha256 = snapshotSha256,
                createdAt = createdAt,
                tableInventory = tableInventory,
                preferencesSha256 = preferencesSha256
            )

            // Step 5: Construct manifest
            val manifest = BackupArchiveManifest(
                archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
                formatVersion = BackupArchiveFormat.CURRENT_FORMAT_VERSION,
                appId = context.packageName,
                appVersionCode = try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                } catch (_: Exception) { 0 },
                createdAt = createdAt,
                databaseVersion = dbVersion,
                databaseSha256 = snapshotSha256,
                databaseSizeBytes = tempSnapshotDb.length(),
                datasetMode = if (isExperimental) "EXPERIMENTAL" else "LEGACY_PRODUCTION",
                datasetId = if (isExperimental) session?.datasetId else "production",
                tableInventory = tableInventory,
                quickCheckResult = "ok",
                foreignKeyCheckResult = "ok",
                canonicalContentSha256 = canonicalContentSha256,
                minSupportedAppVersion = 1,
                maxSupportedSchemaVersion = 30,
                runtimeAuthorityMode = if (isExperimental) "EXPERIMENTAL" else "LEGACY_PRODUCTION",
                restorePolicy = "FAIL_CLOSED_ON_INCOMPATIBLE_AUTHORITY",
                preferencesSha256 = preferencesSha256,
                preferencesSizeBytes = preferencesBytes.size.toLong(),
                preferencesCount = preferencesCount
            )

            if (failureInjection == CreationFailureInjection.DURING_ZIP_WRITE) {
                throw IOException("Injected failure during ZIP write")
            }

            // Step 6: Write ZIP archive
            FileOutputStream(tempZipFile).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    ZipOutputStream(bos).use { zos ->
                        // Entry 1: manifest.json
                        val manifestBytes = manifest.toJson().toByteArray(Charsets.UTF_8)
                        val manifestEntry = ZipEntry(BackupArchiveFormat.MANIFEST_ENTRY_NAME)
                        manifestEntry.size = manifestBytes.size.toLong()
                        zos.putNextEntry(manifestEntry)
                        zos.write(manifestBytes)
                        zos.closeEntry()

                        // Entry 2: uhabits.db
                        val dbEntry = ZipEntry(BackupArchiveFormat.SQLITE_ENTRY_NAME)
                        dbEntry.size = tempSnapshotDb.length()
                        zos.putNextEntry(dbEntry)
                        FileInputStream(tempSnapshotDb).use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()

                        // Entry 3: preferences.json
                        val prefEntry = ZipEntry(BackupArchiveFormat.PREFERENCES_ENTRY_NAME)
                        prefEntry.size = preferencesBytes.size.toLong()
                        zos.putNextEntry(prefEntry)
                        zos.write(preferencesBytes)
                        zos.closeEntry()
                    }
                }
            }

            if (failureInjection == CreationFailureInjection.DURING_POST_ZIP_VALIDATION) {
                throw IOException("Injected failure during post-zip validation")
            }

            // Step 7: Validate complete ZIP and embedded database
            val targetVersion = if (isExperimental) org.isoron.uhabits.core.DATABASE_VERSION else LEGACY_DATABASE_VERSION
            val validation = BackupArchiveValidator.validate(tempZipFile, targetVersion)
            if (!validation.isValid) {
                throw IllegalStateException("Generated backup archive failed validation: ${validation.errorMessage}")
            }

            if (failureInjection == CreationFailureInjection.DURING_PUBLICATION) {
                throw IOException("Injected failure during publication")
            }

            // Step 8: Publish completed archive atomically
            if (finalZipFile.exists()) finalZipFile.delete()
            Files.move(
                tempZipFile.toPath(),
                finalZipFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )

            return ArchiveCreationResult(
                archiveFile = finalZipFile,
                sizeBytes = finalZipFile.length(),
                databaseVersion = dbVersion,
                manifest = manifest
            )
        } finally {
            // Step 9: Clean temporary files without touching real recovery candidates
            if (tempSnapshotDb.exists()) tempSnapshotDb.delete()
            if (tempZipFile.exists()) tempZipFile.delete()
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
