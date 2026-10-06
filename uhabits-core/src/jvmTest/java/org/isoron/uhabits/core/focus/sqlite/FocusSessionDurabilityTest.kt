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

package org.isoron.uhabits.core.focus.sqlite

import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.run
import org.isoron.uhabits.core.focus.*
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FocusSessionDurabilityTest {
    private val session = FocusSession(FocusSessionId("durable-id"), 100, 5_000, 3_999,
        source = FocusSessionSource.MANUAL, createdAt = 5_001, updatedAt = 5_001)

    @Test fun factSurvivesActualDatabaseCloseAndReopen() {
        val path = Files.createTempFile("focus-durability-", ".db")
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                FocusSessionSchema.createSchema(first)
                SQLiteFocusSessionRepository(first).record(session)
            } finally { first.close() }
            val reopened = JavaDatabaseOpener().open(path.toString())
            try {
                val repo = SQLiteFocusSessionRepository(reopened)
                assertEquals(session, repo.getById(session.id))
                assertEquals(FocusSessionRecordResult.ALREADY_RECORDED, repo.record(session))
            } finally { reopened.close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun independentConnectionsRecordOnlyOneFactOnConcurrentRetry() {
        val path = Files.createTempFile("focus-concurrent-", ".db")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val setup = JavaDatabaseOpener().open(path.toString())
            try { FocusSessionSchema.createSchema(setup) } finally { setup.close() }
            val start = CountDownLatch(1)
            val futures = (1..2).map {
                executor.submit(Callable {
                    val db = JavaDatabaseOpener().open(path.toString())
                    try {
                        db.run("PRAGMA busy_timeout = 5000")
                        check(start.await(5, TimeUnit.SECONDS))
                        SQLiteFocusSessionRepository(db).record(session)
                    } finally { db.close() }
                })
            }
            start.countDown()
            assertEquals(setOf(FocusSessionRecordResult.INSERTED, FocusSessionRecordResult.ALREADY_RECORDED),
                futures.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            val verify = JavaDatabaseOpener().open(path.toString())
            try { assertEquals(listOf(session), SQLiteFocusSessionRepository(verify).getAll()) }
            finally { verify.close() }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            Files.deleteIfExists(path)
        }
    }
}
