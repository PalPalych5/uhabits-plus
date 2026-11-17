/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.backup

object BackupArchiveFormat {
    const val ARCHIVE_FORMAT = "uhabit-backup-zip"
    const val CURRENT_FORMAT_VERSION = 1
    const val SQLITE_ENTRY_NAME = "uhabits.db"
    const val MANIFEST_ENTRY_NAME = "manifest.json"
    const val PREFERENCES_ENTRY_NAME = "preferences.json"

    // Security & resource bounds against ZIP bombs and unbounded allocations
    const val MAX_TOTAL_ENTRIES = 20
    const val MAX_MANIFEST_SIZE_BYTES = 1024L * 1024L // 1 MB
    const val MAX_PREFERENCES_SIZE_BYTES = 1024L * 1024L // 1 MB
    const val MAX_DATABASE_SIZE_BYTES = 1024L * 1024L * 1024L // 1 GB
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L // 1 GB
}
