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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ExtractedArchive(
    val manifest: BackupArchiveManifest,
    val preferencesExtracted: Boolean
)

object BackupArchiveExtractor {

    fun extractArchive(
        zipFile: File,
        destinationDbFile: File,
        destinationPreferencesFile: File? = null
    ): ExtractedArchive {
        destinationDbFile.parentFile?.mkdirs()
        if (destinationDbFile.exists()) destinationDbFile.delete()
        if (destinationPreferencesFile != null) {
            destinationPreferencesFile.parentFile?.mkdirs()
            if (destinationPreferencesFile.exists()) destinationPreferencesFile.delete()
        }

        var manifestJson: String? = null
        var foundDatabase = false
        var foundPreferences = false
        val dbSha256Digest = MessageDigest.getInstance("SHA-256")
        val prefSha256Digest = MessageDigest.getInstance("SHA-256")

        try {
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    var totalDecompressedBytes = 0L

                    while (entry != null) {
                        val name = entry.name
                        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                            throw BackupRestoreException.SecurityViolationException("Unsafe entry path detected in archive: $name")
                        }

                        when (name) {
                            BackupArchiveFormat.MANIFEST_ENTRY_NAME -> {
                                val baos = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var read: Int
                                var entryBytes = 0L
                                while (zis.read(buffer).also { read = it } != -1) {
                                    entryBytes += read
                                    totalDecompressedBytes += read
                                    if (entryBytes > BackupArchiveFormat.MAX_MANIFEST_SIZE_BYTES ||
                                        totalDecompressedBytes > BackupArchiveFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                        throw BackupRestoreException.SecurityViolationException("Manifest exceeds resource limits")
                                    }
                                    baos.write(buffer, 0, read)
                                }
                                manifestJson = baos.toString("UTF-8")
                            }
                            BackupArchiveFormat.SQLITE_ENTRY_NAME -> {
                                FileOutputStream(destinationDbFile).use { fos ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    var dbBytes = 0L
                                    while (zis.read(buffer).also { read = it } != -1) {
                                        dbBytes += read
                                        totalDecompressedBytes += read
                                        if (dbBytes > BackupArchiveFormat.MAX_DATABASE_SIZE_BYTES ||
                                            totalDecompressedBytes > BackupArchiveFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                            throw BackupRestoreException.SecurityViolationException("Database exceeds resource limits")
                                        }
                                        dbSha256Digest.update(buffer, 0, read)
                                        fos.write(buffer, 0, read)
                                    }
                                }
                                foundDatabase = true
                            }
                            BackupArchiveFormat.PREFERENCES_ENTRY_NAME -> {
                                if (destinationPreferencesFile != null) {
                                    FileOutputStream(destinationPreferencesFile).use { fos ->
                                        val buffer = ByteArray(8192)
                                        var read: Int
                                        var prefBytes = 0L
                                        while (zis.read(buffer).also { read = it } != -1) {
                                            prefBytes += read
                                            totalDecompressedBytes += read
                                            if (prefBytes > BackupArchiveFormat.MAX_PREFERENCES_SIZE_BYTES ||
                                                totalDecompressedBytes > BackupArchiveFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                                throw BackupRestoreException.SecurityViolationException("Preferences exceed resource limits")
                                            }
                                            prefSha256Digest.update(buffer, 0, read)
                                            fos.write(buffer, 0, read)
                                        }
                                    }
                                    foundPreferences = true
                                } else {
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (zis.read(buffer).also { read = it } != -1) {
                                        totalDecompressedBytes += read
                                        if (totalDecompressedBytes > BackupArchiveFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                            throw BackupRestoreException.SecurityViolationException("Preferences exceed resource limits")
                                        }
                                    }
                                    foundPreferences = true
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (!foundDatabase || destinationDbFile.length() == 0L) {
                destinationDbFile.delete()
                destinationPreferencesFile?.delete()
                throw BackupRestoreException.CorruptedArchiveException("Missing or empty database entry in archive")
            }

            if (manifestJson == null) {
                destinationDbFile.delete()
                destinationPreferencesFile?.delete()
                throw BackupRestoreException.CorruptedArchiveException("Missing manifest entry in archive")
            }

            val manifest = try {
                BackupArchiveManifest.fromJson(manifestJson)
            } catch (e: Exception) {
                destinationDbFile.delete()
                destinationPreferencesFile?.delete()
                throw BackupRestoreException.CorruptedArchiveException("Malformed manifest: ${e.message}", e)
            }

            val actualDbSha = dbSha256Digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualDbSha.equals(manifest.databaseSha256, ignoreCase = true)) {
                destinationDbFile.delete()
                destinationPreferencesFile?.delete()
                throw BackupRestoreException.CorruptedArchiveException("Database checksum mismatch: expected ${manifest.databaseSha256}, actual $actualDbSha")
            }

            if (manifest.preferencesSha256 != null) {
                if (!foundPreferences) {
                    destinationDbFile.delete()
                    destinationPreferencesFile?.delete()
                    throw BackupRestoreException.CorruptedArchiveException("Missing preferences entry declared in manifest")
                }
                if (destinationPreferencesFile != null) {
                    val actualPrefSha = prefSha256Digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualPrefSha.equals(manifest.preferencesSha256, ignoreCase = true)) {
                        destinationDbFile.delete()
                        destinationPreferencesFile.delete()
                        throw BackupRestoreException.CorruptedArchiveException("Preferences checksum mismatch: expected ${manifest.preferencesSha256}, actual $actualPrefSha")
                    }
                }
            }

            return ExtractedArchive(manifest, foundPreferences)
        } catch (e: Throwable) {
            destinationDbFile.delete()
            destinationPreferencesFile?.delete()
            if (e is BackupRestoreException) throw e
            throw BackupRestoreException.CorruptedArchiveException("Extraction failed: ${e.message}", e)
        }
    }

    fun extractDatabase(
        zipFile: File,
        destinationFile: File,
        expectedSha256: String? = null
    ): BackupArchiveManifest {
        val result = extractArchive(zipFile, destinationFile, null)
        val checkAgainst = expectedSha256 ?: result.manifest.databaseSha256
        val digest = MessageDigest.getInstance("SHA-256")
        val actualSha = destinationFile.inputStream().use { input ->
            val buf = ByteArray(8192)
            var r: Int
            while (input.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!actualSha.equals(checkAgainst, ignoreCase = true)) {
            destinationFile.delete()
            throw BackupRestoreException.CorruptedArchiveException("Database checksum mismatch: expected $checkAgainst, actual $actualSha")
        }
        return result.manifest
    }
}
