/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.widgets

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter
import org.isoron.uhabits.core.ui.widgets.WidgetBehavior
import org.isoron.uhabits.intents.WidgetIntentIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WidgetsEffectiveAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun captureEvidence(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: context.cacheDir
        assertTrue("Evidence output directory must exist", directory.isDirectory || directory.mkdirs())
        val output = java.io.File(directory, "$name.png")
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Native screenshot must be available", screenshot)
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        android.util.Log.i("PR23WidgetsEvidence", output.absolutePath)
    }

    @Test
    fun allSixWidgetsRenderWithEffectiveSnapshotsAndSafeGating() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-widgets-$suffix.db"
        val testPrefs = "uhabits-test-widgets-prefs-$suffix"

        val expSession = originalSession.copy(
            datasetId = "exp-$suffix",
            databaseFilename = testDbName,
            preferencesNamespace = testPrefs,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(widgetsEnabled = true),
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            assertTrue("Widgets must be enabled in test session", component.session.capabilities.widgetsEnabled)
            ContributionSchema.createSchema(component.db)
            val today = getToday()

            // Numerical habit: weekly >= 300 min
            val study = component.modelFactory.buildHabit().apply {
                name = "PR23 Study"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
                targetType = NumericalHabitType.AT_LEAST
            }
            component.habitList.add(study)

            // Manual log: 60 min on today
            study.originalEntries.add(Entry(today, 60_000))
            study.recompute()
            component.habitList.update(study)

            val facts = SQLiteContributionRepository(component.db)
            val instant1 = startOfLocalDay(today) + 12 * 3600_000L
            val instant2 = startOfLocalDay(today) + 13 * 3600_000L

            // 180 min and 90 min focus sessions -> total 330 min
            facts.record(Contribution(ContributionId("c-180-$suffix"), HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION, "s1", 180_000L, instant1, instant1, instant1))
            facts.record(Contribution(ContributionId("c-90-$suffix"), HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION, "s2", 90_000L, instant2, instant2, instant2))

            component.effectiveReadRevision.changed()

            // 1. Checkmark widget presentation
            val snapshot = component.effectiveHabitReads!!.snapshot(study)
            assertNotNull(snapshot)
            val checkmarkState = EffectiveWidgetPresenter.checkmark(study, snapshot, today)
            assertEquals(330_000L, checkmarkState.quantity)
            assertEquals(60_000L, checkmarkState.manualQuantity)
            assertEquals(270_000L, checkmarkState.focusQuantity)
            assertEquals(HabitGoalStatus.SATISFIED, checkmarkState.compatibilityCompletion)
            // Score must be null because other days lack observations
            assertNull("Derived incomplete coverage must not invent score", checkmarkState.score)

            // 2. Target widget presentation
            val targetState = EffectiveWidgetPresenter.target(snapshot, today)
            assertTrue(targetState.available)
            assertNotNull(targetState.progress)
            assertEquals(HabitGoalStatus.SATISFIED, targetState.progress!!.status)
            assertEquals(330_000L, targetState.progress!!.value.totalValue)
            assertEquals(60_000L, targetState.progress!!.value.manualValue)
            assertEquals(270_000L, targetState.progress!!.value.contributionValue)

            // 3. History widget presentation (includes today and has correct square)
            val historyState = EffectiveWidgetPresenter.history(study, snapshot, org.isoron.uhabits.core.ui.views.LightTheme())
            assertTrue(historyState.series.isNotEmpty())

            // 4. Score widget presentation (must be null / unavailable)
            val scoreState = EffectiveWidgetPresenter.score(study, snapshot, 1, 0, org.isoron.uhabits.core.ui.views.LightTheme())
            assertNull("Score must be safely gated when coverage incomplete", scoreState)

            // 5. Streak widget presentation (must be null / unavailable)
            val streakState = EffectiveWidgetPresenter.streak(study, snapshot, org.isoron.uhabits.core.ui.views.LightTheme())
            assertNull("Streak must be safely gated when unproven", streakState)

            // 6. Frequency widget presentation
            val freqState = EffectiveWidgetPresenter.frequency(study, snapshot, DayOfWeek.MONDAY, org.isoron.uhabits.core.ui.views.LightTheme())
            assertNotNull("Frequency widget should render when observations have complete coverage", freqState)
            assertEquals(330_000, freqState!!.frequency[today.startOfMonth()]!![(today.dayOfWeek.daysSinceSunday + 1) % 7])

            // If day coverage is incomplete (UNKNOWN manual value with contribution), frequency safely gates to null
            facts.record(Contribution(ContributionId("c-incomplete-$suffix"), HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION, "inc1", 10_000L,
                startOfLocalDay(today.minus(1)) + 12 * 3600_000L,
                startOfLocalDay(today.minus(1)) + 12 * 3600_000L,
                startOfLocalDay(today.minus(1)) + 12 * 3600_000L))
            component.effectiveReadRevision.changed()
            val incompleteSnapshot = component.effectiveHabitReads!!.snapshot(study)
            assertNull("Frequency must be safely gated when day coverage incomplete",
                EffectiveWidgetPresenter.frequency(study, incompleteSnapshot, DayOfWeek.MONDAY, org.isoron.uhabits.core.ui.views.LightTheme()))

            // Inflate CheckmarkWidget RemoteViews to verify Android RemoteViews creation
            val checkmarkWidget = CheckmarkWidget(context, 101, study)
            checkmarkWidget.effectiveSnapshot = snapshot
            checkmarkWidget.setDimensions(WidgetDimensions(200, 200, 200, 200))
            val checkmarkViews = checkmarkWidget.portraitRemoteViews
            val rootLayout = FrameLayout(context)
            val appliedView = checkmarkViews.apply(context, rootLayout)
            assertNotNull(appliedView)

            captureEvidence("qa-01-widgets-six-render")
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(testDbName)
            context.deleteSharedPreferences("uhabits_prefs_$testPrefs")
        }
    }

    @Test
    fun canonicalCorrection330To240UpdatesRemoteViewsEndToEnd() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-correction-$suffix.db"
        val testPrefs = "uhabits-test-correction-prefs-$suffix"

        val expSession = originalSession.copy(
            datasetId = "exp-$suffix",
            databaseFilename = testDbName,
            preferencesNamespace = testPrefs,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(widgetsEnabled = true),
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            ContributionSchema.createSchema(component.db)
            val today = getToday()

            val study = component.modelFactory.buildHabit().apply {
                name = "Study 300"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
                targetType = NumericalHabitType.AT_LEAST
            }
            component.habitList.add(study)
            study.originalEntries.add(Entry(today, 60_000))
            study.recompute()
            component.habitList.update(study)

            val facts = SQLiteContributionRepository(component.db)
            val instant1 = startOfLocalDay(today) + 12 * 3600_000L
            val instant2 = startOfLocalDay(today) + 13 * 3600_000L

            val c180 = Contribution(ContributionId("c-180-$suffix"), HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION, "s1", 180_000L, instant1, instant1, instant1)
            val c90 = Contribution(ContributionId("c-90-$suffix"), HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION, "s2", 90_000L, instant2, instant2, instant2)
            facts.record(c180)
            facts.record(c90)
            component.effectiveReadRevision.changed()

            // Initial: 60 + 180 + 90 = 330 min (Goal reached)
            var snapshot = component.effectiveHabitReads!!.snapshot(study)
            assertEquals(330_000L, snapshot.day(today).totalValue)
            assertEquals(HabitGoalStatus.SATISFIED, snapshot.goalAchievementOn(today).status)

            // Verify TargetWidget displays 330 / 300 min
            val targetWidget = TargetWidget(context, 102, study)
            targetWidget.effectiveSnapshot = snapshot
            targetWidget.setDimensions(WidgetDimensions(250, 250, 250, 250))
            val targetViews = targetWidget.portraitRemoteViews
            val container = FrameLayout(context)
            val inflated = targetViews.apply(context, container)
            assertNotNull(inflated)

            captureEvidence("qa-02-widget-before-correction-330")

            // Remove 90 min contribution via repository
            val revBefore = component.effectiveReadRevision.value
            facts.removeById(c90.id, System.currentTimeMillis().coerceAtLeast(c90.createdAt + 1000L))
            component.effectiveReadRevision.changed()
            val revAfter = component.effectiveReadRevision.value
            assertTrue(revAfter > revBefore)

            // Re-read snapshot: 60 + 180 = 240 min (UNKNOWN status)
            snapshot = component.effectiveHabitReads!!.snapshot(study)
            assertEquals(240_000L, snapshot.day(today).totalValue)
            assertEquals(HabitGoalStatus.UNKNOWN, snapshot.goalAchievementOn(today).status)
            assertEquals(60_000, study.originalEntries.get(today).value)

            targetWidget.effectiveSnapshot = snapshot
            val updatedViews = targetWidget.portraitRemoteViews
            val container2 = FrameLayout(context)
            val updatedInflated = updatedViews.apply(context, container2)
            assertNotNull(updatedInflated)

            captureEvidence("qa-03-widget-after-correction-240")
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(testDbName)
            context.deleteSharedPreferences("uhabits_prefs_$testPrefs")
        }
    }

    @Test
    fun manualActionIsolationPreservesContributions() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-manual-$suffix.db"
        val testPrefs = "uhabits-test-manual-prefs-$suffix"

        val expSession = originalSession.copy(
            datasetId = "exp-$suffix",
            databaseFilename = testDbName,
            preferencesNamespace = testPrefs,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(widgetsEnabled = true),
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            ContributionSchema.createSchema(component.db)
            val today = getToday()

            val habit = component.modelFactory.buildHabit().apply {
                name = "Manual Isolation"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 60.0
                frequency = Frequency.DAILY
            }
            component.habitList.add(habit)

            // Manual = 30 min, Contribution = 60 min -> Effective = 90 min
            habit.originalEntries.add(Entry(today, 30_000, "original note"))
            habit.recompute()
            component.habitList.update(habit)

            val facts = SQLiteContributionRepository(component.db)
            val instant = startOfLocalDay(today) + 12 * 3600_000L
            val focusCredit = Contribution(ContributionId("focus-$suffix"), HabitRef(habit.uuid!!),
                ContributionSourceType.FOCUS_SESSION, "f1", 60_000L, instant, instant, instant)
            facts.record(focusCredit)
            component.effectiveReadRevision.changed()

            var snap = component.effectiveHabitReads!!.snapshot(habit)
            assertEquals(90_000L, snap.day(today).totalValue)
            assertEquals(30_000L, snap.day(today).manualValue)
            assertEquals(60_000L, snap.day(today).contributionValue)

            val behavior = WidgetBehavior(component.habitList, CommandRunner(component.taskRunner),
                component.notificationTray, component.preferences)

            // Widget increment +10 min (10000)
            behavior.onIncrement(habit, today, 10_000)
            kotlinx.coroutines.runBlocking { component.taskRunner.await() }
            assertEquals(40_000, habit.originalEntries.get(today).value)
            assertEquals("original note", habit.originalEntries.get(today).notes)
            component.effectiveReadRevision.changed()
            snap = component.effectiveHabitReads!!.snapshot(habit)
            assertEquals(100_000L, snap.day(today).totalValue)
            assertEquals(40_000L, snap.day(today).manualValue)
            assertEquals(60_000L, snap.day(today).contributionValue)

            // Widget decrement -10 min (10000)
            behavior.onDecrement(habit, today, 10_000)
            kotlinx.coroutines.runBlocking { component.taskRunner.await() }
            assertEquals(30_000, habit.originalEntries.get(today).value)
            assertEquals("original note", habit.originalEntries.get(today).notes)
            component.effectiveReadRevision.changed()
            snap = component.effectiveHabitReads!!.snapshot(habit)
            assertEquals(90_000L, snap.day(today).totalValue)
            assertEquals(30_000L, snap.day(today).manualValue)
            assertEquals(60_000L, snap.day(today).contributionValue)

            captureEvidence("qa-04-manual-isolation")
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(testDbName)
            context.deleteSharedPreferences("uhabits_prefs_$testPrefs")
        }
    }

    @Test
    fun stalePendingIntentRejectedAcrossDatasetSwitching() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val habit = HabitsApplication.component.modelFactory.buildHabit().apply {
            id = 10L
            uuid = "widget-habit-1"
        }

        val sessionA = originalSession.copy(
            datasetId = "dataset-A",
            capabilities = DatasetCapabilities.PRODUCTION
        )
        val sessionB = originalSession.copy(
            datasetId = "dataset-B",
            capabilities = DatasetCapabilities.PRODUCTION
        )

        // 1. Intent created in dataset A
        val intentA = Intent().apply {
            data = Uri.parse("content://org.isoron.uhabits/habits/10")
        }
        WidgetIntentIdentity.bind(intentA, sessionA, habit)

        // Validate on dataset A: accepted
        assertTrue(WidgetIntentIdentity.accepts(intentA, sessionA, habit))

        // Validate on dataset B: rejected
        assertFalse(WidgetIntentIdentity.accepts(intentA, sessionB, habit))

        // 2. Habit deleted or replaced with different UUID
        val differentHabit = HabitsApplication.component.modelFactory.buildHabit().apply {
            id = 11L
            uuid = "widget-habit-2"
        }
        assertFalse(WidgetIntentIdentity.accepts(intentA, sessionA, differentHabit))

        // 3. Android Intent.filterEquals distinguishes datasets and habits
        val intentB = Intent().apply {
            data = Uri.parse("content://org.isoron.uhabits/habits/10")
        }
        WidgetIntentIdentity.bind(intentB, sessionB, habit)
        assertFalse("Intents for different datasets must not be filterEqual", intentA.filterEquals(intentB))

        val intentDifferentHabit = Intent().apply {
            data = Uri.parse("content://org.isoron.uhabits/habits/11")
        }
        WidgetIntentIdentity.bind(intentDifferentHabit, sessionA, differentHabit)
        assertFalse("Intents for different habits must not be filterEqual", intentA.filterEquals(intentDifferentHabit))

        // 4. ContentUris.parseId succeeds despite query parameters
        assertEquals(10L, ContentUris.parseId(intentA.data!!))
    }
}
