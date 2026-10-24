package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.localDateAt
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue

/** List-only presentation of already scoped roots; Due and repository rules stay authoritative. */
internal data class TaskListSections(
    val active: List<UserTask>,
    val overdue: List<UserTask>,
    val completed: List<UserTask>,
    val groups: List<Group> = emptyList(),
) {
    data class Group(val key: String, val title: String, val tasks: List<UserTask>)

    companion object {
        fun build(
            scopedRoots: List<UserTask>,
            view: TaskSmartView,
            today: LocalDate,
            nowMillis: Long,
            availableContainers: List<OrganizationalItem> = emptyList(),
            locale: Locale = Locale.getDefault(),
            matrix: Boolean = false,
        ): TaskListSections {
            if (matrix) {
                val pending = scopedRoots.filter { !it.isCompleted }.sortedBy { it.sortOrder }
                val keys = listOf("I", "II", "III", "IV", "unclassified")
                val titles = if (locale.language == "ru") listOf(
                    "I · Важно и срочно", "II · Важно, не срочно", "III · Не важно, срочно",
                    "IV · Не важно и не срочно", "Без классификации",
                ) else listOf("I · Important and urgent", "II · Important, not urgent",
                    "III · Not important, urgent", "IV · Not important or urgent", "Unclassified")
                val grouped = pending.groupBy { task ->
                    when {
                        task.important == null || task.urgent == null -> 4
                        task.important == true && task.urgent == true -> 0
                        task.important == true -> 1
                        task.urgent == true -> 2
                        else -> 3
                    }
                }
                return TaskListSections(pending, emptyList(), emptyList(), keys.mapIndexed { index, key ->
                    Group("matrix:$key", titles[index], grouped[index].orEmpty())
                })
            }
            val pendingOrder = compareBy<UserTask> { !it.isOverdue(today, nowMillis) }
                .thenBy { it.sortOrder }
                .thenBy {
                    when (val due = it.due) {
                        is TaskDue.Timed -> due.epochMillis
                        is TaskDue.Day -> due.date.toLocalDate().daysSince2000.toLong() * 86400000L
                        TaskDue.None -> Long.MAX_VALUE
                    }
                }
            val matching = scopedRoots.filter { view.matches(it, today, nowMillis) }
            if (view == TaskSmartView.COMPLETED) {
                val ordered = matching.sortedByDescending { it.completedAt?.takeIf { at -> at > 0 } ?: 0L }
                val dated = ordered.groupBy { it.completedAt?.takeIf { at -> at > 0 }?.let { at -> localDateAt(at) } }
                val groups = dated.keys.filterNotNull().sortedDescending().map { date ->
                    Group("completed:${date.daysSince2000}", dateTitle(date, today, true, locale), dated.getValue(date))
                } + dated[null].orEmpty().takeIf { it.isNotEmpty() }.let { unknown ->
                    if (unknown == null) emptyList()
                    else listOf(Group("completed:unknown", label(locale, "Дата завершения не указана", "Completion date not specified"), unknown))
                }
                return TaskListSections(ordered, emptyList(), emptyList(), groups)
            }
            val separateOverdue = view == TaskSmartView.TODAY || view == TaskSmartView.ALL || view == TaskSmartView.INBOX
            val overdue = if (separateOverdue) scopedRoots.filter {
                it.isOverdue(today, nowMillis) && (view != TaskSmartView.INBOX || it.containerId == null)
            }.sortedWith(pendingOrder) else emptyList()
            val active = matching.filter { !separateOverdue || !it.isOverdue(today, nowMillis) }.sortedWith(pendingOrder)
            val completed = if (view == TaskSmartView.ALL || view == TaskSmartView.INBOX) scopedRoots.filter {
                it.isCompleted && (view != TaskSmartView.INBOX || it.containerId == null)
            }.sortedByDescending { it.completedAt ?: 0L } else emptyList()
            val groups = when (view) {
                TaskSmartView.ALL -> {
                    val known = availableContainers.mapTo(hashSetOf()) { it.key }
                    val byContainer = active.groupBy { it.containerId?.value }
                    val inbox = byContainer[null].orEmpty()
                    val unavailable = active.filter { task ->
                        val key = task.containerId?.value
                        key != null && key !in known
                    }
                    (if (inbox.isEmpty()) emptyList() else listOf(Group("inbox", label(locale, "Входящие", "Inbox"), inbox))) +
                        availableContainers.mapNotNull { item -> byContainer[item.key]?.let {
                            Group("container:${item.key}", item.path ?: item.name, it)
                        } } +
                        (if (unavailable.isEmpty()) emptyList() else listOf(Group("container:unavailable", label(locale, "Другой раздел", "Other section"), unavailable)))
                }
                TaskSmartView.UPCOMING -> active.groupBy { task ->
                    when (val due = task.due) {
                        is TaskDue.Day -> due.date.toLocalDate()
                        is TaskDue.Timed -> localDateAt(due.epochMillis)
                        TaskDue.None -> null
                    }
                }.filterKeys { it != null }.entries.sortedBy { it.key }.map { (date, tasks) ->
                    Group("due:${date!!.daysSince2000}", dateTitle(date, today, false, locale), tasks)
                }
                else -> emptyList()
            }
            return TaskListSections(active, overdue, completed, groups)
        }

        private fun label(locale: Locale, ru: String, en: String) = if (locale.language == "ru") ru else en

        private fun dateTitle(date: LocalDate, today: LocalDate, completed: Boolean, locale: Locale): String {
            if (completed && date == today) return label(locale, "Сегодня", "Today")
            if (completed && date == today.minus(1)) return label(locale, "Вчера", "Yesterday")
            if (!completed && date == today.plus(1)) return label(locale, "Завтра", "Tomorrow")
            val value = java.time.LocalDate.of(date.year, date.month, date.day)
            val pattern = when {
                date.year != today.year -> "d MMM yyyy"
                !completed && date <= today.plus(7) -> "EEEE, d MMM"
                else -> "d MMM"
            }
            return value.format(DateTimeFormatter.ofPattern(pattern, locale))
                .replaceFirstChar { it.titlecase(locale) }
        }
    }
}
