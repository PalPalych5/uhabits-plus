/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.database

import android.content.Context
import android.util.Log
import org.isoron.platform.time.DateUtils
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.backup.BackupManager

class AutoBackup(private val context: Context) {
    private val backupManager: BackupManager
        get() = (context.applicationContext as HabitsApplication).component.backupManager

    fun run(keep: Int = 5) {
        Log.i("AutoBackup", "Starting automatic backups...")
        val files = backupManager.listBackups()
        val newestTimestamp = files.maxOfOrNull { it.modifiedAt } ?: 0L
        val now = DateUtils.getLocalTime()
        if (now - newestTimestamp > DateUtils.DAY_LENGTH) {
            backupManager.backupNow(keep)
        } else {
            Log.i("AutoBackup", "Fresh backup found (timestamp=$newestTimestamp)")
        }
    }
}
