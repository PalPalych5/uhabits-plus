package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TaskDraftTest {
    @Test fun classificationRemainsIndependentAcrossExpansionAndCollapse() {
        for (important in listOf(null, false, true)) for (urgent in listOf(null, false, true)) {
            val quick = TaskDraft(title = "Task", important = important, urgent = urgent)
            val collapsed = quick.copy(description = "Expanded note").copy(title = "Collapsed")
            assertEquals(important, collapsed.important)
            assertEquals(urgent, collapsed.urgent)
            assertEquals(TaskDue.None, collapsed.due)
        }
        assertNull(TaskDraft.EMPTY.important)
        assertNull(TaskDraft.EMPTY.urgent)
    }

    @Test
    fun editingDraftDoesNotReplaceExactTimedDueWithDay() {
        val zone = ZoneId.of("Asia/Novosibirsk")
        val millis = LocalDateTime.of(2026, 10, 23, 18, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val quickAdd = TaskDraft(title = "PR5 due", due = TaskDue.Timed.local(millis, zone.id))
        val expanded = quickAdd.copy(
            description = "Notes",
            containerId = ContainerId("work"),
            subtasks = listOf(TaskDraft.DraftSubtask("Child", true)),
        )
        val collapsed = expanded.copy(title = "Edited PR5 due")

        assertEquals(quickAdd.due, collapsed.due)
        val due = collapsed.due as TaskDue.Timed
        assertEquals(millis, due.epochMillis)
        assertEquals("Asia/Novosibirsk", due.originZoneId)
        assertEquals(expanded.description, collapsed.description)
        assertEquals(expanded.containerId, collapsed.containerId)
        assertEquals(expanded.subtasks, collapsed.subtasks)
    }

    @Test
    fun defaultDraftIsEmpty() {
        val draft = TaskDraft.EMPTY
        assertEquals("", draft.title)
        assertEquals("", draft.description)
        assertEquals(TaskDue.None, draft.due)
        assertNull(draft.containerId)
        assertEquals(emptyList<TaskDraft.DraftSubtask>(), draft.subtasks)
    }

    @Test
    fun draftMaintainsSubtasksAndAttributes() {
        val subtasks = listOf(
            TaskDraft.DraftSubtask("Subtask 1", isCompleted = false),
            TaskDraft.DraftSubtask("Subtask 2", isCompleted = true),
        )
        val draft = TaskDraft(
            title = "Main Task",
            description = "Detailed notes",
            due = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, 22))),
            containerId = ContainerId("work_container"),
            subtasks = subtasks
        )

        assertEquals("Main Task", draft.title)
        assertEquals("Detailed notes", draft.description)
        assertEquals(ContainerId("work_container"), draft.containerId)
        assertEquals(2, draft.subtasks.size)
        assertEquals("Subtask 1", draft.subtasks[0].title)
        assertEquals(false, draft.subtasks[0].isCompleted)
        assertEquals("Subtask 2", draft.subtasks[1].title)
        assertEquals(true, draft.subtasks[1].isCompleted)
    }
}
