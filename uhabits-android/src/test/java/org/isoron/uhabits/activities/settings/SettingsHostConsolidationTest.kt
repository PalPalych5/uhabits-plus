package org.isoron.uhabits.activities.settings

import org.isoron.uhabits.activities.settings.SettingsCardMorphController.SettleTarget
import org.isoron.uhabits.activities.settings.SettingsCardMorphController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsHostConsolidationTest {

    @Test
    fun preparedReturnRevealsBottomNavigationBeforeShowingHomeOrRestoringScroll() {
        val events = mutableListOf<String>()

        SettingsReturnCoordinator.runPreparedReturn(
            revealBottomNavigationHidden = { events += "revealBottomNavHidden" },
            showHomeFragment = { events += "showHome" },
            restoreHomeScrollAndStart = { events += "restoreHomeScrollAndStart" },
        )

        assertEquals(
            listOf("revealBottomNavHidden", "showHome", "restoreHomeScrollAndStart"),
            events,
        )
    }

    @Test
    fun immediateReturnSettlesOverlayRevealsBottomNavShowsHomePopsAndRestoresScrollInOrder() {
        val events = mutableListOf<String>()

        SettingsReturnCoordinator.runImmediateReturn(
            settleOverlayHome = { events += "settleOverlayHome" },
            revealBottomNavigation = { events += "revealBottomNav" },
            showHomeFragment = { events += "showHome" },
            popDetailBackStack = { events += "popDetail" },
            restoreHomeScroll = { events += "restoreHomeScroll" },
            syncChrome = { events += "syncChrome" },
        )

        assertEquals(
            listOf(
                "settleOverlayHome",
                "revealBottomNav",
                "showHome",
                "popDetail",
                "restoreHomeScroll",
                "syncChrome",
            ),
            events,
        )
    }

    @Test
    fun queuedBackDuringForwardTransitionsCleanlyThroughDetailBackToIdleHome() {
        val sm = SettingsMorphStateMachine()

        assertEquals(State.IDLE_HOME, sm.state)
        assertTrue(sm.reserveForward())
        assertEquals(State.PREPARING_FORWARD, sm.state)
        assertFalse("Duplicate forward tap must be ignored", sm.reserveForward())

        assertTrue(sm.requestBackDuringForward())
        assertTrue(sm.startAnimatingForward())
        assertFalse("Cannot start return while forward is animating", sm.reserveReturn())

        assertTrue("Queued back must survive until forward settles", sm.finishForward())
        assertEquals(State.IDLE_DETAIL, sm.state)
        assertFalse(sm.backQueued)

        assertTrue(sm.reserveReturn())
        assertTrue(sm.startAnimatingReturn())
        sm.finishReturn()
        assertEquals(State.IDLE_HOME, sm.state)
    }

    @Test
    fun cancelAndSettleClearsQueuedBackAndRestoresTargetIdleState() {
        val sm = SettingsMorphStateMachine()
        assertFalse(sm.needsCancel(SettleTarget.HOME))

        assertTrue(sm.reserveForward())
        assertTrue(sm.requestBackDuringForward())
        assertTrue(sm.needsCancel(SettleTarget.HOME))

        sm.beginCancel()
        assertEquals(State.CANCELLING, sm.state)
        sm.finishCancel(SettleTarget.HOME)

        assertEquals(State.IDLE_HOME, sm.state)
        assertFalse(sm.backQueued)
        assertFalse(sm.needsCancel(SettleTarget.HOME))

        assertTrue(sm.reserveForward())
        sm.beginCancel()
        sm.finishCancel(SettleTarget.DETAIL)
        assertEquals(State.IDLE_DETAIL, sm.state)
        assertFalse(sm.needsCancel(SettleTarget.DETAIL))
    }

    @Test
    fun partiallyVisibleBottomCardBoundsConvergeAcrossForwardAndReturnMorph() {
        val startBounds = MorphRect(left = 32f, top = 1650f, right = 1048f, bottom = 1980f)
        val endBounds = MorphRect(left = 0f, top = 0f, right = 1080f, bottom = 2200f)
        val startRadius = 36f

        val forwardStart = SettingsMorphFrameMath.computeFrame(startBounds, endBounds, startRadius, 0f, isForward = true)
        val forwardMid = SettingsMorphFrameMath.computeFrame(startBounds, endBounds, startRadius, 0.5f, isForward = true)
        val forwardEnd = SettingsMorphFrameMath.computeFrame(startBounds, endBounds, startRadius, 1f, isForward = true)

        assertEquals(startBounds, forwardStart.bounds)
        assertEquals(startRadius, forwardStart.cornerRadius, 0.001f)
        assertEquals(endBounds, forwardEnd.bounds)
        assertEquals(0f, forwardEnd.cornerRadius, 0.001f)
        assertTrue(forwardMid.bounds.top < startBounds.top && forwardMid.bounds.top > endBounds.top)
        assertTrue(forwardMid.bounds.bottom > startBounds.bottom && forwardMid.bounds.bottom < endBounds.bottom)

        val returnStart = SettingsMorphFrameMath.computeFrame(startBounds, endBounds, startRadius, 0f, isForward = false)
        val returnMid = SettingsMorphFrameMath.computeFrame(startBounds, endBounds, startRadius, 0.5f, isForward = false)
        val returnEnd = SettingsMorphFrameMath.computeFrame(startBounds, endBounds, startRadius, 1f, isForward = false)

        assertEquals(endBounds, returnStart.bounds)
        assertEquals(0f, returnStart.cornerRadius, 0.001f)
        assertEquals(startBounds, returnEnd.bounds)
        assertEquals(startRadius, returnEnd.cornerRadius, 0.001f)
        assertTrue(returnMid.bounds.top > endBounds.top && returnMid.bounds.top < startBounds.top)
        assertTrue(returnMid.bounds.bottom < endBounds.bottom && returnMid.bounds.bottom > startBounds.bottom)
    }

    @Test
    fun homeSectionsPreserveStatisticsDirectActionAndDynamicDeveloperVisibility() {
        var directDialogCalls = 0
        var detailOpens = 0
        val appearance = SettingsSectionModel.fromItems(
            id = SettingsSectionId.APPEARANCE,
            iconRes = 1,
            title = "Appearance",
            summary = "Theme and layout",
            items = emptyList(),
        )
        val statistics = SettingsSectionModel.fromItems(
            id = SettingsSectionId.STATISTICS,
            iconRes = 2,
            title = "Statistics",
            summary = "Preferences",
            items = emptyList(),
            homeEndIconRes = 99,
            homeAction = { directDialogCalls += 1 },
        )
        val developer = SettingsSectionModel.fromItems(
            id = SettingsSectionId.DEVELOPER,
            iconRes = 3,
            title = "Developer",
            summary = "Diagnostics",
            items = emptyList(),
        )

        val withoutDev = SettingsListComposer.composeHome(
            sections = listOf(appearance, statistics),
            accessibilityDescription = { "${it.title}: ${it.summary}" },
            onSectionClick = { detailOpens += 1 },
        )
        assertEquals(listOf("section:APPEARANCE", "section:STATISTICS"), withoutDev.map { it.stableKey })

        val statsSection = withoutDev.last() as SettingItem.Section
        val appearanceSection = withoutDev.first() as SettingItem.Section
        assertEquals(99, statsSection.endIconRes)
        assertEquals(null, appearanceSection.endIconRes)

        statsSection.onClick(null)
        assertEquals(1, directDialogCalls)
        assertEquals(0, detailOpens)

        // Null transition source cannot accidentally bypass Statistics or launch morph without anchor.
        appearanceSection.onClick(null)
        assertEquals(0, detailOpens)

        val withDev = SettingsListComposer.composeHome(
            sections = listOf(appearance, statistics, developer),
            accessibilityDescription = { "${it.title}: ${it.summary}" },
            onSectionClick = { detailOpens += 1 },
        )
        assertEquals(
            listOf("section:APPEARANCE", "section:STATISTICS", "section:DEVELOPER"),
            withDev.map { it.stableKey },
        )
    }

    @Test
    fun consolidatedComposeListAndSectionHostPreserveScrollAndComposeContracts() {
        val listComposeSource = readSettingsSource("SettingsListCompose.kt")
        val fragmentSource = readSettingsSource("SettingsFragment.kt")
        val sectionFragmentSource = readSettingsSource("SettingsSectionFragment.kt")

        assertTrue(listComposeSource.contains("internal fun SettingsListContent"))
        assertTrue(listComposeSource.contains("LazyColumn"))
        assertTrue(listComposeSource.contains("key = { it.stableKey }"))

        assertFalse(fragmentSource.contains("useComposeRows"))
        assertFalse(fragmentSource.contains("CustomSettingsAdapter"))
        assertFalse(fragmentSource.contains("RecyclerView"))
        assertTrue(fragmentSource.contains("SettingsListContent"))

        assertTrue(sectionFragmentSource.contains("STATE_HOME_SCROLL"))
        assertTrue(sectionFragmentSource.contains("SettingsReturnCoordinator.runPreparedReturn"))
        assertTrue(sectionFragmentSource.contains("SettingsReturnCoordinator.runImmediateReturn"))
        assertTrue(sectionFragmentSource.contains("captureHomeScrollState()"))
    }

    private fun readSettingsSource(fileName: String): String {
        val sourceDir = listOf(
            Path.of("src/main/java/org/isoron/uhabits/activities/settings"),
            Path.of("uhabits-android/src/main/java/org/isoron/uhabits/activities/settings"),
        ).first { Files.isDirectory(it) }
        return String(Files.readAllBytes(sourceDir.resolve(fileName)), Charsets.UTF_8)
    }
}
