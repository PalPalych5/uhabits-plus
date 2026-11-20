/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.habits.list

import androidx.compose.ui.geometry.Offset
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.views.HabitListDisplayModeState
import org.isoron.uhabits.core.models.HabitList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HabitWorkspaceHostTest {

    private val labels = HabitToolbarLabels(
        habitsTitle = "Habits",
        archiveTitle = "Archive",
        hideArchived = "Hide archived",
        hideCompleted = "Hide completed",
        hideEntered = "Hide entered",
        sortManual = "Manually",
        sortByName = "By name",
        sortByColor = "By color",
        sortByScore = "By score",
        sortByStatus = "By status",
        sortBySphere = "By sphere",
        sortByLevel = "By level",
        skipDay = "Skip day",
        search = "Search"
    )

    @Test
    fun `buildHabitToolbarState builds normal mode filters sorts and sync`() {
        val state = buildHabitToolbarState(
            archive = false,
            searching = false,
            query = "",
            showArchived = false,
            showCompleted = true,
            areQuestionMarksEnabled = true,
            isSkipEnabled = false,
            primaryOrder = HabitList.Order.BY_SCORE_DESC,
            isHabitSpheresEnabled = true,
            isDayTiersEnabled = true,
            isClassicLayout = false,
            showCardBorders = true,
            syncReady = true,
            isSyncing = true,
            labels = labels
        )

        assertEquals("Habits", state.title)
        assertFalse(state.archive)
        assertFalse(state.searching)
        assertEquals(4, state.searchEndMarginDp)
        assertEquals(4, state.cardEndMarginDp)
        assertTrue(state.syncVisible)
        assertTrue(state.syncing)

        assertEquals(2, state.filters.size)
        assertTrue(state.filters[0].checked) // !showArchived
        assertEquals("Hide entered", state.filters[1].title)
        assertFalse(state.filters[1].checked) // !showCompleted

        assertEquals(7, state.sorts.size)
        val scoreSort = state.sorts.first { it.id == R.id.actionSortScore }
        assertTrue(scoreSort.checked)
        assertTrue(scoreSort.supportsDirection)
        assertTrue(scoreSort.isDescending)

        assertEquals(
            listOf(R.id.actionSkipDay, R.id.actionSearch),
            state.overflow.map { it.id }
        )
    }

    @Test
    fun `buildHabitToolbarState falls back disabled sphere and tier sorts and respects classic margin`() {
        val state = buildHabitToolbarState(
            archive = false,
            searching = false,
            query = "",
            showArchived = true,
            showCompleted = false,
            areQuestionMarksEnabled = false,
            isSkipEnabled = false,
            primaryOrder = HabitList.Order.BY_SPHERE,
            isHabitSpheresEnabled = false,
            isDayTiersEnabled = false,
            isClassicLayout = true,
            showCardBorders = false,
            syncReady = false,
            isSyncing = false,
            labels = labels
        )

        assertEquals(0, state.searchEndMarginDp)
        assertEquals(0, state.cardEndMarginDp)
        assertFalse(state.syncVisible)
        assertEquals("Hide completed", state.filters[1].title)
        assertEquals(5, state.sorts.size)
        assertTrue(state.sorts.first { it.id == R.id.actionSortManual }.checked)
    }

    @Test
    fun `buildHabitToolbarState handles archive and active search modes cleanly`() {
        val archiveState = buildHabitToolbarState(
            archive = true,
            searching = false,
            query = "",
            showArchived = true,
            showCompleted = true,
            areQuestionMarksEnabled = false,
            isSkipEnabled = false,
            primaryOrder = HabitList.Order.BY_POSITION,
            isHabitSpheresEnabled = false,
            isDayTiersEnabled = false,
            isClassicLayout = false,
            showCardBorders = false,
            syncReady = true,
            isSyncing = false,
            labels = labels
        )
        assertEquals("Archive", archiveState.title)
        assertTrue(archiveState.archive)
        assertFalse(archiveState.syncVisible)
        assertTrue(archiveState.filters.isEmpty())
        assertTrue(archiveState.sorts.isEmpty())
        assertEquals(listOf(R.id.actionSearch), archiveState.overflow.map { it.id })
        assertEquals(2, archiveState.cardEndMarginDp)

        val searchState = buildHabitToolbarState(
            archive = false,
            searching = true,
            query = "run",
            showArchived = false,
            showCompleted = true,
            areQuestionMarksEnabled = false,
            isSkipEnabled = false,
            primaryOrder = HabitList.Order.BY_POSITION,
            isHabitSpheresEnabled = false,
            isDayTiersEnabled = false,
            isClassicLayout = false,
            showCardBorders = true,
            syncReady = true,
            isSyncing = true,
            labels = labels
        )
        assertTrue(searchState.searching)
        assertEquals("run", searchState.query)
        assertFalse(searchState.syncVisible)
        assertFalse(searchState.syncing)
        assertTrue(searchState.filters.isEmpty())
        assertTrue(searchState.sorts.isEmpty())
        assertTrue(searchState.overflow.isEmpty())
    }

    @Test
    fun `mapWindowOffsetToLocalOffset maps celebration coordinates relative to single compose host`() {
        assertEquals(
            Offset.Zero,
            mapWindowOffsetToLocalOffset(Offset.Zero, Offset(20f, 96f))
        )
        assertEquals(
            Offset(520f, 524f),
            mapWindowOffsetToLocalOffset(Offset(540f, 620f), Offset(20f, 96f))
        )
    }

    @Test
    fun `resolveHabitListDisplayModeState treats blank dataset states consistently`() {
        assertEquals(
            HabitListDisplayModeState.LOADING,
            resolveHabitListDisplayModeState(
                hasLoaded = false,
                lastRefreshFailed = false,
                visibleCount = 0,
                isSearching = false,
                hasNoHabit = true
            )
        )
        assertEquals(
            HabitListDisplayModeState.ERROR,
            resolveHabitListDisplayModeState(
                hasLoaded = false,
                lastRefreshFailed = true,
                visibleCount = 0,
                isSearching = false,
                hasNoHabit = true
            )
        )
        assertEquals(
            HabitListDisplayModeState.EMPTY_NO_HABITS,
            resolveHabitListDisplayModeState(
                hasLoaded = true,
                lastRefreshFailed = false,
                visibleCount = 0,
                isSearching = false,
                hasNoHabit = true
            )
        )
        assertEquals(
            HabitListDisplayModeState.EMPTY_ALL_DONE,
            resolveHabitListDisplayModeState(
                hasLoaded = true,
                lastRefreshFailed = false,
                visibleCount = 0,
                isSearching = false,
                hasNoHabit = false
            )
        )
        assertEquals(
            HabitListDisplayModeState.EMPTY_SEARCH,
            resolveHabitListDisplayModeState(
                hasLoaded = true,
                lastRefreshFailed = false,
                visibleCount = 0,
                isSearching = true,
                hasNoHabit = true
            )
        )
        assertEquals(
            HabitListDisplayModeState.CONTENT,
            resolveHabitListDisplayModeState(
                hasLoaded = true,
                lastRefreshFailed = false,
                visibleCount = 3,
                isSearching = false,
                hasNoHabit = false
            )
        )
    }

    @Test
    fun `consolidated host retains public API without tbar or adapter data observer`() {
        val methods = ListHabitsRootView::class.java.methods.map { it.name }.toSet()
        assertFalse(methods.contains("getTbar"))
        assertTrue(methods.contains("bindSelectionMenu"))
        assertTrue(methods.contains("startCelebration"))
        assertTrue(methods.contains("refreshDisplayState"))

        val projectRoot = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val rootViewSource = File(
            projectRoot,
            "uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/ListHabitsRootView.kt"
        ).readText()
        assertFalse(rootViewSource.contains("AdapterDataObserver"))
        assertFalse(rootViewSource.contains("buildToolbar("))
        assertFalse(rootViewSource.contains("RelativeLayout"))

        val fragmentSource = File(
            projectRoot,
            "uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/ListHabitsFragment.kt"
        ).readText()
        assertFalse(fragmentSource.contains("setSupportActionBar"))
        assertFalse(fragmentSource.contains("onCreateOptionsMenu"))
        assertFalse(fragmentSource.contains("setHasOptionsMenu"))
    }
}
