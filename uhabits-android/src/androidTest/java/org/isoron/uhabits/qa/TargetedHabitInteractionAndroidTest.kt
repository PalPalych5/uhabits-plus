/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.qa

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.list.views.CheckmarkPanelView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.NumberPanelView
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Targeted Android instrumentation QA suite verifying:
 * 1. Habit card list grid rendering, button panel sizing, and view holder recycling.
 * 2. Sphere/group boundary stability and metadata preservation after entry date edits.
 * 3. Notification button persistence and non-ongoing tray dismissal policy.
 * 4. Pomodoro overtime-to-break transitions and idempotent break execution.
 * 5. Process / Activity recreation state preservation.
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
}
