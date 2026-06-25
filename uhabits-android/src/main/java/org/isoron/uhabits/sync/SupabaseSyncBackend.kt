package org.isoron.uhabits.sync

import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SupabaseSyncBackend : SyncBackend {
    override suspend fun signIn(
        config: SupabaseSyncConfig,
        email: String,
        password: String
    ): SyncSession {
        val response = request(
            method = "POST",
            url = authUrl(config, "token?grant_type=password"),
            headers = apiKeyHeaders(config),
            body = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()
        )
        return parseSession(response)
    }

    override suspend fun refreshSession(
        config: SupabaseSyncConfig,
        session: SyncSession
    ): SyncSession {
        val response = request(
            method = "POST",
            url = authUrl(config, "token?grant_type=refresh_token"),
            headers = apiKeyHeaders(config),
            body = JSONObject()
                .put("refresh_token", session.refreshToken)
                .toString()
        )
        return parseSession(response)
    }

    override suspend fun signOut(session: SyncSession, config: SupabaseSyncConfig) {
        val response = request(
            method = "POST",
            url = authUrl(config, "logout"),
            headers = authHeaders(config, session.accessToken),
            body = "{}"
        )
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

        val response = request(
            method = "POST",
            url = restUrl(config, "sync_log"),
            headers = authHeaders(config, session.accessToken) +
                ("Prefer" to "resolution=merge-duplicates,return=minimal"),
            body = body.toString()
        )
        ensureSuccess(response)
        return SyncPushResult(records.mapNotNull { it.queueId })
    }

    override suspend fun pullChanges(
        config: SupabaseSyncConfig,
        session: SyncSession,
        lastLogId: Long
    ): SyncPullResult {
        val response = request(
            method = "GET",
            url = "${restUrl(config, "sync_log")}?select=log_id,op_uuid,entity_type,entity_uuid,operation_type,payload_json,created_at,device_id&user_id=eq.${session.userId}&log_id=gt.$lastLogId&order=log_id.asc",
            headers = authHeaders(config, session.accessToken)
        )
        ensureSuccess(response)
        val rows = runCatching { JSONArray(response.body) }.getOrElse { cause ->
            throw invalidSyncLogResponse("expected a JSON array", response.body, cause)
        }
        val events = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i)
                    ?: throw invalidSyncLogResponse("row $i is not an object", response.body)
                add(
                    RemoteSyncEvent(
                        logId = row.requiredLong("log_id", i, response.body),
                        opUuid = row.requiredString("op_uuid", i, response.body),
                        entityType = row.requiredString("entity_type", i, response.body),
                        entityUuid = row.requiredString("entity_uuid", i, response.body),
                        operationType = row.requiredString("operation_type", i, response.body),
                        payloadJson = row.requiredObject("payload_json", i, response.body).toString(),
                        createdAt = row.requiredLong("created_at", i, response.body),
                        deviceId = row.requiredString("device_id", i, response.body)
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
        val response = request(
            method = "POST",
            url = restUrl(config, "devices"),
            headers = authHeaders(config, session.accessToken) +
                ("Prefer" to "resolution=merge-duplicates,return=minimal"),
            body = JSONArray()
                .put(
                    JSONObject()
                        .put("user_id", session.userId)
                        .put("device_id", deviceId)
                        .put("last_seen_at", System.currentTimeMillis())
                )
                .toString()
        )
        ensureSuccess(response)
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null
    ): HttpResponseData {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw networkFailure(e)
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponseData(
                status = status,
                body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            )
        } catch (e: IOException) {
            throw networkFailure(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccess(response: HttpResponseData) {
        if (response.status in 200..299) return
        val detail = runCatching {
            val json = JSONObject(response.body)
            listOf("message", "msg", "error_description", "error", "code")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
        }.getOrNull() ?: response.body.takeIf { it.isNotBlank() } ?: "No response body"
        throw IllegalStateException("Supabase request failed (${response.status}): $detail")
    }

    private fun parseSession(response: HttpResponseData): SyncSession {
        ensureSuccess(response)
        val json = JSONObject(response.body)
        val user = json.optJSONObject("user")
        return SyncSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            userId = user?.getString("id") ?: throw IllegalStateException("Missing user id"),
            email = user?.optString("email"),
            expiresAtEpochSeconds = json.optLong("expires_at", 0L)
        )
    }

    private fun apiKeyHeaders(config: SupabaseSyncConfig): Map<String, String> =
        mapOf("apikey" to config.anonKey)

    private fun authHeaders(config: SupabaseSyncConfig, accessToken: String): Map<String, String> =
        mapOf(
            "apikey" to config.anonKey,
            "Authorization" to "Bearer $accessToken"
        )

    private fun networkFailure(cause: IOException): IllegalStateException =
        IllegalStateException(
            "Supabase network request failed: ${cause.message ?: cause::class.simpleName}",
            cause
        )

    private fun JSONObject.requiredLong(field: String, row: Int, responseBody: String): Long {
        if (!has(field) || isNull(field)) {
            throw invalidSyncLogResponse("required field '$field' is null or missing at row $row", responseBody)
        }
        val value = get(field)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: throw invalidSyncLogResponse(
            "required field '$field' is not a valid long at row $row (value=$value)",
            responseBody
        )
    }

    private fun JSONObject.requiredString(field: String, row: Int, responseBody: String): String {
        if (!has(field) || isNull(field)) {
            throw invalidSyncLogResponse("required field '$field' is null or missing at row $row", responseBody)
        }
        return getString(field).takeIf { it.isNotBlank() && it != "null" }
            ?: throw invalidSyncLogResponse("required field '$field' is empty at row $row", responseBody)
    }

    private fun JSONObject.requiredObject(field: String, row: Int, responseBody: String): JSONObject {
        if (!has(field) || isNull(field)) {
            throw invalidSyncLogResponse("required field '$field' is null or missing at row $row", responseBody)
        }
        return optJSONObject(field)
            ?: throw invalidSyncLogResponse("required field '$field' is not an object at row $row", responseBody)
    }

    private fun invalidSyncLogResponse(
        detail: String,
        responseBody: String,
        cause: Throwable? = null
    ): IllegalStateException = IllegalStateException(
        "Invalid Supabase GET /rest/v1/sync_log response: $detail; body=${responseBody.take(1000)}",
        cause
    )

    private fun restUrl(config: SupabaseSyncConfig, table: String): String =
        "${config.baseUrl.trimEnd('/')}/rest/v1/$table"

    private fun authUrl(config: SupabaseSyncConfig, suffix: String): String =
        "${config.baseUrl.trimEnd('/')}/auth/v1/$suffix"

    private data class HttpResponseData(val status: Int, val body: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
