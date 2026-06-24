package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run

data class EntryData(
    var id: Long? = null,
    var habitId: Long? = null,
    var uuid: String = "",
    var timestamp: Long = 0,
    var value: Int = 0,
    var notes: String = "",
    var updatedAt: Long = 0,
    var deletedAt: Long? = null
)

class EntryRepository(private val db: Database) {
    private val findAllByHabitStmt by lazy {
        db.prepareStatement(
            """SELECT id, habit, uuid, timestamp, value, notes, updated_at, deleted_at
               FROM Repetitions
               WHERE habit = ? AND deleted_at IS NULL
               ORDER BY timestamp DESC"""
        )
    }

    private val upsertStmt by lazy {
        db.prepareStatement(
            """INSERT INTO Repetitions(habit, uuid, timestamp, value, notes, updated_at, deleted_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(habit, timestamp) DO UPDATE SET
                   uuid=excluded.uuid,
                   value=excluded.value,
                   notes=excluded.notes,
                   updated_at=excluded.updated_at,
                   deleted_at=excluded.deleted_at"""
        )
    }

    private val softDeleteByHabitAndTimestampStmt by lazy {
        db.prepareStatement(
            "UPDATE Repetitions SET updated_at = ?, deleted_at = ? WHERE habit = ? AND timestamp = ? AND deleted_at IS NULL"
        )
    }

    private val softDeleteByHabitStmt by lazy {
        db.prepareStatement(
            "UPDATE Repetitions SET updated_at = ?, deleted_at = ? WHERE habit = ? AND deleted_at IS NULL"
        )
    }

    private val findByUuidStmt by lazy {
        db.prepareStatement(
            """SELECT id, habit, uuid, timestamp, value, notes, updated_at, deleted_at
               FROM Repetitions WHERE uuid = ? LIMIT 1"""
        )
    }

    fun findAllByHabitId(habitId: Long): List<EntryData> {
        findAllByHabitStmt.reset()
        findAllByHabitStmt.bindLong(1, habitId)
        val results = mutableListOf<EntryData>()
        while (findAllByHabitStmt.step() == StepResult.ROW) {
            results.add(
                EntryData(
                    id = findAllByHabitStmt.getLong(0),
                    habitId = findAllByHabitStmt.getLong(1),
                    uuid = findAllByHabitStmt.getTextOrNull(2) ?: "",
                    timestamp = findAllByHabitStmt.getLong(3),
                    value = findAllByHabitStmt.getInt(4),
                    notes = findAllByHabitStmt.getTextOrNull(5) ?: "",
                    updatedAt = findAllByHabitStmt.getLong(6),
                    deletedAt = findAllByHabitStmt.getLongOrNull(7)
                )
            )
        }
        return results
    }

    fun upsert(data: EntryData): Long {
        if (data.uuid.isBlank()) data.uuid = "${data.habitId}:${data.timestamp}"
        upsertStmt.reset()
        upsertStmt.bindLong(1, data.habitId!!)
        upsertStmt.bindText(2, data.uuid)
        upsertStmt.bindLong(3, data.timestamp)
        upsertStmt.bindInt(4, data.value)
        upsertStmt.bindText(5, data.notes)
        upsertStmt.bindLong(6, data.updatedAt)
        if (data.deletedAt != null) upsertStmt.bindLong(7, data.deletedAt!!) else upsertStmt.bindNull(7)
        upsertStmt.step()
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun insert(data: EntryData): Long = upsert(data)

    fun findByUuid(uuid: String): EntryData? {
        findByUuidStmt.reset()
        findByUuidStmt.bindText(1, uuid)
        if (findByUuidStmt.step() != StepResult.ROW) return null
        return EntryData(
            id = findByUuidStmt.getLong(0),
            habitId = findByUuidStmt.getLong(1),
            uuid = findByUuidStmt.getTextOrNull(2) ?: "",
            timestamp = findByUuidStmt.getLong(3),
            value = findByUuidStmt.getInt(4),
            notes = findByUuidStmt.getTextOrNull(5) ?: "",
            updatedAt = findByUuidStmt.getLong(6),
            deletedAt = findByUuidStmt.getLongOrNull(7)
        )
    }

    fun softDeleteByHabitIdAndTimestamp(habitId: Long, timestamp: Long, deletedAt: Long) {
        softDeleteByHabitAndTimestampStmt.reset()
        softDeleteByHabitAndTimestampStmt.bindLong(1, deletedAt)
        softDeleteByHabitAndTimestampStmt.bindLong(2, deletedAt)
        softDeleteByHabitAndTimestampStmt.bindLong(3, habitId)
        softDeleteByHabitAndTimestampStmt.bindLong(4, timestamp)
        softDeleteByHabitAndTimestampStmt.step()
    }

    fun deleteByHabitIdAndTimestamp(habitId: Long, timestamp: Long) =
        softDeleteByHabitIdAndTimestamp(habitId, timestamp, deletedAt = 0)

    fun softDeleteByHabitId(habitId: Long, deletedAt: Long) {
        softDeleteByHabitStmt.reset()
        softDeleteByHabitStmt.bindLong(1, deletedAt)
        softDeleteByHabitStmt.bindLong(2, deletedAt)
        softDeleteByHabitStmt.bindLong(3, habitId)
        softDeleteByHabitStmt.step()
    }

    fun deleteByHabitId(habitId: Long) = softDeleteByHabitId(habitId, deletedAt = 0)

    fun execSQL(sql: String) = db.run(sql)
}
