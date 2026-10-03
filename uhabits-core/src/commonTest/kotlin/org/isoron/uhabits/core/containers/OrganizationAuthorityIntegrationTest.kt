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

package org.isoron.uhabits.core.containers

import kotlinx.coroutines.test.runTest
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class OrganizationAuthorityIntegrationTest : BaseUnitTest() {

    private lateinit var sqlModelFactory: SQLModelFactory
    private lateinit var sqliteList: SQLiteHabitList
    private lateinit var store: SQLiteOrganizationStore
    private lateinit var orgService: OrganizationServiceImpl
    private lateinit var facade: HabitOrganizationFacadeImpl

    private suspend fun setUpContainerLocal() {
        val db = buildMemoryDatabase()
        OrganizationSchema.createSchema(db)
        sqlModelFactory = SQLModelFactory(db)
        store = SQLiteOrganizationStore(db)
        store.seedBaseline(
            revision = OrganizationRevision(1L),
            recordedAt = 1000L
        )
        var time = 1000L
        var idCounter = 1
        val clock = Clock { time += 10L; time }
        val idGen = IdGenerator { "test-id-${idCounter++}" }
        val habitLookup = HabitIdentityLookup { true }
        orgService = OrganizationServiceImpl(
            store = store,
            unitOfWork = store,
            containerQueries = store,
            placementQueries = store,
            clock = clock,
            idGenerator = idGen,
            habitIdentityLookup = habitLookup
        )
        facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store,
            habitPlacementQueries = store,
            organizationService = orgService,
            habitBlockRepository = sqlModelFactory.habitBlockRepository
        )
        sqliteList = SQLiteHabitList(sqlModelFactory).apply {
            organizationFacade = facade
        }
    }

    private suspend fun setUpLegacy() {
        val db = buildMemoryDatabase()
        sqlModelFactory = SQLModelFactory(db)
        facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.LEGACY,
            habitBlockRepository = sqlModelFactory.habitBlockRepository
        )
        sqliteList = SQLiteHabitList(sqlModelFactory).apply {
            organizationFacade = facade
        }
    }

    @Test
    fun `legacy mode - habit creation and block assignment works normally`() = runTest {
        setUpLegacy()
        val habit = sqlModelFactory.buildHabit().apply {
            name = "Legacy Habit"
            blockId = 1L
        }
        val cmd = CreateHabitCommand(sqlModelFactory, sqliteList, habit)
        cmd.run()

        val saved = sqliteList.getByUUID(habit.uuid!!)
        assertNotNull(saved)
        assertEquals(1L, saved.blockId)
        val ext = sqlModelFactory.habitExtensionRepository.findByHabitId(saved.id!!)
        assertEquals(1L, ext?.blockId)
    }

    @Test
    fun `container local mode - habit creation with container placement`() = runTest {
        setUpContainerLocal()
        val rootId = ContainerId(Uuid.random().toHexString())
        orgService.create(CreateContainer(name = "Health", parentId = null, color = PaletteColor(1), opUuid = "op-c1", explicitId = rootId))

        val model = sqlModelFactory.buildHabit().apply {
            name = "Exercise"
            blockId = 7L // Legacy block should be ignored in container mode!
        }
        val cmd = CreateHabitCommand(
            modelFactory = sqlModelFactory,
            habitList = sqliteList,
            model = model,
            initialContainerId = rootId,
            organizationFacade = facade
        )
        cmd.run()

        val saved = sqliteList.getByUUID(model.uuid!!)
        assertNotNull(saved)
        // Legacy blockId must be null for new habits in CONTAINER_LOCAL mode
        assertNull(saved.blockId)

        // Container placement must be present in HabitPlacements
        val placement = store.current(HabitRef(model.uuid!!))
        assertNotNull(placement)
        assertEquals(rootId, placement.containerId)

        // Facade placement info
        val info = facade.getPlacementInfo(model.uuid!!, null)
        assertFalse(info.isUnassigned)
        assertEquals(rootId, info.containerId)
        assertEquals("Health", info.containerName)
    }

    @Test
    fun `container local mode - habit creation unassigned`() = runTest {
        setUpContainerLocal()
        val model = sqlModelFactory.buildHabit().apply {
            name = "Random Idea"
        }
        val cmd = CreateHabitCommand(
            modelFactory = sqlModelFactory,
            habitList = sqliteList,
            model = model,
            initialContainerId = null,
            organizationFacade = facade
        )
        cmd.run()

        val saved = sqliteList.getByUUID(model.uuid!!)
        assertNotNull(saved)
        assertNull(saved.blockId)

        val placement = store.current(HabitRef(model.uuid!!))
        assertNull(placement?.containerId)

        val info = facade.getPlacementInfo(model.uuid!!, null)
        assertTrue(info.isUnassigned)
    }

    @Test
    fun `container local mode - ordinary habit edit does NOT change placement or write legacy block`() = runTest {
        setUpContainerLocal()
        val rootId = ContainerId(Uuid.random().toHexString())
        orgService.create(CreateContainer(name = "Health", parentId = null, color = PaletteColor(1), opUuid = "op-c1", explicitId = rootId))

        val model = sqlModelFactory.buildHabit().apply {
            name = "Exercise"
        }
        CreateHabitCommand(sqlModelFactory, sqliteList, model, initialContainerId = rootId, organizationFacade = facade).run()

        val saved = sqliteList.getByUUID(model.uuid!!)!!
        val originalRev = orgService.currentRevision()

        // Edit habit fields (name, question, dayTier, timerEnabled) without container change
        val modified = sqlModelFactory.buildHabit().apply {
            copyFrom(saved)
            name = "Intense Exercise"
            question = "Did you sweat today?"
            dayTier = DayTier.IDEAL
            timerEnabled = true
        }

        val editCmd = EditHabitCommand(
            habitList = sqliteList,
            habitId = saved.id!!,
            modified = modified,
            changeContainer = false,
            organizationFacade = facade
        )
        editCmd.run()

        val updated = sqliteList.getById(saved.id!!)!!
        assertEquals("Intense Exercise", updated.name)
        assertEquals(DayTier.IDEAL, updated.dayTier)
        assertNull(updated.blockId)

        // Revision must not have changed because placement was not touched
        assertEquals(originalRev, orgService.currentRevision())

        // Placement remains intact
        val placement = store.current(HabitRef(model.uuid!!))
        assertNotNull(placement)
        assertEquals(rootId, placement.containerId)
    }

    @Test
    fun `container local mode - move habit updates placements and history without touching legacy block`() = runTest {
        setUpContainerLocal()
        val root1 = ContainerId(Uuid.random().toHexString())
        val root2 = ContainerId(Uuid.random().toHexString())
        orgService.create(CreateContainer(name = "Health", parentId = null, color = PaletteColor(1), opUuid = "op-r1", explicitId = root1))
        orgService.create(CreateContainer(name = "Fitness", parentId = null, color = PaletteColor(2), opUuid = "op-r2", explicitId = root2))

        val model = sqlModelFactory.buildHabit().apply { name = "Running" }
        CreateHabitCommand(sqlModelFactory, sqliteList, model, initialContainerId = root1, organizationFacade = facade).run()

        val saved = sqliteList.getByUUID(model.uuid!!)!!

        // Move to root2
        val modified = sqlModelFactory.buildHabit().apply { copyFrom(saved) }
        val editCmd = EditHabitCommand(
            habitList = sqliteList,
            habitId = saved.id!!,
            modified = modified,
            targetContainerId = root2,
            changeContainer = true,
            organizationFacade = facade
        )
        editCmd.run()

        // Placement updated to root2
        val placement = store.current(HabitRef(model.uuid!!))
        assertNotNull(placement)
        assertEquals(root2, placement.containerId)

        // History recorded: check placement history at revision
        val revAfterMove = orgService.currentRevision()
        val histPlacement = store.getHabitPlacementHistoryAtRevision(HabitRef(model.uuid!!), revAfterMove)
        assertEquals(root2, histPlacement?.containerId)

        // Legacy blockId in extensions remains untouched
        val ext = sqlModelFactory.habitExtensionRepository.findByHabitId(saved.id!!)
        assertNull(ext?.blockId)
    }

    @Test
    fun `container local mode - delete habit retains placement history and tombstone`() = runTest {
        setUpContainerLocal()
        val root = ContainerId(Uuid.random().toHexString())
        orgService.create(CreateContainer(name = "Health", parentId = null, color = PaletteColor(1), opUuid = "op-r1", explicitId = root))

        val model = sqlModelFactory.buildHabit().apply { name = "Running" }
        CreateHabitCommand(sqlModelFactory, sqliteList, model, initialContainerId = root, organizationFacade = facade).run()

        val saved = sqliteList.getByUUID(model.uuid!!)!!
        val habitRef = HabitRef(model.uuid!!)

        // Delete habit
        DeleteHabitsCommand(sqliteList, listOf(saved)).run()

        // Habit removed from active list
        assertNull(sqliteList.getByUUID(model.uuid!!))

        // Habit has deletedAt tombstone in Habit table
        val habitData = sqlModelFactory.habitRepository.findByUuid(saved.uuid!!)
        assertNotNull(habitData?.deletedAt)

        // Placement is PRESERVED (not purged)
        val currentPlacement = store.current(habitRef)
        assertEquals(root, currentPlacement?.containerId)
    }

    @Test
    fun `container local mode - removeAll hard purge is prohibited`() = runTest {
        setUpContainerLocal()
        assertFailsWith<UnsupportedOperationException> {
            sqliteList.removeAll()
        }
    }

    @Test
    fun `container local mode - legacy block writers throw IllegalStateException`() = runTest {
        setUpContainerLocal()
        val repo = sqlModelFactory.habitBlockRepository
        assertFailsWith<IllegalStateException> {
            repo.insert(HabitBlockData(name = "Disallowed", color = 1, icon = null, position = 0, isArchived = false))
        }
        assertFailsWith<IllegalStateException> {
            repo.update(HabitBlockData(id = 1L, name = "Disallowed", color = 1, icon = null, position = 0, isArchived = false))
        }
        assertFailsWith<IllegalStateException> {
            repo.delete(1L)
        }
        assertFailsWith<IllegalStateException> {
            repo.softDelete(1L, 1000L)
        }
    }

    @Test
    fun `container local mode - deep hierarchy depth 4+ resolves to root container in BY_SPHERE sorting`() = runTest {
        setUpContainerLocal()
        val r1 = ContainerId("root-1")
        val l1 = ContainerId("level-1")
        val l2 = ContainerId("level-2")
        val l3 = ContainerId("level-3")

        val r2 = ContainerId("root-2")

        orgService.create(CreateContainer(name = "Study", parentId = null, color = PaletteColor(1), siblingOrder = 0, opUuid = "op-r1", explicitId = r1))
        orgService.create(CreateContainer(name = "Computer Science", parentId = r1, color = PaletteColor(1), opUuid = "op-l1", explicitId = l1))
        orgService.create(CreateContainer(name = "Databases", parentId = l1, color = PaletteColor(1), opUuid = "op-l2", explicitId = l2))
        orgService.create(CreateContainer(name = "Lab 2", parentId = l2, color = PaletteColor(1), opUuid = "op-l3", explicitId = l3))

        orgService.create(CreateContainer(name = "Work", parentId = null, color = PaletteColor(2), siblingOrder = 1, opUuid = "op-r2", explicitId = r2))

        val habitDeep = sqlModelFactory.buildHabit().apply { name = "Deep Habit" }
        CreateHabitCommand(sqlModelFactory, sqliteList, habitDeep, initialContainerId = l3, organizationFacade = facade).run()

        val habitWork = sqlModelFactory.buildHabit().apply { name = "Work Habit" }
        CreateHabitCommand(sqlModelFactory, sqliteList, habitWork, initialContainerId = r2, organizationFacade = facade).run()

        // Root group key for deep habit must be root-1
        assertEquals("root-1", facade.getRootGroupKey(habitDeep.uuid!!, null))
        assertEquals("Study", facade.getRootGroupName(habitDeep.uuid!!, null))

        // Sorting by BY_SPHERE must group root-1 before root-2 (by sibling order)
        sqliteList.resort()
        val sorted = sqliteList.toMutableList()
        assertEquals(habitDeep.uuid, sorted[0].uuid)
        assertEquals(habitWork.uuid, sorted[1].uuid)
    }

    @Test
    fun `container local mode - bulk operations subtree isolation`() = runTest {
        setUpContainerLocal()
        val rootA = ContainerId("root-a")
        val childA = ContainerId("child-a")
        val rootB = ContainerId("root-b")

        orgService.create(CreateContainer(name = "RootA", parentId = null, color = PaletteColor(1), opUuid = "op-ra", explicitId = rootA))
        orgService.create(CreateContainer(name = "ChildA", parentId = rootA, color = PaletteColor(1), opUuid = "op-ca", explicitId = childA))
        orgService.create(CreateContainer(name = "RootB", parentId = null, color = PaletteColor(2), opUuid = "op-rb", explicitId = rootB))

        val hA = sqlModelFactory.buildHabit().apply { name = "Habit A" }
        val hChild = sqlModelFactory.buildHabit().apply { name = "Habit Child" }
        val hB = sqlModelFactory.buildHabit().apply { name = "Habit B" }

        CreateHabitCommand(sqlModelFactory, sqliteList, hA, initialContainerId = rootA, organizationFacade = facade).run()
        CreateHabitCommand(sqlModelFactory, sqliteList, hChild, initialContainerId = childA, organizationFacade = facade).run()
        CreateHabitCommand(sqlModelFactory, sqliteList, hB, initialContainerId = rootB, organizationFacade = facade).run()

        // Subtree query for rootA must include hA and hChild, but NOT hB
        val subtreeUuids = facade.getHabitUuidsForContainer(rootA.value, subtree = true)
        assertEquals(setOf(hA.uuid, hChild.uuid), subtreeUuids)
        assertFalse(subtreeUuids.contains(hB.uuid))

        // Direct query for rootA includes only hA
        val directUuids = facade.getHabitUuidsForContainer(rootA.value, subtree = false)
        assertEquals(setOf(hA.uuid), directUuids)
    }

    @Test
    fun `container local mode - statistics filter by containerKey with allowedHabitUuids`() = runTest {
        setUpContainerLocal()
        val rootA = ContainerId("root-a")
        orgService.create(CreateContainer(name = "RootA", parentId = null, color = PaletteColor(1), opUuid = "op-ra", explicitId = rootA))

        val h1 = sqlModelFactory.buildHabit().apply { name = "Habit 1" }
        val h2 = sqlModelFactory.buildHabit().apply { name = "Habit 2" }
        CreateHabitCommand(sqlModelFactory, sqliteList, h1, initialContainerId = rootA, organizationFacade = facade).run()
        CreateHabitCommand(sqlModelFactory, sqliteList, h2, initialContainerId = null, organizationFacade = facade).run()

        val allowed = facade.getHabitUuidsForContainer(rootA.value, subtree = true)
        val filterState = StatisticsFilterState(
            allowedHabitUuids = allowed,
            containerKey = rootA.value
        )

        // Filtering using allowedHabitUuids
        val filtered = sqliteList.filter { habit ->
            filterState.allowedHabitUuids?.contains(habit.uuid) ?: true
        }
        assertEquals(1, filtered.size)
        assertEquals(h1.uuid, filtered[0].uuid)
    }
}
