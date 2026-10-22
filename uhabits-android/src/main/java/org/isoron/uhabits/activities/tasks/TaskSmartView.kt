package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.UserTask

enum class TaskSmartView(val displayName: String) {
    TODAY("Сегодня"),
    OVERDUE("Просроченные"),
    INBOX("Входящие"),
    UPCOMING("Предстоящие"),
    ALL("Все задачи"),
    COMPLETED("Завершённые");

    fun matches(task: UserTask, today: LocalDate, nowMillis: Long): Boolean = when (this) {
        TODAY -> !task.isCompleted && task.isDueToday(today, nowMillis)
        OVERDUE -> !task.isCompleted && task.isOverdue(today, nowMillis)
        INBOX -> !task.isCompleted && task.containerId == null
        UPCOMING -> !task.isCompleted && task.isDueUpcoming(today, nowMillis)
        ALL -> !task.isCompleted
        COMPLETED -> task.isCompleted
    }
}
