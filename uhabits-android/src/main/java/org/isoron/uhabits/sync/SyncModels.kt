package org.isoron.uhabits.sync

import org.isoron.uhabits.core.sync.SyncQueueRecord

data class SyncSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String?,
    val expiresAtEpochSeconds: Long
)

data class SupabaseSyncConfig(
    val baseUrl: String,
    val anonKey: String
)

data class RemoteSyncEvent(
    val logId: Long,
    val opUuid: String,
    val entityType: String,
    val entityUuid: String,
    val operationType: String,
    val payloadJson: String,
    val createdAt: Long,
    val deviceId: String
)

data class SyncPullResult(
    val events: List<RemoteSyncEvent>,
    val latestLogId: Long
)

data class SyncPushResult(
    val pushedQueueIds: List<Long>
)

sealed class SyncRunResult {
    data class Success(val pushed: Int, val pulled: Int) : SyncRunResult()
    data class Failure(val userMessage: String, val technicalMessage: String? = null) : SyncRunResult()
    data class Skipped(val reason: String) : SyncRunResult()
}

interface SyncBackend {
    suspend fun signIn(config: SupabaseSyncConfig, email: String, password: String): SyncSession
    suspend fun refreshSession(config: SupabaseSyncConfig, session: SyncSession): SyncSession
    suspend fun signOut(session: SyncSession, config: SupabaseSyncConfig)
    suspend fun pushChanges(
        config: SupabaseSyncConfig,
        session: SyncSession,
        deviceId: String,
        records: List<SyncQueueRecord>
    ): SyncPushResult

    suspend fun pullChanges(
        config: SupabaseSyncConfig,
        session: SyncSession,
        lastLogId: Long
    ): SyncPullResult
}
