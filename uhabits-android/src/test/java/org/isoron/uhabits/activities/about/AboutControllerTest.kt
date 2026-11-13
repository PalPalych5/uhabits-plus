/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutControllerTest {

    @Test
    fun versionTap_unlocksDeveloperOnFifthTapOnlyOnce() {
        var persistedCount = 0
        val state = AboutDeveloperUnlockState(
            initialIsDeveloper = false,
            persistDeveloperEnabled = { persistedCount++ },
        )

        repeat(4) { index ->
            val outcome = state.onVersionTap()
            assertEquals(DeveloperUnlockOutcome.Countdown(4 - index), outcome)
            assertFalse(state.isDeveloperEnabled)
            assertEquals(0, persistedCount)
        }

        assertEquals(DeveloperUnlockOutcome.Activated, state.onVersionTap())
        assertTrue(state.isDeveloperEnabled)
        assertEquals(1, persistedCount)

        assertEquals(DeveloperUnlockOutcome.AlreadyEnabled, state.onVersionTap())
        assertEquals(0, state.remainingTaps)
        assertEquals(1, persistedCount)
    }

    @Test
    fun versionTap_whenAlreadyDeveloper_doesNotRetriggerOrDecrement() {
        var persistedCount = 0
        val state = AboutDeveloperUnlockState(
            initialIsDeveloper = true,
            persistDeveloperEnabled = { persistedCount++ },
        )

        repeat(6) {
            assertEquals(DeveloperUnlockOutcome.AlreadyEnabled, state.onVersionTap())
            assertTrue(state.isDeveloperEnabled)
            assertEquals(0, state.remainingTaps)
            assertEquals(0, persistedCount)
        }
    }

    @Test
    fun creditsData_preservesForkUrlsAndUpstreamAttribution() {
        assertEquals("https://github.com/PalPalych5/uhabits-plus", AboutCreditsData.SOURCE_CODE_URL)
        assertEquals("https://github.com/PalPalych5/uhabits-plus/issues", AboutCreditsData.ISSUES_URL)
        assertEquals("https://github.com/iSoron/uhabits", AboutCreditsData.UPSTREAM_REPO_URL)
        assertEquals("https://github.com/iSoron/uhabits/graphs/contributors", AboutCreditsData.UPSTREAM_CONTRIBUTORS_URL)
        assertEquals("GPL-3.0-or-later", AboutCreditsData.PROJECT_LICENSE_SPDX)
        assertEquals("PalPalych5", AboutCreditsData.FORK_MAINTAINER)
        assertEquals(15, AboutCreditsData.upstreamDevelopers.size)
        assertTrue(AboutCreditsData.upstreamTranslators.isNotEmpty())
        assertTrue(AboutCreditsData.openSourceNotices.any { it.name == "UHabit Next" && it.license == "GPL-3.0-or-later" })
        assertTrue(AboutCreditsData.openSourceNotices.any { it.name == "Loop Habit Tracker" && it.license == "GPL-3.0-or-later" })
        assertFalse(AboutCreditsData.openSourceNotices.any { it.name.contains("Ktor", ignoreCase = true) })
        assertTrue(AboutCreditsData.openSourceNotices.any { it.name == "Konfetti" && it.license == "ISC License" })
        assertTrue(AboutCreditsData.openSourceNotices.any { it.name == "Android Desugar JDK Libs" && it.license.contains("Classpath Exception") })
        assertTrue(AboutCreditsData.openSourceNotices.any { it.name == "Tabler Icons" && it.license == "MIT License" })
        assertTrue(AboutCreditsData.openSourceNotices.any { it.name == "Lucide Icons" && it.license.startsWith("ISC License") })
    }

    @Test
    fun destinationParent_preservesSecondaryBackHierarchy() {
        assertEquals(null, AboutDestination.MAIN.parent())
        assertEquals(AboutDestination.MAIN, AboutDestination.CONTRIBUTORS.parent())
        assertEquals(AboutDestination.MAIN, AboutDestination.LICENSES.parent())
        assertEquals(AboutDestination.MAIN, AboutDestination.GPL_LICENSE.parent())
        assertEquals(AboutDestination.LICENSES, AboutDestination.FULL_NOTICE.parent())
    }
}
