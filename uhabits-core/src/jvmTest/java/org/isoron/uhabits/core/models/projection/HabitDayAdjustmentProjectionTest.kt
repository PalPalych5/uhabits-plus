/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.models.projection

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.ZipReader
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.io.HabitsCSVExporter
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsReadIdentity
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.statistics.UnifiedStatisticsService
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HabitDayAdjustmentProjectionTest {

    private suspend fun setup(): Triple<SQLModelFactory, SQLiteHabitList, LocalDate> {
        val db = TestDatabaseHelper.createEmptyDatabase()
        ContributionSchema.createSchema(db)
        HabitDayAdjustmentSchema.createSchema(db)
        var clockMs = 1_762_800_000_000L
        val factory = SQLModelFactory(db, deviceIdProvider = { "test-device" }, nowProvider = { ++clockMs })
        val habitList = factory.buildHabitList() as SQLiteHabitList
        val habit = factory.buildHabit().apply {
            name = "Focus Work"
            uuid = "habit-focus-work"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 30.0
            unit = "min"
        }
        habitList.add(habit)
        return Triple(factory, habitList, LocalDate(2026, 11, 10))
    }

    private fun recordFocus(
        contribRepo: SQLiteContributionRepository,
        id: String,
        habitUuid: String,
        day: LocalDate,
        millis: Long,
    ) {
        val ts = startOfLocalDay(day, 0, 0) + 3_600_000L
        contribRepo.record(
            Contribution(
                id = ContributionId(id),
                targetHabit = HabitRef(habitUuid),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "session-$id",
                contributedValue = millis,
                effectiveTimestamp = ts,
                createdAt = ts,
                updatedAt = ts,
            )
        )
    }

    @Test
    fun editTotalIncreaseDecreaseClearAndSubsequentFocusFollowInvariants() = runTest {
        val (factory, habitList, today) = setup()
        setToday(today)
        val db = factory.database
        try {
            val habit = habitList.getByUUID("habit-focus-work")!!
            val ref = HabitRef(habit.uuid!!)
            val contribRepo = factory.contributionRepository
            val adjRepo = factory.habitDayAdjustmentRepository
            val valueService = EffectiveHabitValueService(contribRepo, adjustmentRepository = adjRepo)
            val snapshotProvider = EffectiveHabitReadSnapshotProvider(contribRepo, adjRepo)
            val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)

            // 1. Seed immutable Focus = 25 min on today
            recordFocus(contribRepo, "c1", habit.uuid!!, today, 25_000L)
            assertEquals(25_000L, valueService.valueForDay(habit, today).totalValue)

            // 2. Increase total to 40 -> manual = 15, adjustment = 0, Focus untouched
            CreateRepetitionCommand(habitList, habit, today, 40_000, "extra manual", isTotalEdit = true).run()
            assertEquals(15_000, habit.originalEntries.get(today).value)
            assertEquals("extra manual", habit.originalEntries.get(today).notes)
            assertNull(adjRepo.getByHabitAndDate(ref, today))
            assertEquals(25_000L, contribRepo.getActiveTotalForHabit(ref))
            assertEquals(40_000L, valueService.valueForDay(habit, today).totalValue)

            // 3. Decrease total below Focus to 10 -> manual = 0, adjustment = -15, Focus still 25
            CreateRepetitionCommand(habitList, habit, today, 10_000, "trimmed", isTotalEdit = true).run()
            assertEquals(0, habit.originalEntries.get(today).value)
            assertEquals(-15_000L, adjRepo.getByHabitAndDate(ref, today)?.adjustmentMillis)
            assertEquals(25_000L, contribRepo.getActiveTotalForHabit(ref))
            assertEquals(10_000L, valueService.valueForDay(habit, today).totalValue)

            // 4. Subsequent Focus completion (+15 min) increases edited total from 10 -> 25
            recordFocus(contribRepo, "c2", habit.uuid!!, today, 15_000L)
            assertEquals(40_000L, contribRepo.getActiveTotalForHabit(ref))
            assertEquals(25_000L, valueService.valueForDay(habit, today).totalValue)

            // 5. Clearing total to 0 sets manual = 0 and adjustment = -40 (effective = 0)
            CreateRepetitionCommand(habitList, habit, today, 0, "", isTotalEdit = true).run()
            assertEquals(0, habit.originalEntries.get(today).value)
            assertEquals(-40_000L, adjRepo.getByHabitAndDate(ref, today)?.adjustmentMillis)
            assertEquals(0L, valueService.valueForDay(habit, today).totalValue)

            // 6. Setting Entry.UNKNOWN removes adjustment and restores unadjusted Focus view (40)
            CreateRepetitionCommand(habitList, habit, today, Entry.UNKNOWN, "", isTotalEdit = true).run()
            assertEquals(Entry.UNKNOWN, habit.originalEntries.get(today).value)
            assertNull(adjRepo.getByHabitAndDate(ref, today))
            assertEquals(40_000L, valueService.valueForDay(habit, today).totalValue)

            // 7. Historical date decrease reflects across Snapshot, Scores/Streaks, CSV, and UnifiedStatistics
            val yesterday = today.minus(1)
            recordFocus(contribRepo, "c3", habit.uuid!!, yesterday, 35_000L)
            CreateRepetitionCommand(habitList, habit, yesterday, 5_000, "", isTotalEdit = true).run()

            val snapshots = snapshotProvider.prepare(listOf(habit), yesterday, today, "test", 1L, calendar)
            val snap = snapshots.getValue(ref)
            assertEquals(5_000L, snap.day(yesterday).totalValue)
            assertEquals(40_000L, snap.day(today).totalValue)
            assertTrue(snap.hasProjectedChanges)
            // Yesterday (5 < 30 target) is not a completed streak; today (40 >= 30) gives streak = 1
            assertEquals(1, snap.streakResult.streaks?.singleOrNull()?.length ?: 0)
            assertTrue((snap.scoreResult.on(yesterday) ?: 1.0) < 0.5)

            // Verify CSV export reflects adjusted effective values in Effective.csv without overwriting Checkmarks.csv
            val bytes = HabitsCSVExporter(habitList, listOf(habit)) { hs, f, t ->
                snapshotProvider.prepare(hs, f, t, "test", 1L, calendar)
            }.writeArchive()
            val zipEntries = ZipReader(bytes).entries()
            val effCsv = zipEntries.find { it.name == "001 Focus Work/Effective.csv" }
            assertNotNull(effCsv)
            assertTrue(effCsv!!.content.contains("5.0"))

            // Verify UnifiedStatistics reflects yesterday = 5 and today = 40
            val orgStore = MemoryOrganizationStore()
            val context = StatisticsQueryContext(
                from = yesterday,
                to = today,
                timezoneId = java.util.TimeZone.getDefault().id,
                firstWeekday = DayOfWeek.MONDAY,
                habitHourOffset = 0,
                habitMinuteOffset = 0,
                datasetId = "test",
                revision = 1L,
                now = startOfLocalDay(today.plus(1), 0, 0) + 43_200_000L,
                sectionScope = StatisticsSectionScope.All,
            )
            val report = UnifiedStatisticsService(
                habitList = habitList,
                tasks = MemoryUserTaskRepository(),
                focus = MemoryFocusSessionRepository(),
                contributions = contribRepo,
                placements = orgStore,
                containers = orgStore,
                adjustments = adjRepo,
            ).read(context, { StatisticsReadIdentity("test", 1L) })
            assertNotNull(report.habits)
            val summary = report.habits!!.habitSummaries.single()
            assertEquals(1, summary.satisfiedCount)
            assertEquals(1, summary.notSatisfiedCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun atomicTransactionRollsBackBothManualEntryAndAdjustmentOnFailure() = runTest {
        val (factory, habitList, today) = setup()
        setToday(today)
        val db = factory.database
        try {
            val habit = habitList.getByUUID("habit-focus-work")!!
            recordFocus(factory.contributionRepository, "c1", habit.uuid!!, today, 25_000L)
            CreateRepetitionCommand(habitList, habit, today, 40_000, "initial", isTotalEdit = true).run()

            val failingAdjRepo = object : HabitDayAdjustmentRepository {
                override fun getByHabitAndDate(targetHabit: HabitRef, date: LocalDate) =
                    factory.habitDayAdjustmentRepository.getByHabitAndDate(targetHabit, date)
                override fun getByHabitsInDateRange(targetHabits: Set<HabitRef>, from: LocalDate, to: LocalDate) =
                    factory.habitDayAdjustmentRepository.getByHabitsInDateRange(targetHabits, from, to)
                override fun upsert(adjustment: HabitDayAdjustment): Unit = throw IllegalStateException("Injected write failure")
                override fun delete(targetHabit: HabitRef, date: LocalDate) = Unit
                override fun deleteByHabit(targetHabit: HabitRef) = Unit
            }

            try {
                CreateRepetitionCommand(
                    habitList = habitList,
                    habit = habit,
                    date = today,
                    value = 10_000,
                    notes = "should rollback",
                    isTotalEdit = true,
                    adjustmentRepository = failingAdjRepo,
                ).run()
                fail("Expected injected failure")
            } catch (_: IllegalStateException) {
                // expected
            }

            // Reload from SQLite and verify previous Repetitions row (15_000, 'initial') stayed intact
            val reloaded = factory.buildHabitList() as SQLiteHabitList
            val persisted = reloaded.getByUUID("habit-focus-work")!!
            assertEquals(15_000, persisted.originalEntries.get(today).value)
            assertEquals("initial", persisted.originalEntries.get(today).notes)
            assertNull(factory.habitDayAdjustmentRepository.getByHabitAndDate(HabitRef(habit.uuid!!), today))
        } finally {
            db.close()
        }
    }
}
