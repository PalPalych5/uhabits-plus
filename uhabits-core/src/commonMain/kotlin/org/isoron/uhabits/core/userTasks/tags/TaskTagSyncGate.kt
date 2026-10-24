package org.isoron.uhabits.core.userTasks.tags

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult

/** Never feed new entities into the v1 endpoint, which can ACK unsupported payloads. */
object TaskTagSyncGate {
    const val REQUIRED_CAPABILITY = "task_tags_v1"

    fun hasPendingChanges(db: Database): Boolean {
        val table = db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='TaskTagOutbox'")
        val exists = try { table.step() == StepResult.ROW } finally { table.finalize() }
        if (!exists) return false
        val pending = db.prepareStatement("SELECT 1 FROM TaskTagOutbox LIMIT 1")
        return try { pending.step() == StepResult.ROW } finally { pending.finalize() }
    }
}
