package org.isoron.uhabits.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.json.JSONArray
import org.json.JSONObject

class SupabaseSyncBackend : SyncBackend {
    private val client by lazy { HttpClient(Android) }

    override suspend fun signIn(
        config: SupabaseSyncConfig,
        email: String,
        password: String
    ): SyncSession {
        val response = client.post<HttpResponse>(authUrl(config, "token?grant_type=password")) {
            header("apikey", config.anonKey)
            body = TextContent(
                JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .toString(),
                ContentType.Application.Json
            )
        }
        return parseSession(response)
    }

    override suspend fun refreshSession(
        config: SupabaseSyncConfig,
        session: SyncSession
    ): SyncSession {
        val response = client.post<HttpResponse>(authUrl(config, "token?grant_type=refresh_token")) {
            header("apikey", config.anonKey)
            body = TextContent(
                JSONObject()
                    .put("refresh_token", session.refreshToken)
                    .toString(),
                ContentType.Application.Json
            )
        }
        return parseSession(response)
    }

    override suspend fun signOut(session: SyncSession, config: SupabaseSyncConfig) {
        val response = client.post<HttpResponse>(authUrl(config, "logout")) {
            applyAuthHeaders(config, session.accessToken)
            body = TextContent("{}", ContentType.Application.Json)
        }
        ensureSuccess(response)
    }

    override suspend fun pushChanges(
        config: SupabaseSyncConfig,
        session: SyncSession,
        deviceId: String,
        records: List<SyncQueueRecord>
    ): SyncPushResult {
        if (records.isEmpty()) return SyncPushResult(emptyList())

        upsertDevice(config, session, deviceId)

        val body = JSONArray()
        records.forEach { record ->
            body.put(
                JSONObject()
                    .put("user_id", session.userId)
                    .put("device_id", deviceId)
                    .put("op_uuid", record.opUuid)
                    .put("entity_type", record.entityType)
                    .put("entity_uuid", record.entityUuid)
                    .put("operation_type", record.operationType)
                    .put("payload_json", JSONObject(record.payloadJson))
                    .put("created_at", record.createdAt)
            )
        }

        val response = client.post<HttpResponse>(restUrl(config, "sync_log")) {
            applyAuthHeaders(config, session.accessToken)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            this.body = TextContent(body.toString(), ContentType.Application.Json)
        }
        ensureSuccess(response)
        return SyncPushResult(records.mapNotNull { it.queueId })
    }

    override suspend fun pullChanges(
        config: SupabaseSyncConfig,
        session: SyncSession,
        lastLogId: Long
    ): SyncPullResult {
        val response = client.get<HttpResponse>(
            "${restUrl(config, "sync_log")}?select=log_id,op_uuid,entity_type,entity_uuid,operation_type,payload_json,created_at,device_id&user_id=eq.${session.userId}&log_id=gt.$lastLogId&order=log_id.asc"
        ) {
            applyAuthHeaders(config, session.accessToken)
        }
        ensureSuccess(response)
        val rows = JSONArray(response.readText())
        val events = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                add(
                    RemoteSyncEvent(
                        logId = row.getLong("log_id"),
                        opUuid = row.getString("op_uuid"),
                        entityType = row.getString("entity_type"),
                        entityUuid = row.getString("entity_uuid"),
                        operationType = row.getString("operation_type"),
                        payloadJson = row.optJSONObject("payload_json")?.toString() ?: "{}",
                        createdAt = row.getLong("created_at"),
                        deviceId = row.getString("device_id")
                    )
                )
            }
        }
        return SyncPullResult(
            events = events,
            latestLogId = events.lastOrNull()?.logId ?: lastLogId
        )
    }

    private suspend fun upsertDevice(
        config: SupabaseSyncConfig,
        session: SyncSession,
        deviceId: String
    ) {
        val response = client.post<HttpResponse>(restUrl(config, "devices")) {
            applyAuthHeaders(config, session.accessToken)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            body = TextContent(
                JSONArray()
                    .put(
                        JSONObject()
                            .put("user_id", session.userId)
                            .put("device_id", deviceId)
                            .put("last_seen_at", System.currentTimeMillis())
                    )
                    .toString(),
                ContentType.Application.Json
            )
        }
        ensureSuccess(response)
    }

    private fun HttpRequestBuilder.applyAuthHeaders(config: SupabaseSyncConfig, accessToken: String) {
        header("apikey", config.anonKey)
        header("Authorization", "Bearer $accessToken")
        header("Content-Type", "application/json")
    }

    private suspend fun HttpResponse.throwIfError() {
        if (status.value in 200..299) return
        val body = runCatching { readText() }.getOrNull()
        throw IllegalStateException(body ?: "Supabase request failed with ${status.value}")
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        response.throwIfError()
    }

    private suspend fun parseSession(response: HttpResponse): SyncSession {
        ensureSuccess(response)
        val json = JSONObject(response.readText())
        val user = json.optJSONObject("user")
        return SyncSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            userId = user?.getString("id") ?: throw IllegalStateException("Missing user id"),
            email = user?.optString("email"),
            expiresAtEpochSeconds = json.optLong("expires_at", 0L)
        )
    }

    private fun restUrl(config: SupabaseSyncConfig, table: String): String =
        "${config.baseUrl.trimEnd('/')}/rest/v1/$table"

    private fun authUrl(config: SupabaseSyncConfig, suffix: String): String =
        "${config.baseUrl.trimEnd('/')}/auth/v1/$suffix"
}
