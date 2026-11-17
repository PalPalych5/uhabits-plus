/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.qa

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.list.views.CheckmarkPanelView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.database.AndroidSqlTiming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * Bounded P0 closure regression gate covering:
 * 1. Light / Dark / AMOLED theme transitions + Cards / Classic layout + border & corner-radius changes,
 *    confirming legitimate full refreshes vs zero unnecessary global rebinds on entry taps.
 * 2. Score / Status sorting, filtered lists (isCompletedAllowed / isEnteredAllowed / searchQuery),
 *    incremental updates under entry-dependent sorts/filters, and sphere/group boundary updates.
 * 3. Android SQL SELECT classification for single-habit edits vs full warm refreshes across 26 root spheres.
 * 4. Two-phase real OS process kill & cold startup state restoration.
 */
@RunWith(AndroidJUnit4::class)
class P0ClosureRegressionAndroidTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
    }

    private fun drain() {
        val component = HabitsApplication.component
        repeat(6) {
            runBlocking { component.taskRunner.await() }
            ins.waitForIdleSync()
        }
    }

    private fun drainWithLayout(listView: HabitCardListView) {
        drain()
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var pending = false
            ins.runOnMainSync {
                pending = listView.hasPendingAdapterUpdates() || listView.isLayoutRequested
            }
            if (!pending) break
            Thread.sleep(20)
            drain()
        }
    }

    private fun waitForListView(scenario: ActivityScenario<MainActivity>, minItems: Int = 1): HabitCardListView {
        var found: HabitCardListView? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (found == null && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val candidate = descendants(activity.window.decorView).filterIsInstance<HabitCardListView>().firstOrNull()
                if (candidate != null && (candidate.adapter?.itemCount ?: 0) >= minItems && candidate.childCount > 0) {
                    found = candidate
                }
            }
            if (found == null) Thread.sleep(50)
        }
        return requireNotNull(found) { "HabitCardListView with >= $minItems items did not appear" }
    }

    private fun firstCardContentDrawable(card: HabitCardView): GradientDrawable {
        val innerFrame = card.getChildAt(1) as LinearLayout
        val ripple = innerFrame.background as RippleDrawable
        return ripple.getDrawable(0) as GradientDrawable
    }

    private inline fun withIsolatedDataset(block: (HabitsApplication) -> Unit) {
        val app = context.applicationContext as HabitsApplication
        val original = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val dbName = "uhabits-test-p0closure-$suffix.db"
        val prefsName = "uhabits-test-p0closure-$suffix"
        val session = original.copy(
            datasetId = "p0closure-$suffix",
            databaseFilename = dbName,
            preferencesNamespace = prefsName,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(
                timerEnabled = true,
                syncEnabled = false,
                remoteCallsAllowed = false,
                backgroundJobsEnabled = false
            )
        )
        ins.runOnMainSync { app.rebuildComponent(session) }
        try {
            block(app)
        } finally {
            ins.runOnMainSync {
                HabitsApplication.component.timerSessionManager.resetCurrent()
                app.rebuildComponent(original)
            }
            ins.waitForIdleSync()
            context.deleteDatabase(dbName)
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun testAppearanceLayoutsBordersCornersAndRefreshDistinction() = withIsolatedDataset {
        val component = HabitsApplication.component
        val prefs = component.preferences
        val today = getToday()
        ins.runOnMainSync {
            prefs.isFirstRun = false
            prefs.isShortToggleEnabled = true
            prefs.theme = ThemeSwitcher.THEME_LIGHT
            prefs.isPureBlackEnabled = false
            prefs.sectionLayoutMode = Preferences.SECTION_LAYOUT_CARDS
            prefs.showHabitCardBorders = true
            prefs.habitsCardCornerRadius = 12
        }

        for (i in 0 until 6) {
            val h = component.modelFactory.buildHabit().apply {
                name = "Appearance Habit $i"
                type = if (i % 2 == 0) HabitType.YES_NO else HabitType.NUMERICAL
                targetType = NumericalHabitType.AT_LEAST
                targetValue = 30.0
                frequency = Frequency.DAILY
                color = PaletteColor(i % 8)
            }
            component.habitList.add(h)
            h.originalEntries.add(Entry(today.minus(1), if (h.isNumerical) 30_000 else Entry.YES_MANUAL))
            h.recompute()
        }

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            drain()
            waitForListView(scenario, minItems = 6)

            // 1. Light -> Dark -> AMOLED -> Light theme checks
            scenario.onActivity { activity ->
                val palette = MainTabsThemeBridge.resolve(activity)
                assertFalse("Expected Light palette", palette.isDark)
                assertFalse("Expected non-AMOLED in Light", palette.isPureBlack)
                val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                assertEquals(palette.surface, firstCardContentDrawable(card).color?.defaultColor)
            }

            ins.runOnMainSync {
                prefs.theme = ThemeSwitcher.THEME_DARK
                prefs.isPureBlackEnabled = false
            }
            scenario.recreate()
            drain()
            waitForListView(scenario, minItems = 6)
            scenario.onActivity { activity ->
                val palette = MainTabsThemeBridge.resolve(activity)
                assertTrue("Expected Dark palette", palette.isDark)
                assertFalse("Expected Dark (non-AMOLED) palette", palette.isPureBlack)
                val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                assertEquals(palette.surface, firstCardContentDrawable(card).color?.defaultColor)
            }

            ins.runOnMainSync {
                prefs.theme = ThemeSwitcher.THEME_DARK
                prefs.isPureBlackEnabled = true
            }
            scenario.recreate()
            drain()
            waitForListView(scenario, minItems = 6)
            scenario.onActivity { activity ->
                val palette = MainTabsThemeBridge.resolve(activity)
                assertTrue("Expected Dark flag in AMOLED", palette.isDark)
                assertTrue("Expected PureBlack flag in AMOLED", palette.isPureBlack)
                val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                assertEquals(0xFF0C0E12.toInt(), firstCardContentDrawable(card).color?.defaultColor)
            }

            // Classic mode in AMOLED must switch card surface to pure BLACK and show bottomDivider
            ins.runOnMainSync { prefs.sectionLayoutMode = Preferences.SECTION_LAYOUT_CLASSIC }
            drain()
            scenario.onActivity { activity ->
                val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                assertEquals(View.VISIBLE, card.getChildAt(0).visibility)
                assertEquals(Color.BLACK, firstCardContentDrawable(card).color?.defaultColor)
                assertEquals(0f, firstCardContentDrawable(card).cornerRadius, 0.01f)
            }

            ins.runOnMainSync {
                prefs.theme = ThemeSwitcher.THEME_LIGHT
                prefs.isPureBlackEnabled = false
                prefs.sectionLayoutMode = Preferences.SECTION_LAYOUT_CARDS
            }
            scenario.recreate()
            drain()
            val listView = waitForListView(scenario, minItems = 6)
            val adapter = listView.adapter as HabitCardListAdapter

            var fullRefreshes = 0
            val observer = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    fullRefreshes++
                }
            }
            ins.runOnMainSync { adapter.registerAdapterDataObserver(observer) }
            try {
                // 2. Boolean entry tap MUST NOT trigger full notifyDataSetChanged()
                fullRefreshes = 0
                scenario.onActivity { activity ->
                    val btn = descendants(activity.window.decorView)
                        .filterIsInstance<CheckmarkButtonView>()
                        .first { it.isShown && it.width > 0 }
                    assertTrue(btn.performClick())
                }
                drain()
                assertEquals("Targeted Boolean entry tap must not call notifyDataSetChanged()", 0, fullRefreshes)

                // 3. Cards <-> Classic transitions MUST legitimately refresh & update geometry
                fullRefreshes = 0
                ins.runOnMainSync { prefs.sectionLayoutMode = Preferences.SECTION_LAYOUT_CLASSIC }
                drain()
                assertTrue("Switching to Classic layout must trigger legitimate full refresh", fullRefreshes > 0)
                scenario.onActivity { activity ->
                    val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                    assertEquals(View.VISIBLE, card.getChildAt(0).visibility)
                    assertEquals(0f, firstCardContentDrawable(card).cornerRadius, 0.01f)
                }

                fullRefreshes = 0
                ins.runOnMainSync { prefs.sectionLayoutMode = Preferences.SECTION_LAYOUT_CARDS }
                drain()
                assertTrue("Switching back to Cards layout must trigger legitimate full refresh", fullRefreshes > 0)
                scenario.onActivity { activity ->
                    val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                    assertEquals(View.GONE, card.getChildAt(0).visibility)
                }

                // 4. Corner radius changes MUST legitimately refresh & update GradientDrawable.cornerRadius
                val density = context.resources.displayMetrics.density
                fullRefreshes = 0
                ins.runOnMainSync { prefs.habitsCardCornerRadius = 24 }
                drain()
                assertTrue("Corner radius 12->24 must trigger legitimate full refresh", fullRefreshes > 0)
                scenario.onActivity { activity ->
                    val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                    val r24 = firstCardContentDrawable(card).cornerRadius
                    assertTrue("Expected ~24dp cornerRadius ($r24)", abs(r24 - 24f * density) < 2f)
                }

                fullRefreshes = 0
                ins.runOnMainSync { prefs.habitsCardCornerRadius = 4 }
                drain()
                assertTrue("Corner radius 24->4 must trigger legitimate full refresh", fullRefreshes > 0)
                scenario.onActivity { activity ->
                    val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                    val r4 = firstCardContentDrawable(card).cornerRadius
                    assertTrue("Expected ~4dp cornerRadius ($r4)", abs(r4 - 4f * density) < 2f)
                }

                // 5. Border toggle MUST legitimately refresh & update fill transparency
                fullRefreshes = 0
                ins.runOnMainSync { prefs.showHabitCardBorders = false }
                drain()
                assertTrue("Disabling card borders must trigger legitimate full refresh", fullRefreshes > 0)
                scenario.onActivity { activity ->
                    val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                    assertEquals(Color.TRANSPARENT, firstCardContentDrawable(card).color?.defaultColor)
                }

                fullRefreshes = 0
                ins.runOnMainSync { prefs.showHabitCardBorders = true }
                drain()
                assertTrue("Re-enabling card borders must trigger legitimate full refresh", fullRefreshes > 0)
                scenario.onActivity { activity ->
                    val palette = MainTabsThemeBridge.resolve(activity)
                    val card = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().first()
                    assertEquals(palette.surface, firstCardContentDrawable(card).color?.defaultColor)
                }
            } finally {
                ins.runOnMainSync { adapter.unregisterAdapterDataObserver(observer) }
            }
        }
    }

    @Test
    fun testScoreStatusSortingFilteredListsAndSphereBoundaryUpdates() = withIsolatedDataset {
        val component = HabitsApplication.component
        val prefs = component.preferences
        val today = getToday()
        ins.runOnMainSync {
            prefs.isFirstRun = false
            prefs.isHabitSpheresEnabled = true
            prefs.isShowSphereNamesEnabled = true
            prefs.areHabitGroupSeparatorsEnabled = true
        }

        val alphaId = ContainerId("sphere-alpha-${UUID.randomUUID().toString().take(6)}")
        val betaId = ContainerId("sphere-beta-${UUID.randomUUID().toString().take(6)}")
        val service = requireNotNull(component.organizationFacade.organizationService)
        service.create(CreateContainer(name = "Sphere Alpha", explicitId = alphaId, color = PaletteColor(1)))
        service.create(CreateContainer(name = "Sphere Beta", explicitId = betaId, color = PaletteColor(4)))

        fun addHabit(habitName: String, streakDays: Int, todayVal: Int, containerId: ContainerId): Habit {
            val h = component.modelFactory.buildHabit().apply {
                name = habitName
                type = HabitType.YES_NO
                frequency = Frequency.DAILY
                color = PaletteColor(2)
            }
            component.habitList.add(h)
            for (d in 1..streakDays) {
                h.originalEntries.add(Entry(today.minus(d), Entry.YES_MANUAL))
            }
            if (todayVal != Entry.UNKNOWN) {
                h.originalEntries.add(Entry(today, todayVal))
            }
            h.recompute()
            component.organizationFacade.placeHabit(requireNotNull(h.uuid), containerId)
            return h
        }

        // Distinct scores and today statuses:
        val hDone = addHabit("Alpha Done High", streakDays = 20, todayVal = Entry.YES_MANUAL, containerId = alphaId)
        val hSkip = addHabit("Alpha Skip Mid", streakDays = 8, todayVal = Entry.SKIP, containerId = alphaId)
        val hOpen = addHabit("Beta Open Low", streakDays = 1, todayVal = Entry.UNKNOWN, containerId = betaId)
        val hNo = addHabit("Beta No Lowest", streakDays = 0, todayVal = Entry.NO, containerId = betaId)

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            drain()
            val listView = waitForListView(scenario, minItems = 4)
            val adapter = listView.adapter as HabitCardListAdapter
            val cache = component.habitCardListCache

            // 1. Score sorting (Note: Loop's BY_SCORE_DESC comparator compares score ascending; verify exact match)
            ins.runOnMainSync { adapter.primaryOrder = HabitList.Order.BY_SCORE_DESC }
            drain()
            val descScoreNames = (0 until adapter.itemCount).map { adapter.getItem(it)!!.name }
            assertEquals(listOf(hNo.name, hOpen.name, hSkip.name, hDone.name), descScoreNames)

            ins.runOnMainSync { adapter.primaryOrder = HabitList.Order.BY_SCORE_ASC }
            drain()
            val ascScoreNames = (0 until adapter.itemCount).map { adapter.getItem(it)!!.name }
            assertEquals(listOf(hDone.name, hSkip.name, hOpen.name, hNo.name), ascScoreNames)

            // 2. Status sorting: SKIP(3) & YES_MANUAL(2) both count as isCompletedToday, ordered by today's value
            ins.runOnMainSync { adapter.primaryOrder = HabitList.Order.BY_STATUS_DESC }
            drain()
            val descStatusNames = (0 until adapter.itemCount).map { adapter.getItem(it)!!.name }
            assertEquals(listOf(hSkip.name, hDone.name, hNo.name, hOpen.name), descStatusNames)

            ins.runOnMainSync { adapter.primaryOrder = HabitList.Order.BY_STATUS_ASC }
            drain()
            val ascStatusNames = (0 until adapter.itemCount).map { adapter.getItem(it)!!.name }
            assertEquals(listOf(hOpen.name, hNo.name, hDone.name, hSkip.name), ascStatusNames)

            // 3. Filtered lists: Hide completed (excludes YES_MANUAL & SKIP), Hide entered, Search query
            ins.runOnMainSync {
                adapter.primaryOrder = HabitList.Order.BY_POSITION
                adapter.setFilter(HabitMatcher(isCompletedAllowed = false))
                adapter.refresh()
            }
            drain()
            val hideCompleted = (0 until adapter.itemCount).map { adapter.getItem(it)!!.id }
            assertEquals("Only uncompleted habits (UNKNOWN, NO) should remain", setOf(hOpen.id, hNo.id), hideCompleted.toSet())

            ins.runOnMainSync {
                adapter.setFilter(HabitMatcher(isEnteredAllowed = false))
                adapter.refresh()
            }
            drain()
            val hideEntered = (0 until adapter.itemCount).map { adapter.getItem(it)!!.id }
            assertEquals("Only un-entered UNKNOWN habit should remain", listOf(hOpen.id), hideEntered)

            ins.runOnMainSync {
                adapter.setFilter(HabitMatcher(searchQuery = "Alpha"))
                adapter.refresh()
            }
            drain()
            val alphaSearch = (0 until adapter.itemCount).map { adapter.getItem(it)!!.id }
            assertEquals(setOf(hDone.id, hSkip.id), alphaSearch.toSet())

            // 4. Incremental vs fallback refresh checks without global notifyDataSetChanged()
            var fullRefreshes = 0
            val refreshEvents = mutableListOf<String>()
            val adapterObs = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { fullRefreshes++ }
            }
            ins.runOnMainSync {
                adapter.setFilter(HabitMatcher(isCompletedAllowed = false))
                adapter.refresh()
                adapter.registerAdapterDataObserver(adapterObs)
            }
            cache.refreshObserver = { mode, _, reason -> refreshEvents.add("$mode:${reason ?: "ok"}") }
            drain()
            fullRefreshes = 0
            refreshEvents.clear()

            // Completing hOpen under !isCompletedAllowed triggers targeted_fallback -> onItemRemoved (NOT notifyDataSetChanged)
            ins.runOnMainSync {
                component.commandRunner.run(CreateRepetitionCommand(component.habitList, hOpen, today, Entry.YES_MANUAL, ""))
            }
            drain()
            assertTrue("Expected targeted_fallback:filter_or_order in $refreshEvents",
                refreshEvents.any { it == "targeted_fallback:filter_or_order" })
            assertEquals("Filtered item removal must stay incremental (no notifyDataSetChanged)", 0, fullRefreshes)
            val remainingAfterComplete = (0 until adapter.itemCount).map { adapter.getItem(it)!!.id }
            assertFalse("Newly completed habit must disappear under hide-completed filter", hOpen.id in remainingAfterComplete)

            // Restore hOpen + verify BY_SCORE_DESC dynamic reorder stays incremental (onItemMoved, 0 notifyDataSetChanged)
            ins.runOnMainSync {
                component.commandRunner.run(CreateRepetitionCommand(component.habitList, hOpen, today, Entry.UNKNOWN, ""))
                adapter.setFilter(HabitMatcher())
                adapter.primaryOrder = HabitList.Order.BY_SCORE_DESC
            }
            drain()
            fullRefreshes = 0
            refreshEvents.clear()
            ins.runOnMainSync {
                component.commandRunner.run(CreateRepetitionCommand(component.habitList, hOpen, today, Entry.YES_MANUAL, ""))
            }
            drain()
            assertTrue("Score-sorted edit should trigger targeted_fallback:filter_or_order ($refreshEvents)",
                refreshEvents.any { it == "targeted_fallback:filter_or_order" })
            assertEquals("Score-sorted move must not call notifyDataSetChanged()", 0, fullRefreshes)

            // 5. Sphere/group boundary updates on move between spheres
            ins.runOnMainSync {
                adapter.unregisterAdapterDataObserver(adapterObs)
                cache.refreshObserver = null
                adapter.primaryOrder = HabitList.Order.BY_SPHERE
            }
            drainWithLayout(listView)

            scenario.onActivity { activity ->
                val cards = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().toList()
                val orderNames = (0 until adapter.itemCount).map { adapter.getItem(it)?.name }
                val headingsByHabit = cards.associate { it.habit!!.name to it.groupHeading }
                assertEquals("Unexpected Alpha heading; order=$orderNames headings=$headingsByHabit", "Sphere Alpha", headingsByHabit[hDone.name])
                assertNull("Second item in Sphere Alpha must have null heading", headingsByHabit[hSkip.name])
                assertEquals("Unexpected Beta heading; order=$orderNames headings=$headingsByHabit", "Sphere Beta", headingsByHabit[hOpen.name])
                assertNull("Second item in Sphere Beta must have null heading", headingsByHabit[hNo.name])
            }

            // Move hSkip from Sphere Alpha to Sphere Beta using EditHabitCommand (real user edit flow)
            val modifiedSkip = component.modelFactory.buildHabit().apply {
                copyFrom(hSkip)
            }
            ins.runOnMainSync {
                component.commandRunner.run(
                    EditHabitCommand(
                        habitList = component.habitList,
                        habitId = requireNotNull(hSkip.id),
                        modified = modifiedSkip,
                        targetContainerId = betaId,
                        changeContainer = true,
                        organizationFacade = component.organizationFacade,
                        visualIdentity = null
                    )
                )
            }
            drainWithLayout(listView)

            scenario.onActivity { activity ->
                val listLifecycleOwner = listView.findViewTreeLifecycleOwner()
                assertNotNull("RecyclerView must have a tree LifecycleOwner", listLifecycleOwner)

                val cards = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().toList()
                val posMap = (0 until adapter.itemCount).associate { adapter.getItem(it)!!.id to it }
                val metaSkip = cache.getMetadata(hSkip.id!!)
                assertEquals("Moved habit rootGroupName must update to Sphere Beta", "Sphere Beta", metaSkip.rootGroupName)
                // Only hDone stays in Sphere Alpha; Sphere Beta's heading must appear ONLY on the first row in Sphere Beta
                val sphereBetaCards = cards.filter { cache.getMetadata(it.habit!!.id!!).rootGroupName == "Sphere Beta" }
                    .sortedBy { posMap.getValue(it.habit!!.id!!) }
                assertEquals("Expected 3 habits in Sphere Beta", 3, sphereBetaCards.size)
                assertEquals("First habit in Sphere Beta must show group heading", "Sphere Beta", sphereBetaCards[0].groupHeading)
                assertNull("Second habit in Sphere Beta must NOT show stale heading", sphereBetaCards[1].groupHeading)
                assertNull("Third habit in Sphere Beta must NOT show heading", sphereBetaCards[2].groupHeading)

                val headingCompose = descendants(sphereBetaCards[0]).filterIsInstance<ComposeView>().first()
                assertEquals(View.VISIBLE, headingCompose.visibility)
                assertEquals(
                    "Attached HabitCardView must keep list/Fragment tree owner rather than Activity fallback",
                    listLifecycleOwner,
                    sphereBetaCards[0].findViewTreeLifecycleOwner()
                )
                assertEquals(
                    "Attached ComposeView heading must resolve list/Fragment tree owner",
                    listLifecycleOwner,
                    headingCompose.findViewTreeLifecycleOwner()
                )

                // Offscreen/unattached ViewHolders must not get Activity owners pinned onto them.
                val detachedCard = adapter.createViewHolder(listView, 0).itemView as HabitCardView
                detachedCard.composeGroupHeading = "Detached Sphere"
                val detachedCompose = descendants(detachedCard).filterIsInstance<ComposeView>().first()
                assertNull("Offscreen HabitCardView must not cache a LifecycleOwner", detachedCard.findViewTreeLifecycleOwner())
                assertNull("Offscreen ComposeView must not cache a LifecycleOwner", detachedCompose.findViewTreeLifecycleOwner())
            }

            // Toggle BY_SPHERE -> BY_POSITION -> BY_SPHERE to confirm heading reuse hides cleanly when ungrouped.
            ins.runOnMainSync { adapter.primaryOrder = HabitList.Order.BY_POSITION }
            drainWithLayout(listView)
            scenario.onActivity { activity ->
                val cards = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().toList()
                cards.forEach { card ->
                    descendants(card).filterIsInstance<ComposeView>().forEach { cv ->
                        assertEquals("Ungrouped recycled ComposeView heading must be GONE", View.GONE, cv.visibility)
                    }
                }
            }
            ins.runOnMainSync { adapter.primaryOrder = HabitList.Order.BY_SPHERE }
            drainWithLayout(listView)
            scenario.onActivity { activity ->
                val listLifecycleOwner = listView.findViewTreeLifecycleOwner()
                val cards = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().toList()
                val posMap = (0 until adapter.itemCount).associate { adapter.getItem(it)!!.id to it }
                val sphereBetaCards = cards.filter { cache.getMetadata(it.habit!!.id!!).rootGroupName == "Sphere Beta" }
                    .sortedBy { posMap.getValue(it.habit!!.id!!) }
                assertEquals("Sphere Beta", sphereBetaCards[0].groupHeading)
                val firstHeading = descendants(sphereBetaCards[0]).filterIsInstance<ComposeView>().first()
                assertEquals(View.VISIBLE, firstHeading.visibility)
                assertEquals(listLifecycleOwner, firstHeading.findViewTreeLifecycleOwner())
            }
        }
    }

    @Test
    fun testAndroidRootGroupSqlCostMeasurement() = withIsolatedDataset {
        val component = HabitsApplication.component
        val today = getToday()
        val service = requireNotNull(component.organizationFacade.organizationService)
        val containerIds = (0 until 26).map { idx ->
            ContainerId("root-sphere-%02d".format(idx)).also { cid ->
                service.create(CreateContainer(name = "Sphere %02d".format(idx), explicitId = cid, color = PaletteColor(idx % 8)))
            }
        }
        val habits = mutableListOf<Habit>()
        for (i in 0 until 52) {
            val h = component.modelFactory.buildHabit().apply {
                name = "SQL Habit %02d".format(i)
                type = HabitType.YES_NO
                frequency = Frequency.DAILY
                color = PaletteColor(i % 8)
            }
            component.habitList.add(h)
            for (d in 1..30) {
                h.originalEntries.add(Entry(today.minus(d), if ((i + d) % 2 == 0) Entry.YES_MANUAL else Entry.NO))
            }
            h.recompute()
            component.organizationFacade.placeHabit(requireNotNull(h.uuid), containerIds[i % 26])
            habits.add(h)
        }

        val cache = component.habitCardListCache
        cache.setCheckmarkCount(5)
        ins.runOnMainSync {
            cache.onAttached()
            cache.primaryOrder = HabitList.Order.BY_POSITION
            cache.refreshAllHabits()
        }
        drain()
        assertEquals(52, cache.habitCount)

        var selectCount = 0
        var selectTotalMs = 0.0
        val cacheTimings = mutableMapOf<String, MutableList<Double>>()
        AndroidSqlTiming.observer = { verb, ms ->
            if (verb == "SELECT") {
                selectCount++
                selectTotalMs += ms
            }
        }
        cache.timingObserver = { stage, ms ->
            cacheTimings.getOrPut(stage) { mutableListOf() }.add(ms)
        }

        try {
            // 1. Targeted single-habit Boolean edit (5 runs)
            val targetedSelectCounts = mutableListOf<Int>()
            val targetedSelectMs = mutableListOf<Double>()
            val target = habits.first()
            repeat(5) { iter ->
                val nextVal = if (iter % 2 == 0) Entry.YES_MANUAL else Entry.NO
                selectCount = 0
                selectTotalMs = 0.0
                ins.runOnMainSync {
                    component.commandRunner.run(CreateRepetitionCommand(component.habitList, target, today, nextVal, ""))
                }
                drain()
                targetedSelectCounts.add(selectCount)
                targetedSelectMs.add(selectTotalMs)
            }

            // 2. Warm full refresh (5 runs) across 26 root spheres / 52 habits
            val warmFullSelectCounts = mutableListOf<Int>()
            val warmFullSelectMs = mutableListOf<Double>()
            repeat(5) {
                selectCount = 0
                selectTotalMs = 0.0
                ins.runOnMainSync { cache.refreshAllHabits() }
                drain()
                warmFullSelectCounts.add(selectCount)
                warmFullSelectMs.add(selectTotalMs)
            }

            // 3. Direct isolated measurement of facadeImpl.withBatchReadScope root-group reads (5 runs)
            val facadeImpl = component.organizationFacade as HabitOrganizationFacadeImpl
            val directRootSelectCounts = mutableListOf<Int>()
            val directRootWallMs = mutableListOf<Double>()
            val directRootSqlMs = mutableListOf<Double>()
            repeat(5) {
                selectCount = 0
                selectTotalMs = 0.0
                val t0 = SystemClock.elapsedRealtimeNanos()
                facadeImpl.withBatchReadScope {
                    for (h in habits) {
                        facadeImpl.getRootGroupKey(requireNotNull(h.uuid), h.blockId)
                        facadeImpl.getRootGroupName(requireNotNull(h.uuid), h.blockId)
                    }
                }
                directRootWallMs.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                directRootSelectCounts.add(selectCount)
                directRootSqlMs.add(selectTotalMs)
            }

            val summary = JSONObject()
                .put("roots", 26)
                .put("habits", 52)
                .put("targetedEditSelectCounts", targetedSelectCounts)
                .put("targetedEditSelectTotalMs", targetedSelectMs)
                .put("warmFullRefreshSelectCounts", warmFullSelectCounts)
                .put("warmFullRefreshSelectTotalMs", warmFullSelectMs)
                .put("directBatchRootGroupSelectCounts", directRootSelectCounts)
                .put("directBatchRootGroupWallMs", directRootWallMs)
                .put("directBatchRootGroupSqlMs", directRootSqlMs)
                .put("cacheBackgroundMs", cacheTimings["cache.background"].orEmpty())
                .put("cachePublishMs", cacheTimings["cache.publish"].orEmpty())

            Log.i("P0_SQL_CLASSIFICATION", summary.toString())
            val outDir = File(context.getExternalFilesDir(null), "p0-closure").apply { mkdirs() }
            File(outDir, "sql-classification.json").writeText(summary.toString(2))
        } finally {
            AndroidSqlTiming.observer = null
            cache.timingObserver = null
            ins.runOnMainSync { cache.onDetached() }
        }
    }

    @Test
    fun phase1_seedAndMutateBeforeProcessKill() {
        val component = HabitsApplication.component
        val prefs = component.preferences
        val today = getToday()
        ins.runOnMainSync {
            prefs.isFirstRun = false
            prefs.isShortToggleEnabled = true
            prefs.theme = ThemeSwitcher.THEME_DARK
            prefs.isPureBlackEnabled = true
            prefs.sectionLayoutMode = Preferences.SECTION_LAYOUT_CLASSIC
            prefs.habitsCardCornerRadius = 18
        }

        val sphereId = ContainerId("procdeath-sphere-${UUID.randomUUID().toString().take(6)}")
        requireNotNull(component.organizationFacade.organizationService).create(
            CreateContainer(name = "Persisted Sphere", explicitId = sphereId, color = PaletteColor(3))
        )

        val boolHabit = component.modelFactory.buildHabit().apply {
            name = "Persisted Boolean Habit"
            type = HabitType.YES_NO
            frequency = Frequency.DAILY
            color = PaletteColor(1)
        }
        component.habitList.add(boolHabit)
        boolHabit.originalEntries.add(Entry(today.minus(1), Entry.YES_MANUAL))
        boolHabit.recompute()
        component.organizationFacade.placeHabit(requireNotNull(boolHabit.uuid), sphereId)

        val numHabit = component.modelFactory.buildHabit().apply {
            name = "Persisted Numerical Habit"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 45.0
            unit = "min"
            frequency = Frequency.DAILY
            color = PaletteColor(5)
        }
        component.habitList.add(numHabit)
        numHabit.originalEntries.add(Entry(today, 50_000))
        numHabit.recompute()
        component.organizationFacade.placeHabit(requireNotNull(numHabit.uuid), sphereId)

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            drain()
            waitForListView(scenario, minItems = 2)
            scenario.onActivity { activity ->
                val boolCard = descendants(activity.window.decorView)
                    .filterIsInstance<HabitCardView>()
                    .first { it.habit?.id == boolHabit.id }
                val btn = requireNotNull(boolCard.findViewById<CheckmarkButtonView>(R.id.habitCheckmarkToday))
                assertTrue(btn.performClick())
            }
            drain()
        }

        val persistedBool = requireNotNull(component.habitList.getById(requireNotNull(boolHabit.id)))
        val todayEntryVal = persistedBool.computedEntries.get(today).value
        assertEquals("Boolean click must persist YES_MANUAL before kill", Entry.YES_MANUAL, todayEntryVal)
        val boolScore = persistedBool.scores[today].value

        val metaPrefs = context.getSharedPreferences("uhabits_prefs_p0_procdeath_meta", Context.MODE_PRIVATE)
        assertTrue(
            metaPrefs.edit()
                .putInt("phase1_pid", Process.myPid())
                .putLong("bool_habit_id", requireNotNull(boolHabit.id))
                .putLong("num_habit_id", requireNotNull(numHabit.id))
                .putFloat("bool_score", boolScore.toFloat())
                .commit()
        )
    }

    @Test
    fun phase2_verifyPersistedStateRestoredAfterProcessKill() {
        val metaPrefs = context.getSharedPreferences("uhabits_prefs_p0_procdeath_meta", Context.MODE_PRIVATE)
        val phase1Pid = metaPrefs.getInt("phase1_pid", -1)
        assertTrue("Phase 1 PID must have been recorded", phase1Pid != -1)
        assertNotEquals("Phase 2 must run in a brand-new OS process after kill", phase1Pid, Process.myPid())

        val boolHabitId = metaPrefs.getLong("bool_habit_id", -1L)
        val numHabitId = metaPrefs.getLong("num_habit_id", -1L)
        val phase1Score = metaPrefs.getFloat("bool_score", -1f).toDouble()
        assertTrue(boolHabitId > 0 && numHabitId > 0)

        try {
            val component = HabitsApplication.component
            val prefs = component.preferences
            val today = getToday()

            // Verify preferences persisted cleanly across real process death
            assertEquals(ThemeSwitcher.THEME_DARK, prefs.theme)
            assertTrue(prefs.isPureBlackEnabled)
            assertEquals(Preferences.SECTION_LAYOUT_CLASSIC, prefs.sectionLayoutMode)
            assertEquals(18, prefs.habitsCardCornerRadius)

            // Verify SQLite & model state restored cold
            val restoredBool = requireNotNull(component.habitList.getById(boolHabitId))
            val restoredNum = requireNotNull(component.habitList.getById(numHabitId))
            assertEquals(Entry.YES_MANUAL, restoredBool.computedEntries.get(today).value)
            assertEquals(50_000, restoredNum.originalEntries.get(today).value)
            assertEquals(phase1Score, restoredBool.scores[today].value, 1e-4)
            assertEquals(
                "Persisted Sphere",
                component.organizationFacade.getRootGroupName(requireNotNull(restoredBool.uuid), restoredBool.blockId)
            )

            // Verify UI renders persisted state cleanly in the fresh OS process
            ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
                drain()
                waitForListView(scenario, minItems = 2)
                scenario.onActivity { activity ->
                    val palette = MainTabsThemeBridge.resolve(activity)
                    assertTrue(palette.isDark && palette.isPureBlack)

                    val cards = descendants(activity.window.decorView).filterIsInstance<HabitCardView>().toList()
                    val boolCard = cards.first { it.habit?.id == boolHabitId }
                    assertEquals(View.VISIBLE, boolCard.getChildAt(0).visibility)
                    assertEquals(Color.BLACK, firstCardContentDrawable(boolCard).color?.defaultColor)

                    val todayBtn = requireNotNull(boolCard.findViewById<CheckmarkButtonView>(R.id.habitCheckmarkToday))
                    assertEquals(
                        "Rendered today button must reflect YES_MANUAL after process restart",
                        Entry.YES_MANUAL,
                        todayBtn.value
                    )
                }
            }
        } finally {
            metaPrefs.edit().clear().commit()
        }
    }
}
