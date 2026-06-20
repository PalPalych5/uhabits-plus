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

        assertFalse(state.navigate(MainDestination.TODAY))
        assertEquals(emptyList<MainDestination>(), state.history())
    }

    @Test
    fun backFollowsVisitHistory() {
        val state = MainNavigationState()

        assertTrue(state.navigate(MainDestination.HABITS))
        assertTrue(state.navigate(MainDestination.REPORTS))
        assertEquals(MainDestination.HABITS, state.navigateBack())
        assertEquals(MainDestination.TODAY, state.navigateBack())
        assertNull(state.navigateBack())
    }

    @Test
    fun stateCanBeRestored() {
        val original = MainNavigationState()
        original.navigate(MainDestination.HABITS)
        original.navigate(MainDestination.SETTINGS)

        val restored = MainNavigationState(original.current, original.history())

        assertEquals(MainDestination.SETTINGS, restored.current)
        assertEquals(MainDestination.HABITS, restored.navigateBack())
        assertEquals(MainDestination.TODAY, restored.navigateBack())
    }

    @Test
    fun archiveSelectsHabitsBottomItem() {
        assertEquals(
            MainDestination.HABITS,
            MainDestination.ARCHIVE.bottomItemDestination
        )
    }
}
