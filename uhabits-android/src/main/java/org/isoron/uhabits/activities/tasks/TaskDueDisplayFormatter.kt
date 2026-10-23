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

    fun format(due: TaskDue, locale: Locale): String = when (due) {
        TaskDue.None -> "Задать срок"
        is TaskDue.Day -> LocalDate.parse(due.date.isoDate)
            .format(DateTimeFormatter.ofPattern("d MMM", locale))
        is TaskDue.Timed -> Instant.ofEpochMilli(due.epochMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))
    }
}
