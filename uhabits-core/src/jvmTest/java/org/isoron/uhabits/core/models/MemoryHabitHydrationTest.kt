package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class MemoryHabitHydrationTest : BaseUnitTest() {
    private class CountingList : MemoryHabitList() {
        var sorts = 0
        var sortedVisits = 0
        override fun resort() {
            sorts++
            sortedVisits += size()
            super.resort()
        }
    }

    private fun list() = CountingList().apply {
        primaryOrder = HabitList.Order.BY_NAME_ASC
        sorts = 0
        sortedVisits = 0
    }

    private fun habit(name: String, id: Long? = null) = modelFactory.buildHabit().apply {
        this.name = name
        this.id = id
    }

    private fun HabitList.names() = map { it.name }

    @Test fun unobservedHydrationSortsOnceAndPreservesReferenceOrderAndMetadata() {
        val candidate = list()
        val reference = list()
        val date = LocalDate(2014, 4, 3)
        candidate.globalStatisticsStartDate = date
        reference.globalStatisticsStartDate = date
        val input = listOf(habit("Zulu", 20), habit("Alpha", 10), habit("Alpha", 30))
        input.forEach { reference.add(it) }
        candidate.addAllForHydration(input.asSequence())
        assertEquals(reference.toList(), candidate.toList())
        assertEquals(listOf(10L, 30L, 20L), candidate.map { it.id })
        assertEquals(1, candidate.sorts)
        assertEquals(3, candidate.sortedVisits)
        assertEquals(3, reference.sorts)
        assertEquals(6, reference.sortedVisits)
        input.forEach { assertSame(it, candidate.getById(it.id!!)); assertEquals(date, it.globalStatisticsStartDate) }
    }

    @Test fun emptyHydrationDoesNotSortOrNotify() {
        val candidate = list()
        candidate.addAllForHydration(emptySequence())
        assertEquals(0, candidate.sorts)
        assertEquals(0, candidate.size())
    }

    @Test fun lazyDatabaseWorkDoesNotHoldMemoryListMonitor() {
        for (observed in listOf(false, true)) {
            val candidate = list()
            val child = if (observed) candidate.getFiltered(HabitMatcher()) as MemoryHabitList else null
            try {
                candidate.addAllForHydration(sequence {
                    assertFalse(Thread.holdsLock(candidate))
                    yield(habit("Zulu", 2))
                    assertFalse(Thread.holdsLock(candidate))
                    yield(habit("Alpha", 1))
                })
                assertEquals(listOf("Alpha", "Zulu"), candidate.names())
            } finally { child?.detachFromParent() }
        }
    }

    @Test fun existingFilteredChildObservesEverySortedPrefix() {
        val candidate = list()
        val child = candidate.getFiltered(HabitMatcher()) as MemoryHabitList
        val prefixes = mutableListOf<List<String>>()
        child.observable.addListener { prefixes.add(child.names()) }
        try {
            candidate.addAllForHydration(sequenceOf(habit("Zulu", 3), habit("Alpha", 1), habit("Middle", 2)))
            assertEquals(listOf(listOf("Zulu"), listOf("Alpha", "Zulu"), listOf("Alpha", "Middle", "Zulu")), prefixes)
            assertEquals(candidate.names(), child.names())
            assertEquals(3, candidate.sorts)
        } finally {
            child.detachFromParent()
        }
    }

    @Test fun duplicateObjectAndDuplicateIdKeepOriginalValidation() {
        val same = habit("Same", 7)
        val duplicateObject = list()
        val repeated = assertFailsWith<IllegalArgumentException> {
            duplicateObject.addAllForHydration(sequenceOf(same, same))
        }
        assertEquals("habit already added", repeated.message)
        assertEquals(listOf(same), duplicateObject.toList())
        val duplicateId = list()
        val first = habit("Zulu", 4)
        val repeatedId = assertFailsWith<RuntimeException> {
            duplicateId.addAllForHydration(sequenceOf(first, habit("Different", 4)))
        }
        assertEquals("duplicate id", repeatedId.message)
        assertSame(first, duplicateId.getById(4))
        duplicateId.add(habit("Alpha", 5))
        assertEquals(listOf("Alpha", "Zulu"), duplicateId.names())
    }

    @Test fun failedSequenceRetainsSortedPartialListAndOriginalFailure() {
        val candidate = list()
        val failure = IllegalStateException("synthetic read failure")
        val thrown = assertFailsWith<IllegalStateException> {
            candidate.addAllForHydration(sequence {
                yield(habit("Zulu", 2))
                yield(habit("Alpha", 1))
                throw failure
            })
        }
        assertSame(failure, thrown)
        assertEquals(listOf("Alpha", "Zulu"), candidate.names())
        assertEquals(1, candidate.sorts)
        candidate.add(habit("Middle", 3))
        assertEquals(listOf("Alpha", "Middle", "Zulu"), candidate.names())
        assertEquals(2, candidate.sorts)
    }

    @Test fun filteredListRejectsHydrationBeforeConsumingSequence() {
        val parent = list()
        val child = parent.getFiltered(HabitMatcher()) as MemoryHabitList
        var consumed = false
        try {
            assertFailsWith<IllegalStateException> {
                child.addAllForHydration(sequence { consumed = true; yield(habit("Never", 1)) })
            }
            assertFalse(consumed)
        } finally {
            child.detachFromParent()
        }
    }

    @Test fun nullIdsAreAssignedExactlyAsOrdinaryAdd() {
        val candidate = list()
        val zulu = habit("Zulu")
        val alpha = habit("Alpha")
        candidate.addAllForHydration(sequenceOf(zulu, alpha))
        assertEquals(0L, zulu.id)
        assertEquals(1L, alpha.id)
        candidate.add(habit("Middle"))
        assertEquals(listOf(1L, 2L, 0L), candidate.map { it.id })
    }
    @Test fun nonemptyUnobservedListFallsBackToSortedIncrementalAdds() {
        val candidate = list()
        candidate.add(habit("Middle", 3))
        candidate.sorts = 0
        candidate.addAllForHydration(sequenceOf(habit("Zulu", 2), habit("Alpha", 1)))
        assertEquals(listOf("Alpha", "Middle", "Zulu"), candidate.names())
        assertEquals(2, candidate.sorts)
    }

    @Test fun completionAfterDeferredHydrationUpdatesNestedFiltersAndReentrantListeners() {
        val candidate = list()
        val a = habit("Alpha", 1)
        val b = habit("Zulu", 2)
        candidate.addAllForHydration(sequenceOf(b, a))
        val child = candidate.getFiltered(HabitMatcher(isCompletedAllowed = false))
        val descendant = child.getFiltered(HabitMatcher())
        var reentered = false
        candidate.observable.addListener {
            if (!reentered) {
                reentered = true
                b.originalEntries.add(Entry(org.isoron.platform.time.getToday(), Entry.YES_MANUAL))
                b.recompute()
                candidate.onEntriesChanged()
            }
        }
        a.originalEntries.add(Entry(org.isoron.platform.time.getToday(), Entry.YES_MANUAL))
        a.recompute()
        candidate.onEntriesChanged()
        assertEquals(emptyList(), child.toList())
        assertEquals(emptyList(), descendant.toList())
        a.originalEntries.add(Entry(org.isoron.platform.time.getToday(), Entry.UNKNOWN))
        a.recompute()
        candidate.onEntriesChanged()
        assertEquals(listOf(a), child.toList())
        assertEquals(listOf(a), descendant.toList())
    }}
