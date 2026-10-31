/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.stateful

import org.isoron.platform.io.Database
import org.isoron.platform.io.run
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.isoron.uhabits.core.userTasks.toIsoDateString
import kotlin.random.Random

/**
 * Standard dataset profiles covering small, medium, large, and extreme workloads.
 */
enum class DatasetProfile(
    val habitCount: Int,
    val daySpan: Int,
    val containerCount: Int,
    val taskCount: Int,
    val focusSessionCount: Int,
) {
    SMALL(habitCount = 10, daySpan = 30, containerCount = 4, taskCount = 15, focusSessionCount = 20),
    MEDIUM(habitCount = 100, daySpan = 365, containerCount = 15, taskCount = 150, focusSessionCount = 250),
    LARGE(habitCount = 500, daySpan = 1095, containerCount = 40, taskCount = 600, focusSessionCount = 1000),
    EXTREME(habitCount = 1000, daySpan = 1825, containerCount = 80, taskCount = 1200, focusSessionCount = 2000),
}

/**
 * Configuration driving deterministic generation of synthetic datasets.
 */
data class DatasetConfig(
    val profile: DatasetProfile,
    val baseDate: LocalDate = LocalDate(2026, 10, 31),
    val ultraDenseRatio: Double = 0.05,
    val emptyRatio: Double = 0.10,
    val numericalRatio: Double = 0.50,
    val atMostRatio: Double = 0.25,
    val archivedRatio: Double = 0.15,
)

/**
 * Serializable / immutable representation of synthetic habit definition and its history.
 */
