/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.core.containers.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.uhabits.core.containers.HabitVisualIdentityStore
import org.isoron.uhabits.core.containers.IconRef

class SQLiteHabitVisualIdentityStore(
    private val db: Database
) : HabitVisualIdentityStore {

    override fun get(habitUuid: String): IconRef? {
        val stmt = db.prepareStatement(
            "SELECT icon_type, icon_value FROM HabitVisualIdentities WHERE habit_uuid = ?"
        )
        return try {
            stmt.bindText(1, habitUuid)
            if (stmt.step() == StepResult.ROW) {
                val type = stmt.getText(0)
                val value = stmt.getText(1)
                when (type) {
                    "VECTOR" -> IconRef.Vector(value)
                    "EMOJI" -> IconRef.Emoji(value)
                    else -> IconRef.None
                }
            } else {
                null
            }
        } finally {
            stmt.finalize()
        }
    }

    override fun getAll(): Map<String, IconRef> {
        val stmt = db.prepareStatement("SELECT habit_uuid, icon_type, icon_value FROM HabitVisualIdentities")
        val result = mutableMapOf<String, IconRef>()
        return try {
            while (stmt.step() == StepResult.ROW) {
                val uuid = stmt.getText(0)
                val type = stmt.getText(1)
                val value = stmt.getText(2)
                val ref = when (type) {
                    "VECTOR" -> IconRef.Vector(value)
                    "EMOJI" -> IconRef.Emoji(value)
                    else -> IconRef.None
                }
                result[uuid] = ref
            }
            result
        } finally {
            stmt.finalize()
        }
    }

    override fun set(habitUuid: String, iconRef: IconRef) {
        if (iconRef is IconRef.None) {
            remove(habitUuid)
            return
        }
        val type = when (iconRef) {
            is IconRef.Vector -> "VECTOR"
            is IconRef.Emoji -> "EMOJI"
            is IconRef.None -> return
        }
        val value = iconRef.key
        val stmt = db.prepareStatement(
            """
            INSERT INTO HabitVisualIdentities(habit_uuid, icon_type, icon_value)
            VALUES(?, ?, ?)
            ON CONFLICT(habit_uuid) DO UPDATE SET icon_type = excluded.icon_type, icon_value = excluded.icon_value
            """.trimIndent()
        )
        try {
            stmt.bindText(1, habitUuid)
            stmt.bindText(2, type)
            stmt.bindText(3, value)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    override fun remove(habitUuid: String) {
        val stmt = db.prepareStatement("DELETE FROM HabitVisualIdentities WHERE habit_uuid = ?")
        try {
            stmt.bindText(1, habitUuid)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }
}
