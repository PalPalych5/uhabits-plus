/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
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
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTaskServiceImpl
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.isoron.uhabits.widgets.WidgetUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class RealServerSyncInteroperabilityTest {

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

    private suspend fun createTestDatabase(): Database {
        val db = JavaDatabaseOpener().open(":memory:")
        db.setVersion(8)

        var migrationsDir = File("../uhabits-core/assets/main/migrations")
        if (!migrationsDir.exists()) {
            migrationsDir = File("uhabits-core/assets/main/migrations")
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
            syncSupabaseUrl = "http://127.0.0.1:8080"
            syncSupabaseAnonKey = "anon-key-mock"
            isSyncReviewRequired = false
        }
        val context = FakeContext()
        val authStore = FakeSyncAuthStore(context)

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
    fun testRealServerInteroperabilityBetweenDevices() = runBlocking {
        val serverAvailable = try {
            java.net.Socket("127.0.0.1", 8080).use { true }
        } catch (e: Exception) {
            false
        }
        org.junit.Assume.assumeTrue("Mock sync server is not running on 127.0.0.1:8080", serverAvailable)

        val realBackend = SupabaseSyncBackend()

        // 1. Device 2 Environment
        val db2 = createTestDatabase()
        val dev2 = setupDeviceEnvironment(
            db = db2,
            backend = realBackend,
            deviceId = "device-2-interop",
            session = DatasetSessionFactory.productionNext()
        )

        // 2. Authenticate Device 2 against the real mock server
        val signInResult = dev2.coordinator.signIn("user@uhabits.local", "password123")
        assertTrue("Sign in should succeed: $signInResult", signInResult is SyncRunResult.Success)

        // 3. Device 2 Sync Pull (Initial Bootstrap from server)
        val pullResult = dev2.coordinator.runSync(manual = true)
        assertTrue("Sync pull should succeed: $pullResult", pullResult is SyncRunResult.Success)
        val successResult = pullResult as SyncRunResult.Success
        println("Device 2 pulled ${successResult.pulled} events from real test server")
        assertTrue("Device 2 should pull events (at least 30)", successResult.pulled >= 30)

        // 4. Verify Next Entities Reconstructed on Device 2
        // Containers: "Work." must exist
        val workContainer = dev2.db.querySingle(
            "SELECT id, name FROM Containers WHERE uuid = ?",
            "fe3a148abfb244f8940135e2eb2b69e5"
        ) { stmt -> Pair(stmt.getLong(0), stmt.getText(1)) }
        assertNotNull("Container 'Work.' must be pulled to Device 2", workContainer)
        assertEquals("Work.", workContainer?.second)

        // Tasks: "Read report" must exist
        val readReportTask = dev2.taskRepo.getById(TaskId("f0b39324d18043d4994636f9359bb0eb"))
        assertNotNull("Task 'Read report' must be pulled to Device 2", readReportTask)
        assertEquals("Read report", readReportTask?.title)
        assertEquals(TaskStatus.PENDING, readReportTask?.status)
        assertEquals(ContainerId("fe3a148abfb244f8940135e2eb2b69e5"), readReportTask?.containerId)

        // Focus Sessions: session must exist
        val focusSession = dev2.focusRepo.getById(FocusSessionId("e16a69e6-5bf7-4cb2-8eaf-e428cb757359"))
        assertNotNull("Focus session must be pulled to Device 2", focusSession)
        assertEquals(64836L, focusSession?.durationMs)
        assertEquals(TaskId("f0b39324d18043d4994636f9359bb0eb"), focusSession?.taskId)

        // Contributions: contribution must exist
        val contribution = dev2.contribRepo.getById(ContributionId("contrib:focus:e16a69e6-5bf7-4cb2-8eaf-e428cb757359:h-uuid-2"))
        assertNotNull("Contribution must be pulled to Device 2", contribution)
        assertEquals(1081L, contribution?.contributedValue)
        assertEquals("h-uuid-2", contribution?.targetHabit?.uuid)

        // Habits: legacy habits must exist
        val habitCount = dev2.db.querySingle("SELECT count(*) FROM Habits WHERE deleted_at IS NULL") { it.getInt(0) } ?: 0
        assertTrue("Habits must be reconstructed on Device 2 (count=$habitCount)", habitCount > 0)
        println("Device 2 has $habitCount habits after pull")

        // 5. Offline Edits on Device 2
        println("Performing offline edits on Device 2...")
        // a) Create a new task "Write release notes" in container "Work."
        val newTask = dev2.taskService.createTask(
            title = "Write release notes",
            containerId = ContainerId("fe3a148abfb244f8940135e2eb2b69e5"),
            description = "Prepare final release notes"
        )
        assertNotNull("New task must be created", newTask)

        // b) Complete task "Read report"
        val completedTask = dev2.taskService.setCompleted(
            TaskId("f0b39324d18043d4994636f9359bb0eb"),
            true
        )
        assertEquals(TaskStatus.COMPLETED, completedTask.status)

        // c) Create and delete a task to test deletion propagation
        val ephemeralTask = dev2.taskService.createTask(
            title = "Ephemeral task to delete",
            containerId = ContainerId("fe3a148abfb244f8940135e2eb2b69e5")
        )
        dev2.taskService.deleteTask(ephemeralTask.id)
        assertNull("Deleted task must be null locally", dev2.taskRepo.getById(ephemeralTask.id))

        // 6. Device 2 Reconnection & Push
        println("Reconnecting Device 2 and pushing offline edits...")
        val pushResult = dev2.coordinator.runSync(manual = true)
        assertTrue("Device 2 push should succeed: $pushResult", pushResult is SyncRunResult.Success)
        val pushSuccess = pushResult as SyncRunResult.Success
        println("Device 2 pushed ${pushSuccess.pushed} changes to server")
        assertTrue("Device 2 should push at least 2 changes", pushSuccess.pushed >= 2)

        dev2.coordinator.dispose()
        db2.close()
    }
}
