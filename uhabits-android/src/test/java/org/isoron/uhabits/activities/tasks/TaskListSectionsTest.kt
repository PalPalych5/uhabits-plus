package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class TaskListSectionsTest {
    private val today = LocalDate(2026, 10, 23)
    private val now = java.time.LocalDateTime.of(2026, 10, 23, 12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun day(value: Int) = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, value)))
    private fun task(id: String, due: TaskDue = TaskDue.None, order: Int = 0) =
        UserTask(TaskId(id), id, due = due, sortOrder = order, createdAt = 0, updatedAt = 0)
    private fun done(id: String, at: Long? = null) = task(id).copy(status = TaskStatus.COMPLETED, completedAt = at)
    private fun build(tasks: List<UserTask>, view: TaskSmartView = TaskSmartView.ALL) =
        TaskListSections.build(tasks, view, today, now)
    private fun List<UserTask>.ids() = map { it.id.value }

    @Test fun todayKeepsDayAndExactInstantWhileExpiredTimedMovesOnlyToOverdue() {
        val sections = build(listOf(task("none"), task("yesterday", day(22)), task("day", day(23)),
            task("expired", TaskDue.Timed.of(now - 1)), task("instant", TaskDue.Timed.of(now)),
            task("later", TaskDue.Timed.of(now + 1)), task("tomorrow", day(24))), TaskSmartView.TODAY)
        assertEquals(setOf("day", "instant", "later"), sections.active.ids().toSet())
        assertEquals(setOf("yesterday", "expired"), sections.overdue.ids().toSet())
        assertTrue(sections.completed.isEmpty())
        assertEquals(5, (sections.active + sections.overdue).map { it.id }.distinct().size)
    }

    @Test fun allPartitionsWithoutDroppingNoneOrCompletedHistory() {
        val source = listOf(task("none"), task("old", day(22)), task("today", day(23)), done("done", 9))
        val sections = build(source)
        assertEquals(listOf("today", "none"), sections.active.ids())
        assertEquals(listOf("old"), sections.overdue.ids())
        assertEquals(listOf("done"), sections.completed.ids())
        assertEquals(source.map { it.id }.toSet(), (sections.active + sections.overdue + sections.completed).map { it.id }.toSet())
    }

    @Test fun inboxAppliesUnassignedRuleToEverySection() {
        val unassigned = listOf(task("none"), task("old", day(22)), done("done"))
        val assigned = unassigned.map { it.copy(id = TaskId("assigned-${it.id}"), containerId = ContainerId("child")) }
        val sections = build(unassigned + assigned, TaskSmartView.INBOX)
        assertEquals(listOf("none"), sections.active.ids())
        assertEquals(listOf("old"), sections.overdue.ids())
        assertEquals(listOf("done"), sections.completed.ids())
    }

    @Test fun overdueAndUpcomingKeepTheirExistingViewsWithoutCompanionSections() {
        val source = listOf(task("old", day(22)), task("today", day(23)), task("future", day(24)), task("none"), done("done"))
        val overdue = build(source, TaskSmartView.OVERDUE)
        assertEquals(listOf("old"), overdue.active.ids())
        assertTrue(overdue.overdue.isEmpty() && overdue.completed.isEmpty())
        val upcoming = build(source, TaskSmartView.UPCOMING)
        assertEquals(listOf("future"), upcoming.active.ids())
        assertTrue(upcoming.overdue.isEmpty() && upcoming.completed.isEmpty())
    }

    @Test fun completionAndReopenMoveSameIdentityWithoutLosingDue() {
        val original = task("today", day(23))
        val completed = original.copy(status = TaskStatus.COMPLETED, completedAt = now)
        assertEquals(listOf(original), build(listOf(original)).active)
        assertEquals(listOf(completed), build(listOf(completed)).completed)
        assertTrue(build(listOf(completed), TaskSmartView.TODAY).active.isEmpty())
        assertEquals(listOf(original), build(listOf(completed.copy(status = TaskStatus.PENDING, completedAt = null))).active)
        assertTrue(build(listOf(original.copy(due = TaskDue.None)), TaskSmartView.TODAY).active.isEmpty())
    }

    @Test fun sortingRetainsSortOrderThenDueAndCompletedNewestFirst() {
        val sections = build(listOf(task("none"), task("late", day(25)), task("first", day(24)),
            task("priority", day(26), -1), done("old", 1), done("new", 10), done("unknown")))
        assertEquals(listOf("priority", "first", "late", "none"), sections.active.ids())
        assertEquals(listOf("new", "old", "unknown"), sections.completed.ids())
        assertEquals(sections.completed, build(sections.completed, TaskSmartView.COMPLETED).active)
    }

    @Test fun alreadyScopedNestedContainerRootsAreRetainedAcrossSections() {
        val parent = task("parent").copy(containerId = ContainerId("parent"))
        val child = task("child", day(22)).copy(containerId = ContainerId("child"))
        val completed = done("done").copy(containerId = ContainerId("child"))
        val sections = build(listOf(parent, child, completed))
        assertEquals(listOf(parent), sections.active)
        assertEquals(listOf(child), sections.overdue)
        assertEquals(listOf(completed), sections.completed)
    }

    @Test fun emptyAndCompletedOnlySourcesRemainRepresentable() {
        assertEquals(TaskListSections(emptyList(), emptyList(), emptyList()), build(emptyList()))
        val completed = done("done", now)
        val all = build(listOf(completed))
        assertTrue(all.active.isEmpty() && all.overdue.isEmpty())
        assertEquals(listOf(completed), all.completed)
        val archive = build(listOf(completed), TaskSmartView.COMPLETED)
        assertEquals(listOf(completed), archive.active)
        assertTrue(archive.overdue.isEmpty() && archive.completed.isEmpty())
    }
}
