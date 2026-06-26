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
 * Loop Habit Tracker is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.ui.screens.habits.today

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodayScreenStateBuilderTest : BaseUnitTest() {
    private val today
        get() = getToday()

    @Test
    fun buildsBooleanStatuses() {
        val completed = booleanHabit("Checked", Entry.YES_MANUAL)
        val missed = booleanHabit("Missed", Entry.NO, dayTier = DayTier.MINIMUM)
        val unknown = booleanHabit("Unknown", Entry.UNKNOWN, dayTier = DayTier.MINIMUM)
        val skipped = booleanHabit("Skipped", Entry.SKIP)
        habitList.add(completed)
        habitList.add(missed)
        habitList.add(unknown)
        habitList.add(skipped)

        val state = TodayScreenStateBuilder.build(habitList, today)

        assertEquals(1, state.completedCount)
        assertEquals(3, state.totalCount)
        val statusesByName = state.sections.single().items.associate { it.name to it.status }
        assertEquals(TodayHabitStatus.COMPLETED, statusesByName["Checked"])
        assertEquals(TodayHabitStatus.REMAINING, statusesByName["Missed"])
        assertEquals(TodayHabitStatus.UNKNOWN, statusesByName["Unknown"])
        assertEquals(TodayHabitStatus.SKIPPED, statusesByName["Skipped"])
        assertEquals(listOf("Missed", "Unknown"), state.remaining.map { it.name })
    }

    @Test
    fun buildsNumericalAtLeastProgressUsingScaledValues() {
        habitList.add(
            numericalHabit(
                "Reading",
                45_000,
                targetValue = 30.0,
                unit = "min",
                notes = "Focused session"
            )
        )

        val item = TodayScreenStateBuilder.build(habitList, today).sections.single().items.single()

        assertEquals(TodayHabitStatus.COMPLETED, item.status)
        assertEquals(45.0, item.currentValue)
        assertEquals(30.0, item.targetValue)
        assertEquals("Focused session", item.notes)
        assertEquals(45.0, TodayScreenStateBuilder.build(habitList, today).focusMinutes)
    }

    @Test
    fun buildsNumericalSkipWithoutScaling() {
        habitList.add(numericalHabit("Rest day", Entry.SKIP, targetValue = 30.0, unit = "min"))

        val state = TodayScreenStateBuilder.build(habitList, today)
        val item = state.sections.single().items.single()

        assertEquals(TodayHabitStatus.SKIPPED, item.status)
        assertEquals(null, item.currentValue)
        assertEquals(0.0, state.focusMinutes)
        assertEquals(emptyList(), state.remaining)
    }

    @Test
    fun buildsNumericalAtMostStatuses() {
        // 1. Daily AT_MOST within limit (target: <= 30 min/day, today: 20 min)
        val dailyWithin = numericalHabit(
            name = "Daily within",
            value = 20_000,
            targetValue = 30.0,
            unit = "min",
            targetType = NumericalHabitType.AT_MOST
        )
        habitList.add(dailyWithin)

        // 2. Daily AT_MOST exceeded (target: <= 30 min/day, today: 45 min)
        val dailyExceeded = numericalHabit(
            name = "Daily exceeded",
            value = 45_000,
            targetValue = 30.0,
            unit = "min",
            targetType = NumericalHabitType.AT_MOST
        )
        habitList.add(dailyExceeded)

        // 3. Weekly AT_MOST within limit (target: <= 2 times/week, Mon: 1, Thu: 1)
        val weeklyWithin = Habit(
            name = "Weekly within",
            type = HabitType.NUMERICAL,
            targetValue = 2.0,
            targetType = NumericalHabitType.AT_MOST,
            frequency = Frequency(1, 7),
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, 1000))
            originalEntries.add(Entry(today.minus(3), 1000))
            recompute()
        }
        habitList.add(weeklyWithin)

        // 4. Weekly AT_MOST exceeded (target: <= 2 times/week, Mon: 1, Tue: 1, Wed: 1)
        val weeklyExceeded = Habit(
            name = "Weekly exceeded",
            type = HabitType.NUMERICAL,
            targetValue = 2.0,
            targetType = NumericalHabitType.AT_MOST,
            frequency = Frequency(1, 7),
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, 1000))
            originalEntries.add(Entry(today.minus(1), 1000))
            originalEntries.add(Entry(today.minus(2), 1000))
            recompute()
        }
        habitList.add(weeklyExceeded)

        // 5. AT_LEAST remains unchanged (target: >= 30 min/day, today: 20 min -> REMAINING; today: 45 min -> COMPLETED)
        val atLeastRemaining = numericalHabit(
            name = "At least remaining",
            value = 20_000,
            targetValue = 30.0,
            unit = "min",
            targetType = NumericalHabitType.AT_LEAST
        )
        val atLeastDone = numericalHabit(
            name = "At least done",
            value = 45_000,
            targetValue = 30.0,
            unit = "min",
            targetType = NumericalHabitType.AT_LEAST
        )
        habitList.add(atLeastRemaining)
        habitList.add(atLeastDone)

        // 6. 30-day AT_MOST within limit (target: <= 2.0 per 30 days, actual: 2.0)
        val monthlyWithin = Habit(
            name = "Monthly within",
            type = HabitType.NUMERICAL,
            targetValue = 2.0,
            targetType = NumericalHabitType.AT_MOST,
            frequency = Frequency(1, 30),
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, 1000))
            originalEntries.add(Entry(today.minus(5), 1000))
            recompute()
        }
        habitList.add(monthlyWithin)

        // 7. 30-day AT_MOST exceeded limit (target: <= 2.0 per 30 days, actual: 3.0)
        val monthlyExceeded = Habit(
            name = "Monthly exceeded",
            type = HabitType.NUMERICAL,
            targetValue = 2.0,
            targetType = NumericalHabitType.AT_MOST,
            frequency = Frequency(1, 30),
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, 1000))
            originalEntries.add(Entry(today.minus(5), 1000))
            originalEntries.add(Entry(today.minus(10), 1000))
            recompute()
        }
        habitList.add(monthlyExceeded)

        val items = TodayScreenStateBuilder.build(habitList, today).sections.single().items
        val statusesByName = items.associate { it.name to it.status }
        val itemMap = items.associateBy { it.name }

        // Assert 1. Daily within
        assertEquals(TodayHabitStatus.COMPLETED, statusesByName["Daily within"])
        assertEquals(20.0, itemMap["Daily within"]?.periodProgressActual)
        assertEquals(30.0, itemMap["Daily within"]?.periodProgressTarget)

        // Assert 2. Daily exceeded
        assertEquals(TodayHabitStatus.EXCEEDED, statusesByName["Daily exceeded"])

        // Assert 3. Weekly within
        assertEquals(TodayHabitStatus.COMPLETED, statusesByName["Weekly within"])
        assertEquals(2.0, itemMap["Weekly within"]?.periodProgressActual)
        assertEquals(2.0, itemMap["Weekly within"]?.periodProgressTarget)

        // Assert 4. Weekly exceeded
        assertEquals(TodayHabitStatus.EXCEEDED, statusesByName["Weekly exceeded"])
        assertEquals(3.0, itemMap["Weekly exceeded"]?.periodProgressActual)
        assertEquals(2.0, itemMap["Weekly exceeded"]?.periodProgressTarget)

        // Assert 5. AT_LEAST
        assertEquals(TodayHabitStatus.REMAINING, statusesByName["At least remaining"])
        assertEquals(TodayHabitStatus.COMPLETED, statusesByName["At least done"])

        // Assert 6. 30-day within (total == target)
        assertEquals(TodayHabitStatus.COMPLETED, statusesByName["Monthly within"])
        assertEquals(2.0, itemMap["Monthly within"]?.periodProgressActual)
        assertEquals(2.0, itemMap["Monthly within"]?.periodProgressTarget)
        assertEquals(PeriodLabel.MONTH, itemMap["Monthly within"]?.periodLabel)

        // Assert 7. 30-day exceeded (total > target)
        assertEquals(TodayHabitStatus.EXCEEDED, statusesByName["Monthly exceeded"])
        assertEquals(3.0, itemMap["Monthly exceeded"]?.periodProgressActual)
        assertEquals(2.0, itemMap["Monthly exceeded"]?.periodProgressTarget)
        assertEquals(PeriodLabel.MONTH, itemMap["Monthly exceeded"]?.periodLabel)
    }

    @Test
    fun excludesArchivedHabits() {
        habitList.add(booleanHabit("Active", Entry.YES_MANUAL))
        habitList.add(booleanHabit("Archived", Entry.YES_MANUAL).apply { isArchived = true })

        val state = TodayScreenStateBuilder.build(habitList, today)

        assertEquals(1, state.totalCount)
        assertEquals("Active", state.sections.single().items.single().name)
    }

    @Test
    fun sumsOnlyAtLeastMinuteUnitsForFocusMinutes() {
        habitList.add(numericalHabit("Reading", 30_000, targetValue = 30.0, unit = "min"))
        habitList.add(numericalHabit("Speech", 25_000, targetValue = 25.0, unit = "мин"))
        habitList.add(numericalHabit("Kegel", 3_000, targetValue = 3.0, unit = "sets"))
        habitList.add(
            numericalHabit(
                name = "Social media",
                value = 12_000,
                targetValue = 15.0,
                unit = "min",
                targetType = NumericalHabitType.AT_MOST
            )
        )

        val state = TodayScreenStateBuilder.build(habitList, today)

        assertEquals(55.0, state.focusMinutes)
    }

    @Test
    fun usesHistoricalGoalForDisplayedDate() {
        val switchDate = today
        val oldDate = today.minus(1)
        val reading = Habit(
            name = "Reading",
            type = HabitType.NUMERICAL,
            targetValue = 20.0,
            targetType = NumericalHabitType.AT_LEAST,
            unit = "min",
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2000, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "min"),
                HabitGoal(switchDate, Frequency.DAILY, NumericalHabitType.AT_LEAST, 20.0, "min")
            )
            originalEntries.add(Entry(oldDate, 15_000))
            originalEntries.add(Entry(switchDate, 15_000))
            recompute()
        }
        habitList.add(reading)

        val oldItem = TodayScreenStateBuilder.build(habitList, oldDate).sections.single().items.single()
        val newItem = TodayScreenStateBuilder.build(habitList, switchDate).sections.single().items.single()

        assertEquals(TodayHabitStatus.COMPLETED, oldItem.status)
        assertEquals(15.0, oldItem.targetValue)
        assertEquals(TodayHabitStatus.REMAINING, newItem.status)
        assertEquals(20.0, newItem.targetValue)
    }

    @Test
    fun excludesPreResetDatesFromSummariesWithoutDeletingRawEntries() {
        val oldDate = today.minus(1)
        val reading = numericalHabit("Reading", Entry.UNKNOWN, targetValue = 30.0, unit = "min").apply {
            originalEntries.add(Entry(oldDate, 30_000, "before reset"))
            statisticsStartDate = today
            recompute()
        }
        habitList.add(reading)

        val oldState = TodayScreenStateBuilder.build(habitList, oldDate)

        assertEquals(0, oldState.completedCount)
        assertEquals(0, oldState.totalCount)
        assertEquals(0.0, oldState.focusMinutes)
        assertEquals(TodayTierProgress(0, 0), oldState.minimum)
        assertTrue(oldState.sections.isEmpty())
        assertEquals(30_000, reading.originalEntries.get(oldDate).value)
        assertEquals("before reset", reading.originalEntries.get(oldDate).notes)
    }

    @Test
    fun keepsSkipAndUnknownBehaviorWithHistoricalGoalsAndSoftReset() {
        val active = Habit(
            name = "Limit",
            type = HabitType.NUMERICAL,
            targetValue = 1.0,
            targetType = NumericalHabitType.AT_MOST,
            unit = "times",
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2000, 1, 1), Frequency(1, 7), NumericalHabitType.AT_MOST, 2.0, "times"),
                HabitGoal(today, Frequency(1, 7), NumericalHabitType.AT_MOST, 1.0, "times")
            )
            originalEntries.add(Entry(today, Entry.SKIP))
            recompute()
        }
        val preResetUnknown = booleanHabit("Unknown before reset", Entry.UNKNOWN).apply {
            originalEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
            statisticsStartDate = today
            recompute()
        }
        habitList.add(active)
        habitList.add(preResetUnknown)

        val state = TodayScreenStateBuilder.build(habitList, today)
        val statuses = state.sections.single().items.associate { it.name to it.status }
        val oldState = TodayScreenStateBuilder.build(habitList, today.minus(1))

        assertEquals(TodayHabitStatus.SKIPPED, statuses["Limit"])
        assertEquals(TodayHabitStatus.UNKNOWN, statuses["Unknown before reset"])
        assertEquals(false, oldState.sections.flatMap { it.items }.any { it.name == "Unknown before reset" })
    }

    @Test
    fun usesHistoricalAtMostBundlesForWeeklyAndMonthlyPeriods() {
        val weekly = Habit(
            name = "Weekly limit",
            type = HabitType.NUMERICAL,
            targetValue = 1.0,
            targetType = NumericalHabitType.AT_MOST,
            unit = "times",
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2000, 1, 1), Frequency(1, 7), NumericalHabitType.AT_MOST, 2.0, "times"),
                HabitGoal(today.minus(6), Frequency(1, 7), NumericalHabitType.AT_MOST, 1.0, "times")
            )
            originalEntries.add(Entry(today.minus(13), 1_000))
            originalEntries.add(Entry(today.minus(11), 1_000))
            originalEntries.add(Entry(today.minus(1), 1_000))
            originalEntries.add(Entry(today, 1_000))
            recompute()
        }
        val monthly = Habit(
            name = "Monthly limit",
            type = HabitType.NUMERICAL,
            targetValue = 1.0,
            targetType = NumericalHabitType.AT_MOST,
            unit = "times",
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            val monthStart = today.startOfMonth()
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2000, 1, 1), Frequency(1, 30), NumericalHabitType.AT_MOST, 2.0, "times"),
                HabitGoal(monthStart, Frequency(1, 30), NumericalHabitType.AT_MOST, 1.0, "times")
            )
            originalEntries.add(Entry(monthStart.minus(10), 1_000))
            originalEntries.add(Entry(monthStart.minus(5), 1_000))
            originalEntries.add(Entry(monthStart.plus(1), 1_000))
            originalEntries.add(Entry(monthStart.plus(2), 1_000))
            recompute()
        }
        habitList.add(weekly)
        habitList.add(monthly)

        val oldWeekly = TodayScreenStateBuilder.build(habitList, today.minus(11)).sections.single().items.associateBy { it.name }
        val newWeekly = TodayScreenStateBuilder.build(habitList, today).sections.single().items.associateBy { it.name }
        val oldMonthly = TodayScreenStateBuilder.build(habitList, today.startOfMonth().minus(5)).sections.single().items.associateBy { it.name }
        val newMonthly = TodayScreenStateBuilder.build(habitList, today.startOfMonth().plus(2)).sections.single().items.associateBy { it.name }

        assertEquals(TodayHabitStatus.COMPLETED, oldWeekly["Weekly limit"]?.status)
        assertEquals(2.0, oldWeekly["Weekly limit"]?.periodProgressTarget)
        assertEquals(TodayHabitStatus.EXCEEDED, newWeekly["Weekly limit"]?.status)
        assertEquals(1.0, newWeekly["Weekly limit"]?.periodProgressTarget)

        assertEquals(TodayHabitStatus.COMPLETED, oldMonthly["Monthly limit"]?.status)
        assertEquals(2.0, oldMonthly["Monthly limit"]?.periodProgressTarget)
        assertEquals(TodayHabitStatus.EXCEEDED, newMonthly["Monthly limit"]?.status)
        assertEquals(1.0, newMonthly["Monthly limit"]?.periodProgressTarget)
    }

    @Test
    fun groupsHabitsByBlocksAndSortsByPosition() {
        val block1 = org.isoron.uhabits.core.models.HabitBlock(id = 1, name = "Intellect", color = PaletteColor(11), position = 2)
        val block2 = org.isoron.uhabits.core.models.HabitBlock(id = 2, name = "Limits", color = PaletteColor(0), position = 0)
        val block3 = org.isoron.uhabits.core.models.HabitBlock(id = 3, name = "Body", color = PaletteColor(7), position = 1)
        (habitList as org.isoron.uhabits.core.models.memory.MemoryHabitList).setBlocks(listOf(block1, block2, block3))

        habitList.add(booleanHabit("Intellect B", Entry.YES_MANUAL).apply { blockId = 1; position = 2 })
        habitList.add(booleanHabit("Limits", Entry.YES_MANUAL).apply { blockId = 2; position = 0 })
        habitList.add(booleanHabit("Body", Entry.NO).apply { blockId = 3; position = 0 })
        habitList.add(booleanHabit("Intellect A", Entry.NO).apply { blockId = 1; position = 1 })
        habitList.add(booleanHabit("Unassigned", Entry.NO)) // fallback to "Прочее" (position = 1000)

        val sections = TodayScreenStateBuilder.build(habitList, today).sections

        assertEquals(4, sections.size)
        assertEquals(listOf("Limits", "Body", "Intellect", "Прочее"), sections.map { it.blockName })
        assertEquals(listOf("Intellect A", "Intellect B"), sections[2].items.map { it.name })
    }

    @Test
    fun calculatesSectionAggregatesAfterGrouping() {
        val block1 = org.isoron.uhabits.core.models.HabitBlock(id = 1, name = "Intellect", color = PaletteColor(11), position = 0)
        val block2 = org.isoron.uhabits.core.models.HabitBlock(id = 2, name = "Speech", color = PaletteColor(13), position = 1)
        (habitList as org.isoron.uhabits.core.models.memory.MemoryHabitList).setBlocks(listOf(block1, block2))

        habitList.add(numericalHabit("Reading", 30_000, targetValue = 30.0, unit = "min").apply { blockId = 1 })
        habitList.add(numericalHabit("Speech", 20_000, targetValue = 25.0, unit = "мин").apply { blockId = 2 })
        habitList.add(booleanHabit("Unassigned", Entry.YES_MANUAL))

        val sectionsByName = TodayScreenStateBuilder.build(habitList, today).sections.associateBy { it.blockName }

        assertEquals(1, sectionsByName["Intellect"]?.completedCount)
        assertEquals(1, sectionsByName["Intellect"]?.totalCount)
        assertEquals(30.0, sectionsByName["Intellect"]?.focusMinutes)
        assertEquals(0, sectionsByName["Speech"]?.completedCount)
        assertEquals(1, sectionsByName["Speech"]?.totalCount)
        assertEquals(20.0, sectionsByName["Speech"]?.focusMinutes)
        assertEquals(1, sectionsByName["Прочее"]?.completedCount)
        assertEquals(1, sectionsByName["Прочее"]?.totalCount)
    }

    @Test
    fun buildsCumulativeTierProgressAndMinimumRemaining() {
        habitList.add(booleanHabit("Minimum done", Entry.YES_MANUAL, dayTier = DayTier.MINIMUM))
        habitList.add(booleanHabit("Minimum left", Entry.NO, dayTier = DayTier.MINIMUM))
        habitList.add(booleanHabit("Normal done", Entry.YES_MANUAL, dayTier = DayTier.NORMAL))
        habitList.add(booleanHabit("Ideal left", Entry.NO, dayTier = DayTier.IDEAL))
        habitList.add(booleanHabit("Optional done", Entry.YES_MANUAL, dayTier = DayTier.OPTIONAL))

        val state = TodayScreenStateBuilder.build(habitList, today)

        assertEquals(TodayTierProgress(1, 2), state.minimum)
        assertEquals(TodayTierProgress(2, 3), state.normal)
        assertEquals(TodayTierProgress(2, 4), state.ideal)
        assertEquals(listOf("Minimum left"), state.remaining.map { it.name })
    }

    @Test
    fun excludesSkippedItemsFromAllCounters() {
        habitList.add(booleanHabit("Skipped minimum", Entry.SKIP, dayTier = DayTier.MINIMUM))
        habitList.add(booleanHabit("Done normal", Entry.YES_MANUAL, dayTier = DayTier.NORMAL))

        val state = TodayScreenStateBuilder.build(habitList, today)

        assertEquals(1, state.completedCount)
        assertEquals(1, state.totalCount)
        assertEquals(TodayTierProgress(0, 0), state.minimum)
        assertEquals(TodayTierProgress(1, 1), state.normal)
        assertEquals(TodayTierProgress(1, 1), state.ideal)
        assertEquals(1, state.sections.single().totalCount)
    }

    @Test
    fun calculatesWeeklyQuotaProgress() {
        // Boolean weekly habit: 3 times a week (Frequency(3, 7))
        val weeklyBool = Habit(
            name = "Workout",
            type = HabitType.YES_NO,
            frequency = Frequency(3, 7),
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            originalEntries.add(Entry(today.minus(2), Entry.YES_MANUAL))
            recompute()
        }
        habitList.add(weeklyBool)

        // Numerical weekly habit: 2 times a week, 10 units each (Frequency(2, 7), targetValue = 10.0)
        val weeklyNum = Habit(
            name = "Reading",
            type = HabitType.NUMERICAL,
            targetValue = 10.0,
            frequency = Frequency(2, 7),
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, 5_000))
            originalEntries.add(Entry(today.minus(1), 8_000))
            recompute()
        }
        habitList.add(weeklyNum)

        val state = TodayScreenStateBuilder.build(habitList, today)
        val itemsByName = state.sections.single().items.associateBy { it.name }

        val boolItem = itemsByName["Workout"]!!
        assertEquals(true, boolItem.isWeeklyQuota)
        assertEquals(2.0, boolItem.periodProgressActual)
        assertEquals(3.0, boolItem.periodProgressTarget)

        val numItem = itemsByName["Reading"]!!
        assertEquals(true, numItem.isWeeklyQuota)
        assertEquals(13.0, numItem.periodProgressActual)
        assertEquals(20.0, numItem.periodProgressTarget)
    }

    @Test
    fun generatesMotivationsCorrectly() {
        // 1. Return after skip (comeback)
        val comebackHabit = Habit(
            name = "Comeback",
            type = HabitType.YES_NO,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            originalEntries.add(Entry(today.minus(1), Entry.SKIP))
            recompute()
        }
        habitList.add(comebackHabit)

        // 2. Minimum Completed today
        val minHabit = Habit(
            name = "Min Habit",
            type = HabitType.YES_NO,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            dayTier = DayTier.MINIMUM
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        habitList.add(minHabit)

        val state = TodayScreenStateBuilder.build(habitList, today)

        assertEquals(true, state.motivations.contains("minimum_completed"))
        assertEquals(true, state.motivations.contains("comeback|Comeback"))
    }

    @Test
    fun generatesStreakMilestoneMotivations() {
        val streakHabit = Habit(
            name = "Streak Habit",
            type = HabitType.YES_NO,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            originalEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
            originalEntries.add(Entry(today.minus(2), Entry.YES_MANUAL))
            recompute()
        }
        habitList.add(streakHabit)

        val state = TodayScreenStateBuilder.build(habitList, today)
        assertEquals(true, state.motivations.contains("streak_milestone|Streak Habit|3"))
    }

    @Test
    fun testTodayScreenStateUnitAndTargetResolution() {
        val h = numericalHabit("Meditation", 15_000, targetValue = 20.0, unit = "мин")
        habitList.add(h)

        val state = TodayScreenStateBuilder.build(habitList, today)
        val item = state.sections.single().items.single()
        assertEquals("мин", item.unit)
        assertEquals(20.0, item.targetValue)
        assertEquals(15.0, item.currentValue)
        assertEquals(TodayHabitStatus.REMAINING, item.status)
    }

    private fun booleanHabit(
        name: String,
        value: Int,
        color: PaletteColor = PaletteColor(8),
        dayTier: DayTier = DayTier.NORMAL
    ): Habit {
        return Habit(
            name = name,
            color = color,
            type = HabitType.YES_NO,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            this.dayTier = dayTier
            if (value != Entry.UNKNOWN) originalEntries.add(Entry(today, value))
            recompute()
        }
    }

    private fun numericalHabit(
        name: String,
        value: Int,
        targetValue: Double,
        unit: String,
        targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
        color: PaletteColor = PaletteColor(8),
        notes: String = ""
    ): Habit {
        return Habit(
            name = name,
            color = color,
            type = HabitType.NUMERICAL,
            targetValue = targetValue,
            targetType = targetType,
            unit = unit,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            if (value != Entry.UNKNOWN) originalEntries.add(Entry(today, value, notes))
            recompute()
        }
    }
}
