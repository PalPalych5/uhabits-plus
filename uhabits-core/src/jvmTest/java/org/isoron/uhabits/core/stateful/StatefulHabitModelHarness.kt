/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.stateful

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.EditHabitGoalCommand
import org.isoron.uhabits.core.commands.GoalApplyScope
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.ScoreList
import org.isoron.uhabits.core.models.StreakList
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.tasks.TaskRunner
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Algebraic representation of operations in the generative stateful harness.
 */
sealed class StatefulHabitOp {
    abstract val label: String

    data class Create(
        override val label: String,
        val isNumerical: Boolean,
        val targetType: NumericalHabitType,
        val targetValue: Double,
        val frequency: Frequency,
        val unit: String,
        val colorIndex: Int = 0,
    ) : StatefulHabitOp()

    data class UpdateGoal(
        override val label: String,
        val newTargetValue: Double,
        val newFrequency: Frequency,
        val newTargetType: NumericalHabitType,
        val effectiveDate: LocalDate,
        val applyScope: GoalApplyScope = GoalApplyScope.FROM_DATE,
    ) : StatefulHabitOp()

    data class LogEntry(
        override val label: String,
        val date: LocalDate,
        val value: Int,
    ) : StatefulHabitOp()

    data class EditDateEntry(
        override val label: String,
        val date: LocalDate,
        val newValue: Int,
    ) : StatefulHabitOp()

    data class ScheduleChange(
        override val label: String,
        val reminder: Reminder?,
    ) : StatefulHabitOp()

    object SourceRestart : StatefulHabitOp() {
        override val label: String = "GLOBAL_RESTART"
    }

    data class Delete(
        override val label: String,
    ) : StatefulHabitOp()
}

/**
 * Immutable snapshot of habit state used to verify Isolation and RAW invariants.
 */
data class HabitSnapshot(
    val id: Long?,
    val uuid: String?,
    val name: String,
    val isNumerical: Boolean,
    val frequency: Frequency,
    val targetType: NumericalHabitType,
    val targetValue: Double,
    val unit: String,
    val reminder: Reminder?,
    val entries: Map<LocalDate, Int>,
    val goalHistory: List<HabitGoal>,
)

fun captureHabitSnapshot(habit: Habit): HabitSnapshot {
    val entriesMap = mutableMapOf<LocalDate, Int>()
    for (entry in habit.originalEntries.getKnown()) {
        entriesMap[entry.date] = entry.value
    }
    return HabitSnapshot(
        id = habit.id,
        uuid = habit.uuid,
        name = habit.name,
        isNumerical = habit.isNumerical,
        frequency = habit.frequency,
        targetType = habit.targetType,
        targetValue = habit.targetValue,
        unit = habit.unit,
        reminder = habit.reminder?.let { Reminder(it.hour, it.minute, it.days) },
        entries = entriesMap,
        goalHistory = habit.goalHistory.map {
            HabitGoal(it.effectiveDate, it.frequency, it.targetType, it.targetValue, it.unit)
        },
    )
}

/**
 * Execution environment contract supporting Memory and SQLite backends.
 */
interface StatefulEnv {
    val baseDate: LocalDate
    fun apply(op: StatefulHabitOp)
    fun getHabit(label: String): Habit?
    fun getAllHabits(): List<Habit>
    fun restartSource()
    fun close()
}

/**
 * Memory-based execution environment.
 */
class MemoryStatefulEnv(override val baseDate: LocalDate) : StatefulEnv {
    val factory = MemoryModelFactory()
    val habitList: HabitList = factory.buildHabitList()
    val labelsToIds = mutableMapOf<String, Long>()

    init {
        setToday(baseDate)
    }

    override fun apply(op: StatefulHabitOp) {
        when (op) {
            is StatefulHabitOp.Create -> {
                val habit = factory.buildHabit().apply {
                    this.name = op.label
                    this.type = if (op.isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
                    this.targetType = op.targetType
                    this.targetValue = op.targetValue
                    this.frequency = op.frequency
                    this.unit = op.unit
                    this.color = PaletteColor(op.colorIndex)
                }
                habitList.add(habit)
                labelsToIds[op.label] = habit.id!!
            }
            is StatefulHabitOp.UpdateGoal -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetType = op.newTargetType
                    targetValue = op.newTargetValue
                    frequency = op.newFrequency
                }
                val cmd = EditHabitGoalCommand(
                    habitList, id, modified, op.applyScope, op.effectiveDate
                )
                cmd.run()
            }
            is StatefulHabitOp.LogEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, op.value))
                habit.recompute()
            }
            is StatefulHabitOp.EditDateEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, op.newValue))
                habit.recompute()
            }
            is StatefulHabitOp.ScheduleChange -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.reminder = op.reminder
                habitList.update(habit)
            }
            is StatefulHabitOp.SourceRestart -> {
                restartSource()
            }
            is StatefulHabitOp.Delete -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id)
                if (habit != null) {
                    habitList.remove(habit)
                }
                labelsToIds.remove(op.label)
            }
        }
    }

    override fun getHabit(label: String): Habit? {
        val id = labelsToIds[label] ?: return null
        return habitList.getById(id)
    }

    override fun getAllHabits(): List<Habit> {
        val list = mutableListOf<Habit>()
        for (id in labelsToIds.values) {
            habitList.getById(id)?.let { list.add(it) }
        }
        return list
    }

    override fun restartSource() {
        // For memory, reload preserves registered state
        habitList.resort()
    }

    override fun close() {
        // No-op for memory
    }
}

