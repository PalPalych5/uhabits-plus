package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult

data class HabitGoalData(
    val id: Long? = null,
    val habitId: Long,
    val effectiveTimestamp: Long,
    val freqNum: Int,
    val freqDen: Int,
    val targetType: Int,
    val targetValue: Double,
    val unit: String
)

class HabitGoalRepository(private val db: Database) {
    private val findAllByHabitIdStmt by lazy {
        db.prepareStatement(
            """SELECT id, habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit
               FROM HabitGoals WHERE habit_id = ? ORDER BY effective_timestamp DESC"""
        )
    }

    private val insertStmt by lazy {
        db.prepareStatement(
            """INSERT INTO HabitGoals(
               habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit
               ) VALUES (?, ?, ?, ?, ?, ?, ?)"""
        )
    }

    private val deleteByHabitIdStmt by lazy {
        db.prepareStatement("DELETE FROM HabitGoals WHERE habit_id = ?")
    }

    fun findAllByHabitId(habitId: Long): List<HabitGoalData> {
        findAllByHabitIdStmt.reset()
        findAllByHabitIdStmt.bindLong(1, habitId)
        val results = mutableListOf<HabitGoalData>()
        while (findAllByHabitIdStmt.step() == StepResult.ROW) {
            results.add(
                HabitGoalData(
                    id = findAllByHabitIdStmt.getLong(0),
                    habitId = findAllByHabitIdStmt.getLong(1),
                    effectiveTimestamp = findAllByHabitIdStmt.getLong(2),
                    freqNum = findAllByHabitIdStmt.getInt(3),
                    freqDen = findAllByHabitIdStmt.getInt(4),
                    targetType = findAllByHabitIdStmt.getInt(5),
                    targetValue = findAllByHabitIdStmt.getReal(6),
                    unit = findAllByHabitIdStmt.getTextOrNull(7) ?: ""
                )
            )
        }
        return results
    }

    fun insert(data: HabitGoalData) {
        insertStmt.reset()
        insertStmt.bindLong(1, data.habitId)
        insertStmt.bindLong(2, data.effectiveTimestamp)
        insertStmt.bindInt(3, data.freqNum)
        insertStmt.bindInt(4, data.freqDen)
        insertStmt.bindInt(5, data.targetType)
        insertStmt.bindReal(6, data.targetValue)
        insertStmt.bindText(7, data.unit)
        insertStmt.step()
    }

    fun replaceAll(habitId: Long, goals: List<HabitGoalData>) {
        deleteByHabitId(habitId)
        goals.sortedBy { it.effectiveTimestamp }.forEach { insert(it.copy(habitId = habitId)) }
    }

    fun deleteByHabitId(habitId: Long) {
        deleteByHabitIdStmt.reset()
        deleteByHabitIdStmt.bindLong(1, habitId)
        deleteByHabitIdStmt.step()
    }
}
