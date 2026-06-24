package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult

data class EntryOpRecord(
    val opUuid: String,
    val habitUuid: String,
    val entryTimestamp: Long,
    val deltaValue: Int,
    val opType: String,
    val notes: String? = null,
    val deviceId: String,
    val createdAt: Long,
    val deletedAt: Long? = null
)

class EntryOpRepository(private val db: Database) {
    private val insertStmt by lazy {
        db.prepareStatement(
            """INSERT OR REPLACE INTO EntryOps(
               op_uuid, habit_uuid, entry_timestamp, delta_value, op_type,
               notes, device_id, created_at, deleted_at
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
    }

    private val findByHabitAndTimestampStmt by lazy {
        db.prepareStatement(
            """SELECT op_uuid, habit_uuid, entry_timestamp, delta_value, op_type,
               notes, device_id, created_at, deleted_at
               FROM EntryOps
               WHERE habit_uuid = ? AND entry_timestamp = ? AND deleted_at IS NULL
               ORDER BY created_at ASC"""
        )
    }

    private val findByOpUuidStmt by lazy {
        db.prepareStatement(
            """SELECT op_uuid, habit_uuid, entry_timestamp, delta_value, op_type,
               notes, device_id, created_at, deleted_at
               FROM EntryOps WHERE op_uuid = ? LIMIT 1"""
        )
    }

    fun insert(record: EntryOpRecord) {
        insertStmt.reset()
        insertStmt.bindText(1, record.opUuid)
        insertStmt.bindText(2, record.habitUuid)
        insertStmt.bindLong(3, record.entryTimestamp)
        insertStmt.bindInt(4, record.deltaValue)
        insertStmt.bindText(5, record.opType)
        if (record.notes != null) insertStmt.bindText(6, record.notes) else insertStmt.bindNull(6)
        insertStmt.bindText(7, record.deviceId)
        insertStmt.bindLong(8, record.createdAt)
        if (record.deletedAt != null) insertStmt.bindLong(9, record.deletedAt) else insertStmt.bindNull(9)
        insertStmt.step()
    }

    fun findActive(habitUuid: String, entryTimestamp: Long): List<EntryOpRecord> {
        findByHabitAndTimestampStmt.reset()
        findByHabitAndTimestampStmt.bindText(1, habitUuid)
        findByHabitAndTimestampStmt.bindLong(2, entryTimestamp)
        val records = mutableListOf<EntryOpRecord>()
        while (findByHabitAndTimestampStmt.step() == StepResult.ROW) {
            records.add(
                EntryOpRecord(
                    opUuid = findByHabitAndTimestampStmt.getText(0),
                    habitUuid = findByHabitAndTimestampStmt.getText(1),
                    entryTimestamp = findByHabitAndTimestampStmt.getLong(2),
                    deltaValue = findByHabitAndTimestampStmt.getInt(3),
                    opType = findByHabitAndTimestampStmt.getText(4),
                    notes = findByHabitAndTimestampStmt.getTextOrNull(5),
                    deviceId = findByHabitAndTimestampStmt.getText(6),
                    createdAt = findByHabitAndTimestampStmt.getLong(7),
                    deletedAt = findByHabitAndTimestampStmt.getLongOrNull(8)
                )
            )
        }
        return records
    }

    fun findByOpUuid(opUuid: String): EntryOpRecord? {
        findByOpUuidStmt.reset()
        findByOpUuidStmt.bindText(1, opUuid)
        if (findByOpUuidStmt.step() != StepResult.ROW) return null
        return EntryOpRecord(
            opUuid = findByOpUuidStmt.getText(0),
            habitUuid = findByOpUuidStmt.getText(1),
            entryTimestamp = findByOpUuidStmt.getLong(2),
            deltaValue = findByOpUuidStmt.getInt(3),
            opType = findByOpUuidStmt.getText(4),
            notes = findByOpUuidStmt.getTextOrNull(5),
            deviceId = findByOpUuidStmt.getText(6),
            createdAt = findByOpUuidStmt.getLong(7),
            deletedAt = findByOpUuidStmt.getLongOrNull(8)
        )
    }
}
