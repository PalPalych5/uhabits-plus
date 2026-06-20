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

data class HabitBlockData(
    val id: Long? = null,
    val name: String,
    val color: Int,
    val icon: String? = null,
    val position: Int = 0,
    val isArchived: Boolean = false
)

class HabitBlockRepository(private val db: Database) {
    private val findAllStmt by lazy {
        db.prepareStatement(
            "SELECT id, name, color, icon, position, is_archived FROM HabitBlocks ORDER BY position"
        )
    }
    private val findByIdStmt by lazy {
        db.prepareStatement(
            "SELECT id, name, color, icon, position, is_archived FROM HabitBlocks WHERE id = ?"
        )
    }
    private val insertStmt by lazy {
        db.prepareStatement(
            "INSERT INTO HabitBlocks(name, color, icon, position, is_archived) VALUES (?, ?, ?, ?, ?)"
        )
    }
    private val updateStmt by lazy {
        db.prepareStatement(
            "UPDATE HabitBlocks SET name = ?, color = ?, icon = ?, position = ?, is_archived = ? WHERE id = ?"
        )
    }
    private val deleteStmt by lazy {
        db.prepareStatement("DELETE FROM HabitBlocks WHERE id = ?")
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

    fun insert(data: HabitBlockData): Long {
        insertStmt.reset()
        bindForInsert(insertStmt, data)
        insertStmt.step()
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun update(data: HabitBlockData) {
        updateStmt.reset()
        bindForInsert(updateStmt, data)
        updateStmt.bindLong(6, data.id!!)
        updateStmt.step()
    }

    fun delete(id: Long) {
        deleteStmt.reset()
        deleteStmt.bindLong(1, id)
        deleteStmt.step()
    }

    fun execSQL(sql: String) = db.run(sql)

    private fun bindForInsert(stmt: PreparedStatement, data: HabitBlockData) {
        stmt.bindText(1, data.name)
        stmt.bindInt(2, data.color)
        if (data.icon != null) stmt.bindText(3, data.icon) else stmt.bindNull(3)
        stmt.bindInt(4, data.position)
        stmt.bindInt(5, if (data.isArchived) 1 else 0)
    }

    private fun readRow(stmt: PreparedStatement): HabitBlockData {
        return HabitBlockData(
            id = stmt.getLong(0),
            name = stmt.getText(1),
            color = stmt.getInt(2),
            icon = stmt.getTextOrNull(3),
            position = stmt.getInt(4),
            isArchived = stmt.getInt(5) != 0
        )
    }
}
