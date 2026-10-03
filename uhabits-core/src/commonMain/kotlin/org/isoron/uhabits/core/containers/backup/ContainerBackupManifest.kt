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

package org.isoron.uhabits.core.containers.backup

import org.isoron.uhabits.core.containers.migration.Sha256

data class ContainerBackupManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val sourceSchemaVersion: Int = 29,
    val foundationVersion: Int = CURRENT_FOUNDATION_VERSION,
    val datasetMode: String = "CONTAINER_LOCAL",
    val datasetUuid: String,
    val createdAt: Long,
    val sourceSnapshotSha256: String,
    val canonicalChecksum: String,
    val appSettings: Map<String, Long> = emptyMap(),
    val nonSecretPreferences: Map<String, String> = emptyMap(),
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format_version\": ").append(formatVersion).append(",\n")
        sb.append("  \"source_schema_version\": ").append(sourceSchemaVersion).append(",\n")
        sb.append("  \"foundation_version\": ").append(foundationVersion).append(",\n")
        sb.append("  \"dataset_mode\": \"").append(escapeJson(datasetMode)).append("\",\n")
        sb.append("  \"dataset_uuid\": \"").append(escapeJson(datasetUuid)).append("\",\n")
        sb.append("  \"created_at\": ").append(createdAt).append(",\n")
        sb.append("  \"source_snapshot_sha256\": \"").append(escapeJson(sourceSnapshotSha256)).append("\",\n")
        sb.append("  \"canonical_checksum\": \"").append(escapeJson(canonicalChecksum)).append("\",\n")

        sb.append("  \"app_settings\": {")
        val settingsEntries = appSettings.entries.toList()
        for (i in settingsEntries.indices) {
            val (k, v) = settingsEntries[i]
            sb.append("\"").append(escapeJson(k)).append("\": ").append(v)
            if (i < settingsEntries.size - 1) sb.append(", ")
        }
        sb.append("},\n")

        sb.append("  \"non_secret_preferences\": {")
        val prefsEntries = nonSecretPreferences.entries.toList()
        for (i in prefsEntries.indices) {
            val (k, v) = prefsEntries[i]
            sb.append("\"").append(escapeJson(k)).append("\": \"").append(escapeJson(v)).append("\"")
            if (i < prefsEntries.size - 1) sb.append(", ")
        }
        sb.append("}\n")
        sb.append("}")
        return sb.toString()
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val CURRENT_FOUNDATION_VERSION = 1

        private val SENSITIVE_KEY_PATTERNS = listOf(
            "token", "secret", "password", "auth", "credential", "private_key", "supabase"
        )

        fun sanitizePreferences(prefs: Map<String, String>): Map<String, String> {
            return prefs.filterKeys { key ->
                val lower = key.lowercase()
                SENSITIVE_KEY_PATTERNS.none { lower.contains(it) }
            }
        }

        fun computeCanonicalChecksum(
            datasetUuid: String,
            sourceSnapshotSha256: String,
            createdAt: Long,
        ): String {
            val payload = "MANIFEST:v$CURRENT_FORMAT_VERSION:$CURRENT_FOUNDATION_VERSION:$datasetUuid:$sourceSnapshotSha256:$createdAt"
            return Sha256.hexDigest(Sha256.digest(payload.encodeToByteArray()))
        }

        fun fromJson(json: String): ContainerBackupManifest {
            val formatVersion = extractInt(json, "format_version") ?: CURRENT_FORMAT_VERSION
            val sourceSchemaVersion = extractInt(json, "source_schema_version") ?: 29
            val foundationVersion = extractInt(json, "foundation_version") ?: CURRENT_FOUNDATION_VERSION
            val datasetMode = extractString(json, "dataset_mode") ?: "CONTAINER_LOCAL"
            val datasetUuid = extractString(json, "dataset_uuid") ?: ""
            val createdAt = extractLong(json, "created_at") ?: 0L
            val sourceSnapshotSha256 = extractString(json, "source_snapshot_sha256") ?: ""
            val canonicalChecksum = extractString(json, "canonical_checksum") ?: ""

            val appSettings = extractMapLong(json, "app_settings")
            val nonSecretPreferences = extractMapString(json, "non_secret_preferences")

            return ContainerBackupManifest(
                formatVersion = formatVersion,
                sourceSchemaVersion = sourceSchemaVersion,
                foundationVersion = foundationVersion,
                datasetMode = datasetMode,
                datasetUuid = datasetUuid,
                createdAt = createdAt,
                sourceSnapshotSha256 = sourceSnapshotSha256,
                canonicalChecksum = canonicalChecksum,
                appSettings = appSettings,
                nonSecretPreferences = nonSecretPreferences,
            )
        }

        private fun escapeJson(s: String): String =
            s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

        private fun extractString(json: String, key: String): String? {
            val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return regex.find(json)?.groupValues?.get(1)
        }

        private fun extractInt(json: String, key: String): Int? {
            val regex = Regex("\"$key\"\\s*:\\s*(\\d+)")
            return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
        }

        private fun extractLong(json: String, key: String): Long? {
            val regex = Regex("\"$key\"\\s*:\\s*(\\d+)")
            return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
        }

        private fun extractMapLong(json: String, key: String): Map<String, Long> {
            val blockRegex = Regex("\"$key\"\\s*:\\s*\\{([^}]*)\\}")
            val content = blockRegex.find(json)?.groupValues?.get(1) ?: return emptyMap()
            val entryRegex = Regex("\"([^\"]+)\"\\s*:\\s*(\\d+)")
            return entryRegex.findAll(content).associate {
                it.groupValues[1] to it.groupValues[2].toLong()
            }
        }

        private fun extractMapString(json: String, key: String): Map<String, String> {
            val blockRegex = Regex("\"$key\"\\s*:\\s*\\{([^}]*)\\}")
            val content = blockRegex.find(json)?.groupValues?.get(1) ?: return emptyMap()
            val entryRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
            return entryRegex.findAll(content).associate {
                it.groupValues[1] to it.groupValues[2]
            }
        }
    }
}
