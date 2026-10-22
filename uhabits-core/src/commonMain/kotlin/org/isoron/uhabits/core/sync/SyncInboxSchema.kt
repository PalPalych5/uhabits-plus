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
import org.isoron.platform.io.run

object SyncInboxSchema {
    val DDL_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS SyncState (
            key TEXT PRIMARY KEY NOT NULL,
            value TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS SyncInbox (
            log_id INTEGER PRIMARY KEY NOT NULL,
            device_id TEXT NOT NULL,
            op_uuid TEXT NOT NULL UNIQUE,
            entity_type TEXT NOT NULL,
            entity_uuid TEXT NOT NULL,
            operation_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            received_at INTEGER NOT NULL,
            deferred_reason TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_attempt_at INTEGER
        );
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_sync_inbox_entity ON SyncInbox(entity_type, entity_uuid);",
        "CREATE INDEX IF NOT EXISTS idx_sync_inbox_pending ON SyncInbox(status, deferred_reason);",
        """
        CREATE TABLE IF NOT EXISTS SyncTombstones (
            entity_type TEXT NOT NULL,
            entity_uuid TEXT NOT NULL,
            deleted_at INTEGER NOT NULL,
            PRIMARY KEY (entity_type, entity_uuid)
        );
        """.trimIndent()
    )

    fun createSchema(db: Database) {
        for (stmt in DDL_STATEMENTS) {
            db.run(stmt)
        }
    }
}
