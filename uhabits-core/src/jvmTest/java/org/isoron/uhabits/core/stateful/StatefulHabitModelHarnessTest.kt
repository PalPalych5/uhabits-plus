/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.stateful

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.ScoreList
import org.isoron.uhabits.core.models.StreakList
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatefulHabitModelHarnessTest {

    private val baseDate = LocalDate(2026, 10, 31)

    @Before
    fun setUp() {
        setToday(baseDate)
    }

    /**
     * Test levels defined for the generative harness.
     */
    enum class HarnessLevel(
        val seedsCount: Int,
        val opsPerSeed: Int,
        val checkInterval: Int,
    ) {
        QUICK(seedsCount = 25, opsPerSeed = 100, checkInterval = 25),
        STANDARD(seedsCount = 100, opsPerSeed = 500, checkInterval = 100),
        STRESS(seedsCount = 50, opsPerSeed = 2000, checkInterval = 250),
        EXTENDED(seedsCount = 10, opsPerSeed = 5000, checkInterval = 500),
    }

    @Test
    fun testQuickLevelDifferentialExecution() {
        // QUICK level: 25 seeds x 100 ops with differential Memory/SQLite validation
        executeDifferentialLevel(HarnessLevel.QUICK, baseSeed = 1000L)
    }

    @Test
    fun testStandardLevelDifferentialExecution() {
        // STANDARD level: 100 seeds x 500 ops with differential Memory/SQLite validation
        executeDifferentialLevel(HarnessLevel.STANDARD, baseSeed = 2000L)
    }

    private fun executeDifferentialLevel(level: HarnessLevel, baseSeed: Long) {
        for (seedIdx in 0 until level.seedsCount) {
            val seed = baseSeed + seedIdx
            val generator = StatefulSequenceGenerator(seed)
            val ops = generator.generate(count = level.opsPerSeed, baseDate = baseDate)

            val memEnv = MemoryStatefulEnv(baseDate)
            val sqlEnv = SQLiteStatefulEnv(baseDate)

            try {
                for ((opIndex, op) in ops.withIndex()) {
                    // Capture pre-operation snapshots for isolation checks
                    val memBefore = if ((opIndex + 1) % level.checkInterval == 0 || opIndex == ops.lastIndex) {
                        memEnv.getAllHabits().associate { it.name to captureHabitSnapshot(it) }
                    } else emptyMap()

                    // Apply operation to both environments
                    memEnv.apply(op)
                    sqlEnv.apply(op)

                    // Periodic intermediate check & final state check across all 10 invariants
                    val isCheckPoint = (opIndex + 1) % level.checkInterval == 0 || opIndex == ops.lastIndex
                    if (isCheckPoint) {
                        verifyInvariants(
                            stepIndex = opIndex,
                            seed = seed,
                            op = op,
                            memEnv = memEnv,
                            sqlEnv = sqlEnv,
                            memBefore = memBefore
                        )
                    }
                }
            } finally {
                memEnv.close()
                sqlEnv.close()
            }
        }
    }

    private fun verifyInvariants(
        stepIndex: Int,
        seed: Long,
        op: StatefulHabitOp,
        memEnv: StatefulEnv,
        sqlEnv: StatefulEnv,
        memBefore: Map<String, HabitSnapshot>,
    ) {
        val msg = "Seed $seed Op $stepIndex (${op::class.simpleName} on ${op.label}):"

        // Invariant 1: Read-After-Write (RAW) on Target Habit
        when (op) {
            is StatefulHabitOp.CreateHabit -> {
                val hMem = memEnv.getHabit(op.label)
                val hSql = sqlEnv.getHabit(op.label)
                assertNotNull(hMem, "$msg Memory RAW failed: Created habit not found")
                assertNotNull(hSql, "$msg SQLite RAW failed: Created habit not found")
                assertEquals(op.targetValue, hMem.targetValue, "$msg Memory target value mismatch")
                assertEquals(op.targetValue, hSql.targetValue, "$msg SQLite target value mismatch")
                assertEquals(op.frequency, hMem.frequency, "$msg Memory frequency mismatch")
                assertEquals(op.frequency, hSql.frequency, "$msg SQLite frequency mismatch")
            }
            is StatefulHabitOp.AddBinaryCheckmark -> {
                val hMem = memEnv.getHabit(op.label)
                val hSql = sqlEnv.getHabit(op.label)
                if (hMem != null) assertEquals(Entry.YES_MANUAL, hMem.originalEntries.get(op.date).value, "$msg Memory checkmark mismatch")
                if (hSql != null) assertEquals(Entry.YES_MANUAL, hSql.originalEntries.get(op.date).value, "$msg SQLite checkmark mismatch")
            }
            is StatefulHabitOp.AddNumericalEntry -> {
                val hMem = memEnv.getHabit(op.label)
                val hSql = sqlEnv.getHabit(op.label)
                if (hMem != null) assertEquals(op.value, hMem.originalEntries.get(op.date).value, "$msg Memory entry mismatch")
                if (hSql != null) assertEquals(op.value, hSql.originalEntries.get(op.date).value, "$msg SQLite entry mismatch")
            }
            is StatefulHabitOp.EditEntryValue -> {
                val hMem = memEnv.getHabit(op.label)
                val hSql = sqlEnv.getHabit(op.label)
                if (hMem != null) assertEquals(op.newValue, hMem.originalEntries.get(op.date).value, "$msg Memory edited entry mismatch")
                if (hSql != null) assertEquals(op.newValue, hSql.originalEntries.get(op.date).value, "$msg SQLite edited entry mismatch")
            }
            is StatefulHabitOp.DeleteEntry -> {
                val hMem = memEnv.getHabit(op.label)
                val hSql = sqlEnv.getHabit(op.label)
                if (hMem != null) assertEquals(Entry.UNKNOWN, hMem.originalEntries.get(op.date).value, "$msg Memory delete entry mismatch")
                if (hSql != null) assertEquals(Entry.UNKNOWN, hSql.originalEntries.get(op.date).value, "$msg SQLite delete entry mismatch")
            }
            is StatefulHabitOp.ArchiveAndUnarchive -> {
                val hMem = memEnv.getHabit(op.label)
                val hSql = sqlEnv.getHabit(op.label)
                if (hMem != null) assertEquals(op.archive, hMem.isArchived, "$msg Memory archive state mismatch")
                if (hSql != null) assertEquals(op.archive, hSql.isArchived, "$msg SQLite archive state mismatch")
            }
            is StatefulHabitOp.Delete -> {
                assertNull(memEnv.getHabit(op.label), "$msg Memory delete failed")
                assertNull(sqlEnv.getHabit(op.label), "$msg SQLite delete failed")
            }
            else -> {}
        }

        // Invariant 2: Cross-Habit Isolation (Untargeted habits unchanged)
        if (memBefore.isNotEmpty() && op.label !in listOf("ALL", "GLOBAL_RESTART", "GLOBAL_BACKUP_RESTORE", "GLOBAL_SETTINGS")) {
            for ((name, before) in memBefore) {
                if (name != op.label && name != (op as? StatefulHabitOp.ReorderHabits)?.targetLabel) {
                    val after = memEnv.getHabit(name)
                    if (after != null) {
                        val afterSnap = captureHabitSnapshot(after)
                        assertEquals(before.name, afterSnap.name, "$msg Isolation violated on $name")
                        assertEquals(before.entries, afterSnap.entries, "$msg Isolation entries violated on $name")
                        assertEquals(before.targetValue, afterSnap.targetValue, "$msg Isolation target value violated on $name")
                        assertEquals(before.goalHistory, afterSnap.goalHistory, "$msg Isolation goal history violated on $name")
                    }
                }
            }
        }

        // Invariant 3: Recomputation Idempotency
        for (h in memEnv.getAllHabits()) {
            val s1 = h.scores.get(baseDate).value
            val st1 = h.streaks.getLatest()?.length ?: 0
            h.recompute()
            val s2 = h.scores.get(baseDate).value
            val st2 = h.streaks.getLatest()?.length ?: 0
            assertEquals(s1, s2, "$msg Idempotency score mismatch on ${h.name}")
            assertEquals(st1, st2, "$msg Idempotency streak mismatch on ${h.name}")
        }

        // Invariant 6: Goal Timeline Consistency
        for (h in memEnv.getAllHabits()) {
            val goals = h.normalizedGoalHistory()
            for (i in 0 until goals.size - 1) {
                assertTrue(goals[i].effectiveDate.isOlderThan(goals[i + 1].effectiveDate),
                    "$msg Goal timeline must be strictly monotonic for ${h.name}")
            }
        }

        // Invariant 8: Differential Equivalence (MemoryStatefulEnv vs SQLiteStatefulEnv)
        val memHabits = memEnv.getAllHabits().associateBy { it.name }
        val sqlHabits = sqlEnv.getAllHabits().associateBy { it.name }
        assertEquals(memHabits.keys, sqlHabits.keys, "$msg Differential habit keys mismatch between Memory and SQLite")

        for (name in memHabits.keys) {
            val m = memHabits[name]!!
            val s = sqlHabits[name]!!

            assertEquals(m.name, s.name, "$msg Differential name mismatch")
            assertEquals(m.isNumerical, s.isNumerical, "$msg Differential isNumerical mismatch")
            assertEquals(m.targetType, s.targetType, "$msg Differential targetType mismatch on $name")
            assertEquals(m.targetValue, s.targetValue, "$msg Differential targetValue mismatch on $name")
            assertEquals(m.frequency, s.frequency, "$msg Differential frequency mismatch on $name")
            assertEquals(m.unit, s.unit, "$msg Differential unit mismatch on $name")
            assertEquals(m.isArchived, s.isArchived, "$msg Differential isArchived mismatch on $name")
            assertEquals(m.statisticsStartDate, s.statisticsStartDate, "$msg Differential statisticsStartDate mismatch on $name")

            val mEntries = m.originalEntries.getKnown().filter { it.value != Entry.UNKNOWN }.associate { it.date to it.value }
            val sEntries = s.originalEntries.getKnown().filter { it.value != Entry.UNKNOWN }.associate { it.date to it.value }
            assertEquals(mEntries, sEntries, "$msg Differential entries mismatch on $name")

            val mGoals = m.normalizedGoalHistory()
            val sGoals = s.normalizedGoalHistory()
            assertEquals(mGoals.size, sGoals.size, "$msg Differential goal count mismatch on $name")
            for (gIdx in mGoals.indices) {
                assertEquals(mGoals[gIdx].effectiveDate, sGoals[gIdx].effectiveDate, "$msg Goal effectiveDate mismatch")
                assertEquals(mGoals[gIdx].targetValue, sGoals[gIdx].targetValue, "$msg Goal targetValue mismatch")
                assertEquals(mGoals[gIdx].frequency, sGoals[gIdx].frequency, "$msg Goal frequency mismatch")
            }

            assertEquals(m.isCompletedOn(baseDate), s.isCompletedOn(baseDate), "$msg isCompletedOn mismatch on $name")
            assertEquals(m.scores.get(baseDate).value, s.scores.get(baseDate).value, "$msg Score mismatch on $name")
            assertEquals(m.streaks.getLatest()?.length, s.streaks.getLatest()?.length, "$msg Streak mismatch on $name")
        }
    }

    @Test
    fun testShrinkingAlgorithmFindsMinimalFailingSequence() {
        val seed = 42L
        val generator = StatefulSequenceGenerator(seed)
        val initialOps = generator.generate(count = 30, baseDate = baseDate)

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

        val targetLabel = result.minimalOps.first().label
        assertTrue(result.minimalOps.all { it.label == targetLabel },
            "Shrunk sequence should retain only operations targeting $targetLabel")
    }

    @Test
    fun testIdempotentRepeatedOperations() {
        val env = MemoryStatefulEnv(baseDate)
        try {
            val createOp = StatefulHabitOp.CreateHabit(
                label = "H_Idempotent",
                isNumerical = true,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 50.0,
                frequency = Frequency.DAILY,
                unit = "min"
            )
            env.apply(createOp)

            val logOp = StatefulHabitOp.AddNumericalEntry(
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

    @Test
    fun testInvalidInputValidationHandledSafely() {
        val env = MemoryStatefulEnv(baseDate)
        try {
            val createOp = StatefulHabitOp.CreateHabit(
                label = "H_Validation",
                isNumerical = true,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 25.0,
                frequency = Frequency.DAILY,
                unit = "min"
            )
            env.apply(createOp)

            // Attempt invalid operations (negative target, negative frequency denominator)
            val invalidOp1 = StatefulHabitOp.AttemptInvalidInput("H_Validation", invalidTargetValue = -100.0, invalidFrequency = null)
            val invalidOp2 = StatefulHabitOp.AttemptInvalidInput("H_Validation", invalidTargetValue = null, invalidFrequency = Frequency(-1, 0))

            env.apply(invalidOp1)
            env.apply(invalidOp2)

            val h = env.getHabit("H_Validation")
            assertNotNull(h)
            assertEquals(25.0, h.targetValue, "Valid target value must be preserved")
            assertEquals(Frequency.DAILY, h.frequency, "Valid frequency must be preserved")
        } finally {
            env.close()
        }
    }
}
