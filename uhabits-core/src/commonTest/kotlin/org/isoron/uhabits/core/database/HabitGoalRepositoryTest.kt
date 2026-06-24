package org.isoron.uhabits.core.database

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import kotlin.test.Test
import kotlin.test.assertEquals

class HabitGoalRepositoryTest {
    @Test
    fun replacesAndReadsGoals() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val goals = HabitGoalRepository(db)
        val habitId = habits.insert(HabitData(name = "Reading"))

        goals.replaceAll(
            habitId,
            listOf(
                HabitGoalData(habitId = habitId, effectiveTimestamp = 0, freqNum = 1, freqDen = 1, targetType = 0, targetValue = 15.0, unit = "min"),
                HabitGoalData(habitId = habitId, effectiveTimestamp = 86_400_000L, freqNum = 1, freqDen = 1, targetType = 0, targetValue = 20.0, unit = "min")
            )
        )

        val loaded = goals.findAllByHabitId(habitId)
        assertEquals(2, loaded.size)
        assertEquals(20.0, loaded[0].targetValue)
        assertEquals(15.0, loaded[1].targetValue)
        db.close()
    }
}
