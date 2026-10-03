/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.session

import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.isoron.uhabits.sync.SupabaseSyncConfig
import org.isoron.uhabits.sync.SyncBackend
import org.isoron.uhabits.sync.SyncPullResult
import org.isoron.uhabits.sync.SyncPushResult
import org.isoron.uhabits.sync.SyncSession

/**
 * A hard-disabled [SyncBackend] implementation injected during experimental dataset sessions.
 * Guarantees zero network calls by throwing [IllegalStateException] on any attempted operation.
 */
object DisabledSyncBackend : SyncBackend {

    override suspend fun signIn(config: SupabaseSyncConfig, email: String, password: String): SyncSession {
        throw IllegalStateException("Remote calls are disabled in experimental dataset session")
    }

    override suspend fun refreshSession(config: SupabaseSyncConfig, session: SyncSession): SyncSession {
        throw IllegalStateException("Remote calls are disabled in experimental dataset session")
    }

    override suspend fun signOut(session: SyncSession, config: SupabaseSyncConfig) {
        // Safe no-op for sign out in disabled mode
    }

    override suspend fun pushChanges(
        config: SupabaseSyncConfig,
        session: SyncSession,
        deviceId: String,
        records: List<SyncQueueRecord>
    ): SyncPushResult {
        throw IllegalStateException("Remote calls are disabled in experimental dataset session")
    }

    override suspend fun pullChanges(
        config: SupabaseSyncConfig,
        session: SyncSession,
        lastLogId: Long
    ): SyncPullResult {
        throw IllegalStateException("Remote calls are disabled in experimental dataset session")
    }
}
