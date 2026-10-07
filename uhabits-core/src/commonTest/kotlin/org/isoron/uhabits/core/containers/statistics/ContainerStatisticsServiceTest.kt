/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.core.containers.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContainerStatisticsServiceTest {

    private val orgStore = MemoryOrganizationStore()
    private val modelFactory = MemoryModelFactory()
    private val habitList = modelFactory.buildHabitList()
    private val taskRepo = MemoryUserTaskRepository()
    private val focusRepo = MemoryFocusSessionRepository()
    private val contributionRepo = MemoryContributionRepository()
    private val habitService = EffectiveHabitValueService(
        repository = contributionRepo,
        hourOffset = 0,
        minuteOffset = 0,
        firstWeekday = DayOfWeek.MONDAY
    )

    private var nowMillisValue = 1_000_000_000L

    private val service = ContainerStatisticsService(
        containerQueries = orgStore,
        placementQueries = orgStore,
        habitList = habitList,
        userTaskRepository = taskRepo,
        focusSessionRepository = focusRepo,
        effectiveHabitValueService = habitService,
        firstWeekday = DayOfWeek.MONDAY,
        hourOffset = 0,
        minuteOffset = 0,
        clock = { nowMillisValue }
    )

    private fun addContainer(id: String, name: String, parentId: String? = null): Container {
        val c = Container(
            id = ContainerId(id),
            parentId = parentId?.let(::ContainerId),
            name = name,
            color = null,
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1)
        )
        orgStore.executeInTransaction { tx -> tx.saveContainer(c) }
        return c
    }

    private fun placeHabit(habitUuid: String, containerId: String?) {
        val placement = HabitPlacement(
            habit = HabitRef(habitUuid),
            containerId = containerId?.let(::ContainerId),
            localOrder = 0,
            origin = PlacementOrigin.USER_CHANGE,
            revision = OrganizationRevision(1)
        )
        orgStore.executeInTransaction { tx -> tx.saveHabitPlacement(placement) }
    }

    private fun createFocusSession(
        id: String,
        startedAt: Long,
        endedAt: Long,
        durationMs: Long,
        containerId: String? = null,
        organizationContext: String? = null,
        taskId: String? = null,
        habitUuid: String? = null,
    ): FocusSession {
        val s = FocusSession(
            id = FocusSessionId(id),
            startedAt = startedAt,
            endedAt = endedAt,
            durationMs = durationMs,
            taskId = taskId?.let(::TaskId),
            habit = habitUuid?.let(::HabitRef),
            containerId = containerId?.let(::ContainerId),
            source = FocusSessionSource.TIMER,
            createdAt = startedAt,
            updatedAt = endedAt,
            revision = 1L,
            organizationContext = organizationContext
        )
        focusRepo.record(s)
        return s
    }

    private fun createTask(
        id: String,
        title: String,
        containerId: String? = null,
        status: TaskStatus = TaskStatus.PENDING,
        plannedDate: LocalDate? = null,
        deadline: Long? = null,
        completedAt: Long? = null,
    ): UserTask {
        val t = UserTask(
            id = TaskId(id),
            title = title,
            containerId = containerId?.let(::ContainerId),
            status = status,
            plannedDate = plannedDate,
            deadline = deadline,
            completedAt = completedAt,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        taskRepo.save(t)
        return t
    }

    private fun createNumericalHabit(
        uuid: String,
        name: String,
        unit: String = "min",
        targetValue: Double = 300.0,
        frequency: Frequency = Frequency.WEEKLY,
        targetType: NumericalHabitType = NumericalHabitType.AT_LEAST
    ): Habit {
        val h = modelFactory.buildHabit().apply {
            this.uuid = uuid
            this.name = name
            this.type = HabitType.NUMERICAL
            this.unit = unit
            this.targetValue = targetValue
            this.frequency = frequency
            this.targetType = targetType
        }
        habitList.add(h)
        return h
    }

    private fun createBooleanHabit(
        uuid: String,
        name: String,
        frequency: Frequency = Frequency.DAILY
    ): Habit {
        val h = modelFactory.buildHabit().apply {
            this.uuid = uuid
            this.name = name
            this.type = HabitType.YES_NO
            this.frequency = frequency
        }
        habitList.add(h)
        return h
    }

    // ========================================================================
    // 1. Focus Statistics & Historical Invariance
    // ========================================================================

    @Test
    fun directSessionIncludedInHereAndDescendantExcluded() {
        val study = addContainer("study", "Study")
        val db = addContainer("db", "Databases", parentId = "study")

        val date = LocalDate(2026, 10, 5) // Monday
        val startTs = startOfLocalDay(date)
        val endTs = startTs + 1800_000L

        // Direct session in Study
        createFocusSession(
            id = "sess-direct",
            startedAt = startTs,
            endedAt = endTs,
            durationMs = 1800_000L, // 30 min
            containerId = "study",
            organizationContext = """{"path":[{"id":"study","name":"Study"}]}"""
        )

        // Descendant session in Databases
        createFocusSession(
            id = "sess-descendant",
            startedAt = startTs + 2000_000L,
            endedAt = startTs + 5000_000L,
            durationMs = 3000_000L, // 50 min
            containerId = "db",
            organizationContext = """{"path":[{"id":"study","name":"Study"},{"id":"db","name":"Databases"}]}"""
        )

        val range = ContainerStatsRange.day(date)

        // Query HERE for Study: only direct session
        val reportHere = service.getReport(study.id, range, ContainerScopeMode.HERE)
        assertEquals(1800_000L, reportHere.focus.totalDurationMs)
        assertEquals(1, reportHere.focus.sessionCount)
        assertEquals(1800_000L, reportHere.focus.directDurationMs)
        assertEquals(0L, reportHere.focus.descendantDurationMs)
        assertTrue(reportHere.focus.childBreakdown.isEmpty())

        // Query SUBTREE for Study: both direct and descendant
        val reportSubtree = service.getReport(study.id, range, ContainerScopeMode.SUBTREE)
        assertEquals(4800_000L, reportSubtree.focus.totalDurationMs)
        assertEquals(2, reportSubtree.focus.sessionCount)
        assertEquals(1800_000L, reportSubtree.focus.directDurationMs)
        assertEquals(3000_000L, reportSubtree.focus.descendantDurationMs)
        assertEquals(1, reportSubtree.focus.childBreakdown.size)
        assertEquals(ContainerId("db"), reportSubtree.focus.childBreakdown[0].containerId)
        assertEquals("Databases", reportSubtree.focus.childBreakdown[0].containerName)
        assertEquals(3000_000L, reportSubtree.focus.childBreakdown[0].durationMs)
    }

    @Test
    fun movedContainerHistoricalSessionRemainsAttributedToHistoricalAncestor() {
        val study = addContainer("study", "Study")
        val work = addContainer("work", "Work")
        val db = addContainer("db", "Databases", parentId = "study")

        val date = LocalDate(2026, 10, 5)
        val startTs = startOfLocalDay(date)

        // Session occurred when Databases was under Study
        createFocusSession(
            id = "sess-historical-db",
            startedAt = startTs,
            endedAt = startTs + 3600_000L,
            durationMs = 3600_000L, // 60 min
            containerId = "db",
            organizationContext = """{"path":[{"id":"study","name":"Study"},{"id":"db","name":"Databases"}]}"""
        )

        // Now move Databases under Work in current tree!
        orgStore.executeInTransaction { tx -> tx.saveContainer(db.copy(parentId = work.id)) }

        val range = ContainerStatsRange.day(date)

        // Historical Study report MUST still include the session under SUBTREE
        val reportStudy = service.getReport(study.id, range, ContainerScopeMode.SUBTREE)
        assertEquals(3600_000L, reportStudy.focus.totalDurationMs)
        assertEquals(1, reportStudy.focus.sessionCount)
        assertEquals(1, reportStudy.focus.childBreakdown.size)
        assertEquals(ContainerId("db"), reportStudy.focus.childBreakdown[0].containerId)

        // Work report for that same historical range MUST NOT include this old session
        val reportWork = service.getReport(work.id, range, ContainerScopeMode.SUBTREE)
        assertEquals(0L, reportWork.focus.totalDurationMs)
        assertEquals(0, reportWork.focus.sessionCount)
    }

    @Test
    fun dailyBucketsAndRangeBoundariesCorrect() {
        val root = addContainer("root", "Root")
        val day1 = LocalDate(2026, 10, 5) // Monday
        val day2 = LocalDate(2026, 10, 6) // Tuesday
        val day3 = LocalDate(2026, 10, 7) // Wednesday

        val ts1 = startOfLocalDay(day1) + 3600_000L // 1:00 AM Mon
        val ts2 = startOfLocalDay(day2) + 7200_000L // 2:00 AM Tue
        val ts3 = startOfLocalDay(day3) + 3600_000L // 1:00 AM Wed

        createFocusSession("s1", ts1 - 1000_000L, ts1, 1000_000L, "root", """{"path":[{"id":"root"}]}""")
        createFocusSession("s2", ts2 - 2000_000L, ts2, 2000_000L, "root", """{"path":[{"id":"root"}]}""")
        createFocusSession("s3", ts3 - 1500_000L, ts3, 1500_000L, "root", """{"path":[{"id":"root"}]}""")

        // Week range: Mon 5 Oct to Sun 11 Oct
        val rangeWeek = ContainerStatsRange.week(day1, DayOfWeek.MONDAY)
        val report = service.getReport(root.id, rangeWeek, ContainerScopeMode.HERE)

        assertEquals(4500_000L, report.focus.totalDurationMs)
        assertEquals(3, report.focus.sessionCount)
        assertEquals(7, report.focus.dailyBuckets.size) // 7 days in week

        val monBucket = report.focus.dailyBuckets.find { it.date == day1 }
        val tueBucket = report.focus.dailyBuckets.find { it.date == day2 }
        val wedBucket = report.focus.dailyBuckets.find { it.date == day3 }

        assertEquals(1000_000L, monBucket?.durationMs)
        assertEquals(2000_000L, tueBucket?.durationMs)
        assertEquals(1500_000L, wedBucket?.durationMs)
    }

    // ========================================================================
    // 2. Task Statistics
    // ========================================================================

    @Test
    fun taskCompletionAndCurrentStateSemantics() {
        val study = addContainer("study", "Study")
        val db = addContainer("db", "Databases", parentId = "study")

        val day = LocalDate(2026, 10, 5)
        val startTs = startOfLocalDay(day)

        // Completed inside range
        createTask("t1", "Task Completed In Range", containerId = "study",
            status = TaskStatus.COMPLETED, completedAt = startTs + 5000L)

        // Completed in descendant inside range
        createTask("t2", "Descendant Task Completed", containerId = "db",
            status = TaskStatus.COMPLETED, completedAt = startTs + 10000L)

        // Completed outside range
        createTask("t3", "Task Completed Yesterday", containerId = "study",
            status = TaskStatus.COMPLETED, completedAt = startTs - 5000L)

        // Pending task with plannedDate (plannedDate != completion!)
        createTask("t4", "Pending Planned Task", containerId = "study",
            status = TaskStatus.PENDING, plannedDate = day)

        // Hard overdue pending task
        nowMillisValue = startTs + 50000L
        createTask("t5", "Hard Overdue Task", containerId = "db",
            status = TaskStatus.PENDING, deadline = startTs + 1000L)

        val range = ContainerStatsRange.day(day)

        // Query HERE: only study container
        val reportHere = service.getReport(study.id, range, ContainerScopeMode.HERE)
        assertEquals(1, reportHere.tasks.completedCount) // only t1
        assertEquals(1, reportHere.tasks.pendingCount) // t4
        assertEquals(0, reportHere.tasks.overdueCount) // t5 is in db, not study

        // Query SUBTREE: study + db
        val reportSubtree = service.getReport(study.id, range, ContainerScopeMode.SUBTREE)
        assertEquals(2, reportSubtree.tasks.completedCount) // t1 + t2
        assertEquals(2, reportSubtree.tasks.pendingCount) // t4 + t5
        assertEquals(1, reportSubtree.tasks.overdueCount) // t5
        assertEquals(TaskPlacementMode.CURRENT, reportSubtree.tasks.placementMode)
    }

    // ========================================================================
    // 3. Habit Statistics & Projection Progress
    // ========================================================================

    @Test
    fun habitProgressInDirectAndSubtreeWithContributions() {
        val study = addContainer("study", "Study")
        val db = addContainer("db", "Databases", parentId = "study")

        val hStudy = createNumericalHabit("h-study", "Study Habit", "min", 300.0, Frequency.WEEKLY)
        placeHabit(hStudy.uuid!!, "study")

        val hDb = createNumericalHabit("h-db", "Databases Habit", "min", 100.0, Frequency.WEEKLY)
        placeHabit(hDb.uuid!!, "db")

        val day = LocalDate(2026, 10, 5) // Monday
        val dayTs = startOfLocalDay(day) + 3600_000L

        // Add 60 min contribution to hStudy
        contributionRepo.record(
            Contribution(
                id = ContributionId("c1"),
                targetHabit = HabitRef(hStudy.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "sess-1",
                contributedValue = 60_000L, // 60 min
                effectiveTimestamp = dayTs,
                createdAt = dayTs,
                updatedAt = dayTs
            )
        )

        val rangeWeek = ContainerStatsRange.week(day, DayOfWeek.MONDAY)

        // Query HERE: only hStudy
        val reportHere = service.getReport(study.id, rangeWeek, ContainerScopeMode.HERE)
        assertEquals(1, reportHere.habits.totalHabitCount)
        assertEquals(1, reportHere.habits.unknownCount) // incomplete coverage below target is UNKNOWN
        assertEquals(0, reportHere.habits.reachedCount)
        val rowStudy = reportHere.habits.habitRows.find { it.habitRef.uuid == hStudy.uuid }
        assertNotNull(rowStudy)
        assertEquals(60_000L, rowStudy.contributionValue)
        assertEquals(60_000L, rowStudy.totalValue)

        // Query SUBTREE: hStudy and hDb
        val reportSubtree = service.getReport(study.id, rangeWeek, ContainerScopeMode.SUBTREE)
        assertEquals(2, reportSubtree.habits.totalHabitCount)
        assertEquals(2, reportSubtree.habits.unknownCount)
        assertEquals(2, reportSubtree.habits.habitRows.size)
    }

    @Test
    fun booleanHabitProgressTrackedCorrectly() {
        val root = addContainer("root", "Root")
        val hBool = createBooleanHabit("h-bool", "Daily Review")
        placeHabit(hBool.uuid!!, "root")

        val day1 = LocalDate(2026, 10, 5)
        val day2 = LocalDate(2026, 10, 6)

        // Mark completed on day1
        hBool.originalEntries.add(Entry(day1, Entry.YES_MANUAL))
        // Skip on day2
        hBool.originalEntries.add(Entry(day2, Entry.SKIP))

        val range = ContainerStatsRange.custom(day1, day2, "2 Days")
        val report = service.getReport(root.id, range, ContainerScopeMode.HERE)

        assertEquals(1, report.habits.totalHabitCount)
        val row = report.habits.habitRows[0]
        assertFalse(row.isNumerical)
        assertEquals(1, row.completedDaysCount)
        assertEquals(2, row.totalDaysCount)
    }

    // ========================================================================
    // 4. Double Counting Protection
    // ========================================================================

    @Test
    fun focusSessionAndContributionAreNeverSummedTogether() {
        val root = addContainer("root", "Root")
        val habit = createNumericalHabit("h-1", "Coding", "min", 60.0, Frequency.DAILY)
        placeHabit(habit.uuid!!, "root")

        val date = LocalDate(2026, 10, 5)
        val startTs = startOfLocalDay(date)
        val endTs = startTs + 2400_000L // 40 min

        // FocusSession of 40 min recorded
        createFocusSession(
            id = "sess-1",
            startedAt = startTs,
            endedAt = endTs,
            durationMs = 2400_000L,
            containerId = "root",
            organizationContext = """{"path":[{"id":"root","name":"Root"}]}"""
        )

        // Attribution generated 40 min Contribution for the habit
        contributionRepo.record(
            Contribution(
                id = ContributionId("c-1"),
                targetHabit = HabitRef(habit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "sess-1",
                contributedValue = 40_000L,
                effectiveTimestamp = startTs + 1000L,
                createdAt = startTs,
                updatedAt = endTs
            )
        )

        val report = service.getReport(root.id, ContainerStatsRange.day(date), ContainerScopeMode.HERE)

        // Focus summary reports the real 40 minutes worked
        assertEquals(2400_000L, report.focus.totalDurationMs)

        // Habit summary reports the 40 min progress towards the habit
        val habitRow = report.habits.habitRows.single()
        assertEquals(40_000L, habitRow.contributionValue)

        // The report does NOT synthesize or return 80 min or a 133% score
        assertEquals(2400_000L, report.focus.totalDurationMs)
    }

    // ========================================================================
    // 5. Empty / Partial Data Handling
    // ========================================================================

    @Test
    fun handlesEmptyContainerGracefully() {
        val emptyContainer = addContainer("empty", "Empty Project")
        val range = ContainerStatsRange.day(LocalDate(2026, 10, 5))

        val report = service.getReport(emptyContainer.id, range, ContainerScopeMode.SUBTREE)

        assertEquals("Empty Project", report.containerName)
        assertEquals(0L, report.focus.totalDurationMs)
        assertEquals(0, report.focus.sessionCount)
        assertTrue(report.focus.childBreakdown.isEmpty())
        assertEquals(0, report.tasks.completedCount)
        assertEquals(0, report.tasks.pendingCount)
        assertEquals(0, report.tasks.overdueCount)
        assertEquals(0, report.habits.totalHabitCount)
        assertTrue(report.habits.habitRows.isEmpty())
    }
}
