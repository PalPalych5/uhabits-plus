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

class SyncStateRepository(private val db: Database) {

    fun getCursor(): Long? {
        val raw = db.querySingle("SELECT value FROM SyncState WHERE key = 'pull_cursor'") {
            it.getText(0)
        }
        return raw?.toLongOrNull()
    }

    fun setCursor(cursor: Long, now: Long = 0L) {
        val stmt = db.prepareStatement(
            "INSERT OR REPLACE INTO SyncState (key, value, updated_at) VALUES ('pull_cursor', ?, ?)"
        )
        try {
            stmt.bindText(1, cursor.toString())
            stmt.bindLong(2, now)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    fun getAccountUserId(): String? {
        return db.querySingle("SELECT value FROM SyncState WHERE key = 'account_user_id'") {
            it.getText(0)
        }
    }

    fun setAccountUserId(userId: String, now: Long = 0L) {
        val stmt = db.prepareStatement(
            "INSERT OR REPLACE INTO SyncState (key, value, updated_at) VALUES ('account_user_id', ?, ?)"
        )
        try {
            stmt.bindText(1, userId)
            stmt.bindLong(2, now)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    fun clear(now: Long = 0L) {
        val stmt = db.prepareStatement(
            "INSERT OR REPLACE INTO SyncState (key, value, updated_at) VALUES ('pull_cursor', '0', ?)"
        )
        try {
            stmt.bindLong(1, now)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }
}
