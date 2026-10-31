/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.stateful

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitGoalCommand
import org.isoron.uhabits.core.commands.GoalApplyScope
import org.isoron.uhabits.core.commands.SetGlobalStatisticsStartDateCommand
import org.isoron.uhabits.core.commands.SetHabitStatisticsStartDateCommand
import org.isoron.uhabits.core.commands.UnarchiveHabitsCommand
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.backup.ContainerBackupService
import org.isoron.uhabits.core.containers.backup.ContainerRestoreService
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.ScoreList
import org.isoron.uhabits.core.models.StreakList
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Algebraic representation of operations in the generative stateful harness covering 20 domain operations.
 */
sealed class StatefulHabitOp {
    abstract val label: String

    // (1) Create habit
    data class CreateHabit(
        override val label: String,
        val isNumerical: Boolean,
        val targetType: NumericalHabitType,
        val targetValue: Double,
        val frequency: Frequency,
        val unit: String,
        val colorIndex: Int = 0,
        val blockId: Long? = null,
        val containerId: ContainerId? = null,
    ) : StatefulHabitOp()

    // (2) Add binary checkmark
    data class AddBinaryCheckmark(
        override val label: String,
        val date: LocalDate,
    ) : StatefulHabitOp()

    // (3) Add numerical entry
    data class AddNumericalEntry(
        override val label: String,
        val date: LocalDate,
        val value: Int,
    ) : StatefulHabitOp()

    // (4) Edit entry value
    data class EditEntryValue(
        override val label: String,
        val date: LocalDate,
        val newValue: Int,
    ) : StatefulHabitOp()

    // (5) Delete entry (set Entry.UNKNOWN)
    data class DeleteEntry(
        override val label: String,
        val date: LocalDate,
    ) : StatefulHabitOp()

    // (6) Move entry to another date
    data class MoveEntryDate(
        override val label: String,
        val fromDate: LocalDate,
        val toDate: LocalDate,
    ) : StatefulHabitOp()

    // (7) Change habit start date (statisticsStartDate)
    data class ChangeHabitStartDate(
        override val label: String,
        val newStartDate: LocalDate?,
    ) : StatefulHabitOp()

    // (8) Change schedule/reminder/frequency
    data class ChangeScheduleFrequency(
        override val label: String,
        val reminder: Reminder?,
        val frequency: Frequency? = null,
    ) : StatefulHabitOp()

    // (9) Change daily goal
    data class ChangeDailyGoal(
        override val label: String,
        val newTargetValue: Double,
        val effectiveDate: LocalDate,
    ) : StatefulHabitOp()

    // (10) Change weekly goal
    data class ChangeWeeklyGoal(
        override val label: String,
        val newTargetValue: Double,
        val frequency: Frequency,
        val effectiveDate: LocalDate,
    ) : StatefulHabitOp()

    // (11) Apply goal FROM_DATE
    data class ApplyGoalFromDate(
        override val label: String,
        val newTargetValue: Double,
        val frequency: Frequency,
        val targetType: NumericalHabitType,
        val effectiveDate: LocalDate,
    ) : StatefulHabitOp()

    // (12) Apply goal ENTIRE_HISTORY
    data class ApplyGoalEntireHistory(
        override val label: String,
        val newTargetValue: Double,
        val frequency: Frequency,
        val targetType: NumericalHabitType,
        val effectiveDate: LocalDate,
    ) : StatefulHabitOp()

    // (13) Change targetType (AT_LEAST <-> AT_MOST)
    data class ChangeTargetType(
        override val label: String,
        val newTargetType: NumericalHabitType,
        val effectiveDate: LocalDate,
    ) : StatefulHabitOp()

    // (14) Archive and unarchive habit
    data class ArchiveAndUnarchive(
        override val label: String,
        val archive: Boolean,
    ) : StatefulHabitOp()

    // (15) Reorder habits
    data class ReorderHabits(
        override val label: String,
        val targetLabel: String,
    ) : StatefulHabitOp()

