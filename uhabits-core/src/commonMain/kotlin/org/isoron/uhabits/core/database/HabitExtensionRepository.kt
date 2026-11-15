package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode

data class HabitExtensionData(
    val habitId: Long,
    val dayTier: String = "NORMAL",
    val timerEnabled: Boolean = false,
    val blockId: Long? = null,
    val statsStartTimestamp: Long? = null,
    val missingDataPolicy: Int? = null
)

class HabitExtensionRepository(private val db: Database) {
    var authorityMode: OrganizationAuthorityMode = OrganizationAuthorityMode.LEGACY

    private val hasMissingDataPolicyColumn: Boolean by lazy {
        val stmt = db.prepareStatement("PRAGMA table_info(HabitExtensions)")
        try {
            var found = false
            while (stmt.step() == StepResult.ROW) {
                if (stmt.getText(1) == "missing_data_policy") {
                    found = true
                    break
                }
            }
            found
        } finally {
            stmt.finalize()
        }
    }

    private val findByHabitIdStmt by lazy {
        val selectCols = if (hasMissingDataPolicyColumn) {
            "habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp, missing_data_policy"
        } else {
            "habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp"
        }
        db.prepareStatement("SELECT $selectCols FROM HabitExtensions WHERE habit_id = ?")
    }
    private val upsertStmt by lazy {
        if (hasMissingDataPolicyColumn) {
            db.prepareStatement(
                """INSERT OR REPLACE INTO HabitExtensions(habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp, missing_data_policy)
                   VALUES (?, ?, ?, ?, ?, ?)"""
            )
        } else {
            db.prepareStatement(
                """INSERT OR REPLACE INTO HabitExtensions(habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp)
                   VALUES (?, ?, ?, ?, ?)"""
            )
        }
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
            timerEnabled = findByHabitIdStmt.getInt(2) != 0,
            blockId = findByHabitIdStmt.getLongOrNull(3),
            statsStartTimestamp = findByHabitIdStmt.getLongOrNull(4),
            missingDataPolicy = if (hasMissingDataPolicyColumn) findByHabitIdStmt.getIntOrNull(5) else null
        )
    }

    fun upsert(data: HabitExtensionData) {
        val existing = findByHabitId(data.habitId)
        val blockIdToWrite = if (authorityMode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            existing?.blockId
        } else {
            data.blockId
        }
        val effectiveMissingPolicy = if (hasMissingDataPolicyColumn) data.missingDataPolicy else null
        if (existing == data.copy(blockId = blockIdToWrite, missingDataPolicy = effectiveMissingPolicy)) return

        upsertStmt.reset()
        upsertStmt.bindLong(1, data.habitId)
        upsertStmt.bindText(2, data.dayTier)
        upsertStmt.bindInt(3, if (data.timerEnabled) 1 else 0)
        if (blockIdToWrite != null) {
            upsertStmt.bindLong(4, blockIdToWrite)
        } else {
            upsertStmt.bindNull(4)
        }
        if (data.statsStartTimestamp != null) {
            upsertStmt.bindLong(5, data.statsStartTimestamp)
        } else {
            upsertStmt.bindNull(5)
        }
        if (hasMissingDataPolicyColumn) {
            if (data.missingDataPolicy != null) {
                upsertStmt.bindInt(6, data.missingDataPolicy)
            } else {
                upsertStmt.bindNull(6)
            }
        }
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
