package org.isoron.uhabits.activities.tasks

import android.content.Context
import androidx.annotation.StringRes
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.core.userTasks.UserTask

enum class TaskSmartView(
    val displayName: String,
    @StringRes val titleRes: Int,
) {
    TODAY("Сегодня", R.string.task_smart_today),
    OVERDUE("Просроченные", R.string.task_smart_overdue),
    INBOX("Входящие", R.string.task_smart_inbox),
    UPCOMING("Предстоящие", R.string.task_smart_upcoming),
    ALL("Все задачи", R.string.task_smart_all),
    COMPLETED("Завершённые", R.string.task_smart_completed);

    fun label(context: Context): String = context.getString(titleRes)

    fun matches(task: UserTask, today: LocalDate, nowMillis: Long): Boolean = when (this) {
        TODAY -> !task.isCompleted && task.isDueToday(today, nowMillis)
        OVERDUE -> !task.isCompleted && task.isOverdue(today, nowMillis)
        INBOX -> !task.isCompleted && task.containerId == null
        UPCOMING -> !task.isCompleted && task.isDueUpcoming(today, nowMillis)
        ALL -> !task.isCompleted
        COMPLETED -> task.isCompleted
    }
}