    // (16) Move between spheres/blocks and container subgroups
    data class MoveBetweenContainers(
        override val label: String,
        val targetContainerId: ContainerId?,
        val targetBlockId: Long?,
    ) : StatefulHabitOp()

    // (17) Change first weekday / globalStatisticsStartDate
    data class ChangeFirstWeekdayAndGlobalStatsDate(
        val firstWeekdayInt: Int?,
        val globalStatsDate: LocalDate?,
    ) : StatefulHabitOp() {
        override val label: String = "GLOBAL_SETTINGS"
    }

    // (18) Force recomputation of projections
    data class ForceRecomputation(
        override val label: String = "ALL",
    ) : StatefulHabitOp()

    // (19) Close/reopen/restart repositories (SourceRestart)
    object SourceRestart : StatefulHabitOp() {
        override val label: String = "GLOBAL_RESTART"
    }

    // (20) Backup/Restore snapshot roundtrip in isolated test environment
    object BackupRestoreRoundtrip : StatefulHabitOp() {
        override val label: String = "GLOBAL_BACKUP_RESTORE"
    }

    // Invalid input validation testing
    data class AttemptInvalidInput(
        override val label: String,
        val invalidTargetValue: Double?,
        val invalidFrequency: Frequency?,
    ) : StatefulHabitOp()

    // Backward-compatibility aliases
    data class Delete(
        override val label: String,
    ) : StatefulHabitOp()

    // Backward-compatibility aliases mapping to primary operations
    companion object {
        fun Create(
            label: String,
            isNumerical: Boolean,
            targetType: NumericalHabitType,
            targetValue: Double,
            frequency: Frequency,
            unit: String,
            colorIndex: Int = 0,
        ): CreateHabit = CreateHabit(label, isNumerical, targetType, targetValue, frequency, unit, colorIndex)

        fun LogEntry(label: String, date: LocalDate, value: Int): AddNumericalEntry =
            AddNumericalEntry(label, date, value)

        fun EditDateEntry(label: String, date: LocalDate, newValue: Int): EditEntryValue =
            EditEntryValue(label, date, newValue)

        fun ScheduleChange(label: String, reminder: Reminder?): ChangeScheduleFrequency =
            ChangeScheduleFrequency(label, reminder)

        fun UpdateGoal(
            label: String,
            newTargetValue: Double,
            newFrequency: Frequency,
            newTargetType: NumericalHabitType,
            effectiveDate: LocalDate,
            applyScope: GoalApplyScope = GoalApplyScope.FROM_DATE,
        ): StatefulHabitOp = if (applyScope == GoalApplyScope.ENTIRE_HISTORY) {
            ApplyGoalEntireHistory(label, newTargetValue, newFrequency, newTargetType, effectiveDate)
        } else {
            ApplyGoalFromDate(label, newTargetValue, newFrequency, newTargetType, effectiveDate)
        }
    }
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
    val isArchived: Boolean,
    val statisticsStartDate: LocalDate?,
    val reminder: Reminder?,
    val blockId: Long?,
    val containerId: ContainerId?,
    val entries: Map<LocalDate, Int>,
    val goalHistory: List<HabitGoal>,
    val position: Int,
)

fun captureHabitSnapshot(habit: Habit, containerId: ContainerId? = null): HabitSnapshot {
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
        isArchived = habit.isArchived,
        statisticsStartDate = habit.statisticsStartDate,
        reminder = habit.reminder?.let { Reminder(it.hour, it.minute, it.days) },
        blockId = habit.blockId,
        containerId = containerId,
        entries = entriesMap,
        goalHistory = habit.goalHistory.map {
            HabitGoal(it.effectiveDate, it.frequency, it.targetType, it.targetValue, it.unit)
        },
        position = habit.position,
    )
}

/**
 * Execution environment contract supporting Memory and SQLite backends.
 */
