package org.isoron.uhabits.tasks

import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.backup.BackupEntry
import org.isoron.uhabits.backup.BackupManager

@Inject
class RestoreDatabaseTaskFactory(
    private val application: HabitsApplication,
    private val backupManager: BackupManager
) {
    fun create(entry: BackupEntry, listener: RestoreDatabaseTask.Listener) =
        RestoreDatabaseTask(application, backupManager, entry, listener)
}
