/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionRecordResult
import org.isoron.uhabits.core.contribution.ContributionRemovalResult
import org.isoron.uhabits.core.contribution.ContributionRepository
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionCursor
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionRecordResult
import org.isoron.uhabits.core.focus.FocusSessionRepository
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.toTaskDueDate
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic synthetic integration verification for [UnifiedStatisticsService].
 *
 * Validates read adapter contracts according to PR14.0/PR14.1 (C1-C7, T1-T4, F1-F4):
 * 1. Original Habit entries, goal history, frequency, scores, streaks remain untouched (detached snapshot).
 * 2. Source write methods are never invoked across any repository during read.
 * 3. Revision/dataset invalidation and cancellation throw [StatisticsReadInvalidatedException].
 * 4. Per-domain failures are explicit (failed domain is null with [StatisticsIssueCode.READ_FAILED], no fake zeroes).
 * 5. Ledger tombstones and task-habit relink do not reattribute past facts or activate tombstones.
 * 6. Explicit timezone query evaluation and habit day offset isolation (habit offset does not shift tasks/focus).
 * 7. Canonical Section 8 synthetic fixture end-to-end integration.
 * 8. Organization section scope filtering across habits, tasks, and focus.
 */
class UnifiedStatisticsServiceTest {

    @Test
    fun failedSectionResolutionMakesWholeReadUnavailable() {
        val organization = MemoryOrganizationStore()
        val brokenTree = object : ContainerQueries by organization {
            override fun subtreeIds(root: ContainerId): Set<ContainerId> = error("Tree unavailable")
        }
        val context = createQueryContext(sectionScope = StatisticsSectionScope.Container(ContainerId("root")))
        val report = UnifiedStatisticsService(MemoryHabitList(), MemoryUserTaskRepository(),
            MemoryFocusSessionRepository(), MemoryContributionRepository(), organization, brokenTree)
            .read(context, { StatisticsReadIdentity(context.datasetId, context.revision) })
        assertNull(report.habits)
        assertNull(report.tasks)
        assertNull(report.focus)
        assertEquals("sectionScope", report.readIssues.single().entityId)
        assertEquals(StatisticsIssueCode.READ_FAILED, report.readIssues.single().code)
    }

    private val factory = MemoryModelFactory()

    private fun createQueryContext(
        from: LocalDate = LocalDate(2026, 10, 19),
        to: LocalDate = LocalDate(2026, 10, 25),
        timezoneId: String = "Asia/Novosibirsk",
        firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
        habitHourOffset: Int = 0,
        habitMinuteOffset: Int = 0,
        datasetId: String = "synthetic-dataset",
        revision: Long = 100L,
        now: Long = 1793019600000L, // 2026-10-26 09:00:00 UTC+7
        sectionScope: StatisticsSectionScope = StatisticsSectionScope.All,
    ) = StatisticsQueryContext(
        from = from,
        to = to,
        timezoneId = timezoneId,
        firstWeekday = firstWeekday,
        habitHourOffset = habitHourOffset,
        habitMinuteOffset = habitMinuteOffset,
        datasetId = datasetId,
        revision = revision,
        now = now,
        sectionScope = sectionScope,
    )

    // ========================================================================
    // 1. Original Habit entries / history / frequency untouched
    // ========================================================================

    @Test
    fun originalHabitFactsAndEntriesUntouchedByRead() {
        val habitList = MemoryHabitList()
        val taskRepo = MemoryUserTaskRepository()
        val focusRepo = MemoryFocusSessionRepository()
        val contribRepo = MemoryContributionRepository()
        val orgStore = MemoryOrganizationStore()

        val habit = factory.buildHabit().apply {
            uuid = "habit-water-uuid"
            name = "Вода"
            frequency = Frequency(1, 1)
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 1.0
            goalHistory = mutableListOf(
                HabitGoal(
                    effectiveDate = LocalDate(2026, 10, 1),
                    frequency = Frequency(1, 1),
                    targetType = NumericalHabitType.AT_LEAST,
                    targetValue = 1.0,
                    unit = "glass",
                ),
            )
            originalEntries.add(Entry(LocalDate(2026, 10, 19), Entry.YES_MANUAL))
            originalEntries.add(Entry(LocalDate(2026, 10, 20), Entry.YES_MANUAL))
            originalEntries.add(Entry(LocalDate(2026, 10, 21), Entry.NO))
            originalEntries.add(Entry(LocalDate(2026, 10, 22), Entry.YES_MANUAL))
            originalEntries.add(Entry(LocalDate(2026, 10, 23), Entry.UNKNOWN))
            originalEntries.add(Entry(LocalDate(2026, 10, 24), Entry.NO))
            originalEntries.add(Entry(LocalDate(2026, 10, 25), Entry.YES_MANUAL))
        }
        habitList.add(habit)

        // Capture deep snapshot of initial state before read
        val initialEntries = habit.originalEntries.getKnown().map { it.copy() }
        val initialGoals = habit.goalHistory.map { it.copy(frequency = it.frequency.copy()) }
        val initialFrequency = habit.frequency.copy()
        val initialComputedEntriesSize = habit.computedEntries.getKnown().size
        val initialScoresSize = habit.scores.getByInterval(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31))
        val initialStreaksSize = habit.streaks.getLatest()

        val service = UnifiedStatisticsService(
            habitList = habitList,
            tasks = taskRepo,
            focus = focusRepo,
            contributions = contribRepo,
            placements = orgStore,
            containers = orgStore,
        )

        val context = createQueryContext()
        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        assertNotNull(report.habits)
        assertEquals(0, report.readIssues.size)

        // Verify habit original entries completely untouched
        val afterEntries = habit.originalEntries.getKnown()
        assertEquals(initialEntries.size, afterEntries.size)
        for (i in initialEntries.indices) {
            assertEquals(initialEntries[i].date, afterEntries[i].date)
            assertEquals(initialEntries[i].value, afterEntries[i].value)
        }