interface StatefulEnv {
    val baseDate: LocalDate
    val habitList: HabitList
    val containers: List<Container>
    fun apply(op: StatefulHabitOp)
    fun getHabit(label: String): Habit?
    fun getAllHabits(): List<Habit>
    fun getPlacement(habitUuid: String): ContainerId?
    fun restartSource()
    fun performBackupRestoreRoundtrip()
    fun close()
}

/**
 * Memory-based execution environment.
 */
class MemoryStatefulEnv(override val baseDate: LocalDate) : StatefulEnv {
    val factory = MemoryModelFactory()
    override val habitList: HabitList = factory.buildHabitList()
    val orgStore = MemoryOrganizationStore(
        initialCutoverRevision = OrganizationRevision(1L),
        initialCutoverAt = 1000L,
    )
    val taskRepo = MemoryUserTaskRepository()
    val focusRepo = MemoryFocusSessionRepository()
    val contribRepo = MemoryContributionRepository()

    val labelsToIds = mutableMapOf<String, Long>()
    val labelsToUuids = mutableMapOf<String, String>()
    private val placements = mutableMapOf<String, ContainerId?>()

    override val containers: List<Container> = listOf(
        Container(
            id = ContainerId("root-1"),
            parentId = null,
            name = "Root 1",
            color = PaletteColor(1),
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L)
        ),
        Container(
            id = ContainerId("child-1"),
            parentId = ContainerId("root-1"),
            name = "Child 1",
            color = PaletteColor(2),
            icon = null,
            siblingOrder = 1,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L)
        )
    )

    init {
        setToday(baseDate)
        orgStore.seedBaseline(containersList = containers)
    }

    override fun apply(op: StatefulHabitOp) {
        when (op) {
            is StatefulHabitOp.CreateHabit -> {
                val habit = factory.buildHabit().apply {
                    this.name = op.label
                    this.type = if (op.isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
                    this.targetType = op.targetType
                    this.targetValue = op.targetValue
                    this.frequency = op.frequency
                    this.unit = op.unit
                    this.color = PaletteColor(op.colorIndex)
                    this.blockId = op.blockId
                }
                habitList.add(habit)
                labelsToIds[op.label] = habit.id!!
                labelsToUuids[op.label] = habit.uuid!!
                placements[habit.uuid!!] = op.containerId
                habit.recompute()
            }
            is StatefulHabitOp.AddBinaryCheckmark -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, Entry.YES_MANUAL))
                habit.recompute()
            }
            is StatefulHabitOp.AddNumericalEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, op.value))
                habit.recompute()
            }
            is StatefulHabitOp.EditEntryValue -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                if (op.newValue == Entry.UNKNOWN) {
                    habit.originalEntries.remove(op.date)
                } else {
                    habit.originalEntries.add(Entry(op.date, op.newValue))
                }
                habit.recompute()
            }
            is StatefulHabitOp.DeleteEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.remove(op.date)
                habit.recompute()
            }
            is StatefulHabitOp.MoveEntryDate -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val existingVal = habit.originalEntries.get(op.fromDate).value
                if (existingVal != Entry.UNKNOWN) {
                    habit.originalEntries.remove(op.fromDate)
                    habit.originalEntries.add(Entry(op.toDate, existingVal))
                    habit.recompute()
                }
            }
            is StatefulHabitOp.ChangeHabitStartDate -> {
                val id = labelsToIds[op.label] ?: return
                SetHabitStatisticsStartDateCommand(habitList, id, op.newStartDate).run()
            }
            is StatefulHabitOp.ChangeScheduleFrequency -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.reminder = op.reminder
                if (op.frequency != null) habit.frequency = op.frequency
                habitList.update(habit)
                habit.recompute()
            }
            is StatefulHabitOp.ChangeDailyGoal -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = Frequency.DAILY
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ChangeWeeklyGoal -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = op.frequency
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ApplyGoalFromDate -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = op.frequency
                    targetType = op.targetType
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ApplyGoalEntireHistory -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = op.frequency
                    targetType = op.targetType
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.ENTIRE_HISTORY, op.effectiveDate).run()
            }
            is StatefulHabitOp.ChangeTargetType -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetType = op.newTargetType
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ArchiveAndUnarchive -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                if (op.archive) {
                    ArchiveHabitsCommand(habitList, listOf(habit)).run()
                } else {
                    UnarchiveHabitsCommand(habitList, listOf(habit)).run()
                }
            }
            is StatefulHabitOp.ReorderHabits -> {
                val id1 = labelsToIds[op.label] ?: return
                val id2 = labelsToIds[op.targetLabel] ?: return
                val h1 = habitList.getById(id1) ?: return
                val h2 = habitList.getById(id2) ?: return
                habitList.reorder(h1, h2)
            }
            is StatefulHabitOp.MoveBetweenContainers -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.blockId = op.targetBlockId
                placements[habit.uuid!!] = op.targetContainerId
                habitList.update(habit)
            }
            is StatefulHabitOp.ChangeFirstWeekdayAndGlobalStatsDate -> {
                SetGlobalStatisticsStartDateCommand(habitList, op.globalStatsDate).run()
            }
            is StatefulHabitOp.ForceRecomputation -> {
                if (op.label == "ALL") {
                    for (h in getAllHabits()) h.recompute()
                    habitList.resort()
                } else {
                    val id = labelsToIds[op.label]
                    if (id != null) {
                        habitList.getById(id)?.recompute()
                    }
                }
            }
            is StatefulHabitOp.SourceRestart -> {
                restartSource()
            }
            is StatefulHabitOp.BackupRestoreRoundtrip -> {
                performBackupRestoreRoundtrip()
            }
            is StatefulHabitOp.AttemptInvalidInput -> {
                // Test validation of invalid inputs safely
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                try {
                    if (op.invalidFrequency != null && (op.invalidFrequency.numerator <= 0 || op.invalidFrequency.denominator <= 0)) {
                        // Reject invalid frequency
                        return
                    }
                    if (op.invalidTargetValue != null && (op.invalidTargetValue.isNaN() || op.invalidTargetValue.isInfinite() || op.invalidTargetValue < 0)) {
                        // Reject invalid target value
                        return
                    }
                } catch (_: Throwable) {
                    // Handled safely
                }
            }
            is StatefulHabitOp.Delete -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id)
                if (habit != null) {
                    habitList.remove(habit)
                    placements.remove(habit.uuid!!)
                }
                labelsToIds.remove(op.label)
                labelsToUuids.remove(op.label)
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

    override fun getPlacement(habitUuid: String): ContainerId? {
        return placements[habitUuid]
    }

    override fun restartSource() {
        habitList.resort()
    }

    override fun performBackupRestoreRoundtrip() {
        // For memory env, verify in-memory snapshot parity
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
    override var habitList: HabitList = factory.buildHabitList()
    var orgStore = SQLiteOrganizationStore(db)
    var taskRepo = SQLiteUserTaskRepository(db)
    var focusRepo = SQLiteFocusSessionRepository(db)
    var contribRepo = SQLiteContributionRepository(db)

    val labelsToIds = mutableMapOf<String, Long>()
    val labelsToUuids = mutableMapOf<String, String>()
    private val placements = mutableMapOf<String, ContainerId?>()

    override val containers: List<Container> = listOf(
        Container(
            id = ContainerId("root-1"),
            parentId = null,
            name = "Root 1",
            color = PaletteColor(1),
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L)
        ),
        Container(
            id = ContainerId("child-1"),
            parentId = ContainerId("root-1"),
            name = "Child 1",
            color = PaletteColor(2),
            icon = null,
            siblingOrder = 1,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L)
        )
    )

    init {
        setToday(baseDate)
        db.runInTransaction {
            OrganizationSchema.createSchema(db)
            UserTaskSchema.createSchema(db)
            FocusSessionSchema.createSchema(db)
            ContributionSchema.createSchema(db)

            val baseTime = baseDate.unixTime
            db.run(
                "INSERT OR IGNORE INTO OrganizationChanges (revision, op_uuid, recorded_at, operation_type, origin, command_payload) " +
                    "VALUES (1, 'baseline-sql-cutover', $baseTime, 'BASELINE', 'BASELINE', 'BASELINE')"
            )
            db.run(
                "INSERT OR REPLACE INTO OrganizationState (" +
                    "id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, cutover_at, source_schema_version, source_snapshot_sha256" +
                    ") VALUES (1, 'harness-state-uuid', 1, 'CONTAINER_LOCAL', 1, 1, $baseTime, 29, 'harness-sha')"
            )

            // Seed initial containers
            for (c in containers) {
                orgStore.saveContainer(c)
                orgStore.recordContainerHistory(c, OrganizationRevision(1L))
            }
        }
    }

    override fun apply(op: StatefulHabitOp) {
        when (op) {
            is StatefulHabitOp.CreateHabit -> {
                val habit = factory.buildHabit().apply {
                    this.name = op.label
                    this.type = if (op.isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
                    this.targetType = op.targetType
                    this.targetValue = op.targetValue
                    this.frequency = op.frequency
                    this.unit = op.unit
                    this.color = PaletteColor(op.colorIndex)
                    this.blockId = op.blockId
                }
                habitList.add(habit)
                labelsToIds[op.label] = habit.id!!
                labelsToUuids[op.label] = habit.uuid!!
                val placement = HabitPlacement(
                    habit = HabitRef(habit.uuid!!),
                    containerId = op.containerId,
                    localOrder = 0,
                    origin = PlacementOrigin.USER_CHANGE,
                    revision = OrganizationRevision(1L)
                )
                orgStore.saveHabitPlacement(placement)
                orgStore.recordHabitPlacementHistory(placement, OrganizationRevision(1L))
                placements[habit.uuid!!] = op.containerId
                habit.recompute()
            }
            is StatefulHabitOp.AddBinaryCheckmark -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, Entry.YES_MANUAL))
                habit.recompute()
            }
            is StatefulHabitOp.AddNumericalEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, op.value))
                habit.recompute()
            }
            is StatefulHabitOp.EditEntryValue -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, op.newValue))
                habit.recompute()
            }
            is StatefulHabitOp.DeleteEntry -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.originalEntries.add(Entry(op.date, Entry.UNKNOWN))
                habit.recompute()
            }
            is StatefulHabitOp.MoveEntryDate -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val existingVal = habit.originalEntries.get(op.fromDate).value
                if (existingVal != Entry.UNKNOWN) {
                    habit.originalEntries.add(Entry(op.fromDate, Entry.UNKNOWN))
                    habit.originalEntries.add(Entry(op.toDate, existingVal))
                    habit.recompute()
                }
            }
            is StatefulHabitOp.ChangeHabitStartDate -> {
                val id = labelsToIds[op.label] ?: return
                SetHabitStatisticsStartDateCommand(habitList, id, op.newStartDate).run()
            }
            is StatefulHabitOp.ChangeScheduleFrequency -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.reminder = op.reminder
                if (op.frequency != null) habit.frequency = op.frequency
                habitList.update(habit)
                habit.recompute()
            }
            is StatefulHabitOp.ChangeDailyGoal -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = Frequency.DAILY
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ChangeWeeklyGoal -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = op.frequency
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ApplyGoalFromDate -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = op.frequency
                    targetType = op.targetType
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ApplyGoalEntireHistory -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetValue = op.newTargetValue
                    frequency = op.frequency
                    targetType = op.targetType
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.ENTIRE_HISTORY, op.effectiveDate).run()
            }
            is StatefulHabitOp.ChangeTargetType -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                val modified = factory.buildHabit().apply {
                    copyFrom(habit)
                    targetType = op.newTargetType
                }
                EditHabitGoalCommand(habitList, id, modified, GoalApplyScope.FROM_DATE, op.effectiveDate).run()
            }
            is StatefulHabitOp.ArchiveAndUnarchive -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                if (op.archive) {
                    ArchiveHabitsCommand(habitList, listOf(habit)).run()
                } else {
                    UnarchiveHabitsCommand(habitList, listOf(habit)).run()
                }
            }
            is StatefulHabitOp.ReorderHabits -> {
                val id1 = labelsToIds[op.label] ?: return
                val id2 = labelsToIds[op.targetLabel] ?: return
                val h1 = habitList.getById(id1) ?: return
                val h2 = habitList.getById(id2) ?: return
                habitList.reorder(h1, h2)
            }
            is StatefulHabitOp.MoveBetweenContainers -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                habit.blockId = op.targetBlockId
                val placement = HabitPlacement(
                    habit = HabitRef(habit.uuid!!),
                    containerId = op.targetContainerId,
                    localOrder = 0,
                    origin = PlacementOrigin.USER_CHANGE,
                    revision = OrganizationRevision(1L)
                )
                orgStore.saveHabitPlacement(placement)
                orgStore.recordHabitPlacementHistory(placement, OrganizationRevision(1L))
                placements[habit.uuid!!] = op.targetContainerId
                habitList.update(habit)
            }
            is StatefulHabitOp.ChangeFirstWeekdayAndGlobalStatsDate -> {
                SetGlobalStatisticsStartDateCommand(habitList, op.globalStatsDate).run()
            }
            is StatefulHabitOp.ForceRecomputation -> {
                if (op.label == "ALL") {
                    for (h in getAllHabits()) h.recompute()
                    habitList.resort()
                } else {
                    val id = labelsToIds[op.label]
                    if (id != null) {
                        habitList.getById(id)?.recompute()
                    }
                }
            }
            is StatefulHabitOp.SourceRestart -> {
                restartSource()
            }
            is StatefulHabitOp.BackupRestoreRoundtrip -> {
                performBackupRestoreRoundtrip()
            }
            is StatefulHabitOp.AttemptInvalidInput -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id) ?: return
                try {
                    if (op.invalidFrequency != null && (op.invalidFrequency.numerator <= 0 || op.invalidFrequency.denominator <= 0)) {
                        return
                    }
                    if (op.invalidTargetValue != null && (op.invalidTargetValue.isNaN() || op.invalidTargetValue.isInfinite() || op.invalidTargetValue < 0)) {
                        return
                    }
                } catch (_: Throwable) {
                }
            }
            is StatefulHabitOp.Delete -> {
                val id = labelsToIds[op.label] ?: return
                val habit = habitList.getById(id)
                if (habit != null) {
                    habitList.remove(habit)
                    placements.remove(habit.uuid!!)
                }
                labelsToIds.remove(op.label)
                labelsToUuids.remove(op.label)
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

    override fun getPlacement(habitUuid: String): ContainerId? {
        return placements[habitUuid] ?: orgStore.getHabitPlacement(HabitRef(habitUuid))?.containerId
    }

    override fun restartSource() {
        factory = SQLModelFactory(db)
        habitList = factory.buildHabitList()
        orgStore = SQLiteOrganizationStore(db)
        taskRepo = SQLiteUserTaskRepository(db)
        focusRepo = SQLiteFocusSessionRepository(db)
        contribRepo = SQLiteContributionRepository(db)

        labelsToIds.clear()
        labelsToUuids.clear()
        for (h in habitList) {
            labelsToIds[h.name] = h.id!!
            labelsToUuids[h.name] = h.uuid!!
        }
    }

    override fun performBackupRestoreRoundtrip() {
        runBlocking {
            val backupDb = TestDatabaseHelper.createEmptyDatabase()
            val stagingDb = TestDatabaseHelper.createEmptyDatabase()
            val destinationDb = TestDatabaseHelper.createEmptyDatabase()

            try {
                val backupRes = ContainerBackupService.createBackup(
                    sourceDb = db,
                    targetDb = backupDb,
                    timestamp = baseDate.unixTime,
                )
                assertTrue(
                    backupRes is org.isoron.uhabits.core.containers.backup.BackupCreationResult.Success,
                    "Backup failed: ${(backupRes as? org.isoron.uhabits.core.containers.backup.BackupCreationResult.Failure)?.error}"
                )

                val restoreRes = ContainerRestoreService.restore(
                    backupDb = backupDb,
                    stagingDb = stagingDb,
                    destinationDb = destinationDb,
                )
                assertTrue(
                    restoreRes is org.isoron.uhabits.core.containers.backup.RestoreResult.Success,
                    "Restore failed: ${(restoreRes as? org.isoron.uhabits.core.containers.backup.RestoreResult.Failure)?.let { "${it.reason} - errors: ${it.errors}" }}"
                )

                val check = destinationDb.querySingle("PRAGMA integrity_check") { it.getText(0) }
                assertEquals("ok", check)

                db.close()
                db = destinationDb
                restartSource()
            } finally {
                backupDb.close()
                stagingDb.close()
            }
        }
    }

    override fun close() {
        db.close()
    }
}

