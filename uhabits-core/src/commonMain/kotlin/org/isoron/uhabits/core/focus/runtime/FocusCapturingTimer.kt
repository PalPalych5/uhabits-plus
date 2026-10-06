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

package org.isoron.uhabits.core.focus.runtime

import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.timer.PomodoroCompletion
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionEngine
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.isoron.uhabits.core.userTasks.TaskId

/**
 * Decorates the existing timer without changing legacy behavior when store is null.
 * Durable facts are work only: pauses and breaks never contribute. A backwards wall
 * clock cancels the unsafe active segment instead of fabricating elapsed work.
 */
class FocusCapturingTimer(
    private val engine: TimerSessionEngine,
    private val store: FocusCaptureStore?,
    private val clock: () -> Long,
    private val idProvider: () -> String,
    private val deviceId: String? = null,
    private val targetProvider: (Long) -> FocusCaptureTarget? = { null },
) {
    private var target: FocusCaptureTarget? = null
    private var active: FocusCaptureSegment? = null
    private var observedAt: Long = clock()
    val currentTarget: FocusCaptureTarget? get() = target

    init {
        store?.load()?.let { journal ->
            restore(journal.timer, journal.autoSwitch)
            target = journal.target
            active = journal.active
            observedAt = journal.observedAt
            if (clock() < observedAt) mutate { cancelUnsafeSegment() }
        }
    }

    var isAutoSwitch: Boolean
        get() = engine.isAutoSwitch
        set(value) {
            if (engine.isAutoSwitch != value) mutate { engine.isAutoSwitch = value }
        }

    fun snapshot(): TimerSessionSnapshot = engine.snapshot()

    fun configurePomodoro(id: Long, name: String, focusDurationMillis: Long, breakDurationMillis: Long): Boolean =
        mutate { engine.configurePomodoro(id, name, focusDurationMillis, breakDurationMillis) }

    fun switchMode(id: Long, name: String, mode: TimerMode): Boolean =
        mutate { engine.switchMode(id, name, mode) }

    fun start(id: Long, name: String): Boolean {
        if (store == null) return engine.start(id, name)
        val before = engine.snapshot()
        val freshFocus = before.phase == PomodoroPhase.FOCUS && !before.hasActiveSession && active == null
        val requested = if (freshFocus) targetProvider(id) ?: target else target ?: targetProvider(id)
        return startInternal(id, name, requested)
    }

    fun start(id: Long, name: String, target: FocusCaptureTarget): Boolean = startInternal(id, name, target)

    private fun startInternal(id: Long, name: String, requested: FocusCaptureTarget?): Boolean = mutate {
        val before = engine.snapshot()
        if (target != null && requested != null &&
            (target!!.kind != requested.kind || target!!.id != requested.id) &&
            (before.hasActiveSession || active != null)) return@mutate false
        val resumedLegacy = target == null && active == null && before.hasActiveSession
        val accepted = engine.start(id, name)
        if (accepted && store != null && !resumedLegacy) {
            if (target == null || (before.phase == PomodoroPhase.FOCUS && !before.hasActiveSession && active == null)) {
                target = requested
            }
            val after = engine.snapshot()
            if (target != null && active == null && after.phase == PomodoroPhase.FOCUS &&
                (after.mode == TimerMode.STOPWATCH || after.elapsedMillis < after.focusDurationMillis)) {
                active = FocusCaptureSegment(FocusSessionId(idProvider()), after.startedAtMillis, 0, deviceId)
            }
        }
        accepted
    }

    fun pause(id: Long): Boolean = mutate { facts ->
        val before = engine.snapshot()
        if (before.habitId == id && before.isRunning && before.mode == TimerMode.POMODORO &&
            before.phase == PomodoroPhase.FOCUS && before.elapsedMillis >= before.focusDurationMillis) {
            finalizeFocus(before, facts, includeOvertime = false)
        }
        engine.pause(id)
    }

    fun finish(id: Long): Long = mutate { facts ->
        val before = engine.snapshot()
        if (before.habitId == id) finalizeFocus(before, facts, includeOvertime = recordsOvertime())
        val result = engine.finish(id)
        if (before.habitId == id) { active = null; target = null }
        result
    }

    fun reset(id: Long): Boolean = mutate {
        val result = engine.reset(id)
        if (result) { active = null; target = null }
        result
    }

    fun transitionToBreakManually() = transitionToNextPhaseManually()

    fun transitionToNextPhaseManually() = mutate { facts ->
        finalizeFocus(engine.snapshot(), facts, includeOvertime = recordsOvertime())
        engine.transitionToNextPhaseManually()
        active = null
    }

    fun tick(): PomodoroCompletion? {
        if (store == null) return engine.tick()
        val now = clock()
        val current = engine.snapshot()
        val duration = if (current.phase == PomodoroPhase.FOCUS) current.focusDurationMillis else current.breakDurationMillis
        if (now >= observedAt && (!current.isRunning || current.mode != TimerMode.POMODORO ||
                current.elapsedMillis < duration || (!engine.isAutoSwitch && current.completionTriggered))) {
            // Wall time progresses from the journal's original startedAt. Heartbeats do not
            // change engine state and need no disk write; remember time for this process only.
            observedAt = now
            return null
        }
        return mutate { facts ->
            val before = engine.snapshot()
            val result = engine.tick()
            if (result == PomodoroCompletion.FOCUS) {
                finalizeFocus(before, facts, includeOvertime = (active?.elapsedOffsetMillis ?: 0) > 0)
                if (engine.isAutoSwitch) active = null
            }
            result
        }
    }

    private fun recordsOvertime(): Boolean = !engine.isAutoSwitch || (active?.elapsedOffsetMillis ?: 0) > 0

    private fun finalizeFocus(before: TimerSessionSnapshot, facts: MutableList<FocusSession>, includeOvertime: Boolean) {
        if (store == null || before.phase != PomodoroPhase.FOCUS || active == null || target == null) return
        val elapsed = before.elapsedMillis
        if (before.mode == TimerMode.POMODORO && active!!.elapsedOffsetMillis == 0L && elapsed >= before.focusDurationMillis) {
            val baseEnd = if (before.isRunning && before.accumulatedMillis < before.focusDurationMillis) {
                before.startedAtMillis + before.focusDurationMillis - before.accumulatedMillis
            } else clock() - (elapsed - before.focusDurationMillis)
            appendFact(before.focusDurationMillis, baseEnd, facts)
            active = if (!engine.isAutoSwitch) FocusCaptureSegment(
                FocusSessionId(idProvider()), baseEnd, before.focusDurationMillis, active!!.deviceId
            ) else null
        }
        val segment = active ?: return
        val remaining = elapsed - segment.elapsedOffsetMillis
        if ((before.mode == TimerMode.STOPWATCH || includeOvertime || elapsed < before.focusDurationMillis) && remaining > 0) {
            appendFact(remaining, clock(), facts)
            active = null
        }
    }

    private fun appendFact(duration: Long, end: Long, facts: MutableList<FocusSession>) {
        val segment = active ?: return
        val context = target ?: return
        if (duration <= 0 || end <= segment.startedAt || duration > end - segment.startedAt) return
        facts.add(FocusSession(
            id = segment.id, startedAt = segment.startedAt, endedAt = end, durationMs = duration,
            taskId = if (context.kind == FocusPrimaryTargetType.TASK) TaskId(context.id) else null,
            habit = if (context.kind == FocusPrimaryTargetType.HABIT) HabitRef(context.id) else null,
            containerId = if (context.kind == FocusPrimaryTargetType.CONTAINER) ContainerId(context.id) else context.containerId,
            source = FocusSessionSource.TIMER, deviceId = segment.deviceId,
            createdAt = clock(), updatedAt = clock(), primaryTargetType = context.kind,
            organizationContext = context.organizationContext,
        ))
    }

    private fun cancelUnsafeSegment() {
        engine.snapshot().habitId?.let { engine.reset(it) }
        target = null
        active = null
    }

    private fun <T> mutate(action: (MutableList<FocusSession>) -> T): T {
        if (store == null) return action(mutableListOf())
        val before = FocusCaptureJournal(engine.snapshot(), engine.isAutoSwitch, target, active, observedAt)
        try {
            val now = clock()
            require(now >= 0) { "Clock must be a nonnegative epoch" }
            if (now < observedAt) cancelUnsafeSegment()
            val facts = mutableListOf<FocusSession>()
            val result = action(facts)
            observedAt = now
            store.commit(FocusCaptureJournal(engine.snapshot(), engine.isAutoSwitch, target, active, now), facts)
            return result
        } catch (error: Throwable) {
            restore(before.timer, before.autoSwitch)
            target = before.target
            active = before.active
            observedAt = before.observedAt
            throw error
        }
    }

    private fun restore(snapshot: TimerSessionSnapshot, autoSwitch: Boolean) {
        engine.isAutoSwitch = autoSwitch
        engine.restore(snapshot.habitId, snapshot.habitName, snapshot.mode, snapshot.phase,
            snapshot.isRunning, snapshot.accumulatedMillis, snapshot.startedAtMillis,
            snapshot.focusDurationMillis, snapshot.breakDurationMillis, snapshot.completionTriggered)
    }
}
