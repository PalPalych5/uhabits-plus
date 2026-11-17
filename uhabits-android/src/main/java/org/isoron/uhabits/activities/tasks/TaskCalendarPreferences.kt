/* SPDX-License-Identifier: GPL-3.0-or-later */

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
