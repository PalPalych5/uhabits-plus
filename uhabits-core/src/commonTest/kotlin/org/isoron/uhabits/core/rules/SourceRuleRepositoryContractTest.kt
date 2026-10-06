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

package org.isoron.uhabits.core.rules

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.rules.memory.MemorySourceRuleRepository
import org.isoron.uhabits.core.rules.sqlite.SourceRuleSchema
import org.isoron.uhabits.core.rules.sqlite.SQLiteSourceRuleRepository
import kotlin.test.*

abstract class SourceRuleRepositoryContractTest {

    protected abstract suspend fun createRepository(): SourceRuleRepository
    protected open suspend fun cleanup() {}

    private fun sampleRule(
        id: String = "rule-1",
        containerId: String = "c-1",
        habitUuid: String = "h-1",
        includeDescendants: Boolean = true,
        isEnabled: Boolean = true,
        effectiveFrom: Long = 0L,
        createdAt: Long = 1000L,
        updatedAt: Long = createdAt,
        removedAt: Long? = null,
    ) = SourceRule(
        id = SourceRuleId(id),
        sourceKind = SourceRuleSourceKind.FOCUS_SESSION,
        sourceContainerId = ContainerId(containerId),
        includeDescendants = includeDescendants,
        targetHabit = HabitRef(habitUuid),
        valueMapping = SourceRuleValueMapping.FOCUS_DURATION,
        isEnabled = isEnabled,
        effectiveFrom = effectiveFrom,
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = removedAt,
    )

    @Test
    fun testRecordAndGetById() = runTest {
        val repo = createRepository()
        try {
            val rule = sampleRule()
            assertEquals(SourceRuleRecordResult.INSERTED, repo.record(rule))

            val retrieved = repo.getById(rule.id)
            assertEquals(rule, retrieved)
            assertNull(repo.getById(SourceRuleId("nonexistent")))
        } finally {
            cleanup()
        }
    }

    @Test
    fun testIdempotentExactRetry() = runTest {
        val repo = createRepository()
        try {
            val rule = sampleRule()
            assertEquals(SourceRuleRecordResult.INSERTED, repo.record(rule))
            assertEquals(SourceRuleRecordResult.ALREADY_RECORDED, repo.record(rule))
            assertEquals(1, repo.getAll(includeRemoved = true).size)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testConflictOnSameIdWithDifferentPayload() = runTest {
        val repo = createRepository()
        try {
            val rule1 = sampleRule(id = "rule-1", containerId = "c-1")
            assertEquals(SourceRuleRecordResult.INSERTED, repo.record(rule1))

            val conflict = sampleRule(id = "rule-1", containerId = "c-2")
            assertFailsWith<SourceRuleConflictException> {
                repo.record(conflict)
            }
            assertEquals("c-1", repo.getById(SourceRuleId("rule-1"))?.sourceContainerId?.value)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testConflictOnSameScopeWithDifferentId() = runTest {
        val repo = createRepository()
        try {
            val rule1 = sampleRule(id = "rule-1", containerId = "c-alpha", habitUuid = "h-alpha")
            assertEquals(SourceRuleRecordResult.INSERTED, repo.record(rule1))

            val rule2 = sampleRule(id = "rule-2", containerId = "c-alpha", habitUuid = "h-alpha")
            assertFailsWith<SourceRuleConflictException> {
                repo.record(rule2)
            }
            assertNull(repo.getById(SourceRuleId("rule-2")))
        } finally {
            cleanup()
        }
    }

    @Test
    fun testTombstoneRemovalAndIdempotency() = runTest {
        val repo = createRepository()
        try {
            val rule = sampleRule(id = "rule-del")
            repo.record(rule)

            // First removal
            val res1 = repo.remove(rule.id, removedAt = 5000L)
            assertEquals(SourceRuleRemovalResult.REMOVED, res1)

            val removed = repo.getById(rule.id)
            assertNotNull(removed)
            assertEquals(5000L, removed.removedAt)
            assertEquals(5000L, removed.updatedAt)
            assertFalse(removed.isActive)

            // Second removal is idempotent
            val res2 = repo.remove(rule.id, removedAt = 6000L)
            assertEquals(SourceRuleRemovalResult.ALREADY_REMOVED, res2)

            // Non-existent rule returns NOT_FOUND
            val resNotFound = repo.remove(SourceRuleId("does-not-exist"), 5000L)
            assertEquals(SourceRuleRemovalResult.NOT_FOUND, resNotFound)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testQueriesByTargetHabitAndContainer() = runTest {
        val repo = createRepository()
        try {
            val r1 = sampleRule(id = "r1", containerId = "c1", habitUuid = "h1", createdAt = 1000L)
            val r2 = sampleRule(id = "r2", containerId = "c1", habitUuid = "h2", createdAt = 2000L)
            val r3 = sampleRule(id = "r3", containerId = "c2", habitUuid = "h1", createdAt = 3000L)
            repo.record(r1)
            repo.record(r2)
            repo.record(r3)

            // Habit h1 has r1 and r3
            assertEquals(listOf(r1, r3), repo.getByTargetHabit(HabitRef("h1")))
            // Container c1 has r1 and r2
            assertEquals(listOf(r1, r2), repo.getByContainer(ContainerId("c1")))

            // Remove r1
            repo.remove(r1.id, removedAt = 4000L)

            // getByTargetHabit excluding removed
            assertEquals(listOf(r3), repo.getByTargetHabit(HabitRef("h1"), includeRemoved = false))
            assertEquals(2, repo.getByTargetHabit(HabitRef("h1"), includeRemoved = true).size)

            // getByContainer excluding removed
            assertEquals(listOf(r2), repo.getByContainer(ContainerId("c1"), includeRemoved = false))
            assertEquals(2, repo.getByContainer(ContainerId("c1"), includeRemoved = true).size)
        } finally {
            cleanup()
        }
    }

    @Test
    fun testActiveRulesExcludesDisabledAndRemoved() = runTest {
        val repo = createRepository()
        try {
            val active = sampleRule(id = "active", isEnabled = true)
            val disabled = sampleRule(id = "disabled", containerId = "c-d", habitUuid = "h-d", isEnabled = false)
            val toRemove = sampleRule(id = "to-remove", containerId = "c-r", habitUuid = "h-r", isEnabled = true)

            repo.record(active)
            repo.record(disabled)
            repo.record(toRemove)
            repo.remove(toRemove.id, 5000L)

            val activeRules = repo.getActiveRules()
            assertEquals(listOf(active), activeRules)

            val allWithRemoved = repo.getAll(includeRemoved = true)
            assertEquals(3, allWithRemoved.size)
        } finally {
            cleanup()
        }
    }
}

class MemorySourceRuleRepositoryContractTest : SourceRuleRepositoryContractTest() {
    override suspend fun createRepository(): SourceRuleRepository = MemorySourceRuleRepository()
}

class SQLiteSourceRuleRepositoryContractTest : SourceRuleRepositoryContractTest() {
    private var db: Database? = null

    override suspend fun createRepository(): SourceRuleRepository {
        val database = TestDatabaseHelper.createEmptyDatabase()
        db = database
        SourceRuleSchema.createSchema(database)
        return SQLiteSourceRuleRepository(database)
    }

    override suspend fun cleanup() {
        db?.close()
        db = null
    }
}
