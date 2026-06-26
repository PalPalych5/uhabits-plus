package org.isoron.uhabits.core.models

import org.isoron.platform.time.getToday
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntrySemanticsTest : BaseUnitTest() {

    @BeforeTest
    override fun setUp() {
        super.setUp()
    }

    @Test
    fun testNumericEntrySemantics() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = 15.0
        h.unit = "мин"
        h.recompute()

        val today = getToday()

        // 1. Verify x1000 scaling: 15_000 represents 15.0
        h.originalEntries.add(Entry(today, 15_000, "15 minutes of work"))
        h.recompute()

        val computedToday = h.computedEntries.get(today)
        assertEquals(15_000, computedToday.value)
        assertEquals("15 minutes of work", computedToday.notes)
        assertTrue(h.isCompletedToday())

        // 2. Distinction between 0.0 and skip/unknown
        // value = 0 (manual zero)
        h.originalEntries.add(Entry(today.minus(1), 0, "Zero entry"))
        h.recompute()
        assertEquals(0, h.computedEntries.get(today.minus(1)).value)
        assertFalse(h.isCompletedOn(today.minus(1)))

        // value = SKIP (3)
        h.originalEntries.add(Entry(today.minus(2), Entry.SKIP, "Skipped day"))
        h.recompute()
        assertEquals(Entry.SKIP, h.computedEntries.get(today.minus(2)).value)
        assertFalse(h.isCompletedOn(today.minus(2)))

        // value = UNKNOWN (-1)
        assertEquals(Entry.UNKNOWN, h.computedEntries.get(today.minus(3)).value)
        assertFalse(h.isCompletedOn(today.minus(3)))
    }

    @Test
    fun testBooleanEntrySemantics() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.YES_NO
        h.frequency = Frequency(1, 3) // Every 3 days
        h.recompute()

        val today = getToday()

        // 1. Manually completed on today.minus(2) and a newer entry today to allow auto-fill to propagate
        h.originalEntries.add(Entry(today.minus(2), Entry.YES_MANUAL, "Manual yes"))
        h.originalEntries.add(Entry(today, Entry.UNKNOWN))
        h.recompute()

        assertEquals(Entry.YES_MANUAL, h.computedEntries.get(today.minus(2)).value)
        assertEquals("Manual yes", h.computedEntries.get(today.minus(2)).notes)

        // 2. Auto-completion (YES_AUTO)
        // Frequency is 1/3, manual completion on today.minus(2) creates interval [today-2, today].
        // So today-1 and today should be filled with YES_AUTO.
        assertEquals(Entry.YES_AUTO, h.computedEntries.get(today.minus(1)).value)
        assertEquals(Entry.YES_AUTO, h.computedEntries.get(today).value)

        // 3. Manually not completed (NO) is overridden by YES_AUTO if inside an active completion interval
        h.originalEntries.add(Entry(today.minus(1), Entry.NO, "Manual no"))
        h.recompute()
        assertEquals(Entry.YES_AUTO, h.computedEntries.get(today.minus(1)).value)
        assertTrue(h.isCompletedOn(today.minus(1)))
    }

    @Test
    fun testRecomputeBounds() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.YES_NO
        h.originalEntries.add(Entry(getToday().minus(5), Entry.YES_MANUAL))
        h.recompute()

        // Verify initial streak and score are computed
        assertTrue(h.scores[getToday().minus(5)].value > 0.0)

        // Soft reset limits start date
        h.statisticsStartDate = getToday().minus(2)
        h.recompute()

        // Verify that completion is ignored for dates older than statisticsStartDate
        assertFalse(h.isCompletedOn(getToday().minus(5)))

        // Clear statisticsStartDate (re-add/full coverage)
        h.statisticsStartDate = null
        h.recompute()
        assertTrue(h.isCompletedOn(getToday().minus(5)))
    }
}
