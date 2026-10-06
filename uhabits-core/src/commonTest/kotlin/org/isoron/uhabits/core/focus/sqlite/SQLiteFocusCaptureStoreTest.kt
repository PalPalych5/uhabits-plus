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
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.runtime.*
import org.isoron.uhabits.core.timer.*
import kotlin.test.*

class SQLiteFocusCaptureStoreTest {
    @Test fun oldSchemaMigrationPreservesRowsAndInfersPrimaryTarget() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            // The exact old PR11 schema shape, before PR12 metadata columns.
            db.run("""CREATE TABLE FocusSessions (
                id TEXT PRIMARY KEY NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL, task_id TEXT, habit_uuid TEXT, container_id TEXT,
                source TEXT NOT NULL, device_id TEXT, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, revision INTEGER NOT NULL
            )""".trimIndent())
            db.run("INSERT INTO FocusSessions VALUES ('old', 100, 1000, 500, 'task', NULL, 'container', 'MANUAL', 'device', 1000, 1000, 1)")
            FocusSessionSchema.createSchema(db)
            FocusSessionSchema.createSchema(db)
            val fact = SQLiteFocusSessionRepository(db).getAll().single()
            assertEquals("old", fact.id.value)
            assertEquals(500L, fact.durationMs)
            assertEquals("container", fact.containerId?.value)
            assertEquals(FocusPrimaryTargetType.TASK, fact.primaryTargetType)
            assertNull(fact.organizationContext)
            assertEquals(14L, db.queryLong("SELECT count(*) FROM pragma_table_info('FocusSessions')"))
        } finally { db.close() }
    }

    @Test fun rawJournalRoundTripRestoresPausedStateAndFrozenTarget() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val store = SQLiteFocusCaptureStore(db)
            val journal = FocusCaptureJournal(
                TimerSessionSnapshot(habitId = -2, habitName = "Task", mode = TimerMode.POMODORO,
                    phase = PomodoroPhase.FOCUS, accumulatedMillis = 400, elapsedMillis = 400,
                    focusDurationMillis = 1000, breakDurationMillis = 200, displayMillis = 600),
                false, FocusCaptureTarget(FocusPrimaryTargetType.TASK, "task", organizationContext = "{\"frozen\":true}"),
                FocusCaptureSegment(FocusSessionId("active"), 100, 0, "device-old"), 800,
            )
            store.commit(journal, emptyList())
            assertEquals(journal, store.load())
        } finally { db.close() }
    }

    @Test fun failedJournalWriteRollsBackNewFactAndRestoresEngineForRetry() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            var now = 100L
            val store = SQLiteFocusCaptureStore(db)
            val timer = FocusCapturingTimer(TimerSessionEngine { now }, store, { now }, { "fact" },
                targetProvider = { FocusCaptureTarget(FocusPrimaryTargetType.HABIT, "habit") })
            timer.start(1, "Habit")
            val before = store.load()
            db.run("CREATE TRIGGER fail_capture BEFORE INSERT ON FocusCaptureJournal BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            now = 600
            assertFails { timer.finish(1) }
            assertTrue(timer.snapshot().isRunning)
            assertEquals(before, store.load())
            assertTrue(SQLiteFocusSessionRepository(db).getAll().isEmpty())
            db.run("DROP TRIGGER fail_capture")
            timer.finish(1)
            assertEquals(500L, SQLiteFocusSessionRepository(db).getAll().single().durationMs)
            assertNull(store.load()!!.active)
        } finally { db.close() }
    }

    @Test fun replayOfCommittedJournalAndIdenticalFactsIsIdempotent() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            val store = SQLiteFocusCaptureStore(db)
            val fact = FocusSession(FocusSessionId("fact"), 100, 600, 500,
                source = FocusSessionSource.TIMER, createdAt = 600, updatedAt = 600)
            val journal = FocusCaptureJournal(TimerSessionSnapshot(), true, null, null, 600)
            store.commit(journal, listOf(fact))
            store.commit(journal, listOf(fact))
            assertEquals(listOf(fact), SQLiteFocusSessionRepository(db).getAll())
            assertEquals(journal, store.load())
        } finally { db.close() }
    }
}
