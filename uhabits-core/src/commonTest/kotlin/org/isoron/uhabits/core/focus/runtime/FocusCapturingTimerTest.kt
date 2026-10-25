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

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.memory.MemoryFocusCaptureStore
import org.isoron.uhabits.core.timer.*
import kotlin.test.*

class FocusCapturingTimerTest {
    private var now = 1_000L
    private var nextId = 0
    private val store = MemoryFocusCaptureStore()
    private val target = FocusCaptureTarget(FocusPrimaryTargetType.TASK, "task-1", ContainerId("old-container"), "{\"path\":[\"old-container\"]}")
    private fun timer(captureStore: FocusCaptureStore? = store) = FocusCapturingTimer(
        TimerSessionEngine { now }, captureStore, { now }, { "session-${++nextId}" }, "device-1", { target }
    )

    @Test fun stopwatchExcludesPausedTimeAndPreservesFrozenContext() {
        val timer = timer()
        assertTrue(timer.start(1, "Task"))
        now += 400
        timer.pause(1)
        now += 2_000
        assertTrue(timer.start(1, "Renamed", target.copy(containerId = ContainerId("new-container"))))
        now += 600
        assertEquals(1_000L, timer.finish(1))
        val fact = store.repository.getAll().single()
        assertEquals(1_000L, fact.durationMs)
        assertEquals(3_000L, fact.endedAt - fact.startedAt)
        assertEquals(target.containerId, fact.containerId)
        assertEquals(target.organizationContext, fact.organizationContext)
    }

    @Test fun eachPrimaryTargetIsRecordedWithoutImplicitCredit() {
        val timer = timer()
        FocusPrimaryTargetType.entries.forEachIndexed { index, kind ->
            val primary = FocusCaptureTarget(kind, "id-$index")
            timer.start(-2, "Target", primary)
            now += 100
            timer.finish(-2)
            val fact = store.repository.getAll().last()
            assertEquals(kind, fact.primaryTargetType)
            assertEquals(if (kind == FocusPrimaryTargetType.TASK) primary.id else null, fact.taskId?.value)
            assertEquals(if (kind == FocusPrimaryTargetType.HABIT) primary.id else null, fact.habit?.uuid)
            assertEquals(if (kind == FocusPrimaryTargetType.CONTAINER) primary.id else null, fact.containerId?.value)
        }
    }

    @Test fun activePrimaryTargetCannotBeReplaced() {
        val timer = timer()
        timer.start(-2, "Task", target)
        assertFalse(timer.start(-2, "Other", FocusCaptureTarget(FocusPrimaryTargetType.CONTAINER, "other")))
        assertEquals(target, timer.currentTarget)
        now += 100
        timer.finish(-2)
        assertNull(timer.currentTarget)
    }

    @Test fun cancellationAndZeroLengthWorkCreateNoFacts() {
        val timer = timer()
        timer.start(1, "Task")
        now += 100
        timer.reset(1)
        timer.start(1, "Task")
        timer.finish(1)
        assertTrue(store.repository.getAll().isEmpty())
    }

    @Test fun automaticCompletionRecordsFocusOnlyAndBreakCreatesNoFact() {
        val timer = timer()
        timer.configurePomodoro(1, "Task", 1_000, 400)
        timer.switchMode(1, "Task", TimerMode.POMODORO)
        timer.start(1, "Task")
        now += 1_200
        assertEquals(PomodoroCompletion.FOCUS, timer.tick())
        assertEquals(1_000L, store.repository.getAll().single().durationMs)
        assertNull(timer.tick())
        timer.start(1, "Task")
        now += 500
        assertEquals(PomodoroCompletion.BREAK, timer.tick())
        assertEquals(1, store.repository.getAll().size)
        timer.start(1, "Task")
        now += 200
        timer.finish(1)
        assertEquals(listOf(1_000L, 200L), store.repository.getAll().map { it.durationMs })
    }

    @Test fun nonAutomaticOvertimeIsDistinctFromCompletedBaseAndRetriesDoNotDuplicate() {
        val timer = timer()
        timer.configurePomodoro(1, "Task", 1_000, 400)
        timer.switchMode(1, "Task", TimerMode.POMODORO)
        timer.isAutoSwitch = false
        timer.start(1, "Task")
        now += 1_100
        assertEquals(PomodoroCompletion.FOCUS, timer.tick())
        assertNull(timer.tick())
        now += 300
        assertEquals(1_400L, timer.finish(1))
        val facts = store.repository.getAll()
        assertEquals(listOf(1_000L, 400L), facts.map { it.durationMs })
        assertNotEquals(facts[0].id, facts[1].id)
        assertEquals(facts[0].endedAt, facts[1].startedAt)
        timer.finish(1)
        assertEquals(facts, store.repository.getAll())
    }

