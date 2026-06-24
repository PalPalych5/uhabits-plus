package org.isoron.uhabits.backup

import androidx.documentfile.provider.DocumentFile
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.AndroidDirFinder
import java.io.File

@Inject
class BackupCatalog(
    private val dirFinder: AndroidDirFinder,
    private val safBackupStorage: SafBackupStorage
) {
    fun listBackups(): List<BackupEntry> {
        return (listPrivateBackups() + listPublicBackups()).sortedByDescending { it.modifiedAt }
    }

    fun listPrivateBackups(): List<BackupEntry> = readPrivateBackups()

    fun listPublicBackups(): List<BackupEntry> = readPublicBackups()

    fun applyPrivateRetention(keep: Int) = prunePrivateBackups(keep)

    fun applyPublicRetention(keep: Int) = prunePublicBackups(keep)

    internal fun filterBackupFiles(files: List<File>): List<File> {
        return filterLocalBackupFiles(files)
    }

    private fun readPrivateBackups(): List<BackupEntry> {
        val dir = dirFinder.getFilesDir(BACKUP_DIR_NAME) ?: return emptyList()
        return filterBackupFiles(dir.listFiles()?.toList().orEmpty()).map {
            BackupEntry(
                name = it.name,
                location = it.absolutePath,
                modifiedAt = it.lastModified(),
                sizeBytes = it.length(),
                source = BackupSource.PRIVATE
            )
        }
    }

    private fun readPublicBackups(): List<BackupEntry> {
        val dir = safBackupStorage.getFolderOrNull() ?: return emptyList()
        return dir.listFiles()
            .filter { it.isFile && BackupFileNamePolicy.isBackupFile(it.name) }
            .sortedByDescending { it.lastModified() }
            .map {
                BackupEntry(
                    name = it.name ?: it.uri.toString(),
                    location = it.uri.toString(),
                    modifiedAt = it.lastModified(),
                    sizeBytes = it.length(),
                    source = BackupSource.PUBLIC
                )
            }
    }

    private fun prunePrivateBackups(keep: Int) {
        val dir = dirFinder.getFilesDir(BACKUP_DIR_NAME) ?: return
        val backups = filterBackupFiles(dir.listFiles()?.toList().orEmpty())
        backups.drop(keep).forEach { it.delete() }
    }

    private fun prunePublicBackups(keep: Int) {
        val dir = safBackupStorage.getFolderOrNull() ?: return
        val backups = dir.listFiles()
            .filter { it.isFile && BackupFileNamePolicy.isBackupFile(it.name) }
            .sortedByDescending { it.lastModified() }
        backups.drop(keep).forEach { it.delete() }
    }

    fun getPrivateBackupDir(): File? = dirFinder.getFilesDir(BACKUP_DIR_NAME)

    companion object {
        const val BACKUP_DIR_NAME = "Backups"

        internal fun filterLocalBackupFiles(files: List<File>): List<File> {
            return files.filter { it.isFile && BackupFileNamePolicy.isBackupFile(it.name) }
                .sortedByDescending { it.lastModified() }
        }

        internal fun applyRetentionToLocalFiles(files: List<File>, keep: Int) {
            filterLocalBackupFiles(files).drop(keep).forEach { it.delete() }
        }
    }
}
