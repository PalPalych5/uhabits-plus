/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.activities.habits.show.views

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.isoron.uhabits.BaseViewTest
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.show.ShowHabitView
import org.isoron.uhabits.core.userTasks.TaskId
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class TimerCardViewTest : BaseViewTest() {

    private lateinit var card: TimerCardView

    @Before
    override fun setUp() {
        super.setUp()
        card = TimerCardView(targetContext)
    }

    @Test
    fun testTimerCard_rebindUpdatesHabitInstance() {
        val manager = HabitsApplication.component.timerSessionManager
        val habit1 = fixtures.createLongNumericalHabit().apply {
            unit = "min"
            targetValue = 30.0
        }
        card.setHabit(habit1, manager)
        assertSame("Initial habit must be set", habit1, card.currentHabit)

        val habit2 = fixtures.createLongNumericalHabit().apply {
            id = habit1.id
            uuid = habit1.uuid
            name = "Fresh Synced Habit"
        }
        assertNotEquals(System.identityHashCode(habit1), System.identityHashCode(habit2))

        card.updateHabit(habit2)
        assertSame("TimerCardView must retain fresh habit instance after rebind", habit2, card.currentHabit)
    }

    @Test
    fun testTimerCard_updateHabitClearsTaskId() {
        val manager = HabitsApplication.component.timerSessionManager
        card.setTask(TaskId("task-test-42"), "Task Title", manager)
        assertNull(card.currentHabit)

        val habit = fixtures.createLongNumericalHabit()
        card.updateHabit(habit)
        assertSame(habit, card.currentHabit)
    }

    @Test
    fun testShowHabitView_updateHabitPropagatesToTimerCard() {
        val view = ShowHabitView(targetContext)
        val manager = HabitsApplication.component.timerSessionManager
        val notifier = HabitsApplication.component.pomodoroCompletionNotifier
        val habit1 = fixtures.createLongNumericalHabit().apply {
            unit = "min"
            targetValue = 30.0
        }
        view.initTimer(habit1, manager, notifier, {}, {})
        assertSame(habit1, view.timerCard.currentHabit)

        val habit2 = fixtures.createLongNumericalHabit().apply {
            id = habit1.id
            uuid = habit1.uuid
            name = "Fresh Synced Habit"
        }
        view.updateHabit(habit2)
        assertSame(habit2, view.timerCard.currentHabit)
    }
}
