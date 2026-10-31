package org.isoron.uhabits.core.database

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

    @Test
    fun sameDateDivergentUuidThrowsIllegalStateExceptionAndPreservesOriginal() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val goals = HabitGoalRepository(db)
        val habitId = habits.insert(HabitData(name = "Running"))

        val goal1 = HabitGoalData(
            habitId = habitId,
            uuid = "goal-uuid-1",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 10.0,
            unit = "km"
        )
        goals.upsert(goal1)

        val goal2 = HabitGoalData(
            habitId = habitId,
            uuid = "goal-uuid-2",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 20.0,
            unit = "km"
        )

        assertFailsWith<IllegalStateException> {
            goals.upsert(goal2)
        }

        val original = goals.findByUuid("goal-uuid-1")
        assertEquals(10.0, original?.targetValue)
        assertEquals("goal-uuid-1", original?.uuid)

        assertNull(goals.findByUuid("goal-uuid-2"))
        db.close()
    }

    @Test
    fun distinctHabitsSameTimestampSucceedIndependently() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val goals = HabitGoalRepository(db)
        val habit1Id = habits.insert(HabitData(name = "Habit 1"))
        val habit2Id = habits.insert(HabitData(name = "Habit 2"))

        val goalH1 = HabitGoalData(
            habitId = habit1Id,
            uuid = "goal-h1-1000",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 5.0,
            unit = "count"
        )
        val goalH2 = HabitGoalData(
            habitId = habit2Id,
            uuid = "goal-h2-1000",
            effectiveTimestamp = 1000,
            freqNum = 2,
            freqDen = 1,
            targetType = 0,
            targetValue = 15.0,
            unit = "pages"
        )

        goals.upsert(goalH1)
        goals.upsert(goalH2)

        val loadedH1 = goals.findByHabitAndTimestamp(habit1Id, 1000)
        assertEquals("goal-h1-1000", loadedH1?.uuid)
        assertEquals(5.0, loadedH1?.targetValue)

        val loadedH2 = goals.findByHabitAndTimestamp(habit2Id, 1000)
        assertEquals("goal-h2-1000", loadedH2?.uuid)
        assertEquals(15.0, loadedH2?.targetValue)

        assertNull(goals.findByHabitAndTimestamp(habit1Id, 9999))
        db.close()
    }

    @Test
    fun replaceAllRollsBackAndPreservesOriginalGoalsWhenCollisionThrows() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val goals = HabitGoalRepository(db)
        val habitId = habits.insert(HabitData(name = "Meditation"))

        val goal1 = HabitGoalData(
            habitId = habitId,
            uuid = "g1",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 10.0,
            unit = "min"
        )
        val goal2 = HabitGoalData(
            habitId = habitId,
            uuid = "g2",
            effectiveTimestamp = 2000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 20.0,
            unit = "min"
        )
        goals.upsert(goal1)
        goals.upsert(goal2)

        val colliding = HabitGoalData(
            habitId = habitId,
            uuid = "g2-divergent-uuid",
            effectiveTimestamp = 2000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 25.0,
            unit = "min"
        )

        assertFailsWith<IllegalStateException> {
            goals.replaceAll(habitId, listOf(goal1, colliding), updatedAt = 5000)
        }

        // Both original goals must remain active and intact with deleted_at == null
        val activeGoals = goals.findAllByHabitId(habitId)
        assertEquals(2, activeGoals.size)
        val g2 = goals.findByUuid("g2")
        assertEquals(20.0, g2?.targetValue)
        assertNull(g2?.deletedAt)

        db.close()
    }

    @Test
    fun sameUuidTargetingDifferentHabitThrowsAndPreservesOriginal() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val goals = HabitGoalRepository(db)
        val habit1 = habits.insert(HabitData(name = "Habit 1"))
        val habit2 = habits.insert(HabitData(name = "Habit 2"))

        val originalGoal = HabitGoalData(
            habitId = habit1,
            uuid = "shared-uuid",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 10.0,
            unit = "min"
        )
        goals.upsert(originalGoal)

        val conflictingGoal = HabitGoalData(
            habitId = habit2,
            uuid = "shared-uuid",
            effectiveTimestamp = 2000,
            freqNum = 2,
            freqDen = 1,
            targetType = 0,
            targetValue = 50.0,
            unit = "pages"
        )

        assertFailsWith<IllegalStateException> {
            goals.upsert(conflictingGoal)
        }

        val preserved = goals.findByUuid("shared-uuid")!!
        assertEquals(habit1, preserved.habitId)
        assertEquals(1000L, preserved.effectiveTimestamp)
        assertEquals(10.0, preserved.targetValue, 0.001)
        db.close()
    }

    @Test
    fun sameUuidTargetingDifferentDateThrowsAndPreservesOriginal() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val goals = HabitGoalRepository(db)
        val habit1 = habits.insert(HabitData(name = "Habit 1"))

        val originalGoal = HabitGoalData(
            habitId = habit1,
            uuid = "goal-uuid-same",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 10.0,
            unit = "min"
        )
        goals.upsert(originalGoal)

        val conflictingGoal = HabitGoalData(
            habitId = habit1,
            uuid = "goal-uuid-same",
            effectiveTimestamp = 2000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 50.0,
            unit = "min"
        )

        assertFailsWith<IllegalStateException> {
            goals.upsert(conflictingGoal)
        }

        val preserved = goals.findByUuid("goal-uuid-same")!!
        assertEquals(habit1, preserved.habitId)
        assertEquals(1000L, preserved.effectiveTimestamp)
        assertEquals(10.0, preserved.targetValue, 0.001)
        assertNull(goals.findByHabitAndTimestamp(habit1, 2000))
        db.close()
    }

    @Test
    fun tombstoneOccupiesUniqueIndexAndPreventsDivergentUuid() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val goals = HabitGoalRepository(db)
        val habitId = habits.insert(HabitData(name = "Reading"))

        val goal = HabitGoalData(
            habitId = habitId,
            uuid = "goal-original",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 15.0,
            unit = "pages",
            deletedAt = 2000L
        )
        goals.upsert(goal)

        // Tombstone exists
        val tombstone = goals.findByHabitAndTimestamp(habitId, 1000)
        assertEquals("goal-original", tombstone?.uuid)
        assertEquals(2000L, tombstone?.deletedAt)

        // Attempting to upsert a divergent UUID on the same date collides with tombstone
        val divergent = HabitGoalData(
            habitId = habitId,
            uuid = "goal-divergent",
            effectiveTimestamp = 1000,
            freqNum = 1,
            freqDen = 1,
            targetType = 0,
            targetValue = 30.0,
            unit = "pages"
        )

        assertFailsWith<IllegalStateException> {
            goals.upsert(divergent)
        }

        // Tombstone remains untouched
        val after = goals.findByHabitAndTimestamp(habitId, 1000)
        assertEquals("goal-original", after?.uuid)
        assertEquals(2000L, after?.deletedAt)
        db.close()
    }
}