data class SyntheticHabitData(
    val name: String,
    val uuid: String,
    val isNumerical: Boolean,
    val targetType: NumericalHabitType,
    val targetValue: Double,
    val frequency: Frequency,
    val unit: String,
    val colorIndex: Int,
    val isArchived: Boolean,
    val statisticsStartDate: LocalDate?,
    val goalHistory: List<HabitGoal>,
    val entries: Map<LocalDate, Int>,
    val reminder: Reminder?,
    val containerId: ContainerId?,
    val blockId: Long?,
) {
    fun toHabit(factory: org.isoron.uhabits.core.models.ModelFactory): Habit {
        return factory.buildHabit().apply {
            this.name = this@SyntheticHabitData.name
            this.uuid = this@SyntheticHabitData.uuid
            this.type = if (this@SyntheticHabitData.isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
            this.targetType = this@SyntheticHabitData.targetType
            this.targetValue = this@SyntheticHabitData.targetValue
            this.frequency = this@SyntheticHabitData.frequency
            this.unit = this@SyntheticHabitData.unit
            this.color = PaletteColor(this@SyntheticHabitData.colorIndex)
            this.isArchived = this@SyntheticHabitData.isArchived
            this.statisticsStartDate = this@SyntheticHabitData.statisticsStartDate
            this.reminder = this@SyntheticHabitData.reminder
            this.blockId = this@SyntheticHabitData.blockId
            this.goalHistory = this@SyntheticHabitData.goalHistory.toMutableList()
        }
    }
}

/**
 * Complete, self-consistent synthetic dataset containing habits, containers, placements,
 * tasks, focus sessions, and contributions.
 */
data class SyntheticDataset(
    val config: DatasetConfig,
    val seed: Long,
    val habits: List<SyntheticHabitData>,
    val containers: List<Container>,
    val placements: List<HabitPlacement>,
    val blocks: List<HabitBlock>,
    val userTasks: List<UserTask>,
    val focusSessions: List<FocusSession>,
    val contributions: List<Contribution>,
) {
    /**
     * Populates this dataset into an SQLite database with real SQLModelFactory repositories.
     */
    fun populate(db: Database, factory: SQLModelFactory) {
        org.isoron.platform.time.setToday(config.baseDate)
        db.runInTransaction {
            // 1. Ensure schemas
            OrganizationSchema.createSchema(db)
            UserTaskSchema.createSchema(db)
            FocusSessionSchema.createSchema(db)
            ContributionSchema.createSchema(db)

            // Baseline revision in OrganizationChanges and OrganizationState
            val baseTime = config.baseDate.unixTime
            db.run(
                "INSERT OR IGNORE INTO OrganizationChanges (revision, op_uuid, recorded_at, operation_type, origin, command_payload) " +
                    "VALUES (1, 'baseline-cutover', $baseTime, 'BASELINE', 'BASELINE', 'BASELINE')"
            )
            db.run(
                "INSERT OR REPLACE INTO OrganizationState (" +
                    "id, dataset_uuid, foundation_version, mode, cutover_revision, current_revision, cutover_at, source_schema_version, source_snapshot_sha256" +
                    ") VALUES (1, 'synthetic-dataset-uuid', 1, 'CONTAINER_LOCAL', 1, 1, $baseTime, 29, 'synthetic-sha')"
            )

            // 2. Insert blocks
            for (block in blocks) {
                factory.habitBlockRepository.insert(
                    HabitBlockData(
                        id = block.id,
                        name = block.name,
                        color = block.color.paletteIndex,
                        icon = block.icon,
                        position = block.position,
                        uuid = "block-${block.id}"
                    )
                )
            }

            // 3. Organization store: containers
            val orgStore = SQLiteOrganizationStore(db)
            for (container in containers) {
                orgStore.saveContainer(container)
                orgStore.recordContainerHistory(container, OrganizationRevision(1L))
            }

            // 4. Habits and entries
            val habitList = factory.buildHabitList()
            for (hData in habits) {
                val habit = hData.toHabit(factory)
                habitList.add(habit)
                for ((date, value) in hData.entries) {
                    habit.originalEntries.add(Entry(date, value))
                }
                habit.recompute()
            }

            // 5. Habit placements (now both containers and habits exist in DB)
            for (placement in placements) {
                orgStore.saveHabitPlacement(placement)
                orgStore.recordHabitPlacementHistory(placement, OrganizationRevision(1L))
            }

            // 5. Tasks
            val taskRepo = SQLiteUserTaskRepository(db)
            for (task in userTasks) {
                taskRepo.save(task)
            }

            // 6. Focus sessions
            val focusRepo = SQLiteFocusSessionRepository(db)
            for (session in focusSessions) {
                focusRepo.record(session)
            }

            // 7. Contributions
            val contribRepo = SQLiteContributionRepository(db)
            for (contrib in contributions) {
                contribRepo.record(contrib)
            }
        }
    }

    /**
     * Populates this dataset into an in-memory execution environment.
     */
    fun populate(
        factory: MemoryModelFactory,
        orgStore: MemoryOrganizationStore,
        taskRepo: MemoryUserTaskRepository,
        focusRepo: MemoryFocusSessionRepository,
        contribRepo: MemoryContributionRepository,
    ) {
        org.isoron.platform.time.setToday(config.baseDate)
        orgStore.seedBaseline(containersList = containers, placementsList = placements)
        val habitList = factory.buildHabitList()
        for (hData in habits) {
            val habit = hData.toHabit(factory)
            habitList.add(habit)
            for ((date, value) in hData.entries) {
                habit.originalEntries.add(Entry(date, value))
            }
            habit.recompute()
        }
        for (task in userTasks) {
            taskRepo.save(task)
        }
        for (session in focusSessions) {
            focusRepo.record(session)
        }
        for (contrib in contributions) {
            contribRepo.record(contrib)
        }
    }
}

/**
 * Generator producing fully reproducible, diverse, and realistic synthetic datasets.
 */
class SyntheticDatasetGenerator(private val config: DatasetConfig) {

    companion object {
        val SAMPLE_UNITS = listOf("min", "hr", "pages", "km", "kcal", "steps", "glasses", "")
        val SAMPLE_BLOCK_NAMES = listOf("Health & Fitness", "Work & Study", "Personal Growth", "Mindfulness")
        val SAMPLE_HABIT_NAMES = listOf(
            "Morning Run", "Read Book", "Meditation", "Drink Water", "Deep Work",
            "Gym Workout", "Guitar Practice", "Journaling", "Cold Shower", "Stretching",
            "Coding Practice", "Sleep 8 Hours", "No Sugar", "Language Learning", "Walking 10k",
            "Evening Walk", "Pomodoro Sessions", "Vegetable Intake", "Call Family", "Plan Tomorrow"
        )
    }

    fun generate(seed: Long): SyntheticDataset {
        val random = Random(seed)

        fun deterministicUuid(): String {
            val h = random.nextLong()
            val l = random.nextLong()
            return "%08x-%04x-%04x-%04x-%012x".format(
                (h ushr 32) and 0xFFFFFFFFL,
                (h ushr 16) and 0xFFFFL,
                h and 0xFFFFL,
                (l ushr 48) and 0xFFFFL,
                l and 0xFFFFFFFFFFFFL
            )
        }

        val baseDate = config.baseDate
        val daySpan = config.profile.daySpan
        val startDate = baseDate.minus(daySpan - 1)
        val nowEpochMs = baseDate.unixTime

        // 1. Generate Legacy Habit Blocks
        val blocks = SAMPLE_BLOCK_NAMES.mapIndexed { index, name ->
            HabitBlock(
                id = (index + 1).toLong(),
                name = name,
                color = PaletteColor(index % 12),
                icon = null,
                position = index,
            )
        }

        // 2. Generate Containers (Roots, Children, Grandchildren)
        val containerCount = config.profile.containerCount
        val containers = mutableListOf<Container>()
        val containerIds = mutableListOf<ContainerId>()

        // 3 to 6 root containers
        val rootCount = minOf(containerCount, maxOf(2, containerCount / 3))
        for (i in 0 until rootCount) {
            val cid = ContainerId(deterministicUuid())
            containerIds.add(cid)
            containers.add(
                Container(
                    id = cid,
                    parentId = null,
                    name = "Workspace ${i + 1}",
                    color = PaletteColor(i % 12),
                    icon = "folder",
                    siblingOrder = i,
                    isArchived = false,
                    createdAt = nowEpochMs - (daySpan.toLong() * 86400000L),
                    updatedAt = nowEpochMs,
                    deletedAt = null,
                    revision = OrganizationRevision(1L),
                )
            )
        }

        // Child / Grandchild containers
        for (i in rootCount until containerCount) {
            val cid = ContainerId(deterministicUuid())
            val parentId = containerIds[random.nextInt(containerIds.size)]
            containerIds.add(cid)
            containers.add(
                Container(
                    id = cid,
                    parentId = parentId,
                    name = "Section ${i + 1}",
                    color = PaletteColor((i + 3) % 12),
                    icon = "tag",
                    siblingOrder = i,
                    isArchived = random.nextDouble() < 0.10,
                    createdAt = nowEpochMs - (daySpan.toLong() * 86400000L),
                    updatedAt = nowEpochMs,
                    deletedAt = null,
                    revision = OrganizationRevision(1L),
                )
            )
        }

        // 3. Generate Habits
        val habitCount = config.profile.habitCount
        val habits = mutableListOf<SyntheticHabitData>()
        val placements = mutableListOf<HabitPlacement>()

        val ultraDenseCount = maxOf(1, (habitCount * config.ultraDenseRatio).toInt())
        val emptyCount = maxOf(1, (habitCount * config.emptyRatio).toInt())

        for (i in 0 until habitCount) {
            val habitUuid = deterministicUuid()
            val isNumerical = random.nextDouble() < config.numericalRatio
            val targetType = if (isNumerical) {
                if (random.nextDouble() < config.atMostRatio) NumericalHabitType.AT_MOST else NumericalHabitType.AT_LEAST
            } else {
                NumericalHabitType.AT_LEAST
            }

            val targetVal = if (isNumerical) {
                when (targetType) {
                    NumericalHabitType.AT_LEAST -> random.nextInt(15, 120).toDouble()
                    NumericalHabitType.AT_MOST -> random.nextInt(2, 10).toDouble()
                }
            } else 1.0

            val frequency = when (random.nextInt(10)) {
                in 0..5 -> Frequency.DAILY
                6 -> Frequency(1, 7)
                7 -> Frequency(3, 7)
                8 -> Frequency(5, 7)
                else -> Frequency(2, 3)
            }

            val unit = if (isNumerical) SAMPLE_UNITS[random.nextInt(SAMPLE_UNITS.size - 1)] else ""
            val colorIndex = random.nextInt(0, 12)
            val isArchived = random.nextDouble() < config.archivedRatio

            // Statistics start date
            val statsStartDate = when (random.nextInt(4)) {
                0 -> null
                1 -> startDate
                2 -> startDate.plus(daySpan / 2)
                else -> baseDate.minus(random.nextInt(1, minOf(30, maxOf(2, daySpan))))
            }

            // Goal history transitions (30% have 2 or 3 historical goals)
            val hasGoalHistory = random.nextDouble() < 0.30 && daySpan >= 10
            val goalHistory = mutableListOf<HabitGoal>()
            if (hasGoalHistory) {
                val epoch1 = startDate
                val epoch2 = startDate.plus(daySpan / 3)
                val epoch3 = startDate.plus((2 * daySpan) / 3)

                goalHistory.add(HabitGoal(epoch1, Frequency.DAILY, targetType, targetVal * 0.75, unit))
                goalHistory.add(HabitGoal(epoch2, frequency, targetType, targetVal, unit))
                if (epoch3 <= baseDate) {
                    goalHistory.add(HabitGoal(epoch3, frequency, targetType, targetVal * 1.25, unit))
                }
            } else {
                goalHistory.add(HabitGoal(startDate, frequency, targetType, targetVal, unit))
            }

            // Reminder
            val reminder = if (random.nextBoolean()) {
                Reminder(random.nextInt(6, 23), random.nextInt(0, 59), WeekdayList.EVERY_DAY)
            } else null

            // Block and container assignment
            val blockId = if (blocks.isNotEmpty() && random.nextDouble() < 0.8) {
                blocks[random.nextInt(blocks.size)].id
            } else null

            val containerId = if (containerIds.isNotEmpty() && random.nextDouble() < 0.75) {
                containerIds[random.nextInt(containerIds.size)]
            } else null

            // Generate entries according to density category
            val entries = mutableMapOf<LocalDate, Int>()
            val isUltraDense = i < ultraDenseCount
            val isEmpty = i >= (habitCount - emptyCount)

            if (!isEmpty) {
                var current = startDate
                while (current <= baseDate) {
                    val dateActive = statsStartDate == null || !current.isOlderThan(statsStartDate)
                    if (isUltraDense) {
                        // 95% logged, 3% skip, 2% unlogged
                        val roll = random.nextDouble()
                        if (roll < 0.95) {
                            val v = if (isNumerical) {
                                (targetVal * random.nextDouble(0.9, 1.4) * 1000).toInt()
                            } else Entry.YES_MANUAL
                            entries[current] = v
                        } else if (roll < 0.98) {
                            entries[current] = Entry.SKIP
                        }
                    } else if (dateActive) {
                        // Realistic density: streaks, gaps, skips, explicit 0
                        val roll = random.nextDouble()
                        when {
                            // 50% success
                            roll < 0.50 -> {
                                val v = if (isNumerical) {
                                    val factor = if (targetType == NumericalHabitType.AT_MOST) {
                                        random.nextDouble(0.4, 0.95) // stays below limit
                                    } else {
                                        random.nextDouble(0.8, 1.3)
                                    }
                                    (targetVal * factor * 1000).toInt()
                                } else Entry.YES_MANUAL
                                entries[current] = v
                            }
                            // 10% explicit zero / failed limit
                            roll < 0.60 -> {
                                val v = if (isNumerical) {
                                    if (targetType == NumericalHabitType.AT_MOST) {
                                        (targetVal * 1.5 * 1000).toInt() // exceeded limit
                                    } else 0
                                } else Entry.NO
                                entries[current] = v
                            }
                            // 10% skip
                            roll < 0.70 -> {
                                entries[current] = Entry.SKIP
                            }
                            // 30% unlogged (omitted from entries map)
                            else -> {}
                        }
                    }
                    current = current.plus(1)
                }
            } else {
                // Empty / near-empty habit: log at most 0 to 2 entries
                val entryCount = random.nextInt(0, 3)
                for (k in 0 until entryCount) {
                    val d = startDate.plus(random.nextInt(daySpan))
                    val v = if (isNumerical) (targetVal * 1000).toInt() else Entry.YES_MANUAL
                    entries[d] = v
                }
            }

            val habitName = if (i < SAMPLE_HABIT_NAMES.size) {
                SAMPLE_HABIT_NAMES[i]
            } else {
                "Habit #${i + 1}"
            }

            val habitData = SyntheticHabitData(
                name = habitName,
                uuid = habitUuid,
                isNumerical = isNumerical,
                targetType = targetType,
                targetValue = targetVal,
                frequency = frequency,
                unit = unit,
                colorIndex = colorIndex,
                isArchived = isArchived,
                statisticsStartDate = statsStartDate,
                goalHistory = goalHistory,
                entries = entries,
                reminder = reminder,
                containerId = containerId,
                blockId = blockId,
            )
            habits.add(habitData)

            // Habit placement
            placements.add(
                HabitPlacement(
                    habit = HabitRef(habitUuid),
                    containerId = containerId,
                    localOrder = i,
                    origin = PlacementOrigin.USER_CHANGE,
                    revision = OrganizationRevision(1L),
                )
            )
        }

        // 4. Generate UserTasks
        val taskCount = config.profile.taskCount
        val userTasks = mutableListOf<UserTask>()
        for (i in 0 until taskCount) {
            val taskId = TaskId(deterministicUuid())
            val isCompleted = random.nextDouble() < 0.35
            val offset = random.nextInt(-daySpan / 2, daySpan / 2)
            val taskDate = baseDate.plus(offset)
            val due = when (random.nextInt(3)) {
                0 -> TaskDue.None
                1 -> TaskDue.Day(TaskDueDate.parse(taskDate.toIsoDateString()))
                else -> TaskDue.Timed.of(taskDate.unixTime + (random.nextInt(8, 20) * 3600000L))
            }

            val containerId = if (containerIds.isNotEmpty() && random.nextDouble() < 0.70) {
                containerIds[random.nextInt(containerIds.size)]
            } else null

            val linkedHabitUuid = if (habits.isNotEmpty() && random.nextDouble() < 0.20) {
                habits[random.nextInt(habits.size)].uuid
            } else null

            userTasks.add(
                UserTask(
                    id = taskId,
                    title = "Task #${i + 1}: Implementation Step",
                    description = "Synthetic task generated for stateful validation",
                    containerId = containerId,
                    status = if (isCompleted) TaskStatus.COMPLETED else TaskStatus.PENDING,
                    due = due,
                    completedAt = if (isCompleted) nowEpochMs - random.nextInt(1000, 86400000) else null,
                    createdAt = nowEpochMs - (random.nextInt(1, daySpan).toLong() * 86400000L),
                    updatedAt = nowEpochMs,
                    important = random.nextBoolean(),
                    urgent = random.nextBoolean(),
                    linkedHabitUuid = linkedHabitUuid,
                    habitTimeAccrualEnabled = linkedHabitUuid != null,
                )
            )
        }

        // 5. Generate FocusSessions and Contributions
        val sessionCount = config.profile.focusSessionCount
        val focusSessions = mutableListOf<FocusSession>()
        val contributions = mutableListOf<Contribution>()

        for (i in 0 until sessionCount) {
            val sessionId = FocusSessionId(deterministicUuid())
            val sessionDate = startDate.plus(random.nextInt(daySpan))
            val startMillis = sessionDate.unixTime + (random.nextInt(8, 20) * 3600000L)
            val durationMinutes = listOf(15, 25, 30, 45, 50, 60)[random.nextInt(6)]
            val durationMs = durationMinutes * 60_000L
            val endMillis = startMillis + durationMs

            val targetRoll = random.nextInt(3)
            val targetTask = if (targetRoll == 0 && userTasks.isNotEmpty()) userTasks[random.nextInt(userTasks.size)] else null
            val targetHabit = if (targetRoll == 1 && habits.isNotEmpty()) habits[random.nextInt(habits.size)] else null
            val targetContainer = if (targetRoll == 2 && containerIds.isNotEmpty()) containerIds[random.nextInt(containerIds.size)] else null

            val session = FocusSession(
                id = sessionId,
                startedAt = startMillis,
                endedAt = endMillis,
                durationMs = durationMs,
                taskId = targetTask?.id,
                habit = targetHabit?.let { HabitRef(it.uuid) },
                containerId = targetContainer,
                source = if (random.nextBoolean()) FocusSessionSource.TIMER else FocusSessionSource.MANUAL,
                createdAt = endMillis,
                updatedAt = endMillis,
            )
            focusSessions.add(session)

            // Derive Contribution if targeted at Habit or linked task
            val habitToCredit = targetHabit?.uuid ?: targetTask?.linkedHabitUuid
            if (habitToCredit != null) {
                val contribId = ContributionId(deterministicUuid())
                contributions.add(
                    Contribution(
                        id = contribId,
                        targetHabit = HabitRef(habitToCredit),
                        sourceType = ContributionSourceType.FOCUS_SESSION,
                        sourceId = sessionId.value,
                        contributedValue = durationMinutes * 1000L, // x1000 scale
                        effectiveTimestamp = startMillis,
                        createdAt = endMillis,
                        updatedAt = endMillis,
                    )
                )
            }
        }

        return SyntheticDataset(
            config = config,
            seed = seed,
            habits = habits,
            containers = containers,
            placements = placements,
            blocks = blocks,
            userTasks = userTasks,
            focusSessions = focusSessions,
            contributions = contributions,
        )
    }
}
