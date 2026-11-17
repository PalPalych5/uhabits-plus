/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.preferences

import android.content.SharedPreferences
import org.isoron.uhabits.core.preferences.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesStorageTest {

    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? {
            val v = map[key] ?: return defValue
            if (v !is String) throw ClassCastException("Value is not a String: $v")
            return v
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val v = map[key] ?: return defValues
            @Suppress("UNCHECKED_CAST")
            return v as? MutableSet<String> ?: throw ClassCastException()
        }

        override fun getInt(key: String?, defValue: Int): Int {
            val v = map[key] ?: return defValue
            if (v !is Int) throw ClassCastException("java.lang.String cannot be cast to java.lang.Integer")
            return v
        }

        override fun getLong(key: String?, defValue: Long): Long {
            val v = map[key] ?: return defValue
            if (v !is Long) throw ClassCastException("Value is not a Long: $v")
            return v
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            val v = map[key] ?: return defValue
            if (v !is Float) throw ClassCastException("Value is not a Float: $v")
            return v
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            val v = map[key] ?: return defValue
            if (v !is Boolean) throw ClassCastException("Value is not a Boolean: $v")
            return v
        }

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listeners.remove(listener)
        }
    }

    private class FakeEditor(private val sp: FakeSharedPreferences) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val toRemove = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) pending[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) toRemove.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) sp.map.clear()
            for (k in toRemove) sp.map.remove(k)
            for ((k, v) in pending) sp.map[k] = v
            for (l in sp.listeners.toList()) {
                for (k in pending.keys + toRemove) {
                    l.onSharedPreferenceChanged(sp, k)
                }
            }
        }
    }

    private fun createStorage(fakePrefs: FakeSharedPreferences): SharedPreferencesStorage {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return fakePrefs
            }
        }
        val session = org.isoron.uhabits.core.containers.session.DatasetSessionFactory.production().copy(
            preferencesNamespace = "test_prefs"
        )
        return SharedPreferencesStorage(fakeContext, session)
    }

    @Test
    fun testGetInt_normalIntegerStored_returnsExpectedValue() {
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.map["pref_theme"] = 2
        val storage = createStorage(fakePrefs)

        assertEquals(2, storage.getInt("pref_theme", 0))
    }

    @Test
    fun testGetInt_stringStoredInteger_parsesAndReturnsValue() {
        // ListPreference stores entry values as String (e.g. "1")
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.map["pref_theme"] = "1"
        val storage = createStorage(fakePrefs)

        assertEquals(1, storage.getInt("pref_theme", 0))
    }

    @Test
    fun testGetInt_malformedStringStored_fallsBackToDefaultValueWithoutCrashing() {
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.map["pref_theme"] = "invalid_string_not_a_number"
        val storage = createStorage(fakePrefs)

        assertEquals(42, storage.getInt("pref_theme", 42))
    }

    @Test
    fun testGetInt_missingKey_returnsDefaultValue() {
        val fakePrefs = FakeSharedPreferences()
        val storage = createStorage(fakePrefs)

        assertEquals(99, storage.getInt("non_existent_key", 99))
    }

    @Test
    fun testPutAndGet_primitives() {
        val fakePrefs = FakeSharedPreferences()
        val storage = createStorage(fakePrefs)

        storage.putInt("test_int", 50)
        assertEquals(50, storage.getInt("test_int", 0))

        storage.putBoolean("test_bool", true)
        assertTrue(storage.getBoolean("test_bool", false))

        storage.putLong("test_long", 123456789L)
        assertEquals(123456789L, storage.getLong("test_long", 0L))

        storage.putString("test_str", "hello")
        assertEquals("hello", storage.getString("test_str", ""))

        storage.remove("test_int")
        assertEquals(0, storage.getInt("test_int", 0))
    }
}
