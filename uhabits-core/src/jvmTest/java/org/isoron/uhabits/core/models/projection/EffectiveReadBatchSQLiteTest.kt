package org.isoron.uhabits.core.models.projection

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.*
import org.isoron.platform.time.*
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.sqlite.*
import org.isoron.uhabits.core.models.*
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
}
