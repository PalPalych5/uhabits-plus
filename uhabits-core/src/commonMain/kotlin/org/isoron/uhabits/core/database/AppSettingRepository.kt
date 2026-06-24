package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult

class AppSettingRepository(private val db: Database) {
    private val findLongStmt by lazy {
        db.prepareStatement("SELECT long_value FROM AppSettings WHERE key = ?")
    }

    private val upsertLongStmt by lazy {
        db.prepareStatement(
            "INSERT OR REPLACE INTO AppSettings(key, long_value) VALUES (?, ?)"
        )
    }

    private val deleteStmt by lazy {
        db.prepareStatement("DELETE FROM AppSettings WHERE key = ?")
    }

    fun getLong(key: String): Long? {
        findLongStmt.reset()
        findLongStmt.bindText(1, key)
        if (findLongStmt.step() != StepResult.ROW) return null
        return findLongStmt.getLongOrNull(0)
    }

    fun putLong(key: String, value: Long?) {
        if (value == null) {
            delete(key)
            return
        }
        upsertLongStmt.reset()
        upsertLongStmt.bindText(1, key)
        upsertLongStmt.bindLong(2, value)
        upsertLongStmt.step()
    }

    fun delete(key: String) {
        deleteStmt.reset()
        deleteStmt.bindText(1, key)
        deleteStmt.step()
    }
}