    @Test fun finishBeforeTickStillSplitsNonAutomaticBaseAndOvertime() {
        val timer = timer()
        timer.configurePomodoro(1, "Task", 1_000, 400)
        timer.switchMode(1, "Task", TimerMode.POMODORO)
        timer.isAutoSwitch = false
        timer.start(1, "Task")
        now += 1_250
        timer.finish(1)
        assertEquals(listOf(1_000L, 250L), store.repository.getAll().map { it.durationMs })
    }

    @Test fun reconstructionUsesJournalAndStableCompletionId() {
        var timer = timer()
        timer.configurePomodoro(1, "Task", 1_000, 400)
        timer.switchMode(1, "Task", TimerMode.POMODORO)
        timer.start(1, "Task")
        val originalId = store.load()!!.active!!.id
        now += 1_100
        timer = timer()
        assertEquals(target, timer.currentTarget)
        assertEquals(PomodoroCompletion.FOCUS, timer.tick())
        assertEquals(originalId, store.repository.getAll().single().id)
        timer = timer()
        assertEquals(PomodoroPhase.BREAK, timer.snapshot().phase)
        assertNull(timer.tick())
        assertEquals(1, store.repository.getAll().size)
    }

    @Test fun storageFailureRollsBackEngineAndJournalUntilRetry() {
        var fail = false
        val failing = object : FocusCaptureStore {
            override fun load() = store.load()
            override fun commit(journal: FocusCaptureJournal, facts: List<FocusSession>) {
                if (fail) error("disk failure")
                store.commit(journal, facts)
            }
        }
        val timer = timer(failing)
        timer.start(1, "Task")
        val before = store.load()
        now += 500
        fail = true
        assertFailsWith<IllegalStateException> { timer.finish(1) }
        assertTrue(timer.snapshot().isRunning)
        assertEquals(before, store.load())
        assertTrue(store.repository.getAll().isEmpty())
        fail = false
        timer.finish(1)
        assertEquals(500L, store.repository.getAll().single().durationMs)
    }

    @Test fun backwardClockCancelsUnsafeSegmentWithoutInventingWork() {
        val timer = timer()
        timer.start(1, "Task")
        now += 500
        timer.tick()
        now -= 200
        assertEquals(0L, timer.finish(1))
        assertTrue(store.repository.getAll().isEmpty())
        assertFalse(timer.snapshot().isRunning)
        assertNull(timer.currentTarget)
    }

    @Test fun backwardClockOnRecoveryCancelsJournalSegment() {
        val first = timer()
        first.start(1, "Task")
        now += 500
        first.pause(1)
        now -= 100
        val restored = timer()
        assertFalse(restored.snapshot().isRunning)
        assertNull(store.load()!!.active)
        assertTrue(store.repository.getAll().isEmpty())
    }

    @Test fun ordinaryHeartbeatDoesNotWriteAndStillDetectsBackwardClock() {
        var commits = 0
        val counting = object : FocusCaptureStore {
            override fun load() = store.load()
            override fun commit(journal: FocusCaptureJournal, facts: List<FocusSession>) {
                commits++
                store.commit(journal, facts)
            }
        }
        val timer = timer(counting)
        timer.start(1, "Task")
        assertEquals(1, commits)
        repeat(100) { now += 10; assertNull(timer.tick()) }
        assertEquals(1, commits)
        now -= 50
        assertNull(timer.tick())
        assertEquals(2, commits)
        assertFalse(timer.snapshot().isRunning)
        assertTrue(store.repository.getAll().isEmpty())
    }

    @Test fun nullStoreExactlyDelegatesExistingEngineAndDoesNotResolveTargets() {
        val raw = TimerSessionEngine { now }
        val wrapper = FocusCapturingTimer(raw, null, { now }, { error("unused") }, targetProvider = { error("unused") })
        wrapper.start(1, "Legacy")
        now += 500
        assertEquals(raw.snapshot(), wrapper.snapshot())
        assertEquals(500L, wrapper.finish(1))
        assertNull(wrapper.currentTarget)
    }

