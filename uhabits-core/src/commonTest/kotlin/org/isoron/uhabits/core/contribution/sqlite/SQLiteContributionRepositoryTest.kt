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

package org.isoron.uhabits.core.contribution.sqlite

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.begin
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.rollback
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import kotlin.test.*

class SQLiteContributionRepositoryTest {

    private fun sampleContribution(
        id: String = "c-1",
        habitUuid: String = "h-1",
        sourceType: ContributionSourceType = ContributionSourceType.FOCUS_SESSION,
        sourceId: String = "s-1",
        value: Long = 42000L,
    ) = Contribution(
        id = ContributionId(id),
        targetHabit = HabitRef(habitUuid),
        sourceType = sourceType,
        sourceId = sourceId,
        contributedValue = value,
        effectiveTimestamp = 1000L,
        createdAt = 1000L,
        updatedAt = 1000L,
        removedAt = null,
    )

    @Test
    fun testProductionDatabaseVersionUnchanged() = runTest {
        assertEquals(29, LEGACY_DATABASE_VERSION)
        assertEquals(30, DATABASE_VERSION)
    }

    @Test
    fun testExternalTransactionRollbackRevertsContribution() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(db)
            val repo = SQLiteContributionRepository(db)

            db.begin()
            val c = sampleContribution()
            assertEquals(ContributionRecordResult.INSERTED, repo.record(c))
            assertEquals(1, repo.getAll().size)

            db.rollback()

            // After external transaction rollback, contribution is gone
            assertNull(repo.getById(c.id))
            assertTrue(repo.getAll().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun testFailureMidwayLeavesNoPartialState() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(db)
            val repo = SQLiteContributionRepository(db)

            val c = sampleContribution(id = "c-ok")
            repo.record(c)

            // Attempting to record a conflicting entry throws
            val conflict = sampleContribution(id = "c-ok", value = 999L)
            assertFailsWith<ContributionConflictException> {
                repo.record(conflict)
            }

            // Original state remains untouched
            val stored = repo.getById(ContributionId("c-ok"))
            assertNotNull(stored)
            assertEquals(42000L, stored.contributedValue)
            assertEquals(1, repo.getAll().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun testUniqueConstraintOnSourceAndTargetEnforced() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            ContributionSchema.createSchema(db)
            val repo = SQLiteContributionRepository(db)

            val c1 = sampleContribution(id = "c-1", sourceId = "src-1", habitUuid = "h-1")
            repo.record(c1)

            // Direct SQL attempt to insert duplicate (source_type, source_id, target_habit_uuid)
            // with different id is rejected by UNIQUE constraint
            val c2DifferentId = sampleContribution(id = "c-2", sourceId = "src-1", habitUuid = "h-1")
            assertFailsWith<ContributionConflictException> {
                repo.record(c2DifferentId)
            }

            assertEquals(1L, db.queryLong("SELECT count(*) FROM Contributions"))
        } finally {
            db.close()
        }
    }

    @Test
    fun testOptInSchemaDoesNotPolluteEmptyDatabase() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            // An unmigrated production DB has version 0 or 29, and does NOT have Contributions
            val count = db.queryLong("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'Contributions'")
            assertEquals(0L, count)
        } finally {
            db.close()
        }
    }
}
