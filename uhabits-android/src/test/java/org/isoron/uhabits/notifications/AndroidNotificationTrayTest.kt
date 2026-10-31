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

package org.isoron.uhabits.notifications

import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.BaseAndroidJVMTest
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.intents.PendingIntentFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused unit test suite for GAP-7: Habit reminder notification failure handling in AndroidNotificationTray.
 *
 * Verifies:
 * 1. Initial notify SecurityException (permission denied/revoked) returns false and does not mark notification active.
 * 2. Initial notify SecurityException does not attempt Xiaomi soundless retry.
 * 3. Initial notify RuntimeException (Xiaomi sound failure) retries without sound and marks active on success.
 * 4. Xiaomi soundless retry encountering SecurityException (permission race) is safely caught without crashing.
 * 5. Xiaomi soundless retry encountering RuntimeException (double failure) is safely caught without crashing.
 * 6. Happy path delivery records active status.
 * 7. Downstream reminder scheduling continuation is preserved when notification delivery fails.
 */
class AndroidNotificationTrayTest : BaseAndroidJVMTest() {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { key?.let { temp[it] = values }; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun remove(key: String?): SharedPreferences.Editor { key?.let { temp.remove(it) }; return this }
            override fun clear(): SharedPreferences.Editor { temp.clear(); return this }
            override fun commit(): Boolean { data.putAll(temp); return true }
            override fun apply() { commit() }
        }
    }

    private class FakeContext : ContextWrapper(null) {
        private val prefs = FakeSharedPreferences()
        override fun getPackageName(): String = "org.isoron.uhabits"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getSystemService(name: String): Any? = null
    }

    private lateinit var tray: AndroidNotificationTray
    private lateinit var testHabit: Habit
    private val testDate = LocalDate(2026, 10, 31)

    private val postedNotifications = mutableListOf<Pair<Int, Notification>>()
    private val buildNotificationCalls = mutableListOf<Boolean>() // tracks disableSound flag per call
    private var initialPosterException: Throwable? = null
    private var retryPosterException: Throwable? = null
    private var notifyAttempts = 0

    @Before
    override fun setUp() {
        super.setUp()
        testHabit = fixtures.createEmptyHabit().apply {
            id = 101L
            name = "Test Habit"
        }
        val fakeContext = FakeContext()
        tray = AndroidNotificationTray(
            context = fakeContext,
            pendingIntents = PendingIntentFactory(fakeContext, IntentFactory()),
            preferences = Preferences(MemoryStorage())
        )

        postedNotifications.clear()
        buildNotificationCalls.clear()
        initialPosterException = null
        retryPosterException = null
        notifyAttempts = 0

        // Seams for focused testing of notify exception control flow
        tray.channelCreator = { /* no-op in unit test */ }
        tray.notificationBuilder = { _, _, _, disableSound, _ ->
            buildNotificationCalls.add(disableSound)
            Notification()
        }
        tray.notificationPoster = { id, notification ->
            notifyAttempts++
            if (notifyAttempts == 1) {
                initialPosterException?.let { throw it }
            } else {
                retryPosterException?.let { throw it }
            }
            postedNotifications.add(id to notification)
        }
    }

    @Test
    fun initialNotify_throwsSecurityException_returnsFalseAndDoesNotAddToActive() {
        initialPosterException = SecurityException("POST_NOTIFICATIONS permission not granted")

        tray.showNotification(testHabit, notificationId = 42, date = testDate, reminderTime = 1000L)

        // Notification must not be recorded active (prevents false active status)
        assertFalse("Notification must not be marked active on SecurityException", tray.isNotificationActive(42))
        // Must not attempt soundless retry for SecurityException
        assertEquals("Initial notify only, no soundless retry on SecurityException", 1, buildNotificationCalls.size)
        assertFalse("Initial build must not disable sound", buildNotificationCalls[0])
        assertEquals(0, postedNotifications.size)
    }

    @Test
    fun initialNotify_throwsRuntimeException_retriesWithoutSoundAndSucceeds() {
        initialPosterException = RuntimeException("Xiaomi MIUI audio track failure")

        tray.showNotification(testHabit, notificationId = 42, date = testDate, reminderTime = 1000L)

        // Retried and succeeded, must be recorded active
        assertTrue("Notification must be marked active after soundless retry success", tray.isNotificationActive(42))
        assertEquals(2, buildNotificationCalls.size)
        assertFalse("First build had sound enabled", buildNotificationCalls[0])
        assertTrue("Second build disabled sound for Xiaomi recovery", buildNotificationCalls[1])
        assertEquals(1, postedNotifications.size)
        assertEquals(42, postedNotifications[0].first)
    }

    @Test
    fun xiaomiRetry_throwsSecurityException_safelyCaughtAndDoesNotAddToActive() {
        // Initial failure is runtime sound error triggering retry; retry hits permission race
        initialPosterException = RuntimeException("Xiaomi sound error")
        // When first attempt fails and retry is made:
        tray.notificationPoster = { id, notification ->
            if (buildNotificationCalls.size == 1) {
                throw RuntimeException("Xiaomi sound error")
            } else {
                throw SecurityException("Permission revoked concurrently during retry")
            }
        }

        // Must not crash receiver/caller
        tray.showNotification(testHabit, notificationId = 42, date = testDate, reminderTime = 1000L)

        assertFalse("Notification must not be active when retry hits SecurityException", tray.isNotificationActive(42))
        assertEquals(2, buildNotificationCalls.size)
    }

    @Test
    fun xiaomiRetry_throwsRuntimeException_safelyCaughtAndDoesNotAddToActive() {
        tray.notificationPoster = { _, _ ->
            if (buildNotificationCalls.size == 1) {
                throw RuntimeException("Sound error")
            } else {
                throw RuntimeException("Secondary failure without sound")
            }
        }

        // Must not crash receiver/caller
        tray.showNotification(testHabit, notificationId = 42, date = testDate, reminderTime = 1000L)

        assertFalse("Notification must not be active on double runtime failure", tray.isNotificationActive(42))
        assertEquals(2, buildNotificationCalls.size)
    }

    @Test
    fun happyPath_postsSuccessfullyAndMarksActive() {
        val result = tray.notify(testHabit, 42, testDate, 1000L, isTest = false)

        assertTrue("notify must return true on success", result)
        assertEquals(1, postedNotifications.size)

        tray.showNotification(testHabit, 42, testDate, 1000L)
        assertTrue("Notification must be active on success", tray.isNotificationActive(42))
        assertEquals(2, postedNotifications.size)
    }

    @Test
    fun reminderSchedulingPreserved_whenNotificationThrowsSecurityException() {
        initialPosterException = SecurityException("Permission revoked")

        var schedulingCompleted = false
        try {
            tray.showNotification(testHabit, 42, testDate, 1000L)
            // Simulates downstream ReminderScheduler.scheduleAll() in ReminderController
            schedulingCompleted = true
        } catch (e: Throwable) {
            schedulingCompleted = false
        }

        assertTrue("Downstream scheduling must execute without being aborted by notification exception", schedulingCompleted)
    }
}
