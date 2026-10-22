package org.isoron.uhabits.tasks

import android.os.Process
import kotlin.system.exitProcess
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.backup.BackupEntry
import org.isoron.uhabits.backup.BackupManager
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.utils.ExperimentalRestoreRestartRequiredException

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
                // DatabaseUtils quarantines sync and clears the replacement's delivery
                // state before publishing it; the old component DB is already closed.
                application.shutdownForDatabaseRestoreRestart()
            }
        }
    }

    override fun onPostExecute() {
        val current = result ?: Result.failure(IllegalStateException("Restore did not run"))
        current.fold(
            onSuccess = { listener.onRestoreFinished(null) },
            onFailure = {
                listener.onRestoreFinished(it.message ?: "Restore failed")
                if (it is ExperimentalRestoreRestartRequiredException) {
                    application.scheduleProcessRestart(MainDestination.HABITS, showRestoreSuccess = false)
                    // Closed repositories cannot be reused by merely relaunching an Activity.
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }
            }
        )
    }

    fun interface Listener {
        fun onRestoreFinished(error: String?)
    }
}
