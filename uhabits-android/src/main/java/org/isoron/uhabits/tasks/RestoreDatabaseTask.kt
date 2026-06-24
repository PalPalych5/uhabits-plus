package org.isoron.uhabits.tasks

import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.backup.BackupEntry
import org.isoron.uhabits.backup.BackupManager
import org.isoron.uhabits.core.tasks.Task

class RestoreDatabaseTask(
    private val application: HabitsApplication,
    private val backupManager: BackupManager,
    private val entry: BackupEntry,
    private val listener: Listener
) : Task {
    private var result: Result<org.isoron.uhabits.backup.RestoreResult>? = null

    override suspend fun doInBackground() {
        result = runCatching {
            backupManager.restoreFromBackup(entry).also {
                application.component.syncCoordinator.markRestoreNeedsReview(
                    "Локальная база восстановлена из резервной копии. Перед следующей синхронизацией требуется подтверждение."
                )
                application.shutdownForDatabaseRestoreRestart()
            }
        }
    }

    override fun onPostExecute() {
        val current = result ?: Result.failure(IllegalStateException("Restore did not run"))
        current.fold(
            onSuccess = { listener.onRestoreFinished(null) },
            onFailure = { listener.onRestoreFinished(it.message ?: "Restore failed") }
        )
    }

    fun interface Listener {
        fun onRestoreFinished(error: String?)
    }
}
