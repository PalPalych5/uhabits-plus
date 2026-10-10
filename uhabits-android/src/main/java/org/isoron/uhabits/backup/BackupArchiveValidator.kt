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

import android.database.sqlite.SQLiteDatabase
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object BackupArchiveValidator {

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun detectFormat(file: File): BackupFormat {
        if (!file.exists() || file.length() < 4) return BackupFormat.UNKNOWN
        try {
            FileInputStream(file).use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) {
                    return BackupFormat.VERSIONED_ZIP
                }
                if (read >= SQLITE_HEADER.size && header.copyOf(SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)) {
                    return BackupFormat.LEGACY_RAW_SQLITE
                }
            }
        } catch (_: Exception) {
            return BackupFormat.UNKNOWN
        }
        return BackupFormat.UNKNOWN
    }

    fun validate(file: File, targetVersion: Int = 29): BackupValidationResult {
        if (!file.exists() || file.length() <= 0L) {
            return BackupValidationResult(
                isValid = false,
                errorMessage = "Backup file is empty or missing",
                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED
            )
        }

        return when (detectFormat(file)) {
            BackupFormat.VERSIONED_ZIP -> validateZipArchive(file, targetVersion)
            BackupFormat.LEGACY_RAW_SQLITE -> validateRawSqlite(file, targetVersion)
            BackupFormat.UNKNOWN -> BackupValidationResult(
                isValid = false,
                format = BackupFormat.UNKNOWN,
                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                errorMessage = "Unsupported or corrupted backup format: file is neither a valid ZIP archive nor SQLite database"
            )
        }
    }

    private fun validateZipArchive(zipFile: File, targetVersion: Int): BackupValidationResult {
        val seenEntries = mutableSetOf<String>()
        var manifestContent: String? = null
        val tempExtractedDb = File.createTempFile("val-uhabits-", ".db", zipFile.parentFile ?: File(System.getProperty("java.io.tmpdir")))

        try {
            var totalUncompressedBytes = 0L
            var entryCount = 0
            val sha256Digest = MessageDigest.getInstance("SHA-256")

            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > BackupArchiveFormat.MAX_TOTAL_ENTRIES) {
                            return BackupValidationResult(
                                isValid = false,
                                format = BackupFormat.VERSIONED_ZIP,
                                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                                errorMessage = "Archive exceeds maximum allowed entry count (${BackupArchiveFormat.MAX_TOTAL_ENTRIES})"
                            )
                        }

                        val name = entry.name
                        // Path traversal check
                        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains("/") || name.contains("\\")) {
                            return BackupValidationResult(
                                isValid = false,
                                format = BackupFormat.VERSIONED_ZIP,
                                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                                errorMessage = "Archive contains unsafe entry path: $name"
                            )
                        }

                        // Duplicate entry check
                        if (!seenEntries.add(name)) {
                            return BackupValidationResult(
                                isValid = false,
                                format = BackupFormat.VERSIONED_ZIP,
                                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                                errorMessage = "Archive contains duplicate entry: $name"
                            )
                        }

                        when (name) {
                            BackupArchiveFormat.MANIFEST_ENTRY_NAME -> {
                                val baos = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var read: Int
                                var manifestBytes = 0L
                                while (zis.read(buffer).also { read = it } != -1) {
                                    manifestBytes += read
                                    totalUncompressedBytes += read
                                    if (manifestBytes > BackupArchiveFormat.MAX_MANIFEST_SIZE_BYTES ||
                                        totalUncompressedBytes > BackupArchiveFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                        return BackupValidationResult(
                                            isValid = false,
                                            format = BackupFormat.VERSIONED_ZIP,
                                            compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                                            errorMessage = "Manifest entry exceeds maximum allowed size"
                                        )
                                    }
                                    baos.write(buffer, 0, read)
                                }
                                manifestContent = baos.toString("UTF-8")
                            }
                            BackupArchiveFormat.SQLITE_ENTRY_NAME -> {
                                FileOutputStream(tempExtractedDb).use { fos ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    var dbBytes = 0L
                                    while (zis.read(buffer).also { read = it } != -1) {
                                        dbBytes += read
                                        totalUncompressedBytes += read
                                        if (dbBytes > BackupArchiveFormat.MAX_DATABASE_SIZE_BYTES ||
                                            totalUncompressedBytes > BackupArchiveFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                            return BackupValidationResult(
                                                isValid = false,
                                                format = BackupFormat.VERSIONED_ZIP,
                                                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                                                errorMessage = "Database entry exceeds maximum allowed decompression size"
                                            )
                                        }
                                        sha256Digest.update(buffer, 0, read)
                                        fos.write(buffer, 0, read)
                                    }
                                }
                            }
                            BackupArchiveFormat.PREFERENCES_ENTRY_NAME -> {
                                // Reserved extension point: read safely under limits
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    totalUncompressedBytes += read
                                    if (totalUncompressedBytes > BackupArchiveFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                        return BackupValidationResult(
                                            isValid = false,
                                            format = BackupFormat.VERSIONED_ZIP,
                                            compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                                            errorMessage = "Archive exceeds total uncompressed size limit"
                                        )
                                    }
                                }
                            }
                            else -> {
                                return BackupValidationResult(
                                    isValid = false,
                                    format = BackupFormat.VERSIONED_ZIP,
                                    compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                                    errorMessage = "Archive contains unexpected or unsupported entry: $name"
                                )
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (manifestContent == null) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                    errorMessage = "Archive is missing mandatory manifest: ${BackupArchiveFormat.MANIFEST_ENTRY_NAME}"
                )
            }
            if (!seenEntries.contains(BackupArchiveFormat.SQLITE_ENTRY_NAME) || tempExtractedDb.length() == 0L) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                    errorMessage = "Archive is missing mandatory database: ${BackupArchiveFormat.SQLITE_ENTRY_NAME}"
                )
            }

            // Parse manifest (untrusted input)
            val manifest = try {
                BackupArchiveManifest.fromJson(manifestContent!!)
            } catch (e: Exception) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                    errorMessage = "Malformed or unparseable manifest: ${e.message}"
                )
            }

            if (manifest.archiveFormat != BackupArchiveFormat.ARCHIVE_FORMAT) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.UNSUPPORTED_VERSION,
                    errorMessage = "Unsupported archive format: ${manifest.archiveFormat}"
                )
            }
            if (manifest.formatVersion > BackupArchiveFormat.CURRENT_FORMAT_VERSION) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.UNSUPPORTED_VERSION,
                    errorMessage = "Unsupported archive format version: ${manifest.formatVersion}"
                )
            }

            // Verify SQLite SHA-256
            val actualDbSha256 = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualDbSha256.equals(manifest.databaseSha256, ignoreCase = true)) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                    errorMessage = "Database checksum mismatch: expected ${manifest.databaseSha256}, actual $actualDbSha256"
                )
            }

            // Verify canonical content SHA-256
            val expectedCanonicalSha = BackupArchiveManifest.computeCanonicalContentSha256(
                databaseVersion = manifest.databaseVersion,
                databaseSha256 = manifest.databaseSha256,
                createdAt = manifest.createdAt,
                tableInventory = manifest.tableInventory
            )
            if (!expectedCanonicalSha.equals(manifest.canonicalContentSha256, ignoreCase = true)) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                    errorMessage = "Canonical content checksum mismatch: manifest integrity compromised"
                )
            }

            // Verify SQLite database integrity & schema
            val actualDbHeader = ByteArray(SQLITE_HEADER.size)
            FileInputStream(tempExtractedDb).use { it.read(actualDbHeader) }
            if (!actualDbHeader.contentEquals(SQLITE_HEADER)) {
                return BackupValidationResult(
                    isValid = false,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                    errorMessage = "Extracted database does not have valid SQLite header"
                )
            }

            val db = SQLiteDatabase.openDatabase(tempExtractedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                if (db.version != manifest.databaseVersion) {
                    return BackupValidationResult(
                        isValid = false,
                        format = BackupFormat.VERSIONED_ZIP,
                        compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                        errorMessage = "Database user_version (${db.version}) does not match manifest (${manifest.databaseVersion})"
                    )
                }

                val integrity = db.rawQuery("PRAGMA quick_check", null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (integrity != "ok") {
                    return BackupValidationResult(
                        isValid = false,
                        format = BackupFormat.VERSIONED_ZIP,
                        compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                        errorMessage = "Database failed quick_check: $integrity"
                    )
                }

                val fkCheckCount = db.rawQuery("PRAGMA foreign_key_check", null).use { it.count }
                if (fkCheckCount > 0) {
                    return BackupValidationResult(
                        isValid = false,
                        format = BackupFormat.VERSIONED_ZIP,
                        compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                        errorMessage = "Database failed foreign_key_check ($fkCheckCount violations)"
                    )
                }

                // Table inventory check
                val actualTables = mutableMapOf<String, Long>()
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val tableName = cursor.getString(0)
                        val rowCount = db.rawQuery("SELECT COUNT(*) FROM \"$tableName\"", null).use { countCursor ->
                            if (countCursor.moveToFirst()) countCursor.getLong(0) else 0L
                        }
                        actualTables[tableName] = rowCount
                    }
                }

                val manifestTableMap = manifest.tableInventory.associate { it.tableName to it.rowCount }
                if (actualTables != manifestTableMap) {
                    return BackupValidationResult(
                        isValid = false,
                        format = BackupFormat.VERSIONED_ZIP,
                        compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                        errorMessage = "Table inventory mismatch between database and manifest"
                    )
                }

                // Check required tables for database version
                val requiredTables = buildList {
                    add("Habits")
                    add("Repetitions")
                    if (manifest.databaseVersion >= 26) add("HabitExtensions")
                    if (manifest.databaseVersion >= 27) add("HabitBlocks")
                    if (manifest.databaseVersion >= 28) {
                        add("HabitGoals")
                        add("AppSettings")
                    }
                    if (manifest.databaseVersion >= 29) {
                        add("SyncQueue")
                        add("EntryOps")
                    }
                    if (manifest.databaseVersion >= 30) {
                        addAll(DatabaseUtils.NEXT_TABLE_NAMES)
                    }
                }
                if (!actualTables.keys.containsAll(requiredTables)) {
                    return BackupValidationResult(
                        isValid = false,
                        format = BackupFormat.VERSIONED_ZIP,
                        compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                        errorMessage = "Database is missing required schema tables for version ${manifest.databaseVersion}"
                    )
                }

                // Authority and cutover safety check
                val hasNextEntities = DatabaseUtils.NEXT_TABLE_NAMES.any(actualTables.keys::contains)
                if (targetVersion <= org.isoron.uhabits.core.LEGACY_DATABASE_VERSION && (manifest.databaseVersion > targetVersion || hasNextEntities)) {
                    return BackupValidationResult(
                        isValid = false,
                        databaseVersion = manifest.databaseVersion,
                        format = BackupFormat.VERSIONED_ZIP,
                        compatibilityStatus = BackupCompatibilityStatus.REQUIRES_NEXT_AUTHORITY,
                        errorMessage = "Backup contains schema version ${manifest.databaseVersion} or Next entities, which cannot be restored into legacy production (v$targetVersion). Production cutover is not authorized.",
                        manifest = manifest
                    )
                }

                return BackupValidationResult(
                    isValid = true,
                    databaseVersion = manifest.databaseVersion,
                    format = BackupFormat.VERSIONED_ZIP,
                    compatibilityStatus = BackupCompatibilityStatus.COMPATIBLE,
                    manifest = manifest
                )
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            return BackupValidationResult(
                isValid = false,
                format = BackupFormat.VERSIONED_ZIP,
                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                errorMessage = "Archive validation failed: ${e.message}"
            )
        } finally {
            tempExtractedDb.delete()
        }
    }

    private fun validateRawSqlite(file: File, targetVersion: Int): BackupValidationResult {
        val legacyResult = DatabaseUtils.validateRawSqliteBackup(file)
        if (!legacyResult.isValid) {
            return BackupValidationResult(
                isValid = false,
                databaseVersion = legacyResult.databaseVersion,
                format = BackupFormat.LEGACY_RAW_SQLITE,
                compatibilityStatus = BackupCompatibilityStatus.CORRUPTED,
                errorMessage = legacyResult.errorMessage
            )
        }

        val version = legacyResult.databaseVersion ?: 0
        if (targetVersion <= org.isoron.uhabits.core.LEGACY_DATABASE_VERSION && version > targetVersion) {
            return BackupValidationResult(
                isValid = false,
                databaseVersion = version,
                format = BackupFormat.LEGACY_RAW_SQLITE,
                compatibilityStatus = BackupCompatibilityStatus.REQUIRES_NEXT_AUTHORITY,
                errorMessage = "Raw SQLite backup version $version exceeds legacy target version $targetVersion. Production cutover is not authorized."
            )
        }

        return BackupValidationResult(
            isValid = true,
            databaseVersion = version,
            format = BackupFormat.LEGACY_RAW_SQLITE,
            compatibilityStatus = BackupCompatibilityStatus.COMPATIBLE
        )
    }
}
