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

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferencesAdapterTest {

    private class TestSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): Map<String, *> = map.toMap()

        override fun getString(key: String?, defValue: String?): String? =
            map[key]?.let { it as? String } ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            map[key]?.let { it as? Set<String> } ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (map[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (map[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (map[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = TestEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class TestEditor(private val prefs: TestSharedPreferences) : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
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
            clear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) prefs.map.clear()
            for ((key, value) in modifications) {
                if (value == null) {
                    prefs.map.remove(key)
                } else {
                    prefs.map[key] = value
                }
            }
        }
    }

    private fun buildPreferencesJson(
        format: String = BackupPreferencesAdapter.PREFERENCES_FORMAT,
        formatVersion: Int = BackupPreferencesAdapter.CURRENT_PREFERENCES_FORMAT_VERSION,
        entries: Map<String, Pair<String, Any>> = emptyMap()
    ): String {
        val root = JSONObject().apply {
            put("format", format)
            put("format_version", formatVersion)
            put("app_id", "org.isoron.uhabits")
            put("created_at", 1700000000000L)
            val prefsObj = JSONObject()
            for ((k, pair) in entries) {
                val entryObj = JSONObject().apply {
                    put("type", pair.first)
                    put("value", pair.second)
                }
                prefsObj.put(k, entryObj)
            }
            put("preferences", prefsObj)
        }
        return root.toString()
    }

    @Test
    fun validateValidPreferencesPasses() {
        val json = buildPreferencesJson(
            entries = mapOf(
                "pref_theme" to Pair("INT", 1),
                "pref_first_weekday" to Pair("STRING", "2"),
                "pref_pure_black" to Pair("BOOLEAN", true),
                "pref_app_language" to Pair("STRING", "en"),
                "pref_pomodoro_default_focus_minutes" to Pair("INT", 30)
            )
        )

        val result = BackupPreferencesAdapter.validatePreferencesJson(json)
        assertTrue(result.isValid)
        assertNull(result.errorMessage)
        assertEquals(5, result.preferenceCount)
    }

    @Test
    fun validateFailsOnWrongFormat() {
        val json = buildPreferencesJson(format = "invalid-format")
        val result = BackupPreferencesAdapter.validatePreferencesJson(json)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("Unsupported preferences format") == true)
    }

    @Test
    fun validateFailsOnFutureVersion() {
        val json = buildPreferencesJson(formatVersion = 2)
        val result = BackupPreferencesAdapter.validatePreferencesJson(json)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("Unsupported preferences format version") == true)
    }

    @Test
    fun validateFailsOnDisallowedOrUnknownKey() {
        val disallowedKeys = listOf(
            "pref_sync_key",
            "pref_sync_password",
            "sync_device_id",
            "pref_developer",
            "pref_experimental_mode",
            "pref_sync_enabled",
            "pref_sync_account_email",
            "production_cutover_gate",
            "unknown_arbitrary_key"
        )

        for (disallowedKey in disallowedKeys) {
            val json = buildPreferencesJson(
                entries = mapOf(disallowedKey to Pair("STRING", "secret_or_unauthorized"))
            )
            val result = BackupPreferencesAdapter.validatePreferencesJson(json)
            assertFalse("Key $disallowedKey should have failed validation", result.isValid)
            assertTrue(result.errorMessage?.contains("Unallowlisted or unauthorized") == true)
        }
    }

    @Test
    fun validateFailsOnRangeViolations() {
        val testCases = listOf(
            Triple<String, String, Any>("pref_theme", "INT", 99),
            Triple<String, String, Any>("pref_first_weekday", "STRING", "9"),
            Triple<String, String, Any>("pref_pomodoro_default_focus_minutes", "INT", 0),
            Triple<String, String, Any>("pref_pomodoro_default_focus_minutes", "INT", 181),
            Triple<String, String, Any>("pref_pomodoro_default_break_minutes", "INT", 0),
            Triple<String, String, Any>("pref_pomodoro_default_break_minutes", "INT", 61),
            Triple<String, String, Any>("pref_habit_card_corner_radius", "INT", 40)
        )

        for ((key, type, invalidVal) in testCases) {
            val json = buildPreferencesJson(
                entries = mapOf<String, Pair<String, Any>>(key to Pair(type, invalidVal))
            )
            val result = BackupPreferencesAdapter.validatePreferencesJson(json)
            assertFalse("Expected range violation failure for $key = $invalidVal", result.isValid)
            assertTrue(result.errorMessage?.contains("Invalid or out-of-range value") == true)
        }
    }

    @Test
    fun validateFailsOnTypeMismatch() {
        val json = buildPreferencesJson(
            entries = mapOf<String, Pair<String, Any>>("pref_pure_black" to Pair("INT", 1))
        )
        val result = BackupPreferencesAdapter.validatePreferencesJson(json)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("Type mismatch") == true)
    }

    @Test
    fun exportIgnoresExcludedAndNonAllowlistedKeys() {
        val prefs = TestSharedPreferences()
        prefs.edit()
            .putBoolean("pref_pure_black", true)
            .putInt("pref_theme", 2)
            .putString("pref_sync_key", "secret-password-123")
            .putString("sync_device_id", "device-uuid-999")
            .putBoolean("pref_developer", true)
            .putBoolean("pref_sync_review_required", true)
            .putString("unknown_key", "ignored")
            .apply()

        val json = BackupPreferencesAdapter.exportPreferences(prefs)
        val obj = JSONObject(json)
        val prefMap = obj.getJSONObject("preferences")

        assertTrue(prefMap.has("pref_pure_black"))
        assertTrue(prefMap.has("pref_theme"))
        assertFalse(prefMap.has("pref_sync_key"))
        assertFalse(prefMap.has("sync_device_id"))
        assertFalse(prefMap.has("pref_developer"))
        assertFalse(prefMap.has("pref_sync_review_required"))
        assertFalse(prefMap.has("unknown_key"))
    }

    @Test
    fun exportIsDeterministicAndSorted() {
        val prefs = TestSharedPreferences()
        prefs.edit()
            .putInt("pref_theme", 1)
            .putBoolean("pref_pure_black", false)
            .putString("pref_app_language", "en")
            .putString("pref_first_weekday", "1")
            .apply()

        val json1 = BackupPreferencesAdapter.exportPreferences(prefs, createdAt = 1700000000000L)
        val json2 = BackupPreferencesAdapter.exportPreferences(prefs, createdAt = 1700000000000L)
        assertEquals(json1, json2)

        val obj = JSONObject(json1)
        val prefMap = obj.getJSONObject("preferences")
        val expectedKeys = setOf("pref_app_language", "pref_first_weekday", "pref_pure_black", "pref_theme")
        assertEquals(expectedKeys, prefMap.keys().asSequence().toSet())
    }

    @Test
    fun importRestoresAllowlistedValues() {
        val prefs = TestSharedPreferences()
        prefs.edit()
            .putInt("pref_theme", 0)
            .putBoolean("pref_pure_black", false)
            .apply()

        val importJson = buildPreferencesJson(
            entries = mapOf(
                "pref_theme" to Pair("INT", 2),
                "pref_pure_black" to Pair("BOOLEAN", true),
                "pref_app_language" to Pair("STRING", "ru")
            )
        )

        BackupPreferencesAdapter.importPreferences(prefs, importJson)

        assertEquals(2, prefs.getInt("pref_theme", -1))
        assertTrue(prefs.getBoolean("pref_pure_black", false))
        assertEquals("ru", prefs.getString("pref_app_language", null))
    }

    @Test
    fun importRejectsUnallowlistedAndUnauthorizedKeys() {
        val prefs = TestSharedPreferences()
        prefs.edit()
            .putBoolean("pref_sync_review_required", true)
            .putString("pref_sync_key", "original-secret")
            .apply()

        val importJson = buildPreferencesJson(
            entries = mapOf(
                "pref_theme" to Pair("INT", 1),
                "pref_sync_review_required" to Pair("BOOLEAN", false),
                "pref_developer" to Pair("BOOLEAN", true)
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupPreferencesAdapter.importPreferences(prefs, importJson)
        }

        // State remains completely untouched
        assertTrue("pref_sync_review_required must remain unchanged after rejected import", prefs.getBoolean("pref_sync_review_required", false))
        assertEquals("original-secret", prefs.getString("pref_sync_key", null))
        assertFalse(prefs.contains("pref_developer"))
    }

    @Test
    fun snapshotAndRollbackRestoresState() {
        val prefs = TestSharedPreferences()
        prefs.edit()
            .putInt("pref_theme", 1)
            .putBoolean("pref_pure_black", false)
            .putString("pref_sync_key", "secret-token") // Excluded key preserved
            .apply()

        val snapshot = BackupPreferencesAdapter.capturePortablePreferencesSnapshot(prefs)

        // Modify preferences
        prefs.edit()
            .putInt("pref_theme", 2)
            .putBoolean("pref_pure_black", true)
            .apply()

        assertEquals(2, prefs.getInt("pref_theme", -1))
        assertTrue(prefs.getBoolean("pref_pure_black", false))

        // Restore snapshot
        BackupPreferencesAdapter.restorePortablePreferencesSnapshot(prefs, snapshot)

        assertEquals(1, prefs.getInt("pref_theme", -1))
        assertFalse(prefs.getBoolean("pref_pure_black", true))
        assertEquals("secret-token", prefs.getString("pref_sync_key", null))
    }
}
