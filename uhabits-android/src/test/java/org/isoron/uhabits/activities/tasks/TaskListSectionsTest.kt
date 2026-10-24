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
    @Test fun matrixRequiresBothExplicitValuesAndNeverInfersUrgencyFromDue() {
        val source = listOf(true to true, true to false, false to true, false to false,
            null to null, null to false, false to null, null to true, true to null).mapIndexed { index, (important, urgent) ->
            task("task-$index", day(22), index).copy(important = important, urgent = urgent)
        }
        val result = TaskListSections.build(source + source.first().copy(id = TaskId("done"), status = TaskStatus.COMPLETED),
            TaskSmartView.TODAY, today, now, matrix = true)
        assertEquals(listOf("task-0"), result.groups[0].tasks.ids())
        assertEquals(listOf("task-1"), result.groups[1].tasks.ids())
        assertEquals(listOf("task-2"), result.groups[2].tasks.ids())
        assertEquals(listOf("task-3"), result.groups[3].tasks.ids())
        assertEquals((4..8).map { "task-$it" }, result.groups[4].tasks.ids())
        assertEquals(source.map { it.id }.toSet(), result.groups.flatMap { it.tasks }.map { it.id }.toSet())
        assertTrue(result.overdue.isEmpty() && result.completed.isEmpty())
    }

    @Test fun emptyMatrixKeepsAllHeadingsAndScopedOrderAndIdentityRemainStable() {
        val empty = TaskListSections.build(emptyList(), TaskSmartView.ALL, today, now, matrix = true)
        assertEquals(5, empty.groups.size)
        assertTrue(empty.groups.all { it.tasks.isEmpty() })
        val first = task("first", order = -1).copy(important = true, urgent = false)
        val second = task("second", day(22)).copy(important = true, urgent = false)
        val result = TaskListSections.build(listOf(second, first), TaskSmartView.COMPLETED, today, now, matrix = true)
        assertEquals(listOf(first, second), result.groups[1].tasks)
        assertEquals(empty.groups.map { it.key }, result.groups.map { it.key })
    }
    private val today = LocalDate(2026, 10, 23)
    private val now = java.time.LocalDateTime.of(2026, 10, 23, 12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val ru = java.util.Locale("ru")
    private fun day(value: Int) = TaskDue.Day(TaskDueDate.from(LocalDate(2026, 10, value)))
    private fun task(id: String, due: TaskDue = TaskDue.None, order: Int = 0) =
        UserTask(TaskId(id), id, due = due, sortOrder = order, createdAt = 0, updatedAt = 0)
    private fun done(id: String, at: Long? = null) = task(id).copy(status = TaskStatus.COMPLETED, completedAt = at)
    private fun build(tasks: List<UserTask>, view: TaskSmartView = TaskSmartView.ALL) =
        TaskListSections.build(tasks, view, today, now, locale = ru)
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

    @Test fun upcomingCombinesDayAndTimedOnLocalCalendarDate() {
        val timed = java.time.LocalDateTime.of(2026, 10, 24, 23, 59)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val result = build(listOf(task("later", day(26)), task("timed", TaskDue.Timed.of(timed)),
            task("day", day(24)), task("none"), done("completed")), TaskSmartView.UPCOMING)
        assertEquals("Завтра", result.groups.first().title)
        assertEquals(setOf("day", "timed"), result.groups.first().tasks.ids().toSet())
        assertEquals(listOf("later"), result.groups.last().tasks.ids())
        assertEquals(2, result.groups.size)
    }

    @Test fun archiveUsesOnlyPositiveCompletionInstantAndKeepsUnknownDatesLast() {
        val result = build(listOf(done("today", now), done("yesterday", now - 86400000L),
            done("null").copy(due = day(24), updatedAt = now), done("zero", 0), done("negative", -1)),
            TaskSmartView.COMPLETED)
        assertEquals(listOf("Сегодня", "Вчера", "Дата завершения не указана"), result.groups.map { it.title })
        assertEquals(listOf("today"), result.groups[0].tasks.ids())
        assertEquals(setOf("null", "zero", "negative"), result.groups.last().tasks.ids().toSet())
    }

    @Test fun upcomingYearBoundaryIncludesYearAndOmitsEmptyDates() {
        val result = TaskListSections.build(
            listOf(task("tomorrow", day(24)), task("future", TaskDue.Day(TaskDueDate.parse("2027-01-02")))),
            TaskSmartView.UPCOMING,
            today,
            now,
            locale = java.util.Locale.ENGLISH,
        )
        assertEquals(listOf("Tomorrow", "2 Jan 2027"), result.groups.map { it.title })
        val archive = TaskListSections.build(
            listOf(done("today", now), done("yesterday", now - 86400000L), done("unknown")),
            TaskSmartView.COMPLETED,
            today,
            now,
            locale = java.util.Locale.ENGLISH,
        )
        assertEquals(listOf("Today", "Yesterday", "Completion date not specified"), archive.groups.map { it.title })
        assertTrue(build(emptyList(), TaskSmartView.UPCOMING).groups.isEmpty())
    }

    @Test fun timedAndCompletionGroupingUseLocalMidnightRatherThanUtcDate() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Novosibirsk"))
            val instant = java.time.Instant.parse("2026-10-23T18:00:00Z").toEpochMilli()
            val upcoming = build(listOf(task("timed", TaskDue.Timed.of(instant)), task("day", day(24))), TaskSmartView.UPCOMING)
            assertEquals(1, upcoming.groups.size)
            assertEquals("Завтра", upcoming.groups.single().title)
            val archive = TaskListSections.build(listOf(done("completed", instant)), TaskSmartView.COMPLETED,
                LocalDate(2026, 10, 24), instant, locale = ru)
            assertEquals("Сегодня", archive.groups.single().title)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test fun todayNeverIncludesCompletedCompanionRegardlessOfDue() {
        val result = build(listOf(done("day").copy(due = day(23)),
            done("timed").copy(due = TaskDue.Timed.of(now)),
            done("other").copy(due = day(22)), done("none")), TaskSmartView.TODAY)
        assertTrue(result.completed.isEmpty())
        assertTrue(result.active.isEmpty() && result.overdue.isEmpty())
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

    @Test fun allUsesDirectPreorderContainersAndRetainsUnavailableAssignments() {
        val tasks = listOf(task("none"), task("missing").copy(containerId = ContainerId("missing")),
            task("parent").copy(containerId = ContainerId("p")), task("deep").copy(containerId = ContainerId("d")))
        fun item(key: String, path: String) = org.isoron.uhabits.core.containers.facade.OrganizationalItem(
            key, key, null, null, path, key == "p")
        val result = TaskListSections.build(tasks, TaskSmartView.ALL, today, now,
            listOf(item("p", "Parent"), item("empty", "Parent / Empty"), item("d", "Parent / Child / Third / Fourth")),
            locale = ru)
        assertEquals(listOf("inbox", "container:p", "container:d", "container:unavailable"), result.groups.map { it.key })
        assertEquals(listOf("none"), result.groups.first().tasks.ids())
        assertTrue(result.groups.first().tasks.all { it.containerId == null })
        assertEquals("Parent / Child / Third / Fourth", result.groups[2].title)
        assertEquals("Другой раздел", result.groups.last().title)
        assertEquals(listOf("missing"), result.groups.last().tasks.ids())
        assertEquals(ContainerId("missing"), result.groups.last().tasks.single().containerId)
        assertEquals(tasks.map { it.id }.toSet(), result.groups.flatMap { it.tasks }.map { it.id }.toSet())
        assertEquals(tasks.size, result.groups.sumOf { it.tasks.size })
    }

    @Test fun dueAndContainerQuickActionsRelocateTasksAcrossGroupsWithoutDuplication() {
        fun item(key: String, path: String) = org.isoron.uhabits.core.containers.facade.OrganizationalItem(
            key, key, null, null, path, true
        )
        val containers = listOf(item("work", "Работа"), item("home", "Дом"))

        // 1. UPCOMING: changing due moves task from Tomorrow to 26 Oct without duplication
        val t1 = task("t1", day(24))
        val t2 = TaskDue.Timed.of(
            java.time.LocalDateTime.of(2026, 10, 24, 15, 30)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ZoneId.systemDefault().id,
        ).let { task("t2", it) }
        val beforeUpcoming = TaskListSections.build(listOf(t1, t2), TaskSmartView.UPCOMING, today, now, containers, ru)
        assertEquals(1, beforeUpcoming.groups.size)
        assertEquals(setOf("t1", "t2"), beforeUpcoming.groups.single().tasks.ids().toSet())

        val t1Moved = t1.copy(due = day(26))
        val afterUpcoming = TaskListSections.build(listOf(t1Moved, t2), TaskSmartView.UPCOMING, today, now, containers, ru)
        assertEquals(2, afterUpcoming.groups.size)
        assertEquals(listOf("t2"), afterUpcoming.groups[0].tasks.ids())
        assertEquals(listOf("t1"), afterUpcoming.groups[1].tasks.ids())
        assertEquals(2, afterUpcoming.groups.flatMap { it.tasks }.size)

        // 2. ALL: moving tasks from Inbox and Work to Home relocates without duplication
        val inboxTask = task("inbox", day(23))
        val workTask = task("workTask", day(25)).copy(containerId = ContainerId("work"))
        val beforeAll = TaskListSections.build(listOf(inboxTask, workTask), TaskSmartView.ALL, today, now, containers, ru)
        assertEquals(listOf("inbox", "container:work"), beforeAll.groups.map { it.key })

        val movedToHome = listOf(
            inboxTask.copy(containerId = ContainerId("home")),
            workTask.copy(containerId = ContainerId("home")),
        )
        val afterAll = TaskListSections.build(movedToHome, TaskSmartView.ALL, today, now, containers, ru)
        assertEquals(listOf("container:home"), afterAll.groups.map { it.key })
        assertEquals(setOf("inbox", "workTask"), afterAll.groups.single().tasks.ids().toSet())
        assertEquals(2, afterAll.groups.sumOf { it.tasks.size })
    }

    @Test fun tagFilteredTasksMaintainSmartViewGroupingsAndContainerIntersections() {
        fun item(key: String, path: String) = org.isoron.uhabits.core.containers.facade.OrganizationalItem(
            key, key, null, null, path, true
        )
        val containers = listOf(item("work", "Работа"), item("home", "Дом"))
        val tInboxWorkImportant = task("inbox-wi", day(23))
        val tWorkOnly = task("work-only", day(24)).copy(containerId = ContainerId("work"))
        val tHomeWorkImportant = task("home-wi", day(25)).copy(containerId = ContainerId("home"))
        val tDoneWorkImportant = done("done-wi", now).copy(containerId = ContainerId("work"))
        val allTasks = listOf(tInboxWorkImportant, tWorkOnly, tHomeWorkImportant, tDoneWorkImportant)
        val tagsByTask = mapOf(
            TaskId("inbox-wi") to setOf("tag-work", "tag-important"),
            TaskId("work-only") to setOf("tag-work"),
            TaskId("home-wi") to setOf("tag-work", "tag-important"),
            TaskId("done-wi") to setOf("tag-work", "tag-important"),
        )
        fun filterByTags(source: List<UserTask>, requiredTags: Set<String>): List<UserTask> =
            if (requiredTags.isEmpty()) source else source.filter { tagsByTask[it.id].orEmpty().containsAll(requiredTags) }

        val intersection = filterByTags(allTasks, setOf("tag-work", "tag-important"))
        assertEquals(setOf("inbox-wi", "home-wi", "done-wi"), intersection.ids().toSet())

        val allSections = TaskListSections.build(intersection, TaskSmartView.ALL, today, now, containers, ru)
        assertEquals(listOf("inbox", "container:home"), allSections.groups.map { it.key })
        assertEquals(listOf("done-wi"), allSections.completed.ids())

        val todaySections = TaskListSections.build(intersection, TaskSmartView.TODAY, today, now, containers, ru)
        assertEquals(listOf("inbox-wi"), todaySections.active.ids())
        assertTrue(todaySections.completed.isEmpty())

        val upcomingSections = TaskListSections.build(intersection, TaskSmartView.UPCOMING, today, now, containers, ru)
        assertEquals(1, upcomingSections.groups.size)
        assertEquals(listOf("home-wi"), upcomingSections.groups.single().tasks.ids())

        val completedSections = TaskListSections.build(intersection, TaskSmartView.COMPLETED, today, now, containers, ru)
        assertEquals(1, completedSections.groups.size)
        assertEquals(listOf("done-wi"), completedSections.groups.single().tasks.ids())

        val emptyIntersection = filterByTags(allTasks, setOf("tag-someday"))
        val emptySections = TaskListSections.build(emptyIntersection, TaskSmartView.ALL, today, now, containers, ru)
        assertTrue(emptySections.active.isEmpty() && emptySections.overdue.isEmpty() && emptySections.completed.isEmpty())
    }
}