    @Test fun legacyActiveEngineWithoutJournalCannotBecomeSyntheticFacts() {
        val raw = TimerSessionEngine { now }
        raw.start(1, "Legacy")
        now += 500
        val wrapper = FocusCapturingTimer(raw, store, { now }, { "new-id" }, targetProvider = { target })
        wrapper.start(1, "Legacy")
        now += 100
        wrapper.finish(1)
        assertTrue(store.repository.getAll().isEmpty())
    }

    @Test fun pausedAutomaticCompletedFocusCannotBeRecordedAgainOnResume() {
        val timer = timer()
        timer.configurePomodoro(1, "Task", 1_000, 400)
        timer.switchMode(1, "Task", TimerMode.POMODORO)
        timer.start(1, "Task")
        now += 1_100
        timer.pause(1)
        now += 500
        timer.start(1, "Task")
        timer.tick()
        assertEquals(listOf(1_000L), store.repository.getAll().map { it.durationMs })
    }

    @Test fun completedBreakAllowsNewPrimaryTargetInsteadOfReusingPreviousIdentity() {
        val timer = timer()
        timer.configurePomodoro(-2, "Task", 1_000, 400)
        timer.switchMode(-2, "Task", TimerMode.POMODORO)
        timer.start(-2, "Task", target)
        now += 1_000
        timer.tick()
        timer.start(-2, "Break", target)
        now += 400
        timer.tick()
        val other = FocusCaptureTarget(FocusPrimaryTargetType.CONTAINER, "other")
        assertTrue(timer.start(-2, "Other", other))
        assertEquals(other, timer.currentTarget)
        now += 200
        timer.finish(-2)
        assertNull(store.load()!!.target)
        assertEquals("other", store.repository.getAll().last().containerId?.value)
        assertEquals(FocusPrimaryTargetType.CONTAINER, store.repository.getAll().last().primaryTargetType)
    }

    @Test fun nextFocusCapturesFreshOrganizationWhilePauseResumeKeepsOriginalSnapshot() {
        var provided = target
        val timer = FocusCapturingTimer(TimerSessionEngine { now }, store, { now }, { "session-${++nextId}" },
            targetProvider = { provided })
        timer.configurePomodoro(1, "Task", 1_000, 400)
        timer.switchMode(1, "Task", TimerMode.POMODORO)
        timer.start(1, "Task")
        now += 200
        timer.pause(1)
        provided = target.copy(containerId = ContainerId("new"), organizationContext = "new-path")
        timer.start(1, "Task")
        now += 800
        timer.tick()
        assertEquals(target.organizationContext, store.repository.getAll().first().organizationContext)
        timer.start(1, "Break")
        now += 400
        timer.tick()
        timer.start(1, "Task")
        assertEquals(provided, timer.currentTarget)
        now += 100
        timer.finish(1)
        assertEquals("new-path", store.repository.getAll().last().organizationContext)
    }

    @Test fun enablingAutoSwitchDuringExistingOvertimeDoesNotDiscardActualWork() {
        val timer = timer()
        timer.configurePomodoro(1, "Task", 1_000, 400)
        timer.switchMode(1, "Task", TimerMode.POMODORO)
        timer.isAutoSwitch = false
        timer.start(1, "Task")
        now += 1_000
        timer.tick()
        now += 200
        timer.isAutoSwitch = true
        timer.tick()
        assertEquals(listOf(1_000L, 200L), store.repository.getAll().map { it.durationMs })
    }

    @Test fun testCommitFailureRestoresTimerStateWithStableIdentity() {
        var failCommit = false
        val failingStore = object : FocusCaptureStore {
            override fun load() = store.load()
            override fun commit(journal: FocusCaptureJournal, facts: List<FocusSession>) {
                if (failCommit) throw IllegalStateException("Simulated DB failure")
                store.commit(journal, facts)
            }
        }
        val timer = timer(failingStore)
        assertTrue(timer.start(1, "Task"))
        now += 500
        val snapshotBefore = timer.snapshot()
        val journalBefore = store.load()
        assertNotNull(journalBefore?.active)
        val originalSessionId = journalBefore.active!!.id

        failCommit = true
        assertFailsWith<IllegalStateException> {
            timer.finish(1)
        }

        // Timer engine state must be restored to state before failure
        val restoredSnapshot = timer.snapshot()
        assertEquals(snapshotBefore.isRunning, restoredSnapshot.isRunning)
        assertEquals(snapshotBefore.elapsedMillis, restoredSnapshot.elapsedMillis)
        assertEquals(target, timer.currentTarget)

        // When commit failure clears, next finish succeeds
        failCommit = false
        now += 500
        timer.finish(1)
        assertEquals(1, store.repository.getAll().size)
    }
}
