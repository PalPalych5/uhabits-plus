/* SPDX-License-Identifier: GPL-3.0-or-later */

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
