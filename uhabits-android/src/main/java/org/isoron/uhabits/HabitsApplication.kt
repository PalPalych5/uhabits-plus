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
class HabitsApplication : Application() {

    private lateinit var context: Context
    private var widgetUpdater: WidgetUpdater? = null
    private var reminderScheduler: ReminderScheduler? = null
    private var notificationTray: NotificationTray? = null

    val sessionManager: DatasetSessionManager by lazy { DatasetSessionManager(this) }

    override fun onCreate() {
        super.onCreate()
        context = this

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!sharedPrefs.contains("pref_app_language")) {
            sharedPrefs.edit().putString("pref_app_language", "ru-RU").apply()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU"))
        } else {
            val appLanguage = sharedPrefs.getString("pref_app_language", "ru-RU") ?: "ru-RU"
            val locales = AppCompatDelegate.getApplicationLocales()
            val currentLanguageTag = if (locales.isEmpty) "" else locales.toLanguageTags()
            if (currentLanguageTag != appLanguage) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(appLanguage))
            }
        }

        val session = sessionManager.resolveSessionOnStartup()

        if (isTestMode()) {
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
    }

    override fun onTerminate() {
        stopServices()
        super.onTerminate()
    }

    fun shutdownForDatabaseRestoreRestart() {
        // A destructive DB restore invalidates every open SQLite/repository/cache handle in the
        // process, so we must shut down and relaunch instead of trying to hot-reload state.
        stopServices()
        runCatching { component.db.close() }
        DatabaseUtils.closeDatabase()
    }

    fun scheduleProcessRestart(destination: MainDestination) {
        val intent = MainActivity.intent(this, destination, showRestoreSuccess = true).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            RESTORE_RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager.setExact(
            AlarmManager.RTC,
            System.currentTimeMillis() + 200,
            pendingIntent
        )
    }

    fun switchDatasetSession(newSession: DatasetSession) {
        rebuildComponent(newSession)
    }

    fun rebuildComponent(session: DatasetSession = sessionManager.resolvedSession()) {
        stopServices()

        if (isComponentInitialized) {
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

        val prefs = component.preferences
        prefs.lastAppVersion = BuildConfig.VERSION_CODE

        setToday(computeToday(component.preferences.midnightDelayHours, 0))

        val habitList = component.habitList
        for (h in habitList) h.recompute()

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
            if (session.capabilities.remindersEnabled) {
                reminderScheduler?.scheduleAll()
            }
            if (session.capabilities.widgetsEnabled) {
                widgetUpdater?.updateWidgets()
            }
        }
    }

    private fun stopServices() {
        reminderScheduler?.stopListening()
        reminderScheduler = null
        widgetUpdater?.stopListening()
        widgetUpdater = null
        notificationTray?.stopListening()
        notificationTray = null
        if (isComponentInitialized) {
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
