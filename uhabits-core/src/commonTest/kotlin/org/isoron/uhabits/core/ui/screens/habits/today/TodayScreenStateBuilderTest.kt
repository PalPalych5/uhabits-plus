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

import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.test.Test
import kotlin.test.assertEquals

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
        habitList.add(
            numericalHabit(
                name = "Social media",
                value = 12_000,
                targetValue = 15.0,
                unit = "min",
                targetType = NumericalHabitType.AT_MOST
            )
        )
        habitList.add(
            numericalHabit(
                name = "Music",
                value = 20_000,
                targetValue = 15.0,
                unit = "min",
                targetType = NumericalHabitType.AT_MOST
            )
        )
        habitList.add(
            numericalHabit(
                name = "Phone",
                value = Entry.UNKNOWN,
                targetValue = 30.0,
                unit = "min",
                targetType = NumericalHabitType.AT_MOST
            )
        )

        val statusesByName = TodayScreenStateBuilder.build(habitList, today)
            .sections
            .single()
            .items
            .associate { it.name to it.status }

        assertEquals(TodayHabitStatus.COMPLETED, statusesByName["Social media"])
        assertEquals(TodayHabitStatus.EXCEEDED, statusesByName["Music"])
        assertEquals(TodayHabitStatus.UNKNOWN, statusesByName["Phone"])
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
