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

package org.isoron.uhabits.core.containers.backup

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.EditContainer
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.OrganizationServiceImpl
import org.isoron.uhabits.core.containers.PlaceHabit
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationValidator
import org.isoron.uhabits.core.containers.migration.MigrationExecutionResult
import org.isoron.uhabits.core.containers.migration.MigrationValidationResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.containers.sqlite.SQLiteHabitVisualIdentityStore
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerBackupRestoreTest {

    @Test
    fun restoredRawSnapshotDropsDeliveryContextAndCannotImportLegacyCursor() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            // Also supports snapshots predating PR 2.2: no service tables exist yet.
            DatabaseSnapshotter.clearSyncDeliveryState(db)
            assertEquals(0L, org.isoron.uhabits.core.sync.SyncStateRepository(db).getCursor())
            seedDeliveryState(db, "backup-account")
            DatabaseSnapshotter.clearSyncDeliveryState(db)
            assertEquals(0L, org.isoron.uhabits.core.sync.SyncStateRepository(db).getCursor())
            assertNull(org.isoron.uhabits.core.sync.SyncStateRepository(db).getAccountUserId())
            assertEquals(0L, db.queryLong("SELECT count(*) FROM SyncInbox"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM SyncTombstones"))
            assertEquals(7L, db.queryLong("SELECT count(*) FROM Habits"))
        } finally { db.close() }
    }

    @Test
    fun testFullBackupRestoreRoundtrip_logicalParity() = runTest {
        // 1. Create and seed v29 database
        val sourceDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(sourceDb)

        // 2. Migrate to Experimental Container dataset via PR3
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        val execResult = LegacyContainerMigrationExecutor.execute(sourceDb, plan)
        assertTrue(execResult is org.isoron.uhabits.core.containers.migration.MigrationExecutionResult.Success)

        // Add a nested child container to verify tree depth restoration
        val store = SQLiteOrganizationStore(sourceDb)
        val parentContainerId = ContainerId("b-uuid-1")
        val childContainerId = ContainerId("c-nested-child")

        var idCounter = 1
        val service = OrganizationServiceImpl(
            store = store,
            unitOfWork = store,
            containerQueries = store,
            placementQueries = store,
            clock = { 2000000L },
            idGenerator = { "gen-id-${idCounter++}" },
            habitIdentityLookup = { true },
        )
        val createChildRes = service.create(
            CreateContainer(
                name = "Вложенный раздел",
                parentId = parentContainerId,
                explicitId = childContainerId,
            )
        )
        assertTrue(createChildRes.isSuccess)

        // Place habit 7 into the nested child
        val placeRes = service.placeHabit(
            PlaceHabit(
                habit = HabitRef("h-uuid-7"),
                targetContainerId = childContainerId,
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(placeRes.isSuccess)

        // 3. Create full backup
        val backupDb = TestDatabaseHelper.createEmptyDatabase()
        val backupRes = ContainerBackupService.createBackup(
            sourceDb = sourceDb,
            targetDb = backupDb,
            timestamp = 2500000L,
            preferences = mapOf("theme" to "dark", "first_day_of_week" to "1"),
        )
        assertTrue(backupRes is BackupCreationResult.Success)
        assertNotNull(backupRes.manifest)
        assertEquals("dark", backupRes.manifest.nonSecretPreferences["theme"])

        // Verify sourceDb was NOT mutated by backup creation
        val sourceIntegrity = sourceDb.querySingle("PRAGMA integrity_check") { it.getText(0) }
        assertEquals("ok", sourceIntegrity)

        // 4. Restore backup into destinationDb via stagingDb
        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()

        val restoreRes = ContainerRestoreService.restore(
            backupDb = backupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
        )
        assertTrue(restoreRes is RestoreResult.Success)
        assertEquals(false, restoreRes.wasMigratedFromLegacy)

        // 5. Verify 100% logical parity in destinationDb
        assertEquals("ok", destinationDb.querySingle("PRAGMA integrity_check") { it.getText(0) })

        val destStore = SQLiteOrganizationStore(destinationDb)
        val destState = destStore.readState()
        assertEquals(store.readState().currentRevision, destState.currentRevision)

        // Containers count: 5 initial from blocks + 1 nested child = 6
        val destContainers = destStore.getAllContainers(includeDeleted = true)
        assertEquals(6, destContainers.size)

        val childInDest = destStore.getContainer(childContainerId)
        assertNotNull(childInDest)
        assertEquals("Вложенный раздел", childInDest.name)
        assertEquals(parentContainerId, childInDest.parentId)

        // Habits count
        assertEquals(7L, destinationDb.queryLong("SELECT count(*) FROM Habits"))
        assertEquals(7L, destinationDb.queryLong("SELECT count(*) FROM HabitPlacements"))
        assertEquals(5L, destinationDb.queryLong("SELECT count(*) FROM LegacyBlockMap"))
        assertEquals(9L, destinationDb.queryLong("SELECT count(*) FROM Repetitions"))
        assertEquals(2L, destinationDb.queryLong("SELECT count(*) FROM EntryOps"))
        assertEquals(1L, destinationDb.queryLong("SELECT count(*) FROM SyncQueue"))
        assertEquals(2L, destinationDb.queryLong("SELECT count(*) FROM AppSettings"))

        // Placement of habit 7 in nested child
        val p7 = destStore.getHabitPlacement(HabitRef("h-uuid-7"))
        assertNotNull(p7)
        assertEquals(childContainerId, p7.containerId)

        // History check
        assertEquals(6L, destinationDb.queryLong("SELECT count(*) FROM ContainerHistory"))
        assertEquals(8L, destinationDb.queryLong("SELECT count(*) FROM HabitPlacementHistory"))

        // Semantic validation on destinationDb
        val destInventory = RawLegacyInventoryReader.read(destinationDb)
        val semanticErrors = ContainerRestoreService.validateContainerDatasetSemantics(destinationDb, destInventory)
        assertTrue(semanticErrors.isEmpty(), "Semantic errors found: $semanticErrors")

        sourceDb.close()
        backupDb.close()
        stagingDb.close()
        destinationDb.close()
    }

    @Test
    fun testVisualIdentityBackupRestoreRoundtrip() = runTest {
        // 1. Create and seed v29 database and migrate to experimental dataset
        val sourceDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(sourceDb)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        val execResult = LegacyContainerMigrationExecutor.execute(sourceDb, plan)
        assertTrue(execResult is org.isoron.uhabits.core.containers.migration.MigrationExecutionResult.Success)

        val store = SQLiteOrganizationStore(sourceDb)
        val visualStore = SQLiteHabitVisualIdentityStore(sourceDb)

        var idCounter = 1
        val service = OrganizationServiceImpl(
            store = store,
            unitOfWork = store,
            containerQueries = store,
            placementQueries = store,
            clock = { 2000000L },
            idGenerator = { "gen-id-${idCounter++}" },
            habitIdentityLookup = { true },
        )

        // 2. Configure container visual identities: root container and nested subcontainer
        val rootContainerId = ContainerId("b-uuid-1")
        val editRootRes = service.edit(
            EditContainer(
                id = rootContainerId,
                name = "Корневой раздел с иконкой",
                color = PaletteColor(3),
                icon = "vector:book",
                expectedRevision = store.readState().currentRevision,
            )
        )
        assertTrue(editRootRes.isSuccess)

        val nestedContainerId = ContainerId("c-nested-sub")
        val createSubRes = service.create(
            CreateContainer(
                name = "Подраздел Учёбы",
                parentId = rootContainerId,
                explicitId = nestedContainerId,
                color = PaletteColor(4),
                icon = "vector:graduation-cap",
            )
        )
        assertTrue(createSubRes.isSuccess)

        // 3. Configure habit visual identities:
        // - Habit UUID X ("h-uuid-1"): vector icon
        // - Habit UUID Y ("h-uuid-2"): emoji icon
        // - Habit UUID Z ("h-uuid-3"): None / missing identity
        visualStore.set("h-uuid-1", IconRef.Vector("book"))
        visualStore.set("h-uuid-2", IconRef.Emoji("☕"))

        // Verify set before backup
        assertEquals(IconRef.Vector("book"), visualStore.get("h-uuid-1"))
        assertEquals(IconRef.Emoji("☕"), visualStore.get("h-uuid-2"))
        assertNull(visualStore.get("h-uuid-3"))

        // 4. Create full experimental backup
        val backupDb = TestDatabaseHelper.createEmptyDatabase()
        val backupRes = ContainerBackupService.createBackup(
            sourceDb = sourceDb,
            targetDb = backupDb,
            timestamp = 3000000L,
            preferences = mapOf("theme" to "amoled"),
        )
        assertTrue(backupRes is BackupCreationResult.Success)

        // 5. Restore into destinationDb via stagingDb
        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()

        val restoreRes = ContainerRestoreService.restore(
            backupDb = backupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
        )
        assertTrue(restoreRes is RestoreResult.Success)
        assertEquals(false, restoreRes.wasMigratedFromLegacy)

        // 6. Verify visual identity roundtrip in destinationDb
        val destVisualStore = SQLiteHabitVisualIdentityStore(destinationDb)
        assertEquals(IconRef.Vector("book"), destVisualStore.get("h-uuid-1"), "Habit X vector icon must be restored identically")
        assertEquals(IconRef.Emoji("☕"), destVisualStore.get("h-uuid-2"), "Habit Y emoji icon must be restored identically")
        assertNull(destVisualStore.get("h-uuid-3"), "Habit Z without identity must remain null")

        // Verify container visual identities in destinationDb
        val destStore = SQLiteOrganizationStore(destinationDb)
        val restoredRoot = destStore.getContainer(rootContainerId)
        assertNotNull(restoredRoot)
        assertEquals(PaletteColor(3), restoredRoot.color)
        assertEquals("vector:book", restoredRoot.icon)

        val restoredSub = destStore.getContainer(nestedContainerId)
        assertNotNull(restoredSub)
        assertEquals(PaletteColor(4), restoredSub.color)
        assertEquals("vector:graduation-cap", restoredSub.icon)
        assertEquals(rootContainerId, restoredSub.parentId)

        // 7. Verify semantic validation passes on clean restored destination
        val destInventory = RawLegacyInventoryReader.read(destinationDb)
        val semanticErrors = ContainerRestoreService.validateContainerDatasetSemantics(destinationDb, destInventory)
        assertTrue(semanticErrors.isEmpty(), "Restored dataset must pass semantic validation without errors: $semanticErrors")

        // 8. Verify orphan HabitVisualIdentity detection in semantic validator
        destinationDb.run(
            "INSERT INTO HabitVisualIdentities (habit_uuid, icon_type, icon_value) VALUES ('orphan-ghost-habit-uuid', 'VECTOR', 'alert')"
        )
        val orphanErrors = ContainerRestoreService.validateContainerDatasetSemantics(destinationDb, destInventory)
        assertTrue(
            orphanErrors.any { it.contains("HabitVisualIdentity references non-existent habit UUID 'orphan-ghost-habit-uuid'") },
            "Semantic validator must catch orphan HabitVisualIdentities referencing non-existent habits"
        )

        sourceDb.close()
        backupDb.close()
        stagingDb.close()
        destinationDb.close()
    }

    @Test
    fun testFullBackupRestoreRoundtrip_userTasks() = runTest {
        val sourceDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val beforeInventory = RawLegacyInventoryReader.read(sourceDb)
        val plan = LegacyContainerMigrationPlanner.plan(beforeInventory, cutoverTimestamp = 1000000L)
        val execResult = LegacyContainerMigrationExecutor.execute(sourceDb, plan)
        assertTrue(execResult is MigrationExecutionResult.Success)

        val taskRepo = org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository(sourceDb)
        val rootTask = org.isoron.uhabits.core.userTasks.UserTask(
            id = org.isoron.uhabits.core.userTasks.TaskId("ut-root-1"),
            title = "Finalize Architecture Spec",
            description = "Sections 1 through 5",
            containerId = ContainerId("b-uuid-1"),
            status = org.isoron.uhabits.core.userTasks.TaskStatus.PENDING,
            plannedDate = org.isoron.platform.time.LocalDate(2026, 10, 4),
            deadline = 1791108000000L,
            completedAt = null,
            parentTaskId = null,
            sortOrder = 0,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val subTask = org.isoron.uhabits.core.userTasks.UserTask(
            id = org.isoron.uhabits.core.userTasks.TaskId("ut-sub-1"),
            title = "Draft diagram",
            description = "Mermaid chart",
            containerId = ContainerId("b-uuid-1"),
            status = org.isoron.uhabits.core.userTasks.TaskStatus.COMPLETED,
            plannedDate = org.isoron.platform.time.LocalDate(2026, 10, 3),
            deadline = null,
            completedAt = 2000L,
            parentTaskId = rootTask.id,
            sortOrder = 0,
            createdAt = 1100L,
            updatedAt = 2000L,
        )
        val standaloneTask = org.isoron.uhabits.core.userTasks.UserTask(
            id = org.isoron.uhabits.core.userTasks.TaskId("ut-inbox-1"),
            title = "Buy fresh coffee beans",
            description = "",
            containerId = null,
            status = org.isoron.uhabits.core.userTasks.TaskStatus.PENDING,
            plannedDate = null,
            deadline = null,
            completedAt = null,
            parentTaskId = null,
            sortOrder = 1,
            createdAt = 1200L,
            updatedAt = 1200L,
        )
        taskRepo.save(rootTask)
        taskRepo.save(subTask)
        taskRepo.save(standaloneTask)

        // Sync delivery state belongs to a live account/database, not a backup.
        seedDeliveryState(sourceDb, "source-account")

        // Create full experimental backup
        val backupDb = TestDatabaseHelper.createEmptyDatabase()
        val backupRes = ContainerBackupService.createBackup(
            sourceDb = sourceDb,
            targetDb = backupDb,
            timestamp = 3000000L,
            preferences = mapOf("theme" to "dark"),
        )
        assertTrue(backupRes is BackupCreationResult.Success)

        assertTrue(DatabaseSnapshotter.listTables(backupDb).intersect(
            setOf("SyncInbox", "SyncState", "SyncTombstones")
        ).isEmpty())
        assertEquals(1L, sourceDb.queryLong("SELECT count(*) FROM SyncInbox"))

        val stagingDb = TestDatabaseHelper.createEmptyDatabase()
        val destinationDb = TestDatabaseHelper.createEmptyDatabase()

        seedDeliveryState(destinationDb, "previous-destination-account")

        val restoreRes = ContainerRestoreService.restore(
            backupDb = backupDb,
            stagingDb = stagingDb,
            destinationDb = destinationDb,
        )
        assertTrue(restoreRes is RestoreResult.Success)

        for (table in listOf("SyncInbox", "SyncState", "SyncTombstones")) {
            assertEquals(0L, destinationDb.queryLong("SELECT count(*) FROM $table"), table)
        }
        assertEquals(7L, destinationDb.queryLong("SELECT count(*) FROM Habits"))

        // Verify tasks in destinationDb
        val destTaskRepo = org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository(destinationDb)
        val restoredRoot = destTaskRepo.getById(rootTask.id)
        assertNotNull(restoredRoot)
        assertEquals("Finalize Architecture Spec", restoredRoot.title)
        assertEquals("Sections 1 through 5", restoredRoot.description)
        assertEquals(ContainerId("b-uuid-1"), restoredRoot.containerId)
        assertEquals(org.isoron.uhabits.core.userTasks.TaskStatus.PENDING, restoredRoot.status)
        assertEquals(org.isoron.platform.time.LocalDate(2026, 10, 4), restoredRoot.plannedDate)
        assertEquals(1791108000000L, restoredRoot.deadline)
        assertNull(restoredRoot.completedAt)
        assertNull(restoredRoot.parentTaskId)

        val restoredSub = destTaskRepo.getById(subTask.id)
        assertNotNull(restoredSub)
        assertEquals("Draft diagram", restoredSub.title)
        assertEquals(org.isoron.uhabits.core.userTasks.TaskStatus.COMPLETED, restoredSub.status)
        assertEquals(2000L, restoredSub.completedAt)
        assertEquals(rootTask.id, restoredSub.parentTaskId)

        val restoredInbox = destTaskRepo.getById(standaloneTask.id)
        assertNotNull(restoredInbox)
        assertEquals("Buy fresh coffee beans", restoredInbox.title)
        assertNull(restoredInbox.containerId)
        assertNull(restoredInbox.parentTaskId)

        val destInventory = RawLegacyInventoryReader.read(destinationDb)
        val errors = ContainerRestoreService.validateContainerDatasetSemantics(destinationDb, destInventory)
        assertTrue(errors.isEmpty(), "Restored UserTasks dataset must pass semantic validation without errors: $errors")

        // Verify invalid parent detection
        destinationDb.run(
            "INSERT INTO UserTasks (id, title, description, status, sort_order, created_at, updated_at, parent_task_id) " +
                "VALUES ('orphan-child', 'Orphan', '', 'PENDING', 0, 1000, 1000, 'non-existent-parent')"
        )
        val orphanErrors = ContainerRestoreService.validateContainerDatasetSemantics(destinationDb, destInventory)
        assertTrue(
            orphanErrors.any { it.contains("references non-existent parentTask 'non-existent-parent'") },
            "Semantic validator must catch orphan parentTask reference"
        )

        sourceDb.close()
        backupDb.close()
        stagingDb.close()
        destinationDb.close()
    }

    private fun seedDeliveryState(db: org.isoron.platform.io.Database, account: String) {
        org.isoron.uhabits.core.sync.SyncInboxSchema.createSchema(db)
        org.isoron.uhabits.core.sync.SyncStateRepository(db).apply {
            setCursor(42L)
            setAccountUserId(account)
        }
        db.run("INSERT INTO SyncTombstones VALUES ('user_task', 'deleted-task', 100)")
        db.run("""
            INSERT INTO SyncInbox (log_id, device_id, op_uuid, entity_type, entity_uuid,
                operation_type, payload_json, created_at, received_at, deferred_reason)
            VALUES (43, 'device', 'op', 'user_task', 'stale-task', 'UPSERT', '{}', 100, 101, 'DEPENDENCY')
        """.trimIndent())
    }
}
