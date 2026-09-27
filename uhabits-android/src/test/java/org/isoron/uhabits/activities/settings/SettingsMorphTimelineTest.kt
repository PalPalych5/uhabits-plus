package org.isoron.uhabits.activities.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMorphTimelineTest {
    @Test
    fun smoothstepHasStableEndpointsAndIsMonotonic() {
        assertEquals(0f, SettingsTransitionSpec.smoothstep(0.2f, 0.8f, 0f), 0f)
        assertEquals(0f, SettingsTransitionSpec.smoothstep(0.2f, 0.8f, 0.2f), 0f)
        assertEquals(1f, SettingsTransitionSpec.smoothstep(0.2f, 0.8f, 0.8f), 0f)
        assertEquals(1f, SettingsTransitionSpec.smoothstep(0.2f, 0.8f, 1f), 0f)

        var previous = 0f
        for (step in 0..100) {
            val current = SettingsTransitionSpec.smoothstep(0.03f, 0.95f, step / 100f)
            assertTrue(current >= previous)
            previous = current
        }
    }

    @Test
    fun forwardHandoffWindowsKeepSurfacePopulated() {
        assertEquals(1f, SettingsTransitionSpec.forwardSourceAlpha(0.18f), 0.0001f)
        assertEquals(0f, SettingsTransitionSpec.forwardSourceAlpha(0.48f), 0.0001f)
        assertTrue(SettingsTransitionSpec.forwardToolbarAlpha(0.70f) > 0f)
        assertTrue(SettingsTransitionSpec.forwardBodyAlpha(0.76f) > 0f)
        assertEquals(1f, SettingsTransitionSpec.forwardToolbarAlpha(0.84f), 0.0001f)
        assertEquals(1f, SettingsTransitionSpec.forwardBodyAlpha(0.92f), 0.0001f)
    }

    @Test
    fun returnUsesIndependentContentAndSourceChannels() {
        assertEquals(1f, SettingsTransitionSpec.returnToolbarAlpha(0f), 0f)
        assertEquals(1f, SettingsTransitionSpec.returnBodyAlpha(0f), 0f)
        assertEquals(0f, SettingsTransitionSpec.returnSourceAlpha(0.22f), 0.0001f)
        assertTrue(SettingsTransitionSpec.returnSourceAlpha(0.40f) > 0f)
        assertTrue(SettingsTransitionSpec.returnBodyAlpha(0.40f) > 0f)
        assertEquals(1f, SettingsTransitionSpec.returnSourceAlpha(0.62f), 0.0001f)
        assertEquals(1f, SettingsTransitionSpec.returnNavigationAlpha(0.42f), 0.0001f)
    }
}
