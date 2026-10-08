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
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.common.views.FrequencyChart
import org.isoron.uhabits.activities.common.views.StreakChart
import org.isoron.uhabits.activities.statistics.views.CompareCalendarView
import org.isoron.uhabits.activities.statistics.views.CompareHabitsMultiLineChartView
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException
import org.isoron.uhabits.core.models.projection.HabitProjectionIntegrityException
import org.isoron.uhabits.core.ui.screens.statistics.CompareHabitsPresenter
import org.isoron.uhabits.core.ui.screens.statistics.ComparePeriod
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsReportStateBuilder
import org.isoron.uhabits.databinding.ActivityCompareHabitsBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dedicated full-screen analysis activity for comparing 2 to 4 habits:
 * - Deterministic, guaranteed distinct series palette
 * - Configurable comparison period (Week, Month, 3 Months, Year, All Time)
 * - Section 1: Overview (Loop score, period changes, period completion, streak)
 * - Section 2: Stability (Multi-series Loop Score chart with interactive legend & scrub inspection)
 * - Section 3: Performance (Normalized History chart toward own target)
 * - Section 4: Calendar (Small multiples with identical time windows)
 * - Section 5: Streaks (Latest & record streaks using horizontal Loop streak bars)
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

    private lateinit var binding: ActivityCompareHabitsBinding
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private val dateFormatter = JavaLocalDateFormatter(Locale.getDefault())

    private val selectedHabits = mutableListOf<Habit>()
    private var availableHabits: List<Habit> = emptyList()
    private var currentPeriod = ComparePeriod.MONTH
    private var effectiveRevisionListener: (() -> Unit)? = null
    private var observedRevision: EffectiveReadRevision? = null
    internal var renderedSnapshots: Map<HabitRef, EffectiveHabitReadSnapshot> = emptyMap()
        private set
    internal var renderedRange: Pair<LocalDate, LocalDate>? = null
        private set

    private val component get() = (application as HabitsApplication).component
    private val palette get() = MainTabsThemeBridge.resolve(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        binding = ActivityCompareHabitsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundColor(palette.background)
        (binding.toolbar.parent as View).setBackgroundColor(palette.surface)
        binding.toolbar.setBackgroundColor(palette.surface)
        binding.periodTabLayout.setBackgroundColor(palette.surface)
        binding.habitChipsScroll.setBackgroundColor(palette.surface)

        setupToolbar()
        parseInitialIntent(savedInstanceState)
        setupPeriodTabs()
        binding.addHabitButton.setOnClickListener { showHabitPickerDialog() }
        renderHabitChips()
        rebuildComparison()
        if (selectedHabits.isEmpty() && savedInstanceState == null) {
            binding.root.post { showHabitPickerDialog() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("selected_habit_ids", selectedHabits.mapNotNull { it.id }.toLongArray())
        outState.putString("comparison_period", currentPeriod.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val revision = component.effectiveReadRevision
        observedRevision = revision
        effectiveRevisionListener = {
            runOnUiThread {
                if (observedRevision === revision && !isFinishing && !isDestroyed) rebuildComparison()
            }
        }.also { listener ->
            revision.addListener(listener)
        }
        // Refresh changes made while this Activity was paused, including calendar preferences.
        rebuildComparison()
    }

    override fun onPause() {
        super.onPause()
        effectiveRevisionListener?.let { observedRevision?.removeListener(it) }
        observedRevision = null
        effectiveRevisionListener = null
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun parseInitialIntent(savedInstanceState: Bundle? = null) {
        val allowed = intent.getLongArrayExtra("allowed_habit_ids")?.toSet()
        val allActive = component.habitList.filter {
            !it.isArchived && (allowed == null || it.id?.let(allowed::contains) == true)
        }
        availableHabits = allActive
        val habitIds = (savedInstanceState?.getLongArray("selected_habit_ids")
            ?: intent.getLongArrayExtra("initial_habit_ids"))?.toList()
        if (habitIds != null && habitIds.isNotEmpty()) {
            habitIds.distinct().mapNotNull { id -> allActive.firstOrNull { it.id == id } }
                .take(4)
                .forEach { selectedHabits.add(it) }
        }

        val initialPeriodStr = savedInstanceState?.getString("comparison_period")
            ?: intent.getStringExtra("initial_period")
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
                    binding.compareContentContainer.animate().cancel()
                    binding.compareContentContainer.alpha = 0f
                    rebuildComparison()
                    binding.compareContentContainer.animate()
                        .alpha(1f)
                        .setDuration(160L)
                        .start()
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
                setPadding(dp(8f).toInt(), dp(2f).toInt(), dp(6f).toInt(), dp(2f).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f)
                    setColor(ColorUtils.setAlphaComponent(seriesColor, 35))
                    setStroke(dp(1.2f).toInt(), seriesColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(28f).toInt()
                ).apply {
                    marginEnd = dp(6f).toInt()
                }
            }

            // Series indicator dot
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6f).toInt(), dp(6f).toInt()).apply {
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
        binding.addHabitButton.visibility = if (selectedHabits.size < 4) View.VISIBLE else View.GONE
        binding.habitChipsScroll.post {
            val target = if (scrollToEnd) binding.habitChipsContainer.width else 0
            binding.habitChipsScroll.smoothScrollTo(target, 0)
        }
    }

    private fun showHabitPickerDialog() {
        val habits = availableHabits
        val blocks = component.habitList.getBlocks().associateBy { it.id }
        val spheresEnabled = component.preferences.isHabitSpheresEnabled
        val pending = selectedHabits.toMutableList()
        val dialog = Dialog(this)
        val surface = palette.surface
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

        fun renderHabitRow(habit: Habit) {
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

        fun renderList(query: String) {
            list.removeAllViews()
            val filtered = habits.filter { it.name.contains(query, ignoreCase = true) }
            if (spheresEnabled) {
                filtered.groupBy { it.blockId }.forEach { (blockId, members) ->
                    val block = blocks[blockId]
                    list.addView(TextView(this).apply {
                        text = block?.name ?: getString(R.string.compare_picker_other_sphere)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(block?.let { themeSwitcher.currentTheme.color(it.color).toInt() }
                            ?: themeColor(R.attr.contrast60))
                        setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
                    })
                    members.forEach { habit -> renderHabitRow(habit) }
                }
            } else {
                filtered.sortedBy { it.name.lowercase() }.forEach { habit ->
                    renderHabitRow(habit)
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

    private fun rebuildComparison() {
        binding.compareContentContainer.removeAllViews()
        renderedSnapshots = emptyMap()
        renderedRange = null

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
        val queryFrom = CompareHabitsPresenter.getQueryStartDate(today, currentPeriod)
        val snapshots = try {
            component.effectiveHabitReads?.snapshots(selectedHabits, queryFrom, today)
        } catch (_: HabitProjectionIntegrityException) {
            binding.compareContentContainer.addView(unavailableObservation())
            return
        } catch (_: EffectiveReadInvalidatedException) {
            binding.compareContentContainer.addView(unavailableObservation())
            return
        }
        val startDate = CompareHabitsPresenter.getStartDateForPeriod(selectedHabits, today, currentPeriod, snapshots)
        renderedSnapshots = snapshots.orEmpty()
        renderedRange = startDate to today

        // 1. Overview Card
        addOverviewCard(startDate, today, snapshots)

        // 2. Stability Chart Card (Multi-series Loop Score)
        addStabilityChartCard(startDate, today, snapshots)

        // 3. Execution / Performance Chart Card (Normalized progress toward own target)
        addExecutionChartCard(startDate, today, snapshots)

        // 4. Calendar Small Multiples
        addCalendarSmallMultiplesCard(startDate, today, snapshots)

        // 5. Streaks Comparison Card
        addStreaksComparisonCard(snapshots)

        // 6. Frequency Comparison Card
        addFrequencyComparisonCard(snapshots)
    }

    private fun unavailableObservation() = TextView(this).apply {
        text = getString(R.string.effective_read_unavailable)
        textSize = 12f
        setTextColor(themeColor(R.attr.contrast60))
        setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
    }

    private fun formatQuantity(value: Double) = java.text.DecimalFormat("0.##").format(value)

    // -------------------------------------------------------------
    // SECTION 1: ОБЗОР (Overview)
    // -------------------------------------------------------------
    private fun addOverviewCard(
        startDate: LocalDate,
        today: LocalDate,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
    ) {
        val (card, container) = createCard(getString(R.string.compare_overview))

        selectedHabits.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val metricState = CompareHabitsPresenter.buildOverviewMetrics(habit, startDate, today, currentPeriod, snapshot)

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

            // Metrics row: Stability, Period change, Period completion, Streak
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

            // 1. Stability (Loop score on today)
            addMetric(
                getString(R.string.compare_stat_stability),
                metricState.scoreToday?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                isAccent = true
            )

            // 2. Period Change (delta to start of current comparison period)
            val delta = metricState.periodDelta?.let { (it * 100).roundToInt() }
            addMetric(
                getString(R.string.compare_stat_change),
                if (delta != null) "${if (delta > 0) "+" else ""}$delta п.п." else "—"
            )

            // 3. Period Completion (%)
            val sliceProg = metricState.periodCompletion
            addMetric(
                getString(R.string.compare_stat_completion),
                if (sliceProg != null) "${(sliceProg * 100).roundToInt()}%" else "—"
            )

            // 4. Streak
            val currStreak = metricState.streakLength ?: 0
            addMetric(
                getString(R.string.compare_stat_streak),
                if (currStreak > 0) "$currStreak" else "—"
            )

            row.addView(metricsRow)
            if (habit.isNumerical && snapshot?.hasContributions == true) {
                val goal = try {
                    snapshot.goalAchievementOn(today).progress
                } catch (_: HabitProjectionIntegrityException) { null }
                row.addView(TextView(this).apply {
                    tag = "compare_goal_${habit.id}"
                    text = if (goal == null) getString(R.string.effective_read_unavailable) else {
                        val prefix = if (goal.value.hasCompleteCoverage) "" else "≥"
                        val actual = goal.value.totalValue?.let { formatQuantity(it / 1000.0) } ?: "—"
                        "$prefix$actual / ${formatQuantity(goal.goal.targetValue)} ${goal.goal.unit}"
                    }
                    contentDescription = text
                    textSize = 12f
                    setTextColor(themeColor(R.attr.contrast60))
                    setPadding(dp(16f).toInt(), dp(4f).toInt(), 0, 0)
                })
            }
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
    private fun addStabilityChartCard(
        startDate: LocalDate,
        today: LocalDate,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
    ) {
        val (card, container) = createCard(
            getString(R.string.compare_stability),
            getString(R.string.compare_stability_loop_score)
        )

        val series = selectedHabits.mapIndexed { i, habit ->
            val color = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val scores = CompareHabitsPresenter.buildStabilitySeries(habit, startDate, today, snapshot)
            CompareHabitsMultiLineChartView.SeriesItem(habit.id ?: 0L, habit.name, color, scores)
        }

        val chart = CompareHabitsMultiLineChartView(this).apply {
            setData(
                series = series,
                rangeStart = startDate,
                rangeEnd = today,
                surfaceColor = palette.surface,
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
    private fun addExecutionChartCard(
        startDate: LocalDate,
        today: LocalDate,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
    ) {
        val (card, container) = createCard(getString(R.string.compare_execution))

        val series = selectedHabits.mapIndexed { i, habit ->
            val color = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val points = CompareHabitsPresenter.buildExecutionSeries(habit, startDate, today, snapshot)
            CompareHabitsMultiLineChartView.SeriesItem(habit.id ?: 0L, habit.name, color, points)
        }

        val chart = CompareHabitsMultiLineChartView(this).apply {
            setData(
                series = series,
                rangeStart = startDate,
                rangeEnd = today,
                surfaceColor = palette.surface,
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
    private fun addCalendarSmallMultiplesCard(
        startDate: LocalDate,
        today: LocalDate,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
    ) {
        val (card, container) = createCard(getString(R.string.compare_calendar))
        val firstWeekday = component.preferences.firstWeekday
        val calendarStart = if (currentPeriod == ComparePeriod.MONTH) today.startOfMonth() else startDate
        val calendarEnd = if (currentPeriod == ComparePeriod.MONTH)
            calendarStart.plus(calendarStart.monthLength - 1) else today
        val calendars = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        selectedHabits.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }

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
                tag = "compare_calendar_${habit.id}"
                setData(habit, calendarStart, calendarEnd, today,
                    if (currentPeriod == ComparePeriod.WEEK) calendarStart.dayOfWeek else firstWeekday,
                    currentPeriod == ComparePeriod.MONTH || currentPeriod == ComparePeriod.WEEK,
                    seriesColor, themeColor(R.attr.contrast20), themeColor(R.attr.contrast60), snapshot)
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
    private fun addStreaksComparisonCard(snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null) {
        val (card, container) = createCard(getString(R.string.compare_streaks))

        val allStreaks = selectedHabits.flatMap { h ->
            val snapshot = h.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val st = CompareHabitsPresenter.buildStreakState(h, snapshot)
            listOfNotNull(st.latestStreak, st.recordStreak)
        }
        val sharedMax = allStreaks.maxOfOrNull { it.length }?.toLong() ?: 1L

        selectedHabits.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[i % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val streakState = CompareHabitsPresenter.buildStreakState(habit, snapshot)

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

            if (!streakState.isExact) row.addView(unavailableObservation())

            fun addStreak(labelRes: Int, streaks: List<org.isoron.uhabits.core.models.Streak>, alpha: Int) {
                if (streaks.isEmpty()) return
                row.addView(TextView(this).apply {
                    text = getString(labelRes)
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
            addStreak(R.string.streak_current, listOfNotNull(streakState.latestStreak), 120)
            addStreak(R.string.streak_record, listOfNotNull(streakState.recordStreak), 255)

            container.addView(row)
        }
        binding.compareContentContainer.addView(card)
    }

    // -------------------------------------------------------------
    // SECTION 6: ЧАСТОТА (Frequency Comparison)
    // -------------------------------------------------------------
    private fun addFrequencyComparisonCard(snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null) {
        val firstWeekday = component.preferences.firstWeekday

        val today = getToday()
        val habitsWithHistory = selectedHabits.filter {
            val snapshot = it.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val habitStart = StatisticsReportStateBuilder.getHabitStartDate(it, today, snapshot)
            habitStart.daysUntil(today) >= 14
        }
        if (habitsWithHistory.isEmpty()) return

        val (card, container) = createCard(getString(R.string.compare_frequency))

        habitsWithHistory.forEachIndexed { i, habit ->
            val seriesColor = SERIES_COLORS[selectedHabits.indexOf(habit) % SERIES_COLORS.size]
            val snapshot = habit.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            val frequency = CompareHabitsPresenter.buildFrequency(habit, snapshot)

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
                setFrequency(frequency)
            }
            if (snapshot?.hasContributions == true && frequency.isEmpty()) row.addView(unavailableObservation())
            else row.addView(chart)
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
            setBackgroundColor(palette.surface)
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
