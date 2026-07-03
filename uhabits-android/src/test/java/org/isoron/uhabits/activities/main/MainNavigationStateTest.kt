package org.isoron.uhabits.activities.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationStateTest {
    @Test
    fun repeatedDestinationIsNoOp() {
        val state = MainNavigationState()

        assertFalse(state.navigate(MainDestination.HABITS))
        assertEquals(emptyList<MainDestination>(), state.history())
    }

    @Test
    fun backFollowsVisitHistory() {
        val state = MainNavigationState()

        assertTrue(state.navigate(MainDestination.STATISTICS))
        assertEquals(MainDestination.HABITS, state.navigateBack())
        assertNull(state.navigateBack())
    }

    @Test
    fun stateCanBeRestored() {
        val original = MainNavigationState()
        original.navigate(MainDestination.SETTINGS)

        val restored = MainNavigationState(original.current, original.history())

        assertEquals(MainDestination.SETTINGS, restored.current)
        assertEquals(MainDestination.HABITS, restored.navigateBack())
    }

    @Test
    fun archiveSelectsHabitsBottomItem() {
        assertEquals(
            MainDestination.HABITS,
            MainDestination.ARCHIVE.bottomItemDestination
        )
    }
}
