/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.statistics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsOverviewStateBuilder
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsPeriod
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsReportStateBuilder
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsResultStatus
import androidx.test.core.app.ActivityScenario
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.activities.statistics.views.CompareCalendarView
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StatisticsEffectiveAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()


    private fun captureCompareEvidence(name: String) {
        instrumentation.waitForIdleSync()
        // Assertions inspect model/UI values; let the corresponding display frame reach SurfaceFlinger.
        Thread.sleep(500)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: context.cacheDir
        assertTrue("Evidence output directory must exist", directory.isDirectory || directory.mkdirs())
        val output = java.io.File(directory, "$name.png")
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Native screenshot must be available", screenshot)
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        android.util.Log.i("PR23CompareEvidence", output.absolutePath)
    }

    @Test
    fun statisticsReportEffectiveProgressAndLiveInvalidationOnDevice() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-stat-$suffix.db"
        val testPrefs = "uhabits-test-stat-prefs-$suffix"

        val expSession = originalSession.copy(
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
            assertTrue("Experimental capabilities must disable sync", !component.session.capabilities.syncEnabled)
            assertTrue("Fresh fixture preferences must disable sync", !component.preferences.isSyncEnabled)
            assertTrue("Session must be experimental", component.session.isExperimental)
            assertNotNull("EffectiveHabitReadController must be present", component.effectiveHabitReads)

            ContributionSchema.createSchema(component.db)

            val today = getToday()
            val priorTuesday = today.minus(7)

            // Habit 1: Weekly AT_LEAST 300 min
            val studyHabit = component.modelFactory.buildHabit().apply {
                name = "Study"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
                targetType = NumericalHabitType.AT_LEAST
                dayTier = DayTier.MINIMUM
            }

            // Habit 2: Daily AT_MOST 60 min
            val limitHabit = component.modelFactory.buildHabit().apply {
                name = "Limit"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 60.0
                frequency = Frequency.DAILY
                targetType = NumericalHabitType.AT_MOST
                dayTier = DayTier.MINIMUM
            }
            val booleanHabit = component.modelFactory.buildHabit().apply {
                name = "Boolean parity"
                frequency = Frequency.DAILY
            }

            instrumentation.runOnMainSync {
                component.habitList.add(studyHabit)
                component.habitList.add(limitHabit)
                component.habitList.add(booleanHabit)
            }
            instrumentation.waitForIdleSync()
            studyHabit.originalEntries.add(Entry(today, 60_000))
            studyHabit.recompute()
            booleanHabit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
            booleanHabit.recompute()
            limitHabit.originalEntries.add(Entry(today.minus(1), Entry.SKIP))
            limitHabit.recompute()

            val contributionRepo = SQLiteContributionRepository(component.db)
            val todayTs = startOfLocalDay(today) + 12 * 3600_000L
            val tuesdayTs = startOfLocalDay(priorTuesday) + 12 * 3600_000L

            // 180 min and 90 min Focus credits for Study today
            val c180 = Contribution(
                id = ContributionId("credit-180-$suffix"),
                targetHabit = HabitRef(studyHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-180",
                contributedValue = 180_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )
            val c90 = Contribution(
                id = ContributionId("credit-90-$suffix"),
                targetHabit = HabitRef(studyHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-90",
                contributedValue = 90_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )
            // 45 min Contribution on prior Tuesday (contribution-only date discovery)
            val c45 = Contribution(
                id = ContributionId("credit-45-$suffix"),
                targetHabit = HabitRef(studyHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-45",
                contributedValue = 45_000L,
                effectiveTimestamp = tuesdayTs,
                createdAt = tuesdayTs,
                updatedAt = tuesdayTs,
            )
            // 20 min Contribution for Limit habit today (with manual UNKNOWN)
            val c20 = Contribution(
                id = ContributionId("credit-20-$suffix"),
                targetHabit = HabitRef(limitHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-20",
                contributedValue = 20_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )

            contributionRepo.record(c180)
            contributionRepo.record(c90)
            contributionRepo.record(c45)
            contributionRepo.record(c20)
            contributionRepo.record(c20.copy(
                id = ContributionId("credit-skipped-$suffix"), sourceId = "focus-skipped",
                effectiveTimestamp = startOfLocalDay(today.minus(1)) + 12 * 3600_000L
            ))

            val weekStart = today.startOfWeek(DayOfWeek.MONDAY)
            val weekEnd = weekStart.plus(6)

            // Step 1: Read snapshots via controller
            val controller = component.effectiveHabitReads!!
            val initialSnapshots = controller.snapshots(listOf(studyHabit, limitHabit, booleanHabit), weekStart.minus(1), weekEnd)
            val booleanSnapshot = initialSnapshots.getValue(HabitRef(booleanHabit.uuid!!))
            assertTrue("Manual-only Boolean must retain legacy completion", booleanSnapshot.compatibilityCompletionOn(today).isCompleted)
            assertEquals(booleanHabit.scores[today].value, booleanSnapshot.scoreResult.on(today)!!, 0.000001)
            val limitSnapshot = initialSnapshots.getValue(HabitRef(limitHabit.uuid!!))
            assertTrue("UNKNOWN manual log remains incomplete with automatic credits", !limitSnapshot.day(today).hasCompleteCoverage)
            assertEquals(HabitGoalStatus.UNKNOWN, limitSnapshot.goalAchievementOn(today).status)
            assertTrue("SKIP remains excluded", limitSnapshot.day(today.minus(1)).isSkipped)
            assertEquals(HabitGoalStatus.SKIPPED, limitSnapshot.goalAchievementOn(today.minus(1)).status)
            assertEquals(20_000L, limitSnapshot.goalAchievementOn(today.minus(1)).progress.value.excludedContributionValue)
            assertEquals(20_000L, limitSnapshot.goalAchievementOn(today).progress.value.totalValue)

            // Step 2: Build Global Statistics report
            val report = StatisticsReportStateBuilder.build(
                habits = listOf(studyHabit, limitHabit),
                period = StatisticsPeriod.WEEK,
                start = weekStart,
                end = weekEnd,
                today = today,
                firstWeekday = DayOfWeek.MONDAY,
                filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
                snapshots = initialSnapshots
            )

            val studyResult = report.habits.first { it.habit.name == "Study" }
            // Effective = 60 + 180 + 90 = 330 min >= 300 min target
            assertEquals(330.0, studyResult.actual, 0.001)
            assertEquals(300.0, studyResult.target, 0.001)
            assertEquals(1.0, studyResult.progress, 0.001)
            assertEquals(StatisticsResultStatus.ON_TRACK, studyResult.status)

            // Limit habit has 20 min credit but incomplete coverage -> PENDING (not falsely COMPLETED)
            val limitResult = report.habits.first { it.habit.name == "Limit" }
            assertEquals(StatisticsResultStatus.PENDING, limitResult.status)

            // Contribution-only date discovery check: ALL-time report starts from prior Tuesday
            val allTimeSnapshots = controller.snapshots(listOf(studyHabit), today.minus(60), today)
            val discoveredStart = StatisticsReportStateBuilder.getHabitStartDate(studyHabit, today, allTimeSnapshots[HabitRef(studyHabit.uuid!!)])
            assertEquals(priorTuesday, discoveredStart)

            // Step 3: Overview check
            val overview = StatisticsOverviewStateBuilder.build(
                habits = listOf(studyHabit, limitHabit),
                start = weekStart,
                end = weekEnd,
                firstWeekday = DayOfWeek.MONDAY,
                snapshots = initialSnapshots
            )
            // Study focus minutes = 330 min
            assertTrue(overview.focusMinutes >= 330.0)

            // Step 4: Live tombstone via legitimate repository API
            val revisionBefore = component.effectiveReadRevision.value
            contributionRepo.removeById(c90.id, System.currentTimeMillis().coerceAtLeast(c90.createdAt + 1000L))
            val revisionAfter = component.effectiveReadRevision.value
            assertTrue("Effective revision must increment after removal", revisionAfter > revisionBefore)

            // Re-read snapshots at new revision
            val updatedSnapshots = controller.snapshots(listOf(studyHabit, limitHabit), weekStart, weekEnd)
            val updatedReport = StatisticsReportStateBuilder.build(
                habits = listOf(studyHabit, limitHabit),
                period = StatisticsPeriod.WEEK,
                start = weekStart,
                end = weekEnd,
                today = today,
                firstWeekday = DayOfWeek.MONDAY,
                filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
                snapshots = updatedSnapshots
            )

            val updatedStudyResult = updatedReport.habits.first { it.habit.name == "Study" }
            // Effective = 60 + 180 = 240 min < 300 min target
            assertEquals(240.0, updatedStudyResult.actual, 0.001)
            assertEquals(300.0, updatedStudyResult.target, 0.001)
            assertEquals(240.0 / 300.0, updatedStudyResult.progress, 0.001)
            assertEquals(StatisticsResultStatus.PENDING, updatedStudyResult.status)
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(testDbName)
            context.deleteSharedPreferences("uhabits_prefs_$testPrefs")
        }
    }

    @Test
    fun activeStatisticsActivityRefreshesOnLiveContributionRemoval() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-stat-live-$suffix.db"
        val testPrefs = "uhabits-test-stat-live-prefs-$suffix"

        val expSession = originalSession.copy(
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
            assertTrue("Experimental capabilities must disable sync", !component.session.capabilities.syncEnabled)
            assertTrue("Fresh fixture preferences must disable sync", !component.preferences.isSyncEnabled)
            ContributionSchema.createSchema(component.db)

            val today = getToday()

            // Habit 1: Weekly AT_LEAST 300 min
            val studyHabit = component.modelFactory.buildHabit().apply {
                name = "PR23 Study"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
                targetType = NumericalHabitType.AT_LEAST
                dayTier = DayTier.MINIMUM
                statisticsStartDate = today.minus(6)
            }

            instrumentation.runOnMainSync {
                component.habitList.add(studyHabit)
            }
            instrumentation.waitForIdleSync()
            studyHabit.originalEntries.add(Entry(today, 60_000))
            studyHabit.recompute()

            val contributionRepo = SQLiteContributionRepository(component.db)
            val todayTs = startOfLocalDay(today) + 12 * 3600_000L

            // 180 min and 90 min Focus credits for Study today -> 60 + 180 + 90 = 330 min
            val c180 = Contribution(
                id = ContributionId("live-180-$suffix"),
                targetHabit = HabitRef(studyHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-180",
                contributedValue = 180_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )
            val c90 = Contribution(
                id = ContributionId("live-90-$suffix"),
                targetHabit = HabitRef(studyHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-90",
                contributedValue = 90_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )

            contributionRepo.record(c180)
            contributionRepo.record(c90)

            // Launch MainActivity into STATISTICS destination
            val intent = MainActivity.intent(context, MainDestination.STATISTICS)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                var originalActivity: MainActivity? = null
                scenario.onActivity { originalActivity = it }

                // Helper to extract all visible texts from activity hierarchy
                fun getAllVisibleTexts(activity: MainActivity): List<String> {
                    val texts = mutableListOf<String>()
                    fun collect(view: android.view.View) {
                        if (view is android.widget.TextView && view.visibility == android.view.View.VISIBLE) {
                            texts.add(view.text.toString())
                        }
                        if (view is android.view.ViewGroup) {
                            for (i in 0 until view.childCount) {
                                collect(view.getChildAt(i))
                            }
                        }
                    }
                    collect(activity.window.decorView)
                    return texts
                }

                // 1. Wait for report to render 330
                var rendered330 = false
                var lastTexts = emptyList<String>()
                for (i in 0..50) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertSame("Statistics must refresh the same Activity", originalActivity, activity)
                        lastTexts = getAllVisibleTexts(activity)
                        if (lastTexts.any { it.contains("330") }) {
                            rendered330 = true
                        }
                    }
                    if (rendered330) break
                    Thread.sleep(100)
                }
                assertTrue("Report must display initial 330 value while active. Visible texts: $lastTexts", rendered330)

                // 2. Remove 90 min contribution via legitimate repository API while Activity remains alive
                val revBefore = component.effectiveReadRevision.value
                val removeResult = contributionRepo.removeById(c90.id, System.currentTimeMillis().coerceAtLeast(c90.createdAt + 1000L))
                assertEquals(org.isoron.uhabits.core.contribution.ContributionRemovalResult.REMOVED, removeResult)
                assertTrue("Revision must increment", component.effectiveReadRevision.value > revBefore)

                // 3. Wait for reactive refresh to update visible report to 240 without recreating or reopening screen
                var updatedTo240 = false
                for (i in 0..50) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertSame("Statistics must refresh the same Activity", originalActivity, activity)
                        lastTexts = getAllVisibleTexts(activity)
                        if (lastTexts.any { it.contains("240") }) {
                            updatedTo240 = true
                        }
                    }
                    if (updatedTo240) break
                    Thread.sleep(100)
                }
                assertTrue("Visible report value must change to 240 on live screen. Visible texts: $lastTexts", updatedTo240)
            }
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(testDbName)
            context.deleteSharedPreferences("uhabits_prefs_$testPrefs")
        }
    }

    @Test
    fun activeCompareHabitsActivityRefreshesOnLiveContributionRemoval() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-cmp-live-$suffix.db"
        val testPrefs = "uhabits-test-cmp-live-prefs-$suffix"

        val expSession = originalSession.copy(
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
            assertTrue("Experimental capabilities must disable sync", !component.session.capabilities.syncEnabled)
            assertTrue("Fresh fixture preferences must disable sync", !component.preferences.isSyncEnabled)
            ContributionSchema.createSchema(component.db)

            val today = getToday()

            val studyHabit = component.modelFactory.buildHabit().apply {
                name = "Compare Study"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
                targetType = NumericalHabitType.AT_LEAST
                dayTier = DayTier.MINIMUM
                statisticsStartDate = today.minus(6)
            }

            val secondHabit = component.modelFactory.buildHabit().apply {
                name = "Compare Read"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 60.0
                frequency = Frequency.DAILY
                targetType = NumericalHabitType.AT_LEAST
                dayTier = DayTier.MINIMUM
                statisticsStartDate = today.minus(6)
            }

            instrumentation.runOnMainSync {
                component.habitList.add(studyHabit)
                component.habitList.add(secondHabit)
            }
            instrumentation.waitForIdleSync()
            studyHabit.originalEntries.add(Entry(today, 60_000))
            studyHabit.recompute()
            secondHabit.originalEntries.add(Entry(today, 60_000))
            secondHabit.recompute()

            val contributionRepo = SQLiteContributionRepository(component.db)
            val todayTs = startOfLocalDay(today) + 12 * 3600_000L

            val c180 = Contribution(
                id = ContributionId("cmp-180-$suffix"),
                targetHabit = HabitRef(studyHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-180",
                contributedValue = 180_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )
            val c90 = Contribution(
                id = ContributionId("cmp-90-$suffix"),
                targetHabit = HabitRef(studyHabit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-90",
                contributedValue = 90_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )

            contributionRepo.record(c180)
            contributionRepo.record(c90)

            val intent = org.isoron.uhabits.intents.IntentFactory().startCompareHabitsActivity(
                context = context,
                initialHabitIds = longArrayOf(studyHabit.id!!, secondHabit.id!!),
                initialPeriod = "WEEK"
            )

            ActivityScenario.launch<CompareHabitsActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()

                var originalActivity: CompareHabitsActivity? = null
                scenario.onActivity { originalActivity = it }
                fun awaitRenderedGoal(expected: Long, status: HabitGoalStatus, minimumRevision: Long) {
                    var matched = false
                    var evidence = "No rendered snapshot"
                    repeat(51) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            assertSame("Compare must refresh the same Activity", originalActivity, activity)
                            val snapshot = activity.renderedSnapshots[HabitRef(studyHabit.uuid!!)]
                            if (snapshot != null) {
                                val goal = snapshot.goalAchievementOn(today).progress
                                val text = activity.window.decorView.findViewWithTag<android.widget.TextView>("compare_goal_${studyHabit.id}")?.text?.toString().orEmpty()
                                evidence = "total=${goal.value.totalValue}, status=${goal.status}, text=$text"
                                if (snapshot.revision >= minimumRevision && goal.value.totalValue == expected && goal.status == status && text.contains((expected / 1000).toString()) && text.contains("300")) {
                                    val calendar = activity.window.decorView.findViewWithTag<CompareCalendarView>("compare_calendar_${studyHabit.id}")
                                    assertNotNull("Actual Compare calendar must be rendered", calendar)
                                    assertEquals(status, calendar.goalStatusOn(today))
                                    val report = StatisticsReportStateBuilder.build(
                                        habits = listOf(studyHabit), period = StatisticsPeriod.WEEK,
                                        start = goal.from, end = goal.to, today = today,
                                        firstWeekday = snapshot.calendar.firstWeekday,
                                        filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL),
                                        snapshots = activity.renderedSnapshots
                                    )
                                    val result = report.habits.single()
                                    assertEquals(expected / 1000.0, result.actual, 0.001)
                                    assertEquals(300.0, result.target, 0.001)
                                    assertEquals(if (status == HabitGoalStatus.SATISFIED) StatisticsResultStatus.ON_TRACK else StatisticsResultStatus.PENDING, result.status)
                                    matched = true
                                }
                            }
                        }
                        if (matched) return
                        Thread.sleep(100)
                    }
                    assertTrue("Rendered Compare and Statistics must agree: $evidence", matched)
                }
                awaitRenderedGoal(330_000L, HabitGoalStatus.SATISFIED, component.effectiveReadRevision.value)
                captureCompareEvidence("compare-before-tombstone")
                val revBefore = component.effectiveReadRevision.value
                val removeResult = contributionRepo.removeById(c90.id, System.currentTimeMillis().coerceAtLeast(c90.createdAt + 1000L))
                assertEquals(org.isoron.uhabits.core.contribution.ContributionRemovalResult.REMOVED, removeResult)
                assertTrue("Revision must increment on removal", component.effectiveReadRevision.value > revBefore)
                awaitRenderedGoal(240_000L, HabitGoalStatus.UNKNOWN, component.effectiveReadRevision.value)
                captureCompareEvidence("compare-after-tombstone")
                val oldFirstWeekday = component.preferences.firstWeekday
                val newFirstWeekday = if (oldFirstWeekday == DayOfWeek.MONDAY) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
                // Preferences.firstWeekday reads this verified key from the session's physical namespace.
                assertTrue(context.getSharedPreferences("uhabits_prefs_$testPrefs", Context.MODE_PRIVATE)
                    .edit().putString("pref_first_weekday", if (newFirstWeekday == DayOfWeek.SUNDAY) "1" else "2").commit())
                assertEquals(newFirstWeekday, component.preferences.firstWeekday)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame("Live calendar settings must retain the Activity", originalActivity, activity)
                    val snapshot = activity.renderedSnapshots.getValue(HabitRef(studyHabit.uuid!!))
                    assertEquals(newFirstWeekday, snapshot.calendar.firstWeekday)
                    val calendar = activity.window.decorView.findViewWithTag<CompareCalendarView>("compare_calendar_${studyHabit.id}")
                    assertEquals(snapshot.goalAchievementOn(today).status, calendar.goalStatusOn(today))
                }
            }
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(testDbName)
            context.deleteSharedPreferences("uhabits_prefs_$testPrefs")
        }
    }
}
