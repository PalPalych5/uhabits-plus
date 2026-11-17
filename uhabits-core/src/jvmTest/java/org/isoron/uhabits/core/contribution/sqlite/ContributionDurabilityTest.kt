/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.contribution.sqlite

import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ContributionDurabilityTest {

    private val contribution = Contribution(
        id = ContributionId("durable-contrib-1"),
        targetHabit = HabitRef("habit-uuid-alpha"),
        sourceType = ContributionSourceType.FOCUS_SESSION,
        sourceId = "session-uuid-omega",
        contributedValue = 42000L,
        effectiveTimestamp = 1000L,
        createdAt = 1000L,
        updatedAt = 1000L,
        removedAt = null,
    )

    @Test
    fun contributionSurvivesActualDatabaseCloseAndReopen() {
        val path = Files.createTempFile("contribution-durability-", ".db")
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                ContributionSchema.createSchema(first)
                val repo = SQLiteContributionRepository(first)
                assertEquals(ContributionRecordResult.INSERTED, repo.record(contribution))
            } finally {
                first.close()
            }

            val reopened = JavaDatabaseOpener().open(path.toString())
            try {
                val repo = SQLiteContributionRepository(reopened)
                assertEquals(contribution, repo.getById(contribution.id))
                assertEquals(ContributionRecordResult.ALREADY_RECORDED, repo.record(contribution))
            } finally {
                reopened.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun tombstoneSurvivesCloseAndReopen() {
        val path = Files.createTempFile("contribution-tombstone-", ".db")
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                ContributionSchema.createSchema(first)
                val repo = SQLiteContributionRepository(first)
                repo.record(contribution)
                val removal = repo.remove(
                    sourceType = ContributionSourceType.FOCUS_SESSION,
                    sourceId = "session-uuid-omega",
                    targetHabit = HabitRef("habit-uuid-alpha"),
                    removedAt = 5000L,
                )
                assertEquals(ContributionRemovalResult.REMOVED, removal)
            } finally {
                first.close()
            }

            val reopened = JavaDatabaseOpener().open(path.toString())
            try {
                val repo = SQLiteContributionRepository(reopened)
                val retrieved = repo.getById(contribution.id)
                assertNotNull(retrieved)
                assertFalse(retrieved.isActive)
                assertEquals(5000L, retrieved.removedAt)
                assertEquals(0L, repo.getActiveTotalForHabit(HabitRef("habit-uuid-alpha")))
            } finally {
                reopened.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun independentConnectionsRecordOnlyOneContributionOnConcurrentRetry() {
        val path = Files.createTempFile("contribution-concurrent-", ".db")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val setup = JavaDatabaseOpener().open(path.toString())
            try {
                ContributionSchema.createSchema(setup)
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
                        SQLiteContributionRepository(db).record(contribution)
                    } finally {
                        db.close()
                    }
                })
            }
            start.countDown()
            assertEquals(
                setOf(ContributionRecordResult.INSERTED, ContributionRecordResult.ALREADY_RECORDED),
                futures.map { it.get(10, TimeUnit.SECONDS) }.toSet(),
            )

            val verify = JavaDatabaseOpener().open(path.toString())
            try {
                assertEquals(listOf(contribution), SQLiteContributionRepository(verify).getAll())
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
