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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupArchiveManifestTest {

    private fun sampleManifest(
        version: Int = 29,
        sha: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        tableCount: Long = 10L,
        createdAt: Long = 1700000000000L
    ): BackupArchiveManifest {
        val inventory = listOf(
            TableInventoryEntry("Habits", 5L),
            TableInventoryEntry("Repetitions", tableCount)
        )
        val canonical = BackupArchiveManifest.computeCanonicalContentSha256(
            databaseVersion = version,
            databaseSha256 = sha,
            createdAt = createdAt,
            tableInventory = inventory
        )
        return BackupArchiveManifest(
            archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
            formatVersion = BackupArchiveFormat.CURRENT_FORMAT_VERSION,
            appId = "org.isoron.uhabits",
            appVersionCode = 230,
            createdAt = createdAt,
            databaseVersion = version,
            databaseSha256 = sha,
            databaseSizeBytes = 4096L,
            datasetMode = "LEGACY_PRODUCTION",
            datasetId = null,
            tableInventory = inventory,
            quickCheckResult = "ok",
            foreignKeyCheckResult = "ok",
            canonicalContentSha256 = canonical,
            runtimeAuthorityMode = "LEGACY_PRODUCTION"
        )
    }

    @Test
    fun roundTripManifestSerialization() {
        val original = sampleManifest()
        val json = original.toJson()
        val parsed = BackupArchiveManifest.fromJson(json)

        assertEquals(original.archiveFormat, parsed.archiveFormat)
        assertEquals(original.formatVersion, parsed.formatVersion)
        assertEquals(original.appId, parsed.appId)
        assertEquals(original.appVersionCode, parsed.appVersionCode)
        assertEquals(original.createdAt, parsed.createdAt)
        assertEquals(original.databaseVersion, parsed.databaseVersion)
        assertEquals(original.databaseSizeBytes, parsed.databaseSizeBytes)
        assertEquals(original.databaseSha256, parsed.databaseSha256)
        assertEquals(original.canonicalContentSha256, parsed.canonicalContentSha256)
        assertEquals(original.datasetMode, parsed.datasetMode)
        assertNull(parsed.datasetId)
        assertEquals(original.tableInventory.size, parsed.tableInventory.size)
        assertEquals(original.tableInventory[0].tableName, parsed.tableInventory[0].tableName)
        assertEquals(original.tableInventory[0].rowCount, parsed.tableInventory[0].rowCount)
        assertEquals(original.tableInventory[1].tableName, parsed.tableInventory[1].tableName)
        assertEquals(original.tableInventory[1].rowCount, parsed.tableInventory[1].rowCount)
        assertEquals(original.quickCheckResult, parsed.quickCheckResult)
        assertEquals(original.foreignKeyCheckResult, parsed.foreignKeyCheckResult)
        assertEquals(original.runtimeAuthorityMode, parsed.runtimeAuthorityMode)
    }

    @Test
    fun roundTripWithNextMetadata() {
        val inventory = listOf(TableInventoryEntry("Habits", 2L))
        val createdAt = 1700000001000L
        val canonical = BackupArchiveManifest.computeCanonicalContentSha256(
            databaseVersion = 30,
            databaseSha256 = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            createdAt = createdAt,
            tableInventory = inventory
        )
        val original = BackupArchiveManifest(
            archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
            formatVersion = 1,
            appId = "org.isoron.uhabits",
            appVersionCode = 300,
            createdAt = createdAt,
            databaseVersion = 30,
            databaseSha256 = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            databaseSizeBytes = 8192L,
            datasetMode = "NEXT_PRODUCTION",
            datasetId = "test-uuid-1234",
            tableInventory = inventory,
            canonicalContentSha256 = canonical,
            runtimeAuthorityMode = "NEXT_PRODUCTION"
        )
        val json = original.toJson()
        val parsed = BackupArchiveManifest.fromJson(json)

        assertEquals(30, parsed.databaseVersion)
        assertEquals("NEXT_PRODUCTION", parsed.datasetMode)
        assertEquals("test-uuid-1234", parsed.datasetId)
        assertEquals("NEXT_PRODUCTION", parsed.runtimeAuthorityMode)
    }

    @Test
    fun canonicalHashIsDeterministic() {
        val inventory1 = listOf(
            TableInventoryEntry("Repetitions", 100L),
            TableInventoryEntry("Habits", 10L)
        )
        val inventory2 = listOf(
            TableInventoryEntry("Habits", 10L),
            TableInventoryEntry("Repetitions", 100L)
        )
        // Order independent because canonical calculation sorts tables by name
        val hash1 = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory1)
        val hash2 = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory2)
        assertEquals(hash1, hash2)
    }

    @Test
    fun canonicalHashChangesOnAnyVariation() {
        val inventory = listOf(TableInventoryEntry("Habits", 10L))
        val base = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory)

        val diffVersion = BackupArchiveManifest.computeCanonicalContentSha256(30, "abc", 1000L, inventory)
        val diffSha = BackupArchiveManifest.computeCanonicalContentSha256(29, "abd", 1000L, inventory)
        val diffTimestamp = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 2000L, inventory)
        val diffRows = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, listOf(TableInventoryEntry("Habits", 11L)))

        assertNotEquals(base, diffVersion)
        assertNotEquals(base, diffSha)
        assertNotEquals(base, diffTimestamp)
        assertNotEquals(base, diffRows)
    }

    @Test
    fun rejectsInvalidJson() {
        try {
            BackupArchiveManifest.fromJson("not json at all")
            fail("Expected exception")
        } catch (e: Exception) {
            // Success
        }
    }

    @Test
    fun rejectsMissingRequiredFields() {
        val incompleteJson = """{"archive_format":"uhabit-backup-zip","format_version":1}"""
        try {
            BackupArchiveManifest.fromJson(incompleteJson)
            fail("Expected exception for missing required fields")
        } catch (e: Exception) {
            // Success
        }
    }

    @Test
    fun roundTripWithPreferencesMetadata() {
        val inventory = listOf(TableInventoryEntry("Habits", 5L))
        val createdAt = 1700000000000L
        val prefSha = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
        val canonical = BackupArchiveManifest.computeCanonicalContentSha256(
            databaseVersion = 29,
            databaseSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            createdAt = createdAt,
            tableInventory = inventory,
            preferencesSha256 = prefSha
        )
        val original = BackupArchiveManifest(
            archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
            formatVersion = 1,
            appId = "org.isoron.uhabits",
            appVersionCode = 230,
            createdAt = createdAt,
            databaseVersion = 29,
            databaseSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            databaseSizeBytes = 4096L,
            datasetMode = "LEGACY_PRODUCTION",
            tableInventory = inventory,
            canonicalContentSha256 = canonical,
            runtimeAuthorityMode = "LEGACY_PRODUCTION",
            preferencesSha256 = prefSha,
            preferencesSizeBytes = 512L,
            preferencesCount = 10
        )

        val json = original.toJson()
        val parsed = BackupArchiveManifest.fromJson(json)

        assertEquals(prefSha, parsed.preferencesSha256)
        assertEquals(512L, parsed.preferencesSizeBytes)
        assertEquals(10, parsed.preferencesCount)
        assertEquals(canonical, parsed.canonicalContentSha256)
    }

    @Test
    fun canonicalHashWithPreferencesChangesHash() {
        val inventory = listOf(TableInventoryEntry("Habits", 10L))
        val withoutPref = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory)
        val withPref1 = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory, preferencesSha256 = "p1")
        val withPref2 = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory, preferencesSha256 = "p2")

        assertNotEquals(withoutPref, withPref1)
        assertNotEquals(withPref1, withPref2)
    }

    @Test
    fun canonicalHashWithoutPreferencesMatchesLegacy() {
        val inventory = listOf(TableInventoryEntry("Habits", 10L))
        val defaultCall = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory)
        val explicitNull = BackupArchiveManifest.computeCanonicalContentSha256(29, "abc", 1000L, inventory, preferencesSha256 = null)

        assertEquals(defaultCall, explicitNull)
    }
}
