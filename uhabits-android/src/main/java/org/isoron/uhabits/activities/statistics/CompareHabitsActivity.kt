package org.isoron.uhabits.activities.statistics

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.views.FrequencyChart
import org.isoron.uhabits.activities.common.views.StreakChart
import org.isoron.uhabits.activities.statistics.views.CompareHabitsMultiLineChartView
import org.isoron.uhabits.activities.statistics.views.CompareCalendarView
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsReportStateBuilder
import org.isoron.uhabits.core.ui.screens.habits.show.views.FrequencyCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.OverviewCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCartPresenter
import org.isoron.uhabits.databinding.ActivityCompareHabitsBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dedicated full-screen analysis activity for comparing 2 to 4 habits:
 * - Deterministic, guaranteed distinct series palette
 * - Configurable comparison period (Week, Month, 3 Months, Year, All Time)
 * - Section 1: Overview (Loop score, month/year changes, total completions)
 * - Section 2: Stability (Multi-series Loop Score chart with interactive legend & scrub inspection)
 * - Section 3: Performance (Normalized History chart toward own target)
 * - Section 4: Calendar (Small multiples with identical time windows)
 * - Section 5: Streaks (Latest & best streaks using horizontal Loop streak bars)
 * - Section 6: Frequency (Small multiples weekday frequency where data permits)
 */
class CompareHabitsActivity : AppCompatActivity() {

    companion object {
        val SERIES_COLORS = intArrayOf(
            Color.parseColor("#00BFA5"), // Emerald / Teal
            Color.parseColor("#FF6E40"), // Coral / Orange
            Color.parseColor("#7C4DFF"), // Deep Purple
            Color.parseColor("#FFB300")  // Amber
        )
    }

    enum class ComparePeriod(val days: Int) {
        WEEK(7),
        MONTH(30),
        THREE_MONTHS(90),
        YEAR(365),
        ALL(-1)
    }

    private lateinit var binding: ActivityCompareHabitsBinding
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private val dateFormatter = JavaLocalDateFormatter(Locale.getDefault())

    private val selectedHabits = mutableListOf<Habit>()
    private var availableHabits: List<Habit> = emptyList()
    private var currentPeriod = ComparePeriod.MONTH

