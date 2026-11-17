/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.backup

import java.io.File

/**
 * Durable state recorder for database restore transitions.
 * Guarantees that any interrupted restore operation across process termination
 * or unexpected reboot can be deterministically reconciled on the next cold start.
 */
class RestoreJournal(
    private val parentDir: File,
    private val databaseFilename: String
) {
    enum class State {
        IDLE,
        PREPARING,
        SWAPPING,
        APPLIED
    }

    data class Record(
        val state: State,
        val targetVersion: Int,
        val timestamp: Long,
        val sourceBackupPath: String?
    )

    val journalFile: File
        get() = File(parentDir, "$databaseFilename.restore.state")

    fun recordState(state: State, targetVersion: Int = 0, sourceBackupPath: String? = null) {
        val line = "${state.name}|$targetVersion|${System.currentTimeMillis()}|${sourceBackupPath.orEmpty()}"
        journalFile.writeText(line)
    }

    fun readState(): Record? {
        if (!journalFile.exists() || journalFile.length() == 0L) return null
        return try {
            val content = journalFile.readText().trim()
            val parts = content.split("|")
            val state = State.valueOf(parts[0])
            val targetVersion = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val timestamp = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val sourcePath = parts.getOrNull(3)?.ifEmpty { null }
            Record(state, targetVersion, timestamp, sourcePath)
        } catch (e: Exception) {
            Record(State.SWAPPING, 0, 0L, null)
        }
    }

    fun clear() {
        if (journalFile.exists()) {
            journalFile.delete()
        }
    }

    val exists: Boolean
        get() = journalFile.exists()
}
