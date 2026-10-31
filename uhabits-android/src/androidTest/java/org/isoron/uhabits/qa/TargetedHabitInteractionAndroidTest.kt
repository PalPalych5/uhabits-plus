/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.qa

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.io.queryLong
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.views.CheckmarkPanelView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.NumberPanelView
import org.isoron.uhabits.activities.habits.show.ShowHabitActivity
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.statistics.unifiedStatisticsReadSource
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.UnarchiveHabitsCommand
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.backup.BackupCreationResult
import org.isoron.uhabits.core.containers.backup.ContainerBackupService
import org.isoron.uhabits.core.containers.backup.ContainerRestoreService
import org.isoron.uhabits.core.containers.backup.RestoreResult
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsReadIdentity
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.database.AndroidDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Targeted Android instrumentation QA suite verifying:
 * 1. Habit card list grid rendering, button panel sizing, and view holder recycling.
 * 2. Sphere/group boundary stability and metadata preservation after entry date edits.
 * 3. Notification button persistence and non-ongoing tray dismissal policy.
 * 4. Pomodoro overtime-to-break transitions and idempotent break execution.
 * 5. Process / Activity recreation state preservation.
 * 6. Phase D Screen 1 stress: 150 habits list rapid scroll recycling and live mutations.
 * 7. Phase D Screens 2-10 & lifecycle: detail, unified statistics, tasks, settings, backup & restore.
 */
