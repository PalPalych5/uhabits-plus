/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.qa

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HabitListRefreshRegressionAndroidTest {
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

}
