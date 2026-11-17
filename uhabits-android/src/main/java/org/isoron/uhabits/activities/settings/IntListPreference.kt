/* SPDX-License-Identifier: GPL-3.0-or-later */

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
