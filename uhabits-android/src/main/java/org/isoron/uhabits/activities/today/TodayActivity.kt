/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.activities.today

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.tasks.TaskDetailBottomSheet
import org.isoron.uhabits.activities.tasks.TaskFocusBottomSheet
import org.isoron.uhabits.activities.tasks.TaskThemeUtils
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.today.TodayFocusItem
import org.isoron.uhabits.core.today.TodayGroupingMode
import org.isoron.uhabits.core.today.TodayHabitItem
import org.isoron.uhabits.core.today.TodayScreenData
import org.isoron.uhabits.core.today.TodayTaskItem
import org.isoron.uhabits.core.today.TodayTaskUrgency
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.applyToolbarInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TodayActivity : AppCompatActivity(), TimerSessionManager.Listener {

    private lateinit var toolbar: Toolbar
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private lateinit var palette: SettingsThemePalette

    private lateinit var cardVariantSelector: LinearLayout
    private lateinit var btnVariantSectioned: TextView
    private lateinit var btnVariantUnified: TextView

    private lateinit var cardFocus: LinearLayout
    private lateinit var tvFocusTargetTitle: TextView
    private lateinit var tvFocusTimerDisplay: TextView
    private lateinit var tvFocusModeBadge: TextView
    private lateinit var btnFocusPauseResume: TextView
    private lateinit var btnFocusFinish: TextView
    private lateinit var btnFocusOpen: TextView

    private lateinit var cardTasks: LinearLayout
    private lateinit var tvTasksHeaderTitle: TextView
    private lateinit var tvTasksHeaderCount: TextView
    private lateinit var dividerTasks: View
    private lateinit var layoutTasksContent: LinearLayout
    private lateinit var tvTasksEmpty: TextView

    private lateinit var cardHabits: LinearLayout
    private lateinit var tvHabitsHeaderTitle: TextView
    private lateinit var tvHabitsHeaderCount: TextView
    private lateinit var dividerHabits: View
    private lateinit var layoutHabitsContent: LinearLayout
    private lateinit var tvHabitsEmpty: TextView

    private var groupingMode = TodayGroupingMode.SECTIONED
    private val appComponent get() = (applicationContext as HabitsApplication).component
    private val prefs get() = appComponent.preferences

    private val dateFormatRu = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, prefs)
        themeSwitcher.apply()
        BaseExceptionHandler.install(this)

        setContentView(R.layout.activity_today)
        findViewById<View>(R.id.coordinatorLayout).applyRootViewInsets()

        toolbar = findViewById(R.id.toolbar)
        toolbar.applyToolbarInsets()
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        palette = MainTabsThemeBridge.resolve(this)
        bindViews()
        setupListeners()
        applyTheme()

        savedInstanceState?.let {
            val modeOrdinal = it.getInt("groupingMode", TodayGroupingMode.SECTIONED.ordinal)
            groupingMode = TodayGroupingMode.entries.getOrElse(modeOrdinal) { TodayGroupingMode.SECTIONED }
        }

        updateVariantButtons()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        palette = MainTabsThemeBridge.resolve(this)
        applyTheme()
        appComponent.timerSessionManager.addListener(this)
        loadData()
    }

    override fun onPause() {
        super.onPause()
        appComponent.timerSessionManager.removeListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("groupingMode", groupingMode.ordinal)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onTimerChanged() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                loadData()
            }
        }
    }

    private fun bindViews() {
        cardVariantSelector = findViewById(R.id.cardVariantSelector)
        btnVariantSectioned = findViewById(R.id.btnVariantSectioned)
        btnVariantUnified = findViewById(R.id.btnVariantUnified)

        cardFocus = findViewById(R.id.cardFocus)
        tvFocusTargetTitle = findViewById(R.id.tvFocusTargetTitle)
        tvFocusTimerDisplay = findViewById(R.id.tvFocusTimerDisplay)
        tvFocusModeBadge = findViewById(R.id.tvFocusModeBadge)
        btnFocusPauseResume = findViewById(R.id.btnFocusPauseResume)
        btnFocusFinish = findViewById(R.id.btnFocusFinish)
        btnFocusOpen = findViewById(R.id.btnFocusOpen)

        cardTasks = findViewById(R.id.cardTasks)
        tvTasksHeaderTitle = findViewById(R.id.tvTasksHeaderTitle)
        tvTasksHeaderCount = findViewById(R.id.tvTasksHeaderCount)
        dividerTasks = findViewById(R.id.dividerTasks)
        layoutTasksContent = findViewById(R.id.layoutTasksContent)
        tvTasksEmpty = findViewById(R.id.tvTasksEmpty)

        cardHabits = findViewById(R.id.cardHabits)
        tvHabitsHeaderTitle = findViewById(R.id.tvHabitsHeaderTitle)
        tvHabitsHeaderCount = findViewById(R.id.tvHabitsHeaderCount)
        dividerHabits = findViewById(R.id.dividerHabits)
        layoutHabitsContent = findViewById(R.id.layoutHabitsContent)
        tvHabitsEmpty = findViewById(R.id.tvHabitsEmpty)
    }

    private fun setupListeners() {
        btnVariantSectioned.setOnClickListener {
            if (groupingMode != TodayGroupingMode.SECTIONED) {
                groupingMode = TodayGroupingMode.SECTIONED
                updateVariantButtons()
                loadData()
            }
        }

        btnVariantUnified.setOnClickListener {
            if (groupingMode != TodayGroupingMode.UNIFIED_LIST) {
                groupingMode = TodayGroupingMode.UNIFIED_LIST
                updateVariantButtons()
                loadData()
            }
        }

        btnFocusPauseResume.setOnClickListener {
            appComponent.timerSessionManager.pauseOrResumeCurrent()
            loadData()
        }

        btnFocusFinish.setOnClickListener {
            appComponent.timerSessionManager.finishCurrent()
            loadData()
        }

        btnFocusOpen.setOnClickListener {
            val snapshot = appComponent.timerSessionManager.snapshot()
            val target = appComponent.timerSessionManager.currentTarget
            if (target?.kind == FocusPrimaryTargetType.TASK) {
                TaskFocusBottomSheet.newInstance(TaskId(target.id), snapshot.habitName, autoStart = false)
                    .show(supportFragmentManager, TaskFocusBottomSheet.TAG)
            } else if (snapshot.habitId != null && snapshot.habitId != -1L) {
                appComponent.habitList.getById(snapshot.habitId!!)?.let { habit ->
                    startActivity(org.isoron.uhabits.intents.IntentFactory().startShowHabitActivity(this, habit))
                }
            }
        }
    }

    private fun updateVariantButtons() {
        val selectedBg = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = dp(8f)
            setStroke(dpInt(1f), palette.divider)
        }
        val unselectedBg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }

        if (groupingMode == TodayGroupingMode.SECTIONED) {
            btnVariantSectioned.background = selectedBg
            btnVariantSectioned.setTextColor(palette.accent)
            btnVariantUnified.background = unselectedBg
            btnVariantUnified.setTextColor(palette.onSurfaceVariant)
        } else {
            btnVariantUnified.background = selectedBg
            btnVariantUnified.setTextColor(palette.accent)
            btnVariantSectioned.background = unselectedBg
            btnVariantSectioned.setTextColor(palette.onSurfaceVariant)
        }
    }

    private fun applyTheme() {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        findViewById<View>(R.id.coordinatorLayout).setBackgroundColor(palette.background)

        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }

        toolbar.setTitleTextColor(palette.onSurface)
        toolbar.setSubtitleTextColor(palette.onSurfaceVariant)

        cardVariantSelector.background = GradientDrawable().apply {
            setColor(MainTabsThemeBridge.withAlpha(palette.surface, 0.6f))
            cornerRadius = dp(10f)
            setStroke(dpInt(1f), palette.divider)
        }

        val cardBg = {
            GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12f)
                setStroke(dpInt(1f), palette.divider)
            }
        }

        cardFocus.background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = dp(12f)
            setStroke(dpInt(1.5f), palette.accent)
        }

        cardTasks.background = cardBg()
        cardHabits.background = cardBg()

        tvFocusTargetTitle.setTextColor(palette.onSurface)
        tvFocusTimerDisplay.setTextColor(palette.accent)
        findViewById<TextView>(R.id.tvFocusWorkingLabel).setTextColor(palette.onSurfaceVariant)

        val btnStyle = { tv: TextView ->
            tv.setTextColor(palette.onSurface)
            tv.background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
                cornerRadius = dp(8f)
            }
        }
        btnStyle(btnFocusPauseResume)
        btnStyle(btnFocusFinish)
        btnStyle(btnFocusOpen)

        tvTasksHeaderTitle.setTextColor(palette.onSurface)
        tvTasksHeaderCount.setTextColor(palette.onSurfaceVariant)
        dividerTasks.setBackgroundColor(palette.divider)
        tvTasksEmpty.setTextColor(palette.onSurfaceVariant)

        tvHabitsHeaderTitle.setTextColor(palette.onSurface)
        tvHabitsHeaderCount.setTextColor(palette.onSurfaceVariant)
        dividerHabits.setBackgroundColor(palette.divider)
        tvHabitsEmpty.setTextColor(palette.onSurfaceVariant)
    }

    private fun loadData() {
        val todayService = appComponent.todayEvaluationService ?: return
        val timerManager = appComponent.timerSessionManager
        val snapshot = timerManager.snapshot()

        val activeFocus = if (snapshot.hasActiveSession) {
            val target = timerManager.currentTarget
            TodayFocusItem(
                targetTitle = snapshot.habitName.ifBlank { "Фокус-сессия" },
                mode = snapshot.mode,
                elapsedMillis = snapshot.elapsedMillis,
                displayTimeMillis = snapshot.displayMillis,
                isRunning = snapshot.isRunning,
                isBreak = snapshot.phase == org.isoron.uhabits.core.timer.PomodoroPhase.BREAK,
                targetType = target?.kind,
                taskId = if (target?.kind == FocusPrimaryTargetType.TASK) TaskId(target.id) else null,
                habitId = snapshot.habitId
            )
        } else null

        val data = todayService.evaluateToday(activeFocus)
        render(data)
    }

    private fun render(data: TodayScreenData) {
        // 1. Subtitle with localized date
        val dateText = try {
            val cal = java.util.Calendar.getInstance().apply {
                set(data.todayDate.year, data.todayDate.month - 1, data.todayDate.day)
            }
            val formatted = dateFormatRu.format(cal.time)
            formatted.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        } catch (e: Exception) {
            "Сегодня, ${data.todayDate}"
        }
        toolbar.subtitle = dateText

        // 2. Active Focus Card
        val focus = data.activeFocus
        if (focus != null) {
            cardFocus.visibility = View.VISIBLE
            tvFocusTargetTitle.text = focus.targetTitle
            tvFocusTimerDisplay.text = formatTimerDisplay(focus.displayTimeMillis)

            tvFocusModeBadge.text = if (focus.mode == TimerMode.POMODORO) {
                if (focus.isBreak) "ПЕРЕРЫВ" else "ПОМОДОРО"
            } else "СЕКУНДОМЕР"
            tvFocusModeBadge.setTextColor(palette.accent)
            tvFocusModeBadge.background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.15f))
                cornerRadius = dp(6f)
            }

            btnFocusPauseResume.text = if (focus.isRunning) getString(R.string.today_eval_focus_pause) else getString(R.string.today_eval_focus_resume)
        } else {
            cardFocus.visibility = View.GONE
        }

        // 3. Tasks Section
        renderTasks(data)

        // 4. Habits Section
        renderHabits(data)
    }

    private fun renderTasks(data: TodayScreenData) {
        layoutTasksContent.removeAllViews()
        val totalCount = data.totalActionableTasksCount
        tvTasksHeaderCount.text = "$totalCount " + pluralize(totalCount, "задача", "задачи", "задач")

        if (totalCount == 0 && data.completedTodayTasks.isEmpty()) {
            tvTasksEmpty.visibility = View.VISIBLE
            return
        }
        tvTasksEmpty.visibility = View.GONE

        if (groupingMode == TodayGroupingMode.SECTIONED) {
            // A. Hard overdue
            if (data.hardOverdueTasks.isNotEmpty()) {
                addTasksSubheader(
                    title = "ПРОСРОЧЕНО (${data.hardOverdueTasks.size})",
                    color = ContextCompat.getColor(this, R.color.red_500)
                )
                data.hardOverdueTasks.forEach { item ->
                    layoutTasksContent.addView(createTaskRow(item))
                }
            }

            // B. Missed plan (Distinct from overdue!)
            if (data.missedPlanTasks.isNotEmpty()) {
                addTasksSubheader(
                    title = "ПРОПУЩЕН ПЛАН (${data.missedPlanTasks.size})",
                    color = ContextCompat.getColor(this, R.color.amber_700)
                )
                data.missedPlanTasks.forEach { item ->
                    layoutTasksContent.addView(createTaskRow(item))
                }
            }

            // C. Planned today
            if (data.plannedTodayTasks.isNotEmpty()) {
                addTasksSubheader(
                    title = "НА СЕГОДНЯ (${data.plannedTodayTasks.size})",
                    color = palette.accent
                )
                data.plannedTodayTasks.forEach { item ->
                    layoutTasksContent.addView(createTaskRow(item))
                }
            }

            // D. Completed today
            if (data.completedTodayTasks.isNotEmpty()) {
                addTasksSubheader(
                    title = "ЗАВЕРШЕНО СЕГОДНЯ (${data.completedTodayTasks.size})",
                    color = palette.onSurfaceVariant
                )
                data.completedTodayTasks.forEach { item ->
                    layoutTasksContent.addView(createTaskRow(item))
                }
            }
        } else {
            // Unified list ordered by urgency
            data.allPendingTasksOrdered.forEach { item ->
                layoutTasksContent.addView(createTaskRow(item, showUrgencyBadge = true))
            }
            if (data.completedTodayTasks.isNotEmpty()) {
                addTasksSubheader(
                    title = "ЗАВЕРШЕНО СЕГОДНЯ (${data.completedTodayTasks.size})",
                    color = palette.onSurfaceVariant
                )
                data.completedTodayTasks.forEach { item ->
                    layoutTasksContent.addView(createTaskRow(item, showUrgencyBadge = false))
                }
            }
        }
    }

    private fun addTasksSubheader(title: String, color: Int) {
        val tv = TextView(this).apply {
            text = title
            textSize = 11.5f
            textStyleBold()
            setTextColor(color)
            setPadding(0, dpInt(8f), 0, dpInt(4f))
            letterSpacing = 0.04f
        }
        layoutTasksContent.addView(tv)
    }

    private fun createTaskRow(item: TodayTaskItem, showUrgencyBadge: Boolean = false): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            minimumHeight = dpInt(52f)
            setPadding(0, dpInt(4f), 0, dpInt(4f))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }

        val isCompleted = item.task.isCompleted

        // 1. Checkmark button (48dp interaction target)
        val btnCheck = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(48f), dpInt(48f))
            val circle = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dpInt(22f), dpInt(22f), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isCompleted) {
                        setColor(palette.accent)
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dpInt(2f), if (item.urgency == TodayTaskUrgency.HARD_OVERDUE) ContextCompat.getColor(context, R.color.red_500) else palette.onSurfaceVariant)
                    }
                }
            }
            addView(circle)

            if (isCompleted) {
                val iv = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dpInt(14f), dpInt(14f), Gravity.CENTER)
                    setImageResource(R.drawable.ic_action_check)
                    setColorFilter(Color.WHITE)
                }
                addView(iv)
            }

            setOnClickListener {
                appComponent.userTaskService.setCompleted(item.task.id, !isCompleted)
                loadData()
            }
        }
        row.addView(btnCheck)

        // 2. Center Content (Title + Context/Meta)
        val layoutContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dpInt(4f), 0, dpInt(8f), 0)
        }

        val tvTitle = TextView(this).apply {
            text = item.task.title
            textSize = 15f
            setTextColor(if (isCompleted) palette.onSurfaceVariant else palette.onSurface)
            if (isCompleted) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }
        layoutContent.addView(tvTitle)

        // Metadata line
        val metaLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpInt(2f), 0, 0)
        }

        // Optional urgency badge for Unified mode
        if (showUrgencyBadge && !isCompleted) {
            val badge = TextView(this).apply {
                textSize = 10.5f
                textStyleBold()
                setPadding(dpInt(5f), dpInt(1f), dpInt(5f), dpInt(1f))
                val (txt, bgCol, txtCol) = when (item.urgency) {
                    TodayTaskUrgency.HARD_OVERDUE -> Triple(
                        "ПРОСРОЧЕНО",
                        ContextCompat.getColor(context, R.color.red_500),
                        Color.WHITE
                    )
                    TodayTaskUrgency.MISSED_PLAN -> Triple(
                        "ПРОПУЩЕН ПЛАН",
                        MainTabsThemeBridge.withAlpha(ContextCompat.getColor(context, R.color.amber_700), 0.18f),
                        ContextCompat.getColor(context, R.color.amber_700)
                    )
                    TodayTaskUrgency.PLANNED_TODAY -> Triple(
                        "СЕГОДНЯ",
                        MainTabsThemeBridge.withAlpha(palette.accent, 0.15f),
                        palette.accent
                    )
                    else -> Triple("", Color.TRANSPARENT, Color.TRANSPARENT)
                }
                text = txt
                setTextColor(txtCol)
                background = GradientDrawable().apply {
                    setColor(bgCol)
                    cornerRadius = dp(4f)
                }
                val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.marginEnd = dpInt(6f)
                layoutParams = lp
            }
            metaLine.addView(badge)
        }

        // Container tag
        if (!item.containerName.isNullOrBlank()) {
            val tvContainer = TextView(this).apply {
                text = "[${item.containerName}]"
                textSize = 12f
                setTextColor(palette.accent)
                setPadding(0, 0, dpInt(6f), 0)
            }
            metaLine.addView(tvContainer)
        }

        // Date/Deadline/Subtask info
        val taskDeadline = item.task.deadline
        val plannedDate = item.task.plannedDate
        val metaText = when {
            item.urgency == TodayTaskUrgency.HARD_OVERDUE && taskDeadline != null -> {
                "Дедлайн: ${formatDateTime(taskDeadline)}"
            }
            item.urgency == TodayTaskUrgency.MISSED_PLAN && plannedDate != null -> {
                val dateStr = "${plannedDate.day} ${monthNameRu(plannedDate.month)}"
                "План: $dateStr (просрочен)"
            }
            plannedDate != null -> {
                "План: сегодня"
            }
            else -> ""
        }

        val subtaskInfo = if (item.subtasksTotal > 0) {
            " · подзадачи: ${item.subtasksCompleted}/${item.subtasksTotal}"
        } else ""

        val tvMeta = TextView(this).apply {
            text = metaText + subtaskInfo
            textSize = 12f
            setTextColor(
                when (item.urgency) {
                    TodayTaskUrgency.HARD_OVERDUE -> ContextCompat.getColor(context, R.color.red_500)
                    TodayTaskUrgency.MISSED_PLAN -> ContextCompat.getColor(context, R.color.amber_700)
                    else -> palette.onSurfaceVariant
                }
            )
        }
        metaLine.addView(tvMeta)
        layoutContent.addView(metaLine)
        row.addView(layoutContent)

        // 3. Start Focus action icon (if not completed)
        if (!isCompleted) {
            val btnFocus = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpInt(44f), dpInt(44f))
                val iv = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dpInt(18f), dpInt(18f), Gravity.CENTER)
                    setImageResource(R.drawable.ic_settings_clock)
                    setColorFilter(palette.onSurfaceVariant)
                }
                addView(iv)
                setOnClickListener {
                    TaskFocusBottomSheet.newInstance(item.task.id, item.task.title, autoStart = true)
                        .show(supportFragmentManager, TaskFocusBottomSheet.TAG)
                }
            }
            row.addView(btnFocus)
        }

        // Row tap opens Task Detail
        row.setOnClickListener {
            val detail = TaskDetailBottomSheet.newInstance(item.task.id)
            detail.onTaskUpdatedListener = { loadData() }
            detail.show(supportFragmentManager, "task_detail")
        }

        return row
    }

    private fun renderHabits(data: TodayScreenData) {
        layoutHabitsContent.removeAllViews()
        val totalCount = data.allHabitsOrdered.size
        tvHabitsHeaderCount.text = "$totalCount " + pluralize(totalCount, "привычка", "привычки", "привычек")

        if (totalCount == 0) {
            tvHabitsEmpty.visibility = View.VISIBLE
            return
        }
        tvHabitsEmpty.visibility = View.GONE

        // Boolean Habits
        data.booleanHabits.forEach { item ->
            layoutHabitsContent.addView(createBooleanHabitRow(item, data.todayDate))
        }

        // Numerical Habits
        data.numericalHabits.forEach { item ->
            layoutHabitsContent.addView(createNumericalHabitRow(item, data.todayDate))
        }
    }

    private fun createBooleanHabitRow(item: TodayHabitItem, today: LocalDate): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            minimumHeight = dpInt(52f)
            setPadding(0, dpInt(4f), 0, dpInt(4f))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }

        val habitColor = themeSwitcher.currentTheme?.color(item.habit.color)?.toInt() ?: palette.accent

        // 1. 48dp Checkmark / Toggle Button using canonical CheckmarkButtonView
        val btnCheck = CheckmarkButtonView(this, prefs).apply {
            color = habitColor
            value = item.todayEntryValue
            notes = item.todayNotes
            onToggle = { newValue, newNotes ->
                appComponent.commandRunner.run(
                    CreateRepetitionCommand(appComponent.habitList, item.habit, today, newValue, newNotes)
                )
                loadData()
            }
            onEdit = {
                performToggle()
            }
        }
        row.addView(btnCheck)

        // 2. Content (Title + Container + Status)
        val layoutContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dpInt(4f), 0, dpInt(8f), 0)
        }

        val tvTitle = TextView(this).apply {
            text = item.habit.name
            textSize = 15f
            setTextColor(palette.onSurface)
        }
        layoutContent.addView(tvTitle)

        val metaLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpInt(2f), 0, 0)
        }

        if (!item.containerName.isNullOrBlank()) {
            val tvContainer = TextView(this).apply {
                text = "[${item.containerName}]"
                textSize = 12f
                setTextColor(palette.accent)
                setPadding(0, 0, dpInt(6f), 0)
            }
            metaLine.addView(tvContainer)
        }

        val tvStatus = TextView(this).apply {
            text = item.progressFormatted
            textSize = 12f
            setTextColor(
                when (item.goalStatus) {
                    HabitGoalStatus.SATISFIED -> habitColor
                    HabitGoalStatus.SKIPPED -> palette.onSurfaceVariant
                    else -> palette.onSurfaceVariant
                }
            )
        }
        metaLine.addView(tvStatus)
        layoutContent.addView(metaLine)
        row.addView(layoutContent)

        row.setOnClickListener {
            startActivity(org.isoron.uhabits.intents.IntentFactory().startShowHabitActivity(this, item.habit))
        }

        return row
    }

    private fun createNumericalHabitRow(item: TodayHabitItem, today: LocalDate): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            minimumHeight = dpInt(56f)
            setPadding(dpInt(8f), dpInt(6f), dpInt(8f), dpInt(6f))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }

        val habitColor = themeSwitcher.currentTheme?.color(item.habit.color)?.toInt() ?: palette.accent

        // Top line: Name + Container + Progress value text
        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        val tvTitle = TextView(this).apply {
            text = item.habit.name
            textSize = 15f
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        topLine.addView(tvTitle)

        if (!item.containerName.isNullOrBlank()) {
            val tvContainer = TextView(this).apply {
                text = "[${item.containerName}]"
                textSize = 12f
                setTextColor(palette.accent)
                setPadding(0, 0, dpInt(8f), 0)
            }
            topLine.addView(tvContainer)
        }

        val tvProgress = TextView(this).apply {
            text = item.progressFormatted
            textSize = 13f
            textStyleBold()
            setTextColor(if (item.goalStatus == HabitGoalStatus.SATISFIED) habitColor else palette.onSurface)
        }
        topLine.addView(tvProgress)
        row.addView(topLine)

        // Progress Bar (Declarative horizontal weighted linear bar)
        val target = item.targetValue ?: 1.0
        val total = (item.totalValue ?: 0L) / 1000.0
        val progressFrac = (total / target).coerceIn(0.0, 1.0).toFloat()

        val progressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dpInt(6f)).apply {
                topMargin = dpInt(6f)
                bottomMargin = dpInt(4f)
            }
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.15f))
                cornerRadius = dp(3f)
            }

            if (progressFrac > 0.001f) {
                val fill = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, progressFrac)
                    background = GradientDrawable().apply {
                        setColor(habitColor)
                        cornerRadius = dp(3f)
                    }
                }
                addView(fill)
            }
            if (progressFrac < 0.999f) {
                val remaining = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f - progressFrac)
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                    }
                }
                addView(remaining)
            }
        }
        row.addView(progressBar)

        // Bottom detail line: Focus attribution badge + manual breakdown
        if (item.contributionValue > 0L) {
            val bottomLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

            val focusMins = item.contributionValue / 1000L
            val badgeFocus = TextView(this).apply {
                text = "+$focusMins мин фокуса"
                textSize = 11f
                textStyleBold()
                setTextColor(habitColor)
                background = GradientDrawable().apply {
                    setColor(MainTabsThemeBridge.withAlpha(habitColor, 0.15f))
                    cornerRadius = dp(4f)
                }
                setPadding(dpInt(6f), dpInt(1f), dpInt(6f), dpInt(1f))
            }
            bottomLine.addView(badgeFocus)
            row.addView(bottomLine)
        }

        row.setOnClickListener {
            startActivity(org.isoron.uhabits.intents.IntentFactory().startShowHabitActivity(this, item.habit))
        }

        return row
    }

    private fun TextView.textStyleBold() {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Float): Float = TaskThemeUtils.dp(this, value)
    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(this, value)

    private fun formatTimerDisplay(millis: Long): String {
        val totalSec = millis / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return if (min >= 60) {
            val hr = min / 60L
            val remMin = min % 60L
            String.format(Locale.getDefault(), "%d:%02d:%02d", hr, remMin, sec)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        }
    }

    private fun formatDateTime(millis: Long): String {
        return try {
            timeFormat.format(Date(millis))
        } catch (e: Exception) {
            ""
        }
    }

    private fun monthNameRu(month: Int): String = when (month) {
        1 -> "янв."
        2 -> "февр."
        3 -> "мар."
        4 -> "апр."
        5 -> "мая"
        6 -> "июн."
        7 -> "июл."
        8 -> "авг."
        9 -> "сент."
        10 -> "окт."
        11 -> "нояб."
        12 -> "дек."
        else -> ""
    }

    private fun pluralize(count: Int, one: String, few: String, many: String): String {
        val rem10 = count % 10
        val rem100 = count % 100
        return when {
            rem100 in 11..19 -> many
            rem10 == 1 -> one
            rem10 in 2..4 -> few
            else -> many
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, TodayActivity::class.java)
    }
}