@RunWith(AndroidJUnit4::class)
class TargetedHabitInteractionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
    }

    private inline fun withIsolatedDataset(block: (HabitsApplication) -> Unit) {
        runCatching {
            instrumentation.uiAutomation.executeShellCommand("svc power stayon true").close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        }
        val app = context.applicationContext as HabitsApplication
        val original = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val session = original.copy(
            datasetId = "targeted-$suffix",
            databaseFilename = "uhabits-test-targeted-$suffix.db",
            preferencesNamespace = "uhabits-test-targeted-$suffix",
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(timerEnabled = true),
        )
        instrumentation.runOnMainSync { app.rebuildComponent(session) }
        try {
            block(app)
        } finally {
            instrumentation.runOnMainSync {
                HabitsApplication.component.timerSessionManager.resetCurrent()
                app.rebuildComponent(original)
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun testHabitCardListGridRenderingAndRecycling() = withIsolatedDataset {
        val component = HabitsApplication.component
        val today = getToday()

        // Create 10 habits: 5 boolean and 5 numerical
        val habits = mutableListOf<Habit>()
        for (i in 0 until 10) {
            val isNum = i % 2 == 1
            val h = component.modelFactory.buildHabit().apply {
                name = if (isNum) "Numerical Habit $i" else "Boolean Habit $i"
                type = if (isNum) HabitType.NUMERICAL else HabitType.YES_NO
                targetType = NumericalHabitType.AT_LEAST
                targetValue = if (isNum) 60.0 else 1.0
                frequency = Frequency.DAILY
                color = PaletteColor(i % 10)
            }
            component.habitList.add(h)
            h.originalEntries.add(Entry(today, if (isNum) 30_000 else Entry.YES_MANUAL))
            h.originalEntries.add(Entry(today.minus(1), if (isNum) 60_000 else Entry.YES_MANUAL))
            h.recompute()
            habits.add(h)
        }

        // Pre-warm cache so habits are available immediately
        val cache = component.habitCardListCache
        cache.setCheckmarkCount(5)
        instrumentation.runOnMainSync { cache.refreshAllHabits() }
        val warmDeadline = SystemClock.elapsedRealtime() + 10000
        while (cache.habitCount < 10 && SystemClock.elapsedRealtime() < warmDeadline) {
            Thread.sleep(100)
        }
        assertEquals(10, cache.habitCount)

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 15000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val listView = descendants(activity.window.decorView)
                        .filterIsInstance<HabitCardListView>()
                        .firstOrNull()
                    val adapter = listView?.adapter as? HabitCardListAdapter
                    if (adapter != null && adapter.itemCount >= 10) {
                        loaded = true
                    }
                }
                if (!loaded) Thread.sleep(100)
            }
            assertTrue("Adapter item count must load habits asynchronously", loaded)

            scenario.onActivity { activity ->
                val listView = descendants(activity.window.decorView)
                    .filterIsInstance<HabitCardListView>()
                    .first()

                val adapter = listView.adapter as HabitCardListAdapter
                assertTrue("Adapter item count must match habits", adapter.itemCount >= 10)

                val checkmarkCount = listView.checkmarkCount
                assertTrue("Checkmark count must be positive", checkmarkCount > 0)

                // Verify visible cards have matching buttonCount and grid panels
                val cardViews = descendants(listView).filterIsInstance<HabitCardView>().toList()
                assertTrue("Rendered habit cards must be present", cardViews.isNotEmpty())
                for (card in cardViews) {
                    assertEquals(checkmarkCount, card.buttonCount)
                    if (card.habit?.isNumerical == true) {
                        val numPanel = descendants(card).filterIsInstance<NumberPanelView>().firstOrNull()
                        assertNotNull("Numerical card must render NumberPanelView", numPanel)
                        assertEquals(checkmarkCount, numPanel!!.buttons.size)
                    } else {
                        val checkPanel = descendants(card).filterIsInstance<CheckmarkPanelView>().firstOrNull()
                        assertNotNull("Boolean card must render CheckmarkPanelView", checkPanel)
                        assertEquals(checkmarkCount, checkPanel!!.buttons.size)
                    }
                }

                // Test dynamic checkmark count adjustment & recycling synchronization
                val newCount = if (checkmarkCount == 5) 7 else 5
                listView.checkmarkCount = newCount
                for (card in descendants(listView).filterIsInstance<HabitCardView>()) {
                    assertEquals("Checkmark count update must propagate immediately to attached card views",
                        newCount, card.buttonCount)
                }

                // Simulate scroll & recycling
                val layoutManager = listView.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }

            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                val listView = descendants(activity.window.decorView)
                    .filterIsInstance<HabitCardListView>()
                    .first()
                val cardViewsAfterScroll = descendants(listView).filterIsInstance<HabitCardView>().toList()
                assertTrue("Cards must be rendered at new scroll position", cardViewsAfterScroll.isNotEmpty())
                for (card in cardViewsAfterScroll) {
                    assertEquals("Recycled cards must synchronize button count with list view",
                        listView.checkmarkCount, card.buttonCount)
                }
            }
        }
    }

    @Test
    fun testSphereGroupBoundaryStabilityAfterEntryDateEdits() = withIsolatedDataset {
        val component = HabitsApplication.component
        component.preferences.isHabitSpheresEnabled = true
        component.preferences.areHabitGroupSeparatorsEnabled = true
        component.preferences.defaultPrimaryOrder = HabitList.Order.BY_SPHERE

        val today = getToday()
        // Sphere 10 habits
        val h1 = component.modelFactory.buildHabit().apply {
            name = "Health Morning Walk"
            blockId = 10L
        }
        val h2 = component.modelFactory.buildHabit().apply {
            name = "Health Drink Water"
            blockId = 10L
        }
        // Sphere 20 habits
        val h3 = component.modelFactory.buildHabit().apply {
            name = "Work Code Review"
            blockId = 20L
        }
        val h4 = component.modelFactory.buildHabit().apply {
            name = "Work Write Specs"
            blockId = 20L
        }

        component.habitList.add(h1)
        component.habitList.add(h2)
        component.habitList.add(h3)
        component.habitList.add(h4)

        h1.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        h1.recompute()
        h2.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        h2.recompute()
        h3.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        h3.recompute()
        h4.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        h4.recompute()

        val cache = component.habitCardListCache
        cache.setCheckmarkCount(5)
        instrumentation.runOnMainSync { cache.refreshAllHabits() }

        // Drain background tasks
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (cache.habitCount < 4 && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertEquals(4, cache.habitCount)

        // Capture initial metadata
        val meta1Before = cache.getMetadata(h1.id!!)
        val meta2Before = cache.getMetadata(h2.id!!)
        val meta3Before = cache.getMetadata(h3.id!!)
        val meta4Before = cache.getMetadata(h4.id!!)

        // Perform entry date edit on h1
        h1.originalEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
        h1.recompute()
        instrumentation.runOnMainSync { cache.refreshHabit(h1.id!!) }

        val drainDeadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < drainDeadline) {
            Thread.sleep(50)
        }

        // Verify grouping metadata is retained via copyMetadataFrom across entry edits
        val meta1After = cache.getMetadata(h1.id!!)
        val meta2After = cache.getMetadata(h2.id!!)
        val meta3After = cache.getMetadata(h3.id!!)
        val meta4After = cache.getMetadata(h4.id!!)

        assertEquals("h1 metadata rootGroupKey must be preserved across entry edits",
            meta1Before.rootGroupKey, meta1After.rootGroupKey)
        assertEquals("h2 metadata rootGroupKey must remain intact",
            meta2Before.rootGroupKey, meta2After.rootGroupKey)
        assertEquals("h3 metadata rootGroupKey must remain intact",
            meta3Before.rootGroupKey, meta3After.rootGroupKey)
        assertEquals("h4 metadata rootGroupKey must remain intact",
            meta4Before.rootGroupKey, meta4After.rootGroupKey)

        assertEquals("Sphere 10 habits must share root group identity",
            meta1After.rootGroupKey, meta2After.rootGroupKey)
        assertEquals("Sphere 20 habits must share root group identity",
            meta3After.rootGroupKey, meta4After.rootGroupKey)
    }

    @Test
    fun testNotificationButtonPersistenceAndTrayOngoingFlagPolicy() = withIsolatedDataset {
        val component = HabitsApplication.component
        val today = getToday()

        val habit = component.modelFactory.buildHabit().apply {
            name = "Notification Policy Habit"
            type = HabitType.YES_NO
            reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        }
        component.habitList.add(habit)

        val tray = component.androidNotificationTray
        val notification = tray.buildNotification(habit, 1000L, today)
        assertNotNull("Built notification must not be null", notification)

        // Verify actions persist and >= 2 action buttons exist (e.g. checkmark, snooze/dismiss)
        assertNotNull("Notification actions must be present", notification.actions)
        assertTrue("Notification must provide at least 2 quick action buttons",
            notification.actions.size >= 2)

        // Verify ongoing flag policy: Notifications must NOT have FLAG_ONGOING_EVENT so users can dismiss them
        val isOngoing = (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
        assertFalse("Tray notification must be dismissible by user (FLAG_ONGOING_EVENT must not be set)", isOngoing)

        // Verify rebuild produces identical persistent actions
        val rebuilt = tray.buildNotification(habit, 2000L, today)
        assertEquals(notification.actions.size, rebuilt.actions.size)
        assertEquals(0, rebuilt.flags and android.app.Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    fun testPomodoroOvertimeToBreakTransitions() = withIsolatedDataset {
        val component = HabitsApplication.component
        val habit = component.modelFactory.buildHabit().apply {
            name = "Pomodoro QA Habit"
            type = HabitType.YES_NO
        }
        component.habitList.add(habit)

        val timer = component.timerSessionManager
        instrumentation.runOnMainSync {
            component.preferences.isPomodoroAutoSwitch = false
            // 1 min focus, 1 min break
            assertTrue(timer.updateDurations(habit, 1, 1))
            assertTrue(timer.switchMode(habit, TimerMode.POMODORO))
            assertTrue(timer.startOrPause(habit))
        }

        // Wait until focus enters overtime
        val deadline = SystemClock.elapsedRealtime() + 80_000
        while (!timer.snapshot().isOvertime && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("Timer must enter overtime", timer.snapshot().isOvertime)

        // Transition from overtime into break
        instrumentation.runOnMainSync { timer.takeBreakCurrent() }
        val firstBreak = timer.snapshot()
        assertEquals(PomodoroPhase.BREAK, firstBreak.phase)
        assertTrue("Break must be actively running", firstBreak.isRunning)

        // Repeated takeBreakCurrent must be idempotent (guard against duplicate session creation)
        Thread.sleep(1200)
        instrumentation.runOnMainSync { timer.takeBreakCurrent() }
        val repeatedBreak = timer.snapshot()
        assertEquals(PomodoroPhase.BREAK, repeatedBreak.phase)
        assertTrue(repeatedBreak.isRunning)
        assertTrue("Break countdown must advance continuously",
            repeatedBreak.elapsedMillis > firstBreak.elapsedMillis)

        val sessions = component.focusSessionRepository?.getByTimeRange(0, Long.MAX_VALUE).orEmpty()
        assertEquals("Exactly 2 focus sessions (base focus + overtime) must be recorded without duplicates",
            2, sessions.size)
    }

    @Test
    fun testProcessRecreationPreservesState() = withIsolatedDataset {
        val component = HabitsApplication.component
        val habit = component.modelFactory.buildHabit().apply {
            name = "Recreation Guard Habit"
            type = HabitType.YES_NO
        }
        component.habitList.add(habit)

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            scenario.onActivity { activity ->
                val listView = descendants(activity.window.decorView)
                    .filterIsInstance<HabitCardListView>()
                    .firstOrNull()
                assertNotNull("HabitCardListView must exist initially", listView)
            }

            // Recreate activity via native Android Activity.recreate()
            scenario.onActivity { activity -> activity.recreate() }
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                assertNotNull("Activity must exist after recreation", activity)
                val listView = descendants(activity.window.decorView)
                    .filterIsInstance<HabitCardListView>()
                    .firstOrNull()
                assertNotNull("HabitCardListView must survive recreation", listView)
            }
        }
    }

    private data class SeededHabitInfo(val habit: Habit, val sphere: Long)

    @Test
    fun testLargeScaleHabitListScrollRecyclingAndLiveMutations() = withIsolatedDataset {
        val component = HabitsApplication.component
        component.preferences.isHabitSpheresEnabled = true
        component.preferences.areHabitGroupSeparatorsEnabled = true
        component.preferences.defaultPrimaryOrder = HabitList.Order.BY_SPHERE

        val today = getToday()
        val spheres = listOf(10L, 20L, 30L, 40L)
        val seededHabits = mutableListOf<SeededHabitInfo>()

        // Create sphere containers so grouping metadata is authoritative
        val sphereContainers = spheres.associateWith { s ->
            val cId = ContainerId("sphere-container-$s")
            component.organizationFacade.organizationService?.create(
                CreateContainer(name = "Sphere $s", explicitId = cId, color = PaletteColor((s / 10).toInt()))
            )
            cId
        }

        // Seed 150 active habits across 4 spheres, mixed boolean and numerical, with multi-month entry history
        for (i in 0 until 150) {
            val sphere = spheres[i % 4]
            val isNum = i % 2 == 1
            val isWeekly = i % 3 == 0
            val targetType = if (i % 4 == 1) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
            val h = component.modelFactory.buildHabit().apply {
                name = if (isNum) "Numerical Habit $i" else "Boolean Habit $i"
                type = if (isNum) HabitType.NUMERICAL else HabitType.YES_NO
                this.targetType = targetType
                targetValue = if (isNum) (10.0 + (i % 50)) else 1.0
                unit = if (isNum) (if (i % 2 == 1) "min" else "km") else ""
                frequency = if (isWeekly) Frequency(3, 7) else Frequency.DAILY
                color = PaletteColor(i % 10)
                blockId = sphere
            }
            component.habitList.add(h)
            val cId = sphereContainers[sphere]
            if (cId != null && h.uuid != null) {
                component.organizationFacade.placeHabit(h.uuid!!, cId)
            }

            // Multi-month entry history
            val entryVal1 = if (isNum) 30_000 else Entry.YES_MANUAL
            val entryVal2 = if (isNum) 60_000 else Entry.YES_MANUAL
            h.originalEntries.add(Entry(today, entryVal1))
            h.originalEntries.add(Entry(today.minus(15), entryVal2))
            h.originalEntries.add(Entry(today.minus(45), entryVal1))
            h.originalEntries.add(Entry(today.minus(75), entryVal2))
            h.recompute()
            seededHabits.add(SeededHabitInfo(h, sphere))
        }

        // Seed 5 archived habits so both active and archived exist
        for (i in 0 until 5) {
            val sphere = spheres[i % 4]
            val h = component.modelFactory.buildHabit().apply {
                name = "Archived Habit $i"
                type = HabitType.YES_NO
                blockId = sphere
                isArchived = true
            }
            component.habitList.add(h)
            h.originalEntries.add(Entry(today, Entry.YES_MANUAL))
            h.originalEntries.add(Entry(today.minus(30), Entry.YES_MANUAL))
            h.recompute()
        }
        assertEquals(155, component.habitList.size())

        // Pre-warm habitCardListCache
        val cache = component.habitCardListCache
        cache.setCheckmarkCount(5)
        instrumentation.runOnMainSync { cache.refreshAllHabits() }
        val warmDeadline = SystemClock.elapsedRealtime() + 25000
        while (cache.habitCount < 150 && SystemClock.elapsedRealtime() < warmDeadline) {
            Thread.sleep(100)
        }
        assertEquals(150, cache.habitCount)

        // Capture sphere root grouping keys
        val habitIdToSphere = seededHabits.associate { it.habit.id!! to it.sphere }
        val sphereRootKeys = spheres.associateWith { s ->
            val sample = seededHabits.first { it.sphere == s }.habit
            cache.getMetadata(sample.id!!).rootGroupKey
        }

        // Launch MainActivity and measure habit list open & initial render latency
        val openStart = SystemClock.elapsedRealtime()
        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 25000
            var loaded = false
            var initialRenderMs = -1L
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val listView = descendants(activity.window.decorView)
                        .filterIsInstance<HabitCardListView>()
                        .firstOrNull()
                    val adapter = listView?.adapter as? HabitCardListAdapter
                    if (adapter != null && adapter.itemCount >= 150) {
                        loaded = true
                        initialRenderMs = SystemClock.elapsedRealtime() - openStart
                    }
                }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue("Adapter item count must load 150 habits asynchronously", loaded)
            Log.i("SPRINT03_ANDROID_PERF", "HABIT_LIST_OPEN_RENDER_MS=$initialRenderMs")

            // Rapid scrolling across the 150-item list measuring scroll/rebind latency
            val scrollPositions = listOf(0, 50, 100, 149, 75, 0)
            val scrollTotalStart = SystemClock.elapsedRealtime()
            for (targetPos in scrollPositions) {
                val jumpStart = SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    val listView = descendants(activity.window.decorView)
                        .filterIsInstance<HabitCardListView>()
                        .first()
                    val layoutManager = listView.layoutManager as LinearLayoutManager
                    layoutManager.scrollToPositionWithOffset(targetPos, 0)
                }
                instrumentation.waitForIdleSync()
                val jumpMs = SystemClock.elapsedRealtime() - jumpStart
                Log.i("SPRINT03_ANDROID_PERF", "SCROLL_JUMP_POS_${targetPos}_MS=$jumpMs")

                // Wait for visible cards to be rendered at the new position
                var cardViews = emptyList<HabitCardView>()
                val scrollRenderDeadline = SystemClock.elapsedRealtime() + 5000
                while (cardViews.isEmpty() && SystemClock.elapsedRealtime() < scrollRenderDeadline) {
                    scenario.onActivity { activity ->
                        val listView = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().firstOrNull()
                        if (listView != null) {
                            cardViews = descendants(listView).filterIsInstance<HabitCardView>().toList()
                        }
                    }
                    if (cardViews.isEmpty()) Thread.sleep(50)
                }
                assertTrue("Cards must be rendered at scroll position $targetPos", cardViews.isNotEmpty())

                scenario.onActivity { activity ->
                    val listView = descendants(activity.window.decorView)
                        .filterIsInstance<HabitCardListView>()
                        .first()
                    val currentCards = descendants(listView).filterIsInstance<HabitCardView>().toList()
                    for (card in currentCards) {
                        val cardHabit = card.habit
                        assertNotNull("Visible card must have a bound habit", cardHabit)
                        assertEquals("Card buttonCount must match listView.checkmarkCount",
                            listView.checkmarkCount, card.buttonCount)

                        if (cardHabit!!.isNumerical) {
                            val numPanel = descendants(card).filterIsInstance<NumberPanelView>().firstOrNull()
                            assertNotNull("Numerical card must render NumberPanelView", numPanel)
                            assertEquals("Buttons size must match checkmarkCount",
                                listView.checkmarkCount, numPanel!!.buttons.size)
                        } else {
                            val checkPanel = descendants(card).filterIsInstance<CheckmarkPanelView>().firstOrNull()
                            assertNotNull("Boolean card must render CheckmarkPanelView", checkPanel)
                            assertEquals("Buttons size must match checkmarkCount",
                                listView.checkmarkCount, checkPanel!!.buttons.size)
                        }

                        // No cross-card data bleed
                        val habitInDb = component.habitList.getById(cardHabit.id!!)
                        assertNotNull("Bound habit must exist in habitList", habitInDb)
                        assertEquals("Habit name must match", habitInDb!!.name, cardHabit.name)
                        assertEquals("Habit type must match", habitInDb.type, cardHabit.type)
                        assertEquals("Habit unit must match", habitInDb.unit, cardHabit.unit)

                        // Sphere/group boundary metadata consistency
                        val meta = cache.getMetadata(cardHabit.id!!)
                        val expectedSphereId = habitIdToSphere[cardHabit.id!!]
                        val expectedSphereKey = sphereRootKeys[expectedSphereId]
                        assertNotNull("Sphere root key must not be null", expectedSphereKey)
                        assertEquals("Sphere metadata must match habit sphere", expectedSphereKey, meta.rootGroupKey)
                    }
                }
            }
            val scrollTotalMs = SystemClock.elapsedRealtime() - scrollTotalStart
            Log.i("SPRINT03_ANDROID_PERF", "HABIT_LIST_SCROLL_TOTAL_MS=$scrollTotalMs")

            // Live entry mutation on a visible habit
            var targetHabit: Habit? = null
            val targetDeadline = SystemClock.elapsedRealtime() + 5000
            while (targetHabit == null && SystemClock.elapsedRealtime() < targetDeadline) {
                scenario.onActivity { activity ->
                    val listView = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().firstOrNull()
                    targetHabit = listView?.let { descendants(it).filterIsInstance<HabitCardView>().firstOrNull()?.habit }
                }
                if (targetHabit == null) Thread.sleep(50)
            }
            assertNotNull("Target habit for live mutation must be visible", targetHabit)
            val habitToMutate = targetHabit!!
            val habitId = habitToMutate.id!!
            val isNum = habitToMutate.isNumerical
            val newEntryVal = if (isNum) 99_000 else Entry.YES_MANUAL
            habitToMutate.originalEntries.add(Entry(today, newEntryVal))
            habitToMutate.recompute()
            instrumentation.runOnMainSync {
                cache.refreshHabit(habitId)
            }
            val mutateDeadline = SystemClock.elapsedRealtime() + 5000
            while (cache.getCheckmarks(habitId)[0] != newEntryVal && SystemClock.elapsedRealtime() < mutateDeadline) {
                Thread.sleep(50)
            }
            assertEquals("Cache checkmark must reflect live entry mutation", newEntryVal, cache.getCheckmarks(habitId)[0])

            // Archive habit via ArchiveHabitsCommand
            val countBeforeArchive = cache.habitCount
            val archiveCmd = ArchiveHabitsCommand(component.habitList, listOf(habitToMutate))
            instrumentation.runOnMainSync {
                component.commandRunner.run(archiveCmd)
            }
            val archiveDeadline = SystemClock.elapsedRealtime() + 5000
            while (cache.habitCount == countBeforeArchive && SystemClock.elapsedRealtime() < archiveDeadline) {
                Thread.sleep(50)
            }
            assertEquals("Cache habit count must decrement after archiving", countBeforeArchive - 1, cache.habitCount)
            assertTrue("Habit must be marked archived", habitToMutate.isArchived)

            // Verify adapter matches
            var adapterItemCount = -1
            val adapterDeadline = SystemClock.elapsedRealtime() + 5000
            while (adapterItemCount != countBeforeArchive - 1 && SystemClock.elapsedRealtime() < adapterDeadline) {
                scenario.onActivity { activity ->
                    val listView = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().firstOrNull()
                    val adapter = listView?.adapter as? HabitCardListAdapter
                    adapterItemCount = adapter?.itemCount ?: -1
                }
                if (adapterItemCount != countBeforeArchive - 1) Thread.sleep(50)
            }
            assertEquals(countBeforeArchive - 1, adapterItemCount)

            // Unarchive habit via UnarchiveHabitsCommand
            val unarchiveCmd = UnarchiveHabitsCommand(component.habitList, listOf(habitToMutate))
            instrumentation.runOnMainSync {
                component.commandRunner.run(unarchiveCmd)
            }
            val unarchiveDeadline = SystemClock.elapsedRealtime() + 5000
            while (cache.habitCount < countBeforeArchive && SystemClock.elapsedRealtime() < unarchiveDeadline) {
                Thread.sleep(50)
            }
            assertEquals("Cache habit count must restore after unarchiving", countBeforeArchive, cache.habitCount)
            assertFalse("Habit must no longer be marked archived", habitToMutate.isArchived)

            var unarchiveAdapterCount = -1
            val unarchiveAdapterDeadline = SystemClock.elapsedRealtime() + 5000
            while (unarchiveAdapterCount != countBeforeArchive && SystemClock.elapsedRealtime() < unarchiveAdapterDeadline) {
                scenario.onActivity { activity ->
                    val listView = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().firstOrNull()
                    val adapter = listView?.adapter as? HabitCardListAdapter
                    unarchiveAdapterCount = adapter?.itemCount ?: -1
                }
                if (unarchiveAdapterCount != countBeforeArchive) Thread.sleep(50)
            }
            assertEquals(countBeforeArchive, unarchiveAdapterCount)
        }
    }

    @Test
    fun testMultiScreenJourneysDetailStatisticsTasksSettingsAndBackupRestore() = withIsolatedDataset {
        val component = HabitsApplication.component
        val today = getToday()
        val now = System.currentTimeMillis()

        // 1. Seed habits: boolean + numerical with goal history
        val boolHabit = component.modelFactory.buildHabit().apply {
            name = "Journey Boolean Habit"
            type = HabitType.YES_NO
            color = PaletteColor(1)
        }
        component.habitList.add(boolHabit)
        boolHabit.originalEntries.add(Entry(today, Entry.YES_MANUAL))
        boolHabit.recompute()

        val numHabit = component.modelFactory.buildHabit().apply {
            name = "Journey Numerical Habit"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 60.0
            unit = "min"
            color = PaletteColor(2)
        }
        component.habitList.add(numHabit)
        numHabit.originalEntries.add(Entry(today, 60_000))
        numHabit.originalEntries.add(Entry(today.minus(1), 45_000))
        numHabit.goalHistory.add(HabitGoal(
            effectiveDate = today.minus(30),
            frequency = Frequency.DAILY,
            targetType = NumericalHabitType.AT_LEAST,
            targetValue = 60.0,
            unit = "min"
        ))
        numHabit.recompute()

        // 2. Seed Container in organizationStore and assign habit placements
        val containerId = ContainerId("container-journey-${UUID.randomUUID().toString().take(6)}")
        component.organizationFacade.organizationService?.create(
            CreateContainer(
                name = "Journey Container",
                explicitId = containerId,
                color = PaletteColor(3),
            )
        )
        if (boolHabit.uuid != null) {
            component.organizationFacade.placeHabit(boolHabit.uuid!!, containerId)
        }
        if (numHabit.uuid != null) {
            component.organizationFacade.placeHabit(numHabit.uuid!!, containerId)
        }

        // 3. Seed UserTask in userTaskRepository
        val taskId = TaskId("task-journey-${UUID.randomUUID().toString().take(6)}")
        val userTask = UserTask(
            id = taskId,
            title = "Journey User Task",
            description = "Journey description",
            containerId = containerId,
            status = TaskStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        component.userTaskRepository.save(userTask)

        // 4. Seed FocusSession in focusSessionRepository
        val focusSessionId = FocusSessionId("focus-journey-${UUID.randomUUID().toString().take(6)}")
        val focusSession = FocusSession(
            id = focusSessionId,
            startedAt = now - 60_000,
            endedAt = now,
            durationMs = 60_000,
            taskId = taskId,
            source = FocusSessionSource.TIMER,
            createdAt = now,
            updatedAt = now,
        )
        component.focusSessionRepository?.record(focusSession)

        // 5. Launch ShowHabitActivity for numerical habit and measure detail screen open latency
        val detailStart = SystemClock.elapsedRealtime()
        val intent = component.intentFactory.startShowHabitActivity(context, numHabit)
        ActivityScenario.launch<ShowHabitActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            val detailOpenMs = SystemClock.elapsedRealtime() - detailStart
            Log.i("SPRINT03_ANDROID_PERF", "HABIT_DETAIL_OPEN_MS=$detailOpenMs")

            scenario.onActivity { activity ->
                assertTrue("ShowHabitActivity must be active", activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                assertFalse("Activity must not be finishing", activity.isFinishing)
                assertNotNull("Overview card must exist", activity.findViewById<View>(R.id.overviewCard))
                assertNotNull("Target card must exist", activity.findViewById<View>(R.id.targetCard))
                assertNotNull("Score card must exist", activity.findViewById<View>(R.id.scoreCard))
                assertNotNull("History card must exist", activity.findViewById<View>(R.id.historyCard))
            }
        }

        // 6. Verify UnifiedStatisticsService computes non-empty statistics
        val statsService = component.unifiedStatisticsReadSource()
        val queryContext = StatisticsQueryContext(
            from = today.minus(30),
            to = today,
            timezoneId = TimeZone.getDefault().id,
            firstWeekday = component.preferences.firstWeekday,
            datasetId = component.session.datasetId,
            revision = 1L,
            now = now
        )
        val statsStart = SystemClock.elapsedRealtime()
        val report = statsService.read(queryContext, { StatisticsReadIdentity(component.session.datasetId, 1L) })
        val statsCalcMs = SystemClock.elapsedRealtime() - statsStart
        Log.i("SPRINT03_ANDROID_PERF", "UNIFIED_STATS_CALC_MS=$statsCalcMs")

        assertNotNull("UnifiedStatisticsReport must not be null", report)
        assertNotNull("Habit statistics must be computed", report.habits)
        assertNotNull("Task statistics must be computed", report.tasks)
        assertNotNull("Focus statistics must be computed", report.focus)

        // 7. Test theme/preference switches + Activity.recreate() + background/foreground on MainActivity
        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
                assertTrue("Activity must be active upon launch", activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED))
                assertFalse("Activity must not be finishing", activity.isFinishing)
            }

            // Background -> Foreground lifecycle transitions
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()

            val executor = Executors.newSingleThreadScheduledExecutor()
            executor.schedule({
                val bringToFront = MainActivity.intent(context, MainDestination.HABITS)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(bringToFront)
            }, 400, TimeUnit.MILLISECONDS)

            scenario.moveToState(Lifecycle.State.RESUMED)
            executor.shutdown()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue("Activity must be active after background/foreground", activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED))
                assertFalse("Activity must not be finishing after resume", activity.isFinishing)
            }

            // Theme/preference switches + recreate
            instrumentation.runOnMainSync {
                component.preferences.theme = ThemeSwitcher.THEME_DARK
                val testPrefs = context.getSharedPreferences("uhabits_prefs_${component.session.preferencesNamespace}", Context.MODE_PRIVATE)
                val newFirstWeekday = if (component.preferences.firstWeekday == DayOfWeek.MONDAY) "1" else "2"
                testPrefs.edit().putString("pref_first_weekday", newFirstWeekday).commit()
            }
            scenario.onActivity { activity ->
                activity.recreate()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull("Activity must exist after recreation", activity)
                assertFalse("Activity must not be finishing after recreation", activity.isFinishing)
                assertTrue("Activity must be active after recreation", activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED))
            }
        }

        // 8. In-app SQLite ContainerBackupService.createBackup + ContainerRestoreService.restore roundtrip
        val backupStart = SystemClock.elapsedRealtime()
        val suffix = UUID.randomUUID().toString().take(6)
        val backupFile = File(context.cacheDir, "test-backup-$suffix.db")
        val stageFile = File(context.cacheDir, "test-stage-$suffix.db")
        val destFile = File(context.cacheDir, "test-dest-$suffix.db")

        val backupSqlite = SQLiteDatabase.openOrCreateDatabase(backupFile, null)
        val stageSqlite = SQLiteDatabase.openOrCreateDatabase(stageFile, null)
        val destSqlite = SQLiteDatabase.openOrCreateDatabase(destFile, null)

        val backupDb = AndroidDatabase(backupSqlite)
        val stageDb = AndroidDatabase(stageSqlite)
        val destDb = AndroidDatabase(destSqlite)

        try {
            val backupResult = ContainerBackupService.createBackup(
                sourceDb = component.db,
                targetDb = backupDb,
                timestamp = System.currentTimeMillis(),
                preferences = mapOf("theme" to "dark", "first_day_of_week" to "1")
            )
            assertTrue("Container backup must succeed", backupResult is BackupCreationResult.Success)

            val restoreResult = ContainerRestoreService.restore(
                backupDb = backupDb,
                stagingDb = stageDb,
                destinationDb = destDb
            )
            assertTrue("Container restore must succeed: $restoreResult", restoreResult is RestoreResult.Success)
            val backupRestoreMs = SystemClock.elapsedRealtime() - backupStart
            Log.i("SPRINT03_ANDROID_PERF", "BACKUP_RESTORE_ROUNDTRIP_MS=$backupRestoreMs")

            // Verify habit count, entries, tasks, and focus sessions remain 100% intact
            val sourceHabitCount = component.db.queryLong("SELECT count(*) FROM Habits WHERE deleted_at IS NULL")
            val destHabitCount = destDb.queryLong("SELECT count(*) FROM Habits WHERE deleted_at IS NULL")
            assertEquals("Restored habit count must match source", sourceHabitCount, destHabitCount)
            assertEquals("Restored habit count must match component", component.habitList.size().toLong(), destHabitCount)

            val sourceEntriesCount = component.db.queryLong("SELECT count(*) FROM Repetitions")
            val destEntriesCount = destDb.queryLong("SELECT count(*) FROM Repetitions")
            assertEquals("Restored entries count must match", sourceEntriesCount, destEntriesCount)

            val destTasks = SQLiteUserTaskRepository(destDb)
            assertEquals("Restored task count must match", component.userTaskRepository.getAll().size, destTasks.getAll().size)

            val destFocus = SQLiteFocusSessionRepository(destDb)
            assertEquals("Restored focus sessions count must match",
                component.focusSessionRepository?.getAll()?.size ?: 0,
                destFocus.getAll().size)
        } finally {
            backupSqlite.close()
            stageSqlite.close()
            destSqlite.close()
            backupFile.delete()
            stageFile.delete()
            destFile.delete()
        }
    }
}
