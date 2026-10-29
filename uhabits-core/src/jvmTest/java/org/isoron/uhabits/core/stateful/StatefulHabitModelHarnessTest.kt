/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.stateful

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.ScoreList
import org.isoron.uhabits.core.models.StreakList
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatefulHabitModelHarnessTest {

    private val baseDate = LocalDate(2026, 10, 19)

    @Test
    fun testMemoryStatefulExecutionWithFixedSeeds() {
        val seeds = listOf(42L, 1337L, 20261029L)

        for (seed in seeds) {
            val generator = StatefulSequenceGenerator(seed)
            val ops = generator.generate(count = 25, baseDate = baseDate)
            val env = MemoryStatefulEnv(baseDate)

            try {
                for ((index, op) in ops.withIndex()) {
                    // 1. Snapshot other habits for Isolation verification
                    val otherHabitsBefore = env.getAllHabits()
                        .filter { it.name != op.label }
                        .associate { it.name to captureHabitSnapshot(it) }

                    // 2. Apply Operation
                    env.apply(op)

                    // 3. Verify Isolation Invariant: No other habit was modified
                    for ((name, beforeSnapshot) in otherHabitsBefore) {
                        val currentHabit = env.getHabit(name)
                        assertNotNull(currentHabit, "Seed $seed Op $index: Isolation violated, $name disappeared")
                        val currentSnapshot = captureHabitSnapshot(currentHabit)
                        assertEquals(beforeSnapshot.name, currentSnapshot.name)
                        assertEquals(beforeSnapshot.frequency, currentSnapshot.frequency)
                        assertEquals(beforeSnapshot.targetValue, currentSnapshot.targetValue)
                        assertEquals(beforeSnapshot.entries, currentSnapshot.entries,
                            "Seed $seed Op $index: Isolation violated, entries of $name changed when modifying ${op.label}")
                        assertEquals(beforeSnapshot.goalHistory.size, currentSnapshot.goalHistory.size,
                            "Seed $seed Op $index: Isolation violated, goals of $name changed")
                    }

                    // 4. Verify Read-After-Write (RAW) Invariant on Target Habit
                    when (op) {
                        is StatefulHabitOp.Create -> {
                            val h = env.getHabit(op.label)
                            assertNotNull(h, "RAW violated: Created habit ${op.label} could not be read")
                            assertEquals(op.targetValue, h.targetValue)
                            assertEquals(op.frequency, h.frequency)
                            assertEquals(op.isNumerical, h.isNumerical)
                        }
                        is StatefulHabitOp.LogEntry -> {
                            val h = env.getHabit(op.label)
                            if (h != null) {
                                assertEquals(op.value, h.originalEntries.get(op.date).value,
                                    "RAW violated: LogEntry value mismatch on ${op.date}")
                            }
                        }
                        is StatefulHabitOp.EditDateEntry -> {
                            val h = env.getHabit(op.label)
                            if (h != null) {
                                assertEquals(op.newValue, h.originalEntries.get(op.date).value,
                                    "RAW violated: EditDateEntry value mismatch on ${op.date}")
                            }
                        }
                        is StatefulHabitOp.UpdateGoal -> {
                            val h = env.getHabit(op.label)
                            if (h != null) {
                                val currentGoal = h.goalAt(op.effectiveDate)
                                assertEquals(op.newTargetValue, currentGoal.targetValue,
                                    "RAW violated: Target value mismatch on ${op.effectiveDate}")
                                assertEquals(op.newFrequency, currentGoal.frequency,
                                    "RAW violated: Frequency mismatch on ${op.effectiveDate}")
                            }
                        }
                        is StatefulHabitOp.ScheduleChange -> {
                            val h = env.getHabit(op.label)
                            if (h != null) {
                                assertEquals(op.reminder?.hour, h.reminder?.hour)
                                assertEquals(op.reminder?.minute, h.reminder?.minute)
                            }
                        }
                        is StatefulHabitOp.Delete -> {
                            assertNull(env.getHabit(op.label), "RAW violated: Deleted habit ${op.label} still exists")
                        }
                        is StatefulHabitOp.SourceRestart -> {
                            // Source restart must maintain active habits count
                            assertTrue(env.getAllHabits().isNotEmpty(), "Source restart must preserve active habits")
                        }
                    }

                    // 5. Verify Deterministic Recomputation Invariant
                    val activeHabit = env.getHabit(op.label)
                    if (activeHabit != null) {
                        val scoreList1 = ScoreList()
                        val streakList1 = StreakList()
                        val fromDate = baseDate.minus(15)
                        val toDate = baseDate.plus(15)

                        scoreList1.recompute(activeHabit, activeHabit.computedEntries, fromDate, toDate)
                        streakList1.recompute(activeHabit, activeHabit.computedEntries, fromDate, toDate)

                        val scoreList2 = ScoreList()
                        val streakList2 = StreakList()
                        scoreList2.recompute(activeHabit, activeHabit.computedEntries, fromDate, toDate)
                        streakList2.recompute(activeHabit, activeHabit.computedEntries, fromDate, toDate)

                        assertEquals(scoreList1.get(baseDate).value, scoreList2.get(baseDate).value,
                            "Deterministic recomputation violated for score on $baseDate")
                        assertEquals(streakList1.getLatest()?.length, streakList2.getLatest()?.length,
                            "Deterministic recomputation violated for latest streak")
                    }
                }
            } finally {
                env.close()
            }
        }
    }

    @Test
    fun testSQLiteStatefulExecutionWithSourceRestarts() {
        val generator = StatefulSequenceGenerator(seed = 999L)
        val ops = generator.generate(count = 20, baseDate = baseDate)
        val env = SQLiteStatefulEnv(baseDate)

        try {
            for ((index, op) in ops.withIndex()) {
                val habitsBefore = env.getAllHabits().associate { it.name to captureHabitSnapshot(it) }

                env.apply(op)

                if (op is StatefulHabitOp.SourceRestart) {
                    // Verify re-hydration from SQLite tables
                    val habitsAfter = env.getAllHabits().associate { it.name to captureHabitSnapshot(it) }
                    assertEquals(habitsBefore.keys, habitsAfter.keys,
                        "SQLite re-hydration must preserve all habit names across restart")

                    for (name in habitsBefore.keys) {
                        val b = habitsBefore[name]!!
                        val a = habitsAfter[name]!!
                        assertEquals(b.targetValue, a.targetValue, "Target value preserved after SQLite restart")
                        assertEquals(b.frequency, a.frequency, "Frequency preserved after SQLite restart")
                        assertEquals(b.entries, a.entries, "All entries preserved after SQLite restart")
                    }
                }
            }
        } finally {
            env.close()
        }
    }

    @Test
    fun testShrinkingAlgorithmFindsMinimalFailingSequence() {
        val seed = 42L
        val generator = StatefulSequenceGenerator(seed)
        val initialOps = generator.generate(count = 25, baseDate = baseDate)

        // Artificial failure condition: Fail if any habit has >= 2 entries logged in originalEntries
        val predicate: (StatefulEnv, List<StatefulHabitOp>) -> Throwable? = { env, ops ->
            var failure: Throwable? = null
            try {
                for (op in ops) {
                    env.apply(op)
                    val failing = env.getAllHabits().firstOrNull { it.originalEntries.getKnown().size >= 2 }
                    if (failing != null) {
                        failure = AssertionError("Artificial failure: habit ${failing.name} has >= 2 entries logged")
                        break
                    }
                }
            } catch (t: Throwable) {
                failure = t
            }
            failure
        }

        val shrinker = SequenceShrinker()
        val result = shrinker.shrink(
            seed = seed,
            initialOps = initialOps,
            envFactory = { MemoryStatefulEnv(baseDate) },
            predicate = predicate,
        )

        assertNotNull(result, "Shrinker must find failing sequence")
        assertTrue(result.shrunkSize < result.originalSize,
            "Shrunk size (${result.shrunkSize}) must be strictly less than original size (${result.originalSize})")
        assertEquals(3, result.shrunkSize,
            "Minimal sequence should be exactly 3 operations (Create + 2 entries), got: ${result.shrunkSize}")

        // Assert that the minimal sequence contains only the create and entry ops for that specific habit
        val targetLabel = result.minimalOps.first().label
        assertTrue(result.minimalOps.all { it.label == targetLabel },
            "Shrunk sequence should eliminate all unrelated habits and retain only $targetLabel")
    }

    @Test
    fun testIdempotentRepeatedOperations() {
        val env = MemoryStatefulEnv(baseDate)
        try {
            val createOp = StatefulHabitOp.Create(
                label = "H_Idempotent",
                isNumerical = true,
                targetType = org.isoron.uhabits.core.models.NumericalHabitType.AT_LEAST,
                targetValue = 50.0,
                frequency = org.isoron.uhabits.core.models.Frequency.DAILY,
                unit = "min"
            )
            env.apply(createOp)

            val logOp = StatefulHabitOp.LogEntry(
                label = "H_Idempotent",
                date = baseDate,
                value = 45_000
            )

            // Apply once
            env.apply(logOp)
            val habit = env.getHabit("H_Idempotent")!!
            val snapshotOnce = captureHabitSnapshot(habit)

            // Apply exact same operation second time
            env.apply(logOp)
            val snapshotTwice = captureHabitSnapshot(habit)

            assertEquals(snapshotOnce.entries, snapshotTwice.entries,
                "Idempotency invariant violated: Logging duplicate entry must not alter state")
            assertEquals(snapshotOnce.entries.size, snapshotTwice.entries.size,
                "Idempotency invariant violated: Duplicate entry must not create redundant records")
        } finally {
            env.close()
        }
    }
}
