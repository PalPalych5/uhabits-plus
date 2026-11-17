package org.isoron.uhabits.activities.tasks

import androidx.fragment.app.FragmentManager
import com.google.android.material.datepicker.MaterialDatePicker
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import java.util.Calendar
import java.util.TimeZone

/**
 * Legacy unused picker.
 * NOTE: The active production task due date and time picker is [TaskDuePickerBottomSheet].
 */
@Deprecated("Unused legacy picker; active production picker is TaskDuePickerBottomSheet")
object TaskDatePicker {
    fun showTime(context: android.content.Context, initial: Long, apply: (Long) -> Unit) {
        val current = Calendar.getInstance().apply { timeInMillis = initial }
        val hour = current.get(Calendar.HOUR_OF_DAY)
        val minute = current.get(Calendar.MINUTE)
        val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        org.isoron.uhabits.activities.common.dialogs.CustomDialogs.showTimePickerDialog(
            context = context,
            title = context.getString(R.string.reminder),
            initialHour = hour,
            initialMinute = minute,
            is24Hour = is24Hour,
            onConfirm = { h, m ->
                val selected = Calendar.getInstance().apply {
                    timeInMillis = initial
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                apply(selected.timeInMillis)
            }
        )
    }

    fun show(manager: FragmentManager, initial: LocalDate, title: String, apply: (LocalDate) -> Unit) {
        val tag = "taskDatePicker:$title"
        // A cancelled DialogFragment removes itself asynchronously. Drain that
        // removal before testing the tag so a quick reopen cannot target a
        // dismissed picker or silently ignore the user's new request.
        manager.executePendingTransactions()
        if (manager.findFragmentByTag(tag) != null) return
        val utc = TimeZone.getTimeZone("UTC")
        val selection = Calendar.getInstance(utc).apply {
            clear()
            set(initial.year, initial.month - 1, initial.day)
        }.timeInMillis
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTheme(R.style.TaskDatePickerTheme)
            .setTitleText(title).setSelection(selection).setPositiveButtonText("Применить")
            .setNegativeButtonText("Отмена").build()
        attach(picker, apply)
        picker.showNow(manager, tag)
    }

    fun reconnect(manager: FragmentManager, title: String, apply: (LocalDate) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val picker = manager.findFragmentByTag("taskDatePicker:$title") as? MaterialDatePicker<Long> ?: return
        attach(picker, apply)
    }

    private fun attach(picker: MaterialDatePicker<Long>, apply: (LocalDate) -> Unit) {
        val utc = TimeZone.getTimeZone("UTC")
        picker.clearOnPositiveButtonClickListeners()
        picker.addOnPositiveButtonClickListener { millis ->
            val date = Calendar.getInstance(utc).apply { timeInMillis = millis }
            apply(LocalDate(date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH)))
        }
    }
}
