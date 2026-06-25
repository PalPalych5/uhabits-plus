/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.core.models

import org.isoron.platform.time.getToday
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HabitTest : BaseUnitTest() {

    @BeforeTest
    override fun setUp() {
        super.setUp()
    }

    @Test
    fun testUuidGeneration() {
        val uuid1 = modelFactory.buildHabit().uuid!!
        val uuid2 = modelFactory.buildHabit().uuid!!
        assertNotEquals(uuid1, uuid2)
    }

    @Test
    fun test_copyAttributes() {
        val model = modelFactory.buildHabit()
        model.isArchived = true
        model.color = PaletteColor(0)
        model.frequency = Frequency(10, 20)
        model.reminder = Reminder(8, 30, WeekdayList(1))
        val habit = modelFactory.buildHabit()
        habit.copyFrom(model)
        assertEquals(habit.isArchived, model.isArchived)
        assertEquals(model.isArchived, habit.isArchived)
        assertEquals(model.color, habit.color)
        assertEquals(model.frequency, habit.frequency)
        assertEquals(model.reminder, habit.reminder)
    }

    @Test
    fun test_hasReminder() {
        val h = modelFactory.buildHabit()
        assertEquals(false, h.hasReminder())
        h.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        assertEquals(true, h.hasReminder())
    }

    @Test
    fun test_isCompleted() {
        val h = modelFactory.buildHabit()
        assertFalse(h.isCompletedToday())
        h.originalEntries.add(Entry(getToday(), Entry.YES_MANUAL))
        h.recompute()
        assertTrue(h.isCompletedToday())
    }

    @Test
    fun test_isEntered() {
        val h = modelFactory.buildHabit()
        assertFalse(h.isEnteredToday())
        h.originalEntries.add(Entry(getToday(), Entry.NO))
        h.recompute()
        assertTrue(h.isEnteredToday())
    }

    @Test
    fun test_isCompleted_numerical() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = 100.0
        assertFalse(h.isCompletedToday())
        h.originalEntries.add(Entry(getToday(), 200000))
        h.recompute()
        assertTrue(h.isCompletedToday())
        h.originalEntries.add(Entry(getToday(), 100000))
        h.recompute()
        assertTrue(h.isCompletedToday())
        h.originalEntries.add(Entry(getToday(), 50000))
        h.recompute()
        assertFalse(h.isCompletedToday())
        h.targetType = NumericalHabitType.AT_MOST
        h.originalEntries.add(Entry(getToday(), 200000))
        h.recompute()
        assertFalse(h.isCompletedToday())
        h.originalEntries.add(Entry(getToday(), 100000))
        h.recompute()
        assertTrue(h.isCompletedToday())
        h.originalEntries.add(Entry(getToday(), 50000))
        h.recompute()
        assertTrue(h.isCompletedToday())
    }

    @Test
    fun testURI() {
        assertTrue(habitList.isEmpty)
        val h = modelFactory.buildHabit()
        habitList.add(h)
        assertEquals(0L, h.id)
        assertEquals("content://org.isoron.uhabits/habit/0", h.uriString)
    }

    @Test
    fun testHistoricalGoalKeepsOldEntriesSuccessful() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = 15.0
        h.unit = "min"
        h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2015, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "min"),
            HabitGoal(LocalDate(2015, 1, 23), Frequency.DAILY, NumericalHabitType.AT_LEAST, 20.0, "min")
        )
        h.originalEntries.add(Entry(LocalDate(2015, 1, 22), 15_000))
        h.originalEntries.add(Entry(LocalDate(2015, 1, 25), 15_000))
        h.recompute()

        assertTrue(h.isCompletedOn(LocalDate(2015, 1, 22)))
        assertFalse(h.isCompletedOn(LocalDate(2015, 1, 25)))
    }

    @Test
    fun testHistoricalGoalUnitDisplaysCorrectly() {
        val h = modelFactory.buildHabit()
        h.type = HabitType.NUMERICAL
        h.targetType = NumericalHabitType.AT_LEAST
        h.targetValue = 15.0
        h.unit = "ир"
        h.goalHistory = mutableListOf(
            HabitGoal(LocalDate(2015, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "ир"),
            HabitGoal(LocalDate(2015, 1, 23), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "мин")
        )
        h.recompute()

        assertEquals("ир", h.goalAt(LocalDate(2015, 1, 22)).unit)
        assertEquals("мин", h.goalAt(LocalDate(2015, 1, 23)).unit)
        assertEquals("мин", h.goalAt(LocalDate(2015, 1, 25)).unit)
    }

    @Test
    fun testSoftResetExcludesOlderEntriesFromCompletion() {
        val h = modelFactory.buildHabit()
        h.originalEntries.add(Entry(getToday().minus(1), Entry.YES_MANUAL))
        h.recompute()
        assertTrue(h.isCompletedOn(getToday().minus(1)))
        h.statisticsStartDate = getToday()
        h.recompute()
        assertFalse(h.isCompletedOn(getToday().minus(1)))
    }

    @Test
    fun testStatisticsEntriesUseMaxOfGlobalAndPerHabitStartDates() {
        val h = modelFactory.buildHabit()
        val threeDaysAgo = getToday().minus(3)
        val twoDaysAgo = getToday().minus(2)
        val yesterday = getToday().minus(1)
        h.originalEntries.add(Entry(threeDaysAgo, Entry.YES_MANUAL))
        h.originalEntries.add(Entry(twoDaysAgo, Entry.YES_MANUAL))
        h.originalEntries.add(Entry(yesterday, Entry.YES_MANUAL))
        h.globalStatisticsStartDate = twoDaysAgo
        h.statisticsStartDate = yesterday
        h.recompute()

        val includedDates = h.statisticsEntries(threeDaysAgo, getToday()).map { it.date }

        assertEquals(listOf(getToday(), yesterday), includedDates)
    }
}
