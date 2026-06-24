/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class HabitBlockData(
    val id: Long? = null,
    val name: String,
    val color: Int,
    val icon: String? = null,
    val position: Int = 0,
    val isArchived: Boolean = false,
    val uuid: String? = null,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null
)

class HabitBlockRepository(private val db: Database) {
    private val findAllStmt by lazy {
        db.prepareStatement(
            """SELECT id, name, color, icon, position, is_archived, uuid, updated_at, deleted_at
               FROM HabitBlocks WHERE deleted_at IS NULL ORDER BY position"""
        )
    }
    private val findByIdStmt by lazy {
        db.prepareStatement(
            """SELECT id, name, color, icon, position, is_archived, uuid, updated_at, deleted_at
               FROM HabitBlocks WHERE id = ? AND deleted_at IS NULL"""
        )
    }
    private val insertStmt by lazy {
        db.prepareStatement(
            """INSERT INTO HabitBlocks(name, color, icon, position, is_archived, uuid, updated_at, deleted_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
        )
    }
    private val updateStmt by lazy {
        db.prepareStatement(
            """UPDATE HabitBlocks
               SET name = ?, color = ?, icon = ?, position = ?, is_archived = ?, uuid = ?, updated_at = ?, deleted_at = ?
               WHERE id = ?"""
        )
    }
    private val findByUuidStmt by lazy {
        db.prepareStatement(
            """SELECT id, name, color, icon, position, is_archived, uuid, updated_at, deleted_at
               FROM HabitBlocks WHERE uuid = ? LIMIT 1"""
        )
    }

    fun findAll(): List<HabitBlockData> {
        findAllStmt.reset()
        val results = mutableListOf<HabitBlockData>()
        while (findAllStmt.step() == StepResult.ROW) {
            results.add(readRow(findAllStmt))
        }
        return results
    }

    fun findById(id: Long): HabitBlockData? {
        findByIdStmt.reset()
        findByIdStmt.bindLong(1, id)
        if (findByIdStmt.step() != StepResult.ROW) return null
        return readRow(findByIdStmt)
    }

    fun findByUuid(uuid: String): HabitBlockData? {
        findByUuidStmt.reset()
        findByUuidStmt.bindText(1, uuid)
        if (findByUuidStmt.step() != StepResult.ROW) return null
        return readRow(findByUuidStmt)
    }

    fun insert(data: HabitBlockData): Long {
        insertStmt.reset()
        bindForInsert(insertStmt, normalizeForWrite(data))
        insertStmt.step()
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun update(data: HabitBlockData) {
        updateStmt.reset()
        val normalized = normalizeForWrite(data)
        bindForInsert(updateStmt, normalized)
        updateStmt.bindLong(9, normalized.id!!)
        updateStmt.step()
    }

    fun softDelete(id: Long, deletedAt: Long) {
        val current = findById(id) ?: return
        update(
            current.copy(
                updatedAt = deletedAt,
                deletedAt = deletedAt
            )
        )
    }

    fun delete(id: Long) = softDelete(id, deletedAt = 0)

    fun execSQL(sql: String) = db.run(sql)

    private fun bindForInsert(stmt: PreparedStatement, data: HabitBlockData) {
        stmt.bindText(1, data.name)
        stmt.bindInt(2, data.color)
        if (data.icon != null) stmt.bindText(3, data.icon) else stmt.bindNull(3)
        stmt.bindInt(4, data.position)
        stmt.bindInt(5, if (data.isArchived) 1 else 0)
        if (data.uuid != null) stmt.bindText(6, data.uuid) else stmt.bindNull(6)
        stmt.bindLong(7, data.updatedAt)
        if (data.deletedAt != null) stmt.bindLong(8, data.deletedAt) else stmt.bindNull(8)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun normalizeForWrite(data: HabitBlockData): HabitBlockData {
        if (!data.uuid.isNullOrBlank()) return data
        return data.copy(uuid = Uuid.random().toHexString())
    }

    private fun readRow(stmt: PreparedStatement): HabitBlockData {
        return HabitBlockData(
            id = stmt.getLong(0),
            name = stmt.getText(1),
            color = stmt.getInt(2),
            icon = stmt.getTextOrNull(3),
            position = stmt.getInt(4),
            isArchived = stmt.getInt(5) != 0,
            uuid = stmt.getTextOrNull(6),
            updatedAt = stmt.getLong(7),
            deletedAt = stmt.getLongOrNull(8)
        )
    }
}
