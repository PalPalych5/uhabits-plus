package org.isoron.uhabits.core.models

import org.isoron.platform.time.getToday
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoricalGoalsTest : BaseUnitTest() {

    @BeforeTest
    override fun setUp() {
        super.setUp()
    }

    @Test
    fun testUnitChangesByDate() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = 15.0
        h.unit = "ир"
        h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2015, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "ир"),
            HabitGoal(LocalDate(2015, 1, 10), Frequency.DAILY, NumericalHabitType.AT_LEAST, 20.0, "мин")
        )
        h.recompute()

        assertEquals("ир", h.goalAt(LocalDate(2015, 1, 1)).unit)
        assertEquals("ир", h.goalAt(LocalDate(2015, 1, 5)).unit)
        assertEquals("мин", h.goalAt(LocalDate(2015, 1, 10)).unit)
        assertEquals("мин", h.goalAt(LocalDate(2015, 1, 12)).unit)
    }

    @Test
    fun testTargetChangesByDateAtLeast() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = 15.0
        h.unit = "мин"
        h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2015, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "мин"),
            HabitGoal(LocalDate(2015, 1, 10), Frequency.DAILY, NumericalHabitType.AT_LEAST, 20.0, "мин")
        )

        h.originalEntries.add(Entry(LocalDate(2015, 1, 5), 16_000))  // 16.0 units
        h.originalEntries.add(Entry(LocalDate(2015, 1, 12), 16_000)) // 16.0 units
        h.recompute()

        // For 2015-01-05: target is 15.0, value is 16.0. Should be completed.
        assertTrue(h.isCompletedOn(LocalDate(2015, 1, 5)))

        // For 2015-01-12: target is 20.0, value is 16.0. Should not be completed.
        assertFalse(h.isCompletedOn(LocalDate(2015, 1, 12)))
    }

    @Test
    fun testTargetChangesByDateAtMost() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_MOST
        h.targetValue = 10.0
        h.unit = "мин"
        h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2015, 1, 1), Frequency.DAILY, NumericalHabitType.AT_MOST, 10.0, "мин"),
            HabitGoal(LocalDate(2015, 1, 10), Frequency.DAILY, NumericalHabitType.AT_MOST, 5.0, "мин")
        )

        h.originalEntries.add(Entry(LocalDate(2015, 1, 5), 8_000))  // 8.0 units
        h.originalEntries.add(Entry(LocalDate(2015, 1, 12), 8_000)) // 8.0 units
        h.recompute()

        // For 2015-01-05: target is <= 10.0, value is 8.0. Should be completed.
        assertTrue(h.isCompletedOn(LocalDate(2015, 1, 5)))

        // For 2015-01-12: target is <= 5.0, value is 8.0. Should not be completed.
        assertFalse(h.isCompletedOn(LocalDate(2015, 1, 12)))
    }

    @Test
    fun testFrequencyChangesByDate() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_MOST
        h.targetValue = 10.0
        h.unit = "мин"
        h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2015, 1, 1), Frequency.DAILY, NumericalHabitType.AT_MOST, 10.0, "мин"),
            // Weekly limit of 20.0 starting 2015-01-20
            HabitGoal(LocalDate(2015, 1, 20), Frequency.WEEKLY, NumericalHabitType.AT_MOST, 20.0, "мин")
        )

        // Add entries for a week under weekly goal (e.g. Wednesday 2015-01-21, Thursday 2015-01-22, Sunday 2015-01-25)
        h.originalEntries.add(Entry(LocalDate(2015, 1, 21), 15_000)) // 15.0 мин
        h.originalEntries.add(Entry(LocalDate(2015, 1, 22), 10_000)) // 10.0 мин
        h.originalEntries.add(Entry(LocalDate(2015, 1, 25), 5_000))  // 5.0 мин
        h.recompute()

        // On 2015-01-25: under weekly limit of 20.0. The sum of the week is 15 + 10 + 5 = 30.0 мин.
        // Since 30.0 > 20.0 (the target limit is AT_MOST 20.0), it should be incomplete.
        assertFalse(h.isCompletedOn(LocalDate(2015, 1, 25)))

        // If we remove the other entries and only have 2015-01-25: 5.0 мин (total 5.0 <= 20.0)
        val h2 = modelFactory.buildHabit()
        h2.type = HabitType.NUMERICAL
        h2.targetType = NumericalHabitType.AT_MOST
        h2.targetValue = 10.0
        h2.unit = "мин"
        h2.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2015, 1, 1), Frequency.DAILY, NumericalHabitType.AT_MOST, 10.0, "мин"),
            HabitGoal(LocalDate(2015, 1, 20), Frequency.WEEKLY, NumericalHabitType.AT_MOST, 20.0, "мин")
        )
        h2.originalEntries.add(Entry(LocalDate(2015, 1, 25), 5_000))
        h2.recompute()

        assertTrue(h2.isCompletedOn(LocalDate(2015, 1, 25)))
    }

    @Test
    fun testGoalCacheInvalidationAndRecompute() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = 15.0
        h.unit = "мин"
        h.recompute()

        // Verify default single goal is initialized
        assertEquals(1, h.normalizedGoalHistory().size)
        assertEquals("мин", h.goalAt(LocalDate(2015, 1, 1)).unit)

        // Update the goal and trigger recompute
        h.goalHistory.add(HabitGoal(LocalDate(2015, 1, 23), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "ир"))
        h.recompute()

        // Verify normalization de-duped and sorted history correctly
        val history = h.normalizedGoalHistory()
        assertEquals(2, history.size)
        assertEquals("мин", h.goalAt(LocalDate(2015, 1, 22)).unit)
        assertEquals("ир", h.goalAt(LocalDate(2015, 1, 23)).unit)
    }
}
