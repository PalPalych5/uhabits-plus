package org.isoron.uhabits.core.database

import org.isoron.platform.Synchronized
import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryInt
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
    // Keep each shared statement's reset/bind/step/read sequence under this monitor.
    private val findAllByHabitStmt by lazy {
        db.prepareStatement(
            """SELECT id, habit, uuid, timestamp, value, notes, updated_at, deleted_at
               FROM Repetitions
               WHERE habit = ? AND deleted_at IS NULL
               ORDER BY timestamp DESC"""
        )
    }

    private val findIdByHabitAndTimestampStmt by lazy {
        db.prepareStatement(
            "SELECT id FROM Repetitions WHERE habit = ? AND timestamp = ? LIMIT 1"
        )
    }

    private val insertOrIgnoreStmt by lazy {
        db.prepareStatement(
            """INSERT OR IGNORE INTO Repetitions(habit, uuid, timestamp, value, notes, updated_at, deleted_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)"""
        )
    }

    private val updateByHabitAndTimestampStmt by lazy {
        db.prepareStatement(
            """UPDATE Repetitions SET
               uuid = ?,
               value = ?,
               notes = ?,
               updated_at = ?,
               deleted_at = ?
               WHERE habit = ? AND timestamp = ?"""
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

    @Synchronized
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

    @Synchronized
    fun upsert(data: EntryData): Long {
        if (data.uuid.isBlank()) data.uuid = "${data.habitId}:${data.timestamp}"

        // 1. Try update first by habit and timestamp
        updateByHabitAndTimestampStmt.reset()
        updateByHabitAndTimestampStmt.bindText(1, data.uuid)
        updateByHabitAndTimestampStmt.bindInt(2, data.value)
        updateByHabitAndTimestampStmt.bindText(3, data.notes)
        updateByHabitAndTimestampStmt.bindLong(4, data.updatedAt)
        if (data.deletedAt != null) updateByHabitAndTimestampStmt.bindLong(5, data.deletedAt!!) else updateByHabitAndTimestampStmt.bindNull(5)
        updateByHabitAndTimestampStmt.bindLong(6, data.habitId!!)
        updateByHabitAndTimestampStmt.bindLong(7, data.timestamp)
        updateByHabitAndTimestampStmt.step()

        if (db.queryInt("SELECT changes()") == 0) {
            // 2. Row does not exist yet: try insert or ignore
            insertOrIgnoreStmt.reset()
            insertOrIgnoreStmt.bindLong(1, data.habitId!!)
            insertOrIgnoreStmt.bindText(2, data.uuid)
            insertOrIgnoreStmt.bindLong(3, data.timestamp)
            insertOrIgnoreStmt.bindInt(4, data.value)
            insertOrIgnoreStmt.bindText(5, data.notes)
            insertOrIgnoreStmt.bindLong(6, data.updatedAt)
            if (data.deletedAt != null) insertOrIgnoreStmt.bindLong(7, data.deletedAt!!) else insertOrIgnoreStmt.bindNull(7)
            insertOrIgnoreStmt.step()

            if (db.queryInt("SELECT changes()") == 0) {
                // Another connection inserted concurrently: update the existing row
                updateByHabitAndTimestampStmt.reset()
                updateByHabitAndTimestampStmt.bindText(1, data.uuid)
                updateByHabitAndTimestampStmt.bindInt(2, data.value)
                updateByHabitAndTimestampStmt.bindText(3, data.notes)
                updateByHabitAndTimestampStmt.bindLong(4, data.updatedAt)
                if (data.deletedAt != null) updateByHabitAndTimestampStmt.bindLong(5, data.deletedAt!!) else updateByHabitAndTimestampStmt.bindNull(5)
                updateByHabitAndTimestampStmt.bindLong(6, data.habitId!!)
                updateByHabitAndTimestampStmt.bindLong(7, data.timestamp)
                updateByHabitAndTimestampStmt.step()
            }
        }

        findIdByHabitAndTimestampStmt.reset()
        findIdByHabitAndTimestampStmt.bindLong(1, data.habitId!!)
        findIdByHabitAndTimestampStmt.bindLong(2, data.timestamp)
        return if (findIdByHabitAndTimestampStmt.step() == StepResult.ROW) {
            findIdByHabitAndTimestampStmt.getLong(0)
        } else {
            -1L
        }
    }

    @Synchronized
    fun insert(data: EntryData): Long = upsert(data)

    @Synchronized
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

    @Synchronized
    fun softDeleteByHabitIdAndTimestamp(habitId: Long, timestamp: Long, deletedAt: Long) {
        softDeleteByHabitAndTimestampStmt.reset()
        softDeleteByHabitAndTimestampStmt.bindLong(1, deletedAt)
        softDeleteByHabitAndTimestampStmt.bindLong(2, deletedAt)
        softDeleteByHabitAndTimestampStmt.bindLong(3, habitId)
        softDeleteByHabitAndTimestampStmt.bindLong(4, timestamp)
        softDeleteByHabitAndTimestampStmt.step()
    }

    @Synchronized
    fun deleteByHabitIdAndTimestamp(habitId: Long, timestamp: Long) =
        softDeleteByHabitIdAndTimestamp(habitId, timestamp, deletedAt = 0)

    @Synchronized
    fun softDeleteByHabitId(habitId: Long, deletedAt: Long) {
        softDeleteByHabitStmt.reset()
        softDeleteByHabitStmt.bindLong(1, deletedAt)
        softDeleteByHabitStmt.bindLong(2, deletedAt)
        softDeleteByHabitStmt.bindLong(3, habitId)
        softDeleteByHabitStmt.step()
    }

    @Synchronized
    fun deleteByHabitId(habitId: Long) = softDeleteByHabitId(habitId, deletedAt = 0)

    @Synchronized
    fun execSQL(sql: String) = db.run(sql)
}
