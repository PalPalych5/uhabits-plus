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

package org.isoron.uhabits

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.database.UnsupportedDatabaseVersionException
import org.isoron.uhabits.core.reminders.ReminderScheduler
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.session.DatasetSessionManager
import org.isoron.uhabits.utils.DatabaseUtils
import org.isoron.uhabits.widgets.WidgetUpdater
import java.io.File

/**
 * The Android application for Loop Habit Tracker.
 */
open class HabitsApplication : Application() {

    private val datasetListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile var isDatasetRebuilding = false
        private set

    fun addDatasetListener(listener: () -> Unit) { datasetListeners.add(listener) }
    fun removeDatasetListener(listener: () -> Unit) { datasetListeners.remove(listener) }
    private fun notifyDatasetListeners() { datasetListeners.forEach { runCatching { it() } } }

    private lateinit var context: Context
    private var widgetUpdater: WidgetUpdater? = null
    private var reminderScheduler: ReminderScheduler? = null
    private var notificationTray: NotificationTray? = null

    val sessionManager: DatasetSessionManager by lazy { DatasetSessionManager(this) }

    override fun onCreate() {
        org.isoron.uhabits.utils.HabitsStartupTrace.install(this)
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("application.create.begin")
        super.onCreate()
        context = this

        // PR24A: reconcile any interrupted database restore before DI or SQLite opening can create an empty database
        DatabaseUtils.reconcileInterruptedRestore(this)

        // PR25: register persisted cutover gate check
        org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.registerPersistedCutoverCheck {
            sessionManager.isProductionNextSelected()
        }

        // PR21 explicit preparation/recovery must inspect legacy state before normal session
        // resolution, DB opening, sync, or timer constructors can write. No migration follows it.
        org.isoron.uhabits.production.ProductionPreparationStartup.runBeforeDependencyInjection(this)
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("application.recovery.end")

        // Per-app language initialisation & controlled migration.
        //
        // AppCompatDelegate.setApplicationLocales() is a no-op on API 33+ during
        // Application.onCreate() because sActivityDelegates is empty until an AppCompatActivity
        // is created. On API < 33 it works immediately in Application.onCreate().
        //
        // Once migrated (KEY_LOCALE_MIGRATED = true), AppCompat / framework LocaleManager is the
        // source of truth: we never overwrite framework-managed per-app locales back to
        // pref_app_language; instead we sync pref_app_language from AppCompat if the user
        // changed the language in Android 13+ system settings.
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            applyLocaleDecision(sharedPrefs)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) {
                if (activity !is androidx.appcompat.app.AppCompatActivity) return
                applyLocaleDecision(sharedPrefs)
            }
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })

        org.isoron.uhabits.utils.HabitsStartupTrace.mark("session.resolve.begin")
        val session = org.isoron.uhabits.utils.HabitsStartupTrace.offlineFixtureSession(this, sessionManager.resolveSessionOnStartup())
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("session.resolve.end")

        // Ordinary legacy tests start empty. Persisted Next datasets must survive
        // instrumentation process restarts (and must never be silently purged).
        if (isTestMode() && !session.isNextArchitecture) {
            val db = DatabaseUtils.getDatabaseFile(context, session.databaseFilename)
            if (db.exists()) db.delete()
        }

        try {
            DatabaseUtils.initializeDatabase(context, session.databaseFilename)
        } catch (e: UnsupportedDatabaseVersionException) {
            val db = DatabaseUtils.getDatabaseFile(context, session.databaseFilename)
            db.renameTo(File(db.absolutePath + ".invalid"))
            DatabaseUtils.initializeDatabase(context, session.databaseFilename)
        }

        rebuildComponent(session)
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("application.create.end")
    }

    override fun onTerminate() {
        stopServices()
        super.onTerminate()
    }

    fun shutdownForDatabaseRestoreRestart() {
        isDatasetRebuilding = true
        notifyDatasetListeners()
        // A destructive DB restore invalidates every open SQLite/repository/cache handle in the
        // process, so we must shut down and relaunch instead of trying to hot-reload state.
        stopServices()
        runCatching { component.db.close() }
        DatabaseUtils.closeDatabase()
    }

    fun scheduleProcessRestart(destination: MainDestination, showRestoreSuccess: Boolean = true) {
        val intent = MainActivity.intent(this, destination, showRestoreSuccess = showRestoreSuccess).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            RESTORE_RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(
                AlarmManager.RTC,
                System.currentTimeMillis() + 200,
                pendingIntent
            )
        } else {
            try {
                alarmManager.setExact(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 200,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                alarmManager.set(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 200,
                    pendingIntent
                )
            }
        }
    }

    fun switchDatasetSession(newSession: DatasetSession) {
        rebuildComponent(newSession)
    }

    /** Internal preparation entry. It restarts; it never snapshots a live writer graph. */
    fun requestProductionRecoveryPreparation(): Boolean {
        if (component.session.mode != org.isoron.uhabits.core.containers.session.DatasetMode.LEGACY_PRODUCTION) return false
        if (!org.isoron.uhabits.production.ProductionPreparationStartup.requestAtNextColdStart(this)) return false
        scheduleProcessRestart(MainDestination.HABITS, showRestoreSuccess = false)
        android.os.Process.killProcess(android.os.Process.myPid())
        return true
    }

    fun rebuildComponent(session: DatasetSession = sessionManager.resolvedSession()) {
        isDatasetRebuilding = true
        notifyDatasetListeners()
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("component.rebuild.begin")
        stopServices()

        if (isComponentInitialized) {
            component.habitCardListCache.shutdown()
            runCatching { component.effectiveHabitReads?.dispose() }
            runCatching { component.focusCreditController?.dispose() }
            runCatching { component.syncCoordinator.dispose() }
            runCatching { component.timerSessionManager.dispose() }
            runCatching { component.db.close() }
        }

        DatabaseUtils.closeDatabase()
        DatabaseUtils.initializeDatabase(this, session.databaseFilename)

        val db = DatabaseUtils.getDatabaseFile(this, session.databaseFilename)
        HabitsApplication.component = HabitsApplicationComponent::class.create(
            appContext = context,
            dbFile = db,
            session = session
        )
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("component.construct.end")

        val prefs = component.preferences
        prefs.lastAppVersion = BuildConfig.VERSION_CODE

        setToday(currentHabitDate(component.preferences.midnightDelayHours, 0))
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("application.calendar.ready")

        val habitList = component.habitList
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("application.habits.begin")
        // SQLiteHabitList hydrates and recomputes every model on its first read,
        // after the session's calendar has been installed above. Force that read
        // without immediately repeating all history/score/streak calculations.
        habitList.size()
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("application.habits.end")
        component.effectiveHabitReads
        component.habitCardListCache.timingObserver = if (org.isoron.uhabits.utils.HabitsStartupTrace.enabled)
            { stage, ms -> org.isoron.uhabits.utils.HabitsStartupTrace.mark("duration.$stage", durationMs = ms) } else null
        component.focusCreditController?.startRecovery()

        if (session.capabilities.widgetsEnabled) {
            widgetUpdater = component.widgetUpdater.apply {
                startListening()
                scheduleStartDayWidgetUpdate()
            }
        } else {
            widgetUpdater = null
        }

        if (session.capabilities.remindersEnabled) {
            reminderScheduler = component.reminderScheduler.apply {
                startListening()
            }

            notificationTray = component.notificationTray.apply {
                startListening()
            }
        } else {
            reminderScheduler = null
            notificationTray = null
        }

        component.taskRunner.execute {
            if (session.capabilities.timerEnabled) {
                runCatching { component.timerSessionManager }
            }
            if (session.capabilities.remindersEnabled) {
                reminderScheduler?.scheduleAll()
            }
            if (session.capabilities.widgetsEnabled) {
                widgetUpdater?.updateWidgets()
            }
        }
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("component.rebuild.end")
        isDatasetRebuilding = false
        notifyDatasetListeners()
    }

    private fun applyLocaleDecision(sharedPrefs: android.content.SharedPreferences) {
        val locales = AppCompatDelegate.getApplicationLocales()
        val appCompatTag = if (locales.isEmpty) "" else locales.toLanguageTags()
        val decision = AppLocaleSync.reconcile(
            hasPrefStored = sharedPrefs.contains(AppLocaleSync.PREF_APP_LANGUAGE),
            storedPref = sharedPrefs.getString(AppLocaleSync.PREF_APP_LANGUAGE, null),
            isMigrated = sharedPrefs.getBoolean(AppLocaleSync.PREF_LOCALE_MIGRATED, false),
            appCompatTag = appCompatTag,
        )
        if (decision.updatedPref != null || decision.markMigrated) {
            sharedPrefs.edit().apply {
                if (decision.updatedPref != null) {
                    putString(AppLocaleSync.PREF_APP_LANGUAGE, decision.updatedPref)
                }
                if (decision.markMigrated) {
                    putBoolean(AppLocaleSync.PREF_LOCALE_MIGRATED, true)
                }
            }.apply()
        }
        if (decision.pushToAppCompat != null) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(decision.pushToAppCompat)
            )
        }
    }

    /** Variant seam; ordinary builds retain the live local calendar and offset behavior. */
    internal open fun currentHabitDate(hourOffset: Int, minuteOffset: Int) = computeToday(hourOffset, minuteOffset)

    private fun stopServices() {
        reminderScheduler?.stopListening()
        reminderScheduler = null
        widgetUpdater?.stopListening()
        widgetUpdater = null
        notificationTray?.cancelAll()
        notificationTray?.stopListening()
        notificationTray = null
        if (isComponentInitialized) {
            component.widgetRenderer.shutdown()
            runCatching { component.midnightTimer.onPause() }
            runCatching { component.effectiveHabitReads?.dispose() }
            runCatching { component.focusCreditController?.dispose() }
            runCatching { component.syncCoordinator.dispose() }
            runCatching { component.timerSessionManager.dispose() }
        }
    }

    val component: HabitsApplicationComponent
        get() = HabitsApplication.component

    companion object {
        private const val RESTORE_RESTART_REQUEST_CODE = 2001
        lateinit var component: HabitsApplicationComponent
        val isComponentInitialized: Boolean
            get() = ::component.isInitialized

        fun isTestMode(): Boolean {
            return try {
                Class.forName("org.isoron.uhabits.BaseAndroidTest")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }
}

/**
 * Pure, testable precedence rules for per-app language initialization and migration.
 *
 * Precedence:
 * 1. Before one-time migration (`!isMigrated`):
 *    - If AppCompat/framework already has a non-empty locale (e.g., set via Android 13+ system
 *      settings before first launch), adopt it into `pref_app_language` and mark migrated.
 *    - Otherwise seed `ru-RU` on fresh install or migrate the non-empty legacy `pref_app_language`
 *      into AppCompat once, and mark migrated.
 * 2. After migration (`isMigrated == true`):
 *    - AppCompat / framework LocaleManager is authoritative. Never overwrite it back to
 *      `pref_app_language`. If `appCompatTag` differs from `storedPref` (e.g., user changed
 *      language in Android 13+ system settings), update `pref_app_language` to match `appCompatTag`.
 */
internal object AppLocaleSync {
    const val PREF_APP_LANGUAGE = "pref_app_language"
    const val PREF_LOCALE_MIGRATED = "pref_app_language_migrated_v1"
    const val DEFAULT_LANGUAGE = "ru-RU"

    data class Decision(
        val pushToAppCompat: String? = null,
        val updatedPref: String? = null,
        val markMigrated: Boolean = false,
    )

    fun reconcile(
        hasPrefStored: Boolean,
        storedPref: String?,
        isMigrated: Boolean,
        appCompatTag: String,
    ): Decision {
        if (!isMigrated) {
            if (appCompatTag.isNotEmpty()) {
                return Decision(
                    pushToAppCompat = null,
                    updatedPref = if (!hasPrefStored || storedPref != appCompatTag) appCompatTag else null,
                    markMigrated = true,
                )
            }
            val target = if (!hasPrefStored) {
                DEFAULT_LANGUAGE
            } else {
                storedPref?.takeIf { it.isNotEmpty() }
            }
            return Decision(
                pushToAppCompat = target,
                updatedPref = if (!hasPrefStored) DEFAULT_LANGUAGE else null,
                markMigrated = true,
            )
        }

        // Post-migration: framework / AppCompat is authoritative.
        val normalizedStored = storedPref.orEmpty()
        return if (normalizedStored != appCompatTag) {
            Decision(
                pushToAppCompat = null,
                updatedPref = appCompatTag,
                markMigrated = false,
            )
        } else {
            Decision()
        }
    }
}
