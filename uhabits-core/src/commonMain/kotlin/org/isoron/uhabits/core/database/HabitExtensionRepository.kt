package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult

data class HabitExtensionData(
    val habitId: Long,
    val dayTier: String = "NORMAL",
    val timerEnabled: Boolean = false
)

class HabitExtensionRepository(private val db: Database) {
    private val findByHabitIdStmt by lazy {
        db.prepareStatement(
            "SELECT habit_id, day_tier, timer_enabled FROM HabitExtensions WHERE habit_id = ?"
        )
    }
    private val upsertStmt by lazy {
        db.prepareStatement(
            """INSERT OR REPLACE INTO HabitExtensions(habit_id, day_tier, timer_enabled)
               VALUES (?, ?, ?)"""
        )
    }
    private val deleteStmt by lazy {
        db.prepareStatement("DELETE FROM HabitExtensions WHERE habit_id = ?")
    }
    private val deleteAllStmt by lazy {
        db.prepareStatement("DELETE FROM HabitExtensions")
    }

    fun findByHabitId(habitId: Long): HabitExtensionData? {
        findByHabitIdStmt.reset()
        findByHabitIdStmt.bindLong(1, habitId)
        if (findByHabitIdStmt.step() != StepResult.ROW) return null
        return HabitExtensionData(
            habitId = findByHabitIdStmt.getLong(0),
            dayTier = findByHabitIdStmt.getText(1),
            timerEnabled = findByHabitIdStmt.getInt(2) != 0
        )
    }

    fun upsert(data: HabitExtensionData) {
        upsertStmt.reset()
        upsertStmt.bindLong(1, data.habitId)
        upsertStmt.bindText(2, data.dayTier)
        upsertStmt.bindInt(3, if (data.timerEnabled) 1 else 0)
        upsertStmt.step()
    }

    fun delete(habitId: Long) {
        deleteStmt.reset()
        deleteStmt.bindLong(1, habitId)
        deleteStmt.step()
    }

    fun deleteAll() {
        deleteAllStmt.reset()
        deleteAllStmt.step()
    }
}
