/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksListPresentationTest {
    private fun task(id: String) = UserTask(id = TaskId(id), title = id, createdAt = 0L, updatedAt = 0L)

    @Test
    fun disclosuresKeepTheirHeadersAndExposeOnlyExpandedRows() {
        val sections = TaskListSections(
            active = listOf(task("active")),
            overdue = listOf(task("overdue")),
            completed = listOf(task("completed")),
        )
        val collapsed = sections.toListItems("Overdue", "Today", "Completed", false, false, false)
        val expanded = sections.toListItems("Overdue", "Today", "Completed", true, true, false)

        assertEquals(listOf("header:overdue", "header:active", "task:active", "header:completed"), collapsed.map { it.key })
        assertEquals(
            listOf("header:overdue", "task:overdue", "header:active", "task:active", "header:completed", "task:completed"),
            expanded.map { it.key },
        )
        assertEquals(
            listOf(TaskListDisclosure.OVERDUE, null, TaskListDisclosure.COMPLETED),
            expanded.filterIsInstance<TaskListItem.Header>().map { it.disclosure },
        )
    }

    @Test
    fun taskKeySurvivesSectionChangesAndEmptyMatrixHeadingsAreKept() {
        val task = task("moving")
        val grouped = TaskListSections(
            active = listOf(task),
            overdue = emptyList(),
            completed = emptyList(),
            groups = listOf(
                TaskListSections.Group("matrix:I", "Important and urgent", listOf(task)),
                TaskListSections.Group("matrix:II", "Important, not urgent", emptyList()),
            ),
        )
        val groupedItems = grouped.toListItems("Overdue", "Today", "Completed", true, false, false)
        val overdueItems = grouped.copy(active = emptyList(), overdue = listOf(task), groups = emptyList())
            .toListItems("Overdue", "Today", "Completed", true, false, false)

        assertEquals(listOf("group:matrix:I", "task:moving", "group:matrix:II"), groupedItems.map { it.key })
        assertTrue(groupedItems.filterIsInstance<TaskListItem.Row>().single().hideContainer)
        assertEquals(
            groupedItems.filterIsInstance<TaskListItem.Row>().single().key,
            overdueItems.filterIsInstance<TaskListItem.Row>().single().key,
        )
    }

    @Test
    fun swipeDistanceMustExceedQuarterWidthAndRespectsRtl() {
        assertNull(committedTaskSwipe(100f, 400, 0f, 0f, 1f, rtl = false))
        assertEquals(TaskSwipeAction.CHANGE_DUE, committedTaskSwipe(101f, 400, 0f, 0f, 1f, rtl = false))
        assertEquals(TaskSwipeAction.MOVE_CONTAINER, committedTaskSwipe(-101f, 400, 0f, 0f, 1f, rtl = false))
        assertEquals(TaskSwipeAction.CHANGE_DUE, committedTaskSwipe(-101f, 400, 0f, 0f, 1f, rtl = true))
        assertEquals(TaskSwipeAction.MOVE_CONTAINER, committedTaskSwipe(101f, 400, 0f, 0f, 1f, rtl = true))
    }

    @Test
    fun shortSwipeRequiresDirectionalFlingAboveScaledEscapeVelocity() {
        assertNull(committedTaskSwipe(42f, 800, 335f, 0f, 2f, rtl = false))
        assertNull(committedTaskSwipe(42f, 800, -340f, 0f, 2f, rtl = false))
        assertNull(committedTaskSwipe(42f, 800, 340f, 341f, 2f, rtl = false))
        assertNull(committedTaskSwipe(42f, 800, 1900f, 1800f, 2f, rtl = false))
        assertEquals(TaskSwipeAction.CHANGE_DUE, committedTaskSwipe(42f, 800, 340f, 0f, 2f, rtl = false))
    }
}
