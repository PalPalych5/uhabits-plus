package org.isoron.uhabits.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.inject.AppContext
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.File

@Inject
class BackupManager(
    @AppContext private val context: Context,
    private val catalog: BackupCatalog,
    private val statusStore: BackupStatusStore,
    private val safBackupStorage: SafBackupStorage
) {
    fun backupNow(keep: Int = 5): BackupResult {
        val now = System.currentTimeMillis()
        val tempDir = requireNotNull(catalog.getPrivateBackupDir()) { "Backup directory unavailable" }
        val tempFile = File(tempDir, "${BackupFileNamePolicy.buildFileName(now)}.tmp")

        return try {
            val snapshot = DatabaseUtils.createDatabaseSnapshot(context, tempFile)
            val finalFile = File(tempDir, BackupFileNamePolicy.buildFileName(now))
            DatabaseUtils.publishLocalBackup(tempFile, finalFile)

            catalog.applyPrivateRetention(keep)
            statusStore.recordSuccess(finalFile.absolutePath, snapshot.sizeBytes, snapshot.databaseVersion, now)
            BackupResult(finalFile.absolutePath, snapshot.sizeBytes, snapshot.databaseVersion)
        } catch (e: Exception) {
            tempFile.delete()
            statusStore.recordFailure(e.message ?: "Backup failed", now)
            throw e
        }
    }

    fun backupToPublicFolder(keep: Int = 5): BackupResult {
        val now = System.currentTimeMillis()
        val tempDir = requireNotNull(catalog.getPrivateBackupDir()) { "Backup directory unavailable" }
        val tempFile = File(tempDir, "${BackupFileNamePolicy.buildFileName(now)}.tmp")

        return try {
            val snapshot = DatabaseUtils.createDatabaseSnapshot(context, tempFile)
            val target = createPublicBackupFile(now)
            DatabaseUtils.copyFileToDocument(context, tempFile, target)
            tempFile.delete()

            catalog.applyPublicRetention(keep)
            statusStore.recordSuccess(target.uri.toString(), snapshot.sizeBytes, snapshot.databaseVersion, now)
            BackupResult(target.uri.toString(), snapshot.sizeBytes, snapshot.databaseVersion)
        } catch (e: Exception) {
            tempFile.delete()
            statusStore.recordFailure(e.message ?: "Backup failed", now)
            throw e
        }
    }

    fun listBackups(): List<BackupEntry> = catalog.listBackups()

    fun listLocalBackups(): List<BackupEntry> = catalog.listPrivateBackups()

    fun listPublicBackups(): List<BackupEntry> = catalog.listPublicBackups()

    fun isPublicBackupFolderConfigured(): Boolean = safBackupStorage.isFolderConfigured()

    fun isPublicBackupFolderAvailable(): Boolean = safBackupStorage.getFolderOrNull() != null

    fun validateBackup(entry: BackupEntry): BackupValidationResult {
        return try {
            val staged = stageToLocalTemp(entry)
            try {
                DatabaseUtils.validateDatabaseBackup(staged)
            } finally {
                if (entry.source == BackupSource.PUBLIC) staged.delete()
            }
        } catch (e: Exception) {
            BackupValidationResult(isValid = false, errorMessage = e.message ?: "Validation failed")
        }
    }

    fun restoreFromBackup(entry: BackupEntry): RestoreResult {
        val now = System.currentTimeMillis()
        val stagedSource = stageToLocalTemp(entry)
        try {
            val validation = DatabaseUtils.validateDatabaseBackup(stagedSource)
            require(validation.isValid) { validation.errorMessage ?: "Invalid backup" }
            requireNotNull(validation.databaseVersion)
            DatabaseUtils.restoreDatabaseFromBackup(context, stagedSource)
            statusStore.recordSuccess(entry.location, stagedSource.length(), validation.databaseVersion, now)
            return RestoreResult(validation.databaseVersion)
        } catch (e: Exception) {
            statusStore.recordFailure(e.message ?: "Restore failed", now)
            throw e
        } finally {
            stagedSource.delete()
        }
    }

    private fun createPublicBackupFile(timestamp: Long): DocumentFile {
        return safBackupStorage.createBackupFile(timestamp)
    }

    private fun stageToLocalTemp(entry: BackupEntry): File {
        val tempDir = requireNotNull(catalog.getPrivateBackupDir()) { "Backup directory unavailable" }
        val staged = File(tempDir, "restore-stage-${System.currentTimeMillis()}.db")
        if (entry.source == BackupSource.PRIVATE) {
            val source = File(entry.location)
            source.copyTo(staged, overwrite = true)
        } else {
            val uri = Uri.parse(entry.location)
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to read backup file")
        }
        return staged
    }
}
