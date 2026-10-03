/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.core.containers.session

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationServiceImpl
import org.isoron.uhabits.core.containers.PlaceHabit
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationValidator
import org.isoron.uhabits.core.containers.migration.MigrationExecutionResult
import org.isoron.uhabits.core.containers.migration.MigrationValidationResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class DatasetSessionTest : BaseUnitTest() {

    @Test
    fun testProductionSessionContract() {
        val session = DatasetSessionFactory.production()

        assertEquals("production", session.datasetId)
        assertEquals(DatasetMode.LEGACY_PRODUCTION, session.mode)
        assertEquals(OrganizationAuthorityMode.LEGACY, session.organizationAuthority)
        assertFalse(session.isExperimental)
        assertEquals("", session.preferencesNamespace)

        // Full capabilities in production
        assertTrue(session.capabilities.syncEnabled)
        assertTrue(session.capabilities.remoteCallsAllowed)
        assertTrue(session.capabilities.timerEnabled)
        assertTrue(session.capabilities.widgetsEnabled)
        assertTrue(session.capabilities.remindersEnabled)
        assertTrue(session.capabilities.backgroundJobsEnabled)
        assertTrue(session.capabilities.backupEnabled)
    }

    @Test
    fun testExperimentalSessionContract() {
        val metadata = ExperimentalDatasetMetadata(
            datasetId = "exp-test-uuid",
            databaseFilename = "uhabits-container-experimental.db",
            foundationVersion = 1,
            sourceSnapshotSha256 = "dummy-sha256",
            createdAtMillis = 1000L
        )

        val session = DatasetSessionFactory.experimental(metadata)

        assertEquals("exp-test-uuid", session.datasetId)
        assertEquals(DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL, session.mode)
        assertEquals(OrganizationAuthorityMode.CONTAINER_LOCAL, session.organizationAuthority)
        assertTrue(session.isExperimental)
        assertEquals("experimental_exp-test-uuid", session.preferencesNamespace)
        assertEquals("uhabits-container-experimental.db", session.databaseFilename)

        // Strict isolation: everything that can mutate wrong dataset is blocked
        assertFalse(session.capabilities.syncEnabled)
        assertFalse(session.capabilities.remoteCallsAllowed)
        assertFalse(session.capabilities.timerEnabled)
        assertFalse(session.capabilities.widgetsEnabled)
        assertFalse(session.capabilities.remindersEnabled)
        assertFalse(session.capabilities.backgroundJobsEnabled)
        assertTrue(session.capabilities.backupEnabled)
    }

    @Test
    fun testInMemoryDatasetSessionResolver() {
        var persistedMetadata: ExperimentalDatasetMetadata? = null
        var isExpSelected = false

        val resolver = object : DatasetSessionResolver {
            override fun resolvedSession(): DatasetSession {
                return if (isExpSelected && persistedMetadata != null) {
                    DatasetSessionFactory.experimental(persistedMetadata!!)
                } else {
                    DatasetSessionFactory.production()
                }
            }

            override fun validateExperimental(metadata: ExperimentalDatasetMetadata): DatasetValidationResult {
                return if (metadata.datasetId.isNotEmpty()) DatasetValidationResult.Valid
                else DatasetValidationResult.Invalid("Empty ID")
            }

            override fun persistExperimentalSelection(metadata: ExperimentalDatasetMetadata) {
                persistedMetadata = metadata
                isExpSelected = true
            }

            override fun clearExperimentalSelection() {
                isExpSelected = false
            }

            override fun loadExperimentalMetadata(): ExperimentalDatasetMetadata? = persistedMetadata
        }

        // Initially production
        assertEquals(DatasetMode.LEGACY_PRODUCTION, resolver.resolvedSession().mode)

        // Select experiment
        val metadata = ExperimentalDatasetMetadata(
            datasetId = "uuid-123",
            databaseFilename = "uhabits-container-experimental.db",
            foundationVersion = 1,
            sourceSnapshotSha256 = "sha",
            createdAtMillis = 5000L
        )
        resolver.persistExperimentalSelection(metadata)
        val expSession = resolver.resolvedSession()
        assertEquals(DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL, expSession.mode)
        assertEquals("uuid-123", expSession.datasetId)

        // Return to production
        resolver.clearExperimentalSelection()
        assertEquals(DatasetMode.LEGACY_PRODUCTION, resolver.resolvedSession().mode)
        // Metadata is preserved for future use
        assertNotNull(resolver.loadExperimentalMetadata())
    }

    @Test
    fun testDatabaseIsolation_productionRemainsUnchangedAfterExperimentalMutations() = runTest {
        // Step 1: Create seeded production DB
        val prodDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val prodInventoryBefore = RawLegacyInventoryReader.read(prodDb)
        val prodHabitsCountBefore = prodDb.queryLong("SELECT count(*) FROM Habits")
        val prodEntriesCountBefore = prodDb.queryLong("SELECT count(*) FROM Repetitions")
        val prodBlocksCountBefore = prodDb.queryLong("SELECT count(*) FROM HabitBlocks")

        // Step 2: Create experimental copy seeded from the same fixture
        val expDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val expInventory = RawLegacyInventoryReader.read(expDb)
        val plan = LegacyContainerMigrationPlanner.plan(expInventory, cutoverTimestamp = 1000000L)
        val execResult = LegacyContainerMigrationExecutor.execute(expDb, plan)
        assertTrue(execResult is MigrationExecutionResult.Success)

        val validation = LegacyContainerMigrationValidator.validate(expDb, expInventory, plan)
        assertTrue(validation is MigrationValidationResult.Valid)

        // Step 3: Perform mutations inside experimental dataset
        val expStore = SQLiteOrganizationStore(expDb)
        val expService = OrganizationServiceImpl(
            store = expStore,
            unitOfWork = expStore,
            containerQueries = expStore,
            placementQueries = expStore,
            clock = { 2000000L },
            idGenerator = { "new-exp-container" },
            habitIdentityLookup = { true }
        )
        val expFacade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = expStore,
            habitPlacementQueries = expStore,
            organizationService = expService
        )

        // Create a new container in experimental DB
        expService.create(
            CreateContainer(
                name = "New Experimental Section",
                parentId = null,
                color = PaletteColor(3),
                opUuid = "op-exp-1"
            )
        )
        assertEquals(6L, expDb.queryLong("SELECT count(*) FROM Containers"))

        // Add a new habit in experimental DB
        val expModelFactory = SQLModelFactory(expDb)
        expModelFactory.habitBlockRepository.authorityMode = OrganizationAuthorityMode.CONTAINER_LOCAL
        val expHabitList = SQLiteHabitList(expModelFactory)
        expHabitList.organizationFacade = expFacade

        val newHabit = expModelFactory.buildHabit().apply {
            name = "Experimental Only Habit"
            color = PaletteColor(4)
        }
        CreateHabitCommand(
            modelFactory = expModelFactory,
            habitList = expHabitList,
            model = newHabit,
            initialContainerId = org.isoron.uhabits.core.containers.ContainerId(expFacade.rootContainers().first().key),
            organizationFacade = expFacade
        ).run()

        // Verify experimental DB was mutated
        assertEquals(prodHabitsCountBefore + 1, expDb.queryLong("SELECT count(*) FROM Habits"))
        assertEquals(8L, expDb.queryLong("SELECT count(*) FROM HabitPlacements"))

        // Step 4: Verify production DB was completely untouched
        val prodInventoryAfter = RawLegacyInventoryReader.read(prodDb)
        assertEquals(prodInventoryBefore.snapshotSha256, prodInventoryAfter.snapshotSha256, "Production DB checksum must be identical")
        assertEquals(prodHabitsCountBefore, prodDb.queryLong("SELECT count(*) FROM Habits"))
        assertEquals(prodEntriesCountBefore, prodDb.queryLong("SELECT count(*) FROM Repetitions"))
        assertEquals(prodBlocksCountBefore, prodDb.queryLong("SELECT count(*) FROM HabitBlocks"))

        // Verify production DB has NO container tables
        val prodTables = prodDb.querySingle("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='Containers'") { it.getLong(0) }
        assertEquals(0L, prodTables)

        prodDb.close()
        expDb.close()
    }

    @Test
    fun testDualWriteGuardsInExperimentalMode() = runTest {
        val expDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val expInventory = RawLegacyInventoryReader.read(expDb)
        val plan = LegacyContainerMigrationPlanner.plan(expInventory, cutoverTimestamp = 1000000L)
        LegacyContainerMigrationExecutor.execute(expDb, plan)

        val expModelFactory = SQLModelFactory(expDb)
        expModelFactory.habitBlockRepository.authorityMode = OrganizationAuthorityMode.CONTAINER_LOCAL

        // Attempting to mutate legacy blocks in CONTAINER_LOCAL must throw
        assertFailsWith<IllegalStateException> {
            expModelFactory.habitBlockRepository.insert(
                org.isoron.uhabits.core.database.HabitBlockData(
                    name = "Forbidden Block",
                    color = 1
                )
            )
        }

        assertFailsWith<IllegalStateException> {
            expModelFactory.habitBlockRepository.delete(1L)
        }

        expDb.close()
    }

    @Test
    fun testSwitchingCyclesPreserveState() = runTest {
        val prodDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val initialProdSha = RawLegacyInventoryReader.read(prodDb).snapshotSha256

        // Cycle 1: Switch to experiment
        val expDb = SemanticMigrationFixtures.createAndSeedV29Database()
        val plan = LegacyContainerMigrationPlanner.plan(RawLegacyInventoryReader.read(expDb), 1000L)
        LegacyContainerMigrationExecutor.execute(expDb, plan)

        val expStore = SQLiteOrganizationStore(expDb)
        val expService = OrganizationServiceImpl(
            store = expStore,
            unitOfWork = expStore,
            containerQueries = expStore,
            placementQueries = expStore,
            clock = { 2000L },
            idGenerator = { "c-cycle" },
            habitIdentityLookup = { true }
        )
        expService.create(CreateContainer(name = "Cycle Container", parentId = null, color = PaletteColor(1), opUuid = "op-c1"))

        // Cycle 2: Return to production
        val prodInventoryCycle1 = RawLegacyInventoryReader.read(prodDb)
        assertEquals(initialProdSha, prodInventoryCycle1.snapshotSha256)

        // Cycle 3: Switch back to experiment (re-open existing experimental DB)
        assertEquals(6L, expDb.queryLong("SELECT count(*) FROM Containers"))

        // Cycle 4: Return to production again
        val prodInventoryCycle2 = RawLegacyInventoryReader.read(prodDb)
        assertEquals(initialProdSha, prodInventoryCycle2.snapshotSha256)

        prodDb.close()
        expDb.close()
    }
}
