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

package org.isoron.uhabits.activities.tasks

import android.content.Context
import android.content.SharedPreferences

class TaskCalendarPreferences(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    var showHabits: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HABITS, DEFAULT_SHOW_HABITS)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_HABITS, value).apply()
        }

    fun hasShowHabits(): Boolean = prefs.contains(KEY_SHOW_HABITS)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "task_calendar_preferences"
        const val KEY_SHOW_HABITS = "show_habits"
        const val DEFAULT_SHOW_HABITS = false
    }
}