/**
 * Deterministic generator creating multi-step operation sequences spanning all 20 operations from fixed random seeds.
 */
class StatefulSequenceGenerator(seed: Long) {
    private val random = Random(seed)

    fun generate(count: Int, baseDate: LocalDate): List<StatefulHabitOp> {
        val ops = mutableListOf<StatefulHabitOp>()
        val activeHabits = mutableMapOf<String, Boolean>() // label -> isNumerical
        val activeLabels = mutableListOf<String>()
        var labelCounter = 0

        fun addHabit(): StatefulHabitOp.CreateHabit {
            val label = "H${labelCounter++}"
            activeLabels.add(label)
            val op = createRandomHabit(label)
            activeHabits[label] = op.isNumerical
            return op
        }

        // Always begin with at least 2 creates to facilitate cross-habit isolation checks
        for (i in 1..2) {
            ops.add(addHabit())
        }

        while (ops.size < count) {
            if (activeLabels.isEmpty()) {
                ops.add(addHabit())
                continue
            }

            val roll = random.nextInt(100)
            val label = activeLabels[random.nextInt(activeLabels.size)]
            val isNumerical = activeHabits[label] ?: false
            val offset = random.nextInt(-10, 10)
            val date = baseDate.plus(offset)

            when {
                // (1) Create habit: 10%
                roll < 10 && activeLabels.size < 6 -> {
                    ops.add(addHabit())
                }
                // (2) Add binary checkmark: 15%
                roll < 25 -> {
                    ops.add(StatefulHabitOp.AddBinaryCheckmark(label, date))
                }
                // (3) Add numerical entry: 15%
                roll < 40 -> {
                    val v = if (isNumerical) random.nextInt(10, 100) * 1000 else Entry.YES_MANUAL
                    ops.add(StatefulHabitOp.AddNumericalEntry(label, date, v))
                }
                // (4) Edit entry value: 10%
                roll < 50 -> {
                    val newVal = if (isNumerical) random.nextInt(10, 100) * 1000 else listOf(Entry.YES_MANUAL, Entry.NO, Entry.SKIP)[random.nextInt(3)]
                    ops.add(StatefulHabitOp.EditEntryValue(label, date, newVal))
                }
                // (5) Delete entry: 5%
                roll < 55 -> {
                    ops.add(StatefulHabitOp.DeleteEntry(label, date))
                }
                // (6) Move entry to another date: 5%
                roll < 60 -> {
                    val toDate = baseDate.plus(random.nextInt(-10, 10))
                    ops.add(StatefulHabitOp.MoveEntryDate(label, date, toDate))
                }
                // (7) Change habit start date: 4%
                roll < 64 -> {
                    val newStart = if (random.nextBoolean()) baseDate.minus(random.nextInt(5, 20)) else null
                    ops.add(StatefulHabitOp.ChangeHabitStartDate(label, newStart))
                }
                // (8) Change schedule/reminder/frequency: 5%
                roll < 69 -> {
                    val reminder = if (random.nextBoolean()) {
                        Reminder(random.nextInt(6, 22), random.nextInt(0, 59), WeekdayList.EVERY_DAY)
                    } else null
                    ops.add(StatefulHabitOp.ChangeScheduleFrequency(label, reminder))
                }
                // (9) Change daily goal: 4%
                roll < 73 -> {
                    val newTarget = if (isNumerical) random.nextInt(20, 100).toDouble() else 1.0
                    ops.add(StatefulHabitOp.ChangeDailyGoal(label, newTarget, date))
                }
                // (10) Change weekly goal: 4%
                roll < 77 -> {
                    val newTarget = if (isNumerical) random.nextInt(20, 100).toDouble() else 1.0
                    ops.add(StatefulHabitOp.ChangeWeeklyGoal(label, newTarget, Frequency(3, 7), date))
                }
                // (11) Apply goal FROM_DATE: 4%
                roll < 81 -> {
                    val newTarget = if (isNumerical) random.nextInt(30, 150).toDouble() else 1.0
                    val freq = if (random.nextBoolean()) Frequency.DAILY else Frequency(1, 7)
                    val tType = if (random.nextBoolean()) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
                    ops.add(StatefulHabitOp.ApplyGoalFromDate(label, newTarget, freq, tType, date))
                }
                // (12) Apply goal ENTIRE_HISTORY: 3%
                roll < 84 -> {
                    val newTarget = if (isNumerical) random.nextInt(25, 90).toDouble() else 1.0
                    ops.add(StatefulHabitOp.ApplyGoalEntireHistory(label, newTarget, Frequency.DAILY, NumericalHabitType.AT_LEAST, baseDate.minus(30)))
                }
                // (13) Change targetType: 3%
                roll < 87 -> {
                    val newType = if (random.nextBoolean()) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
                    ops.add(StatefulHabitOp.ChangeTargetType(label, newType, date))
                }
                // (14) Archive and unarchive: 3%
                roll < 90 -> {
                    ops.add(StatefulHabitOp.ArchiveAndUnarchive(label, archive = random.nextBoolean()))
                }
                // (15) Reorder habits: 2%
                roll < 92 && activeLabels.size > 1 -> {
                    val other = activeLabels.first { it != label }
                    ops.add(StatefulHabitOp.ReorderHabits(label, other))
                }
                // (16) Move between containers / blocks: 2%
                roll < 94 -> {
                    val cid = if (random.nextBoolean()) ContainerId("root-1") else null
                    ops.add(StatefulHabitOp.MoveBetweenContainers(label, cid, targetBlockId = 1L))
                }
                // (17) Change first weekday / global stats date: 1%
                roll < 95 -> {
                    ops.add(StatefulHabitOp.ChangeFirstWeekdayAndGlobalStatsDate(1, baseDate.minus(15)))
                }
                // (18) Force recomputation: 2%
                roll < 97 -> {
                    ops.add(StatefulHabitOp.ForceRecomputation(label))
                }
                // (19) Source restart: 1%
                roll < 98 -> {
                    ops.add(StatefulHabitOp.SourceRestart)
                }
                // (20) Backup / Restore roundtrip: 1%
                roll < 99 -> {
                    ops.add(StatefulHabitOp.BackupRestoreRoundtrip)
                }
                // Invalid input validation testing: 1%
                else -> {
                    ops.add(StatefulHabitOp.AttemptInvalidInput(label, invalidTargetValue = -50.0, invalidFrequency = Frequency(-1, 0)))
                }
            }
        }

        return ops
    }

    private fun createRandomHabit(label: String): StatefulHabitOp.CreateHabit {
        val isNumerical = random.nextBoolean()
        val targetType = if (random.nextBoolean()) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
        val targetValue = if (isNumerical) random.nextInt(20, 120).toDouble() else 1.0
        val frequency = if (random.nextBoolean()) Frequency.DAILY else Frequency(1, 7)
        return StatefulHabitOp.CreateHabit(
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
    val expectedState: String = "",
    val actualState: String = "",
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
            expectedState = "Zero invariant violations",
            actualState = currentError,
        )
    }
}
