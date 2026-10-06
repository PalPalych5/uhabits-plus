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

import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.rules.*
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SourceRuleDurabilityTest {

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
