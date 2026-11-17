/* SPDX-License-Identifier: GPL-3.0-or-later */

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
