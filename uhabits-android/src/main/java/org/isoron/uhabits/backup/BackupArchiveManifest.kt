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

package org.isoron.uhabits.backup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class TableInventoryEntry(
    val tableName: String,
    val rowCount: Long
)

data class BackupArchiveManifest(
    val archiveFormat: String = BackupArchiveFormat.ARCHIVE_FORMAT,
    val formatVersion: Int = BackupArchiveFormat.CURRENT_FORMAT_VERSION,
    val appId: String = "org.isoron.uhabits",
    val appVersionCode: Int = 0,
    val createdAt: Long,
    val databaseVersion: Int,
    val databaseSha256: String,
    val databaseSizeBytes: Long,
    val datasetMode: String,
    val datasetId: String? = null,
    val tableInventory: List<TableInventoryEntry>,
    val quickCheckResult: String = "ok",
    val foreignKeyCheckResult: String = "ok",
    val canonicalContentSha256: String,
    val minSupportedAppVersion: Int = 1,
    val maxSupportedSchemaVersion: Int = 30,
    val runtimeAuthorityMode: String = "LEGACY_PRODUCTION",
    val restorePolicy: String = "FAIL_CLOSED_ON_INCOMPATIBLE_AUTHORITY"
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("archive_format", archiveFormat)
        root.put("format_version", formatVersion)
        root.put("app_id", appId)
        root.put("app_version_code", appVersionCode)
        root.put("created_at", createdAt)

        val dbObj = JSONObject()
        dbObj.put("filename", BackupArchiveFormat.SQLITE_ENTRY_NAME)
        dbObj.put("version", databaseVersion)
        dbObj.put("sha256", databaseSha256)
        dbObj.put("size_bytes", databaseSizeBytes)
        dbObj.put("dataset_mode", datasetMode)
        if (datasetId != null) dbObj.put("dataset_id", datasetId)
        root.put("database", dbObj)

        val schemaObj = JSONObject()
        schemaObj.put("total_tables", tableInventory.size)
        val tablesArr = JSONArray()
        for (entry in tableInventory) {
            val tObj = JSONObject()
            tObj.put("name", entry.tableName)
            tObj.put("row_count", entry.rowCount)
            tablesArr.put(tObj)
        }
        schemaObj.put("tables", tablesArr)
        root.put("schema_inventory", schemaObj)

        val integrityObj = JSONObject()
        integrityObj.put("quick_check", quickCheckResult)
        integrityObj.put("foreign_key_check", foreignKeyCheckResult)
        integrityObj.put("canonical_content_sha256", canonicalContentSha256)
        root.put("integrity", integrityObj)

        val compatObj = JSONObject()
        compatObj.put("min_supported_schema_version", minSupportedAppVersion)
        compatObj.put("max_supported_schema_version", maxSupportedSchemaVersion)
        compatObj.put("runtime_authority_required", runtimeAuthorityMode)
        compatObj.put("restore_policy", restorePolicy)
        root.put("compatibility", compatObj)

        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): BackupArchiveManifest {
            val root = JSONObject(jsonStr)
            val archiveFormat = root.getString("archive_format")
            val formatVersion = root.getInt("format_version")
            val appId = root.optString("app_id", "org.isoron.uhabits")
            val appVersionCode = root.optInt("app_version_code", 0)
            val createdAt = root.getLong("created_at")

            val dbObj = root.getJSONObject("database")
            val databaseVersion = dbObj.getInt("version")
            val databaseSha256 = dbObj.getString("sha256")
            val databaseSizeBytes = dbObj.getLong("size_bytes")
            val datasetMode = dbObj.optString("dataset_mode", "LEGACY_PRODUCTION")
            val datasetId = if (dbObj.has("dataset_id")) dbObj.getString("dataset_id") else null

            val schemaObj = root.getJSONObject("schema_inventory")
            val tablesArr = schemaObj.getJSONArray("tables")
            val inventory = mutableListOf<TableInventoryEntry>()
            for (i in 0 until tablesArr.length()) {
                val tObj = tablesArr.getJSONObject(i)
                inventory.add(TableInventoryEntry(tObj.getString("name"), tObj.getLong("row_count")))
            }

            val integrityObj = root.getJSONObject("integrity")
            val quickCheck = integrityObj.optString("quick_check", "ok")
            val foreignKeyCheck = integrityObj.optString("foreign_key_check", "ok")
            val canonicalSha = integrityObj.getString("canonical_content_sha256")

            val compatObj = root.optJSONObject("compatibility")
            val minSchema = compatObj?.optInt("min_supported_schema_version", 1) ?: 1
            val maxSchema = compatObj?.optInt("max_supported_schema_version", 30) ?: 30
            val runtimeAuthority = compatObj?.optString("runtime_authority_required", "LEGACY_PRODUCTION") ?: "LEGACY_PRODUCTION"
            val restorePolicy = compatObj?.optString("restore_policy", "FAIL_CLOSED_ON_INCOMPATIBLE_AUTHORITY") ?: "FAIL_CLOSED_ON_INCOMPATIBLE_AUTHORITY"

            return BackupArchiveManifest(
                archiveFormat = archiveFormat,
                formatVersion = formatVersion,
                appId = appId,
                appVersionCode = appVersionCode,
                createdAt = createdAt,
                databaseVersion = databaseVersion,
                databaseSha256 = databaseSha256,
                databaseSizeBytes = databaseSizeBytes,
                datasetMode = datasetMode,
                datasetId = datasetId,
                tableInventory = inventory,
                quickCheckResult = quickCheck,
                foreignKeyCheckResult = foreignKeyCheck,
                canonicalContentSha256 = canonicalSha,
                minSupportedAppVersion = minSchema,
                maxSupportedSchemaVersion = maxSchema,
                runtimeAuthorityMode = runtimeAuthority,
                restorePolicy = restorePolicy
            )
        }

        fun computeCanonicalContentSha256(
            databaseVersion: Int,
            databaseSha256: String,
            createdAt: Long,
            tableInventory: List<TableInventoryEntry>
        ): String {
            val sorted = tableInventory.sortedBy { it.tableName }
            val tableStr = sorted.joinToString(";") { "${it.tableName}:${it.rowCount}" }
            val input = "CANONICAL:v1:$databaseVersion:$databaseSha256:$createdAt:$tableStr"
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
