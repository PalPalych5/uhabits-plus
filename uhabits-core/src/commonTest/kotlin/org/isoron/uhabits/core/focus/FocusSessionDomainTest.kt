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

package org.isoron.uhabits.core.focus

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.userTasks.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FocusSessionDomainTest {
    private fun session(id: String = "session-1", start: Long = 1_000) = FocusSession(
        id = FocusSessionId(id),
        startedAt = start,
        endedAt = start + 60_000,
        durationMs = 45_000,
        source = FocusSessionSource.MANUAL,
        createdAt = start + 60_000,
        updatedAt = start + 60_000,
    )

    @Test
    fun pausedWorkCanBeShorterThanElapsedInterval() {
        val fact = session()
        assertEquals(45_000L, fact.durationMs)
        assertEquals(60_000L, fact.endedAt - fact.startedAt)
        assertEquals(fact.endedAt - fact.startedAt, fact.copy(durationMs = 60_000).durationMs)
    }

    @Test
    fun contextIsOptionalAndDoesNotRequireLiveEntities() {
        val repo = MemoryFocusSessionRepository()
        val unassigned = session()
        assertNull(unassigned.taskId)
        assertNull(unassigned.habit)
        assertNull(unassigned.containerId)
        assertNull(unassigned.deviceId)
        assertEquals(FocusSessionRecordResult.INSERTED, repo.record(unassigned))
        val linked = session("linked").copy(
            taskId = TaskId("missing-task"),
            habit = HabitRef("missing-habit-uuid"),
            containerId = ContainerId("missing-container"),
            source = FocusSessionSource.TIMER,
            deviceId = "device-1",
        )
        assertEquals(FocusSessionRecordResult.INSERTED, repo.record(linked))
        assertEquals(linked, repo.getById(linked.id))
    }

    @Test
    fun rejectsBlankSessionIdsAndProvidedReferences() {
        for (blank in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> { FocusSessionId(blank) }
            assertFailsWith<IllegalArgumentException> { session().copy(taskId = TaskId(blank)) }
            assertFailsWith<IllegalArgumentException> { session().copy(habit = HabitRef(blank)) }
            assertFailsWith<IllegalArgumentException> { session().copy(containerId = ContainerId(blank)) }
            assertFailsWith<IllegalArgumentException> { session().copy(deviceId = blank) }
        }
    }

    @Test
    fun rejectsInvalidIntervalsAndActiveDurations() {
        assertFailsWith<IllegalArgumentException> { session().copy(startedAt = -1) }
        assertFailsWith<IllegalArgumentException> { session().copy(endedAt = -1) }
        assertFailsWith<IllegalArgumentException> { session().copy(endedAt = 1_000) }
        assertFailsWith<IllegalArgumentException> { session().copy(endedAt = 999) }
        assertFailsWith<IllegalArgumentException> { session().copy(durationMs = 0) }
        assertFailsWith<IllegalArgumentException> { session().copy(durationMs = -1) }
        assertFailsWith<IllegalArgumentException> { session().copy(durationMs = 60_001) }
    }

    @Test
    fun validatesMetadataEpochsAndInitialRevision() {
        assertFailsWith<IllegalArgumentException> { session().copy(createdAt = -1) }
        assertFailsWith<IllegalArgumentException> { session().copy(updatedAt = -1) }
        assertFailsWith<IllegalArgumentException> { session().copy(updatedAt = 60_999) }
        for (revision in listOf(-1L, 0L, 2L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { session().copy(revision = revision) }
        }
        assertEquals(1L, session().revision)
    }

    @Test
    fun zeroEpochAndLargestSupportedIntervalDoNotOverflow() {
        val fact = session().copy(
            startedAt = 0,
            endedAt = Long.MAX_VALUE,
            durationMs = Long.MAX_VALUE,
            createdAt = 0,
            updatedAt = Long.MAX_VALUE,
        )
        assertEquals(Long.MAX_VALUE, fact.durationMs)
    }

    @Test
    fun recordingExactRetryIsIdempotent() {
        val repo = MemoryFocusSessionRepository()
        val fact = session()
        assertEquals(FocusSessionRecordResult.INSERTED, repo.record(fact))
        assertEquals(FocusSessionRecordResult.ALREADY_RECORDED, repo.record(fact.copy()))
        assertEquals(listOf(fact), repo.getAll())
        assertNull(repo.getById(FocusSessionId("missing")))
    }

    @Test
    fun conflictingContentCannotReplaceAnExistingFact() {
        val repo = MemoryFocusSessionRepository()
        val fact = session().copy(taskId = TaskId("task-1"))
        repo.record(fact)
        val changedContent = listOf(
            fact.copy(startedAt = 1_001),
            fact.copy(endedAt = 61_001),
            fact.copy(durationMs = 30_000),
            fact.copy(taskId = TaskId("task-2")),
            fact.copy(habit = HabitRef("habit-1")),
            fact.copy(containerId = ContainerId("container-1")),
            fact.copy(source = FocusSessionSource.TIMER),
            fact.copy(deviceId = "device-1"),
            fact.copy(createdAt = 60_999),
            fact.copy(updatedAt = 61_001),
        )
        for (changed in changedContent) {
            val conflict = assertFailsWith<FocusSessionConflictException> { repo.record(changed) }
            assertEquals(fact.id, conflict.sessionId)
            assertEquals(fact, repo.getById(fact.id))
        }
        assertEquals(listOf(fact), repo.getAll())
    }

    @Test
    fun queriesUseStableStartThenIdOrder() {
        val repo = MemoryFocusSessionRepository()
        val late = session("late", 2_000)
        val first = session("a")
        val second = session("b")
        listOf(late, second, first).forEach { repo.record(it) }
        assertEquals(listOf(first, second, late), repo.getAll())
    }

    @Test
    fun oneTaskCanHaveMultipleWorkFactsWithHistoricalContexts() {
        val repo = MemoryFocusSessionRepository()
        val task = TaskId("task-1")
        val oldContext = session("b").copy(taskId = task, containerId = ContainerId("old-location"))
        val newContext = session("later", 2_000).copy(
            taskId = task,
            habit = HabitRef("habit-1"),
            containerId = ContainerId("new-location"),
        )
        val sameStart = session("a").copy(taskId = task)
        listOf(newContext, oldContext, session("unrelated"), sameStart).forEach { repo.record(it) }
        assertEquals(listOf(sameStart, oldContext, newContext), repo.getByTask(task))
        assertEquals(3 * 45_000L, repo.getTotalDurationByTask(task))
        assertEquals(0L, repo.getTotalDurationByTask(TaskId("unknown")))
        assertEquals(emptyList(), repo.getByTask(TaskId("unknown")))
        assertEquals(ContainerId("old-location"), repo.getById(oldContext.id)?.containerId)
    }
}
