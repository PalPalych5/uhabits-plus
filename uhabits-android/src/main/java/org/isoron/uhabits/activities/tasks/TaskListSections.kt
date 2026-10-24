package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue

/** List-only presentation of already scoped roots; Due and repository rules stay authoritative. */
internal data class TaskListSections(
    val active: List<UserTask>,
    val overdue: List<UserTask>,
    val completed: List<UserTask>,
) {
    companion object {
        fun build(
            scopedRoots: List<UserTask>,
            view: TaskSmartView,
            today: LocalDate,
            nowMillis: Long,
        ): TaskListSections {
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
                return TaskListSections(matching.sortedByDescending { it.completedAt ?: 0L }, emptyList(), emptyList())
            }
            val separateOverdue = view == TaskSmartView.TODAY || view == TaskSmartView.ALL || view == TaskSmartView.INBOX
            val overdue = if (separateOverdue) scopedRoots.filter {
                it.isOverdue(today, nowMillis) && (view != TaskSmartView.INBOX || it.containerId == null)
            }.sortedWith(pendingOrder) else emptyList()
            val active = matching.filter { !separateOverdue || !it.isOverdue(today, nowMillis) }.sortedWith(pendingOrder)
            val completed = if (view == TaskSmartView.ALL || view == TaskSmartView.INBOX) scopedRoots.filter {
                it.isCompleted && (view != TaskSmartView.INBOX || it.containerId == null)
            }.sortedByDescending { it.completedAt ?: 0L } else emptyList()
            return TaskListSections(active, overdue, completed)
        }
    }
}
