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

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.runtime.*
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionSnapshot

/** The singleton journal is runtime state and is deliberately excluded from full backup. */
class SQLiteFocusCaptureStore(private val db: Database) : FocusCaptureStore {
    private val facts = SQLiteFocusSessionRepository(db)

    init {
        FocusSessionSchema.createSchema(db)
        db.run("""
            CREATE TABLE IF NOT EXISTS FocusCaptureJournal (
                slot INTEGER PRIMARY KEY CHECK(slot = 1),
                timer_id INTEGER, timer_name TEXT NOT NULL, mode TEXT NOT NULL, phase TEXT NOT NULL,
                running INTEGER NOT NULL, elapsed INTEGER NOT NULL, display INTEGER NOT NULL,
                focus_duration INTEGER NOT NULL, break_duration INTEGER NOT NULL,
                started_at INTEGER NOT NULL, accumulated INTEGER NOT NULL,
                overtime INTEGER NOT NULL, completion INTEGER NOT NULL, auto_switch INTEGER NOT NULL,
                observed_at INTEGER NOT NULL, target_kind TEXT, target_id TEXT,
                target_container_id TEXT, organization_context TEXT,
                active_id TEXT, active_started_at INTEGER, active_elapsed_offset INTEGER, active_device_id TEXT
            )
        """.trimIndent())
    }

    override fun load(): FocusCaptureJournal? {
        val stmt = db.prepareStatement("SELECT $COLUMNS FROM FocusCaptureJournal WHERE slot = 1")
        return try {
            if (stmt.step() != StepResult.ROW) null else {
                val targetKind = stmt.getTextOrNull(15)
                val activeId = stmt.getTextOrNull(19)
                FocusCaptureJournal(
                    timer = TimerSessionSnapshot(
                        habitId = stmt.getLongOrNull(0), habitName = stmt.getText(1),
                        mode = TimerMode.valueOf(stmt.getText(2)), phase = PomodoroPhase.valueOf(stmt.getText(3)),
                        isRunning = stmt.getInt(4) != 0, elapsedMillis = stmt.getLong(5), displayMillis = stmt.getLong(6),
                        focusDurationMillis = stmt.getLong(7), breakDurationMillis = stmt.getLong(8),
                        startedAtMillis = stmt.getLong(9), accumulatedMillis = stmt.getLong(10),
                        isOvertime = stmt.getInt(11) != 0, completionTriggered = stmt.getInt(12) != 0,
                    ),
                    autoSwitch = stmt.getInt(13) != 0, observedAt = stmt.getLong(14),
                    target = targetKind?.let { FocusCaptureTarget(
                        FocusPrimaryTargetType.valueOf(it), stmt.getText(16),
                        stmt.getTextOrNull(17)?.let(::ContainerId), stmt.getTextOrNull(18)
                    ) },
                    active = activeId?.let { FocusCaptureSegment(
                        FocusSessionId(it), stmt.getLong(20), stmt.getLong(21), stmt.getTextOrNull(22)
                    ) },
                )
            }
        } finally { stmt.finalize() }
    }

    override fun commit(journal: FocusCaptureJournal, facts: List<FocusSession>) {
        db.run("SAVEPOINT capture_focus_commit")
        try {
            facts.forEach { this.facts.record(it) }
            val stmt = db.prepareStatement("INSERT OR REPLACE INTO FocusCaptureJournal (slot, $COLUMNS) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            try {
                val timer = journal.timer
                if (timer.habitId == null) stmt.bindNull(1) else stmt.bindLong(1, timer.habitId)
                stmt.bindText(2, timer.habitName)
                stmt.bindText(3, timer.mode.name); stmt.bindText(4, timer.phase.name)
                stmt.bindInt(5, if (timer.isRunning) 1 else 0)
                stmt.bindLong(6, timer.elapsedMillis); stmt.bindLong(7, timer.displayMillis)
                stmt.bindLong(8, timer.focusDurationMillis); stmt.bindLong(9, timer.breakDurationMillis)
                stmt.bindLong(10, timer.startedAtMillis); stmt.bindLong(11, timer.accumulatedMillis)
                stmt.bindInt(12, if (timer.isOvertime) 1 else 0)
                stmt.bindInt(13, if (timer.completionTriggered) 1 else 0)
                stmt.bindInt(14, if (journal.autoSwitch) 1 else 0)
                stmt.bindLong(15, journal.observedAt)
                stmt.textOrNull(16, journal.target?.kind?.name); stmt.textOrNull(17, journal.target?.id)
                stmt.textOrNull(18, journal.target?.containerId?.value); stmt.textOrNull(19, journal.target?.organizationContext)
                stmt.textOrNull(20, journal.active?.id?.value)
                stmt.longOrNull(21, journal.active?.startedAt); stmt.longOrNull(22, journal.active?.elapsedOffsetMillis)
                stmt.textOrNull(23, journal.active?.deviceId)
                stmt.step()
            } finally { stmt.finalize() }
            db.run("RELEASE SAVEPOINT capture_focus_commit")
        } catch (error: Throwable) {
            try { db.run("ROLLBACK TO SAVEPOINT capture_focus_commit") }
            finally { db.run("RELEASE SAVEPOINT capture_focus_commit") }
            throw error
        }
    }

    private fun PreparedStatement.textOrNull(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }
    private fun PreparedStatement.longOrNull(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    companion object {
        private const val COLUMNS = "timer_id, timer_name, mode, phase, running, elapsed, display, focus_duration, break_duration, started_at, accumulated, overtime, completion, auto_switch, observed_at, target_kind, target_id, target_container_id, organization_context, active_id, active_started_at, active_elapsed_offset, active_device_id"
    }
}
