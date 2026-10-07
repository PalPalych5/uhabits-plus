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

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.begin
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.rollback
import org.isoron.platform.io.run
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import kotlin.test.*

class SQLiteFocusSessionRepositoryTest {
    @Test fun finalizedKeysetPagesAreBoundedAndStableForEqualEndTimes() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            val repo = SQLiteFocusSessionRepository(db)
            listOf("c", "a", "b").forEach { repo.record(fact(it)) }
            val first = repo.getFinalizedPage(null, 2)
            assertEquals(listOf("a", "b"), first.map { it.id.value })
            val cursor = FocusSessionCursor(first.last().endedAt, first.last().id.value)
            assertEquals(listOf("c"), repo.getFinalizedPage(cursor, 2).map { it.id.value })
            assertFailsWith<IllegalArgumentException> { repo.getFinalizedPage(null, 0) }
        } finally { db.close() }
    }
    private fun fact(id: String = "focus-1") = FocusSession(
        id = FocusSessionId(id), startedAt = 1_791_101_000_123L, endedAt = 1_791_103_000_789L,
        durationMs = 1_200_456L, taskId = TaskId("task-1"), habit = HabitRef("habit-uuid"),
        containerId = ContainerId("container-1"), source = FocusSessionSource.TIMER,
        deviceId = "device-α", createdAt = 1_791_104_000_111L, updatedAt = 1_791_104_000_111L
    )

    @Test fun roundTripPreservesMillisecondsAndOptionalContext() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            val repo = SQLiteFocusSessionRepository(db)
            val linked = fact()
            val standalone = fact("standalone").copy(taskId = null, habit = null, containerId = null,
                source = FocusSessionSource.MANUAL, deviceId = null, primaryTargetType = null)
            assertEquals(FocusSessionRecordResult.INSERTED, repo.record(linked))
            repo.record(standalone)
            assertEquals(linked, repo.getById(linked.id))
            assertEquals(standalone, repo.getById(standalone.id))
            assertNull(repo.getById(FocusSessionId("missing")))
        } finally { db.close() }
    }

    @Test fun identicalRetryDoesNotDuplicateAndConflictDoesNotOverwrite() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            val repo = SQLiteFocusSessionRepository(db)
            val session = fact()
            repo.record(session)
            assertEquals(FocusSessionRecordResult.ALREADY_RECORDED, repo.record(session))
            assertFailsWith<FocusSessionConflictException> { repo.record(session.copy(durationMs = 10)) }
            assertEquals(listOf(session), repo.getAll())
            // A rejected record releases its savepoint: subsequent writes must still work.
            repo.record(fact("next"))
            assertEquals(2, repo.getAll().size)
        } finally { db.close() }
    }

    @Test fun queriesAreStableAndAllowMultipleSessionsPerTask() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            val repo = SQLiteFocusSessionRepository(db)
            val first = fact("a")
            val second = fact("b")
            val earlier = fact("z").copy(startedAt = first.startedAt - 100)
            val unassigned = fact("null").copy(taskId = null, primaryTargetType = FocusPrimaryTargetType.HABIT)
            listOf(second, unassigned, earlier, first).forEach { repo.record(it) }
            assertEquals(listOf(earlier, first, second), repo.getByTask(TaskId("task-1")))
            assertEquals(listOf(earlier, first, second, unassigned), repo.getAll())
            assertTrue(repo.getByTask(TaskId("missing")).isEmpty())
        } finally { db.close() }
    }

    @Test fun optInSchemaIsIdempotentAndDoesNotAlterLegacyVersionOrTaskState() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            assertEquals(0L, db.queryLong("SELECT count(*) FROM sqlite_master WHERE name = 'FocusSessions'"))
            val beforeTables = db.queryLong("SELECT count(*) FROM sqlite_master WHERE type = 'table'")
            val habitCount = db.queryLong("SELECT count(*) FROM Habits")
            val entryCount = db.queryLong("SELECT count(*) FROM Repetitions")
            FocusSessionSchema.createSchema(db)
            FocusSessionSchema.createSchema(db)
            assertEquals(beforeTables + 1, db.queryLong("SELECT count(*) FROM sqlite_master WHERE type = 'table'"))
            assertEquals(LEGACY_DATABASE_VERSION, db.getVersion())
            UserTaskSchema.createSchema(db)
            val taskRepo = SQLiteUserTaskRepository(db)
            val task = UserTask(TaskId("task-1"), "Still pending", createdAt = 100, updatedAt = 100)
            taskRepo.save(task)
            val repo = SQLiteFocusSessionRepository(db)
            repo.record(fact())
            assertEquals(task, taskRepo.getById(task.id))
            assertEquals(habitCount, db.queryLong("SELECT count(*) FROM Habits"))
            assertEquals(entryCount, db.queryLong("SELECT count(*) FROM Repetitions"))
            taskRepo.delete(task.id)
            assertEquals(fact(), repo.getById(fact().id))
        } finally { db.close() }
    }

    @Test fun callerTransactionRollbackRemovesRecordedFact() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            val repo = SQLiteFocusSessionRepository(db)
            db.begin()
            repo.record(fact())
            assertEquals(fact(), repo.getById(fact().id))
            db.rollback()
            assertTrue(repo.getAll().isEmpty())
        } finally { db.close() }
    }

    @Test fun databaseRejectsInvalidRawWorkFacts() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            FocusSessionSchema.createSchema(db)
            fun insert(id: String, start: Long = 100, end: Long = 200, duration: Long = 50,
                       revision: Int = 1, source: String = "MANUAL", updated: Long = 300) {
                db.run("INSERT INTO FocusSessions (id, started_at, ended_at, duration_ms, source, created_at, updated_at, revision) VALUES (?, ?, ?, ?, ?, 300, ?, ?)") {
                    bindText(1, id); bindLong(2, start); bindLong(3, end); bindLong(4, duration)
                    bindText(5, source); bindLong(6, updated); bindInt(7, revision)
                }
            }
            assertFails { insert("zero", duration = 0) }
            assertFails { insert("too-long", duration = 101) }
            assertFails { insert("negative-start", start = -1) }
            assertFails { insert("backwards", end = 99) }
            assertFails { insert(" ") }
            assertFails { insert("future-revision", revision = 2) }
            assertFails { insert("unknown-source", source = "OTHER") }
            assertFails { insert("old-update", updated = 299) }
            assertEquals(0L, db.queryLong("SELECT count(*) FROM FocusSessions"))
        } finally { db.close() }
    }
}
