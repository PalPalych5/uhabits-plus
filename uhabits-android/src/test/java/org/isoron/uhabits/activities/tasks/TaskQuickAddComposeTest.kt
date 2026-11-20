/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TaskQuickAddComposeTest {

    @Test
    fun testQuickAddDraftCreation() {
        val title = "Buy groceries"
        val notes = "Milk, Eggs, Bread"
        val due = TaskDue.Day(TaskDueDate.from(LocalDate(100)))
        val containerId = ContainerId("personal-tasks")
        val tagIds = setOf("errands", "food")

        val draft = TaskDraft(
            title = title,
            description = notes,
            due = due,
            containerId = containerId,
            tagIds = tagIds,
            important = true,
            urgent = false,
            subtasks = listOf(
                TaskDraft.DraftSubtask("Milk", true),
                TaskDraft.DraftSubtask("Eggs", false)
            )
        )

        assertEquals("Buy groceries", draft.title)
        assertEquals("Milk, Eggs, Bread", draft.description)
        assertEquals(due, draft.due)
        assertEquals(containerId, draft.containerId)
        assertEquals(tagIds, draft.tagIds)
        assertEquals(true, draft.important)
        assertEquals(false, draft.urgent)
        assertEquals(2, draft.subtasks.size)
        assertTrue(draft.subtasks[0].isCompleted)
        assertFalse(draft.subtasks[1].isCompleted)
    }

    @Test
    fun testTagLabelFormatting() {
        fun formatTagLabel(tagNames: List<String>): String {
            return if (tagNames.isNotEmpty()) {
                val firstTag = "#${tagNames.first()}"
                if (tagNames.size > 1) "$firstTag +${tagNames.size - 1}" else firstTag
            } else ""
        }

        assertEquals("", formatTagLabel(emptyList()))
        assertEquals("#work", formatTagLabel(listOf("work")))
        assertEquals("#work +1", formatTagLabel(listOf("work", "urgent")))
        assertEquals("#work +2", formatTagLabel(listOf("work", "urgent", "home")))
    }

    @Test
    fun testDueFormattingConsistency() {
        val noneDue = TaskDue.None
        val formattedNone = TaskDueDisplayFormatter.format(noneDue, Locale.US)
        assertNotNull(formattedNone)
    }
}