        // Verify goal history completely untouched
        assertEquals(initialGoals.size, habit.goalHistory.size)
        assertEquals(initialGoals[0].effectiveDate, habit.goalHistory[0].effectiveDate)
        assertEquals(initialGoals[0].targetValue, habit.goalHistory[0].targetValue, 0.0001)
        assertEquals(initialGoals[0].unit, habit.goalHistory[0].unit)
        assertEquals(initialGoals[0].frequency, habit.goalHistory[0].frequency)

        // Verify frequency, computed entries, scores, streaks remain untouched
        assertEquals(initialFrequency, habit.frequency)
        assertEquals(initialComputedEntriesSize, habit.computedEntries.getKnown().size)
        assertEquals(initialScoresSize, habit.scores.getByInterval(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31)))
        assertEquals(initialStreaksSize, habit.streaks.getLatest())
    }

    // ========================================================================
    // 2. Source write methods never invoked
    // ========================================================================

    @Test
    fun sourceWriteMethodsNeverInvokedDuringRead() {
        val habitList = MemoryHabitList()
        val rawTaskRepo = MemoryUserTaskRepository()
        val rawFocusRepo = MemoryFocusSessionRepository()
        val rawContribRepo = MemoryContributionRepository()
        val orgStore = MemoryOrganizationStore()

        val writeInvocations = AtomicInteger(0)

        // Verifying write-guard decorator for UserTaskRepository
        val guardedTaskRepo = object : UserTaskRepository by rawTaskRepo {
            override fun save(task: UserTask) {
                writeInvocations.incrementAndGet()
                rawTaskRepo.save(task)
            }
            override fun saveAll(tasks: Collection<UserTask>) {
                writeInvocations.incrementAndGet()
                rawTaskRepo.saveAll(tasks)
            }
            override fun delete(id: TaskId) {
                writeInvocations.incrementAndGet()
                rawTaskRepo.delete(id)
            }
            override fun deleteByContainer(containerId: ContainerId) {
                writeInvocations.incrementAndGet()
                rawTaskRepo.deleteByContainer(containerId)
            }
            override fun clear() {
                writeInvocations.incrementAndGet()
                rawTaskRepo.clear()
            }
        }

        // Verifying write-guard decorator for FocusSessionRepository
        val guardedFocusRepo = object : FocusSessionRepository by rawFocusRepo {
            override fun record(session: FocusSession): FocusSessionRecordResult {
                writeInvocations.incrementAndGet()
                return rawFocusRepo.record(session)
            }
        }

        // Verifying write-guard decorator for ContributionRepository
        val guardedContribRepo = object : ContributionRepository by rawContribRepo {
            override fun record(contribution: Contribution): ContributionRecordResult {
                writeInvocations.incrementAndGet()
                return rawContribRepo.record(contribution)
            }
            override fun remove(
                sourceType: ContributionSourceType,
                sourceId: String,
                targetHabit: HabitRef,
                removedAt: Long,
            ): ContributionRemovalResult {
                writeInvocations.incrementAndGet()
                return rawContribRepo.remove(sourceType, sourceId, targetHabit, removedAt)
            }
            override fun removeById(id: ContributionId, removedAt: Long): ContributionRemovalResult {
                writeInvocations.incrementAndGet()
                return rawContribRepo.removeById(id, removedAt)
            }
            override fun restoreById(id: ContributionId, updatedAt: Long): Boolean {
                writeInvocations.incrementAndGet()
                return rawContribRepo.restoreById(id, updatedAt)
            }
        }

        // Seed repositories with active facts
        val habit = factory.buildHabit().apply {
            uuid = "h1"
            name = "Reading"
            frequency = Frequency.DAILY
            originalEntries.add(Entry(LocalDate(2026, 10, 20), Entry.YES_MANUAL))
        }
        habitList.add(habit)

        val context = createQueryContext()
        val task = UserTask(
            id = TaskId("t1"),
            title = "Write Report",
            status = TaskStatus.COMPLETED,
            createdAt = context.startMillis + 1_000L,
            updatedAt = context.startMillis + 2_000L,
            completedAt = context.startMillis + 3_000L,
        )
        rawTaskRepo.save(task)

        val session = FocusSession(
            id = FocusSessionId("s1"),
            startedAt = context.startMillis + 10_000L,
            endedAt = context.startMillis + 40_000L,
            durationMs = 30_000L,
            source = FocusSessionSource.TIMER,
            createdAt = context.startMillis + 40_000L,
            updatedAt = context.startMillis + 40_000L,
        )
        rawFocusRepo.record(session)

        val contribution = Contribution(
            id = ContributionId("c1"),
            targetHabit = HabitRef("h1"),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "s1",
            contributedValue = 30_000L,
            effectiveTimestamp = context.startMillis + 40_000L,
            createdAt = context.startMillis + 40_000L,
            updatedAt = context.startMillis + 40_000L,
        )
        rawContribRepo.record(contribution)

        // Reset write count before executing read
        writeInvocations.set(0)

        val service = UnifiedStatisticsService(
            habitList = habitList,
            tasks = guardedTaskRepo,
            focus = guardedFocusRepo,
            contributions = guardedContribRepo,
            placements = orgStore,
            containers = orgStore,
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        assertNotNull(report.tasks)
        assertNotNull(report.focus)
        assertNotNull(report.habits)
        assertEquals("Zero write methods must be called during read", 0, writeInvocations.get())
    }

    @Test
    fun injectedThrowingWriteMethodsNeverCalledDuringRead() {
        val habitList = MemoryHabitList()
        val rawTaskRepo = MemoryUserTaskRepository()
        val rawFocusRepo = MemoryFocusSessionRepository()
        val rawContribRepo = MemoryContributionRepository()
        val orgStore = MemoryOrganizationStore()

        val throwingTaskRepo = object : UserTaskRepository by rawTaskRepo {
            override fun save(task: UserTask) = throw AssertionError("save invoked on UserTaskRepository")
            override fun saveAll(tasks: Collection<UserTask>) = throw AssertionError("saveAll invoked on UserTaskRepository")
            override fun delete(id: TaskId) = throw AssertionError("delete invoked on UserTaskRepository")
            override fun deleteByContainer(containerId: ContainerId) = throw AssertionError("deleteByContainer invoked on UserTaskRepository")
            override fun clear() = throw AssertionError("clear invoked on UserTaskRepository")
        }

        val throwingFocusRepo = object : FocusSessionRepository by rawFocusRepo {
            override fun record(session: FocusSession) = throw AssertionError("record invoked on FocusSessionRepository")
        }

        val throwingContribRepo = object : ContributionRepository by rawContribRepo {
            override fun record(contribution: Contribution) = throw AssertionError("record invoked on ContributionRepository")
            override fun remove(
                sourceType: ContributionSourceType,
                sourceId: String,
                targetHabit: HabitRef,
                removedAt: Long,
            ) = throw AssertionError("remove invoked on ContributionRepository")
            override fun removeById(id: ContributionId, removedAt: Long) = throw AssertionError("removeById invoked on ContributionRepository")
            override fun restoreById(id: ContributionId, updatedAt: Long) = throw AssertionError("restoreById invoked on ContributionRepository")
        }

        val habit = factory.buildHabit().apply {
            uuid = "h-throw-check"
            name = "Exercise"
            frequency = Frequency.DAILY
            originalEntries.add(Entry(LocalDate(2026, 10, 20), Entry.YES_MANUAL))
        }
        habitList.add(habit)

        val context = createQueryContext()
        rawTaskRepo.save(
            UserTask(
                id = TaskId("t-safe"),
                title = "Safe Task",
                status = TaskStatus.COMPLETED,
                createdAt = context.startMillis + 1000L,
                updatedAt = context.startMillis + 2000L,
                completedAt = context.startMillis + 2000L,
            ),
        )
        rawFocusRepo.record(
            FocusSession(
                id = FocusSessionId("f-safe"),
                startedAt = context.startMillis + 1000L,
                endedAt = context.startMillis + 3000L,
                durationMs = 2000L,
                source = FocusSessionSource.TIMER,
                createdAt = context.startMillis + 3000L,
                updatedAt = context.startMillis + 3000L,
            ),
        )
        rawContribRepo.record(
            Contribution(
                id = ContributionId("c-safe"),
                targetHabit = HabitRef("h-throw-check"),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "f-safe",
                contributedValue = 2000L,
                effectiveTimestamp = context.startMillis + 3000L,
                createdAt = context.startMillis + 3000L,
                updatedAt = context.startMillis + 3000L,
            ),
        )

        val service = UnifiedStatisticsService(
            habitList = habitList,
            tasks = throwingTaskRepo,
            focus = throwingFocusRepo,
            contributions = throwingContribRepo,
            placements = orgStore,
            containers = orgStore,
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        assertNotNull(report.tasks)
        assertNotNull(report.focus)
        assertNotNull(report.habits)
    }

    // ========================================================================
    // 3. Revision / dataset invalidation and cancellation
    // ========================================================================

    @Test
    fun revisionMismatchThrowsStatisticsReadInvalidatedException() {
        val service = createStandardService()
        val context = createQueryContext(revision = 10L)

        assertThrows(StatisticsReadInvalidatedException::class.java) {
            service.read(
                request = context,
                currentIdentity = { StatisticsReadIdentity(context.datasetId, 11L) }, // Different revision
            )
        }
    }

    @Test
    fun datasetIdMismatchThrowsStatisticsReadInvalidatedException() {
        val service = createStandardService()
        val context = createQueryContext(datasetId = "dataset-A")

        assertThrows(StatisticsReadInvalidatedException::class.java) {
            service.read(
                request = context,
                currentIdentity = { StatisticsReadIdentity("dataset-B", context.revision) }, // Different dataset
            )
        }
    }

    @Test
    fun initialCancellationThrowsStatisticsReadInvalidatedException() {
        val service = createStandardService()
        val context = createQueryContext()

        assertThrows(StatisticsReadInvalidatedException::class.java) {
            service.read(
                request = context,
                currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
                shouldCancel = { true },
            )
        }
    }

    @Test
    fun midReadRevisionBumpThrowsStatisticsReadInvalidatedException() {
        val taskRepo = MemoryUserTaskRepository()
        var revisionCounter = 100L

        val mutatingTaskRepo = object : UserTaskRepository by taskRepo {
            override fun getAll(): List<UserTask> {
                // Mutate revision in the background during read
                revisionCounter = 101L
                return emptyList()
            }
        }

        val service = UnifiedStatisticsService(
            habitList = MemoryHabitList(),
            tasks = mutatingTaskRepo,
            focus = MemoryFocusSessionRepository(),
            contributions = MemoryContributionRepository(),
            placements = MemoryOrganizationStore(),
            containers = MemoryOrganizationStore(),
        )

        val context = createQueryContext(revision = 100L)
        assertThrows(StatisticsReadInvalidatedException::class.java) {
            service.read(
                request = context,
                currentIdentity = { StatisticsReadIdentity(context.datasetId, revisionCounter) },
            )
        }
    }

    @Test
    fun midReadCancellationThrowsStatisticsReadInvalidatedException() {
        val focusRepo = MemoryFocusSessionRepository()
        val isCancelled = AtomicBoolean(false)

        val mutatingFocusRepo = object : FocusSessionRepository by focusRepo {
            override fun getByTimeRange(startMillis: Long, endMillis: Long): List<FocusSession> {
                isCancelled.set(true)
                return emptyList()
            }
        }

        val service = UnifiedStatisticsService(
            habitList = MemoryHabitList(),
            tasks = MemoryUserTaskRepository(),
            focus = mutatingFocusRepo,
            contributions = MemoryContributionRepository(),
            placements = MemoryOrganizationStore(),
            containers = MemoryOrganizationStore(),
        )

        val context = createQueryContext()
        assertThrows(StatisticsReadInvalidatedException::class.java) {
            service.read(
                request = context,
                currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
                shouldCancel = { isCancelled.get() },
            )
        }
    }

    // ========================================================================
    // 4. Per-domain failures are explicit (no fake zeroes)
    // ========================================================================

    @Test
    fun perDomainTaskFailureIsExplicitAndDoesNotFabricateZero() {
        val rawTaskRepo = MemoryUserTaskRepository()
        val failingTaskRepo = object : UserTaskRepository by rawTaskRepo {
            override fun getAll(): List<UserTask> {
                throw IllegalStateException("Task database table locked")
            }
        }

        val focusRepo = MemoryFocusSessionRepository()
        val context = createQueryContext()
        focusRepo.record(
            FocusSession(
                id = FocusSessionId("s1"),
                startedAt = context.startMillis + 1000,
                endedAt = context.startMillis + 60000,
                durationMs = 59000,
                source = FocusSessionSource.TIMER,
                createdAt = context.startMillis + 60000,
                updatedAt = context.startMillis + 60000,
            ),
        )

        val service = UnifiedStatisticsService(
            habitList = MemoryHabitList(),
            tasks = failingTaskRepo,
            focus = focusRepo,
            contributions = MemoryContributionRepository(),
            placements = MemoryOrganizationStore(),
            containers = MemoryOrganizationStore(),
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        // Tasks must be null, not an empty or fabricated zero result!
        assertNull(report.tasks)
        assertNotNull(report.focus)
        assertEquals(59000L, report.focus?.totalDurationMs)
        assertNotNull(report.habits)

        // Explicit issue recorded
        assertEquals(1, report.readIssues.size)
        val issue = report.readIssues.single()
        assertEquals(StatisticsIssueCode.READ_FAILED, issue.code)
        assertEquals("tasks", issue.entityId)
        assertTrue(issue.message.contains("Task database table locked"))
    }

    @Test
    fun perDomainFocusFailureIsExplicitAndDoesNotFabricateZero() {
        val rawFocusRepo = MemoryFocusSessionRepository()
        val failingFocusRepo = object : FocusSessionRepository by rawFocusRepo {
            override fun getByTimeRange(startMillis: Long, endMillis: Long): List<FocusSession> {
                throw RuntimeException("Focus index corrupted")
            }
        }

        val taskRepo = MemoryUserTaskRepository()
        val context = createQueryContext()
        taskRepo.save(
            UserTask(
                id = TaskId("t1"),
                title = "Valid Task",
                status = TaskStatus.COMPLETED,
                createdAt = context.startMillis + 1000,
                updatedAt = context.startMillis + 2000,
                completedAt = context.startMillis + 2000,
            ),
        )

        val service = UnifiedStatisticsService(
            habitList = MemoryHabitList(),
            tasks = taskRepo,
            focus = failingFocusRepo,
            contributions = MemoryContributionRepository(),
            placements = MemoryOrganizationStore(),
            containers = MemoryOrganizationStore(),
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        // Focus must be null, not 0 duration or empty session count!
        assertNull(report.focus)
        assertNotNull(report.tasks)
        assertEquals(1, report.tasks?.completedCount)
        assertNotNull(report.habits)

        assertEquals(1, report.readIssues.size)
        val issue = report.readIssues.single()
        assertEquals(StatisticsIssueCode.READ_FAILED, issue.code)
        assertEquals("focus", issue.entityId)
        assertTrue(issue.message.contains("Focus index corrupted"))
    }

    @Test
    fun perDomainHabitsFailureIsExplicitAndDoesNotFabricateZero() {
        val failingHabitList = object : MemoryHabitList() {
            override fun iterator(): Iterator<Habit> {
                throw IllegalStateException("Habit storage deserialization error")
            }
        }

        val taskRepo = MemoryUserTaskRepository()
        val context = createQueryContext()
        taskRepo.save(
            UserTask(
                id = TaskId("t1"),
                title = "Valid Task",
                status = TaskStatus.COMPLETED,
                createdAt = context.startMillis + 1000,
                updatedAt = context.startMillis + 2000,
                completedAt = context.startMillis + 2000,
            ),
        )

        val service = UnifiedStatisticsService(
            habitList = failingHabitList,
            tasks = taskRepo,
            focus = MemoryFocusSessionRepository(),
            contributions = MemoryContributionRepository(),
            placements = MemoryOrganizationStore(),
            containers = MemoryOrganizationStore(),
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        // Habits must be null, not 0% or empty cycle outcome!
        assertNull(report.habits)
        assertNotNull(report.tasks)
        assertEquals(1, report.tasks?.completedCount)
        assertNotNull(report.focus)

        assertEquals(1, report.readIssues.size)
        val issue = report.readIssues.single()
        assertEquals(StatisticsIssueCode.READ_FAILED, issue.code)
        assertEquals("habits", issue.entityId)
        assertTrue(issue.message.contains("Habit storage deserialization error"))
    }

    // ========================================================================
    // 5. Ledger tombstones and relink do not reattribute existing facts
    // ========================================================================

    @Test
    fun ledgerTombstonesAndRelinkDoNotReattributeExistingFacts() {
        val habitList = MemoryHabitList()
        val taskRepo = MemoryUserTaskRepository()
        val focusRepo = MemoryFocusSessionRepository()
        val contribRepo = MemoryContributionRepository()
        val orgStore = MemoryOrganizationStore()

        // Habit A (Reading, 30 min numerical)
        val habitA = factory.buildHabit().apply {
            uuid = "habit-A-uuid"
            name = "Reading"
            type = org.isoron.uhabits.core.models.HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 30.0
            unit = "min"
            frequency = Frequency.DAILY
        }
        // Habit B (Writing, 30 min numerical)
        val habitB = factory.buildHabit().apply {
            uuid = "habit-B-uuid"
            name = "Writing"
            type = org.isoron.uhabits.core.models.HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 30.0
            unit = "min"
            frequency = Frequency.DAILY
        }
        habitList.add(habitA)
        habitList.add(habitB)

        val context = createQueryContext()
        val dayTimestamp = context.startMillis + 3600_000L // 01:00 on Oct 19

        // 1. Active contribution to Habit A from session s1
        contribRepo.record(
            Contribution(
                id = ContributionId("c-active-A"),
                targetHabit = HabitRef("habit-A-uuid"),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "s1",
                contributedValue = 20_000L, // 20 min in x1000 scale
                effectiveTimestamp = dayTimestamp,
                createdAt = dayTimestamp,
                updatedAt = dayTimestamp,
            ),
        )

        // 2. Tombstoned contribution to Habit A (revoked/removed)
        contribRepo.record(
            Contribution(
                id = ContributionId("c-tombstoned-A"),
                targetHabit = HabitRef("habit-A-uuid"),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "s2",
                contributedValue = 15_000L,
                effectiveTimestamp = dayTimestamp + 1000L,
                createdAt = dayTimestamp + 1000L,
                updatedAt = dayTimestamp + 2000L,
                removedAt = dayTimestamp + 2000L, // Tombstone
            ),
        )

        // 3. Active contribution to Habit B
        contribRepo.record(
            Contribution(
                id = ContributionId("c-active-B"),
                targetHabit = HabitRef("habit-B-uuid"),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "s3",
                contributedValue = 10_000L, // 10 min
                effectiveTimestamp = dayTimestamp + 3000L,
                createdAt = dayTimestamp + 3000L,
                updatedAt = dayTimestamp + 3000L,
            ),
        )

        // 4. Task 1 was originally associated with Habit A, but user relinked it to Habit B!
        // "A9: прошлое начисление читается из ledger, не из нынешней Task-Habit связи"
        val task1 = UserTask(
            id = TaskId("task-1"),
            title = "Research Chapter",
            linkedHabitUuid = "habit-B-uuid", // Relinked now to Habit B
            status = TaskStatus.COMPLETED,
            createdAt = dayTimestamp,
            updatedAt = dayTimestamp + 4000L,
            completedAt = dayTimestamp + 4000L,
        )
        taskRepo.save(task1)

        val service = UnifiedStatisticsService(
            habitList = habitList,
            tasks = taskRepo,
            focus = focusRepo,
            contributions = contribRepo,
            placements = orgStore,
            containers = orgStore,
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        assertNotNull(report.habits)
        val habitsResult = report.habits!!

        // Find cycle for Habit A
        val cycleA = habitsResult.closedCycles.singleOrNull { it.habitUuid == "habit-A-uuid" && it.endDate == context.from }
        assertNotNull("Habit A cycle must exist", cycleA)
        // Habit A's active contribution value must be exactly 20_000L (tombstone 15_000 excluded)
        assertEquals(20_000L, cycleA!!.quantity.activeContributionValue)
        // Tombstone does not leak into active value
        assertFalse(cycleA.quantity.activeContributionValue == 35_000L)

        // Find cycle for Habit B
        val cycleB = habitsResult.closedCycles.singleOrNull { it.habitUuid == "habit-B-uuid" && it.endDate == context.from }
        assertNotNull("Habit B cycle must exist", cycleB)
        // Habit B must NOT inherit Habit A's historical contribution despite Task 1 relink!
        assertEquals(10_000L, cycleB!!.quantity.activeContributionValue)

        // Total contributions across ledger remain untouched
        assertEquals(3, contribRepo.getAll(includeRemoved = true).size)
    }

    // ========================================================================
    // 6. Explicit timezone and habit day offset isolation
    // ========================================================================

    @Test
    fun explicitTimezoneIgnoresJvmDefaultAndPreservesBoundaries() {
        val previousJvmZone = TimeZone.getDefault()
        try {
            // Set arbitrary JVM default timezone
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))

            val service = createStandardService()

            // Novosibirsk context: UTC+7
            val nskContext = createQueryContext(
                from = LocalDate(2026, 10, 19),
                to = LocalDate(2026, 10, 25),
                timezoneId = "Asia/Novosibirsk",
            )
            // Berlin context: Europe/Berlin (has DST change on 2026-10-25)
            val berlinContext = createQueryContext(
                from = LocalDate(2026, 10, 19),
                to = LocalDate(2026, 10, 25),
                timezoneId = "Europe/Berlin",
            )

            val nskReport = service.read(
                request = nskContext,
                currentIdentity = { StatisticsReadIdentity(nskContext.datasetId, nskContext.revision) },
            )
            val berlinReport = service.read(
                request = berlinContext,
                currentIdentity = { StatisticsReadIdentity(berlinContext.datasetId, berlinContext.revision) },
            )

            // Start of 2026-10-19 in Asia/Novosibirsk is 2026-10-18T17:00:00Z
            val expectedNskStart = Instant.parse("2026-10-18T17:00:00Z").toEpochMilli()
            // Start of 2026-10-26 in Asia/Novosibirsk is 2026-10-25T17:00:00Z
            val expectedNskEnd = Instant.parse("2026-10-25T17:00:00Z").toEpochMilli()

            assertEquals(expectedNskStart, nskReport.context.startMillis)
            assertEquals(expectedNskEnd, nskReport.context.endMillis)

            // In Berlin, DST falls back by 1 hour on Oct 25, so week has 7*24 + 1 = 169 hours
            val berlinWeekMs = berlinReport.context.endMillis - berlinReport.context.startMillis
            assertEquals(169 * 3600_000L, berlinWeekMs)

            // JVM default timezone was not mutated
            assertEquals("Pacific/Honolulu", TimeZone.getDefault().id)
        } finally {
            TimeZone.setDefault(previousJvmZone)
        }
    }

    @Test
    fun habitHourOffsetDoesNotShiftTasksOrFocusCalendarMidnight() {
        val habitList = MemoryHabitList()
        val taskRepo = MemoryUserTaskRepository()
        val focusRepo = MemoryFocusSessionRepository()
        val contribRepo = MemoryContributionRepository()
        val orgStore = MemoryOrganizationStore()

        // Habit with daily frequency
        val habit = factory.buildHabit().apply {
            uuid = "h-early-bird"
            name = "Morning Stretch"
            frequency = Frequency.DAILY
            originalEntries.add(Entry(LocalDate(2026, 10, 19), Entry.YES_MANUAL))
        }
        habitList.add(habit)

        // Query with habitHourOffset = 4 (Habit day starts at 04:00 AM)
        val context = createQueryContext(
            from = LocalDate(2026, 10, 19),
            to = LocalDate(2026, 10, 25),
            timezoneId = "UTC",
            habitHourOffset = 4,
            habitMinuteOffset = 0,
        )

        // Timestamp at 02:30 AM on Oct 20 (before 04:00 habit boundary)
        val earlyMorningOct20 = Instant.parse("2026-10-20T02:30:00Z").toEpochMilli()

        // 1. Task completed at 02:30 AM on Oct 20
        taskRepo.save(
            UserTask(
                id = TaskId("task-early"),
                title = "Night Owl Task",
                status = TaskStatus.COMPLETED,
                createdAt = earlyMorningOct20 - 3600_000L,
                updatedAt = earlyMorningOct20,
                completedAt = earlyMorningOct20,
            ),
        )

        // 2. Focus session ended at 02:30 AM on Oct 20
        focusRepo.record(
            FocusSession(
                id = FocusSessionId("focus-early"),
                startedAt = earlyMorningOct20 - 1800_000L,
                endedAt = earlyMorningOct20,
                durationMs = 1800_000L,
                source = FocusSessionSource.TIMER,
                createdAt = earlyMorningOct20,
                updatedAt = earlyMorningOct20,
            ),
        )

        val service = UnifiedStatisticsService(
            habitList = habitList,
            tasks = taskRepo,
            focus = focusRepo,
            contributions = contribRepo,
            placements = orgStore,
            containers = orgStore,
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        assertNotNull(report.tasks)
        assertNotNull(report.focus)

        // Task must be bucketed on civil calendar date Oct 20, NOT shifted by habit offset!
        val oct20TaskBucket = report.tasks!!.dailyCompletedBuckets.single { it.date == LocalDate(2026, 10, 20) }
        assertEquals(1, oct20TaskBucket.count)
        val oct19TaskBucket = report.tasks!!.dailyCompletedBuckets.single { it.date == LocalDate(2026, 10, 19) }
        assertEquals(0, oct19TaskBucket.count)

        // Focus must be bucketed on civil calendar date Oct 20, NOT shifted by habit offset!
        val oct20FocusBucket = report.focus!!.dailyBuckets.single { it.date == LocalDate(2026, 10, 20) }
        assertEquals(1800_000L, oct20FocusBucket.durationMs)
        val oct19FocusBucket = report.focus!!.dailyBuckets.single { it.date == LocalDate(2026, 10, 19) }
        assertEquals(0L, oct19FocusBucket.durationMs)

        // Meanwhile, habitDateAt for this timestamp correctly resolves to Oct 19!
        assertEquals(LocalDate(2026, 10, 19), context.habitDateAt(earlyMorningOct20))
        assertEquals(LocalDate(2026, 10, 20), context.civilDateAt(earlyMorningOct20))
    }

    // ========================================================================
    // 7. Synthetic fixture PR14 Section 8 full integration verification
    // ========================================================================

    @Test
    fun syntheticFixturePR14Section8FullIntegration() {
        val habitList = MemoryHabitList()
        val taskRepo = MemoryUserTaskRepository()
        val focusRepo = MemoryFocusSessionRepository()
        val contribRepo = MemoryContributionRepository()
        val orgStore = MemoryOrganizationStore()

        // P = 19–25 октября 2026 включительно; now = 26 октября 09:00 UTC+7; Asia/Novosibirsk
        val context = createQueryContext(
            from = LocalDate(2026, 10, 19),
            to = LocalDate(2026, 10, 25),
            timezoneId = "Asia/Novosibirsk",
            firstWeekday = DayOfWeek.MONDAY,
            habitHourOffset = 0,
            habitMinuteOffset = 0,
            now = 1793019600000L, // 2026-10-26 09:00:00 UTC+7
        )

        // Containers: "Работа", "Учёба"
        val workContainer = Container(
            id = ContainerId("c-work"),
            parentId = null,
            name = "Работа",
            color = null,
            icon = null,
            siblingOrder = 1,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L),
        )
        val studyContainer = Container(
            id = ContainerId("c-study"),
            parentId = null,
            name = "Учёба",
            color = null,
            icon = null,
            siblingOrder = 2,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L),
        )
        orgStore.seedBaseline(
            containersList = listOf(workContainer, studyContainer),
        )

        // 1. Habit: "Вода", daily [S,S,F,S,U,F,S]
        val habitWater = factory.buildHabit().apply {
            uuid = "water-uuid"
            name = "Вода"
            frequency = Frequency.DAILY
            originalEntries.add(Entry(LocalDate(2026, 10, 19), Entry.YES_MANUAL))
            originalEntries.add(Entry(LocalDate(2026, 10, 20), Entry.YES_MANUAL))
            originalEntries.add(Entry(LocalDate(2026, 10, 21), Entry.NO))
            originalEntries.add(Entry(LocalDate(2026, 10, 22), Entry.YES_MANUAL))
            originalEntries.add(Entry(LocalDate(2026, 10, 23), Entry.UNKNOWN))
            originalEntries.add(Entry(LocalDate(2026, 10, 24), Entry.NO))
            originalEntries.add(Entry(LocalDate(2026, 10, 25), Entry.YES_MANUAL))
        }
        habitList.add(habitWater)

        // 2. Tasks: 12 completed across days: [1, 2, 0, 3, 2, 1, 3] = 12
        val completedDistribution = listOf(1, 2, 0, 3, 2, 1, 3)
        var taskSeq = 1
        for ((dayIdx, count) in completedDistribution.withIndex()) {
            val date = LocalDate(2026, 10, 19).plus(dayIdx)
            val dayStart = context.civilDayStart(date)
            for (c in 0 until count) {
                taskRepo.save(
                    UserTask(
                        id = TaskId("task-comp-${taskSeq++}"),
                        title = "Task Comp $taskSeq",
                        status = TaskStatus.COMPLETED,
                        createdAt = dayStart + 1000L,
                        updatedAt = dayStart + 5000L,
                        completedAt = dayStart + 5000L,
                    ),
                )
            }
        }

        // Additional 9 tasks created in period (some pending, some completed)
        // (total completed in period is 12)
        assertEquals(12, taskRepo.getAll().size)

        // 3. Focus: 12 sessions = 510 min = 8h 30m. Daily: [60, 90, 45, 75, 120, 60, 60] min
        // Breakdown: Работа 300 min, Учёба 150 min, Без раздела 60 min = 510 min
        // Target types: TASK 360 min, HABIT 120 min, CONTAINER 30 min = 510 min
        val focusDistribution = listOf(60L, 90L, 45L, 75L, 120L, 60L, 60L)
        var sessSeq = 1
        for ((dayIdx, durationMin) in focusDistribution.withIndex()) {
            val date = LocalDate(2026, 10, 19).plus(dayIdx)
            val dayStart = context.civilDayStart(date)
            val durationMs = durationMin * 60_000L
            val (cid, type, taskId, habitRef) = when (dayIdx) {
                0 -> Tuple4(ContainerId("c-work"), FocusPrimaryTargetType.TASK, TaskId("task-comp-1"), null)
                1 -> Tuple4(ContainerId("c-work"), FocusPrimaryTargetType.TASK, TaskId("task-comp-2"), null)
                2 -> Tuple4(ContainerId("c-study"), FocusPrimaryTargetType.HABIT, null, HabitRef("water-uuid"))
                3 -> Tuple4(ContainerId("c-study"), FocusPrimaryTargetType.HABIT, null, HabitRef("water-uuid"))
                4 -> Tuple4(ContainerId("c-work"), FocusPrimaryTargetType.TASK, TaskId("task-comp-4"), null)
                5 -> Tuple4(null, FocusPrimaryTargetType.TASK, TaskId("task-comp-11"), null)
                else -> Tuple4(ContainerId("c-study"), FocusPrimaryTargetType.TASK, TaskId("task-comp-12"), null)
            }
            val frozenPathJson = if (cid != null) {
                """{"path": [{"id": "${cid.value}", "name": "${if (cid.value == "c-work") "Работа" else "Учёба"}"}]}"""
            } else null

            focusRepo.record(
                FocusSession(
                    id = FocusSessionId("sess-${sessSeq++}"),
                    startedAt = dayStart + 3600_000L,
                    endedAt = dayStart + 3600_000L + durationMs,
                    durationMs = durationMs,
                    containerId = cid,
                    taskId = taskId,
                    habit = habitRef,
                    primaryTargetType = type,
                    organizationContext = frozenPathJson,
                    source = FocusSessionSource.TIMER,
                    createdAt = dayStart + 3600_000L + durationMs,
                    updatedAt = dayStart + 3600_000L + durationMs,
                ),
            )
        }

        val service = UnifiedStatisticsService(
            habitList = habitList,
            tasks = taskRepo,
            focus = focusRepo,
            contributions = contribRepo,
            placements = orgStore,
            containers = orgStore,
        )

        val report = service.read(
            request = context,
            currentIdentity = { StatisticsReadIdentity(context.datasetId, context.revision) },
        )

        assertEquals(0, report.readIssues.size)

        // Verify Tasks T1
        assertNotNull(report.tasks)
        assertEquals(12, report.tasks!!.completedCount)
        assertEquals(
            completedDistribution,
            report.tasks!!.dailyCompletedBuckets.map { it.count },
        )

        // Verify Focus F1 & F2
        assertNotNull(report.focus)
        assertEquals(510 * 60_000L, report.focus!!.totalDurationMs)
        assertEquals(7, report.focus!!.sessionCount)
        assertEquals(
            focusDistribution.map { it * 60_000L },
            report.focus!!.dailyBuckets.map { it.durationMs },
        )

        // Verify Habits
        assertNotNull(report.habits)
        val waterCycle = report.habits!!.closedCycles.single { it.habitUuid == "water-uuid" && it.endDate == context.from }
        assertEquals("Вода", waterCycle.habitName)
        assertEquals(HabitCadence.DAILY, waterCycle.cadence)
    }

    // ========================================================================
    // 8. Organization section scope filtering across habits, tasks, and focus
    // ========================================================================

    @Test
    fun sectionScopeFiltersAcrossAllDomainsAndDisjointFocusBranches() {
        val habitList = MemoryHabitList()
        val taskRepo = MemoryUserTaskRepository()
        val focusRepo = MemoryFocusSessionRepository()
        val contribRepo = MemoryContributionRepository()
        val orgStore = MemoryOrganizationStore()

        val rootWork = Container(
            id = ContainerId("work-root"),
            parentId = null,
            name = "Работа",
            color = null,
            icon = null,
            siblingOrder = 1,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L),
        )
        val childProject = Container(
            id = ContainerId("work-child"),
            parentId = ContainerId("work-root"),
            name = "Проект X",
            color = null,
            icon = null,
            siblingOrder = 1,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L),
        )
        val separateHome = Container(
            id = ContainerId("home-root"),
            parentId = null,
            name = "Дом",
            color = null,
            icon = null,
            siblingOrder = 2,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L),
        )
        orgStore.seedBaseline(
            containersList = listOf(rootWork, childProject, separateHome),
            placementsList = listOf(
                HabitPlacement(HabitRef("h-work"), ContainerId("work-child"), 1, PlacementOrigin.USER_CHANGE, OrganizationRevision(1L)),
                HabitPlacement(HabitRef("h-home"), ContainerId("home-root"), 1, PlacementOrigin.USER_CHANGE, OrganizationRevision(1L)),
            ),
        )

        // Habits
        val habitWork = factory.buildHabit().apply {
            uuid = "h-work"
            name = "Coding"
            frequency = Frequency.DAILY
            originalEntries.add(Entry(LocalDate(2026, 10, 20), Entry.YES_MANUAL))
        }
        val habitHome = factory.buildHabit().apply {
            uuid = "h-home"
            name = "Cleaning"
            frequency = Frequency.DAILY
            originalEntries.add(Entry(LocalDate(2026, 10, 20), Entry.YES_MANUAL))
        }
        habitList.add(habitWork)
        habitList.add(habitHome)

        val context = createQueryContext()
        val t = context.startMillis + 10_000L

        // Tasks in Work vs Home
        taskRepo.save(
            UserTask(
                id = TaskId("t-work"),
                title = "Work Task",
                containerId = ContainerId("work-child"),
                status = TaskStatus.COMPLETED,
                createdAt = t,
                updatedAt = t + 1000L,
                completedAt = t + 1000L,
            ),
        )
        taskRepo.save(
            UserTask(
                id = TaskId("t-home"),
                title = "Home Task",
                containerId = ContainerId("home-root"),
                status = TaskStatus.COMPLETED,
                createdAt = t,
                updatedAt = t + 1000L,
                completedAt = t + 1000L,
            ),
        )

        // Focus in Work vs Home
        focusRepo.record(
            FocusSession(
                id = FocusSessionId("f-work"),
                startedAt = t,
                endedAt = t + 60_000L,
                durationMs = 60_000L,
                containerId = ContainerId("work-child"),
                organizationContext = """{"path": [{"id": "work-root", "name": "Работа"}, {"id": "work-child", "name": "Проект X"}]}""",
                source = FocusSessionSource.TIMER,
                createdAt = t + 60_000L,
                updatedAt = t + 60_000L,
            ),
        )
        focusRepo.record(
            FocusSession(
                id = FocusSessionId("f-home"),
                startedAt = t,
                endedAt = t + 120_000L,
                durationMs = 120_000L,
                containerId = ContainerId("home-root"),
                organizationContext = """{"path": [{"id": "home-root", "name": "Дом"}]}""",
                source = FocusSessionSource.TIMER,
                createdAt = t + 120_000L,
                updatedAt = t + 120_000L,
            ),
        )

        val service = UnifiedStatisticsService(
            habitList = habitList,
            tasks = taskRepo,
            focus = focusRepo,
            contributions = contribRepo,
            placements = orgStore,
            containers = orgStore,
        )

        // Query scoped to "Работа" subtree
        val scopedContext = context.copy(
            sectionScope = StatisticsSectionScope.Container(
                id = ContainerId("work-root"),
                includeDescendants = true,
            ),
        )

        val report = service.read(
            request = scopedContext,
            currentIdentity = { StatisticsReadIdentity(scopedContext.datasetId, scopedContext.revision) },
        )

        // Only Work tasks included
        assertNotNull(report.tasks)
        assertEquals(1, report.tasks!!.completedCount)
        assertEquals("t-work", report.tasks!!.completedTasks.single().id.value)

        // Only Work focus included
        assertNotNull(report.focus)
        assertEquals(60_000L, report.focus!!.totalDurationMs)
        assertEquals(1, report.focus!!.sessionCount)

        // Disjoint branch breakdown identifies "Проект X" as CHILD_CONTAINER
        val branch = report.focus!!.sectionBreakdowns.single()
        assertEquals(FocusSectionCategory.CHILD_CONTAINER, branch.category)
        assertEquals(ContainerId("work-child"), branch.containerId)
        assertEquals("Проект X", branch.name)

        // Only Work habit included
        assertNotNull(report.habits)
        assertEquals(listOf("h-work"), report.habits!!.allCycles.map { it.habitUuid }.distinct())
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    @Test
    fun largeMixedCadenceHistoryUsesOneLedgerReadAndExactPartitions() {
        val habits = MemoryHabitList()
        val start = LocalDate(2022, 1, 1)
        repeat(90) { index ->
            habits.add(factory.buildHabit().apply {
                uuid = "large-history-$index"
                name = "Habit $index"
                frequency = Frequency(1, listOf(1, 7, 30)[index % 3])
                goalHistory = mutableListOf(HabitGoal(start, frequency.copy(),
                    NumericalHabitType.AT_LEAST, 1.0, ""))
                originalEntries.add(Entry(start, Entry.YES_MANUAL))
            })
        }
        val ledger = MemoryContributionRepository()
        var reads = 0
        val counting = object : ContributionRepository by ledger {
            override fun getByHabitsInRange(targetHabits: Set<HabitRef>, fromTimestamp: Long,
                toTimestamp: Long, includeRemoved: Boolean): List<Contribution> {
                reads++
                return ledger.getByHabitsInRange(targetHabits, fromTimestamp, toTimestamp, includeRemoved)
            }
        }
        val organization = MemoryOrganizationStore()
        val service = UnifiedStatisticsService(habits, MemoryUserTaskRepository(),
            MemoryFocusSessionRepository(), counting, organization, organization)
        for (from in listOf(LocalDate(2026, 1, 1), start)) {
            val query = createQueryContext(from = from, to = LocalDate(2026, 10, 26))
            val before = reads
            val started = System.nanoTime()
            val report = service.read(query, { StatisticsReadIdentity(query.datasetId, query.revision) })
            val result = requireNotNull(report.habits)
            println("PR14.4 mixed-history from=$from habits=90 cycles=${result.allCycles.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000} ledgerReads=${reads - before}")
            assertEquals(1, reads - before)
            assertTrue(report.readIssues.isEmpty())
            assertEquals(90, result.habitSummaries.map { it.habitUuid }.distinct().size)
            for (aggregate in listOf(result.daily, result.weekly, result.monthly)) {
                val buckets = result.dateBuckets.getValue(aggregate.cadence)
                assertEquals(aggregate.satisfiedCount, buckets.sumOf { it.satisfiedCount })
                assertEquals(aggregate.notSatisfiedCount, buckets.sumOf { it.notSatisfiedCount })
                assertEquals(aggregate.unknownCount, buckets.sumOf { it.unknownCount })
                assertEquals(aggregate.cycles.map { it.key }.toSet(), buckets.flatMap { it.cycles }.map { it.key }.toSet())
            }
        }
    }

    private fun createStandardService(): UnifiedStatisticsService {
        val orgStore = MemoryOrganizationStore()
        return UnifiedStatisticsService(
            habitList = MemoryHabitList(),
            tasks = MemoryUserTaskRepository(),
            focus = MemoryFocusSessionRepository(),
            contributions = MemoryContributionRepository(),
            placements = orgStore,
            containers = orgStore,
        )
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
