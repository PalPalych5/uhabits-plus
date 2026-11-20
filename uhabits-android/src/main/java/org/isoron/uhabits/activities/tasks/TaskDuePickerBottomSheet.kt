package org.isoron.uhabits.activities.tasks

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.util.Locale

/** Transactional shared editor: only explicit confirmation emits a result. */
class TaskDuePickerBottomSheet : BottomSheetDialogFragment() {
    private var date by mutableStateOf<LocalDate?>(null)
    private var time by mutableStateOf<LocalTime?>(null)
    private lateinit var zone: ZoneId
    private var original: TaskDue = TaskDue.None
    private var edited = false
    private var displayMonth by mutableStateOf<YearMonth?>(null)
    private var hourText by mutableStateOf("")
    private var minuteText by mutableStateOf("")
    private var is24Hour = true

    private fun currentDataset() = (requireContext().applicationContext as HabitsApplication).component.session.datasetId
    private fun validDataset() = requireArguments().getString("datasetId") == currentDataset()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!requireArguments().containsKey("datasetId")) requireArguments().putString("datasetId", currentDataset())
        original = readResult(requireArguments())
        displayMonth = state?.getString("displayMonth")?.let(YearMonth::parse)
        zone = ZoneId.of(state?.getString("draftZone") ?: ZoneId.systemDefault().id)
        if (state != null) {
            date = state.getString("draftDate")?.let(LocalDate::parse)
            time = state.getString("draftTime")?.let(LocalTime::parse)
            edited = state.getBoolean("edited")
        } else when (val due = original) {
            is TaskDue.None -> Unit
            is TaskDue.Day -> date = LocalDate.parse(due.date.isoDate)
            is TaskDue.Timed -> Instant.ofEpochMilli(due.epochMillis).atZone(zone).let {
                date = it.toLocalDate(); time = it.toLocalTime().withSecond(0).withNano(0)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draftDate", date?.toString())
        outState.putString("draftTime", time?.toString())
        outState.putString("draftZone", zone.id)
        outState.putBoolean("edited", edited)
        outState.putString("displayMonth", displayMonth?.toString())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val colors = UHabitComposeColors.fromPalette(MainTabsThemeBridge.resolve(context))
        val locale = TaskDueDisplayFormatter.selectedLocale(context)
        is24Hour = DateFormat.is24HourFormat(context)
        displayMonth = displayMonth ?: YearMonth.from(date ?: LocalDate.now(zone))
        resetClockText()

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val clock = time
                val offset = resolveTime()?.let { Instant.ofEpochMilli(it.epochMillis).atZone(zone).offset.id }
                val utc = offset?.let { if (it == "Z") "+0" else it.removeSuffix(":00") }
                UHabitComposeTheme(colors = colors) {
                    TaskDuePickerContent(
                        today = LocalDate.now(zone),
                        date = date,
                        month = checkNotNull(displayMonth),
                        locale = locale,
                        time = clock,
                        is24Hour = is24Hour,
                        hourText = hourText,
                        minuteText = minuteText,
                        zoneCaption = zone.id + (utc?.let { " · UTC$it" } ?: ""),
                        error = clockError(),
                        onDate = { selected ->
                            edited = true
                            date = selected
                            displayMonth = YearMonth.from(selected)
                            resetClockText()
                        },
                        onMonth = {
                            displayMonth = it
                            resetClockText()
                        },
                        onToggleTime = ::toggleTime,
                        onHour = {
                            hourText = it
                            parseHour(it)?.let { hour ->
                                edited = true
                                time = time?.withHour(hour)
                            }
                        },
                        onMinute = {
                            minuteText = it
                            parseMinute()?.let { minute ->
                                edited = true
                                time = time?.withMinute(minute)
                            }
                        },
                        onTogglePeriod = {
                            edited = true
                            time = time?.plusHours(12)
                        },
                        onClear = {
                            edited = true
                            date = null
                            time = null
                            resetClockText()
                        },
                        onClose = ::dismiss,
                        onApply = ::applyDue
                    )
                }
            }
        }
    }

    private fun toggleTime() {
        edited = true
        if (time == null) {
            date = date ?: LocalDate.now(zone)
            time = LocalTime.now(zone).withSecond(0).withNano(0)
        } else {
            time = null
        }
        resetClockText()
    }

    private fun resetClockText() {
        val clock = time
        hourText = clock?.let {
            if (is24Hour) "%02d".format(Locale.ROOT, it.hour)
            else it.format(java.time.format.DateTimeFormatter.ofPattern("h", Locale.ROOT))
        }.orEmpty()
        minuteText = clock?.let { "%02d".format(Locale.ROOT, it.minute) }.orEmpty()
    }

    private fun parseHour(text: String) = parseDuePickerHour(text, is24Hour, (time?.hour ?: 0) >= 12)

    private fun parseMinute() = minuteText.toIntOrNull()?.takeIf { it in 0..59 }

    private fun resolveTime(): TaskDue.Timed? {
        val day = date ?: return null
        val clock = time ?: return null
        return DuePickerClock.resolve(day, clock, zone, original)
    }

    private fun clockError(): Int? = when {
        time == null -> null
        date != null && resolveTime() == null -> R.string.task_due_dst_gap_error
        parseHour(hourText) == null || parseMinute() == null ->
            if (is24Hour) R.string.task_due_invalid_time_error else R.string.task_due_invalid_time_12h_error
        else -> null
    }

    private fun applyDue() {
        if (!validDataset()) {
            dismiss()
            return
        }
        if (clockError() != null) return
        val day = date
        val due = when {
            !edited -> original
            day == null -> TaskDue.None
            time == null -> TaskDue.Day(TaskDueDate.parse(day.toString()))
            else -> resolveTime() ?: return
        }
        parentFragmentManager.setFragmentResult(
            requireArguments().getString("requestKey")!!,
            encode(due).apply { putBoolean(RESULT_EDITED, edited) }
        )
        dismiss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), R.style.ThemeOverlay_UHabit_TaskPickerBottomSheet)

    override fun onStart() {
        super.onStart()
        if (!validDataset()) { dismiss(); return }
        (dialog as? BottomSheetDialog)?.let { sheet ->
            sheet.window?.let { window ->
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                val palette = MainTabsThemeBridge.resolve(requireContext())
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightNavigationBars = !palette.isDark
            }
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    companion object {
        const val RESULT_EDITED = "dueEdited"
        fun newInstance(requestKey: String, initial: TaskDue) = TaskDuePickerBottomSheet().apply {
            arguments = encode(initial).apply { putString("requestKey", requestKey) }
        }
        fun readResult(bundle: Bundle): TaskDue = when (bundle.getString("dueKind")) {
            "DAY" -> TaskDue.Day(TaskDueDate.parse(bundle.getString("dueDate")!!))
            "TIMED" -> TaskDue.Timed.of(bundle.getLong("dueMillis"), bundle.getString("dueZone"))
            else -> TaskDue.None
        }
        fun encode(due: TaskDue) = Bundle().apply {
            when (due) {
                is TaskDue.None -> putString("dueKind", "NONE")
                is TaskDue.Day -> { putString("dueKind", "DAY"); putString("dueDate", due.date.isoDate) }
                is TaskDue.Timed -> { putString("dueKind", "TIMED"); putLong("dueMillis", due.epochMillis); putString("dueZone", due.originZoneId) }
            }
        }
    }
}

internal fun parseDuePickerHour(text: String, is24Hour: Boolean, isPm: Boolean): Int? {
    val hour = text.toIntOrNull() ?: return null
    if (is24Hour) return hour.takeIf { it in 0..23 }
    if (hour !in 1..12) return null
    return hour % 12 + if (isPm) 12 else 0
}

/** Reject gaps; preserve an existing overlap offset, otherwise use the earlier occurrence. */
internal object DuePickerClock {
    // CalendarView reads milliseconds in the device zone, independently of a
    // restored TIMED draft's resolution zone. DAY must keep its calendar date.
    fun calendarMillis(date: LocalDate, deviceZone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(deviceZone).toInstant().toEpochMilli()

    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId, original: TaskDue = TaskDue.None): TaskDue.Timed? {
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return null
        val previous = (original as? TaskDue.Timed)?.let { Instant.ofEpochMilli(it.epochMillis).atZone(zone).offset }
        val offset = previous?.takeIf { it in offsets } ?: offsets.first()
        return TaskDue.Timed.local(local.toInstant(offset).toEpochMilli(), zone.id)
    }
}
