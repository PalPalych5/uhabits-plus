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
package org.isoron.uhabits.activities.reports

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.ui.screens.habits.today.formatTodayValue
import org.isoron.uhabits.databinding.ActivityReportsBinding
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class ReportsFragment : Fragment() {
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private var viewBinding: ActivityReportsBinding? = null
    private val binding get() = viewBinding!!
    private val component
        get() = (requireContext().applicationContext as HabitsApplication).component
    private val sres get() = binding.root.sres

    private enum class ReportTab { DAY, WEEK, MONTH }
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

        viewBinding = ActivityReportsBinding.inflate(inflater, container, false)
        binding.toolbar.applyToolbarInsets()

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)

        dateFormatter = JavaLocalDateFormatter(Locale.getDefault())
        currentTab = ReportTab.entries.getOrElse(
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
        }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
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

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = when (tab.position) {
                    0 -> ReportTab.DAY
                    1 -> ReportTab.WEEK
                    2 -> ReportTab.MONTH
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
        }
    }

    private fun updateReportHeader(start: LocalDate, end: LocalDate) {
        val rangeText = when (currentTab) {
            ReportTab.DAY -> dateFormatter.longFormat(start)
            ReportTab.WEEK -> "${dateFormatter.longFormat(start)} - ${dateFormatter.longFormat(end)}"
            ReportTab.MONTH -> "${dateFormatter.longMonthName(start)} ${start.year}"
        }
        binding.tvDateRange.text = rangeText
    }

    private fun updateReport() {
        val (start, end) = getActiveRange()
        updateReportHeader(start, end)

        binding.reportContentContainer.removeAllViews()

        when (currentTab) {
            ReportTab.DAY -> renderDayReport(start)
            ReportTab.WEEK, ReportTab.MONTH -> renderRangeReport(start, end)
        }
    }

    private fun renderDayReport(date: LocalDate) {
        val habits = component.habitList.toList().filter { !it.isArchived }
        val blocks = component.habitList.getBlocks()
        val blocksMap = blocks.associateBy { it.id }

        val fallbackBlock = HabitBlock(
            id = null,
            name = getString(R.string.habit_block_unassigned),
            color = PaletteColor(18),
            icon = "more_horiz",
            position = 1000
        )

        var completedCount = 0
        var totalCount = 0
        var totalFocusMinutes = 0.0

        val sphereFocus = mutableMapOf<Long?, Double>()

        val remainingHabits = mutableListOf<Habit>()
        val exceededHabits = mutableListOf<Habit>()

        for (habit in habits) {
            val entry = habit.computedEntries.get(date)
            if (entry.value == Entry.SKIP) continue

            totalCount++

            val isCompleted = isHabitCompleted(habit, entry)
            if (isCompleted) {
                completedCount++
            }

            // Focus Minutes calculation
            if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_LEAST && habit.unit.isMinuteUnit()) {
                val valDouble = if (entry.value != Entry.UNKNOWN) entry.value / 1000.0 else 0.0
                totalFocusMinutes += valDouble
                val blockId = habit.blockId
                sphereFocus[blockId] = (sphereFocus[blockId] ?: 0.0) + valDouble
            }

            // Missing Targets & Limit Violations list
            if (habit.isNumerical) {
                if (entry.value == Entry.UNKNOWN) {
                    remainingHabits.add(habit)
                } else {
                    val value = entry.value / 1000.0
                    when (habit.targetType) {
                        NumericalHabitType.AT_LEAST -> {
                            if (value < habit.targetValue) remainingHabits.add(habit)
                        }
                        NumericalHabitType.AT_MOST -> {
                            if (value > habit.targetValue) exceededHabits.add(habit)
                        }
                    }
                }
            } else {
                if (entry.value == Entry.NO || entry.value == Entry.UNKNOWN) {
                    remainingHabits.add(habit)
                }
            }
        }

        // Card 1: Results Summary
        val (summaryCard, summaryContent) = createCard(getString(R.string.overview))
        val summaryText = TextView(requireContext()).apply {
            text = buildString {
                append(getString(R.string.today_summary_completed, completedCount, totalCount))
                val percentage = if (totalCount > 0) (completedCount * 100f / totalCount).roundToInt() else 0
                append(" ($percentage%)\n")
                if (totalFocusMinutes > 0) {
                    append(getString(R.string.today_summary_focus_minutes, totalFocusMinutes.formatTodayValue()))
                }
            }
            textSize = 16f
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
        }
        summaryContent.addView(summaryText)
        binding.reportContentContainer.addView(summaryCard)

        // Card 2: Focus by sphere
        if (sphereFocus.isNotEmpty()) {
            val (focusCard, focusContent) = createCard(getString(R.string.reports_focus_by_sphere))
            for ((blockId, minutes) in sphereFocus.entries.sortedByDescending { it.value }) {
                val block = blocksMap[blockId] ?: fallbackBlock
                val blockName = getLocalizedBlockName(block)
                val row = createSphereRow(block.color, blockName, "${minutes.formatTodayValue()} ${getString(R.string.reports_min_unit)}")
                focusContent.addView(row)
            }
            binding.reportContentContainer.addView(focusCard)
        }

        // Card 3: Missed Targets
        val (missedCard, missedContent) = createCard(getString(R.string.reports_missed_targets))
        if (remainingHabits.isEmpty()) {
            val congratsText = TextView(requireContext()).apply {
                text = "Все цели выполнены! 🎉"
                textSize = 15f
                setTextColor(themeSwitcher.currentTheme.color(PaletteColor(6)).toInt()) // Green
                setTypeface(null, Typeface.ITALIC)
            }
            missedContent.addView(congratsText)
        } else {
            for (habit in remainingHabits) {
                val block = blocksMap[habit.blockId] ?: fallbackBlock
                val entry = habit.computedEntries.get(date)
                val textValue = if (habit.isNumerical) {
                    val actual = if (entry.value != Entry.UNKNOWN) (entry.value / 1000.0).formatTodayValue() else "0"
                    "$actual / ${habit.targetValue.formatTodayValue()} ${habit.unit}"
                } else {
                    ""
                }
                val row = createHabitStatusRow(block.color, habit.name, "❌", textValue)
                missedContent.addView(row)
            }
        }
        binding.reportContentContainer.addView(missedCard)

        // Card 4: Limit Violations
        if (exceededHabits.isNotEmpty()) {
            val (exceededCard, exceededContent) = createCard(getString(R.string.reports_limit_violations))
            for (habit in exceededHabits) {
                val block = blocksMap[habit.blockId] ?: fallbackBlock
                val entry = habit.computedEntries.get(date)
                val actual = (entry.value / 1000.0).formatTodayValue()
                val textValue = "$actual / ${habit.targetValue.formatTodayValue()} ${habit.unit}"
                val row = createHabitStatusRow(block.color, habit.name, "⚠️", textValue)
                exceededContent.addView(row)
            }
            binding.reportContentContainer.addView(exceededCard)
        }
    }

    private fun renderRangeReport(start: LocalDate, end: LocalDate) {
        val habits = component.habitList.toList().filter { !it.isArchived }
        val blocks = component.habitList.getBlocks()
        val blocksMap = blocks.associateBy { it.id }

        val fallbackBlock = HabitBlock(
            id = null,
            name = getString(R.string.habit_block_unassigned),
            color = PaletteColor(18),
            icon = "more_horiz",
            position = 1000
        )

        var completedDaysCount = 0
        var totalDaysCount = 0
        var totalFocusHours = 0.0
        var limitViolationsCount = 0

        val sphereFocusHours = mutableMapOf<Long?, Double>()
        val habitCompletionStats = mutableListOf<HabitCompletionStat>()

        for (habit in habits) {
            val oldestEntryDate = habit.computedEntries.getKnown().lastOrNull()?.date ?: continue
            val rangeStart = if (oldestEntryDate.isNewerThan(start)) oldestEntryDate else start

            var habitTotalDays = 0
            var habitCompletedDays = 0

            var current = rangeStart
            while (current <= end) {
                val entry = habit.computedEntries.get(current)
                if (entry.value != Entry.SKIP) {
                    habitTotalDays++
                    val isCompleted = isHabitCompleted(habit, entry)
                    if (isCompleted) {
                        habitCompletedDays++
                    }

                    // Focus minutes to hours
                    if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_LEAST && habit.unit.isMinuteUnit()) {
                        val valDouble = if (entry.value != Entry.UNKNOWN) entry.value / 1000.0 else 0.0
                        val hours = valDouble / 60.0
                        totalFocusHours += hours
                        val blockId = habit.blockId
                        sphereFocusHours[blockId] = (sphereFocusHours[blockId] ?: 0.0) + hours
                    }

                    // Limit violations check
                    if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_MOST) {
                        if (entry.value != Entry.UNKNOWN) {
                            val value = entry.value / 1000.0
                            if (value > habit.targetValue) {
                                limitViolationsCount++
                            }
                        }
                    }
                }
                current = current.plus(1)
            }

            if (habitTotalDays > 0) {
                completedDaysCount += habitCompletedDays
                totalDaysCount += habitTotalDays
                habitCompletionStats.add(HabitCompletionStat(habit, habitCompletedDays, habitTotalDays))
            }
        }

        // Card 1: General Stats
        val (summaryCard, summaryContent) = createCard(getString(R.string.overview))
        val summaryText = TextView(requireContext()).apply {
            text = buildString {
                val percentage = if (totalDaysCount > 0) (completedDaysCount * 100f / totalDaysCount).roundToInt() else 0
                append("Успешность выполнения: $percentage%\n")
                append("Всего отметок: $completedDaysCount из $totalDaysCount дней\n")
                if (totalFocusHours > 0) {
                    val formattedHours = String.format(Locale.US, "%.1f", totalFocusHours)
                    append("Общее время фокуса: $formattedHours ч\n")
                }
                if (limitViolationsCount > 0) {
                    append("Превышений лимитов: $limitViolationsCount раз")
                }
            }
            textSize = 16f
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
        }
        summaryContent.addView(summaryText)
        binding.reportContentContainer.addView(summaryCard)

        // Card 2: Focus by sphere
        if (sphereFocusHours.isNotEmpty()) {
            val (focusCard, focusContent) = createCard(getString(R.string.reports_focus_by_sphere))
            for ((blockId, hours) in sphereFocusHours.entries.sortedByDescending { it.value }) {
                val block = blocksMap[blockId] ?: fallbackBlock
                val blockName = getLocalizedBlockName(block)
                val formattedHours = String.format(Locale.US, "%.1f", hours)
                val row = createSphereRow(block.color, blockName, "$formattedHours ч")
                focusContent.addView(row)
            }
            binding.reportContentContainer.addView(focusCard)
        }

        // Card 3: Habits Details
        if (habitCompletionStats.isNotEmpty()) {
            val (detailsCard, detailsContent) = createCard("Частота выполнения привычек")
            for (stat in habitCompletionStats.sortedByDescending { it.completedDays * 100f / it.totalDays }) {
                val block = blocksMap[stat.habit.blockId] ?: fallbackBlock
                val percentage = (stat.completedDays * 100f / stat.totalDays).roundToInt()
                val subtitle = "${getLocalizedBlockName(block)} • ${stat.completedDays} / ${stat.totalDays} дн. ($percentage%)"
                val row = createHabitDetailRow(block.color, stat.habit.name, subtitle)
                detailsContent.addView(row)
            }
            binding.reportContentContainer.addView(detailsCard)
        }
    }

    private fun isHabitCompleted(habit: Habit, entry: Entry): Boolean {
        if (habit.type == HabitType.NUMERICAL) {
            if (entry.value == Entry.UNKNOWN) return false
            val value = entry.value / 1000.0
            return when (habit.targetType) {
                NumericalHabitType.AT_LEAST -> value >= habit.targetValue
                NumericalHabitType.AT_MOST -> value <= habit.targetValue
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

    private fun createSphereRow(color: PaletteColor, name: String, valueText: String): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
        }

        val dot = View(requireContext()).apply {
            val size = dp(12f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(12f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeSwitcher.currentTheme.color(color).toInt())
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
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(sres.getColor(android.R.attr.textColorPrimary))
        }

        row.addView(dot)
        row.addView(nameView)
        row.addView(valueView)
        return row
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

    private data class HabitCompletionStat(
        val habit: Habit,
        val completedDays: Int,
        val totalDays: Int
    )

    private fun dp(value: Float) = binding.root.dp(value)

    companion object {
        private const val STATE_TAB = "reports.tab"
        private const val STATE_YEAR = "reports.year"
        private const val STATE_MONTH = "reports.month"
        private const val STATE_DAY = "reports.day"
    }
}

/** Compatibility entry point for existing internal intents. */
class ReportsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.intent(this, MainDestination.REPORTS))
        finish()
    }
}
