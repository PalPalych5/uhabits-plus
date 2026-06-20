package org.isoron.uhabits.core.models

import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HabitMatcherTest : BaseUnitTest() {
    @Test
    fun activeMatcherExcludesArchivedHabits() {
        val active = fixtures.createEmptyHabit()
        val archived = fixtures.createEmptyHabit().apply { isArchived = true }

        assertTrue(HabitMatcher().matches(active))
        assertFalse(HabitMatcher().matches(archived))
    }

    @Test
    fun includeArchivedMatcherAcceptsBothKinds() {
        val active = fixtures.createEmptyHabit()
        val archived = fixtures.createEmptyHabit().apply { isArchived = true }
        val matcher = HabitMatcher(isArchivedAllowed = true)

        assertTrue(matcher.matches(active))
        assertTrue(matcher.matches(archived))
    }

    @Test
    fun archiveOnlyMatcherRejectsActiveHabits() {
        val active = fixtures.createEmptyHabit()
        val archived = fixtures.createEmptyHabit().apply { isArchived = true }
        val matcher = HabitMatcher(
            isArchivedAllowed = true,
            isArchivedRequired = true
        )

        assertFalse(matcher.matches(active))
        assertTrue(matcher.matches(archived))
    }
}
