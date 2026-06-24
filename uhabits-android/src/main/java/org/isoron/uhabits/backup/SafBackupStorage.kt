package org.isoron.uhabits.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.R
import org.isoron.uhabits.inject.AppContext

@Inject
class SafBackupStorage(
    @AppContext private val context: Context
) {
    fun isFolderConfigured(): Boolean = getFolderUriString() != null

    fun getFolderOrNull(): DocumentFile? {
        val uriString = getFolderUriString() ?: return null
        val uri = Uri.parse(uriString)
        return runCatching {
            DocumentFile.fromTreeUri(context, uri)
        }.getOrNull()?.takeIf { it.exists() && it.isDirectory && it.canRead() }
    }

    fun requireReadableFolder(): DocumentFile {
        if (!isFolderConfigured()) {
            throw IllegalStateException(context.getString(R.string.backup_external_folder_not_selected))
        }
        return getFolderOrNull()
            ?: throw IllegalStateException(context.getString(R.string.backup_external_folder_permission_lost))
    }

    fun requireWritableFolder(): DocumentFile {
        val folder = requireReadableFolder()
        if (!folder.canWrite()) {
            throw IllegalStateException(context.getString(R.string.backup_external_folder_permission_lost))
        }
        return folder
    }

    fun createBackupFile(timestamp: Long): DocumentFile {
        val folder = requireWritableFolder()
        val filename = BackupFileNamePolicy.buildFileName(timestamp)
        folder.findFile(filename)?.delete()
        return folder.createFile("application/octet-stream", filename)
            ?: throw IllegalStateException(context.getString(R.string.backup_external_create_failed))
    }

    private fun getFolderUriString(): String? {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREFERENCE_KEY, null)
    }

    companion object {
        const val PREFERENCE_KEY = "publicBackupFolder"
    }
}
