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

package org.isoron.uhabits.core.rules.sqlite

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.ContributionRemovalResult
import org.isoron.uhabits.core.contribution.ContributionService
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.rules.*
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SourceRuleDurabilityTest {

    @Test fun concurrentReconciliationWithDifferentObservationTimesCreditsExactlyOnce() {
        val path = Files.createTempFile("focus-credit-concurrent-", ".db")
        val executor = Executors.newFixedThreadPool(2)
        val session = FocusSession(FocusSessionId("concurrent-session"), 1000L, 61000L, 60000L,
            containerId = ContainerId("container-uuid-alpha"), source = FocusSessionSource.TIMER,
            createdAt = 1000L, updatedAt = 61000L)
        try {
            val setup = JavaDatabaseOpener().open(path.toString())
            try {
                SourceRuleSchema.createSchema(setup); ContributionSchema.createSchema(setup)
                SQLiteSourceRuleRepository(setup).record(sampleRule)
            } finally { setup.close() }
            val start = CountDownLatch(1)
            val futures = (1..2).map { worker -> executor.submit(Callable {
                val db = JavaDatabaseOpener().open(path.toString())
                try {
                    db.run("PRAGMA busy_timeout = 5000")
                    check(start.await(5, TimeUnit.SECONDS))
                    SourceRuleAttributionService(SQLiteSourceRuleRepository(db), SQLiteContributionRepository(db))
                        .attributeSession(session, 100_000L + worker).filterIsInstance<AttributionOutcome.Attributed>().single()
                } finally { db.close() }
            }) }
            start.countDown()
            assertEquals(1, futures.map { it.get(10, TimeUnit.SECONDS) }.count { it.wasInserted })
            val verify = JavaDatabaseOpener().open(path.toString())
            try {
                assertEquals(1, SQLiteContributionRepository(verify).getAll().size)
                assertEquals(1000L, SQLiteContributionRepository(verify).getAll().single().contributedValue)
            } finally { verify.close() }
        } finally {
            executor.shutdownNow(); check(executor.awaitTermination(10, TimeUnit.SECONDS))
            Files.deleteIfExists(path)
        }
    }

    @Test fun pr14ScopeIndexUpgradePreservesHistoricalRowsAndAllowsReplacement() {
        val path = Files.createTempFile("source-rule-upgrade-", ".db")
        try {
            val db = JavaDatabaseOpener().open(path.toString())
            try {
                db.run("""CREATE TABLE SourceRules (
                    id TEXT PRIMARY KEY NOT NULL, source_kind TEXT NOT NULL, source_container_id TEXT NOT NULL,
                    include_descendants INTEGER NOT NULL, target_habit_uuid TEXT NOT NULL, value_mapping TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL, effective_from INTEGER NOT NULL, created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL, removed_at INTEGER,
                    UNIQUE (source_kind, source_container_id, target_habit_uuid))""")
                val repo = SQLiteSourceRuleRepository(db)
                repo.record(sampleRule)
                SourceRuleSchema.createSchema(db)
                assertEquals(sampleRule, repo.getById(sampleRule.id))
                val replacement = sampleRule.copy(id = SourceRuleId("new-interval"), effectiveFrom = 5000L, createdAt = 5000L, updatedAt = 5000L)
                repo.replaceForTarget(sampleRule.targetHabit, replacement, 5000L)
                SourceRuleSchema.createSchema(db)
                assertEquals(2, repo.getAll(true).size)
                assertEquals(listOf(replacement), repo.getActiveRules())
                assertEquals(5000L, repo.getById(sampleRule.id)?.removedAt)
            } finally { db.close() }
        } finally { Files.deleteIfExists(path) }
    }

    private val sampleRule = SourceRule(
        id = SourceRuleId("durable-rule-1"),
        sourceKind = SourceRuleSourceKind.FOCUS_SESSION,
        sourceContainerId = ContainerId("container-uuid-alpha"),
        includeDescendants = true,
        targetHabit = HabitRef("habit-uuid-omega"),
        valueMapping = SourceRuleValueMapping.FOCUS_DURATION,
        isEnabled = true,
        effectiveFrom = 1000L,
        createdAt = 1000L,
        updatedAt = 1000L,
        removedAt = null,
    )

    @Test
    fun historicalAttributionsSurvivePersistedDisableAndRemovalAcrossReopen() = runTest {
        val path = Files.createTempFile("sourcerule-history-", ".db")
        val siblingRule = sampleRule.copy(
            id = SourceRuleId("durable-rule-sibling"),
            targetHabit = HabitRef("habit-uuid-sibling"),
        )
        val oldSession = FocusSession(
            id = FocusSessionId("historical-session"),
            startedAt = 1000L,
            endedAt = 121000L,
            durationMs = 120000L,
            containerId = sampleRule.sourceContainerId,
            source = FocusSessionSource.TIMER,
            createdAt = 1000L,
            updatedAt = 121000L,
        )
        val newSession = oldSession.copy(
            id = FocusSessionId("session-after-disable"),
            startedAt = 300000L,
            endedAt = 420000L,
            createdAt = 300000L,
            updatedAt = 420000L,
        )
        val contributionId = SourceRuleAttributionService.deterministicContributionId(
            oldSession.id.value, sampleRule.targetHabit.uuid,
        )
        val siblingId = SourceRuleAttributionService.deterministicContributionId(
            oldSession.id.value, siblingRule.targetHabit.uuid,
        )
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                first.setVersion(8)
                first.migrateTo(DATABASE_VERSION) { TestDatabaseHelper.loadMigrationSQL(it) }
                first.run("""INSERT INTO Habits(name, description, question, freq_num, freq_den, color, position,
                    reminder_days, highlight, archived, type, target_value, target_type, unit, uuid)
                    VALUES ('Study', '', '', 1, 1, 0, 0, 0, 0, 0, 1, 120.0, 0, 'min', 'habit-uuid-omega')""")
                first.run("""INSERT INTO Repetitions(habit, timestamp, value, notes, uuid)
                    VALUES (1, 86400000, 45000, 'manual', 'manual-study-entry')""")
                SourceRuleSchema.createSchema(first)
                ContributionSchema.createSchema(first)
                FocusSessionSchema.createSchema(first)
                val rules = SQLiteSourceRuleRepository(first)
                val contributions = SQLiteContributionRepository(first)
                val sessions = SQLiteFocusSessionRepository(first)
                val service = SourceRuleAttributionService(rules, contributions)
                rules.record(sampleRule)
                rules.record(siblingRule)
                sessions.record(oldSession)
                assertEquals(2, service.reconcileAll(sessions.getAll(), 121000L).contributionsCreated)
                val historical = assertNotNull(contributions.getById(contributionId))
                val sibling = assertNotNull(contributions.getById(siblingId))

                // Persist the state transition; immutable record() is intentionally not an update API.
                first.run("UPDATE SourceRules SET is_enabled = 0, updated_at = 200000 WHERE id = 'durable-rule-1'")
                assertEquals(SourceRuleRemovalResult.REMOVED, rules.remove(siblingRule.id, 200000L))
                sessions.record(newSession)
                val report = service.reconcileAll(sessions.getAll(), 420000L)
                assertEquals(0, report.contributionsCreated)
                assertEquals(0, report.contributionsRevoked)
                assertEquals(historical, contributions.getById(contributionId))
                assertEquals(sibling, contributions.getById(siblingId))
                assertEquals(45000L, first.queryLong("SELECT value FROM Repetitions WHERE uuid = 'manual-study-entry'"))
            } finally {
                first.close()
            }

            val reopened = JavaDatabaseOpener().open(path.toString())
            try {
                val rules = SQLiteSourceRuleRepository(reopened)
                val contributions = SQLiteContributionRepository(reopened)
                val sessions = SQLiteFocusSessionRepository(reopened)
                assertFalse(assertNotNull(rules.getById(sampleRule.id)).isEnabled)
                assertEquals(200000L, assertNotNull(rules.getById(siblingRule.id)).removedAt)
                for ((id, rule) in listOf(contributionId to sampleRule, siblingId to siblingRule)) {
                    val stored = assertNotNull(contributions.getById(id))
                    assertTrue(stored.isActive)
                    assertEquals(rule.id, stored.sourceRuleId)
                    assertEquals(2000L, stored.contributedValue)
                    assertNotNull(rules.getById(assertNotNull(stored.sourceRuleId)))
                }
                assertEquals(listOf(oldSession, newSession), sessions.getAll())
                assertEquals(45000L, reopened.queryLong("SELECT value FROM Repetitions WHERE uuid = 'manual-study-entry' AND notes = 'manual' AND habit = 1"))
                assertEquals(1L, reopened.queryLong("SELECT COUNT(*) FROM Repetitions"))
                val report = SourceRuleAttributionService(rules, contributions)
                    .reconcileAll(sessions.getAll(), 500000L)
                assertEquals(0, report.contributionsCreated)
                assertEquals(0, report.contributionsRevoked)
                assertNull(contributions.getById(SourceRuleAttributionService.deterministicContributionId(
                    newSession.id.value, sampleRule.targetHabit.uuid,
                )))
                assertNull(contributions.getById(SourceRuleAttributionService.deterministicContributionId(
                    newSession.id.value, siblingRule.targetHabit.uuid,
                )))
                assertTrue(assertNotNull(contributions.getById(contributionId)).isActive)
                assertTrue(assertNotNull(contributions.getById(siblingId)).isActive)
                val sibling = contributions.getById(siblingId)
                assertEquals(ContributionRemovalResult.REMOVED,
                    ContributionService(contributions).revokeFocusSessionContribution(
                        oldSession.id, sampleRule.targetHabit, 600000L,
                    ))
                SourceRuleAttributionService(rules, contributions).reconcileAll(sessions.getAll(), 700000L)
                assertEquals(600000L, assertNotNull(contributions.getById(contributionId)).removedAt)
                assertEquals(sibling, contributions.getById(siblingId))
                assertEquals(listOf(oldSession, newSession), sessions.getAll())
                assertEquals(45000L, reopened.queryLong("SELECT value FROM Repetitions WHERE uuid = 'manual-study-entry' AND notes = 'manual' AND habit = 1"))
                assertEquals(1L, reopened.queryLong("SELECT COUNT(*) FROM Repetitions"))
            } finally {
                reopened.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun ruleSurvivesActualDatabaseCloseAndReopen() {
        val path = Files.createTempFile("sourcerule-durability-", ".db")
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                SourceRuleSchema.createSchema(first)
                val repo = SQLiteSourceRuleRepository(first)
                assertEquals(SourceRuleRecordResult.INSERTED, repo.record(sampleRule))
            } finally {
                first.close()
            }

            val reopened = JavaDatabaseOpener().open(path.toString())
            try {
                val repo = SQLiteSourceRuleRepository(reopened)
                assertEquals(sampleRule, repo.getById(sampleRule.id))
                assertEquals(SourceRuleRecordResult.ALREADY_RECORDED, repo.record(sampleRule))
            } finally {
                reopened.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun tombstoneSurvivesCloseAndReopen() {
        val path = Files.createTempFile("sourcerule-tombstone-", ".db")
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                SourceRuleSchema.createSchema(first)
                val repo = SQLiteSourceRuleRepository(first)
                repo.record(sampleRule)
                val removal = repo.remove(sampleRule.id, removedAt = 5000L)
                assertEquals(SourceRuleRemovalResult.REMOVED, removal)
            } finally {
                first.close()
            }

            val reopened = JavaDatabaseOpener().open(path.toString())
            try {
                val repo = SQLiteSourceRuleRepository(reopened)
                val retrieved = repo.getById(sampleRule.id)
                assertNotNull(retrieved)
                assertFalse(retrieved.isActive)
                assertEquals(5000L, retrieved.removedAt)
                assertTrue(repo.getActiveRules().isEmpty())
                assertEquals(1, repo.getAll(includeRemoved = true).size)
            } finally {
                reopened.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun independentConnectionsRecordOnlyOneRuleOnConcurrentRetry() {
        val path = Files.createTempFile("sourcerule-concurrent-", ".db")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val setup = JavaDatabaseOpener().open(path.toString())
            try {
                SourceRuleSchema.createSchema(setup)
            } finally {
                setup.close()
            }

            val start = CountDownLatch(1)
            val futures = (1..2).map {
                executor.submit(Callable {
                    val db = JavaDatabaseOpener().open(path.toString())
                    try {
                        db.run("PRAGMA busy_timeout = 5000")
                        check(start.await(5, TimeUnit.SECONDS))
                        SQLiteSourceRuleRepository(db).record(sampleRule)
                    } finally {
                        db.close()
                    }
                })
            }
            start.countDown()
            assertEquals(
                setOf(SourceRuleRecordResult.INSERTED, SourceRuleRecordResult.ALREADY_RECORDED),
                futures.map { it.get(10, TimeUnit.SECONDS) }.toSet(),
            )

            val verify = JavaDatabaseOpener().open(path.toString())
            try {
                assertEquals(listOf(sampleRule), SQLiteSourceRuleRepository(verify).getAll())
            } finally {
                verify.close()
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            Files.deleteIfExists(path)
        }
    }
}
