package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.userTasks.due.TaskDue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared compact Due labels, using the application's selected locale. */
object TaskDueDisplayFormatter {
    fun selectedLocale(context: android.content.Context): Locale {
        val tag = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString("pref_app_language", "ru-RU").orEmpty()
        return if (tag.isNotBlank()) Locale.forLanguageTag(tag)
        else context.resources.configuration.locales[0]
    }

    fun format(due: TaskDue, locale: Locale): String =
        format(due, locale, is24Hour = true, noDueLabel = "Задать срок")

    fun format(
        due: TaskDue,
        locale: Locale,
        is24Hour: Boolean,
        noDueLabel: String = "Задать срок",
    ): String = when (due) {
        TaskDue.None -> noDueLabel
        is TaskDue.Day -> LocalDate.parse(due.date.isoDate)
            .format(DateTimeFormatter.ofPattern("d MMM", locale))
        is TaskDue.Timed -> Instant.ofEpochMilli(due.epochMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(if (is24Hour) "d MMM, HH:mm" else "d MMM, h:mm a", locale))
    }

    fun format(context: android.content.Context, due: TaskDue): String = format(
        due = due,
        locale = selectedLocale(context),
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        noDueLabel = context.getString(org.isoron.uhabits.R.string.task_due_set_prompt),
    )
}
