/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.activities.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference

class IntListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dialogPreferenceStyle,
    defStyleRes: Int = 0
) : ListPreference(context, attrs, defStyleAttr, defStyleRes) {

    override fun getPersistedString(defaultReturnValue: String?): String? {
        val key = key ?: return defaultReturnValue
        val prefs = sharedPreferences ?: return defaultReturnValue
        if (!prefs.contains(key)) return defaultReturnValue
        return try {
            prefs.getInt(key, 0).toString()
        } catch (e: ClassCastException) {
            prefs.getString(key, defaultReturnValue)
        }
    }

    override fun persistString(value: String?): Boolean {
        if (value == null) return false
        val key = key ?: return false
        val prefs = sharedPreferences ?: return false
        val intValue = value.toIntOrNull() ?: 0
        prefs.edit().putInt(key, intValue).apply()
        return true
    }
}
