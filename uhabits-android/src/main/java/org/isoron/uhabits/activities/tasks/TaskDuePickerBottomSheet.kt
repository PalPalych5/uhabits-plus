package org.isoron.uhabits.activities.tasks

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Transactional shared editor: only explicit confirmation emits a result. */
class TaskDuePickerBottomSheet : BottomSheetDialogFragment() {
    private var date: LocalDate? = null
    private var time: LocalTime? = null
    private lateinit var zone: ZoneId
    private var original: TaskDue = TaskDue.None
    private var edited = false
    private var displayMonth: java.time.YearMonth? = null
    private var clockInputValid = true

    private fun currentDataset() = (requireContext().applicationContext as HabitsApplication).component.session.datasetId
    private fun validDataset() = requireArguments().getString("datasetId") == currentDataset()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!requireArguments().containsKey("datasetId")) requireArguments().putString("datasetId", currentDataset())
        original = readResult(requireArguments())
        displayMonth = state?.getString("displayMonth")?.let(java.time.YearMonth::parse)
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
        val p = MainTabsThemeBridge.resolve(context)
        fun dp(v: Int) = TaskThemeUtils.dpInt(context, v.toFloat())
        fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }
        fun label(value: String, size: Float = 15f, medium: Boolean = false, muted: Boolean = false) = TextView(context).apply {
            text = value; textSize = size; includeFontPadding = false
            setTextColor(if (muted) p.onSurfaceVariant else p.onSurface)
            typeface = android.graphics.Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fun frame(height: Int) = android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(height)); clipChildren = false; clipToPadding = false
        }
        fun android.widget.FrameLayout.place(view: View, width: Int, height: Int, left: Int = 0, top: Int = 0, right: Boolean = false) {
            addView(view, android.widget.FrameLayout.LayoutParams(if (width < 0) width else dp(width), dp(height)).apply {
                gravity = android.view.Gravity.TOP or if (right) android.view.Gravity.RIGHT else android.view.Gravity.LEFT
                if (right) rightMargin = dp(left) else leftMargin = dp(left)
                topMargin = dp(top)
            })
        }
        fun icon(resource: Int) = android.widget.ImageView(context).apply {
            setImageResource(resource); imageTintList = android.content.res.ColorStateList.valueOf(p.onSurfaceVariant)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun iconAction(resource: Int, description: String, onClick: () -> Unit) = android.widget.FrameLayout(context).apply {
            isClickable = true; isFocusable = true; contentDescription = description
            addView(icon(resource), android.widget.FrameLayout.LayoutParams(dp(24), dp(24), android.view.Gravity.CENTER))
            setOnClickListener { onClick() }
        }
        val onAccent = if (p.isDark) 0xFF102638.toInt() else android.graphics.Color.WHITE
        fun action(text: String, visibleHeight: Int = 48, primary: Boolean = false, fill: Boolean = false, stroke: Boolean = false, size: Float = 14f, alignStart: Boolean = false, onClick: () -> Unit): View =
            android.widget.FrameLayout(context).apply {
                minimumHeight = dp(48); isClickable = true; isFocusable = true; contentDescription = text
                val textView = label(text, size, primary || fill, !primary && !fill).apply {
                    gravity = if (alignStart) (android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL) else android.view.Gravity.CENTER
                    if (primary) setTextColor(onAccent)
                    if (primary || fill || stroke) background = rounded(if (primary) p.accent else if (fill) p.surfaceVariant else p.surface, if (primary) 10 else 8, if (stroke) p.border else null)
                }
                addView(textView, android.widget.FrameLayout.LayoutParams(-1, dp(visibleHeight), if (alignStart) (android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL) else android.view.Gravity.CENTER))
                setOnClickListener { onClick() }
            }
        fun divider() = View(context).apply { setBackgroundColor(p.divider); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.surface)
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0); clipChildren = false }
        val scroll = NestedScrollView(context).apply { addView(body); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val header = frame(56)
        header.addView(View(context).apply { background = rounded(p.divider, 2) }, android.widget.FrameLayout.LayoutParams(dp(36), dp(4), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        header.place(label("Срок", 22f, true), -1, 40, 0, 16)
        header.place(iconAction(R.drawable.ic_close, "Закрыть") { dismiss() }, 48, 48, -12, 8, true)
        body.addView(header)
        val summary = label("", 17f, true)
        body.addView(summary, LinearLayout.LayoutParams(-1, dp(24)))
        val shortcuts = frame(48); body.addView(shortcuts)
        val monthBar = frame(32); body.addView(monthBar)
        val monthLabel = label("", 16f, true); monthBar.place(monthLabel, -1, 32)
        val calendar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(calendar)
        val timeHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(divider()); body.addView(timeHost)
        val error = label("", 13f, muted = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; setPadding(0, dp(8), 0, dp(8)) }
        body.addView(error)
        val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2)); footer.addView(divider())
        val footerActions = frame(71); footer.addView(footerActions)
        lateinit var refresh: () -> Unit
        fun chooseDay(day: LocalDate) { edited = true; date = day; displayMonth = java.time.YearMonth.from(day); refresh() }
        footerActions.place(action("Очистить", alignStart = true) { edited = true; date = null; time = null; refresh() }, 96, 48, 0, 10)
        val confirm = action("Применить", 44, primary = true) {
            view?.clearFocus()
            if (!validDataset()) { dismiss(); return@action }
            if (!clockInputValid) return@action
            val due = if (!edited) original else date?.let { day ->
                if (time == null) TaskDue.Day(TaskDueDate.parse(day.toString()))
                else DuePickerClock.resolve(day, time!!, zone, original) ?: return@action
            } ?: TaskDue.None
            parentFragmentManager.setFragmentResult(requireArguments().getString("requestKey")!!,
                encode(due).apply { putBoolean(RESULT_EDITED, edited) })
            dismiss()
        }
        footerActions.place(confirm, 132, 48, 0, 10, true)
        var hourInput: android.widget.EditText? = null
        var minuteInput: android.widget.EditText? = null
        var zoneCaption: TextView? = null
        val validateClock = {
            val hour = hourInput?.text?.toString()?.toIntOrNull()
            val minute = minuteInput?.text?.toString()?.toIntOrNull()
            val validInput = time == null || (hour != null && hour in 0..23 && minute != null && minute in 0..59)
            val gap = date != null && time != null && DuePickerClock.resolve(date!!, time!!, zone, original) == null
            clockInputValid = validInput && !gap
            error.text = if (gap) "Это время отсутствует при переходе на летнее время. Выберите другое время." else if (!validInput) "Введите часы 00–23 и минуты 00–59" else ""
            error.visibility = if (clockInputValid) View.GONE else View.VISIBLE
            confirm.isEnabled = clockInputValid; confirm.alpha = if (clockInputValid) 1f else .45f
            val local = date?.let { day -> time?.let { clock -> LocalDateTime.of(day, clock) } }
            val offsets = local?.let { zone.rules.getValidOffsets(it) }.orEmpty()
            val priorOffset = (original as? TaskDue.Timed)?.let { Instant.ofEpochMilli(it.epochMillis).atZone(zone).offset }
            val offset = priorOffset?.takeIf { it in offsets } ?: offsets.firstOrNull()
            val utc = offset?.id?.let { if (it == "Z") "+0" else it.removeSuffix(":00") }
            zoneCaption?.text = zone.id + (utc?.let { " · UTC$it" } ?: "")
        }
        fun timeEditor(value: Int, description: String, max: Int, update: (Int) -> Unit) = android.widget.EditText(context).apply {
            setText("%02d".format(java.util.Locale.ROOT, value)); textSize = 19f; includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setTextColor(p.onSurface); gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 0); background = rounded(p.surfaceVariant, 7)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(2))
            setSingleLine(true); setSelectAllOnFocus(true); contentDescription = description
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val v = s?.toString()?.toIntOrNull()
                    if (v != null && v in 0..max) { edited = true; update(v) }
                    validateClock()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        fun updateTime() {
            timeHost.removeAllViews(); hourInput = null; minuteInput = null; zoneCaption = null
            val timed = time != null
            val row = frame(55); timeHost.addView(row)
            row.place(icon(R.drawable.ic_settings_clock), 20, 20, 0, 18)
            row.place(label(if (timed) "Время" else "Добавить время", 15f, muted = !timed), if (timed) 100 else 240, 48, 32, 4)
            if (!timed) {
                row.place(icon(R.drawable.ic_toolbar_add), 18, 18, 5, 19, true)
                row.isClickable = true; row.isFocusable = true; row.contentDescription = "Добавить время"
                row.setOnClickListener {
                    edited = true; date = date ?: LocalDate.now(zone); time = LocalTime.now(zone).withSecond(0).withNano(0)
                    refresh()
                }
            } else {
                val t = time!!
                hourInput = timeEditor(t.hour, "Часы", 23) { time = time?.withHour(it) }
                minuteInput = timeEditor(t.minute, "Минуты", 59) { time = time?.withMinute(it) }
                // The field is visually 44x32; its wrapper receives a full 48dp touch area.
                fun clockField(input: android.widget.EditText, right: Int) {
                    val target = android.widget.FrameLayout(context).apply {
                        isClickable = true
                        setOnClickListener { input.requestFocus(); (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
                    }
                    target.addView(input, android.widget.FrameLayout.LayoutParams(dp(44), dp(32), android.view.Gravity.CENTER))
                    row.place(target, 48, 48, right, 4, true)
                }
                clockField(hourInput!!, 111); clockField(minuteInput!!, 55)
                row.place(label(":", 18f, muted = true).apply { gravity = android.view.Gravity.CENTER }, 12, 48, 101, 4, true)
                row.place(action("Убрать", size = 12f) { edited = true; time = null; refresh() }, 55, 48, -3, 4, true)
                zoneCaption = label("", 11f, muted = true).apply { setPadding(dp(32), 0, 0, 0) }
                timeHost.addView(zoneCaption, LinearLayout.LayoutParams(-1, dp(24)))
            }
        }
        var wantedHeight = 0
        var bottomInset = 0
        fun resize() {
            val available = resources.displayMetrics.heightPixels - dp(32)
            val targetHeight = minOf(wantedHeight + bottomInset, available)
            if (root.layoutParams == null) root.layoutParams = ViewGroup.LayoutParams(-1, targetHeight)
            else root.layoutParams = root.layoutParams.apply { height = targetHeight }
        }
        refresh = {
            val locale = TaskDueDisplayFormatter.selectedLocale(requireContext())
            summary.text = date?.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))?.replaceFirstChar { it.titlecase(locale) } ?: "Без срока"
            shortcuts.removeAllViews()
            val today = LocalDate.now(zone)
            shortcuts.place(action("Сегодня", 28, fill = date == today, stroke = date != today) { chooseDay(today) }, 84, 48, 0, -6)
            shortcuts.place(action("Завтра", 28, fill = date == today.plusDays(1), stroke = date != today.plusDays(1)) { chooseDay(today.plusDays(1)) }, 82, 48, 92, -6)
            val month = displayMonth ?: java.time.YearMonth.from(date ?: today)
            displayMonth = month
            monthLabel.text = month.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
            calendar.removeAllViews()
            val weekdays = LinearLayout(context)
            for (day in 1..7) {
                val dayName = java.time.DayOfWeek.of(day)
                    .getDisplayName(java.time.format.TextStyle.SHORT, locale)
                    .trimEnd('.')
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
                weekdays.addView(
                    label(dayName, 11f, muted = true).apply { gravity = android.view.Gravity.CENTER },
                    LinearLayout.LayoutParams(0, dp(24), 1f)
                )
            }
            calendar.addView(weekdays)
            val offset = month.atDay(1).dayOfWeek.value - 1
            val rows = (offset + month.lengthOfMonth() + 6) / 7
            for (week in 0 until rows) {
                val row = LinearLayout(context)
                for (col in 0..6) {
                    val number = week * 7 + col - offset + 1
                    val cell = android.widget.FrameLayout(context)
                    if (number in 1..month.lengthOfMonth()) {
                        val day = month.atDay(number)
                        val selected = day == date
                        val numberView = label(number.toString(), 15f, selected || day == today).apply {
                            gravity = android.view.Gravity.CENTER
                            if (selected) { background = rounded(p.accent, 17); setTextColor(onAccent) }
                        }
                        cell.addView(numberView, android.widget.FrameLayout.LayoutParams(dp(34), dp(34), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(-1) })
                        if (day == today && !selected) cell.addView(View(context).apply { background = rounded(p.onSurfaceVariant, 2) }, android.widget.FrameLayout.LayoutParams(dp(3), dp(3), android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM).apply { bottomMargin = dp(15) })
                        cell.isClickable = true; cell.isFocusable = true; cell.isSelected = selected
                        cell.contentDescription = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)) + if (selected) ", выбрано" else ""
                        cell.setOnClickListener { chooseDay(day) }
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                calendar.addView(row)
            }
            updateTime(); validateClock()
            wantedHeight = dp(56 + 24 + 48 + 32 + 24 + rows * 48 + (if (time == null) 56 else 80) + 72)
            resize()
        }
        monthBar.place(iconAction(R.drawable.ic_chevron_left, "Предыдущий месяц") { displayMonth = displayMonth!!.minusMonths(1); refresh() }, 48, 48, 39, -8, true)
        monthBar.place(iconAction(R.drawable.ic_chevron_right, "Следующий месяц") { displayMonth = displayMonth!!.plusMonths(1); refresh() }, 48, 48, -12, -8, true)
        refresh()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            bottomInset = maxOf(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            view.setPadding(0, 0, 0, bottomInset)
            resize()
            insets
        }
        return root
    }

    override fun onStart() {
        super.onStart()
        if (!validDataset()) { dismiss(); return }
        (dialog as? BottomSheetDialog)?.let { sheet ->
            sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.resolve(requireContext()).surface)
                val radius = TaskThemeUtils.dp(requireContext(), 24f)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
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
