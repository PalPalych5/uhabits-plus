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

package org.isoron.uhabits.core.models.projection

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.ZipReader
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.resetToday
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.io.HabitsCSVExporter
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.MissingDataPolicy
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsReadIdentity
import org.isoron.uhabits.core.statistics.UnifiedStatisticsService
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.GREY
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.HATCHED
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.OFF
import org.isoron.uhabits.core.ui.views.HistoryChart.Square.ON
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtMostMissingDataPolicyTest {

    @After
    fun tearDown() {
        resetToday()
    }

    private suspend fun setupDbAndList(): Triple<org.isoron.platform.io.Database, SQLModelFactory, SQLiteHabitList> {
        val db = TestDatabaseHelper.createEmptyDatabase()
        db.migrateTo(DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
        val factory = SQLModelFactory(db, deviceIdProvider = { "test-device" }, nowProvider = { 1_762_800_000_000L })
        val list = factory.buildHabitList() as SQLiteHabitList
        return Triple(db, factory, list)
    }

    private fun localeFirstWeekday(): DayOfWeek =
        DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1]

    @Test
    fun completedWeekWithMissingDaysSucceedsAcrossRingsStreaksHistoryAndStats() = runTest {
        val (db, factory, list) = setupDbAndList()
        try {
            val weekStart = LocalDate(2026, 10, 19).startOfWeek(localeFirstWeekday())
            val tue = weekStart.plus(1)
            val afterWeek = weekStart.plus(7)
            setToday(afterWeek)

            val habit = factory.buildHabit().apply {
                name = "Video Games"
                uuid = "at-most-games"
                type = HabitType.NUMERICAL
                frequency = Frequency.WEEKLY
                targetType = NumericalHabitType.AT_MOST
                targetValue = 180.0
                unit = "min"
                statisticsStartDate = weekStart
            }
            list.add(habit)
            habit.originalEntries.add(Entry(tue, 60_000)) // 60 min on Tue; all other 6 days unrecorded
            habit.recompute()

            assertEquals(MissingDataPolicy.DEFAULT_ZERO, habit.effectiveMissingDataPolicy())

            for (offset in 0..6) {
                val d = weekStart.plus(offset)
                assertEquals(60.0, habit.periodActualOn(d)!!, 0.001)
                assertTrue("Expected completed on $d", habit.isCompletedOn(d, afterWeek))
                assertTrue("Expected streak-completed on $d", habit.isCompletedForStreak(d, afterWeek))
            }
            assertEquals(7, habit.streaks.getAll().single().length)

            val valueService = EffectiveHabitValueService(MemoryContributionRepository(), firstWeekday = localeFirstWeekday())
            assertEquals(HabitGoalStatus.SATISFIED, valueService.goalProgressOn(habit, weekStart, today = afterWeek).status)

            val snapshot = EffectiveHabitReadSnapshotProvider(MemoryContributionRepository()).prepare(
                habits = listOf(habit),
                from = weekStart,
                to = afterWeek,
                datasetId = "ds",
                revision = 1L,
                calendar = EffectiveReadCalendarContext("UTC", localeFirstWeekday(), 0, 0),
            ).getValue(HabitRef("at-most-games"))
            assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(weekStart).status)
            assertEquals(HabitGoalStatus.SATISFIED, snapshot.compatibilityCompletionOn(weekStart.plus(4)).status)
            assertEquals(EffectiveReadExactness.LEGACY_COMPATIBILITY, snapshot.streakResult.exactness)
            assertEquals(7, snapshot.streakResult.streaks!!.single().length)

            val history = HistoryCardPresenter.buildState(habit, localeFirstWeekday(), LightTheme())
            assertEquals(OFF, history.series[0]) // ongoing next week starts at afterWeek
            for (i in 1..7) {
                assertEquals("Expected ON at history index $i", ON, history.series[i])
            }

            val orgStore = MemoryOrganizationStore()
            val stats = UnifiedStatisticsService(
                habitList = list,
                tasks = MemoryUserTaskRepository(),
                focus = MemoryFocusSessionRepository(),
                contributions = MemoryContributionRepository(),
                placements = orgStore,
                containers = orgStore,
            )
            val report = stats.read(
                StatisticsQueryContext(
                    from = weekStart,
                    to = weekStart.plus(6),
                    timezoneId = "UTC",
                    firstWeekday = localeFirstWeekday(),
                    datasetId = "ds",
                    revision = 1L,
                    now = startOfLocalDay(afterWeek.plus(1), 0, 0) + 43_200_000L,
                ),
                { StatisticsReadIdentity("ds", 1L) },
            )
            val summary = report.habits!!.habitSummaries.single { it.habitUuid == "at-most-games" }
            assertEquals(1, summary.satisfiedCount)
            assertEquals(0, summary.notSatisfiedCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun ongoingWeekIsPendingWithinLimitAndFailsImmediatelyIfExceeded() = runTest {
        val (db, factory, list) = setupDbAndList()
        try {
            val weekStart = LocalDate(2026, 11, 9).startOfWeek(localeFirstWeekday())
            val mon = weekStart
            val wed = weekStart.plus(2)
            setToday(wed)

            val habit = factory.buildHabit().apply {
                name = "Video Games"
                uuid = "at-most-ongoing"
                type = HabitType.NUMERICAL
                frequency = Frequency.WEEKLY
                targetType = NumericalHabitType.AT_MOST
                targetValue = 180.0
                unit = "min"
                statisticsStartDate = weekStart
            }
            list.add(habit)
            habit.originalEntries.add(Entry(mon, 60_000))
            habit.recompute()

            assertFalse(habit.isCompletedOn(mon, wed))
            assertFalse(habit.isCompletedForStreak(mon, wed))
            assertEquals(emptyList<Any>(), habit.streaks.getAll())

            val valueService = EffectiveHabitValueService(MemoryContributionRepository(), firstWeekday = localeFirstWeekday())
            assertEquals(HabitGoalStatus.UNKNOWN, valueService.goalProgressOn(habit, wed, today = wed).status)
            val pendingHistory = HistoryCardPresenter.buildState(habit, localeFirstWeekday(), LightTheme())
            assertTrue(pendingHistory.series.all { it == OFF })

            val orgStore = MemoryOrganizationStore()
            val stats = UnifiedStatisticsService(
                habitList = list,
                tasks = MemoryUserTaskRepository(),
                focus = MemoryFocusSessionRepository(),
                contributions = MemoryContributionRepository(),
                placements = orgStore,
                containers = orgStore,
            )
            val ongoingReport = stats.read(
                StatisticsQueryContext(
                    from = weekStart,
                    to = wed,
                    timezoneId = "UTC",
                    firstWeekday = localeFirstWeekday(),
                    datasetId = "ds",
                    revision = 1L,
                    now = startOfLocalDay(wed, 0, 0) + 43_200_000L,
                ),
                { StatisticsReadIdentity("ds", 1L) },
            )
            val ongoingSummary = ongoingReport.habits!!.habitSummaries.single()
            assertEquals(0, ongoingSummary.satisfiedCount)
            assertEquals(0, ongoingSummary.notSatisfiedCount)

            // Exceed weekly limit on Wednesday (+140 -> 200 > 180)
            habit.originalEntries.add(Entry(wed, 140_000))
            list.update(habit)
            habit.recompute()

            assertEquals(HabitGoalStatus.NOT_SATISFIED, valueService.goalProgressOn(habit, wed, today = wed).status)
            assertFalse(habit.isCompletedOn(wed, wed))
            val exceededHistory = HistoryCardPresenter.buildState(habit, localeFirstWeekday(), LightTheme())
            assertTrue(exceededHistory.series.all { it == GREY })
        } finally {
            db.close()
        }
    }

    @Test
    fun skippedDaysAndRawPersistenceDistinctionPreserved() = runTest {
        val (db, factory, list) = setupDbAndList()
        try {
            val weekStart = LocalDate(2026, 10, 5).startOfWeek(localeFirstWeekday())
            val tue = weekStart.plus(1)
            val thu = weekStart.plus(3)
            val fri = weekStart.plus(4)
            val afterWeek = weekStart.plus(7)
            setToday(afterWeek)

            val habit = factory.buildHabit().apply {
                name = "Limit"
                uuid = "at-most-persist"
                type = HabitType.NUMERICAL
                frequency = Frequency.WEEKLY
                targetType = NumericalHabitType.AT_MOST
                targetValue = 100.0
                unit = "min"
                statisticsStartDate = weekStart
            }
            list.add(habit)
            habit.originalEntries.add(Entry(tue, 0))          // explicit 0
            habit.originalEntries.add(Entry(thu, 40_000))     // 40 min
            habit.originalEntries.add(Entry(fri, Entry.SKIP)) // skipped day; Mon, Wed, Sat, Sun remain UNKNOWN
            habit.recompute()

            // Persistence must NOT contain fabricated rows for UNKNOWN days
            val persistedCount = db.queryLong("SELECT count(*) FROM Repetitions WHERE habit = ${habit.id}")
            assertEquals(3L, persistedCount)
            assertEquals(0, habit.originalEntries.get(tue).value)
            assertEquals(Entry.SKIP, habit.originalEntries.get(fri).value)
            assertEquals(Entry.UNKNOWN, habit.originalEntries.get(weekStart).value)

            val zipBytes = HabitsCSVExporter(list, listOf(habit)).writeArchive()
            val checkmarks = ZipReader(zipBytes).entries().find { it.name == "Checkmarks.csv" }
            assertNotNull(checkmarks)
            val csvText = checkmarks!!.content
            assertTrue(csvText.contains(",NO"))
            assertTrue(csvText.contains(",40000"))
            assertTrue(csvText.contains(",SKIP"))
            assertTrue(csvText.contains(",UNKNOWN"))

            assertFalse(habit.isCompletedOn(fri, afterWeek))
            assertTrue(habit.isCompletedOn(weekStart, afterWeek))
            val history = HistoryCardPresenter.buildState(habit, localeFirstWeekday(), LightTheme())
            // entries index: afterWeek = 0, Sun = 1, Sat = 2, Fri = 3
            assertEquals(HATCHED, history.series[3])
            assertEquals(ON, history.series[2])
        } finally {
            db.close()
        }
    }

    @Test
    fun perHabitUnknownOverrideQuestionMarksIndependenceAndAtLeastUnchanged() = runTest {
        val (db, factory, list) = setupDbAndList()
        try {
            val weekStart = LocalDate(2026, 10, 12).startOfWeek(localeFirstWeekday())
            val tue = weekStart.plus(1)
            val afterWeek = weekStart.plus(7)
            setToday(afterWeek)

            val atMost = factory.buildHabit().apply {
                name = "Gaming"
                uuid = "at-most-override"
                type = HabitType.NUMERICAL
                frequency = Frequency.WEEKLY
                targetType = NumericalHabitType.AT_MOST
                targetValue = 120.0
                unit = "min"
                statisticsStartDate = weekStart
            }
            list.add(atMost)
            atMost.originalEntries.add(Entry(tue, 30_000))
            atMost.recompute()

            val scoreDefaultZero = atMost.scores.get(afterWeek.minus(1)).value
            val streaksDefaultZero = atMost.streaks.getAll().size
            assertEquals(1, streaksDefaultZero)

            val storage = object : Preferences.Storage {
                private val map = mutableMapOf<String, Any>()
                override fun clear() { map.clear() }
                override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
                override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
                override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
                override fun getString(key: String, defValue: String) = map[key] as? String ?: defValue
                override fun putBoolean(key: String, value: Boolean) { map[key] = value }
                override fun putInt(key: String, value: Int) { map[key] = value }
                override fun putLong(key: String, value: Long) { map[key] = value }
                override fun putString(key: String, value: String) { map[key] = value }
                override fun remove(key: String) { map.remove(key) }
            }
            val prefs = Preferences(storage)
            prefs.areQuestionMarksEnabled = true
            atMost.recompute()
            assertEquals(scoreDefaultZero, atMost.scores.get(afterWeek.minus(1)).value, 1e-9)
            assertEquals(streaksDefaultZero, atMost.streaks.getAll().size)
            prefs.areQuestionMarksEnabled = false
            atMost.recompute()
            assertEquals(scoreDefaultZero, atMost.scores.get(afterWeek.minus(1)).value, 1e-9)
            assertEquals(streaksDefaultZero, atMost.streaks.getAll().size)

            val modified = factory.buildHabit().apply {
                copyFrom(atMost)
                missingDataPolicy = MissingDataPolicy.UNKNOWN
            }
            EditHabitCommand(list, atMost.id!!, modified).run()

            val reloadedList = SQLModelFactory(db).buildHabitList() as SQLiteHabitList
            val reloaded = reloadedList.getByUUID("at-most-override")!!
            reloaded.recompute()
            assertEquals(MissingDataPolicy.UNKNOWN, reloaded.missingDataPolicy)
            assertEquals(MissingDataPolicy.UNKNOWN, reloaded.effectiveMissingDataPolicy())

            val valueService = EffectiveHabitValueService(MemoryContributionRepository(), firstWeekday = localeFirstWeekday())
            assertEquals(HabitGoalStatus.UNKNOWN, valueService.goalProgressOn(reloaded, tue, today = afterWeek).status)
            assertFalse(reloaded.isCompletedOn(weekStart, afterWeek))

            val atLeast = factory.buildHabit().apply {
                name = "Reading"
                uuid = "at-least-unchanged"
                type = HabitType.NUMERICAL
                frequency = Frequency.WEEKLY
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 120.0
                unit = "min"
                statisticsStartDate = weekStart
            }
            list.add(atLeast)
            atLeast.originalEntries.add(Entry(tue, 30_000))
            atLeast.recompute()
            assertEquals(MissingDataPolicy.UNKNOWN, atLeast.effectiveMissingDataPolicy())
            assertEquals(HabitGoalStatus.UNKNOWN, valueService.goalProgressOn(atLeast, tue, today = afterWeek).status)
            assertFalse(atLeast.isCompletedOn(tue, afterWeek))
        } finally {
            db.close()
        }
    }
}
