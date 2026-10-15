package org.isoron.uhabits.core.models

import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import dev.mokkery.matcher.any
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.io.HabitResortDiagnostics
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryHabitListResortTest {

    @Test
    fun countResortCallsDuringResort() {
        val reports = mutableListOf<HabitResortDiagnostics.Report>()
        HabitResortDiagnostics.observer = { reports.add(it) }
        try {
            val parent = MemoryHabitList()
            val factory = MemoryModelFactory()
            repeat(42) { index ->
                parent.add(factory.buildHabit().apply { name = "Habit $index"; position = index })
            }
            val facade = mock<HabitOrganizationFacade>()
            every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
            every { facade.getRootGroupKey(any(), any()) } calls { "root-${it.arg<String>(0)}" }
            every { facade.rootContainers() } returns listOf(OrganizationalItem("root-1", "Root 1", PaletteColor(1), null, "Root 1", true, false))

            parent.organizationFacade = facade
            parent.primaryOrder = HabitList.Order.BY_SPHERE

            val child1 = parent.getFiltered(HabitMatcher(isArchivedAllowed = false))
            val child2 = parent.getFiltered(HabitMatcher(isArchivedAllowed = true))

            reports.clear()

            HabitResortDiagnostics.collect(100) {
                // Emulate what SQLiteHabitList.resort() does:
                parent.organizationFacade = facade
                parent.resort()
            }

            val report = reports.single()
            assertEquals(1L, report.counters["memory.resortCalls"], "Redundant assignment and child resort must be eliminated")
            assertEquals(1L, report.counters["sphere.cacheResets"], "Cache reset must occur exactly once for parent sort")
            assertEquals(42L, report.counters["memory.sortedHabitVisits"], "Only parent's 42 habits should be visited by TimSort")
            assertEquals(42L, report.counters["sphere.rootCacheMisses"], "Only 42 root lookups for parent sort")
            assertEquals(3L, report.counters["observable.notifications"], "1 parent notification + 2 child notifications")
            assertEquals(2L, report.counters["observable.callbacks"], "2 child rebuild callbacks")
        } finally {
            HabitResortDiagnostics.observer = null
        }
    }

    @Test
    fun filteredListPreservesParentOrderWithoutSorting() {
        val parent = MemoryHabitList()
        val factory = MemoryModelFactory()
        val h1 = factory.buildHabit().apply { name = "B"; position = 1 }
        val h2 = factory.buildHabit().apply { name = "A"; position = 0 }
        val h3 = factory.buildHabit().apply { name = "C"; position = 2 }
        parent.add(h1)
        parent.add(h2)
        parent.add(h3)

        parent.primaryOrder = HabitList.Order.BY_NAME_ASC
        assertEquals(listOf("A", "B", "C"), parent.map { it.name })

        val child = parent.getFiltered(HabitMatcher(isArchivedAllowed = true))
        assertEquals(listOf("A", "B", "C"), child.map { it.name })

        // Adding a habit to parent reflects in child in exact sorted order
        val h4 = factory.buildHabit().apply { name = "AB"; position = 3 }
        parent.add(h4)
        assertEquals(listOf("A", "AB", "B", "C"), parent.map { it.name })
        assertEquals(listOf("A", "AB", "B", "C"), child.map { it.name })

        // When order changes on both parent and child (as HabitCardListCache does), both reflect it
        child.primaryOrder = HabitList.Order.BY_NAME_DESC
        parent.primaryOrder = HabitList.Order.BY_NAME_DESC
        assertEquals(listOf("C", "B", "AB", "A"), parent.map { it.name })
        assertEquals(listOf("C", "B", "AB", "A"), child.map { it.name })
    }

    @Test
    fun filteredListWithCustomOrderStillSortsIndependently() {
        val parent = MemoryHabitList()
        val factory = MemoryModelFactory()
        val h1 = factory.buildHabit().apply { name = "B"; position = 1 }
        val h2 = factory.buildHabit().apply { name = "A"; position = 0 }
        val h3 = factory.buildHabit().apply { name = "C"; position = 2 }
        parent.add(h1)
        parent.add(h2)
        parent.add(h3)

        parent.primaryOrder = HabitList.Order.BY_NAME_ASC
        val child = parent.getFiltered(HabitMatcher(isArchivedAllowed = true)) as MemoryHabitList

        // Give child a different order
        child.primaryOrder = HabitList.Order.BY_NAME_DESC
        assertEquals(listOf("A", "B", "C"), parent.map { it.name })
        assertEquals(listOf("C", "B", "A"), child.map { it.name })

        // Adding an item to parent updates child in child's custom order
        val h0 = factory.buildHabit().apply { name = "D"; position = 3 }
        parent.add(h0)
        assertEquals(listOf("A", "B", "C", "D"), parent.map { it.name })
        assertEquals(listOf("D", "C", "B", "A"), child.map { it.name })
    }

    @Test
    fun redundantPropertyAssignmentDoesNotTriggerResort() {
        val parent = MemoryHabitList()
        val factory = MemoryModelFactory()
        parent.add(factory.buildHabit().apply { name = "H1"; position = 0 })

        var resortCount = 0
        parent.observable.addListener { resortCount++ }

        parent.primaryOrder = HabitList.Order.BY_POSITION
        assertEquals(0, resortCount, "Setting identical primaryOrder must be a no-op")

        parent.secondaryOrder = HabitList.Order.BY_NAME_ASC
        assertEquals(0, resortCount, "Setting identical secondaryOrder must be a no-op")

        parent.organizationFacade = null
        assertEquals(0, resortCount, "Setting identical organizationFacade must be a no-op")
    }
}
