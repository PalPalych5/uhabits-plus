/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.isoron.uhabits.core.ui.screens.habits.show.views

import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.ui.views.LightTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreakCartPresenterTest : BaseUnitTest() {
    @Test
    fun testBuildState_excludesLatestFromBestStreaks() {
        val habit = fixtures.createLongHabit()
        val state = StreakCartPresenter.buildState(habit, LightTheme())

        assertFalse(state.bestStreaks.contains(state.latestStreak))
        assertEquals(
            habit.streaks.getBest(11).filterNot { it == state.latestStreak }.take(10),
            state.bestStreaks
        )
    }

    @Test
    fun testBuildState_withSingleStreak() {
        val habit = fixtures.createLongHabit()
        val today = getToday()
        habit.originalEntries.clear()
        habit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        habit.recompute()

        val state = StreakCartPresenter.buildState(habit, LightTheme())

        assertEquals(habit.streaks.getLatest(), state.latestStreak)
        assertTrue(state.bestStreaks.isEmpty())
    }

    @Test
    fun testBuildState_withoutStreaks() {
        val habit = fixtures.createLongHabit()
        habit.originalEntries.clear()
        habit.recompute()

        val state = StreakCartPresenter.buildState(habit, LightTheme())

        assertNull(state.latestStreak)
        assertTrue(state.bestStreaks.isEmpty())
    }
}
