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
 * Loop Habit Tracker is distributed in the hope it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.sync

import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.*
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationChangeRecord
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.sync.SyncQueueRecord
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTaskServiceImpl
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.isoron.uhabits.widgets.WidgetUpdater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class SyncCoordinatorNextTest {

    @Before
    fun setUp() {
        setToday(LocalDate(2026, 10, 10))
    }

    class FakeContext : android.content.ContextWrapper(null)

    class FakeSyncAuthStore(context: android.content.Context) : SyncAuthStore(context) {
        private var session: SyncSession? = null
        override fun save(session: SyncSession) { this.session = session }
        override fun load(): SyncSession? = session
        override fun clear() { this.session = null }
    }

    class FakeWidgetUpdater(context: android.content.Context) : WidgetUpdater(
        context,
        CommandRunner(CoroutineTaskRunner(Dispatchers.Unconfined, Dispatchers.Unconfined)),
        CoroutineTaskRunner(Dispatchers.Unconfined, Dispatchers.Unconfined),
        mock(),
        mock(),
        mock()
    ) {
        override fun updateWidgets() {}
        override fun updateWidgets(modifiedHabitId: Long?) {}
        override fun onCommandFinished(command: org.isoron.uhabits.core.commands.Command) {}
    }

    class InMemorySyncBackend : SyncBackend {
        val remoteLogs = mutableListOf<RemoteSyncEvent>()
        private var nextLogId = 1L

        override suspend fun signIn(config: SupabaseSyncConfig, email: String, password: String): SyncSession {
            return SyncSession("test-token", "test-refresh", "test-user-id", email, System.currentTimeMillis() / 1000 + 3600)
        }

        override suspend fun refreshSession(config: SupabaseSyncConfig, session: SyncSession): SyncSession = session

        override suspend fun signOut(session: SyncSession, config: SupabaseSyncConfig) {}

        override suspend fun pushChanges(
            config: SupabaseSyncConfig,
            session: SyncSession,
            deviceId: String,
            records: List<SyncQueueRecord>
        ): SyncPushResult {
            for (record in records) {
                remoteLogs.add(
                    RemoteSyncEvent(
                        logId = nextLogId++,
                        opUuid = record.opUuid,
                        entityType = record.entityType,
                        entityUuid = record.entityUuid,
                        operationType = record.operationType,
                        payloadJson = record.payloadJson,
                        createdAt = record.createdAt,
                        deviceId = deviceId
                    )
                )
            }
            return SyncPushResult(records.mapNotNull { it.queueId })
        }

        override suspend fun pullChanges(
            config: SupabaseSyncConfig,
            session: SyncSession,
            lastLogId: Long
        ): SyncPullResult {
            val newEvents = remoteLogs.filter { it.logId > lastLogId }
            val latest = remoteLogs.lastOrNull()?.logId ?: lastLogId
            return SyncPullResult(newEvents, latest)
        }
    }

    private suspend fun createTestDatabase(): Database {
        val db = JavaDatabaseOpener().open(":memory:")
        db.setVersion(8)

        var migrationsDir = File("../uhabits-core/assets/main/migrations")
        if (!migrationsDir.exists()) {
            migrationsDir = File("uhabits-core/assets/main/migrations")
        }
        if (!migrationsDir.exists()) {
            migrationsDir = File("c:/Users/Pavel/source/repos/uhabits-plus/uhabits-core/assets/main/migrations")
        }

        db.migrateTo(DATABASE_VERSION) { v ->
            val file = File(migrationsDir, "%02d.sql".format(v))
            if (!file.exists()) {
                throw java.io.FileNotFoundException("Migration file not found: ${file.absolutePath}")
            }
            file.readText()
        }
        return db
    }

    data class DeviceEnvironment(
        val db: Database,
        val modelFactory: SQLModelFactory,
        val coordinator: SyncCoordinator,
        val orgStore: SQLiteOrganizationStore,
        val taskRepo: SQLiteUserTaskRepository,
        val taskService: UserTaskServiceImpl,
        val focusRepo: SQLiteFocusSessionRepository,
        val contribRepo: SQLiteContributionRepository
    )

    private fun setupDeviceEnvironment(
        db: Database,
        backend: SyncBackend,
        deviceId: String,
        session: DatasetSession = DatasetSessionFactory.productionNext()
    ): DeviceEnvironment {
        val modelFactory = SQLModelFactory(
            db,
            deviceIdProvider = { deviceId },
            nowProvider = { System.currentTimeMillis() }
        )
        modelFactory.syncManager.authorityMode = session.organizationAuthority

        val habitList = SQLiteHabitList(modelFactory)
        val preferences = Preferences(MemoryStorage()).apply {
            isSyncEnabled = true
            syncSupabaseUrl = "https://example.supabase.co"
            syncSupabaseAnonKey = "anon-key-123"
        }
        val context = FakeContext()
        val authStore = FakeSyncAuthStore(context)
        authStore.save(SyncSession("token", "refresh", "user-1", "user@example.com", 9999999999L))

        val widgetUpdater = FakeWidgetUpdater(context)
        val commandRunner = CommandRunner(
            CoroutineTaskRunner(Dispatchers.Unconfined, Dispatchers.Unconfined)
        )

        val coordinator = SyncCoordinator(
            context = context,
            modelFactory = modelFactory,
            habitList = habitList,
            preferences = preferences,
            authStore = authStore,
            backend = backend,
            deviceIdProvider = { deviceId },
            widgetUpdater = widgetUpdater,
            commandRunner = commandRunner,
            session = session
        )

        OrganizationSchema.createSchema(db)
        val orgStore = SQLiteOrganizationStore(db, syncManager = modelFactory.syncManager)
        orgStore.seedBaseline()

        UserTaskSchema.createSchema(db)
        val taskRepo = SQLiteUserTaskRepository(db)
        val taskService = UserTaskServiceImpl(
            repository = taskRepo,
            clock = { System.currentTimeMillis() },
            idGenerator = { UUID.randomUUID().toString() },
            syncManager = modelFactory.syncManager
        )

        FocusSessionSchema.createSchema(db)
        val focusRepo = SQLiteFocusSessionRepository(db)

        ContributionSchema.createSchema(db)
        val contribRepo = SQLiteContributionRepository(db)

        return DeviceEnvironment(
            db = db,
            modelFactory = modelFactory,
            coordinator = coordinator,
            orgStore = orgStore,
            taskRepo = taskRepo,
            taskService = taskService,
            focusRepo = focusRepo,
            contribRepo = contribRepo
        )
    }

    @Test
    fun testBidirectionalOrganizationAndTasksSync() = runBlocking {
        val sharedBackend = InMemorySyncBackend()

        // Device A
        val dbA = createTestDatabase()
        val envA = setupDeviceEnvironment(dbA, sharedBackend, "device-A")

        // 1. Device A creates root container "Work" and child container "Project X"
        val rootUuid = "work-root-uuid"
        val childUuid = "project-x-uuid"
        val now = 1000L

        envA.orgStore.saveContainer(
            Container(
                id = ContainerId(rootUuid),
                parentId = null,
                name = "Work",
                color = PaletteColor(1),
                icon = "briefcase",
                siblingOrder = 0,
                isArchived = false,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                revision = OrganizationRevision(1L)
            )
        )

        envA.orgStore.saveContainer(
            Container(
                id = ContainerId(childUuid),
                parentId = ContainerId(rootUuid),
                name = "Project X",
                color = PaletteColor(2),
                icon = "folder",
                siblingOrder = 0,
                isArchived = false,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                revision = OrganizationRevision(1L)
            )
        )

        // 2. Device A creates a Habit and places it in "Project X"
        val habitUuid = "habit-write-spec"
        val habitId = envA.modelFactory.habitRepository.insert(
            HabitData(
                id = 1L,
                name = "Write Specs",
                description = "Daily specs",
                question = "Did you write specs?",
                color = 2,
                position = 0,
                archived = 0,
                type = 0,
                freqNum = 1,
                freqDen = 1,
                targetValue = 0.0,
                targetType = 0,
                unit = "",
                uuid = habitUuid,
                updatedAt = now
            )
        )
        envA.modelFactory.syncManager.enqueueHabitCreate(
            envA.modelFactory.buildHabit().apply {
                id = habitId
                uuid = habitUuid
                name = "Write Specs"
            }
        )
        envA.orgStore.saveHabitPlacement(
            HabitPlacement(
                habit = HabitRef(habitUuid),
                containerId = ContainerId(childUuid),
                localOrder = 0,
                origin = PlacementOrigin.USER_CHANGE,
                revision = OrganizationRevision(1L)
            )
        )

        // 3. Device A creates a Task in "Project X"
        val task = envA.taskService.createTask(
            title = "Submit RFC",
            description = "RFC for Next Sync",
            containerId = ContainerId(childUuid)
        )

        // 4. Device A syncs (pushes changes to backend)
        val syncResultA = envA.coordinator.runSync(manual = true)
        assertTrue("Sync A must succeed: $syncResultA", syncResultA is SyncRunResult.Success)
        assertTrue("Sync A must push at least 4 records", (syncResultA as SyncRunResult.Success).pushed >= 4)

        // Verify backend contains the logs
        val backendLogs = sharedBackend.remoteLogs
        assertTrue(backendLogs.any { it.entityType == "container" && it.entityUuid == rootUuid })
        assertTrue(backendLogs.any { it.entityType == "container" && it.entityUuid == childUuid })
        assertTrue(backendLogs.any { it.entityType == "habit_placement" && it.entityUuid == habitUuid })
        assertTrue(backendLogs.any { it.entityType == "user_task" && it.entityUuid == task.id.value })

        // Device B
        val dbB = createTestDatabase()
        val envB = setupDeviceEnvironment(dbB, sharedBackend, "device-B")

        // 5. Device B syncs (pulls changes from backend)
        val syncResultB = envB.coordinator.runSync(manual = true)
        assertTrue("Sync B must succeed: $syncResultB", syncResultB is SyncRunResult.Success)
        assertTrue((syncResultB as SyncRunResult.Success).pulled > 0)

        // Verify Device B state
        // 6. Check root and child containers
        val rootB = envB.db.querySingle(
            "SELECT id, name, parent_id FROM Containers WHERE uuid = ?",
            rootUuid
        ) { stmt -> Triple(stmt.getLong(0), stmt.getText(1), stmt.getLongOrNull(2)) }
        assertNotNull("Root container must exist on Device B", rootB)
        assertEquals("Work", rootB!!.second)
        assertNull(rootB.third)

        val childB = envB.db.querySingle(
            "SELECT id, name, parent_id FROM Containers WHERE uuid = ?",
            childUuid
        ) { stmt -> Triple(stmt.getLong(0), stmt.getText(1), stmt.getLongOrNull(2)) }
        assertNotNull("Child container must exist on Device B", childB)
        assertEquals("Project X", childB!!.second)
        assertEquals("Child container must point to Root container", rootB.first, childB.third)

        // 7. Check Habit and Placement
        val habitB = envB.modelFactory.habitRepository.findByUuid(habitUuid)
        assertNotNull("Habit must exist on Device B", habitB)

        val placementB = envB.db.querySingle(
            "SELECT container_id, local_order FROM HabitPlacements WHERE habit_id = ?",
            habitB!!.id!!.toString()
        ) { stmt -> Pair(stmt.getLongOrNull(0), stmt.getInt(1)) }
        assertNotNull("Habit placement must exist on Device B", placementB)
        assertEquals("Placement must point to Child container", childB.first, placementB!!.first)
        assertEquals(0, placementB.second)

        // 8. Check UserTask
        val taskB = envB.taskRepo.getById(task.id)
        assertNotNull("Task must exist on Device B", taskB)
        assertEquals("Submit RFC", taskB!!.title)
        assertEquals("RFC for Next Sync", taskB.description)
        assertEquals(childUuid, taskB.containerId?.value)
        assertEquals(TaskStatus.PENDING, taskB.status)

        envA.coordinator.dispose()
        envB.coordinator.dispose()
        dbA.close()
        dbB.close()
    }

    @Test
    fun testFocusSessionAndAttributedContributionSync() = runBlocking {
        val sharedBackend = InMemorySyncBackend()

        val dbA = createTestDatabase()
        val envA = setupDeviceEnvironment(dbA, sharedBackend, "device-A")

        val dbB = createTestDatabase()
        val envB = setupDeviceEnvironment(dbB, sharedBackend, "device-B")

        val habitUuid = "habit-deep-work"
        envA.modelFactory.habitRepository.insert(
            HabitData(
                id = 1L, name = "Deep Work", description = "", question = "", color = 1,
                position = 0, archived = 0, type = 0, freqNum = 1, freqDen = 1,
                targetValue = 0.0, targetType = 0, unit = "", uuid = habitUuid, updatedAt = 1000L
            )
        )
        envB.modelFactory.habitRepository.insert(
            HabitData(
                id = 1L, name = "Deep Work", description = "", question = "", color = 1,
                position = 0, archived = 0, type = 0, freqNum = 1, freqDen = 1,
                targetValue = 0.0, targetType = 0, unit = "", uuid = habitUuid, updatedAt = 1000L
            )
        )

        // 1. Device A creates a Task
        val task = envA.taskService.createTask(title = "Code Review")

        // 2. Device A records a FocusSession on this task
        val focusSessionId = "focus-session-123"
        val focusStart = 2000L
        val focusEnd = 3500L
        val durationMs = 1500L
        val focusSession = FocusSession(
            id = FocusSessionId(focusSessionId),
            startedAt = focusStart,
            endedAt = focusEnd,
            durationMs = durationMs,
            taskId = task.id,
            habit = HabitRef(habitUuid),
            containerId = null,
            source = FocusSessionSource.TIMER,
            deviceId = "device-A",
            createdAt = focusEnd,
            updatedAt = focusEnd,
            revision = 1L,
            primaryTargetType = FocusPrimaryTargetType.TASK,
            organizationContext = null
        )
        envA.focusRepo.record(focusSession)
        envA.modelFactory.syncManager.enqueueFocusSession(focusSession, "create")

        // 3. Device A records an attributed Contribution to "Deep Work"
        val contribId = "contrib-456"
        val contribution = Contribution(
            id = ContributionId(contribId),
            targetHabit = HabitRef(habitUuid),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = focusSessionId,
            contributedValue = 1500L,
            effectiveTimestamp = focusEnd,
            createdAt = focusEnd,
            updatedAt = focusEnd,
            removedAt = null,
            sourceRuleId = null
        )
        envA.contribRepo.record(contribution)
        envA.modelFactory.syncManager.enqueueContribution(contribution, "create")

        // 4. Device A pushes
        val resultA = envA.coordinator.runSync(manual = true)
        assertTrue("Sync A must succeed: $resultA", resultA is SyncRunResult.Success)

        // 5. Device B pulls
        val resultB = envB.coordinator.runSync(manual = true)
        assertTrue("Sync B must succeed: $resultB", resultB is SyncRunResult.Success)

        // 6. Verify FocusSession on Device B
        val sessionB = envB.focusRepo.getById(FocusSessionId(focusSessionId))
        assertNotNull("FocusSession must be synced to Device B", sessionB)
        assertEquals(focusStart, sessionB!!.startedAt)
        assertEquals(focusEnd, sessionB.endedAt)
        assertEquals(durationMs, sessionB.durationMs)
        assertEquals(task.id, sessionB.taskId)
        assertEquals(FocusSessionSource.TIMER, sessionB.source)

        // 7. Verify Contribution on Device B
        val contribB = envB.contribRepo.getById(ContributionId(contribId))
        assertNotNull("Contribution must be synced to Device B", contribB)
        assertEquals(habitUuid, contribB!!.targetHabit.uuid)
        assertEquals(focusSessionId, contribB.sourceId)
        assertEquals(1500L, contribB.contributedValue)
        assertEquals(focusEnd, contribB.effectiveTimestamp)
        assertNull(contribB.removedAt)

        // 8. Re-sync idempotency test
        val resyncB = envB.coordinator.runSync(manual = true)
        assertTrue(resyncB is SyncRunResult.Success)
        val allSessionsB = envB.focusRepo.getAll()
        assertEquals("Focus sessions must not be duplicated", 1, allSessionsB.size)
        val allContribsB = envB.contribRepo.getByHabit(HabitRef(habitUuid))
        assertEquals("Contributions must not be duplicated", 1, allContribsB.size)

        envA.coordinator.dispose()
        envB.coordinator.dispose()
        dbA.close()
        dbB.close()
    }

    @Test
    fun testDeletionsAndTombstonesPropagation() = runBlocking {
        val sharedBackend = InMemorySyncBackend()

        val dbA = createTestDatabase()
        val envA = setupDeviceEnvironment(dbA, sharedBackend, "device-A")

        val dbB = createTestDatabase()
        val envB = setupDeviceEnvironment(dbB, sharedBackend, "device-B")

        // Initial entities
        val containerUuid = "container-to-delete"
        envA.orgStore.saveContainer(
            Container(
                id = ContainerId(containerUuid),
                parentId = null,
                name = "Temporary Container",
                color = PaletteColor(1),
                icon = null,
                siblingOrder = 0,
                isArchived = false,
                createdAt = 1000L,
                updatedAt = 1000L,
                deletedAt = null,
                revision = OrganizationRevision(1L)
            )
        )

        val task = envA.taskService.createTask(
            title = "Task to delete",
            containerId = ContainerId(containerUuid)
        )

        val contribId = "contrib-to-remove"
        val contribution = Contribution(
            id = ContributionId(contribId),
            targetHabit = HabitRef("any-habit"),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "any-session",
            contributedValue = 500L,
            effectiveTimestamp = 1000L,
            createdAt = 1000L,
            updatedAt = 1000L,
            removedAt = null,
            sourceRuleId = null
        )
        envA.contribRepo.record(contribution)
        envA.modelFactory.syncManager.enqueueContribution(contribution, "create")

        // Push initial entities and sync Device B
        envA.coordinator.runSync(manual = true)
        envB.coordinator.runSync(manual = true)

        assertNotNull(envB.taskRepo.getById(task.id))
        assertNotNull(envB.contribRepo.getById(ContributionId(contribId)))
        val containerBInitial = envB.db.querySingle(
            "SELECT deleted_at FROM Containers WHERE uuid = ?",
            containerUuid
        ) { it.getLongOrNull(0) }
        assertNull(containerBInitial)

        // Now Device A deletes container, deletes task, and removes contribution
        val deleteTime = 5000L
        envA.orgStore.recordChange(
            OrganizationChangeRecord(
                revision = OrganizationRevision(2L),
                opUuid = UUID.randomUUID().toString(),
                recordedAt = deleteTime,
                operationType = "CONTAINER_DELETE",
                origin = "LOCAL",
                commandPayload = "{}"
            )
        )
        envA.orgStore.saveContainer(
            Container(
                id = ContainerId(containerUuid),
                parentId = null,
                name = "Temporary Container",
                color = PaletteColor(1),
                icon = null,
                siblingOrder = 0,
                isArchived = false,
                createdAt = 1000L,
                updatedAt = deleteTime,
                deletedAt = deleteTime,
                revision = OrganizationRevision(2L)
            )
        )
        envA.taskService.deleteTask(task.id)
        envA.modelFactory.syncManager.enqueueContributionRemove(contribId, deleteTime)

        // Push deletions from A
        val syncResultDelA = envA.coordinator.runSync(manual = true)
        assertTrue(syncResultDelA is SyncRunResult.Success)

        // Pull deletions to B
        val syncResultDelB = envB.coordinator.runSync(manual = true)
        assertTrue(syncResultDelB is SyncRunResult.Success)

        // Verify on Device B:
        // 1. Container has deleted_at set (tombstone)
        val containerBDeletedAt = envB.db.querySingle(
            "SELECT deleted_at FROM Containers WHERE uuid = ?",
            containerUuid
        ) { it.getLongOrNull(0) }
        assertEquals(deleteTime, containerBDeletedAt)

        // 2. UserTask is deleted
        assertNull("Deleted task must no longer exist on Device B", envB.taskRepo.getById(task.id))

        // 3. Contribution has removed_at set
        val contribB = envB.contribRepo.getById(ContributionId(contribId))
        assertNotNull(contribB)
        assertEquals(deleteTime, contribB!!.removedAt)

        envA.coordinator.dispose()
        envB.coordinator.dispose()
        dbA.close()
        dbB.close()
    }

    @Test
    fun testOfflineBootstrapOfExistingNextEntities() = runBlocking {
        val sharedBackend = InMemorySyncBackend()
        val db = createTestDatabase()
        val env = setupDeviceEnvironment(db, sharedBackend, "device-bootstrap")

        // Create pre-existing offline Next entities without enqueuing
        // 1. Container
        val containerUuid = "pre-existing-container"
        env.db.run(
            """INSERT INTO Containers (uuid, parent_id, name, color, icon, sibling_order, is_archived, created_at, updated_at, deleted_at, revision)
               VALUES ('$containerUuid', NULL, 'Offline Container', 1, 'star', 0, 0, 1000, 1000, NULL, 1)"""
        )

        // 2. Habit & Placement
        val habitUuid = "pre-existing-habit"
        val habitId = env.modelFactory.habitRepository.insert(
            HabitData(
                id = 1L, name = "Offline Habit", description = "", question = "", color = 1,
                position = 0, archived = 0, type = 0, freqNum = 1, freqDen = 1,
                targetValue = 0.0, targetType = 0, unit = "", uuid = habitUuid, updatedAt = 1000L
            )
        )
        val containerLocalId = env.db.querySingle("SELECT id FROM Containers WHERE uuid = '$containerUuid'") { it.getLong(0) }!!
        env.db.run(
            """INSERT INTO HabitPlacements (habit_id, container_id, local_order, placement_origin, revision)
               VALUES ($habitId, $containerLocalId, 0, 'USER_CHANGE', 1)"""
        )

        // 3. UserTask
        val taskId = "pre-existing-task"
        env.db.run(
            """INSERT INTO UserTasks (id, title, description, container_id, status, planned_date, deadline, completed_at, parent_task_id, sort_order, created_at, updated_at)
               VALUES ('$taskId', 'Offline Task', 'Notes', '$containerUuid', 'PENDING', NULL, NULL, NULL, NULL, 0, 1000, 1000)"""
        )

        // 4. FocusSession
        val focusSessionId = "offline-focus-1"
        env.db.run(
            """INSERT INTO FocusSessions (id, started_at, ended_at, duration_ms, task_id, habit_uuid, container_id, source, device_id, created_at, updated_at, revision, primary_target_type, organization_context)
               VALUES ('$focusSessionId', 1000, 2000, 1000, '$taskId', '$habitUuid', NULL, 'MANUAL', 'device-bootstrap', 2000, 2000, 1, 'TASK', NULL)"""
        )

        // 5. Contribution
        val contribId = "offline-contrib-1"
        env.db.run(
            """INSERT INTO Contributions (id, target_habit_uuid, source_type, source_id, contributed_value, effective_timestamp, created_at, updated_at, removed_at, source_rule_id)
               VALUES ('$contribId', '$habitUuid', 'FOCUS_SESSION', '$focusSessionId', 1000, 2000, 2000, 2000, NULL, NULL)"""
        )

        // Verify sync queue is initially empty
        val initialPending = env.modelFactory.syncQueueRepository.findPending()
        assertEquals(0, initialPending.size)

        // Trigger bootstrap
        val bootstrapped = env.coordinator.bootstrapSyncQueue()
        assertTrue("Bootstrap must insert records", bootstrapped > 0)

        val pendingAfter = env.modelFactory.syncQueueRepository.findPending()
        assertTrue(pendingAfter.any { it.entityType == "container" && it.entityUuid == containerUuid })
        assertTrue(pendingAfter.any { it.entityType == "habit_placement" && it.entityUuid == habitUuid })
        assertTrue(pendingAfter.any { it.entityType == "user_task" && it.entityUuid == taskId })
        assertTrue(pendingAfter.any { it.entityType == "focus_session" && it.entityUuid == focusSessionId })
        assertTrue(pendingAfter.any { it.entityType == "contribution" && it.entityUuid == contribId })
        assertTrue(pendingAfter.any { it.entityType == "habit" && it.entityUuid == habitUuid })

        // Check payload validity
        val containerRecord = pendingAfter.first { it.entityType == "container" }
        val cPayload = JSONObject(containerRecord.payloadJson)
        assertEquals("Offline Container", cPayload.getString("name"))

        val taskRecord = pendingAfter.first { it.entityType == "user_task" }
        val tPayload = JSONObject(taskRecord.payloadJson)
        assertEquals("Offline Task", tPayload.getString("title"))

        env.coordinator.dispose()
        db.close()
    }

    @Test
    fun testDependencyOrderPlacementArrivingBeforeHabitRetry() = runBlocking {
        val db = createTestDatabase()
        val env = setupDeviceEnvironment(db, InMemorySyncBackend(), "device-test")

        // Remote events: placement arrives BEFORE habit in remote event list
        val habitUuid = "habit-order-test"
        val placementEvent = RemoteSyncEvent(
            logId = 1L,
            opUuid = "op-placement",
            entityType = "habit_placement",
            entityUuid = habitUuid,
            operationType = "upsert",
            payloadJson = JSONObject()
                .put("habit_uuid", habitUuid)
                .put("container_uuid", JSONObject.NULL)
                .put("local_order", 0)
                .put("origin", "USER_CHANGE")
                .put("updated_at", 1000L)
                .toString(),
            createdAt = 1000L,
            deviceId = "other-device"
        )

        val habitEvent = RemoteSyncEvent(
            logId = 2L,
            opUuid = "op-habit",
            entityType = "habit",
            entityUuid = habitUuid,
            operationType = "create",
            payloadJson = JSONObject()
                .put("name", "Habit From Remote")
                .put("color", 1)
                .put("type", 0)
                .put("freq_num", 1)
                .put("freq_den", 1)
                .put("target_value", 0.0)
                .put("target_type", 0)
                .put("unit", "")
                .put("block_uuid", JSONObject.NULL)
                .toString(),
            createdAt = 1000L,
            deviceId = "other-device"
        )

        // Passing placement before habit
        val events = listOf(placementEvent, habitEvent)

        // coordinator.applyRemoteEvents sorts habit before habit_placement!
        val skipped = env.coordinator.applyRemoteEvents(events)
        assertEquals("Zero events skipped because sort places habit before placement", 0, skipped)

        val habit = env.modelFactory.habitRepository.findByUuid(habitUuid)
        assertNotNull(habit)
        val placement = env.db.querySingle(
            "SELECT local_order FROM HabitPlacements WHERE habit_id = ?",
            habit!!.id!!.toString()
        ) { it.getInt(0) }
        assertEquals(0, placement)

        env.coordinator.dispose()
        db.close()
    }
}
