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

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskCalendarPreferencesTest {

    private lateinit var testPrefs: FakeSharedPreferences
    private lateinit var preferences: TaskCalendarPreferences

    @Before
    fun setUp() {
        testPrefs = FakeSharedPreferences()
        preferences = TaskCalendarPreferences(testPrefs)
    }

    @Test
    fun defaultWithoutExistingPreferenceIsOff() {
        assertFalse(preferences.hasShowHabits())
        assertFalse(preferences.showHabits)
    }

    @Test
    fun settingShowHabitsPersistsValue() {
        preferences.showHabits = true
        assertTrue(preferences.hasShowHabits())
        assertTrue(preferences.showHabits)
        assertEquals(true, testPrefs.getBoolean(TaskCalendarPreferences.KEY_SHOW_HABITS, false))

        preferences.showHabits = false
        assertTrue(preferences.hasShowHabits())
        assertFalse(preferences.showHabits)
        assertEquals(false, testPrefs.getBoolean(TaskCalendarPreferences.KEY_SHOW_HABITS, true))
    }

    @Test
    fun existingTruePreferenceIsPreserved() {
        testPrefs.map[TaskCalendarPreferences.KEY_SHOW_HABITS] = true
        val restored = TaskCalendarPreferences(testPrefs)
        assertTrue(restored.hasShowHabits())
        assertTrue(restored.showHabits)
    }

    @Test
    fun clearRemovesPreferences() {
        preferences.showHabits = true
        assertTrue(preferences.hasShowHabits())
        preferences.clear()
        assertFalse(preferences.hasShowHabits())
        assertFalse(preferences.showHabits)
    }

    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, Any?>()
        private var shouldClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) modifications[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            shouldClear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (shouldClear) prefs.map.clear()
            for ((key, value) in modifications) {
                if (value == null) {
                    prefs.map.remove(key)
                } else {
                    prefs.map[key] = value
                }
            }
        }
    }
}