    private val component get() = (application as HabitsApplication).component

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        binding = ActivityCompareHabitsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        parseInitialIntent()
        setupPeriodTabs()
        binding.addHabitButton.setOnClickListener { showHabitPickerDialog() }
        renderHabitChips()
        rebuildComparison()
        if (selectedHabits.isEmpty() && savedInstanceState == null) {
            binding.root.post { showHabitPickerDialog() }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun parseInitialIntent() {
        val allowed = intent.getLongArrayExtra("allowed_habit_ids")?.toSet()
        val allActive = component.habitList.filter {
            !it.isArchived && (allowed == null || it.id?.let(allowed::contains) == true)
        }
        availableHabits = allActive
        val habitIds = intent.getLongArrayExtra("initial_habit_ids")
        if (habitIds != null && habitIds.isNotEmpty()) {
            habitIds.toList().mapNotNull { id -> allActive.firstOrNull { it.id == id } }
                .take(4)
                .forEach { selectedHabits.add(it) }
        }

        val initialPeriodStr = intent.getStringExtra("initial_period")
        currentPeriod = when (initialPeriodStr) {
            "WEEK" -> ComparePeriod.WEEK
            "MONTH" -> ComparePeriod.MONTH
            "3MONTHS", "THREE_MONTHS" -> ComparePeriod.THREE_MONTHS
            "YEAR" -> ComparePeriod.YEAR
            "ALL" -> ComparePeriod.ALL
            else -> ComparePeriod.MONTH
        }
    }

    private fun setupPeriodTabs() {
        val periods = listOf(
            ComparePeriod.WEEK to getString(R.string.compare_period_week),
            ComparePeriod.MONTH to getString(R.string.compare_period_month),
            ComparePeriod.THREE_MONTHS to getString(R.string.compare_period_3months),
            ComparePeriod.YEAR to getString(R.string.compare_period_year),
            ComparePeriod.ALL to getString(R.string.compare_period_all)
        )

        binding.periodTabLayout.removeAllTabs()
        periods.forEach { (period, title) ->
            val tab = binding.periodTabLayout.newTab().setText(title).setTag(period)
            binding.periodTabLayout.addTab(tab, period == currentPeriod)
        }

        binding.periodTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val period = tab?.tag as? ComparePeriod ?: return
                if (period != currentPeriod) {
                    currentPeriod = period
                    rebuildComparison()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun renderHabitChips(scrollToEnd: Boolean = false) {
        binding.habitChipsContainer.removeAllViews()

        selectedHabits.forEachIndexed { index, habit ->
            val seriesColor = SERIES_COLORS[index % SERIES_COLORS.size]

            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(16f)
                    setColor(ColorUtils.setAlphaComponent(seriesColor, 35))
                    setStroke(dp(1.2f).toInt(), seriesColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32f).toInt()
                ).apply {
                    marginEnd = dp(8f).toInt()
                }
            }

            // Series indicator dot
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply {
                    marginEnd = dp(6f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(seriesColor)
                }
            }
            chip.addView(dot)

            // Habit name
            val nameView = TextView(this).apply {
                text = habit.name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(R.attr.contrast100))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            chip.addView(nameView)

            // Remove 'x' button
            val removeBtn = TextView(this).apply {
                text = "✕"
                textSize = 11f
                setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
                setTextColor(themeColor(R.attr.contrast60))
                setOnClickListener {
                    selectedHabits.remove(habit)
                    renderHabitChips()
                    rebuildComparison()
                }
            }
            chip.addView(removeBtn)

            binding.habitChipsContainer.addView(chip)
        }

        binding.addHabitButton.contentDescription = getString(R.string.compare_habits_add)
        binding.addHabitButton.visibility = View.VISIBLE
        binding.habitChipsScroll.post {
            val target = if (scrollToEnd) binding.habitChipsContainer.width else 0
            binding.habitChipsScroll.smoothScrollTo(target, 0)
        }
    }

    private fun showHabitPickerDialog() {
        val habits = availableHabits
        val blocks = component.habitList.getBlocks().associateBy { it.id }
        val pending = selectedHabits.toMutableList()
        val dialog = Dialog(this)
        val surface = themeColor(R.attr.cardBackgroundColor)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(8f).toInt())
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.statistics_compare_habits)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(R.attr.contrast100))
        })
        val search = EditText(this).apply {
            hint = getString(R.string.compare_picker_search)
            textSize = 15f
            isSingleLine = true
            setTextColor(themeColor(R.attr.contrast100))
            setHintTextColor(themeColor(R.attr.contrast60))
            setPadding(0, dp(12f).toInt(), 0, dp(12f).toInt())
        }
        root.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val cancel = TextView(this).apply {
            text = getString(android.R.string.cancel)
            setTextColor(themeColor(R.attr.contrast80))
            gravity = Gravity.CENTER
            minHeight = dp(48f).toInt()
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            setOnClickListener { dialog.dismiss() }
        }
        val confirm = TextView(this).apply {
            setTextColor(themeColor(R.attr.colorAccent))
            gravity = Gravity.CENTER
            minHeight = dp(48f).toInt()
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            setOnClickListener {
                selectedHabits.clear()
                selectedHabits.addAll(pending)
                dialog.dismiss()
                renderHabitChips(scrollToEnd = true)
                rebuildComparison()
            }
        }
        fun updateAction() {
            confirm.text = getString(R.string.compare_picker_action, pending.size)
            confirm.isEnabled = pending.size in 2..4
            confirm.alpha = if (confirm.isEnabled) 1f else 0.4f
        }
        footer.addView(cancel)
        footer.addView(confirm)
        root.addView(footer)
        fun renderList(query: String) {
            list.removeAllViews()
            habits.filter { it.name.contains(query, ignoreCase = true) }
                .groupBy { it.blockId }
                .forEach { (blockId, members) ->
                    val block = blocks[blockId]
                    list.addView(TextView(this).apply {
                        text = block?.name ?: getString(R.string.compare_picker_other_sphere)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(block?.let { themeSwitcher.currentTheme.color(it.color).toInt() }
                            ?: themeColor(R.attr.contrast60))
                        setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
                    })
                    members.forEach { habit ->
                        val row = LinearLayout(this).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(48f).toInt()
                            orientation = LinearLayout.HORIZONTAL
                        }
                        val indicator = TextView(this).apply {
                            textSize = 20f
                            gravity = Gravity.CENTER
                            setTextColor(themeSwitcher.currentTheme.color(habit.color).toInt())
                            layoutParams = LinearLayout.LayoutParams(dp(36f).toInt(), dp(40f).toInt())
                        }
                        fun refreshIndicator() { indicator.text = if (habit in pending) "●" else "○" }
                        refreshIndicator()
                        row.addView(indicator)
                        row.addView(TextView(this).apply {
                            text = habit.name
                            textSize = 15f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(themeColor(R.attr.contrast100))
                        })
                        row.setOnClickListener {
                            if (habit in pending) pending.remove(habit)
                            else if (pending.size < 4) pending.add(habit)
                            refreshIndicator()
                            updateAction()
                        }
                        list.addView(row)
                    }
                }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderList(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        updateAction()
        renderList("")
        dialog.setContentView(root)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt())
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt())
    }

    private fun getStartDateForPeriod(today: LocalDate): LocalDate {
        return when (currentPeriod) {
            ComparePeriod.WEEK -> today.minus(6)
            ComparePeriod.MONTH -> today.minus(29)
            ComparePeriod.THREE_MONTHS -> today.minus(89)
            ComparePeriod.YEAR -> today.minus(364)
            ComparePeriod.ALL -> {
                selectedHabits.map { StatisticsReportStateBuilder.getHabitStartDate(it, today) }.minOrNull()
                    ?: today
            }
        }
    }

    private fun rebuildComparison() {
        binding.compareContentContainer.removeAllViews()

        if (selectedHabits.size < 2) {
            val warning = TextView(this).apply {
                text = getString(R.string.compare_habits_min_warning)
                gravity = Gravity.CENTER
                textSize = 15f
                setPadding(dp(24f).toInt(), dp(48f).toInt(), dp(24f).toInt(), dp(24f).toInt())
                setTextColor(themeColor(R.attr.contrast60))
            }
            binding.compareContentContainer.addView(warning)
            return
        }

        val today = getToday()
        val startDate = getStartDateForPeriod(today)

        // 1. Overview Card
        addOverviewCard()

        // 2. Stability Chart Card (Multi-series Loop Score)
        addStabilityChartCard(startDate, today)

        // 3. Execution / Performance Chart Card (Normalized progress toward own target)
        addExecutionChartCard(startDate, today)

        // 4. Calendar Small Multiples
        addCalendarSmallMultiplesCard(startDate, today)

        // 5. Streaks Comparison Card
        addStreaksComparisonCard()

        // 6. Frequency Comparison Card
        addFrequencyComparisonCard()
    }

    // -------------------------------------------------------------
    // SECTION 1: ОБЗОР (Overview)
    // -------------------------------------------------------------
    private fun addOverviewCard() {
        val (card, container) = createCard(getString(R.string.compare_overview))
        val theme = themeSwitcher.currentTheme

        selectedHabits.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]
            val state = OverviewCardPresenter.buildState(habit, theme)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6f).toInt(), 0, dp(10f).toInt())
            }

            // One hue identifies each comparison series throughout the screen.
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply {
                    marginEnd = dp(8f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(seriesColor)
                }
            })
            headerRow.addView(TextView(this).apply {
                text = habit.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(R.attr.contrast100))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(headerRow)

            // Metrics row: Stability, Month change, Year change, Total count
            val metricsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16f).toInt(), dp(4f).toInt(), 0, 0)
            }
            fun addMetric(label: String, value: String, isAccent: Boolean = false) {
                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                block.addView(TextView(this).apply {
                    text = label
                    textSize = 10f
                    setTextColor(themeColor(R.attr.contrast60))
                })
                block.addView(TextView(this).apply {
                    text = value
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isAccent) themeColor(R.attr.colorAccent) else themeColor(R.attr.contrast100))
                })
                metricsRow.addView(block)
            }

            val knownEntries = habit.originalEntries.getKnown().filter { entry ->
                val start = habit.effectiveStatisticsStartDate()
                (start == null || entry.date >= start) && entry.date <= getToday() && entry.value != Entry.SKIP
            }
            addMetric(getString(R.string.compare_stat_stability),
                if (knownEntries.isEmpty()) "—" else "${(state.scoreToday * 100).roundToInt()}%", true)
            val monthDelta = (state.scoreMonthDiff * 100).roundToInt()
            addMetric(getString(R.string.compare_stat_month),
                if (knownEntries.any { it.date <= getToday().minus(30) })
                    "${if (monthDelta > 0) "+" else ""}$monthDelta п.п." else "—")
            val yearDelta = (state.scoreYearDiff * 100).roundToInt()
            addMetric(getString(R.string.compare_stat_year),
                if (knownEntries.any { it.date <= getToday().minus(365) })
                    "${if (yearDelta > 0) "+" else ""}$yearDelta п.п." else "—")
            if (habit.isNumerical) {
                addMetric(getString(R.string.compare_stat_observations),
                    knownEntries.count { it.value != Entry.SKIP }.toString())
            } else {
                addMetric(getString(R.string.compare_stat_completed),
                    knownEntries.count { it.value == Entry.YES_MANUAL || it.value == Entry.YES_AUTO }.toString())
            }

            row.addView(metricsRow)
            container.addView(row)

            if (i < selectedHabits.lastIndex) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(0.75f).toInt()
                    ).apply {
                        topMargin = dp(4f).toInt()
                        bottomMargin = dp(4f).toInt()
                    }
                    setBackgroundColor(ColorUtils.setAlphaComponent(themeColor(R.attr.contrast20), 80))
                })
            }
        }
        binding.compareContentContainer.addView(card)
    }

    // -------------------------------------------------------------
    // SECTION 2: УСТОЙЧИВОСТЬ (Loop Score multi-line chart)
    // -------------------------------------------------------------
    private fun addStabilityChartCard(startDate: LocalDate, today: LocalDate) {
        val (card, container) = createCard(
            getString(R.string.compare_stability),
            getString(R.string.compare_stability_loop_score)
        )

        val series = selectedHabits.mapIndexed { i, habit ->
            val color = SERIES_COLORS[i % SERIES_COLORS.size]
            val habitStart = habit.effectiveStatisticsStartDate()?.let { maxOf(startDate, it) } ?: startDate
            val scores = habit.scores.getByInterval(habitStart, today).map {
                Pair(it.date, it.value)
            }
            CompareHabitsMultiLineChartView.SeriesItem(habit.id ?: 0L, habit.name, color, scores)
        }

        val chart = CompareHabitsMultiLineChartView(this).apply {
            setData(
                series = series,
                rangeStart = startDate,
                rangeEnd = today,
                surfaceColor = themeColor(R.attr.cardBackgroundColor),
                onSurfaceColor = themeColor(R.attr.contrast100),
                onSurfaceVariantColor = themeColor(R.attr.contrast60),
                dividerColor = themeColor(R.attr.contrast20)
            )
        }
        container.addView(chart)

        // Interactive legend row (tapping toggles series line visibility)
        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8f).toInt(), 0, 0)
        }
        series.forEach { s ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f)
                    setColor(Color.TRANSPARENT)
                }
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply {
                    marginEnd = dp(5f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(s.color)
                }
            }
            val label = TextView(this).apply {
                text = s.label
                textSize = 11f
                setTextColor(themeColor(R.attr.contrast80))
            }
            item.addView(dot)
            item.addView(label)
            item.setOnClickListener {
                chart.toggleSeriesVisibility(s.habitId)
                label.alpha = if (s.isVisible) 1.0f else 0.4f
                dot.alpha = if (s.isVisible) 1.0f else 0.4f
            }
            legendRow.addView(item)
        }
        container.addView(legendRow)
        binding.compareContentContainer.addView(card)
    }

    // -------------------------------------------------------------
    // SECTION 3: ВЫПОЛНЕНИЕ / HISTORY (Normalized target completion)
    // -------------------------------------------------------------
    private fun addExecutionChartCard(startDate: LocalDate, today: LocalDate) {
        val (card, container) = createCard(getString(R.string.compare_execution))

        // Normalized progress toward habit's own target: 0-100%
        val series = selectedHabits.mapIndexed { i, habit ->
            val color = SERIES_COLORS[i % SERIES_COLORS.size]
            val points = mutableListOf<Pair<LocalDate, Double>>()
            var curr = habit.effectiveStatisticsStartDate()?.let { maxOf(startDate, it) } ?: startDate
            val goal = habit.goalAt(today)

            while (curr <= today) {
                val entry = habit.computedEntries.get(curr)
                val value = entry.value
                val normProgress = when {
                    value == Entry.SKIP || value == Entry.UNKNOWN -> null
                    habit.isNumerical -> {
                        val target = goal.targetValue.coerceAtLeast(1.0)
                        (value / 1000.0 / target).coerceIn(0.0, 1.0)
                    }
                    else -> {
                        if (value == Entry.YES_MANUAL || value == Entry.YES_AUTO) 1.0 else 0.0
                    }
                }
                if (normProgress != null) {
                    points.add(Pair(curr, normProgress))
                }
                curr = curr.plus(1)
            }
            CompareHabitsMultiLineChartView.SeriesItem(habit.id ?: 0L, habit.name, color, points)
        }

        val chart = CompareHabitsMultiLineChartView(this).apply {
            setData(
                series = series,
                rangeStart = startDate,
                rangeEnd = today,
                surfaceColor = themeColor(R.attr.cardBackgroundColor),
                onSurfaceColor = themeColor(R.attr.contrast100),
                onSurfaceVariantColor = themeColor(R.attr.contrast60),
                dividerColor = themeColor(R.attr.contrast20)
            )
        }
        container.addView(chart)
        binding.compareContentContainer.addView(card)
    }

    // -------------------------------------------------------------
    // SECTION 4: КАЛЕНДАРЬ (Small Multiples)
    // -------------------------------------------------------------
    private fun addCalendarSmallMultiplesCard(startDate: LocalDate, today: LocalDate) {
        val (card, container) = createCard(getString(R.string.compare_calendar))
        val firstWeekday = component.preferences.firstWeekday
        val calendarStart = if (currentPeriod == ComparePeriod.MONTH) today.startOfMonth() else startDate
        val calendarEnd = if (currentPeriod == ComparePeriod.MONTH)
            calendarStart.plus(calendarStart.monthLength - 1) else today
        val calendars = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        selectedHabits.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]

            val habitSection = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
            }

            // Header
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(4f).toInt())
            }
            header.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply {
                    marginEnd = dp(6f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(seriesColor)
                }
            })
            header.addView(TextView(this).apply {
                text = habit.name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(R.attr.contrast100))
            })
            habitSection.addView(header)

            habitSection.addView(CompareCalendarView(this).apply {
                setData(habit, calendarStart, calendarEnd, today,
                    if (currentPeriod == ComparePeriod.WEEK) calendarStart.dayOfWeek else firstWeekday,
                    currentPeriod == ComparePeriod.MONTH || currentPeriod == ComparePeriod.WEEK,
                    seriesColor, themeColor(R.attr.contrast20), themeColor(R.attr.contrast60))
            })
            calendars.addView(habitSection)
        }
        container.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(calendars)
        })
        binding.compareContentContainer.addView(card)
    }

    // -------------------------------------------------------------
    // SECTION 5: СЕРИИ (Streaks Comparison)
    // -------------------------------------------------------------
    private fun addStreaksComparisonCard() {
        val (card, container) = createCard(getString(R.string.compare_streaks))
        val theme = themeSwitcher.currentTheme

        val allStreaks = selectedHabits.flatMap { h ->
            val st = StreakCartPresenter.buildState(h, theme)
            listOfNotNull(st.latestStreak) + st.bestStreaks
        }
        val sharedMax = allStreaks.maxOfOrNull { it.length }?.toLong() ?: 1L

        selectedHabits.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]
            val streakState = StreakCartPresenter.buildState(habit, theme)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(2f).toInt())
            }
            header.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply {
                    marginEnd = dp(6f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(seriesColor)
                }
            })
            header.addView(TextView(this).apply {
                text = habit.name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(R.attr.contrast100))
            })
            row.addView(header)

            fun addStreak(label: Int, streaks: List<org.isoron.uhabits.core.models.Streak>, alpha: Int) {
                if (streaks.isEmpty()) return
                row.addView(TextView(this).apply {
                    text = getString(label)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(themeColor(R.attr.contrast80))
                    setPadding(0, dp(8f).toInt(), 0, 0)
                })
                row.addView(StreakChart(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f).toInt())
                    setColor(ColorUtils.setAlphaComponent(seriesColor, alpha))
                    setMaxLengthOverride(sharedMax)
                    setStreaks(streaks)
                })
            }
            addStreak(R.string.compare_current_streak, listOfNotNull(streakState.latestStreak), 120)
            addStreak(R.string.compare_best_streak, streakState.bestStreaks.take(1), 255)

            container.addView(row)
        }
        binding.compareContentContainer.addView(card)
    }

    // -------------------------------------------------------------
    // SECTION 6: ЧАСТОТА (Frequency Comparison)
    // -------------------------------------------------------------
    private fun addFrequencyComparisonCard() {
        val theme = themeSwitcher.currentTheme
        val firstWeekday = component.preferences.firstWeekday

        val habitsWithHistory = selectedHabits.filter {
            val from = it.effectiveStatisticsStartDate()
            from == null || from.daysUntil(getToday()) >= 14
        }
        if (habitsWithHistory.isEmpty()) return

        val (card, container) = createCard(getString(R.string.compare_frequency))

        habitsWithHistory.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[selectedHabits.indexOf(habit) % SERIES_COLORS.size]
            val freqState = FrequencyCardPresenter.buildState(habit, firstWeekday, theme)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(4f).toInt())
            }
            header.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply {
                    marginEnd = dp(6f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(seriesColor)
                }
            })
            header.addView(TextView(this).apply {
                text = habit.name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(R.attr.contrast100))
            })
            row.addView(header)

            val chart = FrequencyChart(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48f).toInt()
                )
                setColor(seriesColor)
                setFirstWeekday(firstWeekday)
                setIsNumerical(habit.isNumerical)
                setFrequency(freqState.frequency)
            }
            row.addView(chart)
            container.addView(row)
        }
        binding.compareContentContainer.addView(card)
    }

    // -------------------------------------------------------------
    // HELPER: Card Container Builder
    // -------------------------------------------------------------
    private fun createCard(titleText: String, subtitleText: String? = null): Pair<LinearLayout, LinearLayout> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(R.attr.cardBackgroundColor))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8f).toInt()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f).toInt(), dp(18f).toInt(), dp(20f).toInt(), dp(18f).toInt())
        }

        val titleView = TextView(this).apply {
            text = titleText
            textSize = 16f
            setTextColor(themeColor(R.attr.contrast100))
        }
        content.addView(titleView)

        subtitleText?.let {
            val subView = TextView(this).apply {
                text = it
                textSize = 11f
                setTextColor(themeColor(R.attr.contrast60))
                setPadding(0, dp(2f).toInt(), 0, dp(8f).toInt())
            }
            content.addView(subView)
        }

        card.addView(content)
        return Pair(card, content)
    }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        val color = a.getColor(0, Color.GRAY)
        a.recycle()
        return color
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
