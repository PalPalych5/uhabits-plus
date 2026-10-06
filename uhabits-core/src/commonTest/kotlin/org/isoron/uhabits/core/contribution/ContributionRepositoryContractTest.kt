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

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import kotlin.test.*

abstract class ContributionRepositoryContractTest {

    protected abstract suspend fun createRepository(): ContributionRepository
    protected open suspend fun cleanup() {}

    private fun sampleContribution(
        id: String = "c-1",
        habitUuid: String = "h-1",
        sourceType: ContributionSourceType = ContributionSourceType.FOCUS_SESSION,
        sourceId: String = "s-1",
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
    fun testRecordAndGetById() = runTest {
        val repo = createRepository()
        try {
            val c = sampleContribution()
            val res = repo.record(c)
            assertEquals(ContributionRecordResult.INSERTED, res)

            val retrieved = repo.getById(c.id)
            assertEquals(c, retrieved)
            assertNull(repo.getById(ContributionId("nonexistent")))
        } finally {
            cleanup()
        }
    }

    @Test
    fun testIdempotentExactRetry() = runTest {
        val repo = createRepository()
        try {
            val c = sampleContribution()
            assertEquals(ContributionRecordResult.INSERTED, repo.record(c))
            assertEquals(ContributionRecordResult.ALREADY_RECORDED, repo.record(c))
            assertEquals(1, repo.getAll(includeRemoved = true).size)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testConflictOnSameIdWithDifferentPayload() = runTest {
        val repo = createRepository()
        try {
            val c = sampleContribution(id = "c-1", value = 100L)
            assertEquals(ContributionRecordResult.INSERTED, repo.record(c))

            val conflict = sampleContribution(id = "c-1", value = 200L)
            assertFailsWith<ContributionConflictException> {
                repo.record(conflict)
            }
            assertEquals(100L, repo.getById(ContributionId("c-1"))?.contributedValue)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testConflictOnSameSourceAndTargetWithDifferentId() = runTest {
        val repo = createRepository()
        try {
            val c1 = sampleContribution(id = "c-1", sourceId = "s-1", habitUuid = "h-1")
            assertEquals(ContributionRecordResult.INSERTED, repo.record(c1))

            val c2 = sampleContribution(id = "c-2", sourceId = "s-1", habitUuid = "h-1")
            assertFailsWith<ContributionConflictException> {
                repo.record(c2)
            }
            assertEquals("c-1", repo.getBySourceAndTarget(ContributionSourceType.FOCUS_SESSION, "s-1", HabitRef("h-1"))?.id?.value)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testMultipleTargetsForSameSourceWithoutCollision() = runTest {
        val repo = createRepository()
        try {
            val c1 = sampleContribution(id = "c-1", sourceId = "session-42", habitUuid = "habit-study")
            val c2 = sampleContribution(id = "c-2", sourceId = "session-42", habitUuid = "habit-project")

            assertEquals(ContributionRecordResult.INSERTED, repo.record(c1))
            assertEquals(ContributionRecordResult.INSERTED, repo.record(c2))

            assertEquals(2, repo.getAll().size)
            assertEquals(c1, repo.getBySourceAndTarget(ContributionSourceType.FOCUS_SESSION, "session-42", HabitRef("habit-study")))
            assertEquals(c2, repo.getBySourceAndTarget(ContributionSourceType.FOCUS_SESSION, "session-42", HabitRef("habit-project")))
            assertEquals(listOf(c1, c2), repo.getBySource(ContributionSourceType.FOCUS_SESSION, "session-42"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun testMultipleSourcesForSameTargetWithoutCollision() = runTest {
        val repo = createRepository()
        try {
            val c1 = sampleContribution(id = "c-1", sourceId = "s-1", habitUuid = "h-shared", value = 10L, effectiveTimestamp = 100L)
            val c2 = sampleContribution(id = "c-2", sourceId = "s-2", habitUuid = "h-shared", value = 20L, effectiveTimestamp = 200L)

            assertEquals(ContributionRecordResult.INSERTED, repo.record(c1))
            assertEquals(ContributionRecordResult.INSERTED, repo.record(c2))

            assertEquals(listOf(c1, c2), repo.getByHabit(HabitRef("h-shared")))
            assertEquals(30L, repo.getActiveTotalForHabit(HabitRef("h-shared")))
        } finally {
            cleanup()
        }
    }

    @Test
    fun testRemovalBySourceAndTarget() = runTest {
        val repo = createRepository()
        try {
            val c1 = sampleContribution(id = "c-1", sourceId = "s-1", habitUuid = "h-1")
            val c2 = sampleContribution(id = "c-2", sourceId = "s-1", habitUuid = "h-2")
            repo.record(c1)
            repo.record(c2)

            // Remove c1
            val res = repo.remove(ContributionSourceType.FOCUS_SESSION, "s-1", HabitRef("h-1"), removedAt = 5000L)
            assertEquals(ContributionRemovalResult.REMOVED, res)

            // Verify c1 tombstoned, c2 remains active
            val c1Tombstone = repo.getById(ContributionId("c-1"))
            assertNotNull(c1Tombstone)
            assertFalse(c1Tombstone.isActive)
            assertEquals(5000L, c1Tombstone.removedAt)

            val c2Active = repo.getById(ContributionId("c-2"))
            assertNotNull(c2Active)
            assertTrue(c2Active.isActive)

            // Repeated removal is safe / idempotent
            val retryRes = repo.remove(ContributionSourceType.FOCUS_SESSION, "s-1", HabitRef("h-1"), removedAt = 5000L)
            assertEquals(ContributionRemovalResult.ALREADY_REMOVED, retryRes)

            // Removing non-existent returns NOT_FOUND
            val notFound = repo.remove(ContributionSourceType.FOCUS_SESSION, "s-999", HabitRef("h-1"), removedAt = 5000L)
            assertEquals(ContributionRemovalResult.NOT_FOUND, notFound)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testRemovalById() = runTest {
        val repo = createRepository()
        try {
            val c = sampleContribution(id = "c-id-test")
            repo.record(c)

            assertEquals(ContributionRemovalResult.REMOVED, repo.removeById(c.id, removedAt = 8000L))
            assertEquals(ContributionRemovalResult.ALREADY_REMOVED, repo.removeById(c.id, removedAt = 8000L))
            assertEquals(ContributionRemovalResult.NOT_FOUND, repo.removeById(ContributionId("nonexistent"), removedAt = 8000L))

            val stored = repo.getById(c.id)
            assertNotNull(stored)
            assertFalse(stored.isActive)
            assertEquals(8000L, stored.removedAt)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testQueryFilteringActiveVsRemoved() = runTest {
        val repo = createRepository()
        try {
            val c1 = sampleContribution(id = "c-1", sourceId = "s-1", habitUuid = "h-1", effectiveTimestamp = 100L)
            val c2 = sampleContribution(id = "c-2", sourceId = "s-2", habitUuid = "h-1", effectiveTimestamp = 200L)
            val c3 = sampleContribution(id = "c-3", sourceId = "s-3", habitUuid = "h-1", effectiveTimestamp = 300L)
            repo.record(c1)
            repo.record(c2)
            repo.record(c3)

            repo.removeById(ContributionId("c-2"), removedAt = 1000L)

            // getAll
            assertEquals(listOf(c1, c3), repo.getAll(includeRemoved = false))
            assertEquals(3, repo.getAll(includeRemoved = true).size)

            // getByHabit
            assertEquals(listOf(c1, c3), repo.getByHabit(HabitRef("h-1"), includeRemoved = false))
            assertEquals(3, repo.getByHabit(HabitRef("h-1"), includeRemoved = true).size)

            // getByHabitInRange
            assertEquals(listOf(c1), repo.getByHabitInRange(HabitRef("h-1"), 50L, 150L))
            assertEquals(emptyList(), repo.getByHabitInRange(HabitRef("h-1"), 150L, 250L, includeRemoved = false))
            assertEquals(1, repo.getByHabitInRange(HabitRef("h-1"), 150L, 250L, includeRemoved = true).size)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testAggregateTotalsExcludesTombstones() = runTest {
        val repo = createRepository()
        try {
            val c1 = sampleContribution(id = "c-1", sourceId = "s-1", habitUuid = "h-agg", value = 100L, effectiveTimestamp = 10L)
            val c2 = sampleContribution(id = "c-2", sourceId = "s-2", habitUuid = "h-agg", value = 50L, effectiveTimestamp = 20L)
            val c3 = sampleContribution(id = "c-3", sourceId = "s-3", habitUuid = "h-other", value = 30L, effectiveTimestamp = 30L)
            repo.record(c1)
            repo.record(c2)
            repo.record(c3)

            assertEquals(150L, repo.getActiveTotalForHabit(HabitRef("h-agg")))
            assertEquals(30L, repo.getActiveTotalForHabit(HabitRef("h-other")))
            assertEquals(0L, repo.getActiveTotalForHabit(HabitRef("h-missing")))

            // In range
            assertEquals(100L, repo.getActiveTotalForHabitInRange(HabitRef("h-agg"), 5L, 15L))
            assertEquals(150L, repo.getActiveTotalForHabitInRange(HabitRef("h-agg"), 5L, 25L))

            // Remove c2
            repo.removeById(ContributionId("c-2"), removedAt = 5000L)
            assertEquals(100L, repo.getActiveTotalForHabit(HabitRef("h-agg")))
            assertEquals(100L, repo.getActiveTotalForHabitInRange(HabitRef("h-agg"), 5L, 25L))
        } finally {
            cleanup()
        }
    }

    @Test
    fun testExplicitZeroSemantics() = runTest {
        val repo = createRepository()
        try {
            val zeroContrib = sampleContribution(id = "c-zero", value = 0L, habitUuid = "h-zero")
            assertEquals(ContributionRecordResult.INSERTED, repo.record(zeroContrib))

            // Record exists and is distinct from missing
            val found = repo.getById(ContributionId("c-zero"))
            assertNotNull(found)
            assertEquals(0L, found.contributedValue)

            // Total is 0, but record count is 1
            assertEquals(0L, repo.getActiveTotalForHabit(HabitRef("h-zero")))
            assertEquals(1, repo.getByHabit(HabitRef("h-zero")).size)

            // Missing habit returns 0 total and empty list
            assertEquals(0L, repo.getActiveTotalForHabit(HabitRef("h-nonexistent")))
            assertTrue(repo.getByHabit(HabitRef("h-nonexistent")).isEmpty())
        } finally {
            cleanup()
        }
    }
}

class MemoryContributionRepositoryContractTest : ContributionRepositoryContractTest() {
    override suspend fun createRepository(): ContributionRepository = MemoryContributionRepository()
}

class SQLiteContributionRepositoryContractTest : ContributionRepositoryContractTest() {
    private var db: Database? = null

    override suspend fun createRepository(): ContributionRepository {
        val database = TestDatabaseHelper.createEmptyDatabase()
        db = database
        ContributionSchema.createSchema(database)
        return SQLiteContributionRepository(database)
    }

    override suspend fun cleanup() {
        db?.close()
        db = null
    }
}
