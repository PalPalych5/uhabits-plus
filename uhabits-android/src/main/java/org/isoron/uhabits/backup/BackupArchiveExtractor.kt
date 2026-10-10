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
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object BackupArchiveExtractor {

    fun extractDatabase(
        zipFile: File,
        destinationFile: File,
        expectedSha256: String? = null
    ): BackupArchiveManifest {
        destinationFile.parentFile?.mkdirs()
        if (destinationFile.exists()) destinationFile.delete()

        var manifestJson: String? = null
        var foundDatabase = false
        val sha256Digest = MessageDigest.getInstance("SHA-256")

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
                                FileOutputStream(destinationFile).use { fos ->
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
                                        sha256Digest.update(buffer, 0, read)
                                        fos.write(buffer, 0, read)
                                    }
                                }
                                foundDatabase = true
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (!foundDatabase || destinationFile.length() == 0L) {
                destinationFile.delete()
                throw BackupRestoreException.CorruptedArchiveException("Missing or empty database entry in archive")
            }

            if (manifestJson == null) {
                destinationFile.delete()
                throw BackupRestoreException.CorruptedArchiveException("Missing manifest entry in archive")
            }

            val manifest = try {
                BackupArchiveManifest.fromJson(manifestJson)
            } catch (e: Exception) {
                destinationFile.delete()
                throw BackupRestoreException.CorruptedArchiveException("Malformed manifest: ${e.message}", e)
            }

            val actualSha = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            val checkAgainst = expectedSha256 ?: manifest.databaseSha256
            if (!actualSha.equals(checkAgainst, ignoreCase = true)) {
                destinationFile.delete()
                throw BackupRestoreException.CorruptedArchiveException("Database checksum mismatch: expected $checkAgainst, actual $actualSha")
            }

            return manifest
        } catch (e: Throwable) {
            destinationFile.delete()
            if (e is BackupRestoreException) throw e
            throw BackupRestoreException.CorruptedArchiveException("Extraction failed: ${e.message}", e)
        }
    }
}
