package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryInt

data class HabitGoalData(
    val id: Long? = null,
    val habitId: Long,
    val uuid: String = "",
    val effectiveTimestamp: Long,
    val freqNum: Int,
    val freqDen: Int,
    val targetType: Int,
    val targetValue: Double,
    val unit: String,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null
)

class HabitGoalRepository(private val db: Database) {
    private val findAllByHabitIdStmt by lazy {
        db.prepareStatement(
            """SELECT id, habit_id, uuid, effective_timestamp, freq_num, freq_den, target_type,
               target_value, unit, updated_at, deleted_at
               FROM HabitGoals
               WHERE habit_id = ? AND deleted_at IS NULL
               ORDER BY effective_timestamp DESC"""
        )
    }

    private val insertOrIgnoreStmt by lazy {
        db.prepareStatement(
            """INSERT OR IGNORE INTO HabitGoals(
               habit_id, uuid, effective_timestamp, freq_num, freq_den, target_type, target_value,
               unit, updated_at, deleted_at
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
    }

    private val updateByUuidStmt by lazy {
        db.prepareStatement(
            """UPDATE HabitGoals SET
               freq_num = ?,
               freq_den = ?,
               target_type = ?,
               target_value = ?,
               unit = ?,
               updated_at = ?,
               deleted_at = ?
               WHERE uuid = ?"""
        )
    }

    private val softDeleteByHabitIdStmt by lazy {
        db.prepareStatement(
            "UPDATE HabitGoals SET updated_at = ?, deleted_at = ? WHERE habit_id = ? AND deleted_at IS NULL"
        )
    }

    private val findByUuidStmt by lazy {
        db.prepareStatement(
            """SELECT id, habit_id, uuid, effective_timestamp, freq_num, freq_den, target_type,
               target_value, unit, updated_at, deleted_at
               FROM HabitGoals WHERE uuid = ? LIMIT 1"""
        )
    }

    @kotlin.jvm.Synchronized
    fun findAllByHabitId(habitId: Long): List<HabitGoalData> {
        findAllByHabitIdStmt.reset()
        findAllByHabitIdStmt.bindLong(1, habitId)
        val results = mutableListOf<HabitGoalData>()
        while (findAllByHabitIdStmt.step() == StepResult.ROW) {
            results.add(
                HabitGoalData(
                    id = findAllByHabitIdStmt.getLong(0),
                    habitId = findAllByHabitIdStmt.getLong(1),
                    uuid = findAllByHabitIdStmt.getText(2),
                    effectiveTimestamp = findAllByHabitIdStmt.getLong(3),
                    freqNum = findAllByHabitIdStmt.getInt(4),
                    freqDen = findAllByHabitIdStmt.getInt(5),
                    targetType = findAllByHabitIdStmt.getInt(6),
                    targetValue = findAllByHabitIdStmt.getReal(7),
                    unit = findAllByHabitIdStmt.getTextOrNull(8) ?: "",
                    updatedAt = findAllByHabitIdStmt.getLong(9),
                    deletedAt = findAllByHabitIdStmt.getLongOrNull(10)
                )
            )
        }
        return results
    }

    @kotlin.jvm.Synchronized
    fun upsert(data: HabitGoalData) {
        val normalized = if (data.uuid.isBlank()) {
            data.copy(uuid = "${data.habitId}:goal:${data.effectiveTimestamp}")
        } else {
            data
        }
        val existing = findByUuid(normalized.uuid)
        if (existing?.copy(id = normalized.id) == normalized) return

        // 1. Try update first by UUID
        updateByUuidStmt.reset()
        updateByUuidStmt.bindInt(1, normalized.freqNum)
        updateByUuidStmt.bindInt(2, normalized.freqDen)
        updateByUuidStmt.bindInt(3, normalized.targetType)
        updateByUuidStmt.bindReal(4, normalized.targetValue)
        updateByUuidStmt.bindText(5, normalized.unit)
        updateByUuidStmt.bindLong(6, normalized.updatedAt)
        if (normalized.deletedAt != null) updateByUuidStmt.bindLong(7, normalized.deletedAt) else updateByUuidStmt.bindNull(7)
        updateByUuidStmt.bindText(8, normalized.uuid)
        updateByUuidStmt.step()

        if (db.queryInt("SELECT changes()") == 0) {
            // 2. Row does not exist yet: try insert or ignore
            insertOrIgnoreStmt.reset()
            insertOrIgnoreStmt.bindLong(1, normalized.habitId)
            insertOrIgnoreStmt.bindText(2, normalized.uuid)
            insertOrIgnoreStmt.bindLong(3, normalized.effectiveTimestamp)
            insertOrIgnoreStmt.bindInt(4, normalized.freqNum)
            insertOrIgnoreStmt.bindInt(5, normalized.freqDen)
            insertOrIgnoreStmt.bindInt(6, normalized.targetType)
            insertOrIgnoreStmt.bindReal(7, normalized.targetValue)
            insertOrIgnoreStmt.bindText(8, normalized.unit)
            insertOrIgnoreStmt.bindLong(9, normalized.updatedAt)
            if (normalized.deletedAt != null) insertOrIgnoreStmt.bindLong(10, normalized.deletedAt) else insertOrIgnoreStmt.bindNull(10)
            insertOrIgnoreStmt.step()

            if (db.queryInt("SELECT changes()") == 0) {
                // Another connection inserted concurrently: update the existing row
                updateByUuidStmt.reset()
                updateByUuidStmt.bindInt(1, normalized.freqNum)
                updateByUuidStmt.bindInt(2, normalized.freqDen)
                updateByUuidStmt.bindInt(3, normalized.targetType)
                updateByUuidStmt.bindReal(4, normalized.targetValue)
                updateByUuidStmt.bindText(5, normalized.unit)
                updateByUuidStmt.bindLong(6, normalized.updatedAt)
                if (normalized.deletedAt != null) updateByUuidStmt.bindLong(7, normalized.deletedAt) else updateByUuidStmt.bindNull(7)
                updateByUuidStmt.bindText(8, normalized.uuid)
                updateByUuidStmt.step()
            }
        }
    }

    fun insert(data: HabitGoalData) = upsert(data)

    @kotlin.jvm.Synchronized
    fun findByUuid(uuid: String): HabitGoalData? {
        findByUuidStmt.reset()
        findByUuidStmt.bindText(1, uuid)
        if (findByUuidStmt.step() != StepResult.ROW) return null
        return HabitGoalData(
            id = findByUuidStmt.getLong(0),
            habitId = findByUuidStmt.getLong(1),
            uuid = findByUuidStmt.getText(2),
            effectiveTimestamp = findByUuidStmt.getLong(3),
            freqNum = findByUuidStmt.getInt(4),
            freqDen = findByUuidStmt.getInt(5),
            targetType = findByUuidStmt.getInt(6),
            targetValue = findByUuidStmt.getReal(7),
            unit = findByUuidStmt.getTextOrNull(8) ?: "",
            updatedAt = findByUuidStmt.getLong(9),
            deletedAt = findByUuidStmt.getLongOrNull(10)
        )
    }

    @kotlin.jvm.Synchronized
    fun replaceAll(habitId: Long, goals: List<HabitGoalData>, updatedAt: Long) {
        softDeleteByHabitId(habitId, updatedAt)
        goals.sortedBy { it.effectiveTimestamp }.forEach {
            upsert(it.copy(habitId = habitId, updatedAt = updatedAt, deletedAt = null))
        }
    }

    fun replaceAll(habitId: Long, goals: List<HabitGoalData>) =
        replaceAll(habitId, goals, updatedAt = 0)

    @kotlin.jvm.Synchronized
    fun softDeleteByHabitId(habitId: Long, deletedAt: Long) {
        softDeleteByHabitIdStmt.reset()
        softDeleteByHabitIdStmt.bindLong(1, deletedAt)
        softDeleteByHabitIdStmt.bindLong(2, deletedAt)
        softDeleteByHabitIdStmt.bindLong(3, habitId)
        softDeleteByHabitIdStmt.step()
    }

    fun deleteByHabitId(habitId: Long) = softDeleteByHabitId(habitId, deletedAt = 0)
}