/**
 * SQLite-based execution environment backed by real SQLite database tables.
 */
class SQLiteStatefulEnv(override val baseDate: LocalDate) : StatefulEnv {
    var db: Database = runBlocking { TestDatabaseHelper.createEmptyDatabase() }
    var factory = SQLModelFactory(db)
    var habitList: HabitList = factory.buildHabitList()
    val labelsToIds = mutableMapOf<String, Long>()

    init {
        setToday(baseDate)
    }

    override fun apply(op: StatefulHabitOp) {
        when (op) {
            is StatefulHabitOp.Create -> {
                val habit = factory.buildHabit().apply {
                    this.name = op.label
                    this.type = if (op.isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
                    this.targetType = op.targetType
                    this.targetValue = op.targetValue
                    this.frequency = op.frequency
                    this.unit = op.unit
                    this.color = PaletteColor(op.colorIndex)
                }
                habitList.add(habit)
                labelsToIds[op.label] = habit.id!!
            }
            is StatefulHabitOp.UpdateGoal -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetType = op.newTargetType
                    targetValue = op.newTargetValue
                    frequency = op.newFrequency
                }
                val cmd = EditHabitGoalCommand(
                    habitList, id, modified, op.applyScope, op.effectiveDate
                )
                cmd.run()
            }
            is StatefulHabitOp.LogEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, op.value))
                habit.recompute()
            }
            is StatefulHabitOp.EditDateEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, op.newValue))
                habit.recompute()
            }
            is StatefulHabitOp.ScheduleChange -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.reminder = op.reminder
                habitList.update(habit)
            }
            is StatefulHabitOp.SourceRestart -> {
                restartSource()
            }
            is StatefulHabitOp.Delete -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id)
                if (habit != null) {
                    habitList.remove(habit)
                }
                labelsToIds.remove(op.label)
            }
        }
    }

    override fun getHabit(label: String): Habit? {
        val id = labelsToIds[label] ?: return null
        return habitList.getById(id)
    }

    override fun getAllHabits(): List<Habit> {
        val list = mutableListOf<Habit>()
        for (id in labelsToIds.values) {
            habitList.getById(id)?.let { list.add(it) }
        }
        return list
    }

    override fun restartSource() {
        // Re-hydrate factory and SQLite list on the same underlying database
        factory = SQLModelFactory(db)
        habitList = factory.buildHabitList()
    }

    override fun close() {
        db.close()
    }
}

/**
 * Deterministic generator creating multi-step operation sequences from fixed random seeds.
 */
class StatefulSequenceGenerator(seed: Long) {
    private val random = Random(seed)

