package org.isoron.uhabits.sync

import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.*
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitExtensionData
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import org.isoron.uhabits.widgets.WidgetUpdater
import org.isoron.uhabits.intents.IntentScheduler

class SyncCoordinatorTest {

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

        // Find migrations directory relative to test execution paths
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

    private fun buildSyncCoordinator(db: Database): Pair<SyncCoordinator, SQLModelFactory> {
        val modelFactory = SQLModelFactory(db)
        val habitList = SQLiteHabitList(modelFactory)
        val preferences = mock<Preferences>()
        val backend = mock<SyncBackend>()
        val deviceIdProvider = DeviceIdProvider { "test-device-id" }
        val commandRunner = CommandRunner(
            CoroutineTaskRunner(
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined
            )
        )
        val context = FakeContext()
        val authStore = FakeSyncAuthStore(context)
        val widgetUpdater = FakeWidgetUpdater(context)
        val coordinator = SyncCoordinator(
            context = context,
            modelFactory = modelFactory,
            habitList = habitList,
            preferences = preferences,
            authStore = authStore,
            backend = backend,
            deviceIdProvider = deviceIdProvider,
            widgetUpdater = widgetUpdater,
            commandRunner = commandRunner
        )
        return Pair(coordinator, modelFactory)
    }

    @Test
    fun testDeterministicDefaultBlockUuidRepair() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Populate database with built-in blocks 1 to 7 with random UUIDs.
        val originalUuidMap = mutableMapOf<Long, String>()
        for (i in 1L..7L) {
            val randomUuid = UUID.randomUUID().toString()
            originalUuidMap[i] = randomUuid
            val block = modelFactory.habitBlockRepository.findById(i)!!
            modelFactory.habitBlockRepository.update(block.copy(uuid = randomUuid))
        }

        // Add a habit linked to block 3 in HabitExtensions
        val habitId = modelFactory.habitRepository.insert(
            HabitData(
                id = 1L,
                name = "Test Habit",
                description = "",
                question = "",
                color = 1,
                position = 1,
                archived = 0,
                type = 0,
                freqNum = 1,
                freqDen = 1,
                targetValue = 0.0,
                targetType = 0,
                unit = "",
                uuid = "habit-uuid-1",
                updatedAt = 1000L
            )
        )
        modelFactory.habitExtensionRepository.upsert(
            HabitExtensionData(
                habitId = habitId,
                dayTier = "NORMAL",
                timerEnabled = false,
                blockId = 3L,
                statsStartTimestamp = null
            )
        )

        // Verify pre-conditions
        assertEquals(originalUuidMap[3L], modelFactory.habitBlockRepository.findById(3L)?.uuid)
        assertEquals(3L, modelFactory.habitExtensionRepository.findByHabitId(habitId)?.blockId)

        // Execute repair
        coordinator.repairDefaultBlockUuids()

        // Verify default blocks 1..7 have stable deterministic UUIDs
        for (i in 1L..7L) {
            val block = modelFactory.habitBlockRepository.findById(i)
            assertNotNull(block)
            assertEquals(i, block?.id)
            assertEquals(SyncCoordinator.DETERMINISTIC_DEFAULT_BLOCK_UUIDS[i], block?.uuid)
        }

