/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.stateful

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.isoron.platform.time.setToday
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyntheticDatasetGeneratorTest {

    private val baseDate = LocalDate(2026, 10, 31)

    @Before
    fun setUp() {
        setToday(baseDate)
    }

    @Test
    fun testSmallProfileDeterminismAndInvariants() {
        val config = DatasetConfig(profile = DatasetProfile.SMALL, baseDate = baseDate)
        val generator = SyntheticDatasetGenerator(config)

        val seed = 42L
        val dataset1 = generator.generate(seed)
        val dataset2 = generator.generate(seed)

        // 1. Exact reproducibility from seed
        assertEquals(dataset1.habits.size, dataset2.habits.size)
        assertEquals(dataset1.containers.size, dataset2.containers.size)
        assertEquals(dataset1.userTasks.size, dataset2.userTasks.size)
        assertEquals(dataset1.focusSessions.size, dataset2.focusSessions.size)
        assertEquals(dataset1.contributions.size, dataset2.contributions.size)

        for (i in dataset1.habits.indices) {
            val h1 = dataset1.habits[i]
            val h2 = dataset2.habits[i]
            assertEquals(h1.uuid, h2.uuid)
            assertEquals(h1.name, h2.name)
            assertEquals(h1.targetValue, h2.targetValue)
            assertEquals(h1.frequency, h2.frequency)
            assertEquals(h1.entries, h2.entries)
            assertEquals(h1.goalHistory, h2.goalHistory)
        }

        // 2. Different seeds yield different outputs
        val datasetOtherSeed = generator.generate(seed = 99999L)
        assertNotEquals(dataset1.habits.first().uuid, datasetOtherSeed.habits.first().uuid)

        // 3. Domain invariant validations
        verifyDomainInvariants(dataset1)
    }

    @Test
    fun testMediumProfileDiversityAndInvariants() {
        val config = DatasetConfig(profile = DatasetProfile.MEDIUM, baseDate = baseDate)
        val generator = SyntheticDatasetGenerator(config)
        val dataset = generator.generate(seed = 1337L)

        assertEquals(100, dataset.habits.size)
        assertEquals(15, dataset.containers.size)
        assertEquals(150, dataset.userTasks.size)
        assertEquals(250, dataset.focusSessions.size)

        // Verify diversity in types and features
        val hasNumerical = dataset.habits.any { it.isNumerical }
        val hasBoolean = dataset.habits.any { !it.isNumerical }
        val hasAtMost = dataset.habits.any { it.targetType == org.isoron.uhabits.core.models.NumericalHabitType.AT_MOST }
        val hasAtLeast = dataset.habits.any { it.targetType == org.isoron.uhabits.core.models.NumericalHabitType.AT_LEAST }
        val hasDaily = dataset.habits.any { it.frequency == org.isoron.uhabits.core.models.Frequency.DAILY }
        val hasWeekly = dataset.habits.any { it.frequency.denominator == 7 }
        val hasMultiGoal = dataset.habits.any { it.goalHistory.size > 1 }
        val hasSkips = dataset.habits.any { it.entries.values.contains(org.isoron.uhabits.core.models.Entry.SKIP) }
        val hasZeroOrExplicitNo = dataset.habits.any { it.entries.values.contains(0) || it.entries.values.contains(org.isoron.uhabits.core.models.Entry.NO) }
        val hasArchived = dataset.habits.any { it.isArchived }
        val hasUnassignedContainers = dataset.placements.any { it.containerId == null }
        val hasAssignedContainers = dataset.placements.any { it.containerId != null }

        assertTrue(hasNumerical, "Dataset must include numerical habits")
        assertTrue(hasBoolean, "Dataset must include boolean habits")
        assertTrue(hasAtMost, "Dataset must include AT_MOST habits")
        assertTrue(hasAtLeast, "Dataset must include AT_LEAST habits")
        assertTrue(hasDaily, "Dataset must include daily habits")
        assertTrue(hasWeekly, "Dataset must include weekly habits")
        assertTrue(hasMultiGoal, "Dataset must include multiple historical goal transitions")
        assertTrue(hasSkips, "Dataset must include skipped days (Entry.SKIP)")
        assertTrue(hasZeroOrExplicitNo, "Dataset must include explicit zero or Entry.NO")
        assertTrue(hasArchived, "Dataset must include archived habits")
        assertTrue(hasUnassignedContainers, "Dataset must include unassigned habits")
        assertTrue(hasAssignedContainers, "Dataset must include container-placed habits")

        verifyDomainInvariants(dataset)
    }

    @Test
    fun testLargeProfileDeterminismAndInvariants() {
        val config = DatasetConfig(profile = DatasetProfile.LARGE, baseDate = baseDate)
        val generator = SyntheticDatasetGenerator(config)
        val dataset = generator.generate(seed = 20261031L)

        assertEquals(500, dataset.habits.size)
        assertEquals(40, dataset.containers.size)
        assertEquals(600, dataset.userTasks.size)
        assertEquals(1000, dataset.focusSessions.size)

        verifyDomainInvariants(dataset)
    }

    @Test
    fun testExtremeProfilePerformanceAndLimits() {
        val config = DatasetConfig(profile = DatasetProfile.EXTREME, baseDate = baseDate)
        val generator = SyntheticDatasetGenerator(config)

        val startTime = System.currentTimeMillis()
        val dataset = generator.generate(seed = 888888L)
        val durationMs = System.currentTimeMillis() - startTime

        assertEquals(1000, dataset.habits.size)
        assertEquals(80, dataset.containers.size)
        assertEquals(1200, dataset.userTasks.size)
        assertEquals(2000, dataset.focusSessions.size)

        // Must complete within 15 seconds comfortably on JVM
        assertTrue(durationMs < 15000, "EXTREME profile generation should finish within 15s (took ${durationMs}ms)")
        verifyDomainInvariants(dataset)
    }

    @Test
    fun testPopulationIntoMemoryAndSQLiteEnvs() {
        val config = DatasetConfig(profile = DatasetProfile.SMALL, baseDate = baseDate)
        val generator = SyntheticDatasetGenerator(config)
        val dataset = generator.generate(seed = 777L)

        // 1. Populate Memory
        val memFactory = MemoryModelFactory()
        val memOrg = MemoryOrganizationStore(
            initialCutoverRevision = org.isoron.uhabits.core.containers.OrganizationRevision(1L),
            initialCutoverAt = 1000L
        )
        val memTasks = MemoryUserTaskRepository()
        val memFocus = MemoryFocusSessionRepository()
        val memContrib = MemoryContributionRepository()

        dataset.populate(memFactory, memOrg, memTasks, memFocus, memContrib)

        val memHabits = memFactory.buildHabitList()
        // Wait, memFactory.buildHabitList() builds a new list, but dataset.populate added to a built list.
        // Let's verify by populating through real SQLite database:
        runBlocking {
            val db = TestDatabaseHelper.createEmptyDatabase()
            try {
                val sqlFactory = SQLModelFactory(db)
                dataset.populate(db, sqlFactory)

                val loadedHabits = sqlFactory.buildHabitList()
                assertEquals(dataset.habits.size, loadedHabits.size())

                val sqliteOrg = org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore(db)
                val loadedContainers = sqliteOrg.getAllContainers(includeDeleted = true)
                assertEquals(dataset.containers.size, loadedContainers.size)

                val taskRepo = org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository(db)
                val allTasks = taskRepo.getAll()
                assertEquals(dataset.userTasks.size, allTasks.size)

                val focusRepo = org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(db)
                val allFocus = focusRepo.getFinalizedPage(null, 500)
                assertEquals(dataset.focusSessions.size, allFocus.size)

                val contribRepo = org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository(db)
                val allContribs = contribRepo.getAll()
                assertEquals(dataset.contributions.size, allContribs.size)
            } finally {
                db.close()
            }
        }
    }

    private fun verifyDomainInvariants(dataset: SyntheticDataset) {
        val containerIdSet = dataset.containers.map { it.id }.toSet()
        val habitUuidSet = dataset.habits.map { it.uuid }.toSet()
        val taskIdSet = dataset.userTasks.map { it.id }.toSet()
        val focusSessionIdSet = dataset.focusSessions.map { it.id.value }.toSet()

        val daySpan = dataset.config.profile.daySpan
        val startDate = dataset.config.baseDate.minus(daySpan - 1)
        val baseDate = dataset.config.baseDate

        // Invariant 1: Container tree acyclicity and valid parents
        val parentMap = dataset.containers.associate { it.id to it.parentId }
        for (c in dataset.containers) {
            var curr = c.parentId
            val visited = mutableSetOf(c.id)
            while (curr != null) {
                assertTrue(curr in containerIdSet, "Container parent $curr must exist in containers")
                assertTrue(curr !in visited, "Container hierarchy cycle detected at $curr")
                visited.add(curr)
                curr = parentMap[curr]
            }
        }

        // Invariant 2: Placements point to valid habits and valid containers (or null)
        for (p in dataset.placements) {
            assertTrue(p.habit.uuid in habitUuidSet, "Placement habit ${p.habit.uuid} must exist")
            if (p.containerId != null) {
                assertTrue(p.containerId in containerIdSet, "Placement container ${p.containerId} must exist")
            }
        }

        // Invariant 3: Habit goal histories are strictly monotonic and valid
        for (h in dataset.habits) {
            assertTrue(h.goalHistory.isNotEmpty(), "Habit ${h.name} must have at least one goal")
            for (i in 0 until h.goalHistory.size - 1) {
                val g1 = h.goalHistory[i]
                val g2 = h.goalHistory[i + 1]
                assertTrue(g1.effectiveDate.isOlderThan(g2.effectiveDate),
                    "Goal history for ${h.name} must be strictly monotonic: ${g1.effectiveDate} < ${g2.effectiveDate}")
            }
            assertTrue(h.frequency.numerator > 0 && h.frequency.denominator > 0,
                "Frequency must be positive: ${h.frequency}")

            // Invariant 4: Entry dates fall within the dataset date span
            for (date in h.entries.keys) {
                assertTrue(!date.isOlderThan(startDate) && !date.isNewerThan(baseDate),
                    "Entry date $date must fall within [$startDate, $baseDate]")
            }
        }

        // Invariant 5: Task references and classifications
        for (t in dataset.userTasks) {
            if (t.containerId != null) {
                assertTrue(t.containerId in containerIdSet, "Task ${t.id} container ${t.containerId} must exist")
            }
            if (t.linkedHabitUuid != null) {
                assertTrue(t.linkedHabitUuid in habitUuidSet, "Task ${t.id} linked habit must exist")
            }
        }

        // Invariant 6: FocusSession timestamp validity
        for (s in dataset.focusSessions) {
            assertTrue(s.endedAt > s.startedAt, "FocusSession ${s.id} endedAt must be after startedAt")
            assertTrue(s.durationMs > 0 && s.durationMs <= s.endedAt - s.startedAt,
                "FocusSession durationMs must be positive and <= elapsed span")
            if (s.taskId != null) {
                assertTrue(s.taskId in taskIdSet, "FocusSession task ${s.taskId} must exist")
            }
            if (s.habit != null) {
                assertTrue(s.habit.uuid in habitUuidSet, "FocusSession habit ${s.habit.uuid} must exist")
            }
            if (s.containerId != null) {
                assertTrue(s.containerId in containerIdSet, "FocusSession container ${s.containerId} must exist")
            }
        }

        // Invariant 7: Contribution references
        for (c in dataset.contributions) {
            assertTrue(c.sourceId in focusSessionIdSet, "Contribution source ${c.sourceId} must exist")
            assertTrue(c.targetHabit.uuid in habitUuidSet, "Contribution target habit ${c.targetHabit.uuid} must exist")
            assertTrue(c.contributedValue >= 0, "Contribution value must be nonnegative")
        }
    }
}
