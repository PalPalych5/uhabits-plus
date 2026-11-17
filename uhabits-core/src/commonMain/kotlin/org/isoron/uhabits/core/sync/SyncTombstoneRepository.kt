/* SPDX-License-Identifier: GPL-3.0-or-later */

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
