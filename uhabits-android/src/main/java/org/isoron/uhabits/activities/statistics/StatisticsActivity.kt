/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.statistics

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.time.*
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.activities.common.views.ScoreChart
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.ui.screens.habits.today.formatTodayValue
import org.isoron.uhabits.databinding.ActivityStatisticsBinding
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class StatisticsFragment : Fragment() {
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private var viewBinding: ActivityStatisticsBinding? = null
    private val binding get() = viewBinding!!
    private val component
        get() = (requireContext().applicationContext as HabitsApplication).component
    private val sres get() = binding.root.sres

    private enum class ReportTab { DAY, WEEK, MONTH, YEAR, ALL }
    private var currentTab = ReportTab.DAY
    private lateinit var currentAnchorDate: LocalDate
    private lateinit var dateFormatter: JavaLocalDateFormatter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        themeSwitcher = AndroidThemeSwitcher(requireActivity(), component.preferences)
        themeSwitcher.apply()

        viewBinding = ActivityStatisticsBinding.inflate(inflater, container, false)
        binding.toolbar.applyToolbarInsets()

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)

        dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
        currentTab = ReportTab.values().getOrElse(
            savedInstanceState?.getInt(STATE_TAB) ?: 0
        ) { ReportTab.DAY }
        currentAnchorDate = savedInstanceState?.let {
            LocalDate(
                it.getInt(STATE_YEAR),
                it.getInt(STATE_MONTH),
                it.getInt(STATE_DAY)
            )
        } ?: getToday()

        setupTabs()
        setupListeners()
        setupFilters()
        updateReport()
        binding.tabLayout.getTabAt(currentTab.ordinal)?.select()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        viewBinding?.let {
            (requireActivity() as AppCompatActivity).setSupportActionBar(it.toolbar)
            (requireActivity() as AppCompatActivity).supportActionBar
                ?.setDisplayHomeAsUpEnabled(false)
            updateReport()
        }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
    }

    fun refresh() {
        if (viewBinding != null && isAdded) {
            updateReport()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTab.ordinal)
        outState.putInt(STATE_YEAR, currentAnchorDate.year)
        outState.putInt(STATE_MONTH, currentAnchorDate.month)
        outState.putInt(STATE_DAY, currentAnchorDate.day)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        viewBinding = null
        super.onDestroyView()
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_day))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_week))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_month))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_year))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reports_tab_all))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = when (tab.position) {
                    0 -> ReportTab.DAY
                    1 -> ReportTab.WEEK
                    2 -> ReportTab.MONTH
                    3 -> ReportTab.YEAR
                    4 -> ReportTab.ALL
                    else -> ReportTab.DAY
                }
                updateReport()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupListeners() {
        binding.btnPrev.setOnClickListener {
            navigateDate(-1)
        }
        binding.btnNext.setOnClickListener {
            navigateDate(1)
        }
    }

    private fun setupFilters() {
        val spheres = component.habitList.getBlocks().toList()
        val sphereNames = ArrayList<String>()
        sphereNames.add(getString(R.string.reports_filter_all_spheres))
        for (block in spheres) {
            sphereNames.add(getLocalizedBlockName(block))
        }
        val sphereAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sphereNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerSphere.adapter = sphereAdapter

        val statusNames = arrayOf(
            getString(R.string.reports_filter_all_habits),
            getString(R.string.reports_filter_active_only),
            getString(R.string.reports_filter_archived_only)
        )
        val statusAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, statusNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerHabitStatus.adapter = statusAdapter

        val goalNames = arrayOf(
            getString(R.string.reports_filter_all_goals),
            getString(R.string.reports_filter_boolean_only),
            getString(R.string.reports_filter_numerical_only)
        )
        val goalAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, goalNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerGoalType.adapter = goalAdapter

        val isDarkTheme = themeSwitcher.currentTheme is org.isoron.uhabits.core.ui.views.DarkTheme
        val spinnerBg = GradientDrawable().apply {
            setColor(if (isDarkTheme) 0x1AFFFFFF.toInt() else 0x0A000000.toInt())
            cornerRadius = dp(6f)
            setStroke(
                dp(1f).toInt(),
                if (isDarkTheme) 0x25FFFFFF.toInt() else 0x15000000.toInt()
            )
        }
        binding.spinnerSphere.background = spinnerBg
        binding.spinnerHabitStatus.background = spinnerBg
        binding.spinnerGoalType.background = spinnerBg

        val padStartEnd = dp(12f).toInt()
        val padTopBottom = dp(6f).toInt()
        binding.spinnerSphere.setPadding(padStartEnd, padTopBottom, padStartEnd, padTopBottom)
        binding.spinnerHabitStatus.setPadding(padStartEnd, padTopBottom, padStartEnd, padTopBottom)
        binding.spinnerGoalType.setPadding(padStartEnd, padTopBottom, padStartEnd, padTopBottom)

        val onSelected = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateReport()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spinnerSphere.onItemSelectedListener = onSelected
        binding.spinnerHabitStatus.onItemSelectedListener = onSelected
        binding.spinnerGoalType.onItemSelectedListener = onSelected
    }

    private fun getSelectedSphereId(): Long? {
        if (viewBinding == null) return null
        val pos = binding.spinnerSphere.selectedItemPosition
        if (pos <= 0) return null
        val blocks = component.habitList.getBlocks().toList()
        return blocks.getOrNull(pos - 1)?.id
    }

    private fun getSelectedHabitStatus(): String {
        if (viewBinding == null) return "ALL"
        return when (binding.spinnerHabitStatus.selectedItemPosition) {
            1 -> "ACTIVE"
            2 -> "ARCHIVED"
            else -> "ALL"
        }
    }

    private fun getSelectedGoalType(): String {
        if (viewBinding == null) return "ALL"
        return when (binding.spinnerGoalType.selectedItemPosition) {
            1 -> "YES_NO"
            2 -> "NUMERICAL"
            else -> "ALL"
        }
    }

    private fun navigateDate(direction: Int) {
        currentAnchorDate = when (currentTab) {
            ReportTab.DAY -> currentAnchorDate.plus(direction)
            ReportTab.WEEK -> currentAnchorDate.plus(direction * 7)
            ReportTab.MONTH -> {
                var y = currentAnchorDate.year
                var m = currentAnchorDate.month + direction
                if (m < 1) {
                    m = 12
                    y -= 1
                } else if (m > 12) {
                    m = 1
                    y += 1
                }
                LocalDate(y, m, 1)
            }
            ReportTab.YEAR -> {
                LocalDate(currentAnchorDate.year + direction, currentAnchorDate.month, currentAnchorDate.day)
            }
            ReportTab.ALL -> currentAnchorDate
        }
        updateReport()
    }

    private fun getActiveRange(): Pair<LocalDate, LocalDate> {
        return when (currentTab) {
            ReportTab.DAY -> Pair(currentAnchorDate, currentAnchorDate)
            ReportTab.WEEK -> {
                val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
                val firstWeekday = DayOfWeek.entries[firstWeekdayNum - 1]
                val start = currentAnchorDate.startOfWeek(firstWeekday)
                Pair(start, start.plus(6))
            }
            ReportTab.MONTH -> {
                val start = currentAnchorDate.startOfMonth()
                Pair(start, start.plus(start.monthLength - 1))
            }
            ReportTab.YEAR -> {
                val start = LocalDate(currentAnchorDate.year, 1, 1)
                val end = LocalDate(currentAnchorDate.year, 12, 31)
                Pair(start, end)
            }
            ReportTab.ALL -> {
                Pair(getToday().minus(3650), getToday())
            }
        }
    }

    private fun updateReportHeader(start: LocalDate, end: LocalDate) {
        if (currentTab == ReportTab.ALL) {
            binding.btnPrev.visibility = View.INVISIBLE
            binding.btnNext.visibility = View.INVISIBLE
            binding.tvDateRange.text = getString(R.string.reports_tab_all)
        } else {
            binding.btnPrev.visibility = View.VISIBLE
            binding.btnNext.visibility = View.VISIBLE
            val rangeText = when (currentTab) {
                ReportTab.DAY -> dateFormatter.longFormat(start)
                ReportTab.WEEK -> "${dateFormatter.longFormat(start)} - ${dateFormatter.longFormat(end)}"
                ReportTab.MONTH -> "${dateFormatter.longMonthName(start)} ${start.year}"
                ReportTab.YEAR -> start.year.toString()
                else -> ""
            }
            binding.tvDateRange.text = rangeText
        }
    }

    private fun updateReport() {
        val (start, end) = getActiveRange()
        updateReportHeader(start, end)

        binding.reportContentContainer.removeAllViews()
        val loadingText = TextView(requireContext()).apply {
            text = getString(R.string.reports_loading)
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0, dp(40f).toInt(), 0, 0)
            setTextColor(sres.getColor(android.R.attr.textColorSecondary))
        }
        binding.reportContentContainer.addView(loadingText)

        val selectedSphereId = getSelectedSphereId()
        val selectedStatus = getSelectedHabitStatus()
        val selectedGoalType = getSelectedGoalType()

        val habits = component.habitList.toList()
        val blocks = component.habitList.getBlocks()

        component.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var calculatedData: StatisticsData? = null

            override suspend fun doInBackground() {
                calculatedData = calculateStatistics(
                    habits = habits,
                    blocks = blocks,
                    start = start,
                    end = end,
                    sphereId = selectedSphereId,
                    statusFilter = selectedStatus,
                    goalTypeFilter = selectedGoalType
                )
            }

            override fun onPostExecute() {
                if (viewBinding == null || !isAdded) return
                binding.reportContentContainer.removeAllViews()
                val result = calculatedData ?: return
                renderStatistics(result)
            }
        })
    }

    private fun calculateStatistics(
        habits: List<Habit>,
        blocks: List<HabitBlock>,
        start: LocalDate,
        end: LocalDate,
        sphereId: Long?,
        statusFilter: String,
        goalTypeFilter: String
    ): StatisticsData {
        val filteredHabits = habits.filter { habit ->
            if (sphereId != null && habit.blockId != sphereId) return@filter false
            if (statusFilter == "ACTIVE" && habit.isArchived) return@filter false
            if (statusFilter == "ARCHIVED" && !habit.isArchived) return@filter false
            if (goalTypeFilter == "YES_NO" && habit.isNumerical) return@filter false
            if (goalTypeFilter == "NUMERICAL" && !habit.isNumerical) return@filter false
            true
        }

        var rangeStart = start
        if (currentTab == ReportTab.ALL) {
            var oldestDate: LocalDate? = null
            for (habit in filteredHabits) {
                val known = habit.computedEntries.getKnown()
                val date = known.lastOrNull()?.date
                if (date != null && (oldestDate == null || date.isOlderThan(oldestDate))) {
                    oldestDate = date
                }
            }
            rangeStart = oldestDate ?: getToday().minus(365)
        }

        var totalDays = 0
        var completedDays = 0
        var totalFocusHours = 0.0
        var limitViolations = 0
        var missedGoals = 0

        val dateRates = mutableMapOf<LocalDate, Float>()
        val sphereFocusMap = mutableMapOf<Long?, Double>()
        val weekdayCompleted = DoubleArray(7)
        val weekdayTotal = DoubleArray(7)

        val habitCompletionStats = mutableListOf<HabitCompletionStat>()

        for (habit in filteredHabits) {
            var hStart = rangeStart
            val statsStart = habit.effectiveStatisticsStartDate()
            if (statsStart != null && statsStart.isNewerThan(hStart)) {
                hStart = statsStart
            }
            if (hStart.isNewerThan(end)) continue

            var hTotal = 0
            var hCompleted = 0

            val denominator = habit.frequency.denominator
            if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_MOST && (denominator == 7 || denominator == 30)) {
                if (denominator == 7) {
                    val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
                    val firstWeekday = DayOfWeek.entries[firstWeekdayNum - 1]
                    var wStart = hStart.startOfWeek(firstWeekday)
                    while (wStart <= end) {
                        val weekEntries = habit.statisticsEntries(wStart, wStart.plus(6))
                        if (weekEntries.isNotEmpty() && !weekEntries.all { it.value == SKIP }) {
                            hTotal++
                            val weekSum = weekEntries.filter { it.value != SKIP }.sumOf { max(0, it.value) } / 1000.0
                            if (weekSum > habit.targetValue) {
                                limitViolations++
                            } else if (weekEntries.any { it.value != Entry.UNKNOWN }) {
                                hCompleted++
                            }
                        }
                        wStart = wStart.plus(7)
                    }
                } else {
                    var mStart = hStart.startOfMonth()
                    while (mStart <= end) {
                        val monthLength = mStart.monthLength
                        val monthEntries = habit.statisticsEntries(mStart, mStart.plus(monthLength - 1))
                        if (monthEntries.isNotEmpty() && !monthEntries.all { it.value == SKIP }) {
                            hTotal++
                            val monthSum = monthEntries.filter { it.value != SKIP }.sumOf { max(0, it.value) } / 1000.0
                            if (monthSum > habit.targetValue) {
                                limitViolations++
                            } else if (monthEntries.any { it.value != Entry.UNKNOWN }) {
                                hCompleted++
                            }
                        }
                        mStart = mStart.plus(monthLength)
                    }
                }
            } else {
                var curr = hStart
                while (curr <= end) {
                    if (habit.isDateIncludedInStatistics(curr)) {
                        val entry = habit.computedEntries.get(curr)
                        if (entry.value != Entry.SKIP) {
                            hTotal++
                            val isCompleted = isHabitCompleted(habit, entry)
                            if (isCompleted) {
                                hCompleted++
                            } else {
                                missedGoals++
                            }

                            val rateIncrement = if (isCompleted) 1f else 0f
                            dateRates[curr] = (dateRates[curr] ?: 0f) + rateIncrement

                            val dow = curr.dayOfWeek
                            weekdayTotal[dow.ordinal] += 1.0
                            if (isCompleted) {
                                weekdayCompleted[dow.ordinal] += 1.0
                            }

                            val goal = habit.goalAt(curr)
                            if (habit.isNumerical && goal.targetType == NumericalHabitType.AT_LEAST && goal.unit.isMinuteUnit()) {
                                val valDouble = if (entry.value != Entry.UNKNOWN) entry.value / 1000.0 else 0.0
                                val hours = valDouble / 60.0
                                totalFocusHours += hours
                                sphereFocusMap[habit.blockId] = (sphereFocusMap[habit.blockId] ?: 0.0) + hours
                            }

                            if (habit.isNumerical && goal.targetType == NumericalHabitType.AT_MOST && entry.value != Entry.UNKNOWN) {
                                if (entry.value / 1000.0 > goal.targetValue) {
                                    limitViolations++
                                }
                            }
                        }
                    }
                    curr = curr.plus(1)
                }
            }

            if (hTotal > 0) {
                totalDays += hTotal
                completedDays += hCompleted
                habitCompletionStats.add(HabitCompletionStat(habit, hCompleted, hTotal))
            }
        }

        val heatmapRates = mutableMapOf<LocalDate, Float>()
        for ((date, completedCount) in dateRates) {
            var activeCount = 0
            for (h in filteredHabits) {
                if (h.isDateIncludedInStatistics(date) && h.computedEntries.get(date).value != Entry.SKIP) {
                    activeCount++
                }
            }
            if (activeCount > 0) {
                heatmapRates[date] = completedCount / activeCount.toFloat()
            }
        }

        var bestSphereName: String? = null
        var bestSphereRate = -1.0
        var worstSphereName: String? = null
        var worstSphereRate = 2.0
        val blocksMap = blocks.associateBy { it.id }

        for (block in blocks) {
            val blockHabits = filteredHabits.filter { it.blockId == block.id }
            if (blockHabits.isEmpty()) continue

            var bTotal = 0
            var bCompleted = 0
            for (habit in blockHabits) {
                val stats = habitCompletionStats.find { it.habit.id == habit.id } ?: continue
                bTotal += stats.totalDays
                bCompleted += stats.completedDays
            }
            if (bTotal > 0) {
                val rate = bCompleted.toDouble() / bTotal
                if (rate > bestSphereRate) {
                    bestSphereRate = rate
                    bestSphereName = getLocalizedBlockName(block)
                }
                if (rate < worstSphereRate) {
                    worstSphereRate = rate
                    worstSphereName = getLocalizedBlockName(block)
                }
            }
        }
        if (worstSphereRate > 1.0) worstSphereName = null

        val completedHabitsCount = habitCompletionStats.count { it.completedDays > 0 }

        var bestStreak = 0
        for (habit in filteredHabits) {
            val maxS = habit.streaks.getBest(1).firstOrNull()?.length ?: 0
            if (maxS > bestStreak) {
                bestStreak = maxS
            }
        }

        val weekdayList = ArrayList<Pair<DayOfWeek, Double>>()
        for (dow in DayOfWeek.values()) {
            val total = weekdayTotal[dow.ordinal]
            val completed = weekdayCompleted[dow.ordinal]
            val rate = if (total > 0.0) completed / total else 0.0
            weekdayList.add(Pair(dow, rate))
        }

        val scoresList = ArrayList<org.isoron.uhabits.core.models.Score>()
        val numChartPoints = when (currentTab) {
            ReportTab.DAY -> 30
            ReportTab.WEEK -> 12
            ReportTab.MONTH -> 6
            ReportTab.YEAR -> 5
            ReportTab.ALL -> 12
        }

        val step = when (currentTab) {
            ReportTab.DAY, ReportTab.WEEK -> 1
            ReportTab.MONTH -> 3
            ReportTab.YEAR, ReportTab.ALL -> 30
        }

        for (i in (numChartPoints - 1) downTo 0) {
            val d = end.minus(i * step)
            var dayCompleted = 0
            var dayTotal = 0
            for (habit in filteredHabits) {
                if (!habit.isDateIncludedInStatistics(d)) continue
                val entry = habit.computedEntries.get(d)
                if (entry.value == Entry.SKIP) continue
                dayTotal++
                if (isHabitCompleted(habit, entry)) {
                    dayCompleted++
                }
            }
            val rate = if (dayTotal > 0) dayCompleted.toDouble() / dayTotal else 0.0
            scoresList.add(org.isoron.uhabits.core.models.Score(d, rate))
        }

        val sphereFocusList = ArrayList<Pair<HabitBlock, Double>>()
        for ((blockId, hours) in sphereFocusMap) {
            val block = blocksMap[blockId]
            if (block != null) {
                sphereFocusList.add(Pair(block, hours))
            }
        }
        sphereFocusList.sortByDescending { it.second }

        val completionPercentage = if (totalDays > 0) (completedDays * 100.0 / totalDays).roundToInt() else 0

        return StatisticsData(
            start = start,
            end = end,
            totalDays = totalDays,
            completedDays = completedDays,
            totalFocusHours = totalFocusHours,
            limitViolations = limitViolations,
            missedGoals = missedGoals,
            completionPercentage = completionPercentage,
            completedHabitsCount = completedHabitsCount,
            bestStreak = bestStreak,
            bestSphereName = bestSphereName,
            worstSphereName = worstSphereName,
            scoreChartData = scoresList,
            sphereFocusHours = sphereFocusList,
            habitStats = habitCompletionStats,
            weekdayFrequency = weekdayList,
            heatmapRates = heatmapRates
        )
    }

    private fun renderStatistics(result: StatisticsData) {
        val context = requireContext()
        val blocks = component.habitList.getBlocks()
        val blocksMap = blocks.associateBy { it.id }
        val fallbackBlock = HabitBlock(
            id = null,
            name = getString(R.string.habit_block_unassigned),
            color = PaletteColor(18),
            icon = "more_horiz",
            position = 1000
        )

        // 1. Overview Metrics Card
        val (summaryCard, summaryContent) = createCard(getString(R.string.overview))
        val gridLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val rateVal = "${result.completionPercentage}%"
        row1.addView(createMetricItem(getString(R.string.reports_metric_completion_rate), rateVal))
        row1.addView(createMetricItem(getString(R.string.reports_metric_completed_count), result.completedHabitsCount.toString()))
        gridLayout.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val focusVal = if (result.totalFocusHours > 0) {
            String.format(Locale.US, "%.1f %s", result.totalFocusHours, getString(R.string.reports_hour_unit))
        } else {
            "—"
        }
        row2.addView(createMetricItem(getString(R.string.reports_metric_focus_time), focusVal))
        row2.addView(createMetricItem(getString(R.string.reports_metric_missed_goals), result.missedGoals.toString()))
        gridLayout.addView(row2)

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val streakVal = if (result.bestStreak > 0) {
            getString(R.string.motivation_streak_milestone, "", result.bestStreak).replace("«»", "").trim()
        } else {
            "—"
        }
        row3.addView(createMetricItem(getString(R.string.reports_metric_best_streak), streakVal))

        val bestWorstVal = if (result.bestSphereName != null) {
            val best = result.bestSphereName
            val worst = result.worstSphereName ?: "—"
            "$best / $worst"
        } else {
            "—"
        }
        row3.addView(createMetricItem(getString(R.string.reports_metric_best_sphere) + " / " + getString(R.string.reports_metric_worst_sphere).lowercase(Locale.getDefault()), bestWorstVal))
        gridLayout.addView(row3)

        summaryContent.addView(gridLayout)
        binding.reportContentContainer.addView(summaryCard)

        // 2. Results over time Card (ScoreChart)
        if (result.scoreChartData.isNotEmpty()) {
            val (chartCard, chartContent) = createCard(getString(R.string.reports_chart_completion))
            val chart = ScoreChart(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(160f).toInt()
                )
                val accentColor = themeSwitcher.currentTheme.color(PaletteColor(17)).toInt()
                setColor(accentColor)
                setScores(result.scoreChartData)
            }
            chartContent.addView(chart)
            binding.reportContentContainer.addView(chartCard)
        }

        // 3. Activity Calendar Card (HeatmapView)
        if (currentTab != ReportTab.DAY && result.heatmapRates.isNotEmpty()) {
            val (heatmapCard, heatmapContent) = createCard(getString(R.string.reports_activity_calendar))
            val isDarkTheme = themeSwitcher.currentTheme is org.isoron.uhabits.core.ui.views.DarkTheme

            val heatmapView = HeatmapView(context).apply {
                this.isDarkTheme = isDarkTheme
                this.dayRates = result.heatmapRates
                this.endDate = result.end
                this.startDate = if (currentTab == ReportTab.ALL) result.end.minus(180) else result.start
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val scroll = HorizontalScrollView(context).apply {
                addView(heatmapView)
                isHorizontalScrollBarEnabled = false
            }
            heatmapContent.addView(scroll)
            binding.reportContentContainer.addView(heatmapCard)
        }

        // 4. Weekday Frequency Card
        if (currentTab != ReportTab.DAY && result.weekdayFrequency.isNotEmpty()) {
            val (weekdayCard, weekdayContent) = createCard(getString(R.string.reports_weekday_frequency))
            val dayNames = JavaLocalDateFormatter(Locale.getDefault()).longWeekdayNames(DayOfWeek.SATURDAY)

            for (i in 0 until 7) {
                val dow = when (i) {
                    0 -> DayOfWeek.MONDAY
                    1 -> DayOfWeek.TUESDAY
                    2 -> DayOfWeek.WEDNESDAY
                    3 -> DayOfWeek.THURSDAY
                    4 -> DayOfWeek.FRIDAY
                    5 -> DayOfWeek.SATURDAY
                    else -> DayOfWeek.SUNDAY
                }

                val stat = result.weekdayFrequency.find { it.first == dow } ?: continue
                val rate = stat.second
                val nameIdx = when (dow) {
                    DayOfWeek.SUNDAY -> 0
                    DayOfWeek.MONDAY -> 1
                    DayOfWeek.TUESDAY -> 2
                    DayOfWeek.WEDNESDAY -> 3
                    DayOfWeek.THURSDAY -> 4
                    DayOfWeek.FRIDAY -> 5
                    DayOfWeek.SATURDAY -> 6
                }
                val dowName = dayNames.getOrNull(nameIdx) ?: dow.name

                val weekdayRow = createSphereRowWithProgress(
                    PaletteColor(17),
                    dowName,
                    "${(rate * 100).roundToInt()}%",
                    rate
                )
                weekdayContent.addView(weekdayRow)
            }
            binding.reportContentContainer.addView(weekdayCard)
        }

        // 5. Focus by sphere Card
        if (component.preferences.isHabitSpheresEnabled && result.sphereFocusHours.isNotEmpty()) {
            val (focusCard, focusContent) = createCard(getString(R.string.reports_focus_by_sphere))
            val maxHours = result.sphereFocusHours.maxOfOrNull { it.second } ?: 1.0
            for ((block, hours) in result.sphereFocusHours) {
                val blockName = getLocalizedBlockName(block)
                val formattedHours = String.format(Locale.US, "%.1f %s", hours, getString(R.string.reports_hour_unit))
                val row = createSphereRowWithProgress(
                    block.color,
                    blockName,
                    formattedHours,
                    hours / maxHours
                )
                focusContent.addView(row)
            }
            binding.reportContentContainer.addView(focusCard)
        }

        // 6. Habits details/ranking Card
        if (result.habitStats.isNotEmpty()) {
            val (detailsCard, detailsContent) = createCard(getString(R.string.reports_frequency_title))
            for (stat in result.habitStats.sortedByDescending { it.completedDays * 100f / it.totalDays }) {
                val block = blocksMap[stat.habit.blockId] ?: fallbackBlock
                val percentage = (stat.completedDays * 100f / stat.totalDays).roundToInt()
                val unitText = when (stat.habit.frequency.denominator) {
                    7 -> getString(R.string.per_week)
                    30 -> getString(R.string.per_month)
                    else -> getString(R.string.reports_days_unit)
                }
                val subtitle = if (component.preferences.isHabitSpheresEnabled) {
                    "${getLocalizedBlockName(block)} • ${stat.completedDays} / ${stat.totalDays} $unitText ($percentage%)"
                } else {
                    "${stat.completedDays} / ${stat.totalDays} $unitText ($percentage%)"
                }
                val rowColor = if (component.preferences.isHabitSpheresEnabled) block.color else stat.habit.color
                val row = createHabitDetailRow(rowColor, stat.habit.name, subtitle)
                detailsContent.addView(row)
            }
            binding.reportContentContainer.addView(detailsCard)
        }
    }

    private fun createMetricItem(title: String, value: String): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val p = dp(8f).toInt()
            setPadding(p, p, p, p)
        }
        val titleView = TextView(requireContext()).apply {
            text = title
            textSize = 12f
            setTextColor(sres.getColor(android.R.attr.textColorSecondary))
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f).toInt()
            }
        }
        layout.addView(titleView)
        layout.addView(valueView)
        return layout
    }

    private fun getPeriodTotalAndTarget(habit: Habit, date: LocalDate): Pair<Double, Double> {
        val goal = habit.goalAt(date)
        val denominator = goal.frequency.denominator
        val targetValue = goal.targetValue
        return when (denominator) {
            7 -> {
                val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
                val firstWeekday = DayOfWeek.values()[firstWeekdayNum - 1]
                val startOfWeek = date.startOfWeek(firstWeekday)
                val endOfWeek = startOfWeek.plus(6)
                val weekEntries = habit.statisticsEntries(startOfWeek, endOfWeek)
                val weekSum = weekEntries.groupedSum(
                    truncateField = TruncateField.WEEK_NUMBER,
                    firstWeekday = firstWeekdayNum,
                    isNumerical = true
                ).firstOrNull()?.value ?: 0
                Pair(weekSum / 1000.0, targetValue)
            }
            30 -> {
                val startOfMonth = date.startOfMonth()
                val endOfMonth = startOfMonth.plus(date.monthLength - 1)
                val monthEntries = habit.statisticsEntries(startOfMonth, endOfMonth)
                val monthSum = monthEntries.groupedSum(
                    truncateField = TruncateField.MONTH,
                    isNumerical = true
                ).firstOrNull()?.value ?: 0
                Pair(monthSum / 1000.0, targetValue)
            }
            else -> {
                val entry = habit.computedEntries.get(date)
                val valDouble = if (entry.value != Entry.UNKNOWN && entry.value != Entry.SKIP) entry.value / 1000.0 else 0.0
                Pair(valDouble, targetValue)
            }
        }
    }

    private fun isHabitCompleted(habit: Habit, entry: Entry): Boolean {
        if (habit.type == HabitType.NUMERICAL) {
            if (entry.value == Entry.UNKNOWN) return false
            val goal = habit.goalAt(entry.date)
            val value = entry.value / 1000.0
            return when (goal.targetType) {
                NumericalHabitType.AT_LEAST -> value >= goal.targetValue
                NumericalHabitType.AT_MOST -> {
                    val denominator = goal.frequency.denominator
                    if (denominator == 7 || denominator == 30) {
                        val (periodActual, periodTarget) = getPeriodTotalAndTarget(habit, entry.date)
                        periodActual <= periodTarget
                    } else {
                        value <= goal.targetValue
                    }
                }
            }
        } else {
            return entry.value == Entry.YES_MANUAL || entry.value == Entry.YES_AUTO
        }
    }

    private fun getLocalizedBlockName(block: HabitBlock): String {
        return if (block.id in 1L..7L) {
            when (block.id) {
                1L -> getString(R.string.today_section_intellect)
                2L -> getString(R.string.today_section_speech)
                3L -> getString(R.string.today_section_body)
                4L -> getString(R.string.today_section_care)
                5L -> getString(R.string.today_section_routine)
                6L -> getString(R.string.today_section_limits)
                7L -> getString(R.string.today_section_other)
                else -> block.name
            }
        } else {
            block.name
        }
    }

    private fun createSphereRowWithProgress(
        color: PaletteColor,
        name: String,
        valueText: String,
        progressFraction: Double
    ): View {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
        }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(requireContext()).apply {
            val size = dp(10f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(10f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeSwitcher.currentTheme.color(color).toInt())
            }
        }

        val nameView = TextView(requireContext()).apply {
            text = name
            textSize = 14f
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueView = TextView(requireContext()).apply {
            text = valueText
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
        }

        row.addView(dot)
        row.addView(nameView)
        row.addView(valueView)

        val isDark = themeSwitcher.currentTheme is org.isoron.uhabits.core.ui.views.DarkTheme
        val progressBar = android.widget.ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (progressFraction.coerceIn(0.0, 1.0) * 1000).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4f).toInt()
            ).apply {
                topMargin = dp(4f).toInt()
            }
            progressDrawable = GradientDrawable().apply {
                setColor(themeSwitcher.currentTheme.color(color).toInt())
                cornerRadius = dp(2f)
            }.let { filled ->
                val track = GradientDrawable().apply {
                    setColor(if (isDark) 0x20FFFFFF.toInt() else 0x15000000.toInt())
                    cornerRadius = dp(2f)
                }
                val ld = android.graphics.drawable.LayerDrawable(arrayOf(track, filled))
                ld.setId(0, android.R.id.background)
                ld.setId(1, android.R.id.progress)
                ld
            }
        }

        container.addView(row)
        container.addView(progressBar)
        return container
    }

    private fun createHabitStatusRow(color: PaletteColor, name: String, statusIcon: String, valueText: String): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
        }

        val iconView = TextView(requireContext()).apply {
            text = statusIcon
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8f).toInt()
            }
        }

        val nameView = TextView(requireContext()).apply {
            text = name
            textSize = 15f
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueView = TextView(requireContext()).apply {
            text = valueText
            textSize = 14f
            setTextColor(themeSwitcher.currentTheme.color(PaletteColor(18)).toInt()) // grey text color
        }

        row.addView(iconView)
        row.addView(nameView)
        if (valueText.isNotEmpty()) {
            row.addView(valueView)
        }
        return row
    }

    private fun createHabitDetailRow(color: PaletteColor, name: String, subtitle: String): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
        }

        val dot = View(requireContext()).apply {
            val size = dp(10f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(6f).toInt()
                rightMargin = dp(12f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeSwitcher.currentTheme.color(color).toInt())
            }
        }

        val textContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(requireContext()).apply {
            text = name
            textSize = 15f
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
        }

        val subtitleView = TextView(requireContext()).apply {
            text = subtitle
            textSize = 13f
            setTextColor(themeSwitcher.currentTheme.color(PaletteColor(18)).toInt()) // grey text color
        }

        textContainer.addView(titleView)
        textContainer.addView(subtitleView)

        row.addView(dot)
        row.addView(textContainer)
        return row
    }

    private fun createCard(titleText: String): Pair<LinearLayout, LinearLayout> {
        val cardContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f).toInt()
                leftMargin = dp(4f).toInt()
                rightMargin = dp(4f).toInt()
            }
            layoutParams = lp
            elevation = dp(1f)
            val padding = dp(16f).toInt()
            setPadding(padding, padding, padding, padding)

            val typedArray = requireContext().obtainStyledAttributes(intArrayOf(R.attr.cardBgColor))
            val cardBg = typedArray.getColor(0, sres.getColor(android.R.color.white))
            typedArray.recycle()

            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(8f)
            }
        }

        val titleView = TextView(requireContext()).apply {
            text = titleText
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            val typedArray = requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            val titleColor = typedArray.getColor(0, sres.getColor(android.R.color.black))
            typedArray.recycle()
            setTextColor(titleColor)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f).toInt()
            }
            layoutParams = lp
        }

        cardContainer.addView(titleView)

        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cardContainer.addView(contentLayout)

        return Pair(cardContainer, contentLayout)
    }

    private fun dp(value: Float) = binding.root.dp(value)

    companion object {
        private const val STATE_TAB = "reports.tab"
        private const val STATE_YEAR = "reports.year"
        private const val STATE_MONTH = "reports.month"
        private const val STATE_DAY = "reports.day"
    }
}

