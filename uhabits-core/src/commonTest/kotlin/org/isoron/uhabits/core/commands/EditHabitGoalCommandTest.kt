/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitNotFoundException
import org.isoron.uhabits.core.models.NumericalHabitType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditHabitGoalCommandTest : BaseUnitTest() {

    private val baseDate = LocalDate(2026, 1, 1)

    @BeforeTest
    override fun setUp() {
        super.setUp()
        setToday(baseDate)
    }

    @Test
    fun testTransitionDailyToWeeklyWithFromDateScope() {
        // Initial goal: Daily 60 minutes
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST).apply {
            targetValue = 60.0
            frequency = Frequency.DAILY
            unit = "min"
        }
        habitList.add(habit)
        val habitId = habit.id!!

        val transitionDate = LocalDate(2026, 6, 1)

        // Modified habit: Weekly 180 minutes
        val modified = modelFactory.buildHabit().apply {
            copyFrom(habit)
            targetValue = 180.0
            frequency = Frequency(1, 7)
            unit = "min"
        }

        commandRunner.run(
            EditHabitGoalCommand(
                habitList = habitList,
                habitId = habitId,
                modified = modified,
                applyScope = GoalApplyScope.FROM_DATE,
                effectiveDate = transitionDate
            )
        )

        val updated = habitList.getById(habitId)!!

        // Dates before transition should return initial goal (Daily 60m)
        val beforeGoal = updated.goalAt(LocalDate(2026, 5, 31))
        assertEquals(Frequency.DAILY, beforeGoal.frequency)
        assertEquals(60.0, beforeGoal.targetValue)
        assertEquals("min", beforeGoal.unit)

        // Date of transition should return new goal (Weekly 180m)
        val onTransitionGoal = updated.goalAt(transitionDate)
        assertEquals(Frequency(1, 7), onTransitionGoal.frequency)
        assertEquals(180.0, onTransitionGoal.targetValue)

        // Date after transition should return new goal (Weekly 180m)
        val afterGoal = updated.goalAt(LocalDate(2026, 6, 15))
        assertEquals(Frequency(1, 7), afterGoal.frequency)
        assertEquals(180.0, afterGoal.targetValue)
    }

    @Test
    fun testSequentialMultipleTransitionsWithFromDateScope() {
        // Initial goal: Daily 30m starting 2026-01-01
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST).apply {
            targetValue = 30.0
            frequency = Frequency.DAILY
            unit = "min"
        }
        habitList.add(habit)
        val habitId = habit.id!!

        // Transition 1 on 2026-04-01: Daily 60m
        val date1 = LocalDate(2026, 4, 1)
        val mod1 = modelFactory.buildHabit().apply {
            copyFrom(habit)
            targetValue = 60.0
            frequency = Frequency.DAILY
        }
        commandRunner.run(EditHabitGoalCommand(habitList, habitId, mod1, GoalApplyScope.FROM_DATE, date1))

        // Transition 2 on 2026-08-01: Weekly 180m
        val date2 = LocalDate(2026, 8, 1)
        val mod2 = modelFactory.buildHabit().apply {
            copyFrom(habit)
            targetValue = 180.0
            frequency = Frequency(1, 7)
        }
        commandRunner.run(EditHabitGoalCommand(habitList, habitId, mod2, GoalApplyScope.FROM_DATE, date2))

        val updated = habitList.getById(habitId)!!

        // Verify Epoch 1: Before 2026-04-01 -> Daily 30m
        assertEquals(30.0, updated.goalAt(LocalDate(2026, 1, 15)).targetValue)
        assertEquals(Frequency.DAILY, updated.goalAt(LocalDate(2026, 3, 31)).frequency)

        // Verify Epoch 2: 2026-04-01 .. 2026-07-31 -> Daily 60m
        assertEquals(60.0, updated.goalAt(date1).targetValue)
        assertEquals(60.0, updated.goalAt(LocalDate(2026, 5, 20)).targetValue)
        assertEquals(Frequency.DAILY, updated.goalAt(LocalDate(2026, 7, 31)).frequency)

        // Verify Epoch 3: 2026-08-01 and onwards -> Weekly 180m
        assertEquals(180.0, updated.goalAt(date2).targetValue)
        assertEquals(Frequency(1, 7), updated.goalAt(date2).frequency)
        assertEquals(180.0, updated.goalAt(LocalDate(2026, 12, 31)).targetValue)
        assertEquals(Frequency(1, 7), updated.goalAt(LocalDate(2027, 2, 1)).frequency)
    }

    @Test
    fun testEntireHistoryScopeReplacesAllHistoricalGoals() {
        // Initial goal: Daily 30m
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST).apply {
            targetValue = 30.0
            frequency = Frequency.DAILY
        }
        habitList.add(habit)
        val habitId = habit.id!!

        // Add an intermediate goal
        val mod1 = modelFactory.buildHabit().apply {
            copyFrom(habit)
            targetValue = 60.0
            frequency = Frequency.DAILY
        }
        commandRunner.run(EditHabitGoalCommand(habitList, habitId, mod1, GoalApplyScope.FROM_DATE, LocalDate(2026, 4, 1)))

        // Now replace ENTIRE_HISTORY with Weekly 210m effective today
        val modEntire = modelFactory.buildHabit().apply {
            copyFrom(habit)
            targetValue = 210.0
            frequency = Frequency(1, 7)
        }
        commandRunner.run(
            EditHabitGoalCommand(
                habitList,
                habitId,
                modEntire,
                GoalApplyScope.ENTIRE_HISTORY,
                LocalDate(2026, 10, 1)
            )
        )

        val updated = habitList.getById(habitId)!!

        // Historical dates prior to 2026-10-01 should also reflect the new goal
        assertEquals(210.0, updated.goalAt(LocalDate(2026, 1, 1)).targetValue)
        assertEquals(Frequency(1, 7), updated.goalAt(LocalDate(2026, 1, 1)).frequency)
        assertEquals(210.0, updated.goalAt(LocalDate(2026, 5, 1)).targetValue)
        assertEquals(210.0, updated.goalAt(LocalDate(2026, 10, 1)).targetValue)
        assertEquals(210.0, updated.goalAt(LocalDate(2027, 1, 1)).targetValue)
    }

    @Test
    fun testObservableNotificationOnGoalEdit() {
        val habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST).apply {
            targetValue = 50.0
            frequency = Frequency.DAILY
        }
        habitList.add(habit)

        var notified = false
        habit.observable.addListener { notified = true }

        val modified = modelFactory.buildHabit().apply {
            copyFrom(habit)
            targetValue = 100.0
        }
        commandRunner.run(EditHabitGoalCommand(habitList, habit.id!!, modified, GoalApplyScope.FROM_DATE, baseDate))

        assertTrue(notified, "Habit observable must notify listeners when goal changes")
    }

    @Test
    fun testThrowsOnNonExistentHabit() {
        val habit = fixtures.createEmptyHabit()
        val modified = modelFactory.buildHabit().apply { copyFrom(habit) }

        val cmd = EditHabitGoalCommand(
            habitList = habitList,
            habitId = 99999L,
            modified = modified,
            applyScope = GoalApplyScope.FROM_DATE,
            effectiveDate = baseDate
        )

        assertFailsWith<HabitNotFoundException> {
            cmd.run()
        }
    }
}