        // Verify local numeric IDs remain unchanged and HabitExtensions.block_id is still 3L
        val ext = modelFactory.habitExtensionRepository.findByHabitId(habitId)
        assertNotNull(ext)
        assertEquals(3L, ext?.blockId)
    }

    @Test
    fun testBootstrapDefaultBlocks() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Set deterministic UUIDs for default blocks 1..7
        for (i in 1L..7L) {
            val block = modelFactory.habitBlockRepository.findById(i)!!
            modelFactory.habitBlockRepository.update(
                block.copy(uuid = SyncCoordinator.DETERMINISTIC_DEFAULT_BLOCK_UUIDS[i])
            )
        }
        // Insert custom block (id = 8)
        modelFactory.habitBlockRepository.insert(
            HabitBlockData(
                id = 8L,
                name = "Custom Block",
                color = 8,
                position = 8,
                isArchived = false,
                uuid = "custom-uuid",
                updatedAt = 1000L
            )
        )

        val blocks = modelFactory.habitBlockRepository.findAll()
        val habits = emptyList<Habit>()

        // 1. With zero habits, all 7 default blocks are returned, but custom block (ID = 8) is excluded.
        val forBootstrapEmpty = coordinator.blocksForBootstrap(blocks, habits)
        assertEquals(7, forBootstrapEmpty.size)
        assertTrue(forBootstrapEmpty.all { it.id!! <= 7L })
        assertFalse(forBootstrapEmpty.any { it.id == 8L })

        // 2. Reference custom block in a habit
        val referencedHabit = modelFactory.buildHabit().apply {
            id = 1L
            uuid = "referenced-habit-uuid"
            blockId = 8L
        }
        val forBootstrapReferenced = coordinator.blocksForBootstrap(blocks, listOf(referencedHabit))
        assertEquals(8, forBootstrapReferenced.size)
        assertTrue(forBootstrapReferenced.any { it.id == 8L })
    }

    @Test
    fun testHabitPayloadBlockUuid() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Update default block 2
        val existingBlock = modelFactory.habitBlockRepository.findById(2L)!!
        modelFactory.habitBlockRepository.update(
            existingBlock.copy(
                name = "Speech",
                color = 2,
                position = 2,
                isArchived = false,
                uuid = "default:speech-thinking",
                updatedAt = 1000L
            )
        )

        val habit = modelFactory.buildHabit().apply {
            id = 1L
            uuid = "habit-uuid"
            name = "Speech Habit"
            blockId = 2L
        }

        // Test that habitPayload contains block_uuid (verify via enqueueHabitUpdate)
        modelFactory.syncManager.enqueueHabitUpdate(habit)
        val pending = modelFactory.syncQueueRepository.findPending()
        val habitEvent = pending.find { it.entityType == "habit" }
        assertNotNull(habitEvent)
        val json = JSONObject(habitEvent?.payloadJson)
        assertEquals("default:speech-thinking", json.getString("block_uuid"))

        // Test that enqueuing only enqueues block metadata for habit_block event,
        // and does NOT treat habit_block events as habit-to-block relation.
        val block = HabitBlockData(
            id = 2L,
            name = "Speech",
            color = 2,
            position = 2,
            isArchived = false,
            uuid = "default:speech-thinking",
            updatedAt = 1000L
        )
        // Enqueue block update
        modelFactory.syncManager.enqueueBlockChange(block, "update")
        val pendingAfterBlock = modelFactory.syncQueueRepository.findPending()
        val blockEvent = pendingAfterBlock.find { it.entityType == "habit_block" }
        assertNotNull(blockEvent)
        val blockJson = JSONObject(blockEvent?.payloadJson)
        assertEquals("Speech", blockJson.getString("name"))
        assertFalse(blockJson.has("habit_uuid")) // habit_block remains metadata-only
    }

    @Test
    fun testRemoteApplyOrdering() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Setup: device A has block numeric ID 10 with UUID "block-xyz".
        // On device B (this device), we will insert a block with a different numeric ID (e.g. 5) but same UUID "block-xyz".
        // This validates "local numeric block IDs may differ across devices".
        val existingBlock = modelFactory.habitBlockRepository.findById(5L)!!
        modelFactory.habitBlockRepository.update(
            existingBlock.copy(
                name = "My Spheres",
                color = 3,
                position = 3,
                isArchived = false,
                uuid = "block-xyz",
                updatedAt = 1000L
            )
        )

        // Remote events batch: habit event comes BEFORE habit_block event in the list!
        val habitEvent = RemoteSyncEvent(
            logId = 101L,
            opUuid = "op-1",
            entityType = "habit",
            entityUuid = "habit-abc",
            operationType = "create",
            payloadJson = JSONObject()
                .put("name", "Remote Habit")
                .put("block_uuid", "block-xyz")
                .toString(),
            createdAt = 2000L,
            deviceId = "device-other"
        )
        val blockEvent = RemoteSyncEvent(
            logId = 102L,
            opUuid = "op-2",
            entityType = "habit_block",
            entityUuid = "block-xyz",
            operationType = "create",
            payloadJson = JSONObject()
                .put("name", "Remote Block Name Updated")
                .put("color", 4)
                .put("position", 2)
                .put("is_archived", "false")
                .toString(),
            createdAt = 2000L,
            deviceId = "device-other"
        )

        val events = listOf(habitEvent, blockEvent)

        // Execute applying events (applyRemoteEvents sorts habit_block before habit)
        val skipped = coordinator.applyRemoteEvents(events)
        assertEquals(0, skipped)

        // Verify that the habit was inserted and associated with block ID 5L (which maps to "block-xyz")
        val habit = modelFactory.habitRepository.findByUuid("habit-abc")
        assertNotNull(habit)
        val ext = modelFactory.habitExtensionRepository.findByHabitId(habit?.id!!)
        assertNotNull(ext)
        assertEquals(5L, ext?.blockId)

        // Verify block was updated with new metadata (color = 4)
        val block = modelFactory.habitBlockRepository.findById(5L)
        assertEquals(4, block?.color)
    }

    @Test
    fun testUnresolvedBlockUuidSafety_preservesExisting() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Setup: existing habit linked to block ID 2L (UUID default:speech-thinking)
        val habitId = modelFactory.habitRepository.insert(
            HabitData(
                id = 1L,
                name = "My Habit",
                description = "",
                question = "",
                color = 1,
                position = 1,
                archived = 0,
                type = 0,
                freqNum = 1,
                freqDen = 1,
                targetValue = 0.0,
                targetType = 0,
                unit = "",
                uuid = "habit-uuid-123",
                updatedAt = 1000L
            )
        )
        modelFactory.habitExtensionRepository.upsert(
            HabitExtensionData(
                habitId = habitId,
                dayTier = "NORMAL",
                timerEnabled = false,
                blockId = 2L,
                statsStartTimestamp = null
            )
        )

        // Remote habit event has an unresolved block_uuid "non-existent-block-uuid"
        val event = RemoteSyncEvent(
            logId = 201L,
            opUuid = "op-uuid-1",
            entityType = "habit",
            entityUuid = "habit-uuid-123",
            operationType = "update",
            payloadJson = JSONObject()
                .put("name", "Habit Updated Remotely")
                .put("block_uuid", "non-existent-block-uuid")
                .toString(),
            createdAt = 2000L,
            deviceId = "device-other"
        )

        val skipped = coordinator.applyRemoteEvents(listOf(event))
        assertEquals(0, skipped)

        // Verify the existing local HabitExtensions.block_id is PRESERVED (still 2L)
        val ext = modelFactory.habitExtensionRepository.findByHabitId(habitId)
        assertNotNull(ext)
        assertEquals(2L, ext?.blockId)

        // Verify unresolved relation is recorded in diagnostics
        val diagnosticsReport = coordinator.buildDiagnosticsReport("1.0", 1)
        val diagJson = JSONObject(diagnosticsReport)
        val unresolvedApplies = diagJson.getJSONArray("unresolved_habit_block_applies")
        assertEquals(1, unresolvedApplies.length())
        val item = unresolvedApplies.getJSONObject(0)
        assertEquals("habit-uuid-123", item.getString("remote_habit_uuid"))
        assertEquals("non-existent-block-uuid", item.getString("remote_block_uuid"))
    }

    @Test
    fun testNullBlockUuidSemantics() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Setup: existing habit linked to block ID 2L (UUID default:speech-thinking)
        val habitId = modelFactory.habitRepository.insert(
            HabitData(
                id = 1L,
                name = "My Habit",
                description = "",
                question = "",
                color = 1,
                position = 1,
                archived = 0,
                type = 0,
                freqNum = 1,
                freqDen = 1,
                targetValue = 0.0,
                targetType = 0,
                unit = "",
                uuid = "habit-uuid-123",
                updatedAt = 1000L
            )
        )
        modelFactory.habitExtensionRepository.upsert(
            HabitExtensionData(
                habitId = habitId,
                dayTier = "NORMAL",
                timerEnabled = false,
                blockId = 2L,
                statsStartTimestamp = null
            )
        )

        // 1. Explicit null (Other) -> block_uuid = null.
        // This should clear the local block assignment to null.
        val explicitNullEvent = RemoteSyncEvent(
            logId = 301L,
            opUuid = "op-301",
            entityType = "habit",
            entityUuid = "habit-uuid-123",
            operationType = "update",
            payloadJson = JSONObject()
                .put("name", "Habit explicit null block")
                .put("block_uuid", JSONObject.NULL)
                .toString(),
            createdAt = 3000L,
            deviceId = "device-other"
        )
        coordinator.applyRemoteEvents(listOf(explicitNullEvent))
        val extAfterNull = modelFactory.habitExtensionRepository.findByHabitId(habitId)
        assertNull(extAfterNull?.blockId)

        // Restore assignment to 2L
        modelFactory.habitExtensionRepository.upsert(
            HabitExtensionData(
                habitId = habitId,
                dayTier = "NORMAL",
                timerEnabled = false,
                blockId = 2L,
                statsStartTimestamp = null
            )
        )

        // 2. Missing block_uuid field.
        // Note: With the safety correction, a missing block_uuid field (legacy payload)
        // preserves the existing local assignment (2L) instead of clearing it to null.
        val missingFieldEvent = RemoteSyncEvent(
            logId = 302L,
            opUuid = "op-302",
            entityType = "habit",
            entityUuid = "habit-uuid-123",
            operationType = "update",
            payloadJson = JSONObject()
                .put("name", "Habit missing block field")
                .toString(),
            createdAt = 4000L,
            deviceId = "device-other"
        )
        coordinator.applyRemoteEvents(listOf(missingFieldEvent))
        val extAfterMissing = modelFactory.habitExtensionRepository.findByHabitId(habitId)
        assertEquals(2L, extAfterMissing?.blockId)
    }

    @Test
    fun testDefaultBlockUuidRepairDetailed() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // 1. Repair is idempotent and doesn't change timestamps unnecessarily.
        // Set deterministic UUIDs for some blocks, but random for others.
        val defaultBlock1 = modelFactory.habitBlockRepository.findById(1L)!!
        val defaultBlock2 = modelFactory.habitBlockRepository.findById(2L)!!
        val customBlock = HabitBlockData(
            id = 8L,
            name = "Custom Sphere",
            color = 8,
            position = 8,
            isArchived = false,
            uuid = "custom-uuid",
            updatedAt = 5000L
        )
        modelFactory.habitBlockRepository.insert(customBlock)

        modelFactory.habitBlockRepository.update(defaultBlock1.copy(uuid = SyncCoordinator.DETERMINISTIC_DEFAULT_BLOCK_UUIDS[1L], updatedAt = 2000L))
        modelFactory.habitBlockRepository.update(defaultBlock2.copy(uuid = "random-uuid-2", updatedAt = 2000L))

        // First repair run
        coordinator.repairDefaultBlockUuids()

        val repaired1 = modelFactory.habitBlockRepository.findById(1L)!!
        val repaired2 = modelFactory.habitBlockRepository.findById(2L)!!
        val customAfter = modelFactory.habitBlockRepository.findById(8L)!!

        assertEquals(SyncCoordinator.DETERMINISTIC_DEFAULT_BLOCK_UUIDS[1L], repaired1.uuid)
        assertEquals(2000L, repaired1.updatedAt) // Unchanged since it was already deterministic

        assertEquals(SyncCoordinator.DETERMINISTIC_DEFAULT_BLOCK_UUIDS[2L], repaired2.uuid)
        assertEquals(2000L, repaired2.updatedAt) // Changed UUID but preserved timestamp to avoid unnecessary sync queueing

        assertNotNull(customAfter) // Custom block is preserved
        assertEquals("custom-uuid", customAfter?.uuid)

        // Second repair run (idempotency check)
        val ts1 = repaired1.updatedAt
        val ts2 = repaired2.updatedAt
        coordinator.repairDefaultBlockUuids()
        assertEquals(ts1, modelFactory.habitBlockRepository.findById(1L)?.updatedAt)
        assertEquals(ts2, modelFactory.habitBlockRepository.findById(2L)?.updatedAt)
    }

    @Test
    fun testBootstrapSyncQueueEvents() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Repair default blocks to deterministic UUIDs first, matching the real sync/bootstrap flow
        coordinator.repairDefaultBlockUuids()

        // Fresh account with zero habits should bootstrap exactly the 7 default blocks
        val habits = emptyList<Habit>()
        val blocks = modelFactory.habitBlockRepository.findAll()

        val bootstrapList = coordinator.blocksForBootstrap(blocks, habits)
        assertEquals(7, bootstrapList.size)
        for (block in bootstrapList) {
            val id = block.id!!
            assertEquals(SyncCoordinator.DETERMINISTIC_DEFAULT_BLOCK_UUIDS[id], block.uuid)
        }

        // Add an unreferenced custom block
        val customBlock = HabitBlockData(
            id = 8L,
            name = "Custom Block",
            color = 8,
            position = 8,
            isArchived = false,
            uuid = "custom-uuid",
            updatedAt = 1000L
        )
        modelFactory.habitBlockRepository.insert(customBlock)

        val blocksWithCustom = modelFactory.habitBlockRepository.findAll()
        val bootstrapWithCustom = coordinator.blocksForBootstrap(blocksWithCustom, habits)
        assertEquals(7, bootstrapWithCustom.size)
        assertFalse(bootstrapWithCustom.any { it.id == 8L }) // Unreferenced custom block not included

        // Reference the custom block in a habit
        val habit = modelFactory.buildHabit().apply {
            id = 1L
            uuid = "habit-uuid"
            blockId = 8L
        }
        val bootstrapWithReferenced = coordinator.blocksForBootstrap(blocksWithCustom, listOf(habit))
        assertEquals(8, bootstrapWithReferenced.size)
        assertTrue(bootstrapWithReferenced.any { it.id == 8L }) // Referenced custom block included
    }

    @Test
    fun testDiagnosticsReportAndMatchFlags() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        // Trigger unresolved block UUID apply
        val event = RemoteSyncEvent(
            logId = 201L,
            opUuid = "op-uuid-1",
            entityType = "habit",
            entityUuid = "habit-uuid-123",
            operationType = "create",
            payloadJson = JSONObject()
                .put("name", "Habit with unresolved block")
                .put("block_uuid", "non-existent-block-uuid")
                .toString(),
            createdAt = 2000L,
            deviceId = "device-other"
        )
        coordinator.applyRemoteEvents(listOf(event))

        // Get report
        val reportStr = coordinator.buildDiagnosticsReport("2.3.1", 20301)
        val report = JSONObject(reportStr)

        // 1. Verify unresolved applies
        val unresolved = report.getJSONArray("unresolved_habit_block_applies")
        assertEquals(1, unresolved.length())
        assertEquals("habit-uuid-123", unresolved.getJSONObject(0).getString("remote_habit_uuid"))
        assertEquals("non-existent-block-uuid", unresolved.getJSONObject(0).getString("remote_block_uuid"))

        // 2. Verify habit_block_assignments
        val assignments = report.getJSONArray("habit_block_assignments")
        assertEquals(1, assignments.length())
        val assign = assignments.getJSONObject(0)
        assertEquals("habit-uuid-123", assign.getString("habit_uuid"))
        assertTrue(assign.getBoolean("is_other")) // Since it's unresolved, it defaults to Other

        // 3. Verify match flags for blocks
        val blocksReport = report.getJSONArray("blocks")
        assertTrue(blocksReport.length() >= 7)
        for (i in 0 until 7) {
            val bObj = blocksReport.getJSONObject(i)
            val expected = SyncCoordinator.DETERMINISTIC_DEFAULT_BLOCK_UUIDS[bObj.getLong("id")]
            assertEquals(expected, bObj.getString("expected_uuid"))
            assertEquals(expected == bObj.getString("uuid"), bObj.getBoolean("uuid_matches_expected"))
        }
    }

    @Test
    fun testSyncQueueAndLifecycleOperations() = runBlocking {
        val db = createTestDatabase()
        val (coordinator, modelFactory) = buildSyncCoordinator(db)

        val habit = modelFactory.buildHabit().apply {
            id = 1L
            uuid = "habit-uuid-1"
            name = "Test Habit"
            blockId = 3L
        }

        // 1. Enqueue habit creates
        modelFactory.syncManager.enqueueHabitCreate(habit)
        val pending = modelFactory.syncQueueRepository.findPending()
        assertTrue(pending.any { it.entityType == "habit" && it.entityUuid == "habit-uuid-1" })

        // 2. Pushed rows marked pushed and not returned by findPending
        val recordId = pending.first().queueId!!
        modelFactory.syncQueueRepository.markPushed(recordId, System.currentTimeMillis())
        val pendingAfterPush = modelFactory.syncQueueRepository.findPending()
        assertFalse(pendingAfterPush.any { it.queueId == recordId })

        // 3. Deleting habit remove/tombstones metadata
        modelFactory.habitRepository.insert(
            HabitData(
                id = 10L,
                name = "Temporary Habit",
                description = "",
                question = "",
                color = 1,
                position = 1,
                archived = 0,
                type = 0,
                freqNum = 1,
                freqDen = 1,
                targetValue = 0.0,
                targetType = 0,
                unit = "",
                uuid = "temp-habit-uuid",
                updatedAt = 1000L
            )
        )
        modelFactory.habitExtensionRepository.upsert(
            HabitExtensionData(
                habitId = 10L,
                dayTier = "NORMAL",
                timerEnabled = false,
                blockId = 1L,
                statsStartTimestamp = null
            )
        )

        // Assert setup
        assertNotNull(modelFactory.habitExtensionRepository.findByHabitId(10L))

        // Soft delete habit
        val record = modelFactory.habitRepository.findAll().find { it.id == 10L }!!
        modelFactory.habitRepository.update(record.copy(updatedAt = 2000L, deletedAt = 2000L))
        // Verify that deleted_at is updated and it is no longer returned by findAll
        val deletedHabit = modelFactory.habitRepository.findAll().find { it.id == 10L }
        assertNull(deletedHabit)
    }
}

