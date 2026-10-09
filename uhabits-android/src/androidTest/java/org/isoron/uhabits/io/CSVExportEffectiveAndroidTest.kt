/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.io

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.JavaUserFile
import org.isoron.platform.io.ZipReader
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionRemovalResult
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.tasks.ExportCSVListener
import org.isoron.uhabits.core.tasks.ExportCSVTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CSVExportEffectiveAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun canonicalFixture_exportAndTombstoneOnAndroid() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-csv-$suffix.db"
        val testPrefs = "uhabits-test-csv-prefs-$suffix"

        val expSession = originalSession.copy(
            datasetId = "exp-$suffix",
            databaseFilename = testDbName,
            preferencesNamespace = testPrefs,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL,
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            ContributionSchema.createSchema(component.db)
            val repo = SQLiteContributionRepository(component.db)

            val targetDate = LocalDate(2026, 10, 7) // Wednesday
            setToday(targetDate)

            // 1. Habit: Study, Goal: AT_LEAST 300 minutes/week
            val study = component.modelFactory.buildHabit().apply {
                uuid = "study-android-uuid"
                name = "Study"
                type = HabitType.NUMERICAL
                unit = "min"
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 300.0
                frequency = Frequency(1, 7)
            }
            component.habitList.add(study)

            // Manual observation: 60 minutes
            study.originalEntries.add(Entry(targetDate, 60000))
            study.recompute()

            val hourOffset = component.preferences.midnightDelayHours
            val dayStart = startOfLocalDay(targetDate, hourOffset, 0) + 12 * 3600 * 1000L
            // Focus Contributions: 180 + 90 minutes
            val c1 = Contribution(
                ContributionId("csv-study-180-$suffix"),
                HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION,
                "focus-1",
                180000L,
                dayStart + 1000,
                dayStart + 1000,
                dayStart + 1000
            )
            val c2 = Contribution(
                ContributionId("csv-study-90-$suffix"),
                HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION,
                "focus-2",
                90000L,
                dayStart + 2000,
                dayStart + 2000,
                dayStart + 2000
            )
            repo.record(c1)
            repo.record(c2)

            // Provider wired identically to ListHabitsFragment / ShowHabitActivity
            val provider = { habits: List<org.isoron.uhabits.core.models.Habit>, from: LocalDate, to: LocalDate ->
                component.effectiveHabitReads!!.snapshots(habits, from, to)
            }

            val exportDir = File(context.cacheDir, "csv-export-1-$suffix").apply { mkdirs() }
            val exportDirUserFile = JavaUserFile(exportDir.toPath())

            var exportedFile1: String? = null
            val task1 = ExportCSVTask(
                component.habitList,
                listOf(study),
                exportDirUserFile,
                provider,
                object : ExportCSVListener {
                    override fun onExportCSVFinished(archiveFilename: String?) {
                        exportedFile1 = archiveFilename
                    }
                }
            )

            runBlocking {
                task1.doInBackground()
            }
            task1.onPostExecute()

            assertNotNull("Export 1 must succeed", exportedFile1)
            val zipBytes1 = File(exportedFile1!!).readBytes()
            val entries1 = runBlocking { ZipReader(zipBytes1).entries() }

            // Assert legacy Checkmarks.csv contains manual-only 60000
            val studyLegacy1 = entries1.find { it.name == "001 Study/Checkmarks.csv" }
            assertNotNull(studyLegacy1)
            assertTrue(studyLegacy1!!.content.contains("2026-10-07,60000,"))
            assertFalse(studyLegacy1.content.contains("330"))

            val rootLegacy1 = entries1.find { it.name == "Checkmarks.csv" }
            assertNotNull(rootLegacy1)
            assertTrue(rootLegacy1!!.content.contains("2026-10-07,60000,"))
            assertFalse(rootLegacy1.content.contains("330"))

            // Assert Effective.csv and 001 Study/Effective.csv contain 330.0 with full provenance
            val studyEffective1 = entries1.find { it.name == "001 Study/Effective.csv" }
            assertNotNull(studyEffective1)
            val lines1 = studyEffective1!!.content.trimEnd().lines()
            val expectedRow1 = "2026-10-07,60.0,270.0,0.0,330.0,330.0,COMPLETE,SATISFIED,min,"
            assertTrue("Expected row1:\n$expectedRow1\nActual:\n${studyEffective1!!.content}", lines1.contains(expectedRow1))

            val rootEffective1 = entries1.find { it.name == "Effective.csv" }
            assertNotNull(rootEffective1)
            assertTrue(rootEffective1!!.content.contains("2026-10-07,330.0,"))

            // 2. Tombstone the 90-minute contribution
            val tombstoneResult = repo.removeById(c2.id, dayStart + 5000)
            assertEquals(ContributionRemovalResult.REMOVED, tombstoneResult)

            // Export again
            val exportDir2 = File(context.cacheDir, "csv-export-2-$suffix").apply { mkdirs() }
            val exportDirUserFile2 = JavaUserFile(exportDir2.toPath())

            var exportedFile2: String? = null
            val task2 = ExportCSVTask(
                component.habitList,
                listOf(study),
                exportDirUserFile2,
                provider,
                object : ExportCSVListener {
                    override fun onExportCSVFinished(archiveFilename: String?) {
                        exportedFile2 = archiveFilename
                    }
                }
            )

            runBlocking {
                task2.doInBackground()
            }
            task2.onPostExecute()

            assertNotNull("Export 2 must succeed", exportedFile2)
            val zipBytes2 = File(exportedFile2!!).readBytes()
            val entries2 = runBlocking { ZipReader(zipBytes2).entries() }

            // Legacy Checkmarks still contains manual 60000
            val studyLegacy2 = entries2.find { it.name == "001 Study/Checkmarks.csv" }
            assertNotNull(studyLegacy2)
            assertTrue(studyLegacy2!!.content.contains("2026-10-07,60000,"))
            assertFalse(studyLegacy2.content.contains("240"))
            assertFalse(studyLegacy2.content.contains("330"))

            // Effective has 240.0 (60 manual + 180 focus, 90 tombstoned)
            val studyEffective2 = entries2.find { it.name == "001 Study/Effective.csv" }
            assertNotNull(studyEffective2)
            val lines2 = studyEffective2!!.content.trimEnd().lines()
            val expectedRow2 = "2026-10-07,60.0,180.0,90.0,240.0,240.0,COMPLETE,UNKNOWN,min,"
            assertTrue("Expected row2", lines2.contains(expectedRow2))
            assertFalse(studyEffective2.content.contains("330"))

            val rootEffective2 = entries2.find { it.name == "Effective.csv" }
            assertNotNull(rootEffective2)
            assertTrue(rootEffective2!!.content.contains("2026-10-07,240.0,"))
            assertFalse(rootEffective2.content.contains("330"))

            // Assert no synthetic manual database writes
            assertEquals(60000L, study.originalEntries.get(targetDate).value.toLong())
            assertEquals(1, study.originalEntries.getKnown().size)
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
        }
    }

    @Test
    fun specialCharactersAndEscapingOnAndroid() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val expSession = originalSession.copy(
            datasetId = "exp-esc-$suffix",
            databaseFilename = "uhabits-test-esc-$suffix.db",
            preferencesNamespace = "uhabits-test-esc-prefs-$suffix",
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL,
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            ContributionSchema.createSchema(component.db)
            val repo = SQLiteContributionRepository(component.db)

            val date = LocalDate(2026, 10, 5)
            setToday(date)

            val specialHabit = component.modelFactory.buildHabit().apply {
                uuid = "special-android-uuid"
                name = "Study, \"Advanced\"\nФранцузский 🎯"
                type = HabitType.NUMERICAL
                unit = "min"
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 60.0
                frequency = Frequency.DAILY
            }
            component.habitList.add(specialHabit)

            specialHabit.originalEntries.add(Entry(date, 30000, "Notes with, comma and \"quotes\"\nand Cyrillic Примечание"))
            specialHabit.recompute()

            val hourOffset = component.preferences.midnightDelayHours
            val dayStart = startOfLocalDay(date, hourOffset, 0) + 12 * 3600 * 1000L
            repo.record(Contribution(
                ContributionId("esc-credit-$suffix"),
                HabitRef(specialHabit.uuid!!),
                ContributionSourceType.FOCUS_SESSION,
                "esc-source",
                30000L,
                dayStart + 1000,
                dayStart + 1000,
                dayStart + 1000
            ))

            val provider = { habits: List<org.isoron.uhabits.core.models.Habit>, from: LocalDate, to: LocalDate ->
                component.effectiveHabitReads!!.snapshots(habits, from, to)
            }

            val exportDir = File(context.cacheDir, "csv-export-esc-$suffix").apply { mkdirs() }
            var exportedFile: String? = null
            val task = ExportCSVTask(
                component.habitList,
                listOf(specialHabit),
                JavaUserFile(exportDir.toPath()),
                provider,
                object : ExportCSVListener {
                    override fun onExportCSVFinished(archiveFilename: String?) { exportedFile = archiveFilename }
                }
            )

            runBlocking { task.doInBackground() }
            task.onPostExecute()

            assertNotNull(exportedFile)
            val zipBytes = File(exportedFile!!).readBytes()
            val entries = runBlocking { ZipReader(zipBytes).entries() }

            val rootEffective = entries.find { it.name == "Effective.csv" }
            assertNotNull(rootEffective)
            assertTrue(rootEffective!!.content.contains("\"Study, \"\"Advanced\"\"\nФранцузский 🎯\""))

            val habitEffective = entries.find { it.name.endsWith("Effective.csv") && it.name != "Effective.csv" }
            assertNotNull(habitEffective)
            assertTrue(habitEffective!!.content.contains("\"Notes with, comma and \"\"quotes\"\"\nand Cyrillic Примечание\""))
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
        }
    }

    @Test
    fun legacyNoContributionParityOnAndroid() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val expSession = originalSession.copy(
            datasetId = "exp-leg-$suffix",
            databaseFilename = "uhabits-test-leg-$suffix.db",
            preferencesNamespace = "uhabits-test-leg-prefs-$suffix",
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL,
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            val habit = component.modelFactory.buildHabit().apply {
                name = "Plain Habit"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 20.0
                frequency = Frequency.DAILY
            }
            component.habitList.add(habit)

            val provider = { habits: List<org.isoron.uhabits.core.models.Habit>, from: LocalDate, to: LocalDate ->
                component.effectiveHabitReads!!.snapshots(habits, from, to)
            }

            val exportDir = File(context.cacheDir, "csv-export-leg-$suffix").apply { mkdirs() }
            var exportedFile: String? = null
            val task = ExportCSVTask(
                component.habitList,
                listOf(habit),
                JavaUserFile(exportDir.toPath()),
                provider,
                object : ExportCSVListener {
                    override fun onExportCSVFinished(archiveFilename: String?) { exportedFile = archiveFilename }
                }
            )

            runBlocking { task.doInBackground() }
            task.onPostExecute()

            assertNotNull(exportedFile)
            val zipBytes = File(exportedFile!!).readBytes()
            val entries = runBlocking { ZipReader(zipBytes).entries() }

            // Exactly 5 legacy files for single habit, no Effective.csv
            assertNull(entries.find { it.name == "Effective.csv" })
            assertNull(entries.find { it.name == "001 Plain Habit/Effective.csv" })
            assertNotNull(entries.find { it.name == "Checkmarks.csv" })
            assertNotNull(entries.find { it.name == "Scores.csv" })
            assertNotNull(entries.find { it.name == "Habits.csv" })
            assertNotNull(entries.find { it.name == "001 Plain Habit/Checkmarks.csv" })
            assertNotNull(entries.find { it.name == "001 Plain Habit/Scores.csv" })
            assertEquals(5, entries.size)
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
        }
    }
}
