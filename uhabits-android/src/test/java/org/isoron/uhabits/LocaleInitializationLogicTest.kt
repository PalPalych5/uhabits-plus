/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [AppLocaleSync.reconcile] covering first launch, cold restart,
 * in-app language switching, Android 13+ system-managed per-app language changes,
 * controlled legacy preference migration, and pre-Android-13 Application.onCreate ->
 * Activity.onStart idempotency.
 */
class LocaleInitializationLogicTest {

    @Test
    fun firstLaunch_seedsRuRu_pushesToAppCompat_andMarksMigrated() {
        val d = AppLocaleSync.reconcile(
            hasPrefStored = false,
            storedPref = null,
            isMigrated = false,
            appCompatTag = "",
        )
        assertEquals("ru-RU", d.pushToAppCompat)
        assertEquals("ru-RU", d.updatedPref)
        assertTrue(d.markMigrated)
    }

    @Test
    fun firstLaunch_withPreSetSystemAppLanguage_respectsSystemAndSyncsPref() {
        val d = AppLocaleSync.reconcile(
            hasPrefStored = false,
            storedPref = null,
            isMigrated = false,
            appCompatTag = "en",
        )
        assertNull(d.pushToAppCompat)
        assertEquals("en", d.updatedPref)
        assertTrue(d.markMigrated)
    }

    @Test
    fun legacyUpgrade_migratesStoredPreferenceOnce_andMarksMigrated() {
        val d = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = "ru-RU",
            isMigrated = false,
            appCompatTag = "",
        )
        assertEquals("ru-RU", d.pushToAppCompat)
        assertNull(d.updatedPref)
        assertTrue(d.markMigrated)
    }

    @Test
    fun legacyUpgrade_withEmptyStoredPrefSystemDefault_doesNotPushRuRu() {
        val d = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = "",
            isMigrated = false,
            appCompatTag = "",
        )
        assertNull(d.pushToAppCompat)
        assertNull(d.updatedPref)
        assertTrue(d.markMigrated)
    }

    @Test
    fun legacyUpgrade_whenAppCompatAlreadySet_respectsAppCompat() {
        val d = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = "ru-RU",
            isMigrated = false,
            appCompatTag = "de-DE",
        )
        assertNull(d.pushToAppCompat)
        assertEquals("de-DE", d.updatedPref)
        assertTrue(d.markMigrated)
    }

    @Test
    fun coldRestart_afterMigration_whenSynced_isNoOp() {
        val d = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = "ru-RU",
            isMigrated = true,
            appCompatTag = "ru-RU",
        )
        assertNull(d.pushToAppCompat)
        assertNull(d.updatedPref)
        assertFalse(d.markMigrated)
    }

    @Test
    fun systemManagedLanguageChange_neverOverwritesFramework_syncsPrefInstead() {
        // User changed language to English (or cleared to "" system default) in Android 13+ system settings.
        val toEnglish = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = "ru-RU",
            isMigrated = true,
            appCompatTag = "en",
        )
        assertNull(toEnglish.pushToAppCompat)
        assertEquals("en", toEnglish.updatedPref)
        assertFalse(toEnglish.markMigrated)

        val toSystemDefault = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = "en",
            isMigrated = true,
            appCompatTag = "",
        )
        assertNull(toSystemDefault.pushToAppCompat)
        assertEquals("", toSystemDefault.updatedPref)
        assertFalse(toSystemDefault.markMigrated)
    }

    @Test
    fun inAppLanguageSwitch_nextColdStartIsNoOp() {
        // SettingsFragment updated both pref_app_language and AppCompatDelegate to "en".
        val d = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = "en",
            isMigrated = true,
            appCompatTag = "en",
        )
        assertNull(d.pushToAppCompat)
        assertNull(d.updatedPref)
        assertFalse(d.markMigrated)
    }

    @Test
    fun preAndroid13_applicationCreateFollowedByActivityStart_isIdempotent() {
        // Step 1: Application.onCreate() on API < 33 seeds and pushes ru-RU.
        val appCreate = AppLocaleSync.reconcile(
            hasPrefStored = false,
            storedPref = null,
            isMigrated = false,
            appCompatTag = "",
        )
        assertEquals("ru-RU", appCreate.pushToAppCompat)
        assertTrue(appCreate.markMigrated)

        // Step 2: Activity.onStart() runs with AppCompat already holding "ru-RU".
        val activityStart = AppLocaleSync.reconcile(
            hasPrefStored = true,
            storedPref = appCreate.updatedPref,
            isMigrated = appCreate.markMigrated,
            appCompatTag = "ru-RU",
        )
        assertNull(activityStart.pushToAppCompat)
        assertNull(activityStart.updatedPref)
        assertFalse(activityStart.markMigrated)
    }
}
