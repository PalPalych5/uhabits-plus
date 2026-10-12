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
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.uhabits.HabitsApplication.Companion.isTestMode
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.backup.RestoreJournal
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.production.cutover.ProductionCutoverGate
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult
import org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator
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

    @Volatile
    var enableWalForNext: Boolean = true

    var restoreFailureInjection: RestoreFailureInjection = RestoreFailureInjection.NONE

    enum class RestoreFailureInjection {
        NONE,
        BEFORE_STAGING,
        AFTER_JOURNAL_PREPARING,
        AFTER_MOVE_ORIGINAL,
        AFTER_INSTALL_REPLACEMENT,
        BEFORE_PREFERENCES_APPLY,
        AFTER_PREFERENCES_APPLY,
        BEFORE_CLEANUP
    }

    val LEGACY_TABLE_NAMES = listOf(
        "Habits",
        "Repetitions",
        "HabitExtensions",
        "HabitBlocks",
        "HabitGoals",
        "AppSettings",
        "SyncQueue",
        "EntryOps"
    )

    val NEXT_TABLE_NAMES = listOf(
        "OrganizationState",
        "OrganizationChanges",
        "Containers",
        "ContainerHistory",
        "HabitPlacements",
        "HabitPlacementHistory",
        "LegacyBlockMap",
        "HabitVisualIdentities",
        "UserTasks",
        "FocusSessions",
        "Contributions",
        "SourceRules"
    )

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
        HabitsStartupTrace.mark("database.configure.begin")
        val resolvedName = filename ?: defaultDatabaseFilename
        currentDatabaseFilename = resolvedName
        if (context != null) {
            reconcileInterruptedRestore(context, resolvedName)
        }
        val targetVersion = if (ProductionCutoverGate.isCutoverAuthorized(resolvedName)) {
            DATABASE_VERSION
        } else {
            LEGACY_DATABASE_VERSION
        }
        val isWalRequested = enableWalForNext && (targetVersion >= 30 || ProductionCutoverGate.isCutoverAuthorized(resolvedName))
        opener = HabitsDatabaseOpener(
            context!!,
            resolvedName,
            targetVersion,
            isWalRequested = isWalRequested
        )
        HabitsStartupTrace.mark("database.configure.end")
    }

    fun closeDatabase() {
        opener?.close()
        opener = null
        currentDatabaseFilename = null
    }

    fun reconcileInterruptedRestore(context: Context, filename: String? = null) {
        val resolvedName = filename ?: defaultDatabaseFilename
        val dbFile = getDatabaseFile(context, resolvedName)
        val parent = dbFile.parentFile ?: return
        val rollbackFile = File(parent, "${dbFile.name}.rollback")
        val prefsRollbackFile = File(parent, "${dbFile.name}.prefs.rollback")
        val restoreTemp = File(parent, "${dbFile.name}.restore.tmp")
        val prefsRestoreTemp = File(parent, "${dbFile.name}.prefs.restore.tmp")
        val journal = RestoreJournal(parent, resolvedName)
        val state = journal.readState()
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        cleanupSidecars(restoreTemp)
        if (restoreTemp.exists()) restoreTemp.delete()
        if (prefsRestoreTemp.exists()) prefsRestoreTemp.delete()

        // 1. Missing or empty primary database check with existing recovery artifacts
        if (!dbFile.exists() || dbFile.length() == 0L) {
            if (rollbackFile.exists()) {
                cleanupSidecars(dbFile)
                Files.move(
                    rollbackFile.toPath(),
                    dbFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
                if (prefsRollbackFile.exists()) {
                    runCatching {
                        org.isoron.uhabits.backup.BackupPreferencesAdapter.importPreferences(sharedPrefs, prefsRollbackFile.readText())
                    }
                    prefsRollbackFile.delete()
                }
                sharedPrefs.edit()
                    .putBoolean("pref_sync_review_required", true)
                    .putString("pref_sync_review_reason", "Database restored from recovery rollback")
                    .commit()
                journal.clear()
                Log.i("DatabaseUtils", "Reconciled interrupted restore: restored $resolvedName from rollback candidate")
                return
            }

            // Check if any archived recovery candidate exists: ${dbFile.name}.recovery.*
            val recoveryFiles = parent.listFiles { _, name -> name.startsWith("${dbFile.name}.recovery.") }
                ?.filter { it.length() > 0L }
                ?.sortedByDescending { it.lastModified() }
            if (!recoveryFiles.isNullOrEmpty()) {
                val bestCandidate = recoveryFiles.firstOrNull { validateDatabaseBackup(it).isValid }
                if (bestCandidate != null) {
                    cleanupSidecars(dbFile)
                    Files.copy(
                        bestCandidate.toPath(),
                        dbFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    sharedPrefs.edit()
                        .putBoolean("pref_sync_review_required", true)
                        .putString("pref_sync_review_reason", "Database restored from archived recovery artifact")
                        .commit()
                    journal.clear()
                    Log.i("DatabaseUtils", "Recovered missing primary database from ${bestCandidate.name}")
                    return
                }
            }

            journal.clear()
            return
        }

        if (!rollbackFile.exists()) {
            if (prefsRollbackFile.exists()) prefsRollbackFile.delete()
            journal.clear()
            return
        }

        // Both dbFile and rollbackFile exist.
        if (state?.state == RestoreJournal.State.APPLIED) {
            val validation = validateDatabaseBackup(dbFile)
            if (validation.isValid) {
                rollbackFile.delete()
                if (prefsRollbackFile.exists()) prefsRollbackFile.delete()
                sharedPrefs.edit()
                    .putBoolean("pref_sync_review_required", true)
                    .putString("pref_sync_review_reason", "Database restored from backup")
                    .commit()
                journal.clear()
                Log.i("DatabaseUtils", "Reconciled interrupted restore: APPLIED state confirmed, cleared rollback")
                return
            }
        }

        val rollbackValidation = validateDatabaseBackup(rollbackFile)
        val dbValidation = validateDatabaseBackup(dbFile)

        if (!dbValidation.isValid && rollbackValidation.isValid) {
            cleanupSidecars(dbFile)
            Files.move(
                rollbackFile.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            if (prefsRollbackFile.exists()) {
                runCatching {
                    org.isoron.uhabits.backup.BackupPreferencesAdapter.importPreferences(sharedPrefs, prefsRollbackFile.readText())
                }
                prefsRollbackFile.delete()
            }
            sharedPrefs.edit()
                .putBoolean("pref_sync_review_required", true)
                .putString("pref_sync_review_reason", "Database restored from valid rollback candidate")
                .commit()
            journal.clear()
            Log.i("DatabaseUtils", "Reconciled interrupted restore: recovered valid rollback file to $resolvedName")
            return
        }

        // Both are valid or state is ambiguous. Never destructively overwrite an existing recovery candidate!
        val timestamp = System.currentTimeMillis()
        val archiveRollback = File(parent, "${dbFile.name}.recovery.$timestamp")
        Files.move(
            rollbackFile.toPath(),
            archiveRollback.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
        if (prefsRollbackFile.exists()) {
            val archivePrefs = File(parent, "${dbFile.name}.prefs.recovery.$timestamp")
            Files.move(
                prefsRollbackFile.toPath(),
                archivePrefs.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }
        sharedPrefs.edit()
            .putBoolean("pref_sync_review_required", true)
            .putString("pref_sync_review_reason", "Interrupted restore reconciled; recovery candidate preserved")
            .commit()
        journal.clear()
        Log.w("DatabaseUtils", "Preserved ambiguous rollback file as ${archiveRollback.name}")
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

        // On Android 10+ (API 29+, bundled SQLite >= 3.28.0), VACUUM INTO is natively supported.
        // On Android 9 (API 28, bundled SQLite 3.22.0) or if VACUUM INTO is unsupported,
        // create atomic snapshot via transactional SQLite schema and table cloning without raw-file copying.
        var snapshotSucceeded = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !forceSqliteCloneForTesting) {
            try {
                db.execSQL("VACUUM INTO '${escapeSqlString(tempFile.absolutePath)}'")
                snapshotSucceeded = true
            } catch (e: Exception) {
                Log.w("DatabaseUtils", "VACUUM INTO failed on API >= 29, falling back to SQLite clone", e)
                if (tempFile.exists()) tempFile.delete()
            }
        }

        if (!snapshotSucceeded) {
            try {
                createSnapshotViaSqliteClone(db, tempFile)
                snapshotSucceeded = true
            } catch (e: Exception) {
                Log.e("DatabaseUtils", "SQLite snapshot clone failed", e)
                tempFile.delete()
                throw IOException("Database snapshot failed: ${e.message}", e)
            }
        }

        if (snapshotFailureInjection == SnapshotFailureInjection.DURING_VALIDATION) {
            tempFile.delete()
            throw IOException("Injected failure during validation")
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
        if (snapshotFailureInjection == SnapshotFailureInjection.DURING_PUBLICATION) {
            tempFile.delete()
            throw IOException("Injected failure during publication")
        }
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

    fun validateDatabaseBackup(file: File, targetVersion: Int = LEGACY_DATABASE_VERSION): org.isoron.uhabits.backup.BackupValidationResult {
        return org.isoron.uhabits.backup.BackupArchiveValidator.validate(file, targetVersion)
    }

    fun validateRawSqliteBackup(file: File): org.isoron.uhabits.backup.BackupValidationResult {
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
                if (version >= 30) {
                    addAll(NEXT_TABLE_NAMES)
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
                if ("OrganizationState" in presentTables) {
                    val (orgMode, foundationVersion, runtimeAuthority) = db.rawQuery(
                        "SELECT mode, foundation_version, runtime_authority FROM OrganizationState WHERE id = 1", null
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            Triple(cursor.getString(0), cursor.getInt(1), cursor.getString(2))
                        } else {
                            Triple(null, -1, null)
                        }
                    }

                    if (orgMode == null) {
                        return org.isoron.uhabits.backup.BackupValidationResult(
                            isValid = false,
                            errorMessage = "OrganizationState table exists but row with id=1 is missing"
                        )
                    }

                    if (orgMode in listOf("PRODUCTION_NEXT", "STAGED")) {
                        if (foundationVersion <= 0 || foundationVersion > org.isoron.uhabits.core.containers.backup.ContainerBackupManifest.CURRENT_FOUNDATION_VERSION) {
                            return org.isoron.uhabits.backup.BackupValidationResult(
                                isValid = false,
                                errorMessage = "Unsupported foundation_version $foundationVersion in OrganizationState"
                            )
                        }
                        if (runtimeAuthority !in listOf("LEGACY", "NEXT")) {
                            return org.isoron.uhabits.backup.BackupValidationResult(
                                isValid = false,
                                errorMessage = "Invalid runtime_authority '$runtimeAuthority' in OrganizationState"
                            )
                        }
                        val fkCheckCount = db.rawQuery("PRAGMA foreign_key_check", null).use { it.count }
                        if (fkCheckCount > 0) {
                            return org.isoron.uhabits.backup.BackupValidationResult(
                                isValid = false,
                                errorMessage = "Database failed foreign_key_check ($fkCheckCount violations)"
                            )
                        }
                    } else {
                        val stage = SQLiteDatabase.create(null)
                        val target = SQLiteDatabase.create(null)
                        try {
                            val wrapped = AndroidDatabase(db)
                            val result = ContainerRestoreService.restore(
                                wrapped, AndroidDatabase(stage), AndroidDatabase(target),
                                externalManifest = compatibleOldExperimentalManifest(wrapped)
                            )
                            if (result is org.isoron.uhabits.core.containers.backup.RestoreResult.Failure) {
                                val detailedMsg = if (result.errors.isNotEmpty()) "${result.reason}: ${result.errors.joinToString("; ")}" else result.reason
                                return org.isoron.uhabits.backup.BackupValidationResult(false, errorMessage = detailedMsg)
                            }
                        } finally { stage.close(); target.close() }
                    }
                } else if (NEXT_TABLE_NAMES.any(presentTables::contains)) {
                    val stage = SQLiteDatabase.create(null)
                    val target = SQLiteDatabase.create(null)
                    try {
                        val wrapped = AndroidDatabase(db)
                        val result = ContainerRestoreService.restore(
                            wrapped, AndroidDatabase(stage), AndroidDatabase(target),
                            externalManifest = compatibleOldExperimentalManifest(wrapped)
                        )
                        if (result is org.isoron.uhabits.core.containers.backup.RestoreResult.Failure) {
                            val detailedMsg = if (result.errors.isNotEmpty()) "${result.reason}: ${result.errors.joinToString("; ")}" else result.reason
                            return org.isoron.uhabits.backup.BackupValidationResult(false, errorMessage = detailedMsg)
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
        val dbFile = getDatabaseFile(context)
        val activeFilename = dbFile.name
        val app = context.applicationContext as? HabitsApplication
        val session = app?.component?.session
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Phase 1: Validate the entire archive before modifying active state
        val isCutoverAuthorized = ProductionCutoverGate.isCutoverAuthorized(activeFilename)
        val targetVersion = if (session?.isNextArchitecture == true || isCutoverAuthorized) {
            DATABASE_VERSION
        } else {
            LEGACY_DATABASE_VERSION
        }

        val validation = validateDatabaseBackup(stagedBackupFile, targetVersion)
        if (validation.compatibilityStatus == org.isoron.uhabits.backup.BackupCompatibilityStatus.REQUIRES_NEXT_AUTHORITY) {
            throw org.isoron.uhabits.backup.BackupRestoreException.IncompatibleAuthorityException(
                validation.errorMessage ?: "Cannot restore Next backup into legacy production dataset"
            )
        }
        require(validation.isValid) { validation.errorMessage ?: "Invalid backup" }
        val backupVersion = requireNotNull(validation.databaseVersion) { "Missing database version in backup" }

        // Phase 2: Determine compatible schema and runtime authority
        if (backupVersion > targetVersion) {
            throw org.isoron.uhabits.backup.BackupRestoreException.IncompatibleAuthorityException(
                "Cannot restore backup with schema version $backupVersion into a dataset targeting version $targetVersion"
            )
        }

        val parent = requireNotNull(dbFile.parentFile)

        // Phase 3: Verify sufficient disk space and accessible restore destination
        val requiredBytes = (validation.manifest?.databaseSizeBytes ?: stagedBackupFile.length()) * 3 + 10 * 1024 * 1024L
        if (parent.usableSpace in 1 until requiredBytes) {
            throw org.isoron.uhabits.backup.BackupRestoreException.InsufficientStorageException(
                "Insufficient storage space for restore: required $requiredBytes bytes, available ${parent.usableSpace} bytes"
            )
        }

        if (restoreFailureInjection == RestoreFailureInjection.BEFORE_STAGING) {
            throw IOException("Injected failure before restore staging")
        }

        // Phase 4 & 5: Staging and recovery candidate preparation
        val isZip = validation.format == org.isoron.uhabits.backup.BackupFormat.VERSIONED_ZIP
        val tempExtractedPrefs = if (isZip && validation.manifest?.preferencesSha256 != null) {
            File.createTempFile("restore-extract-prefs-", ".json", parent)
        } else null

        val dbCandidate = if (isZip) {
            val tempExtracted = File.createTempFile("restore-extract-", ".db", parent)
            try {
                org.isoron.uhabits.backup.BackupArchiveExtractor.extractArchive(
                    stagedBackupFile,
                    tempExtracted,
                    tempExtractedPrefs
                )
                tempExtracted
            } catch (t: Throwable) {
                tempExtracted.delete()
                tempExtractedPrefs?.delete()
                throw t
            }
        } else {
            stagedBackupFile
        }

        try {
            val backup = SQLiteDatabase.openDatabase(dbCandidate.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val hasNextEntities = try {
                val tables = DatabaseSnapshotter.listTables(AndroidDatabase(backup))
                NEXT_TABLE_NAMES.any(tables::contains)
            } finally { backup.close() }

            require(session?.isNextArchitecture == true || isCutoverAuthorized || (!hasNextEntities && backupVersion <= LEGACY_DATABASE_VERSION)) {
                "Experimental or UHabit Next backup cannot replace a legacy production dataset"
            }

            val restoreTemp = File(parent, "${dbFile.name}.restore.tmp")
            val rollbackFile = File(parent, "${dbFile.name}.rollback")
            val prefsRollbackFile = File(parent, "${dbFile.name}.prefs.rollback")

            // Reconcile any previous interrupted restore before starting a new one
            reconcileInterruptedRestore(context, activeFilename)

            cleanupSidecars(restoreTemp)
            if (restoreTemp.exists()) restoreTemp.delete()

            // Phase 6: Persist the restore transaction state before file replacement
            val journal = RestoreJournal(parent, activeFilename)
            journal.recordState(RestoreJournal.State.PREPARING, targetVersion, stagedBackupFile.absolutePath)

            if (restoreFailureInjection == RestoreFailureInjection.AFTER_JOURNAL_PREPARING) {
                throw IOException("Injected failure after journal PREPARING")
            }

            if (session?.isExperimental == true) {
                val source = SQLiteDatabase.openDatabase(dbCandidate.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val stage = SQLiteDatabase.create(null)
                val target = SQLiteDatabase.openOrCreateDatabase(restoreTemp, null)
                try {
                    val wrapped = AndroidDatabase(source)
                    val result = ContainerRestoreService.restore(
                        wrapped, AndroidDatabase(stage), AndroidDatabase(target),
                        externalManifest = compatibleOldExperimentalManifest(wrapped)
                    )
                    require(result is org.isoron.uhabits.core.containers.backup.RestoreResult.Success) {
                        (result as? org.isoron.uhabits.core.containers.backup.RestoreResult.Failure)?.reason ?: "Invalid experimental backup"
                    }
                    require((result.kind as? DatasetKind.ExperimentalContainer)?.datasetUuid == session.datasetId) {
                        "Restore requires the same experimental dataset identity"
                    }
                } catch (error: Throwable) {
                    restoreTemp.delete()
                    throw error
                } finally {
                    source.close(); stage.close(); target.close()
                }
            } else {
                dbCandidate.copyTo(restoreTemp, overwrite = true)
            }

            if (backupVersion < targetVersion) {
                val migrated = SQLiteDatabase.openDatabase(
                    restoreTemp.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE
                )
                try {
                    val wrappedDb = AndroidDatabase(migrated)
                    val intermediateTarget = if (targetVersion >= 30 && backupVersion < 29) 29 else targetVersion
                    if (wrappedDb.getVersion() < intermediateTarget) {
                        runBlocking {
                            wrappedDb.migrateTo(intermediateTarget) { version ->
                                val filename = "%02d.sql".format(version)
                                context.assets.open("migrations/$filename").bufferedReader().readText()
                            }
                        }
                    }
                    if (targetVersion >= 30 && wrappedDb.getVersion() == 29) {
                        val result = ProductionNextSchemaMigrator.migrate(
                            db = wrappedDb,
                            cutoverTimestamp = System.currentTimeMillis(),
                            ownsTransaction = false,
                            runtimeAuthority = if (session?.isNextArchitecture == true || isCutoverAuthorized) "NEXT" else "LEGACY",
                            authorityEpoch = if (session?.isNextArchitecture == true || isCutoverAuthorized) 1L else 0L
                        )
                        if (result is ProductionNextMigrationResult.Failure) {
                            throw IllegalStateException("Version 30 schema migration failed: ${result.reason}", result.cause)
                        }
                    }
                } finally {
                    migrated.close()
                }
            }

            val migratedValidation = validateDatabaseBackup(restoreTemp, targetVersion)
            require(migratedValidation.isValid && migratedValidation.databaseVersion == targetVersion) {
                migratedValidation.errorMessage ?: "Migrated backup is invalid"
            }

            if (session?.isExperimental == true) requireNotNull(app).shutdownForDatabaseRestoreRestart()
            closeDatabase()
            cleanupSidecars(dbFile)

            // Save portable preferences rollback candidate before modifying anything
            if (tempExtractedPrefs != null && tempExtractedPrefs.exists()) {
                val currentPortableExport = org.isoron.uhabits.backup.BackupPreferencesAdapter.exportPreferences(
                    sharedPrefs = sharedPrefs,
                    appId = context.packageName
                )
                prefsRollbackFile.writeText(currentPortableExport)
            }

            journal.recordState(RestoreJournal.State.SWAPPING, targetVersion, stagedBackupFile.absolutePath)

            try {
                if (dbFile.exists()) {
                    Files.move(
                        dbFile.toPath(),
                        rollbackFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                }

                if (restoreFailureInjection == RestoreFailureInjection.AFTER_MOVE_ORIGINAL) {
                    throw IOException("Injected failure after move original database")
                }

                Files.move(
                    restoreTemp.toPath(),
                    dbFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )

                if (restoreFailureInjection == RestoreFailureInjection.AFTER_INSTALL_REPLACEMENT) {
                    throw IOException("Injected failure after installing replacement database")
                }

                if (restoreFailureInjection == RestoreFailureInjection.BEFORE_PREFERENCES_APPLY) {
                    throw IOException("Injected failure before preferences apply")
                }

                // Phase 7: Safely apply portable preferences if present
                if (tempExtractedPrefs != null && tempExtractedPrefs.exists()) {
                    org.isoron.uhabits.backup.BackupPreferencesAdapter.importPreferences(
                        sharedPrefs = sharedPrefs,
                        jsonStr = tempExtractedPrefs.readText()
                    )
                }

                if (restoreFailureInjection == RestoreFailureInjection.AFTER_PREFERENCES_APPLY) {
                    throw IOException("Injected failure after preferences apply")
                }

                journal.recordState(RestoreJournal.State.APPLIED, targetVersion, stagedBackupFile.absolutePath)

                // Phase 8: Validate the resulting database and restored settings
                initializeDatabase(context, activeFilename)
                openDatabase().close()

                // Phase 9: Set or preserve required sync-quarantine gate unconditionally upon restore
                sharedPrefs.edit()
                    .putBoolean("pref_sync_review_required", true)
                    .putString("pref_sync_review_reason", "Database restored from backup")
                    .commit()

                if (session?.isExperimental == true) {
                    context.getSharedPreferences(session.preferencesNamespace + "_timer", Context.MODE_PRIVATE).edit().clear().commit()
                }

                if (restoreFailureInjection == RestoreFailureInjection.BEFORE_CLEANUP) {
                    throw IOException("Injected failure before cleanup")
                }

                // Phase 10: Finalize the transaction and retain recovery data
                rollbackFile.delete()
                prefsRollbackFile.delete()
                journal.clear()
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
                if (prefsRollbackFile.exists()) {
                    runCatching {
                        org.isoron.uhabits.backup.BackupPreferencesAdapter.importPreferences(
                            sharedPrefs = sharedPrefs,
                            jsonStr = prefsRollbackFile.readText()
                        )
                    }
                    prefsRollbackFile.delete()
                }
                sharedPrefs.edit()
                    .putBoolean("pref_sync_review_required", true)
                    .putString("pref_sync_review_reason", "Restore failed and rolled back")
                    .commit()
                initializeDatabase(context, activeFilename)
                journal.clear()
                if (session?.isExperimental == true) throw ExperimentalRestoreRestartRequiredException(e)
                throw e
            } finally {
                cleanupSidecars(restoreTemp)
                restoreTemp.delete()
                tempExtractedPrefs?.delete()
            }
        } finally {
            if (isZip && dbCandidate != stagedBackupFile) {
                dbCandidate.delete()
            }
            tempExtractedPrefs?.delete()
        }
    }

    fun openDatabase(): SQLiteDatabase {
        HabitsStartupTrace.mark("database.open.begin")
        checkNotNull(opener)
        return opener!!.writableDatabase.also { HabitsStartupTrace.mark("database.open.end") }
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

    enum class SnapshotFailureInjection {
        NONE,
        DURING_SCHEMA_CREATION,
        DURING_ROW_COPYING,
        DURING_INDEX_CREATION,
        DURING_COMMIT,
        DURING_DETACH,
        DURING_VALIDATION,
        DURING_PUBLICATION
    }

    @Volatile
    var snapshotFailureInjection: SnapshotFailureInjection = SnapshotFailureInjection.NONE

    @Volatile
    var forceSqliteCloneForTesting: Boolean = false

    internal fun createSnapshotViaSqliteClone(sourceDb: SQLiteDatabase, tempFile: File) {
        // 1. Audit schema objects in source database (fail closed on virtual tables or unknown objects)
        sourceDb.rawQuery(
            "SELECT type, name, sql FROM main.sqlite_master WHERE name NOT LIKE 'sqlite_%'",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val name = cursor.getString(1)
                val sql = cursor.getString(2)
                if (type !in listOf("table", "index", "view", "trigger")) {
                    throw IllegalStateException("Unsupported SQLite schema object type: $type for $name")
                }
                if (sql != null && sql.contains(Regex("""(?i)\bCREATE\s+VIRTUAL\s+TABLE\b"""))) {
                    throw IllegalStateException("Virtual tables are unsupported for snapshot clone: $name")
                }
            }
        }

        val escapedPath = escapeSqlString(tempFile.absolutePath)
        var attached = false
        try {
            sourceDb.execSQL("ATTACH DATABASE '$escapedPath' AS snapshot")
            attached = true

            // Read metadata from source
            val sourceVersion = sourceDb.version
            val appId = sourceDb.rawQuery("PRAGMA main.application_id", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }

            sourceDb.beginTransactionNonExclusive()
            try {
                sourceDb.execSQL("PRAGMA snapshot.user_version = $sourceVersion")
                sourceDb.execSQL("PRAGMA snapshot.application_id = $appId")
                sourceDb.execSQL("PRAGMA defer_foreign_keys = ON")

                if (snapshotFailureInjection == SnapshotFailureInjection.DURING_SCHEMA_CREATION) {
                    throw IOException("Injected failure during schema creation")
                }

                // 2. Recreate tables in snapshot
                val tableNames = mutableListOf<String>()
                sourceDb.rawQuery(
                    "SELECT name, sql FROM main.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        val sql = cursor.getString(1)
                        val snapshotSql = sql.replaceFirst(
                            Regex("""(?i)\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:["`\[]?${Regex.escape(name)}["`\]]?)\s*(\(|AS\b)"""),
                            "CREATE TABLE snapshot.`$name` $1"
                        )
                        sourceDb.execSQL(snapshotSql)
                        tableNames.add(name)
                    }
                }

                if (snapshotFailureInjection == SnapshotFailureInjection.DURING_ROW_COPYING) {
                    throw IOException("Injected failure during row copying")
                }

                // 3. Copy table data
                for (table in tableNames) {
                    sourceDb.execSQL("INSERT INTO snapshot.`$table` SELECT * FROM main.`$table`")
                }

                // 4. Preserve sqlite_sequence / AUTOINCREMENT counters
                val hasSequence = sourceDb.rawQuery(
                    "SELECT 1 FROM main.sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'",
                    null
                ).use { it.moveToFirst() }
                if (hasSequence) {
                    val snapshotHasSequence = sourceDb.rawQuery(
                        "SELECT 1 FROM snapshot.sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'",
                        null
                    ).use { it.moveToFirst() }
                    if (snapshotHasSequence) {
                        sourceDb.execSQL("DELETE FROM snapshot.sqlite_sequence")
                        sourceDb.execSQL("INSERT INTO snapshot.sqlite_sequence(name, seq) SELECT name, seq FROM main.sqlite_sequence")
                    }
                }

                if (snapshotFailureInjection == SnapshotFailureInjection.DURING_INDEX_CREATION) {
                    throw IOException("Injected failure during index creation")
                }

                // 5. Recreate explicit indices in snapshot
                sourceDb.rawQuery(
                    "SELECT name, sql FROM main.sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        val sql = cursor.getString(1)
                        val isUnique = sql.trim().uppercase().startsWith("CREATE UNIQUE")
                        val prefix = if (isUnique) "CREATE UNIQUE INDEX" else "CREATE INDEX"
                        val snapshotSql = sql.replaceFirst(
                            Regex("""(?i)\bCREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:["`\[]?${Regex.escape(name)}["`\]]?)\s+(ON\b)"""),
                            "$prefix snapshot.`$name` $1"
                        )
                        sourceDb.execSQL(snapshotSql)
                    }
                }

                // 6. Recreate views in snapshot
                sourceDb.rawQuery(
                    "SELECT name, sql FROM main.sqlite_master WHERE type = 'view' AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        val sql = cursor.getString(1)
                        val snapshotSql = sql.replaceFirst(
                            Regex("""(?i)\bCREATE\s+VIEW\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:["`\[]?${Regex.escape(name)}["`\]]?)\s+(AS\b)"""),
                            "CREATE VIEW snapshot.`$name` $1"
                        )
                        sourceDb.execSQL(snapshotSql)
                    }
                }

                // 7. Recreate triggers in snapshot
                sourceDb.rawQuery(
                    "SELECT name, sql FROM main.sqlite_master WHERE type = 'trigger' AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        val sql = cursor.getString(1)
                        val snapshotSql = sql.replaceFirst(
                            Regex("""(?i)\bCREATE\s+TRIGGER\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:["`\[]?${Regex.escape(name)}["`\]]?)\s+"""),
                            "CREATE TRIGGER snapshot.`$name` "
                        )
                        sourceDb.execSQL(snapshotSql)
                    }
                }

                // 8. Integrity check on snapshot before commit
                sourceDb.rawQuery("PRAGMA snapshot.integrity_check", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                        val msg = if (cursor.count > 0) cursor.getString(0) else "integrity_check failed"
                        throw IllegalStateException("Integrity check failed on snapshot: $msg")
                    }
                }

                // 9. Foreign key check on snapshot before commit
                sourceDb.rawQuery("PRAGMA snapshot.foreign_key_check", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val table = cursor.getString(0)
                        val rowid = cursor.getLong(1)
                        val target = cursor.getString(2)
                        throw IllegalStateException("Foreign key violation in snapshot: table $table row $rowid -> $target")
                    }
                }

                if (snapshotFailureInjection == SnapshotFailureInjection.DURING_COMMIT) {
                    throw IOException("Injected failure during commit")
                }

                sourceDb.setTransactionSuccessful()
            } finally {
                sourceDb.endTransaction()
            }

            if (snapshotFailureInjection == SnapshotFailureInjection.DURING_DETACH) {
                throw IOException("Injected failure during detach")
            }
        } finally {
            if (attached) {
                try {
                    sourceDb.execSQL("DETACH DATABASE snapshot")
                } catch (e: Exception) {
                    Log.w("DatabaseUtils", "DETACH DATABASE snapshot failed", e)
                    if (snapshotFailureInjection != SnapshotFailureInjection.DURING_DETACH) {
                        throw e
                    }
                }
            }
        }
    }

    data class CheckpointResult(
        val isBusy: Boolean,
        val logFrames: Int,
        val checkpointedFrames: Int
    )

    fun checkpointDatabase(db: SQLiteDatabase, mode: String = "FULL"): CheckpointResult {
        return try {
            db.rawQuery("PRAGMA wal_checkpoint($mode)", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    CheckpointResult(
                        isBusy = cursor.getInt(0) != 0,
                        logFrames = cursor.getInt(1),
                        checkpointedFrames = cursor.getInt(2)
                    )
                } else {
                    CheckpointResult(isBusy = false, logFrames = 0, checkpointedFrames = 0)
                }
            }
        } catch (e: Exception) {
            Log.w("DatabaseUtils", "PRAGMA wal_checkpoint($mode) failed", e)
            CheckpointResult(isBusy = true, logFrames = -1, checkpointedFrames = -1)
        }
    }

    internal fun escapeSqlString(value: String): String {
        return value.replace("'", "''")
    }

    internal fun cleanupSidecars(file: File) {
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    data class SnapshotInfo(
        val sizeBytes: Long,
        val databaseVersion: Int
    )
}
