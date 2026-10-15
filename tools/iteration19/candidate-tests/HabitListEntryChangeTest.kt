package org.isoron.uhabits.core.models

import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.io.HabitResortDiagnostics
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HabitListEntryChangeTest : BaseUnitTest() {
    private val factory = MemoryModelFactory()

    private fun habit(name: String, position: Int) = factory.buildHabit().apply {
        this.name = name
        this.position = position
    }

    private fun enter(habit: Habit, value: Int) {
        habit.originalEntries.add(Entry(getToday(), value))
        habit.recompute()
    }

    @Test
    fun stableEntryEditNotifiesWithoutSortingParentOrMatchingChildren() {
        val parent = MemoryHabitList()
        val a = habit("A", 0)
        val b = habit("B", 1)
        parent.add(a); parent.add(b)
        parent.primaryOrder = HabitList.Order.BY_SPHERE
        val child = parent.getFiltered(HabitMatcher())
        var deliveries = 0
        parent.observable.addListener { deliveries++ }
        child.observable.addListener { deliveries++ }
        val reports = mutableListOf<HabitResortDiagnostics.Report>()
        HabitResortDiagnostics.observer = { reports.add(it) }
        try {
            enter(b, Entry.YES_MANUAL)
            HabitResortDiagnostics.collect(19) { parent.onEntriesChanged() }
            assertEquals(listOf(a, b), child.toList())
            assertEquals(2, deliveries)
            assertEquals(0L, reports.single().counters["memory.resortCalls"] ?: 0L)
            assertEquals(0L, reports.single().counters["composed.comparisons"] ?: 0L)
        } finally { HabitResortDiagnostics.observer = null }
    }

    @Test
    fun stablePositionAndNameTiesPreserveOrderAndDeliverEveryListener() {
        val parent = MemoryHabitList()
        val b = habit("B", 0)
        val a = habit("A", 0)
        parent.add(b); parent.add(a)
        val child = parent.getFiltered(HabitMatcher())
        var parentCalls = 0
        var childCalls = 0
        parent.observable.addListener { parentCalls++ }
        child.observable.addListener { childCalls++ }
        enter(b, Entry.YES_MANUAL)
        parent.onEntriesChanged()
        assertEquals(listOf(a, b), parent.toList())
        assertEquals(listOf(a, b), child.toList())
        assertEquals(1, parentCalls)
        assertEquals(1, childCalls)
    }

    @Test
    fun legacyGroupedPositionAndNameTiesRemainStable() {
        val parent = MemoryHabitList()
        val b = habit("B", 0).apply { blockId = 1 }
        val a = habit("A", 0).apply { blockId = 1 }
        val ungrouped = habit("Outside", -1)
        parent.setBlocks(listOf(HabitBlock(1, "Group", PaletteColor(1), null, 0, false)))
        parent.add(ungrouped); parent.add(b); parent.add(a)
        parent.primaryOrder = HabitList.Order.BY_SPHERE
        enter(b, Entry.YES_MANUAL)
        parent.onEntriesChanged()
        assertEquals(listOf(a, b, ungrouped), parent.toList())
        b.position = -2
        parent.resort()
        assertEquals(listOf(b, a, ungrouped), parent.toList())
    }

    @Test
    fun nestedPlacementRootChangesStillResortThroughUnchangedFacade() {
        val parent = MemoryHabitList()
        val a = habit("A", 1)
        val b = habit("B", 0)
        parent.add(a); parent.add(b)
        val roots = listOf("first", "second").map {
            OrganizationalItem(it, it, PaletteColor(1), null, it, true, false)
        }
        val facade = mock<HabitOrganizationFacade>()
        var aRoot = "first"
        every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
        every { facade.rootContainers() } returns roots
        // The facade boundary resolves arbitrarily nested placements to their roots.
        every { facade.getRootGroupKey(any(), any()) } calls {
            if (it.arg<String>(0) == a.uuid) aRoot else "second"
        }
        parent.organizationFacade = facade
        parent.primaryOrder = HabitList.Order.BY_SPHERE
        val child = parent.getFiltered(HabitMatcher())
        enter(a, Entry.YES_MANUAL)
        parent.onEntriesChanged()
        assertEquals(listOf(a, b), parent.toList())
        assertEquals(listOf(a, b), child.toList())
        aRoot = "second"
        parent.resort()
        assertEquals(listOf(b, a), parent.toList())
        assertEquals(listOf(b, a), child.toList())
    }

    @Test
    fun scoreAndStatusOrdersIncludingSecondaryRespondToEntries() {
        for (order in listOf(HabitList.Order.BY_SCORE_ASC, HabitList.Order.BY_SCORE_DESC,
            HabitList.Order.BY_STATUS_ASC, HabitList.Order.BY_STATUS_DESC)) {
            for (secondary in listOf(false, true)) {
                val parent = MemoryHabitList()
                val a = habit("A", 0)
                val b = habit("B", 0)
                parent.add(a); parent.add(b)
                if (secondary) parent.secondaryOrder = order else parent.primaryOrder = order
                enter(b, Entry.YES_MANUAL)
                parent.onEntriesChanged()
                val ascending = order == HabitList.Order.BY_SCORE_ASC || order == HabitList.Order.BY_STATUS_ASC
                // Score ASC currently places higher scores first; status ASC reverses completed-first.
                val bFirst = if (order == HabitList.Order.BY_SCORE_ASC || order == HabitList.Order.BY_SCORE_DESC) ascending else !ascending
                assertEquals(if (bFirst) listOf(b, a) else listOf(a, b), parent.toList(), "$order secondary=$secondary")
            }
        }
    }

    @Test
    fun completionAndEnteredMembershipPropagateThroughNestedChildren() {
        val parent = MemoryHabitList()
        val a = habit("A", 0)
        parent.add(a)
        val incomplete = parent.getFiltered(HabitMatcher(isCompletedAllowed = false))
        val unentered = incomplete.getFiltered(HabitMatcher(isEnteredAllowed = false))
        enter(a, Entry.NO)
        parent.onEntriesChanged()
        assertEquals(listOf(a), incomplete.toList())
        assertEquals(emptyList(), unentered.toList())
        enter(a, Entry.YES_MANUAL)
        parent.onEntriesChanged()
        assertEquals(emptyList(), incomplete.toList())
        enter(a, Entry.UNKNOWN)
        parent.onEntriesChanged()
        assertEquals(listOf(a), incomplete.toList())
        assertEquals(listOf(a), unentered.toList())
    }

    @Test
    fun independentlySortedChildStillRespondsWhenParentOrderIsStable() {
        val parent = MemoryHabitList()
        val a = habit("A", 0)
        val b = habit("B", 1)
        parent.add(a); parent.add(b)
        val child = parent.getFiltered(HabitMatcher())
        child.primaryOrder = HabitList.Order.BY_STATUS_DESC
        enter(b, Entry.YES_MANUAL)
        parent.onEntriesChanged()
        assertEquals(listOf(a, b), parent.toList())
        assertEquals(listOf(b, a), child.toList())
    }

    @Test
    fun reentrantObserverOrderAndPropertyMutationReachesChildren() {
        val parent = MemoryHabitList()
        val a = habit("A", 0)
        val b = habit("B", 1)
        parent.add(a); parent.add(b)
        var mutate = true
        parent.observable.addListener {
            if (mutate) {
                mutate = false
                a.name = "Z"
                parent.primaryOrder = HabitList.Order.BY_NAME_ASC
            }
        }
        val child = parent.getFiltered(HabitMatcher())
        child.primaryOrder = HabitList.Order.BY_NAME_ASC
        enter(a, Entry.YES_MANUAL)
        parent.onEntriesChanged()
        assertEquals(listOf(b, a), parent.toList())
        assertEquals(listOf(b, a), child.toList())
    }

    @Test
    fun numericalStatusOrderRespondsToQuantityWhileCompletionIsUnchanged() {
        val parent = MemoryHabitList()
        val a = habit("A", 0).apply { type = HabitType.NUMERICAL; targetValue = 10.0 }
        val b = habit("B", 1).apply { type = HabitType.NUMERICAL; targetValue = 10.0 }
        parent.add(a); parent.add(b)
        parent.primaryOrder = HabitList.Order.BY_STATUS_DESC
        val child = parent.getFiltered(HabitMatcher(isCompletedAllowed = false))
        enter(a, 2000); enter(b, 1000)
        parent.onEntriesChanged()
        assertEquals(false, a.isCompletedToday())
        assertEquals(false, b.isCompletedToday())
        assertEquals(listOf(a, b), parent.toList())
        enter(b, 3000)
        parent.onEntriesChanged()
        assertEquals(false, a.isCompletedToday())
        assertEquals(false, b.isCompletedToday())
        assertEquals(listOf(b, a), parent.toList())
        assertEquals(listOf(b, a), child.toList())
    }

    @Test
    fun historicalEntryEditUpdatesTodaysScoreOrderWithoutChangingTodaysEntry() {
        val parent = MemoryHabitList()
        val a = habit("A", 0)
        val b = habit("B", 1)
        parent.add(a); parent.add(b)
        parent.primaryOrder = HabitList.Order.BY_SCORE_ASC
        val child = parent.getFiltered(HabitMatcher())
        val today = getToday()
        val before = b.scores[today].value
        b.originalEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
        b.recompute()
        parent.onEntriesChanged()
        assertEquals(Entry.UNKNOWN, b.originalEntries.get(today).value)
        kotlin.test.assertTrue(b.scores[today].value > before)
        // Existing SCORE_ASC semantics put the higher score first.
        assertEquals(listOf(b, a), parent.toList())
        assertEquals(listOf(b, a), child.toList())
    }

    @Test
    fun detachedChildrenAndDistinctDatasetInstancesRemainIsolated() {
        val old = MemoryHabitList()
        val oldHabit = habit("Old", 0)
        old.add(oldHabit)
        val detached = old.getFiltered(HabitMatcher(isCompletedAllowed = false)) as MemoryHabitList
        detached.detachFromParent()
        val active = MemoryHabitList()
        val activeHabit = habit("New", 0).apply { uuid = oldHabit.uuid }
        active.add(activeHabit)
        val child = active.getFiltered(HabitMatcher(isCompletedAllowed = false))
        enter(oldHabit, Entry.YES_MANUAL)
        old.onEntriesChanged()
        assertSame(oldHabit, detached.single())
        assertSame(activeHabit, child.single())
        enter(activeHabit, Entry.YES_MANUAL)
        active.onEntriesChanged()
        assertEquals(emptyList(), child.toList())
        assertSame(oldHabit, detached.single())
    }
}
