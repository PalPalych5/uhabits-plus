/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainerBackupManifestTest {

    @Test
    fun testManifestJsonRoundtrip() {
        val original = ContainerBackupManifest(
            formatVersion = 1,
            sourceSchemaVersion = 29,
            foundationVersion = 1,
            datasetMode = "CONTAINER_LOCAL",
            datasetUuid = "dataset-test-123456",
            createdAt = 1700000000000L,
            sourceSnapshotSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            canonicalChecksum = "canonical-checksum-abc",
            appSettings = mapOf("sync_last_sync_timestamp" to 50000L, "first_run_timestamp" to 1000L),
            nonSecretPreferences = mapOf("theme" to "dark", "first_day_of_week" to "monday"),
        )

        val json = original.toJson()
        val parsed = ContainerBackupManifest.fromJson(json)

        assertEquals(original.formatVersion, parsed.formatVersion)
        assertEquals(original.sourceSchemaVersion, parsed.sourceSchemaVersion)
        assertEquals(original.foundationVersion, parsed.foundationVersion)
        assertEquals(original.datasetMode, parsed.datasetMode)
        assertEquals(original.datasetUuid, parsed.datasetUuid)
        assertEquals(original.createdAt, parsed.createdAt)
        assertEquals(original.sourceSnapshotSha256, parsed.sourceSnapshotSha256)
        assertEquals(original.canonicalChecksum, parsed.canonicalChecksum)
        assertEquals(original.appSettings, parsed.appSettings)
        assertEquals(original.nonSecretPreferences, parsed.nonSecretPreferences)
    }

    @Test
    fun testSanitizePreferences_removesSensitiveKeys() {
        val input = mapOf(
            "theme" to "amoled",
            "auth_token" to "secret-token-xyz",
            "user_password" to "password123",
            "supabase_api_key" to "sb-secret-key",
            "client_secret" to "very-secret",
            "font_size" to "medium",
        )

        val sanitized = ContainerBackupManifest.sanitizePreferences(input)

        assertEquals(2, sanitized.size)
        assertTrue(sanitized.containsKey("theme"))
        assertTrue(sanitized.containsKey("font_size"))
        assertFalse(sanitized.containsKey("auth_token"))
        assertFalse(sanitized.containsKey("user_password"))
        assertFalse(sanitized.containsKey("supabase_api_key"))
        assertFalse(sanitized.containsKey("client_secret"))
    }
}
