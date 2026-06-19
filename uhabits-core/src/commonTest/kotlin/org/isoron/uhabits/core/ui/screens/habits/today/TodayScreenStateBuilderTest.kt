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
        val missed = booleanHabit("Missed", Entry.NO)
        val unknown = booleanHabit("Unknown", Entry.UNKNOWN)
        val skipped = booleanHabit("Skipped", Entry.SKIP)
        habitList.add(completed)
        habitList.add(missed)
        habitList.add(unknown)
        habitList.add(skipped)

        val state = TodayScreenStateBuilder.build(habitList, today)

        assertEquals(1, state.completedCount)
        assertEquals(4, state.totalCount)
        val statusesByName = state.sections.single().items.associate { it.name to it.status }
        assertEquals(TodayHabitStatus.COMPLETED, statusesByName["Checked"])
        assertEquals(TodayHabitStatus.REMAINING, statusesByName["Missed"])
        assertEquals(TodayHabitStatus.UNKNOWN, statusesByName["Unknown"])
        assertEquals(TodayHabitStatus.SKIPPED, statusesByName["Skipped"])
        assertEquals(listOf("Missed", "Unknown"), state.remaining.map { it.name })
    }

    @Test
    fun buildsNumericalAtLeastProgressUsingScaledValues() {
        habitList.add(numericalHabit("Reading", 45_000, targetValue = 30.0, unit = "min"))

        val item = TodayScreenStateBuilder.build(habitList, today).sections.single().items.single()

        assertEquals(TodayHabitStatus.COMPLETED, item.status)
        assertEquals(45.0, item.currentValue)
        assertEquals(30.0, item.targetValue)
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
    fun groupsSectionsByColorDeterministically() {
        habitList.add(booleanHabit("Green", Entry.YES_MANUAL, color = PaletteColor(7)))
        habitList.add(booleanHabit("Red", Entry.YES_MANUAL, color = PaletteColor(0)))
        habitList.add(booleanHabit("Blue", Entry.NO, color = PaletteColor(11)))

        val sections = TodayScreenStateBuilder.build(habitList, today).sections

        assertEquals(listOf(0, 7, 11), sections.map { it.color.paletteIndex })
        assertEquals(listOf(0, 7, 11), sections.map { it.paletteIndex })
    }

    private fun booleanHabit(
        name: String,
        value: Int,
        color: PaletteColor = PaletteColor(8)
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
        color: PaletteColor = PaletteColor(8)
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
            if (value != Entry.UNKNOWN) originalEntries.add(Entry(today, value))
            recompute()
        }
    }
}
