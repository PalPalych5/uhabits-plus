/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.core.contribution

import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContributionDomainTest {

    private fun sampleContribution(
        id: String = "contrib-1",
        habitUuid: String = "habit-1",
        sourceType: ContributionSourceType = ContributionSourceType.FOCUS_SESSION,
        sourceId: String = "session-1",
        value: Long = 42000L,
        effectiveTimestamp: Long = 1000L,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L,
        removedAt: Long? = null,
    ) = Contribution(
        id = ContributionId(id),
        targetHabit = HabitRef(habitUuid),
        sourceType = sourceType,
        sourceId = sourceId,
        contributedValue = value,
        effectiveTimestamp = effectiveTimestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = removedAt,
    )

    @Test
    fun testValidContributionCreation() {
        val c = sampleContribution()
        assertEquals(ContributionId("contrib-1"), c.id)
        assertEquals(HabitRef("habit-1"), c.targetHabit)
        assertEquals(ContributionSourceType.FOCUS_SESSION, c.sourceType)
        assertEquals("session-1", c.sourceId)
        assertEquals(42000L, c.contributedValue)
        assertEquals(1000L, c.effectiveTimestamp)
        assertTrue(c.isActive)
        assertNull(c.removedAt)
    }

    @Test
    fun testExplicitZeroContributedValueIsValid() {
        val c = sampleContribution(value = 0L)
        assertEquals(0L, c.contributedValue)
        assertTrue(c.isActive)
    }

    @Test
    fun testNegativeValueIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            sampleContribution(value = -1L)
        }
    }

    @Test
    fun testBlankIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ContributionId("   ")
        }
    }

    @Test
    fun testBlankSourceIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            sampleContribution(sourceId = "   ")
        }
    }

    @Test
    fun testTimestampsValidation() {
        assertFailsWith<IllegalArgumentException> {
            sampleContribution(createdAt = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            sampleContribution(createdAt = 2000L, updatedAt = 1000L)
        }
        assertFailsWith<IllegalArgumentException> {
            sampleContribution(createdAt = 2000L, removedAt = 1000L)
        }
    }

    @Test
    fun testTombstonedContributionState() {
        val c = sampleContribution(removedAt = 2000L, updatedAt = 2000L)
        assertFalse(c.isActive)
        assertEquals(2000L, c.removedAt)
    }

    @Test
    fun testContributionServiceExplicitProjection() {
        val repo = MemoryContributionRepository()
        val service = ContributionService(repo)

        val session = FocusSession(
            id = FocusSessionId("focus-42"),
            startedAt = 1000L,
            endedAt = 3520000L,
            durationMs = 2520000L, // 42 minutes
            source = FocusSessionSource.TIMER,
            createdAt = 1000L,
            updatedAt = 3520000L,
            primaryTargetType = FocusPrimaryTargetType.HABIT,
            habit = HabitRef("study-habit"),
        )

        // 1. Explicitly contribute to target habit
        val res = service.contributeFromFocusSession(
            session = session,
            targetHabit = HabitRef("study-habit"),
            contributionId = ContributionId("contrib-study-1"),
            contributedValue = 42000L, // 42 minutes in x1000 scale
        )
        assertEquals(ContributionRecordResult.INSERTED, res)

        val stored = repo.getById(ContributionId("contrib-study-1"))
        requireNotNull(stored)
        assertEquals(ContributionSourceType.FOCUS_SESSION, stored.sourceType)
        assertEquals("focus-42", stored.sourceId)
        assertEquals(HabitRef("study-habit"), stored.targetHabit)
        assertEquals(42000L, stored.contributedValue)

        // 2. Exact retry is idempotent
        val retry = service.contributeFromFocusSession(
            session = session,
            targetHabit = HabitRef("study-habit"),
            contributionId = ContributionId("contrib-study-1"),
            contributedValue = 42000L,
        )
        assertEquals(ContributionRecordResult.ALREADY_RECORDED, retry)

        // 3. Explicit revocation / removal
        val revokeRes = service.revokeFocusSessionContribution(
            sessionId = FocusSessionId("focus-42"),
            targetHabit = HabitRef("study-habit"),
            removedAt = 4000000L,
        )
        assertEquals(ContributionRemovalResult.REMOVED, revokeRes)

        val tombstoned = repo.getById(ContributionId("contrib-study-1"))
        requireNotNull(tombstoned)
        assertFalse(tombstoned.isActive)
        assertEquals(4000000L, tombstoned.removedAt)

        // 4. Repeated revocation is idempotent
        val repeatedRevoke = service.revokeFocusSessionContribution(
            sessionId = FocusSessionId("focus-42"),
            targetHabit = HabitRef("study-habit"),
            removedAt = 4000000L,
        )
        assertEquals(ContributionRemovalResult.ALREADY_REMOVED, repeatedRevoke)
    }
}
