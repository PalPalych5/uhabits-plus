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

        val originalGoal = loaded[1]
        val originalId = originalGoal.id

        // Upsert modified goal with same uuid
        goals.upsert(originalGoal.copy(targetValue = 25.0, unit = "pages"))
        val updated = goals.findByUuid(originalGoal.uuid)
        assertEquals(originalId, updated?.id)
        assertEquals(25.0, updated?.targetValue)
        assertEquals("pages", updated?.unit)

        db.close()
    }

    @Test
    fun concurrentDuplicateUpsertsPreserveIdAndDoNotThrow() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val habitId = habits.insert(HabitData(name = "Meditation"))
        val repo1 = HabitGoalRepository(db)
        val repo2 = HabitGoalRepository(db)

        val goal = HabitGoalData(
            habitId = habitId,
            uuid = "$habitId:goal:1000",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 10.0,
            unit = "min"
        )

        // Multiple consecutive or concurrent upserts on same UUID
        repo1.upsert(goal)
        val initialId = repo1.findByUuid(goal.uuid)?.id

        repo2.upsert(goal.copy(targetValue = 15.0))
        repo1.upsert(goal.copy(targetValue = 20.0))

        val finalGoal = repo2.findByUuid(goal.uuid)
        assertEquals(initialId, finalGoal?.id)
        assertEquals(20.0, finalGoal?.targetValue)
        db.close()
    }
}
