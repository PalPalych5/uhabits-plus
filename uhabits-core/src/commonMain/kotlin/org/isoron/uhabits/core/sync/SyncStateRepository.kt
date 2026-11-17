/* SPDX-License-Identifier: GPL-3.0-or-later */

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
