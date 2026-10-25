package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun draftMaintainsHabitLinkFields() {
        val draft = TaskDraft(
            title = "Task with habit link",
            linkedHabitUuid = "habit-123",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 100_000L,
        )

        assertEquals("habit-123", draft.linkedHabitUuid)
        assertEquals(true, draft.habitTimeAccrualEnabled)
        assertEquals(100_000L, draft.habitLinkEffectiveFrom)

        val collapsed = draft.copy(title = "Collapsed title")
        assertEquals("habit-123", collapsed.linkedHabitUuid)
        assertEquals(true, collapsed.habitTimeAccrualEnabled)
        assertEquals(100_000L, collapsed.habitLinkEffectiveFrom)
    }

    @Test
    fun clearHabitLinkResetsAllLinkAttributesCoherently() {
        val original = TaskDraft(
            title = "Task with habit link",
            description = "Some description",
            important = true,
            urgent = false,
            linkedHabitUuid = "habit-xyz",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 50_000L,
        )

        val cleared = original.clearHabitLink()

        assertNull(cleared.linkedHabitUuid)
        assertFalse(cleared.habitTimeAccrualEnabled)
        assertNull(cleared.habitLinkEffectiveFrom)

        // Verifies all unrelated attributes are strictly preserved
        assertEquals(original.title, cleared.title)
        assertEquals(original.description, cleared.description)
        assertEquals(original.important, cleared.important)
        assertEquals(original.urgent, cleared.urgent)
        assertEquals(original.due, cleared.due)
        assertEquals(original.containerId, cleared.containerId)
    }

    @Test
    fun withHabitLinkSetsAllLinkAttributesCoherently() {
        val draft = TaskDraft(title = "New task")
        val linked = draft.withHabitLink(
            habitUuid = "habit-456",
            effectiveFrom = 123456L,
            accrualEnabled = true,
        )

        assertEquals("habit-456", linked.linkedHabitUuid)
        assertEquals(123456L, linked.habitLinkEffectiveFrom)
        assertTrue(linked.habitTimeAccrualEnabled)

        val switched = linked.withHabitLink(
            habitUuid = "habit-789",
            effectiveFrom = 789012L,
            accrualEnabled = false,
        )

        assertEquals("habit-789", switched.linkedHabitUuid)
        assertEquals(789012L, switched.habitLinkEffectiveFrom)
        assertFalse(switched.habitTimeAccrualEnabled)
        assertEquals(draft.title, switched.title)
    }

    @Test
    fun isCompatibleHabitAcceptsMinuteUnitsAndRejectsNonMinuteOrNonNumerical() {
        val factory = MemoryModelFactory()

        for (unit in listOf("мин", " мин ", "минута", "минуты", "минут", "мин.", "min", "mins", "minute", "minutes", "min.", "MIN", "MINS")) {
            val habit = factory.buildHabit().apply {
                type = HabitType.NUMERICAL
                this.unit = unit
            }
            assertTrue("Expected unit '$unit' to be compatible", TaskDetailBottomSheet.isCompatibleHabit(habit))
        }

        for (incompatibleUnit in listOf("мл", "км", "страниц", "кг", "раз", "", "   ")) {
            val habit = factory.buildHabit().apply {
                type = HabitType.NUMERICAL
                this.unit = incompatibleUnit
            }
            assertFalse("Expected unit '$incompatibleUnit' to be incompatible", TaskDetailBottomSheet.isCompatibleHabit(habit))
        }

        val booleanHabit = factory.buildHabit().apply {
            type = HabitType.YES_NO
            this.unit = "мин"
        }
        assertFalse("Expected YES_NO habit to be incompatible even if unit is 'мин'", TaskDetailBottomSheet.isCompatibleHabit(booleanHabit))
    }
}
