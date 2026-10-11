package org.isoron.uhabits.activities.tasks

import androidx.fragment.app.FragmentManager
import com.google.android.material.datepicker.MaterialDatePicker
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import java.util.Calendar
import java.util.TimeZone

/** Calendar-only selection: convert Material's UTC date without shifting the local day. */
object TaskDatePicker {
    fun showTime(context: android.content.Context, initial: Long, apply: (Long) -> Unit) {
        val current = Calendar.getInstance().apply { timeInMillis = initial }
        android.app.TimePickerDialog(context, { _, hour, minute ->
            val selected = Calendar.getInstance().apply {
                timeInMillis = initial
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            apply(selected.timeInMillis)
        }, current.get(Calendar.HOUR_OF_DAY), current.get(Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(context)).show()
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
