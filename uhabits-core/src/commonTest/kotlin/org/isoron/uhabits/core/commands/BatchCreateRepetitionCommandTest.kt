/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BatchCreateRepetitionCommandTest : BaseUnitTest() {
    private lateinit var today: LocalDate
    private lateinit var boolUntouched: Habit
    private lateinit var boolCompleted: Habit
    private lateinit var boolSkipped: Habit
    private lateinit var numUntouched: Habit
    private lateinit var numTouched: Habit

    @BeforeTest
    override fun setUp() {
        super.setUp()
        today = getToday()

        // 1. Untouched boolean habit
        boolUntouched = Habit(
            name = "Bool Untouched",
            type = HabitType.YES_NO,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        )
        habitList.add(boolUntouched)

        // 2. Completed boolean habit (value = YES_MANUAL)
        boolCompleted = Habit(
            name = "Bool Completed",
            type = HabitType.YES_NO,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, Entry.YES_MANUAL))
            recompute()
        }
        habitList.add(boolCompleted)

        // 3. Already skipped boolean habit
        boolSkipped = Habit(
            name = "Bool Skipped",
            type = HabitType.YES_NO,
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, Entry.SKIP))
            recompute()
        }
        habitList.add(boolSkipped)

        // 4. Untouched numerical habit
        numUntouched = Habit(
            name = "Num Untouched",
            type = HabitType.NUMERICAL,
            targetValue = 10.0,
            targetType = NumericalHabitType.AT_LEAST,
            unit = "min",
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        )
        habitList.add(numUntouched)

        // 5. Touched numerical habit (value = 5_000)
        numTouched = Habit(
            name = "Num Touched",
            type = HabitType.NUMERICAL,
            targetValue = 10.0,
            targetType = NumericalHabitType.AT_LEAST,
            unit = "min",
            computedEntries = modelFactory.buildComputedEntries(),
            originalEntries = modelFactory.buildOriginalEntries(),
            scores = modelFactory.buildScoreList(),
            streaks = modelFactory.buildStreakList()
        ).apply {
            originalEntries.add(Entry(today, 5_000))
            recompute()
        }
        habitList.add(numTouched)
    }

    @Test
    fun testBatchSkip() {
        val testNote = "Batch skipped for test"
        
        // Find habits eligible for skip (simulating what the Habits skip-day filter does)
        val habitsToSkip = mutableListOf<Habit>()
        for (h in habitList) {
            val origVal = h.originalEntries.get(today).value
            if (origVal != Entry.UNKNOWN) continue
            
            val compVal = h.computedEntries.get(today).value
            if (compVal == Entry.YES_MANUAL || compVal == Entry.YES_AUTO || compVal == Entry.SKIP) {
                continue
            }
            
            habitsToSkip.add(h)
        }

        // Verify only boolUntouched and numUntouched are selected
        assertEquals(2, habitsToSkip.size)
        assertEquals(true, habitsToSkip.contains(boolUntouched))
        assertEquals(true, habitsToSkip.contains(numUntouched))

        // Run batch skip command
        val cmd = BatchCreateRepetitionCommand(habitList, habitsToSkip, today, Entry.SKIP, testNote)
        cmd.run()

        // Assert skipped items have Entry.SKIP and the correct notes
        assertEquals(Entry.SKIP, boolUntouched.originalEntries.get(today).value)
        assertEquals(testNote, boolUntouched.originalEntries.get(today).notes)

        assertEquals(Entry.SKIP, numUntouched.originalEntries.get(today).value)
        assertEquals(testNote, numUntouched.originalEntries.get(today).notes)

        // Assert untouched/completed items are unchanged
        assertEquals(Entry.YES_MANUAL, boolCompleted.originalEntries.get(today).value)
        assertEquals("", boolCompleted.originalEntries.get(today).notes)

        assertEquals(Entry.SKIP, boolSkipped.originalEntries.get(today).value)
        assertEquals("", boolSkipped.originalEntries.get(today).notes)

        assertEquals(5000, numTouched.originalEntries.get(today).value)
        assertEquals("", numTouched.originalEntries.get(today).notes)
    }
}