    fun generate(count: Int, baseDate: LocalDate): List<StatefulHabitOp> {
        val ops = mutableListOf<StatefulHabitOp>()
        val activeHabits = mutableMapOf<String, Boolean>() // label -> isNumerical
        val activeLabels = mutableListOf<String>()
        var labelCounter = 0

        fun addHabit(): StatefulHabitOp.Create {
            val label = "H${labelCounter++}"
            activeLabels.add(label)
            val op = createRandomHabit(label)
            activeHabits[label] = op.isNumerical
            return op
        }

        // Start with at least 2 creates to enable isolation tests
        for (i in 1..2) {
            ops.add(addHabit())
        }

        while (ops.size < count) {
            if (activeLabels.isEmpty()) {
                ops.add(addHabit())
                continue
            }

            val roll = random.nextInt(100)
            when {
                // 15% chance: Create a new habit
                roll < 15 && activeLabels.size < 5 -> {
                    ops.add(addHabit())
                }
                // 25% chance: Log entry
                roll < 40 -> {
                    val label = activeLabels[random.nextInt(activeLabels.size)]
                    val isNumerical = activeHabits[label] ?: false
                    val offset = random.nextInt(-10, 10)
                    val date = baseDate.plus(offset)
                    val value = if (isNumerical) {
                        random.nextInt(10, 100) * 1000
                    } else {
                        listOf(Entry.YES_MANUAL, Entry.NO, Entry.SKIP)[random.nextInt(3)]
                    }
                    ops.add(StatefulHabitOp.LogEntry(label, date, value))
                }
                // 20% chance: Edit date entry
                roll < 60 -> {
                    val label = activeLabels[random.nextInt(activeLabels.size)]
                    val isNumerical = activeHabits[label] ?: false
                    val offset = random.nextInt(-10, 10)
                    val date = baseDate.plus(offset)
                    val newVal = if (isNumerical) {
                        random.nextInt(10, 100) * 1000
                    } else {
                        listOf(Entry.YES_MANUAL, Entry.NO, Entry.SKIP)[random.nextInt(3)]
                    }
                    ops.add(StatefulHabitOp.EditDateEntry(label, date, newVal))
                }
                // 15% chance: Update goal
                roll < 75 -> {
                    val label = activeLabels[random.nextInt(activeLabels.size)]
                    val isNumerical = activeHabits[label] ?: false
                    val newTarget = if (isNumerical) random.nextInt(30, 240).toDouble() else 1.0
                    val newFreq = if (random.nextBoolean()) Frequency.DAILY else Frequency(1, 7)
                    val targetType = if (random.nextBoolean()) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
                    val effDate = baseDate.plus(random.nextInt(-5, 5))
                    ops.add(StatefulHabitOp.UpdateGoal(label, newTarget, newFreq, targetType, effDate))
                }
                // 10% chance: Schedule change
                roll < 85 -> {
                    val label = activeLabels[random.nextInt(activeLabels.size)]
                    val reminder = if (random.nextBoolean()) {
                        Reminder(random.nextInt(6, 22), random.nextInt(0, 59), WeekdayList.EVERY_DAY)
                    } else null
                    ops.add(StatefulHabitOp.ScheduleChange(label, reminder))
                }
                // 10% chance: Source restart
                roll < 95 -> {
                    ops.add(StatefulHabitOp.SourceRestart)
                }
                // 5% chance: Delete habit (if more than 1 active)
                else -> {
                    if (activeLabels.size > 1) {
                        val victim = activeLabels.removeAt(random.nextInt(activeLabels.size))
                        activeHabits.remove(victim)
                        ops.add(StatefulHabitOp.Delete(victim))
                    } else {
                        val label = activeLabels[0]
                        val isNum = activeHabits[label] ?: false
                        val v = if (isNum) 50_000 else Entry.YES_MANUAL
                        ops.add(StatefulHabitOp.LogEntry(label, baseDate, v))
                    }
                }
            }
        }

        return ops
    }

    private fun createRandomHabit(label: String): StatefulHabitOp.Create {
        val isNumerical = random.nextBoolean()
        val targetType = if (random.nextBoolean()) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
        val targetValue = if (isNumerical) random.nextInt(20, 120).toDouble() else 1.0
        val frequency = if (random.nextBoolean()) Frequency.DAILY else Frequency(1, 7)
        return StatefulHabitOp.Create(
            label = label,
            isNumerical = isNumerical,
            targetType = targetType,
            targetValue = targetValue,
            frequency = frequency,
            unit = if (isNumerical) "min" else "",
            colorIndex = random.nextInt(0, 10),
        )
    }
}

/**
 * Result returned by the sequence shrinker upon finding a minimal reproducible violation.
 */
data class ShrunkFailureResult(
    val originalSize: Int,
    val shrunkSize: Int,
    val minimalOps: List<StatefulHabitOp>,
    val failureMessage: String,
    val seed: Long,
)

/**
 * Delta-debugging and sublist-reduction shrinker that reduces failing operation sequences.
 */
class SequenceShrinker {

    fun shrink(
        seed: Long,
        initialOps: List<StatefulHabitOp>,
        envFactory: () -> StatefulEnv,
        predicate: (StatefulEnv, List<StatefulHabitOp>) -> Throwable?,
    ): ShrunkFailureResult? {
        val initialEnv = envFactory()
        val initialFailure = predicate(initialEnv, initialOps)
        initialEnv.close()
        if (initialFailure == null) return null // Did not fail originally

        var current = initialOps.toList()
        var currentError = initialFailure.message ?: "Unknown error"

        // Binary sublist elimination / chunk removal
        var stepSize = current.size / 2
        while (stepSize > 0) {
            var i = 0
            while (i < current.size) {
                val candidate = current.toMutableList()
                val removeEnd = minOf(i + stepSize, current.size)
                candidate.subList(i, removeEnd).clear()

                val testEnv = envFactory()
                val err = predicate(testEnv, candidate)
                testEnv.close()

                if (err != null) {
                    current = candidate
                    currentError = err.message ?: currentError
                    break // Reduced! Restart with new smaller sequence
                } else {
                    i += stepSize
                }
            }
            stepSize /= 2
        }

        // Single-element removal pass (1-minimal delta debugging)
        var i = 0
        while (i < current.size) {
            val candidate = current.toMutableList()
            candidate.removeAt(i)

            val testEnv = envFactory()
            val err = predicate(testEnv, candidate)
            testEnv.close()

            if (err != null) {
                current = candidate
                currentError = err.message ?: currentError
                // do not increment i, test new item at this position
            } else {
                i++
            }
        }

        return ShrunkFailureResult(
            originalSize = initialOps.size,
            shrunkSize = current.size,
            minimalOps = current,
            failureMessage = currentError,
            seed = seed,
        )
    }
}