/** Compatibility entry point for existing internal intents. */
class StatisticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.intent(this, MainDestination.STATISTICS))
        finish()
    }
}

data class StatisticsData(
    val start: LocalDate,
    val end: LocalDate,
    val totalDays: Int,
    val completedDays: Int,
    val totalFocusHours: Double,
    val limitViolations: Int,
    val missedGoals: Int,
    val completionPercentage: Int,
    val completedHabitsCount: Int,
    val bestStreak: Int,
    val bestSphereName: String?,
    val worstSphereName: String?,
    val scoreChartData: List<org.isoron.uhabits.core.models.Score>,
    val sphereFocusHours: List<Pair<HabitBlock, Double>>,
    val habitStats: List<HabitCompletionStat>,
    val weekdayFrequency: List<Pair<DayOfWeek, Double>>,
    val heatmapRates: Map<LocalDate, Float>
)

data class HabitCompletionStat(
    val habit: Habit,
    val completedDays: Int,
    val totalDays: Int
)

class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isDarkTheme: Boolean = false
    var dayRates: Map<LocalDate, Float> = emptyMap()
    var startDate: LocalDate = getToday()
    var endDate: LocalDate = getToday()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9f)
    }

    private fun dp(value: Float) = value * context.resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val prefs = (context.applicationContext as HabitsApplication).component.preferences
        val firstWeekday = prefs.firstWeekday
        val gridStart = startDate.startOfWeek(firstWeekday)
        val daysCount = gridStart.daysUntil(endDate) + 1
        val numWeeks = (daysCount + 6) / 7

        val cellSide = dp(10f)
        val gap = dp(2f)
        val leftPadding = dp(24f)
        val topPadding = dp(14f)

        val w = leftPadding + numWeeks * (cellSide + gap) - gap + dp(8f)
        val h = topPadding + 7 * (cellSide + gap) - gap + dp(8f)

        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val prefs = (context.applicationContext as HabitsApplication).component.preferences
        val firstWeekday = prefs.firstWeekday
        val gridStart = startDate.startOfWeek(firstWeekday)
        val daysCount = gridStart.daysUntil(endDate) + 1
        val numWeeks = (daysCount + 6) / 7

        val cellSide = dp(10f)
        val gap = dp(2f)
        val leftPadding = dp(24f)
        val topPadding = dp(14f)

        val app = context.applicationContext as HabitsApplication
        val themeSwitcher = AndroidThemeSwitcher(context, app.component.preferences)
        val theme = themeSwitcher.currentTheme
        val accentColor = theme.color(PaletteColor(17)).toInt()
        val isAMOLED = theme is org.isoron.uhabits.core.ui.views.PureBlackTheme

        // 1. Draw weekday labels (e.g. Пн, Ср, Пт) on the left
        labelPaint.color = sres.getColor(R.attr.contrast40)
        val dayNames = JavaLocalDateFormatter(Locale.getDefault()).shortWeekdayNames(firstWeekday)
        val showIndices = setOf(1, 3, 5)
        for (i in 0 until 7) {
            if (i in showIndices) {
                val name = dayNames.getOrNull(i) ?: ""
                val y = topPadding + i * (cellSide + gap) + cellSide / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText(name, dp(2f), y, labelPaint)
            }
        }

        // 2. Draw month headers at the top when month changes
        var lastMonth = -1
        for (col in 0 until numWeeks) {
            val mondayOfWeek = gridStart.plus(col * 7)
            if (mondayOfWeek.month != lastMonth) {
                lastMonth = mondayOfWeek.month
                val formatter = JavaLocalDateFormatter(Locale.getDefault())
                val monthName = formatter.shortMonthName(mondayOfWeek)
                val x = leftPadding + col * (cellSide + gap)
                canvas.drawText(monthName, x, topPadding - dp(4f), labelPaint)
            }
        }

        // 3. Draw heat grid cells
        for (col in 0 until numWeeks) {
            for (row in 0 until 7) {
                val cellDate = gridStart.plus(col * 7 + row)
                if (cellDate.isOlderThan(startDate) || cellDate.isNewerThan(endDate)) {
                    continue
                }

                val rate = dayRates[cellDate] ?: 0f
                val x = leftPadding + col * (cellSide + gap)
                val y = topPadding + row * (cellSide + gap)

                val baseColor = if (rate > 0f) {
                    accentColor
                } else {
                    if (isAMOLED) 0x12FFFFFF.toInt() else if (isDarkTheme) 0x1AFFFFFF.toInt() else 0x1A000000.toInt()
                }

                cellPaint.color = if (rate > 0f) {
                    val alpha = when {
                        rate <= 0.25f -> 0.25f
                        rate <= 0.50f -> 0.50f
                        rate <= 0.75f -> 0.75f
                        else -> 1.0f
                    }
                    applyAlpha(baseColor, alpha)
                } else {
                    baseColor
                }

                canvas.drawRect(x, y, x + cellSide, y + cellSide, cellPaint)
            }
        }
    }

    private fun applyAlpha(color: Int, fraction: Float): Int {
        val alpha = (fraction * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
