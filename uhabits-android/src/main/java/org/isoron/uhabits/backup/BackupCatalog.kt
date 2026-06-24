package org.isoron.uhabits.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.AndroidDirFinder
import org.isoron.uhabits.inject.AppContext
import java.io.File

@Inject
class BackupCatalog(
    @AppContext private val context: Context,
    private val dirFinder: AndroidDirFinder
) {
    fun listBackups(): List<BackupEntry> {
        return (listPrivateBackups() + listPublicBackups()).sortedByDescending { it.modifiedAt }
    }

    fun applyRetention(keep: Int) {
        prunePrivateBackups(keep)
        prunePublicBackups(keep)
    }

    internal fun filterBackupFiles(files: List<File>): List<File> {
        return filterLocalBackupFiles(files)
    }

    private fun listPrivateBackups(): List<BackupEntry> {
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

    private fun listPublicBackups(): List<BackupEntry> {
        val dir = getPublicBackupDir() ?: return emptyList()
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
        val dir = getPublicBackupDir() ?: return
        val backups = dir.listFiles()
            .filter { it.isFile && BackupFileNamePolicy.isBackupFile(it.name) }
            .sortedByDescending { it.lastModified() }
        backups.drop(keep).forEach { it.delete() }
    }

    fun getPrivateBackupDir(): File? = dirFinder.getFilesDir(BACKUP_DIR_NAME)

    fun getPublicBackupDir(): DocumentFile? {
        val uriString = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("publicBackupFolder", null) ?: return null
        val uri = Uri.parse(uriString)
        return if (uri.scheme == "content") {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromFile(File(uri.path!!))
        }
    }

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
