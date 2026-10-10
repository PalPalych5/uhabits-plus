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
