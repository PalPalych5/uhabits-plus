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
import org.isoron.platform.io.queryInt
import org.isoron.uhabits.core.containers.HabitVisualIdentityStore
import org.isoron.uhabits.core.containers.IconRef

class SQLiteHabitVisualIdentityStore(
    private val db: Database
) : HabitVisualIdentityStore {

    private val cache = mutableMapOf<String, IconRef?>()

    override fun get(habitUuid: String): IconRef? {
        synchronized(cache) {
            if (cache.containsKey(habitUuid)) return cache[habitUuid]
        }
        val stmt = db.prepareStatement(
            "SELECT icon_type, icon_value FROM HabitVisualIdentities WHERE habit_uuid = ?"
        )
        val result = try {
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
        synchronized(cache) {
            cache[habitUuid] = result
        }
        return result
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
            synchronized(cache) {
                result.forEach { (k, v) -> cache[k] = v }
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
        synchronized(cache) {
            cache[habitUuid] = iconRef
        }
        val type = when (iconRef) {
            is IconRef.Vector -> "VECTOR"
            is IconRef.Emoji -> "EMOJI"
            is IconRef.None -> return
        }
        val value = iconRef.key
        // Use safe transactional UPDATE then INSERT OR IGNORE strategy (compatible with SQLite 3.22.0)
        // to avoid INSERT OR REPLACE deleting and re-inserting rows, triggering DELETE triggers or FK side-effects.
        val updateStmt = db.prepareStatement(
            "UPDATE HabitVisualIdentities SET icon_type = ?, icon_value = ? WHERE habit_uuid = ?"
        )
        try {
            updateStmt.bindText(1, type)
            updateStmt.bindText(2, value)
            updateStmt.bindText(3, habitUuid)
            updateStmt.step()
        } finally {
            updateStmt.finalize()
        }

        if (db.queryInt("SELECT changes()") == 0) {
            val insertStmt = db.prepareStatement(
                "INSERT OR IGNORE INTO HabitVisualIdentities(habit_uuid, icon_type, icon_value) VALUES(?, ?, ?)"
            )
            try {
                insertStmt.bindText(1, habitUuid)
                insertStmt.bindText(2, type)
                insertStmt.bindText(3, value)
                insertStmt.step()
            } finally {
                insertStmt.finalize()
            }

            if (db.queryInt("SELECT changes()") == 0) {
                // If inserted concurrently between UPDATE and INSERT OR IGNORE, update again
                val retryUpdateStmt = db.prepareStatement(
                    "UPDATE HabitVisualIdentities SET icon_type = ?, icon_value = ? WHERE habit_uuid = ?"
                )
                try {
                    retryUpdateStmt.bindText(1, type)
                    retryUpdateStmt.bindText(2, value)
                    retryUpdateStmt.bindText(3, habitUuid)
                    retryUpdateStmt.step()
                } finally {
                    retryUpdateStmt.finalize()
                }
            }
        }
    }

    override fun remove(habitUuid: String) {
        synchronized(cache) {
            cache[habitUuid] = null
        }
        val stmt = db.prepareStatement("DELETE FROM HabitVisualIdentities WHERE habit_uuid = ?")
        try {
            stmt.bindText(1, habitUuid)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }
}
