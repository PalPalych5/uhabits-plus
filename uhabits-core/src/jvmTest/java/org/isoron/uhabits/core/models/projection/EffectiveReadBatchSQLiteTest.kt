package org.isoron.uhabits.core.models.projection

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.*
import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.sqlite.*
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.io.HabitsCSVExporter
import kotlin.test.*

class EffectiveReadBatchSQLiteTest : BaseUnitTest() {
    @Test fun fiveHundredHabitsAndMultiMonthRangeUseFourIndexedQueries() = runTest {
        val database = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(database)
            var selects = 0
            val counted = object : Database by database {
                override fun prepareStatement(sql: String): PreparedStatement {
                    if (sql.trimStart().startsWith("SELECT") && sql.contains("FROM Contributions")) selects++
                    return database.prepareStatement(sql)
                }
            }
            val facts = SQLiteContributionRepository(counted)
            val end = LocalDate(2026, 10, 8)
            setToday(end)
            val habits = (0 until 500).map { index -> modelFactory.buildHabit().apply {
                uuid = "sqlite-batch-$index"; type = HabitType.NUMERICAL; unit = "min"
                targetValue = 300.0; frequency = Frequency(1, 7)
                originalEntries.add(Entry(end.minus(90), 0)); originalEntries.add(Entry(end, 0)); recompute()
            } }
            habits.forEachIndexed { index, habit ->
                val instant = startOfLocalDay(end.minus(index % 90)) + 1000
                facts.record(Contribution(ContributionId("sql-credit-$index"), HabitRef(habit.uuid!!),
                    ContributionSourceType.FOCUS_SESSION, "source-$index", 45000, instant, instant, instant))
            }
            selects = 0
            val snapshots = EffectiveHabitReadSnapshotProvider(facts).prepare(habits, end.minus(90), end,
                "sqlite-batch", 1, EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0))
            // Two provider operations, each repository chunks its 500 IDs into two <=400 batches.
            assertEquals(4, selects)
            snapshots.values.forEach { snapshot ->
                snapshot.goalAchievementOn(end)
                snapshot.goalAchievementOn(end.minus(30))
                snapshot.compatibilityCompletionOn(end)
            }
            assertEquals(4, selects)
            val plan = database.prepareStatement("EXPLAIN QUERY PLAN SELECT * FROM Contributions WHERE target_habit_uuid IN (?) AND effective_timestamp >= ? AND effective_timestamp <= ? AND removed_at IS NULL ORDER BY effective_timestamp, id")
            try {
                plan.bindText(1, habits.first().uuid!!); plan.bindLong(2, 0); plan.bindLong(3, Long.MAX_VALUE)
                val descriptions = mutableListOf<String>()
                while (plan.step() == StepResult.ROW) descriptions.add(plan.getText(3))
                assertTrue(descriptions.any { it.contains("idx_contributions_habit") }, descriptions.toString())
            } finally { plan.finalize() }
            assertEquals(500, snapshots.size)
        } finally { database.close() }
    }

    @Test fun globalStatisticsReportUsesZeroPerDayOrPerGoalQueriesAfterPreload() = runTest {
        val database = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(database)
            var selects = 0
            val counted = object : Database by database {
                override fun prepareStatement(sql: String): PreparedStatement {
                    if (sql.trimStart().startsWith("SELECT") && sql.contains("FROM Contributions")) selects++
                    return database.prepareStatement(sql)
                }
            }
            val facts = SQLiteContributionRepository(counted)
            val end = LocalDate(2026, 10, 8)
            setToday(end)
            val habits = (0 until 50).map { index -> modelFactory.buildHabit().apply {
                uuid = "stat-batch-$index"; type = HabitType.NUMERICAL; unit = "min"
                targetValue = 300.0; frequency = Frequency(1, 7); dayTier = DayTier.MINIMUM
                originalEntries.add(Entry(end.minus(30), 0)); originalEntries.add(Entry(end, 0)); recompute()
            } }
            habits.forEachIndexed { index, habit ->
                val instant = startOfLocalDay(end.minus(index % 30)) + 1000
                facts.record(Contribution(ContributionId("stat-credit-$index"), HabitRef(habit.uuid!!),
                    ContributionSourceType.FOCUS_SESSION, "source-$index", 45000, instant, instant, instant))
            }
            selects = 0
            val snapshots = EffectiveHabitReadSnapshotProvider(facts).prepare(habits, end.minus(30), end,
                "stat-batch", 1, EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0))
            assertEquals(2, selects)
            val initialSelects = selects

            val report = org.isoron.uhabits.core.ui.screens.statistics.StatisticsReportStateBuilder.build(
                habits = habits,
                period = org.isoron.uhabits.core.ui.screens.statistics.StatisticsPeriod.MONTH,
                start = end.minus(30),
                end = end,
                today = end,
                firstWeekday = DayOfWeek.MONDAY,
                filters = org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState(habitStatus = org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter.ALL),
                snapshots = snapshots
            )
            val overview = org.isoron.uhabits.core.ui.screens.statistics.StatisticsOverviewStateBuilder.build(
                habits = habits,
                start = end.minus(30),
                end = end,
                firstWeekday = DayOfWeek.MONDAY,
                snapshots = snapshots
            )

            habits.forEach { habit ->
                val snapshot = snapshots.getValue(HabitRef(habit.uuid!!))
                org.isoron.uhabits.core.ui.screens.statistics.CompareHabitsPresenter.buildOverviewMetrics(
                    habit, end.minus(30), end,
                    org.isoron.uhabits.core.ui.screens.statistics.ComparePeriod.MONTH, snapshot
                )
                org.isoron.uhabits.core.ui.screens.statistics.CompareHabitsPresenter.buildExecutionSeries(
                    habit, end.minus(30), end, snapshot
                )
                org.isoron.uhabits.core.ui.screens.statistics.CompareHabitsPresenter.buildFrequency(habit, snapshot)
            }
            assertEquals(initialSelects, selects)
            assertEquals(50, report.habits.size)
            assertNotNull(report.overallProgress)
            assertNotNull(overview)
        } finally { database.close() }
    }

    @Test fun widgetPresentersBatchSingleAndMultipleWithoutPerDayOrPerWidgetQueries() = runTest {
        val database = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(database)
            var selects = 0
            val counted = object : Database by database {
                override fun prepareStatement(sql: String): PreparedStatement {
                    if (sql.trimStart().startsWith("SELECT") && sql.contains("FROM Contributions")) selects++
                    return database.prepareStatement(sql)
                }
            }
            val facts = SQLiteContributionRepository(counted)
            val end = LocalDate(2026, 10, 8)
            setToday(end)
            val theme = org.isoron.uhabits.core.ui.views.WidgetTheme()

            val habits = (0 until 6).map { index -> modelFactory.buildHabit().apply {
                uuid = "widget-batch-$index"; type = HabitType.NUMERICAL; unit = "min"
                targetValue = 300.0; frequency = Frequency(1, 7)
                originalEntries.add(Entry(end.minus(30), 0)); originalEntries.add(Entry(end, 30000)); recompute()
            } }
            habits.forEachIndexed { index, habit ->
                val instant = startOfLocalDay(end.minus(index)) + 1000
                facts.record(Contribution(ContributionId("w-credit-$index"), HabitRef(habit.uuid!!),
                    ContributionSourceType.FOCUS_SESSION, "source-$index", 60000, instant, instant, instant))
            }

            // 1. Single widget snapshot preload
            selects = 0
            val singleSnapshots = EffectiveHabitReadSnapshotProvider(facts).prepare(
                listOf(habits.first()), end.minus(30), end, "single-widget", 1,
                EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
            )
            val singleSelects = selects
            assertEquals(2, singleSelects)

            // Presenting all six widgets for this habit consumes snapshot with 0 extra queries
            val singleSnapshot = singleSnapshots.getValue(HabitRef(habits.first().uuid!!))
            org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.checkmark(habits.first(), singleSnapshot, end)
            org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.target(singleSnapshot, end)
            org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.history(habits.first(), singleSnapshot, theme)
            org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.score(habits.first(), singleSnapshot, 1, 0, theme)
            org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.streak(habits.first(), singleSnapshot, theme)
            org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.frequency(habits.first(), singleSnapshot, DayOfWeek.MONDAY, theme)
            assertEquals(singleSelects, selects)

            // 2. Multi-widget batch preload (6 distinct habits on screen)
            selects = 0
            val multiSnapshots = EffectiveHabitReadSnapshotProvider(facts).prepare(
                habits, end.minus(30), end, "multi-widget", 1,
                EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
            )
            val multiSelects = selects
            assertEquals(2, multiSelects)

            // All 6 widgets presented across all 6 habits
            habits.forEach { habit ->
                val snap = multiSnapshots.getValue(HabitRef(habit.uuid!!))
                org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.checkmark(habit, snap, end)
                org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.target(snap, end)
                org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.history(habit, snap, theme)
                org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.score(habit, snap, 1, 0, theme)
                org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.streak(habit, snap, theme)
                org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.frequency(habit, snap, DayOfWeek.MONDAY, theme)
            }
            assertEquals(multiSelects, selects)

            // 3. Contribution correction
            val removedResult = facts.removeById(ContributionId("w-credit-0"), startOfLocalDay(end) + 2000)
            assertEquals(ContributionRemovalResult.REMOVED, removedResult)
            selects = 0
            val correctedSnapshots = EffectiveHabitReadSnapshotProvider(facts).prepare(
                listOf(habits.first()), end.minus(30), end, "corrected-widget", 2,
                EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
            )
            assertEquals(2, selects)
            val correctedSnapshot = correctedSnapshots.getValue(HabitRef(habits.first().uuid!!))
            val checkmarkState = org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter.checkmark(habits.first(), correctedSnapshot, end)
            assertEquals(30000L, checkmarkState.quantity)
            assertEquals(2, selects)

            // 4. Cold-start refresh
            selects = 0
            val coldStartSnapshots = EffectiveHabitReadSnapshotProvider(facts).prepare(
                habits, end.minus(30), end, "cold-start", 1,
                EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
            )
            assertEquals(2, selects)
            assertEquals(6, coldStartSnapshots.size)
        } finally { database.close() }
    }

    @Test fun csvExportUsesZeroPerDayOrPerHabitQueriesAfterPreload() = runTest {
        val database = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(database)
            var selects = 0
            val counted = object : Database by database {
                override fun prepareStatement(sql: String): PreparedStatement {
                    if (sql.trimStart().startsWith("SELECT") && sql.contains("FROM Contributions")) selects++
                    return database.prepareStatement(sql)
                }
            }
            val facts = SQLiteContributionRepository(counted)
            val end = LocalDate(2026, 10, 8)
            setToday(end)
            val testHabitList = modelFactory.buildHabitList()
            val habits = (0 until 50).map { index -> modelFactory.buildHabit().apply {
                uuid = "csv-batch-$index"; name = "Habit $index"; type = HabitType.NUMERICAL; unit = "min"
                targetValue = 300.0; frequency = Frequency(1, 7); dayTier = DayTier.MINIMUM
                originalEntries.add(Entry(end.minus(30), 0)); originalEntries.add(Entry(end, 0)); recompute()
            } }
            habits.forEach { testHabitList.add(it) }
            habits.forEachIndexed { index, habit ->
                val instant = startOfLocalDay(end.minus(index % 30)) + 1000
                facts.record(Contribution(ContributionId("csv-credit-$index"), HabitRef(habit.uuid!!),
                    ContributionSourceType.FOCUS_SESSION, "source-$index", 45000, instant, instant, instant))
            }
            selects = 0
            val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
            val provider: (List<Habit>, LocalDate, LocalDate) -> Map<HabitRef, EffectiveHabitReadSnapshot> = { hList, from, to ->
                EffectiveHabitReadSnapshotProvider(facts).prepare(hList, from, to, "csv-batch", 1, calendar)
            }

            val exporter = HabitsCSVExporter(testHabitList, habits, provider)
            val archiveBytes = exporter.writeArchive()

            // 50 habits in a single batch <= 400 IDs uses exactly 2 queries (1 for active, 1 for removed)
            assertEquals(2, selects)
            assertTrue(archiveBytes.isNotEmpty())

            val entries = ZipReader(archiveBytes).entries()
            assertNotNull(entries.find { it.name == "Effective.csv" })
            assertNotNull(entries.find { it.name == "EffectiveScores.csv" })
            assertEquals(50, entries.count { it.name.endsWith("/Effective.csv") })
            assertEquals(50, entries.count { it.name.endsWith("/EffectiveScores.csv") })

            // No extra queries occurred during writing individual files or matrices
            assertEquals(2, selects)
        } finally { database.close() }
    }
}
