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
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusCaptureStore
import org.isoron.uhabits.core.focus.runtime.FocusCaptureTarget
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.containers.ContainerId
import org.json.JSONObject
import org.json.JSONArray
import org.isoron.uhabits.backup.BackupManager
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.OrganizationServiceImpl
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteHabitVisualIdentityStore
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import org.isoron.uhabits.core.userTasks.UserTaskService
import org.isoron.uhabits.core.userTasks.UserTaskServiceImpl
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
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
import org.isoron.uhabits.session.DisabledSyncBackend
import org.isoron.uhabits.sync.DeviceIdProvider
import org.isoron.uhabits.sync.SupabaseSyncBackend
import org.isoron.uhabits.sync.SyncAuthStore
import org.isoron.uhabits.sync.SyncBackend
import org.isoron.uhabits.sync.SyncCoordinator
import org.isoron.uhabits.utils.DatabaseUtils
import org.isoron.uhabits.widgets.WidgetUpdater
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@AppScope
@Component
abstract class HabitsApplicationComponent(
    @get:Provides @get:AppContext
    val appContext: Context,
    @get:Provides val dbFile: File,
    @get:Provides val session: DatasetSession = DatasetSessionFactory.production()
) {
    abstract val commandRunner: CommandRunner
    abstract val backupManager: BackupManager

    @get:AppContext
    abstract val context: Context
    abstract val genericImporter: GenericImporter
    abstract val habitCardListCache: HabitCardListCache
    abstract val habitList: HabitList
    abstract val organizationFacade: HabitOrganizationFacade
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
    abstract val userTaskRepository: UserTaskRepository
    abstract val userTaskService: UserTaskService

    val focusCreditController: org.isoron.uhabits.focus.FocusCreditController? by lazy {
        if (session.isExperimental) org.isoron.uhabits.focus.FocusCreditController(
            providedDb, habitList, organizationFacade, preferences
        ) else null
    }

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
    open fun sqlModelFactory(deviceIdentityManager: DeviceIdentityManager): SQLModelFactory {
        val factory = SQLModelFactory(
            providedDb,
            deviceIdProvider = { deviceIdentityManager.deviceId },
            nowProvider = { System.currentTimeMillis() }
        )
        factory.habitBlockRepository.authorityMode = session.organizationAuthority
        factory.syncManager.authorityMode = session.organizationAuthority
        return factory
    }

    @AppScope
    @Provides
    open fun modelFactory(sqlModelFactory: SQLModelFactory): ModelFactory = sqlModelFactory

    @AppScope
    @Provides
    open fun syncAuthStore(@AppContext context: Context): SyncAuthStore = SyncAuthStore(context)

    @AppScope
    @Provides
    open fun syncBackend(): SyncBackend =
        if (!session.capabilities.remoteCallsAllowed) {
            DisabledSyncBackend
        } else {
            SupabaseSyncBackend()
        }

    @AppScope
    @Provides
    open fun deviceIdProvider(deviceIdentityManager: DeviceIdentityManager): DeviceIdProvider =
        DeviceIdProvider { deviceIdentityManager.deviceId }

    @AppScope
    @Provides
    open fun habitOrganizationFacade(
        sqlModelFactory: SQLModelFactory
    ): HabitOrganizationFacade {
        return if (session.organizationAuthority == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            OrganizationSchema.createSchema(providedDb)
            val store = SQLiteOrganizationStore(providedDb)
            val visualIdentityStore = SQLiteHabitVisualIdentityStore(providedDb)
            val service = OrganizationServiceImpl(
                store = store,
                unitOfWork = store,
                containerQueries = store,
                placementQueries = store,
                clock = { System.currentTimeMillis() },
                idGenerator = { Uuid.random().toHexString() },
                habitIdentityLookup = { habitRef -> sqlModelFactory.habitRepository.findByUuid(habitRef.uuid) != null }
            )
            HabitOrganizationFacadeImpl(
                mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
                containerQueries = store,
                habitPlacementQueries = store,
                organizationService = service,
                visualIdentityStore = visualIdentityStore,
                habitBlockRepository = sqlModelFactory.habitBlockRepository
            )
        } else {
            HabitOrganizationFacadeImpl(
                mode = OrganizationAuthorityMode.LEGACY,
                habitBlockRepository = sqlModelFactory.habitBlockRepository
            )
        }
    }

    @AppScope
    @Provides
    open fun habitList(list: SQLiteHabitList, facade: HabitOrganizationFacade): HabitList {
        list.organizationFacade = facade
        return list
    }

    @AppScope
    @Provides
    open fun userTaskRepository(): UserTaskRepository {
        UserTaskSchema.createSchema(providedDb)
        return SQLiteUserTaskRepository(providedDb)
    }

    @AppScope
    @Provides
    open fun userTaskService(repository: UserTaskRepository): UserTaskService {
        return UserTaskServiceImpl(
            repository = repository,
            clock = { System.currentTimeMillis() },
            idGenerator = { Uuid.random().toHexString() }
        )
    }

    @AppScope
    @Provides
    open fun pomodoroAlarmScheduler(
        @AppContext context: Context
    ): PomodoroAlarmScheduler = PomodoroAlarmScheduler(context, session.datasetId)

    @AppScope
    @Provides
    open fun pomodoroCompletionNotifier(
        @AppContext context: Context,
        habitList: HabitList,
        alarmScheduler: PomodoroAlarmScheduler
    ): PomodoroCompletionNotifier = PomodoroCompletionNotifier(context, habitList, alarmScheduler, session.datasetId)

    @AppScope
    @Provides
    open fun timerSessionManager(
        @AppContext context: Context,
        habitList: HabitList,
        commandRunner: CommandRunner,
        preferences: Preferences,
        alarmScheduler: PomodoroAlarmScheduler,
        completionNotifier: PomodoroCompletionNotifier,
        facade: HabitOrganizationFacade,
        deviceIdentityManager: DeviceIdentityManager
    ): TimerSessionManager {
        val capture = if (session.isExperimental) {
            FocusSessionSchema.createSchema(providedDb)
            requireNotNull(focusCreditController).captureStore(SQLiteFocusCaptureStore(providedDb))
        } else null
        fun target(kind: FocusPrimaryTargetType, id: String, container: ContainerId?): FocusCaptureTarget {
            val path = container?.let { facade.containerQueries?.path(it) }
            val json = JSONObject().put("schema", 1).put("datasetId", session.datasetId)
                .put("organizationRevision", path?.revision?.value ?: SQLiteOrganizationStore(providedDb).readState().currentRevision.value)
                .put("path", JSONArray().apply {
                    path?.nodes?.forEach { node -> put(JSONObject().put("id", node.value)
                        .put("name", facade.containerQueries?.find(node, includeDeleted = true)?.name ?: JSONObject.NULL)) }
                })
            return FocusCaptureTarget(kind, id, container, json.toString())
        }
        return TimerSessionManager(
        context = context,
        habitList = habitList,
        commandRunner = commandRunner,
        appPreferences = preferences,
        alarmScheduler = alarmScheduler,
        completionNotifier = completionNotifier,
        session = session,
        captureStore = capture,
        deviceId = if (session.isExperimental) deviceIdentityManager.deviceId else null,
        focusTargetProvider = { habitId ->
            if (!session.isExperimental) null else habitList.getById(habitId)?.let { habit ->
                val uuid = requireNotNull(habit.uuid)
                target(FocusPrimaryTargetType.HABIT, uuid, facade.getPlacementInfo(uuid).containerId)
            }
        },
        captureTargetResolver = { kind, id ->
            if (!session.isExperimental) null else when (kind) {
                FocusPrimaryTargetType.TASK -> SQLiteUserTaskRepository(providedDb).getById(org.isoron.uhabits.core.userTasks.TaskId(id))
                    ?.let { it.title to target(kind, id, it.containerId) }
                FocusPrimaryTargetType.CONTAINER -> facade.containerQueries?.find(ContainerId(id))
                    ?.takeUnless { facade.containerQueries?.isEffectivelyArchived(it.id) == true }
                    ?.let { it.name to target(kind, id, it.id) }
                FocusPrimaryTargetType.HABIT -> null // Existing Habit UI is the primary entry.
            }
        }
        )
    }

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
