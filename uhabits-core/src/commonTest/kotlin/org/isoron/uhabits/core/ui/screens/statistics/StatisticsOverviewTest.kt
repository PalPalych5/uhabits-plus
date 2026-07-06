/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatisticsOverviewTest : BaseUnitTest() {
    private lateinit var habit: Habit

    @BeforeTest
    override fun setUp() {
        super.setUp()
        habit = fixtures.createShortHabit()
    }

    @Test
    fun testRangeOverviewAggregation() {
        val today = getToday()
        // Mark habit completed today
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit.recompute()

        // Test single day range
        val stateSingle = StatisticsOverviewStateBuilder.build(listOf(habit), today, today)
        assertEquals(1, stateSingle.completedCount)
        assertEquals(1, stateSingle.totalCount)

        // Test multiple days range
        val yesterday = today.minus(1)
        val stateRange = StatisticsOverviewStateBuilder.build(listOf(habit), yesterday, today)
        // Yesterday is UNKNOWN/NO, not completed
        assertEquals(1, stateRange.completedCount)
        assertEquals(2, stateRange.totalCount)
    }
}
