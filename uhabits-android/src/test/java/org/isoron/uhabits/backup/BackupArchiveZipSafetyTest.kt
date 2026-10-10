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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveZipSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createValidZipArchive(
        targetZip: File,
        dbContent: ByteArray = "SQLite format 3\u0000dummy content".toByteArray(Charsets.US_ASCII),
        dbVersion: Int = 29
    ): BackupArchiveManifest {
        val dbSha = sha256(dbContent)
        val createdAt = 1700000000000L
        val inventory = listOf(TableInventoryEntry("Habits", 1L))
        val canonical = BackupArchiveManifest.computeCanonicalContentSha256(
            databaseVersion = dbVersion,
            databaseSha256 = dbSha,
            createdAt = createdAt,
            tableInventory = inventory
        )
        val manifest = BackupArchiveManifest(
            archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
            formatVersion = 1,
            appId = "org.isoron.uhabits",
            appVersionCode = 230,
            createdAt = createdAt,
            databaseVersion = dbVersion,
            databaseSizeBytes = dbContent.size.toLong(),
            databaseSha256 = dbSha,
            datasetMode = "LEGACY_PRODUCTION",
            datasetId = null,
            tableInventory = inventory,
            quickCheckResult = "ok",
            foreignKeyCheckResult = "ok",
            canonicalContentSha256 = canonical,
            runtimeAuthorityMode = "LEGACY_PRODUCTION"
        )

        ZipOutputStream(FileOutputStream(targetZip)).use { zos ->
            zos.putNextEntry(ZipEntry(BackupArchiveFormat.SQLITE_ENTRY_NAME))
            zos.write(dbContent)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(BackupArchiveFormat.MANIFEST_ENTRY_NAME))
            zos.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        return manifest
    }

    @Test
    fun detectFormatIdentifiesZipAndRawSqlite() {
        val zipFile = tempFolder.newFile("test.zip")
        createValidZipArchive(zipFile)
        assertEquals(BackupFormat.VERSIONED_ZIP, BackupArchiveValidator.detectFormat(zipFile))

        val dbFile = tempFolder.newFile("test.db")
        dbFile.writeBytes("SQLite format 3\u0000some data".toByteArray(Charsets.US_ASCII))
        assertEquals(BackupFormat.LEGACY_RAW_SQLITE, BackupArchiveValidator.detectFormat(dbFile))

        val randomFile = tempFolder.newFile("random.txt")
        randomFile.writeText("hello world")
        assertEquals(BackupFormat.UNKNOWN, BackupArchiveValidator.detectFormat(randomFile))
    }

    @Test
    fun extractorRejectsZipSlipPathTraversal() {
        val evilZip = tempFolder.newFile("evil.zip")
        ZipOutputStream(FileOutputStream(evilZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../../evil.db"))
            zos.write("evil data".toByteArray())
            zos.closeEntry()
        }

        val destFile = tempFolder.newFile("dest.db")
        try {
            BackupArchiveExtractor.extractDatabase(evilZip, destFile)
            fail("Expected SecurityViolationException on zip slip traversal")
        } catch (e: BackupRestoreException.SecurityViolationException) {
            assertTrue(e.message?.contains("Unsafe entry path") == true)
        }
    }

    @Test
    fun extractorRejectsMissingManifest() {
        val zipWithoutManifest = tempFolder.newFile("no-manifest.zip")
        ZipOutputStream(FileOutputStream(zipWithoutManifest)).use { zos ->
            zos.putNextEntry(ZipEntry("uhabits.db"))
            zos.write("db data".toByteArray())
            zos.closeEntry()
        }

        val destFile = tempFolder.newFile("dest.db")
        try {
            BackupArchiveExtractor.extractDatabase(zipWithoutManifest, destFile)
            fail("Expected CorruptedArchiveException for missing manifest")
        } catch (e: BackupRestoreException.CorruptedArchiveException) {
            assertTrue(e.message?.contains("Missing manifest") == true)
        }
    }

    @Test
    fun extractorRejectsMissingDatabaseEntry() {
        val zipWithoutDb = tempFolder.newFile("no-db.zip")
        val createdAt = 1000L
        val manifest = BackupArchiveManifest(
            archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
            formatVersion = 1,
            appId = "org.isoron.uhabits",
            appVersionCode = 1,
            createdAt = createdAt,
            databaseVersion = 29,
            databaseSizeBytes = 10L,
            databaseSha256 = "abc",
            datasetMode = "LEGACY_PRODUCTION",
            tableInventory = emptyList(),
            canonicalContentSha256 = "def",
            runtimeAuthorityMode = "LEGACY_PRODUCTION"
        )
        ZipOutputStream(FileOutputStream(zipWithoutDb)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val destFile = tempFolder.newFile("dest.db")
        try {
            BackupArchiveExtractor.extractDatabase(zipWithoutDb, destFile)
            fail("Expected CorruptedArchiveException for missing database")
        } catch (e: BackupRestoreException.CorruptedArchiveException) {
            assertTrue(e.message?.contains("Missing or empty database") == true)
        }
    }

    @Test
    fun extractorDetectsTamperedDatabaseChecksum() {
        val zipFile = tempFolder.newFile("tampered.zip")
        val content = "SQLite format 3\u0000authentic data".toByteArray(Charsets.US_ASCII)
        val createdAt = 1000L
        val dbSha = sha256(content)
        val inventory = listOf(TableInventoryEntry("Habits", 1L))
        val canonical = BackupArchiveManifest.computeCanonicalContentSha256(
            databaseVersion = 29,
            databaseSha256 = dbSha,
            createdAt = createdAt,
            tableInventory = inventory
        )
        val manifest = BackupArchiveManifest(
            archiveFormat = BackupArchiveFormat.ARCHIVE_FORMAT,
            formatVersion = 1,
            appId = "org.isoron.uhabits",
            appVersionCode = 1,
            createdAt = createdAt,
            databaseVersion = 29,
            databaseSizeBytes = content.size.toLong(),
            databaseSha256 = dbSha,
            datasetMode = "LEGACY_PRODUCTION",
            tableInventory = inventory,
            canonicalContentSha256 = canonical,
            runtimeAuthorityMode = "LEGACY_PRODUCTION"
        )

        // Write zip where uhabits.db content differs from manifest checksum
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry(BackupArchiveFormat.SQLITE_ENTRY_NAME))
            zos.write("SQLite format 3\u0000tampered data".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(BackupArchiveFormat.MANIFEST_ENTRY_NAME))
            zos.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val destFile = tempFolder.newFile("dest.db")
        try {
            BackupArchiveExtractor.extractDatabase(zipFile, destFile)
            fail("Expected CorruptedArchiveException for checksum mismatch")
        } catch (e: BackupRestoreException.CorruptedArchiveException) {
            assertTrue(e.message?.contains("Database checksum mismatch") == true)
        }
    }

    @Test
    fun extractorSuccessfullyExtractsValidArchive() {
        val zipFile = tempFolder.newFile("valid.zip")
        val content = "SQLite format 3\u0000genuine content".toByteArray(Charsets.US_ASCII)
        val manifest = createValidZipArchive(zipFile, dbContent = content)

        val destFile = tempFolder.newFile("dest.db")
        val extractedManifest = BackupArchiveExtractor.extractDatabase(zipFile, destFile)

        assertEquals(manifest.databaseVersion, extractedManifest.databaseVersion)
        assertEquals(content.size.toLong(), destFile.length())
        assertEquals(content.decodeToString(), destFile.readBytes().decodeToString())
    }
}
