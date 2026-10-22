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

package org.isoron.uhabits.core.production.cutover

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductionCutoverServiceTest {

    @Test
    fun testCutoverFromV29ToV30WithNextAuthority() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            assertEquals(LEGACY_DATABASE_VERSION, db.getVersion())
            val initialHabitsCount = db.queryLong("SELECT count(*) FROM Habits")
            val initialBlocksCount = db.queryLong("SELECT count(*) FROM HabitBlocks")
            val initialRepetitionsCount = db.queryLong("SELECT count(*) FROM Repetitions")

            val cutoverService = ProductionCutoverService()
            val cutoverTimestamp = 1700000000000L
            val result = cutoverService.executeCutover(
                db = db,
                cutoverTimestamp = cutoverTimestamp,
                datasetUuid = "test-cutover-dataset-uuid"
            )

            assertIs<ProductionCutoverResult.Success>(result)
            assertEquals(30, db.getVersion())
            assertEquals(initialHabitsCount, result.habitsCount)
            assertEquals(initialBlocksCount, result.containersCount)
            assertEquals(initialHabitsCount, result.placementsCount)

            // Verify OrganizationState metadata
            val runtimeAuthority = db.queryString("SELECT runtime_authority FROM OrganizationState")
            val authorityEpoch = db.queryLong("SELECT authority_epoch FROM OrganizationState")
            val mode = db.queryString("SELECT mode FROM OrganizationState")
            val datasetUuid = db.queryString("SELECT dataset_uuid FROM OrganizationState")

            assertEquals(ProductionCutoverGate.AUTHORITY_NEXT, runtimeAuthority)
            assertEquals(1L, authorityEpoch)
            assertEquals("PRODUCTION_NEXT", mode)
            assertEquals("test-cutover-dataset-uuid", datasetUuid)

            // Verify legacy data unchanged
            assertEquals(initialHabitsCount, db.queryLong("SELECT count(*) FROM Habits"))
            assertEquals(initialBlocksCount, db.queryLong("SELECT count(*) FROM HabitBlocks"))
            assertEquals(initialRepetitionsCount, db.queryLong("SELECT count(*) FROM Repetitions"))

            // Verify new stores start empty
            assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM FocusSessions"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM FocusCaptureJournal"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM Contributions"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM SourceRules"))

            // Verify containers and habit placements exist
            assertEquals(initialBlocksCount, db.queryLong("SELECT count(*) FROM Containers"))
            assertEquals(initialHabitsCount, db.queryLong("SELECT count(*) FROM HabitPlacements"))
        } finally {
            db.close()
        }
    }

    @Test
    fun testCutoverFailsOnNonV29Database() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            db.setVersion(28)
            val cutoverService = ProductionCutoverService()
            val result = cutoverService.executeCutover(db)

            assertIs<ProductionCutoverResult.Failure>(result)
            assertEquals("PREFLIGHT", result.stage)
            assertTrue(result.reason.contains("Database version must be 29"))
        } finally {
            db.close()
        }
    }
}
