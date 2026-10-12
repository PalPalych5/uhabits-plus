package org.isoron.uhabits.core.production.migration

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.queryLong
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.migration.SemanticMigrationFixtures
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.rules.*
import org.isoron.uhabits.core.rules.memory.MemorySourceRuleRepository
import kotlin.test.*

/** Disposable v29 fixture only; no device, production file, preferences or network. */
class ProductionEffectiveHistoryRegressionTest : BaseUnitTest() {
    @Test fun migratedManualHistoryRetainsZeroUnknownSkipAndExactValuesWhileDirectCreditsStaySeparate() = runTest {
        val db = SemanticMigrationFixtures.createAndSeedV29Database()
        try {
            val date = getToday()
            val legacyFactory = SQLModelFactory(db)
            val habit = legacyFactory.buildHabit().apply {
                name = "Медитация QA"; type = HabitType.NUMERICAL; unit = "мин"; targetValue = 10.0
            }
            legacyFactory.buildHabitList().add(habit)
            habit.originalEntries.add(Entry(date.minus(4), 0, "explicit zero"))
            habit.originalEntries.add(Entry(date.minus(2), Entry.SKIP, "skip"))
            habit.originalEntries.add(Entry(date.minus(1), 5300, "manual 5.3 min"))
            val legacyEntries = (4 downTo 0).map { habit.originalEntries.get(date.minus(it)) }
            val inventory = RawLegacyInventoryReader.read(db)
            val result = ProductionNextSchemaMigrator.migrate(db, 1700000000000L,
                sourceFileSha256 = "aa".repeat(32), sourceSchemaSha256 = "bb".repeat(32))
            val success = assertIs<ProductionNextMigrationResult.Success>(result)
            assertIs<ProductionValidationResult.Valid>(ProductionNextMigrationValidator.validate(db, inventory, success.plan, success.metadata))
            val migrated = SQLModelFactory(db).buildHabitList().getByUUID(habit.uuid!!)!!
            assertEquals(legacyEntries, (4 downTo 0).map { migrated.originalEntries.get(date.minus(it)) })
            val repetitionsBefore = db.queryLong("SELECT count(*) FROM Repetitions")
            val queueBefore = db.queryLong("SELECT count(*) FROM SyncQueue")
            val opsBefore = db.queryLong("SELECT count(*) FROM EntryOps")
            val facts = SQLiteFocusSessionRepository(db)
            val ledger = SQLiteContributionRepository(db)
            val projection = EffectiveHabitValueService(ledger)
            assertEquals(0L, projection.valueForDay(migrated, date.minus(4)).totalValue)
            assertTrue(projection.valueForDay(migrated, date.minus(4)).hasCompleteCoverage)
            assertNull(projection.valueForDay(migrated, date.minus(3)).totalValue)
            assertFalse(projection.valueForDay(migrated, date.minus(3)).hasCompleteCoverage)
            assertTrue(projection.valueForDay(migrated, date.minus(2)).isSkipped)
            assertEquals(5300L, projection.valueForDay(migrated, date.minus(1)).totalValue)
            fun attribution() = SourceRuleAttributionService(MemorySourceRuleRepository(), SQLiteContributionRepository(db)) {
                TargetHabitInfo(true, "мин")
            }
            for ((index, duration) in listOf(3720L, 900000L, 1500000L).withIndex()) {
                val end = startOfLocalDay(date) + 3600000L + index * 2000000L
                val session = FocusSession(FocusSessionId("direct-hotfix-$index"), end - duration, end, duration,
                    habit = HabitRef(migrated.uuid!!), source = FocusSessionSource.TIMER,
                    createdAt = end - duration, updatedAt = end)
                facts.record(session)
                assertTrue(assertIs<AttributionOutcome.Attributed>(attribution().attributeSession(session).single()).wasInserted)
                // A fresh repository/service simulates a retry after component reconstruction.
                facts.record(session)
                assertFalse(assertIs<AttributionOutcome.Attributed>(attribution().attributeSession(session).single()).wasInserted)
                assertEquals(index + 1, facts.getAll().size)
                assertEquals(index + 1, ledger.getAll().size)
                assertEquals(repetitionsBefore, db.queryLong("SELECT count(*) FROM Repetitions"))
            }
            val observed = projection.valueForDay(migrated, date)
            assertEquals(40062L, observed.totalValue)
            assertEquals(40062L, observed.contributionValue)
            assertFalse(observed.hasCompleteCoverage)
            assertEquals(Entry.UNKNOWN, observed.manualEntry.value)
            assertEquals(queueBefore, db.queryLong("SELECT count(*) FROM SyncQueue"))
            assertEquals(opsBefore, db.queryLong("SELECT count(*) FROM EntryOps"))
            assertEquals(legacyEntries, (4 downTo 0).map { migrated.originalEntries.get(date.minus(it)) })
        } finally { db.close() }
    }
}
