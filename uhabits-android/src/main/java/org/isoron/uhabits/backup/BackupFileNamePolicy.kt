package org.isoron.uhabits.backup

import org.isoron.platform.time.DateFormats.Companion.getBackupDateFormat

object BackupFileNamePolicy {
    private val backupPattern = Regex("^Loop Habits Backup .+\\.db$")

    fun buildFileName(timestamp: Long): String {
        val date = getBackupDateFormat().format(timestamp)
        return "Loop Habits Backup $date.db"
    }

    fun isBackupFile(name: String?): Boolean {
        return name?.matches(backupPattern) == true
    }
}
