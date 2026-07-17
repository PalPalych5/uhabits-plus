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
package org.isoron.uhabits.inject

import android.content.Context
import kotlinx.coroutines.Dispatchers
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import org.isoron.platform.io.AndroidFileOpener
import org.isoron.platform.io.DatabaseOpener
import org.isoron.platform.io.FileOpener
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlarmScheduler
import org.isoron.uhabits.activities.habits.show.timer.PomodoroCompletionNotifier
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.backup.BackupManager
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.io.GenericImporter
import org.isoron.uhabits.core.io.Logging
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.preferences.WidgetPreferences
import org.isoron.uhabits.core.reminders.ReminderScheduler
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.database.AndroidDatabaseOpener
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.intents.IntentParser
import org.isoron.uhabits.intents.IntentScheduler
import org.isoron.uhabits.intents.PendingIntentFactory
import org.isoron.uhabits.io.AndroidLogging
import org.isoron.uhabits.notifications.AndroidNotificationTray
import org.isoron.uhabits.preferences.DeviceIdentityManager
import org.isoron.uhabits.preferences.SharedPreferencesStorage
import org.isoron.uhabits.receivers.ReminderController
import org.isoron.uhabits.sync.DeviceIdProvider
import org.isoron.uhabits.sync.SupabaseSyncBackend
import org.isoron.uhabits.sync.SyncAuthStore
import org.isoron.uhabits.sync.SyncBackend
import org.isoron.uhabits.sync.SyncCoordinator
import org.isoron.uhabits.utils.DatabaseUtils
import org.isoron.uhabits.widgets.WidgetUpdater
import java.io.File

@AppScope
@Component
abstract class HabitsApplicationComponent(
    @get:Provides @get:AppContext
    val appContext: Context,
    @get:Provides val dbFile: File
) {
    abstract val commandRunner: CommandRunner
    abstract val backupManager: BackupManager

    @get:AppContext
    abstract val context: Context
    abstract val genericImporter: GenericImporter
    abstract val habitCardListCache: HabitCardListCache
    abstract val habitList: HabitList
    abstract val androidNotificationTray: AndroidNotificationTray
    abstract val intentFactory: IntentFactory
    abstract val intentParser: IntentParser
    abstract val logging: Logging
    abstract val midnightTimer: MidnightTimer
    abstract val modelFactory: ModelFactory
    abstract val notificationTray: NotificationTray
    abstract val pendingIntentFactory: PendingIntentFactory
    abstract val preferences: Preferences
    abstract val reminderScheduler: ReminderScheduler
    abstract val reminderController: ReminderController
    abstract val syncCoordinator: SyncCoordinator
    abstract val taskRunner: TaskRunner
    abstract val pomodoroAlarmScheduler: PomodoroAlarmScheduler
    abstract val pomodoroCompletionNotifier: PomodoroCompletionNotifier
    abstract val timerSessionManager: TimerSessionManager
    abstract val widgetPreferences: WidgetPreferences
    abstract val widgetUpdater: WidgetUpdater

    val db: AndroidDatabase
        get() = providedDb

    private val providedDb: AndroidDatabase by lazy {
        AndroidDatabase(DatabaseUtils.openDatabase())
    }

    @AppScope
    @Provides
    open fun preferences(storage: SharedPreferencesStorage): Preferences =
        Preferences(storage)

    @AppScope
    @Provides
    open fun reminderScheduler(
        sys: IntentScheduler,
        commandRunner: CommandRunner,
        habitList: HabitList,
        widgetPreferences: WidgetPreferences
    ): ReminderScheduler =
        ReminderScheduler(commandRunner, habitList, sys, widgetPreferences)

    @AppScope
    @Provides
    open fun notificationTray(
        taskRunner: TaskRunner,
        commandRunner: CommandRunner,
        preferences: Preferences,
        screen: AndroidNotificationTray
    ): NotificationTray =
        NotificationTray(taskRunner, commandRunner, preferences, screen)

    @AppScope
    @Provides
    open fun widgetPreferences(storage: SharedPreferencesStorage): WidgetPreferences =
        WidgetPreferences(storage)

    @AppScope
    @Provides
    open fun sqlModelFactory(deviceIdentityManager: DeviceIdentityManager): SQLModelFactory =
        SQLModelFactory(
            providedDb,
            deviceIdProvider = { deviceIdentityManager.deviceId },
            nowProvider = { System.currentTimeMillis() }
        )

    @AppScope
    @Provides
    open fun modelFactory(sqlModelFactory: SQLModelFactory): ModelFactory = sqlModelFactory

    @AppScope
    @Provides
    open fun syncAuthStore(@AppContext context: Context): SyncAuthStore = SyncAuthStore(context)

    @AppScope
    @Provides
    open fun syncBackend(): SyncBackend = SupabaseSyncBackend()

    @AppScope
    @Provides
    open fun deviceIdProvider(deviceIdentityManager: DeviceIdentityManager): DeviceIdProvider =
        DeviceIdProvider { deviceIdentityManager.deviceId }

    @AppScope
    @Provides
    open fun habitList(list: SQLiteHabitList): HabitList = list

    @AppScope
    @Provides
    open fun pomodoroAlarmScheduler(
        @AppContext context: Context
    ): PomodoroAlarmScheduler = PomodoroAlarmScheduler(context)

    @AppScope
    @Provides
    open fun pomodoroCompletionNotifier(
        @AppContext context: Context,
        habitList: HabitList,
        alarmScheduler: PomodoroAlarmScheduler
    ): PomodoroCompletionNotifier = PomodoroCompletionNotifier(context, habitList, alarmScheduler)

    @AppScope
    @Provides
    open fun timerSessionManager(
        @AppContext context: Context,
        habitList: HabitList,
        commandRunner: CommandRunner,
        preferences: Preferences,
        alarmScheduler: PomodoroAlarmScheduler,
        completionNotifier: PomodoroCompletionNotifier
    ): TimerSessionManager = TimerSessionManager(
        context,
        habitList,
        commandRunner,
        preferences,
        alarmScheduler,
        completionNotifier
    )

    @AppScope
    @Provides
    open fun databaseOpener(opener: AndroidDatabaseOpener): DatabaseOpener = opener

    @AppScope
    @Provides
    open fun logging(): Logging = AndroidLogging()

    @AppScope
    @Provides
    open fun taskRunner(): TaskRunner = CoroutineTaskRunner(
        mainDispatcher = Dispatchers.Main,
        ioDispatcher = Dispatchers.IO
    )

    @AppScope
    @Provides
    open fun fileOpener(): FileOpener =
        AndroidFileOpener(appContext.assets, appContext.filesDir)
}
