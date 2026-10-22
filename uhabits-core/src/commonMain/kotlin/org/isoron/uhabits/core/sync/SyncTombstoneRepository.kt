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

package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database
import org.isoron.platform.io.querySingle

class SyncTombstoneRepository(private val db: Database) {

    fun recordTombstone(entityType: String, entityUuid: String, deletedAt: Long) {
        val stmt = db.prepareStatement(
            """INSERT OR REPLACE INTO SyncTombstones (entity_type, entity_uuid, deleted_at)
               VALUES (?, ?, ?)"""
        )
        try {
            stmt.bindText(1, entityType)
            stmt.bindText(2, entityUuid)
            stmt.bindLong(3, deletedAt)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    fun getDeletedAt(entityType: String, entityUuid: String): Long? {
        return db.querySingle(
            "SELECT deleted_at FROM SyncTombstones WHERE entity_type = ? AND entity_uuid = ?",
            entityType,
            entityUuid
        ) { it.getLong(0) }
    }

    fun isDeleted(entityType: String, entityUuid: String, beforeOrAtTimestamp: Long): Boolean {
        val deletedAt = getDeletedAt(entityType, entityUuid) ?: return false
        return deletedAt >= beforeOrAtTimestamp
    }

    fun clearAll() {
        val stmt = db.prepareStatement("DELETE FROM SyncTombstones")
        try {
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }
}
