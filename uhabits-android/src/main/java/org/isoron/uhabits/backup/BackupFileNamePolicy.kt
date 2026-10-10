package org.isoron.uhabits.backup

import org.isoron.platform.time.DateFormats.Companion.getBackupDateFormat

object BackupFileNamePolicy {
    private val zipPattern = Regex("^Loop Habits Backup .+\\.zip$")
    private val dbPattern = Regex("^Loop Habits Backup .+\\.db$")
    private val backupPattern = Regex("^Loop Habits Backup .+\\.(zip|db)$")

    fun buildFileName(timestamp: Long): String {
        val date = getBackupDateFormat().format(timestamp)
        return "Loop Habits Backup $date.zip"
    }

    fun buildLegacyDbFileName(timestamp: Long): String {
        val date = getBackupDateFormat().format(timestamp)
        return "Loop Habits Backup $date.db"
    }

    fun isBackupFile(name: String?): Boolean {
        return name?.matches(backupPattern) == true
    }

    fun isZipBackup(name: String?): Boolean {
        return name?.matches(zipPattern) == true
    }

    fun isRawDbBackup(name: String?): Boolean {
        return name?.matches(dbPattern) == true
    }
}
