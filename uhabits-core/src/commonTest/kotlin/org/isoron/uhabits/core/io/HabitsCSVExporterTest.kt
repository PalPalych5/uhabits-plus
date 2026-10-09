/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.core.io

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.ZipReader
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitsCSVExporterTest : BaseUnitTest() {

    private val monday = LocalDate(2026, 10, 5)
    private val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
    private var sequence = 0
    private lateinit var contributionRepo: MemoryContributionRepository

    @BeforeTest
    override fun setUp() {
        super.setUp()
        contributionRepo = MemoryContributionRepository()
        habitList.add(fixtures.createShortHabit())
        habitList.add(fixtures.createEmptyHabit())
    }

    private fun credit(h: Habit, date: LocalDate, value: Long): Contribution {
        val id = "csv-credit-${++sequence}"
        val timestamp = startOfLocalDay(date) + 1000
        return Contribution(
            ContributionId(id),
            HabitRef(h.uuid!!),
            ContributionSourceType.FOCUS_SESSION,
            id,
            value,
            timestamp,
            timestamp,
            timestamp
        ).also(contributionRepo::record)
    }

    private fun snapshotProvider(
        habits: List<Habit>,
        from: LocalDate,
        to: LocalDate
    ): Map<HabitRef, EffectiveHabitReadSnapshot> {
        return EffectiveHabitReadSnapshotProvider(contributionRepo).prepare(
            habits, from, to, "csv-test", 1, calendar
        )
    }

    @Test
    fun testExportCSV() = runTest {
        val selected: MutableList<Habit> = mutableListOf()
        for (h in habitList) selected.add(h)
        val exporter = HabitsCSVExporter(habitList, selected)
        val bytes = exporter.writeArchive()
        assertTrue(bytes.isNotEmpty())

        val entries = ZipReader(bytes).entries()

        val filesToCheck = arrayOf(
            "001 Meditate/Checkmarks.csv",
            "001 Meditate/Scores.csv",
            "002 Wake up early/Checkmarks.csv",
            "002 Wake up early/Scores.csv",
            "Checkmarks.csv",
            "Habits.csv",
            "Scores.csv"
        )

        for (file in filesToCheck) {
            val entry = entries.find { it.name == file }
            assertNotNull(entry, "$file should exist in zip")
            val expected = fileOpener.openResourceFile("csv_export/$file").lines()
            assertEquals(expected, entry.content.trimEnd().lines(), "content mismatch for $file")
        }

        // Verify that no Effective.csv was added for no-contribution dataset
        assertNull(entries.find { it.name == "Effective.csv" })
        assertNull(entries.find { it.name == "001 Meditate/Effective.csv" })
    }

    @Test
    fun testCanonicalFixture_Study330To240_Tombstone() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val study = modelFactory.buildHabit().apply {
            uuid = "study-habit-uuid"
            name = "Study"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 300.0
            frequency = Frequency(1, 7) // AT_LEAST 300 minutes/week
            dayTier = DayTier.MINIMUM
        }
        testHabits.add(study)

        val targetDate = monday.plus(2) // Wednesday, Oct 7, 2026
        setToday(targetDate)

        // Manual observation: 60 minutes
        study.originalEntries.add(Entry(targetDate, 60000))
        study.recompute()

        // Focus Contributions: 180 + 90 minutes
        val c1 = credit(study, targetDate, 180000)
        val c2 = credit(study, targetDate, 90000)

        // Snapshot provider for exporter
        val provider: (List<Habit>, LocalDate, LocalDate) -> Map<HabitRef, EffectiveHabitReadSnapshot> = { habits, from, to ->
            snapshotProvider(habits, from, to)
        }

        // 1. First Export: Expected effective quantity: 330 minutes (60 manual + 180 + 90 focus)
        val exporter1 = HabitsCSVExporter(testHabits, testHabits.toList(), provider)
        val bytes1 = exporter1.writeArchive()
        val entries1 = ZipReader(bytes1).entries()

        // Assert legacy Checkmarks.csv preserves manual-only observation (60000)
        val studyLegacy1 = entries1.find { it.name == "001 Study/Checkmarks.csv" }
        assertNotNull(studyLegacy1)
        assertTrue(studyLegacy1.content.contains("2026-10-07,60000,"), "Legacy Checkmarks must contain raw manual 60000")
        assertFalse(studyLegacy1.content.contains("330"), "Legacy Checkmarks must NEVER contain 330")

        val rootCheckmarks1 = entries1.find { it.name == "Checkmarks.csv" }
        assertNotNull(rootCheckmarks1)
        assertTrue(rootCheckmarks1.content.contains("2026-10-07,60000,"), "Root Checkmarks must contain raw manual 60000")
        assertFalse(rootCheckmarks1.content.contains("330"), "Root Checkmarks must NEVER contain 330")

        // Assert Effective.csv and 001 Study/Effective.csv contain effective 330.0 with full provenance
        val studyEffective1 = entries1.find { it.name == "001 Study/Effective.csv" }
        assertNotNull(studyEffective1, "001 Study/Effective.csv must exist")
        val lines1 = studyEffective1.content.trimEnd().lines()
        assertEquals("Date,ManualValue,ContributionValue,TombstonedValue,EffectiveValue,EffectiveLowerBound,Coverage,GoalStatus,Unit,Notes", lines1[0])
        val expectedRow1 = "2026-10-07,60.0,270.0,0.0,330.0,330.0,COMPLETE,SATISFIED,min,"
        assertTrue(lines1.contains(expectedRow1), "Expected row:\n$expectedRow1\nActual lines:\n${studyEffective1.content}")

        val rootEffective1 = entries1.find { it.name == "Effective.csv" }
        assertNotNull(rootEffective1, "Root Effective.csv must exist")
        assertTrue(rootEffective1.content.contains("2026-10-07,330.0,"), "Root Effective.csv must contain 330.0")

        // 2. Tombstone the 90-minute Contribution through repository API
        val tombstoneTime = startOfLocalDay(targetDate) + 50000
        val removalResult = contributionRepo.removeById(c2.id, tombstoneTime)
        assertEquals(org.isoron.uhabits.core.contribution.ContributionRemovalResult.REMOVED, removalResult)

        // 3. Second Export: Manual remains 60, valid focus becomes 180, tombstoned becomes 90, effective becomes 240
        // Weekly achievement (300 min/week) is NO LONGER reached!
        val exporter2 = HabitsCSVExporter(testHabits, testHabits.toList(), provider)
        val bytes2 = exporter2.writeArchive()
        val entries2 = ZipReader(bytes2).entries()

        // Assert legacy Checkmarks still contains manual 60000
        val studyLegacy2 = entries2.find { it.name == "001 Study/Checkmarks.csv" }
        assertNotNull(studyLegacy2)
        assertTrue(studyLegacy2.content.contains("2026-10-07,60000,"))
        assertFalse(studyLegacy2.content.contains("240"))
        assertFalse(studyLegacy2.content.contains("330"))

        val studyEffective2 = entries2.find { it.name == "001 Study/Effective.csv" }
        assertNotNull(studyEffective2)
        val lines2 = studyEffective2.content.trimEnd().lines()
        val expectedRow2 = "2026-10-07,60.0,180.0,90.0,240.0,240.0,COMPLETE,UNKNOWN,min,"
        assertTrue(lines2.contains(expectedRow2), "Expected row:\n$expectedRow2\nActual lines:\n${studyEffective2.content}")
        assertFalse(studyEffective2.content.contains("330"), "No stale 330 value must be present after tombstone")

        val rootEffective2 = entries2.find { it.name == "Effective.csv" }
        assertNotNull(rootEffective2)
        assertTrue(rootEffective2.content.contains("2026-10-07,240.0,"))
        assertFalse(rootEffective2.content.contains("330"))

        // Assert no synthetic manual database writes
        assertEquals(60000, study.originalEntries.get(targetDate).value)
        assertEquals(1, study.originalEntries.getKnown().size)
    }

    @Test
    fun testContributionOnlyDates_NoManualEntry() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val habit = modelFactory.buildHabit().apply {
            uuid = "contrib-only-uuid"
            name = "Reading"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 20.0
            frequency = Frequency.DAILY
        }
        testHabits.add(habit)

        val targetDate = monday.plus(1)
        setToday(targetDate)

        // NO manual entry logged. Only Focus contribution of 45 minutes.
        credit(habit, targetDate, 45000)

        val exporter = HabitsCSVExporter(testHabits, testHabits.toList(), ::snapshotProvider)
        val bytes = exporter.writeArchive()
        val entries = ZipReader(bytes).entries()

        val effectiveFile = entries.find { it.name == "001 Reading/Effective.csv" }
        assertNotNull(effectiveFile)
        val lines = effectiveFile.content.trimEnd().lines()
        // Manual is UNKNOWN, Contribution is 45.0, Effective is UNKNOWN, Lower bound is 45.0, Coverage is INCOMPLETE, GoalStatus is SATISFIED (45 >= 20 for AT_LEAST)
        val expectedRow = "2026-10-06,UNKNOWN,45.0,0.0,UNKNOWN,45.0,INCOMPLETE,SATISFIED,min,"
        assertTrue(lines.contains(expectedRow), "Lines:\n${effectiveFile.content}")

        val rootEffective = entries.find { it.name == "Effective.csv" }
        assertNotNull(rootEffective)
        assertTrue(rootEffective.content.contains("2026-10-06,>= 45.0,"))
    }

    @Test
    fun testExplicitZero_Versus_UNKNOWN() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val habit = modelFactory.buildHabit().apply {
            uuid = "zero-vs-unknown-uuid"
            name = "Workout"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 30.0
            frequency = Frequency.DAILY
        }
        testHabits.add(habit)

        val dateZero = monday
        val dateUnknown = monday.plus(1)
        setToday(dateUnknown)

        // Date 1: Explicit manual zero
        habit.originalEntries.add(Entry(dateZero, 0))
        habit.recompute()
        credit(habit, dateZero, 30000)

        // Date 2: UNKNOWN manual (no entry) + credit
        credit(habit, dateUnknown, 30000)

        val exporter = HabitsCSVExporter(testHabits, testHabits.toList(), ::snapshotProvider)
        val bytes = exporter.writeArchive()
        val entries = ZipReader(bytes).entries()

        val effectiveFile = entries.find { it.name == "001 Workout/Effective.csv" }
        assertNotNull(effectiveFile)
        val lines = effectiveFile.content.trimEnd().lines()

        // Date 1 (explicit zero): Manual 0.0, Effective 30.0, Coverage COMPLETE
        val rowZero = "2026-10-05,0.0,30.0,0.0,30.0,30.0,COMPLETE,SATISFIED,min,"
        assertTrue(lines.contains(rowZero), "Must contain explicit zero row:\n${effectiveFile.content}")

        // Date 2 (UNKNOWN): Manual UNKNOWN, Effective UNKNOWN, Lower bound 30.0, Coverage INCOMPLETE
        val rowUnknown = "2026-10-06,UNKNOWN,30.0,0.0,UNKNOWN,30.0,INCOMPLETE,SATISFIED,min,"
        assertTrue(lines.contains(rowUnknown), "Must contain UNKNOWN row:\n${effectiveFile.content}")
    }

    @Test
    fun testSkip_WithContributions() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val habit = modelFactory.buildHabit().apply {
            uuid = "skip-habit-uuid"
            name = "Meditate"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 20.0
            frequency = Frequency.DAILY
        }
        testHabits.add(habit)

        val dateSkip = monday
        setToday(dateSkip)

        habit.originalEntries.add(Entry(dateSkip, Entry.SKIP))
        habit.recompute()
        credit(habit, dateSkip, 60000)

        val exporter = HabitsCSVExporter(testHabits, testHabits.toList(), ::snapshotProvider)
        val bytes = exporter.writeArchive()
        val entries = ZipReader(bytes).entries()

        val effectiveFile = entries.find { it.name == "001 Meditate/Effective.csv" }
        assertNotNull(effectiveFile)
        val lines = effectiveFile.content.trimEnd().lines()
        val rowSkip = "2026-10-05,SKIP,60.0,0.0,SKIP,0.0,INCOMPLETE,SKIPPED,min,"
        assertTrue(lines.contains(rowSkip), "Must contain SKIP row:\n${effectiveFile.content}")
    }

    @Test
    fun testAtMost_IncompleteCoverage() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val habit = modelFactory.buildHabit().apply {
            uuid = "at-most-uuid"
            name = "Screen Time"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_MOST
            targetValue = 60.0
            frequency = Frequency.DAILY
        }
        testHabits.add(habit)

        val dateIncompleteSatisfied = monday
        val dateIncompleteExceeded = monday.plus(1)
        setToday(dateIncompleteExceeded)

        // Date 1: Incomplete coverage with 30 min focus (30 <= 60, but missing manual means it cannot prove satisfaction!)
        credit(habit, dateIncompleteSatisfied, 30000)

        // Date 2: Incomplete coverage with 90 min focus (90 > 60 proves failure!)
        credit(habit, dateIncompleteExceeded, 90000)

        val exporter = HabitsCSVExporter(testHabits, testHabits.toList(), ::snapshotProvider)
        val bytes = exporter.writeArchive()
        val entries = ZipReader(bytes).entries()

        val effectiveFile = entries.find { it.name == "001 Screen Time/Effective.csv" }
        assertNotNull(effectiveFile)
        val lines = effectiveFile.content.trimEnd().lines()

        // 30 min is <= 60, but coverage is incomplete so GoalStatus must be UNKNOWN
        val row1 = "2026-10-05,UNKNOWN,30.0,0.0,UNKNOWN,30.0,INCOMPLETE,UNKNOWN,min,"
        assertTrue(lines.contains(row1), "Date 1 must be UNKNOWN status:\n${effectiveFile.content}")

        // 90 min is > 60, so even with incomplete coverage GoalStatus is proved NOT_SATISFIED
        val row2 = "2026-10-06,UNKNOWN,90.0,0.0,UNKNOWN,90.0,INCOMPLETE,NOT_SATISFIED,min,"
        assertTrue(lines.contains(row2), "Date 2 must be NOT_SATISFIED status:\n${effectiveFile.content}")
    }

    @Test
    fun testHistoricalGoalChanges() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val habit = modelFactory.buildHabit().apply {
            uuid = "hist-goal-uuid"
            name = "Writing"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 500.0
            frequency = Frequency.DAILY
        }
        // Historical goal on monday was 200 min, updated on tuesday to 500 min
        val oldGoal = HabitGoal(monday.minus(7), Frequency.DAILY, NumericalHabitType.AT_LEAST, 200.0, "min")
        val currentGoal = HabitGoal(monday.plus(1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 500.0, "min")
        habit.goalHistory.add(oldGoal)
        habit.goalHistory.add(currentGoal)
        testHabits.add(habit)

        setToday(monday.plus(2))

        // On monday, logged 250 min (satisfies old goal of 200, but would not satisfy current goal of 500)
        habit.originalEntries.add(Entry(monday, 250000))
        habit.recompute()
        credit(habit, monday, 50000) // total 300 min

        val exporter = HabitsCSVExporter(testHabits, testHabits.toList(), ::snapshotProvider)
        val bytes = exporter.writeArchive()
        val entries = ZipReader(bytes).entries()

        val effectiveFile = entries.find { it.name == "001 Writing/Effective.csv" }
        assertNotNull(effectiveFile)
        val lines = effectiveFile.content.trimEnd().lines()
        // 300 >= 200 (historical goal) -> SATISFIED
        val row = "2026-10-05,250.0,50.0,0.0,300.0,300.0,COMPLETE,SATISFIED,min,"
        assertTrue(lines.contains(row), "Must evaluate against historical goal:\n${effectiveFile.content}")
    }

    @Test
    fun testCsvEscaping_SpecialCharactersAndUnicode() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val specialHabit = modelFactory.buildHabit().apply {
            uuid = "special-char-uuid"
            name = "Study, \"Advanced\"\nFrench"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 50.0
            frequency = Frequency.DAILY
        }
        testHabits.add(specialHabit)

        val targetDate = monday
        setToday(targetDate)

        specialHabit.originalEntries.add(Entry(targetDate, 20000, "Notes with, comma and \"quotes\"\nnewline"))
        specialHabit.recompute()
        credit(specialHabit, targetDate, 30000)

        val exporter = HabitsCSVExporter(testHabits, testHabits.toList(), ::snapshotProvider)
        val bytes = exporter.writeArchive()
        val entries = ZipReader(bytes).entries()

        val rootCheckmarks = entries.find { it.name == "Checkmarks.csv" }
        assertNotNull(rootCheckmarks)
        // Header must escape habit name with quotes
        assertTrue(rootCheckmarks.content.contains("\"Study, \"\"Advanced\"\"\nFrench\""))

        val rootEffective = entries.find { it.name == "Effective.csv" }
        assertNotNull(rootEffective)
        assertTrue(rootEffective.content.contains("\"Study, \"\"Advanced\"\"\nFrench\""))

        // Individual directory name sanitization preserves alphanumeric and replaces commas/quotes
        val habitEffectiveEntry = entries.find { it.name.endsWith("Effective.csv") && it.name != "Effective.csv" }
        assertNotNull(habitEffectiveEntry)
        assertTrue(habitEffectiveEntry.content.contains("\"Notes with, comma and \"\"quotes\"\"\nnewline\""))
    }

    @Test
    fun testEmptyDataset() = runTest {
        val emptyList = modelFactory.buildHabitList()
        val exporter = HabitsCSVExporter(emptyList, emptyList.toList(), ::snapshotProvider)
        val bytes = exporter.writeArchive()
        assertTrue(bytes.isNotEmpty())

        val entries = ZipReader(bytes).entries()
        val habitsFile = entries.find { it.name == "Habits.csv" }
        assertNotNull(habitsFile)
        val checkmarksFile = entries.find { it.name == "Checkmarks.csv" }
        assertNotNull(checkmarksFile)
    }

    @Test
    fun testDatabaseMutationSafety() = runTest {
        val testHabits = modelFactory.buildHabitList()
        val habit = modelFactory.buildHabit().apply {
            uuid = "mutation-safety-uuid"
            name = "Safety"
            type = HabitType.NUMERICAL
            unit = "min"
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 60.0
            frequency = Frequency.DAILY
        }
        testHabits.add(habit)

        val targetDate = monday
        setToday(targetDate)

        habit.originalEntries.add(Entry(targetDate, 30000, "original note"))
        habit.recompute()
        credit(habit, targetDate, 60000)

        val beforeEntries = habit.originalEntries.getKnown().toList()
        assertEquals(1, beforeEntries.size)

        val exporter = HabitsCSVExporter(testHabits, testHabits.toList(), ::snapshotProvider)
        exporter.writeArchive()

        val afterEntries = habit.originalEntries.getKnown().toList()
        assertEquals(beforeEntries.size, afterEntries.size)
        assertEquals(beforeEntries[0].value, afterEntries[0].value)
        assertEquals(beforeEntries[0].notes, afterEntries[0].notes)
    }
}
