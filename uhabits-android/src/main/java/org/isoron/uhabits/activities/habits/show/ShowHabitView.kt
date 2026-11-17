/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsControllerCompat
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertHealth
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.PomodoroCompletionNotifier
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.activities.habits.show.views.HabitDetailsScreen
import org.isoron.uhabits.activities.habits.show.views.TimerCardView
import org.isoron.uhabits.activities.habits.show.views.TimerUiState
import org.isoron.uhabits.activities.habits.show.views.computeTimerUiState
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.isMinuteUnit
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.databinding.ShowHabitBinding
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.applyToolbarInsets

data class ShowHabitSurfaceSpec(
    val canvasColor: Int,
    val toolbarColor: Int,
    val statusBarColor: Int,
    val navigationBarColor: Int,
    val dividerColor: Int,
    val showToolbarDivider: Boolean,
    val iconColor: Int,
    val isLightSystemBars: Boolean
)

fun resolveShowHabitSurfaceSpec(
    palette: SettingsThemePalette,
    isClassicSectionLayout: Boolean
): ShowHabitSurfaceSpec {
    val classicCanvas = if (palette.isPureBlack) Color.BLACK else palette.surface
    val canvasColor = if (isClassicSectionLayout) classicCanvas else palette.background
    return ShowHabitSurfaceSpec(
        canvasColor = canvasColor,
        toolbarColor = canvasColor,
        statusBarColor = canvasColor,
        navigationBarColor = canvasColor,
        dividerColor = palette.divider,
        showToolbarDivider = isClassicSectionLayout,
        iconColor = palette.onSurface,
        isLightSystemBars = !palette.isDark
    )
}

class ShowHabitView(context: Context) : FrameLayout(context) {
    private val binding = ShowHabitBinding.inflate(LayoutInflater.from(context))
    private var habitUnit: String = ""
    private var isTimerEnabled: Boolean = false
    private var isNumerical: Boolean = false
    private var currentFocusProgress by mutableStateOf<FocusProgressPresentation?>(null)

    var toolbarForegroundColor: Int = Color.WHITE
        private set
    private var currentTitleColor: Int = Color.WHITE

    // Presentation state
    private var showHabitState by mutableStateOf<ShowHabitState?>(null)
    private var isEffectiveReadUnavailable by mutableStateOf(false)
    private var effectiveUnavailableNoticeText by mutableStateOf<String?>(null)
    private var composeColors by mutableStateOf(resolveComposeColors())

    // Timer state & management
    private var habit: Habit? = null
    private var manager: TimerSessionManager? = null
    private var requestNotificationPermission: ((onReady: () -> Unit) -> Unit)? = null
    private var alertHealthProvider: (() -> PomodoroAlertHealth)? = null
    private var onFixAlertIssue: ((PomodoroAlertIssue) -> Unit)? = null
    private var isTimerVisible by mutableStateOf(false)
    private var timerActiveColor by mutableIntStateOf(0)
    private var timerUiState by mutableStateOf<TimerUiState?>(null)
    private var timerAlertIssue by mutableStateOf<PomodoroAlertIssue?>(null)

    // Dedicated timer card wrapper instance for contract & test compatibility
    private val timerCardInstance = TimerCardView(context)
    val timerCard: TimerCardView get() = timerCardInstance
    val currentTimerHabit: Habit? get() = habit

    private var presenter: ShowHabitPresenter? = null
    private val scoreLabels = resources.getStringArray(R.array.strengthIntervalNames)
    private val barNumericalLabels = resources.getStringArray(R.array.strengthIntervalNames)
    private val barBoolLabels = resources.getStringArray(R.array.strengthIntervalNamesWithoutDay)

    private val timerListener = TimerSessionManager.Listener {
        post { syncTimerState() }
    }

    private fun resolveComposeColors(): UHabitComposeColors {
        val palette = MainTabsThemeBridge.resolve(context)
        return UHabitComposeColors.fromPalette(
            palette,
            cardCornerRadiusDp = palette.habitsCardCornerRadius,
            showCardBorders = palette.showHabitCardBorders
        )
    }

    init {
        binding.toolbar.applyToolbarInsets()
        binding.composeView.applyBottomInset()
        addView(binding.root)

        binding.composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(composeColors) {
                    val state = showHabitState
                    if (state != null) {
                        HabitDetailsScreen(
                            state = state,
                            colors = composeColors,
                            focusProgress = currentFocusProgress,
                            isEffectiveReadUnavailable = isEffectiveReadUnavailable,
                            effectiveUnavailableNoticeText = effectiveUnavailableNoticeText,
                            isTimerVisible = isTimerVisible,
                            timerUiState = timerUiState,
                            timerActiveColor = timerActiveColor,
                            timerAlertIssue = timerAlertIssue,
                            onToggleTimer = { toggleTimer() },
                            onSwitchTimerMode = { mode -> habit?.let { manager?.switchMode(it, mode) } },
                            onResetTimer = { manager?.resetCurrent() },
                            onFinishTimer = { manager?.finishCurrent() },
                            onTakeTimerBreak = { manager?.takeBreakCurrent() },
                            onUpdateTimerDurations = { focus, rest -> habit?.let { manager?.updateDurations(it, focus, rest) } },
                            onFixTimerAlertIssue = { issue -> onFixAlertIssue?.invoke(issue) },
                            scoreLabels = scoreLabels,
                            onScorePeriodSelected = { pos -> presenter?.scoreCardPresenter?.onSpinnerPosition(pos) },
                            barNumericalLabels = barNumericalLabels,
                            barBoolLabels = barBoolLabels,
                            onBarNumericalPeriodSelected = { pos -> presenter?.barCardPresenter?.onNumericalSpinnerPosition(pos) },
                            onBarBoolPeriodSelected = { pos -> presenter?.barCardPresenter?.onBoolSpinnerPosition(pos) },
                            onEditHistory = { presenter?.historyCardPresenter?.onClickEditButton() },
                            onEffectiveNoticeClick = {
                                val provenance = when {
                                    state.quantityUnavailable -> R.string.effective_read_quantity_unavailable
                                    state.hasIncompleteCoverage -> R.string.effective_read_incomplete
                                    else -> R.string.effective_read_provenance
                                }
                                AlertDialog.Builder(context)
                                    .setTitle(R.string.focus_credit_breakdown)
                                    .setMessage(provenance)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        manager?.addListener(timerListener)
        syncTimerState()
    }

    override fun onDetachedFromWindow() {
        manager?.removeListener(timerListener)
        super.onDetachedFromWindow()
    }

    private fun toggleTimer() {
        val currentManager = manager ?: return
        val currentHabit = habit ?: return
        val action: () -> Unit = {
            currentManager.startOrPause(currentHabit)
        }
        val requester = requestNotificationPermission
        if (requester != null && !currentManager.snapshot().hasActiveSession) {
            requester(action)
        } else {
            action()
        }
    }

    private fun syncTimerState() {
        timerUiState = computeTimerUiState(
            manager = manager,
            isTask = false,
            taskId = null,
            habit = habit
        )
        timerAlertIssue = alertHealthProvider?.invoke()?.let { health ->
            when {
                !health.notificationsEnabled -> PomodoroAlertIssue.NOTIFICATIONS_DISABLED
                !health.channelEnabled -> PomodoroAlertIssue.CHANNEL_DISABLED
                !health.hasSound && !health.hasVibration -> PomodoroAlertIssue.CHANNEL_SILENT
                !health.exactAlarmsEnabled -> PomodoroAlertIssue.EXACT_ALARMS_DISABLED
                else -> null
            }
        }
    }

    private fun setupCohesiveToolbar(data: ShowHabitState) {
        val activity = context as? AppCompatActivity
        val app = context.applicationContext as? org.isoron.uhabits.HabitsApplication
        val isClassic = app?.component?.preferences?.isClassicSectionLayout ?: false
        val palette = MainTabsThemeBridge.resolve(context)
        val surfaceSpec = resolveShowHabitSurfaceSpec(palette, isClassic)

        val iconColor = surfaceSpec.iconColor
        val titleColor = surfaceSpec.iconColor

        toolbarForegroundColor = iconColor
        currentTitleColor = titleColor

        binding.container.setBackgroundColor(surfaceSpec.canvasColor)
        binding.composeView.setBackgroundColor(surfaceSpec.canvasColor)
        binding.toolbarDivider.setBackgroundColor(surfaceSpec.dividerColor)
        binding.toolbarDivider.visibility = if (surfaceSpec.showToolbarDivider) VISIBLE else GONE

        binding.toolbar.elevation = 0f
        binding.toolbar.title = data.title
        binding.toolbar.setBackgroundColor(surfaceSpec.toolbarColor)
        binding.toolbar.setTitleTextColor(titleColor)

        if (activity != null) {
            activity.window.statusBarColor = surfaceSpec.statusBarColor
            activity.window.navigationBarColor = surfaceSpec.navigationBarColor
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            activity.supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_settings_back)
            val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            insetsController.isAppearanceLightStatusBars = surfaceSpec.isLightSystemBars
            insetsController.isAppearanceLightNavigationBars = surfaceSpec.isLightSystemBars
        }
        binding.toolbar.setTitleTextColor(currentTitleColor)
        binding.toolbar.navigationIcon?.setTint(iconColor)
        binding.toolbar.overflowIcon?.setTint(iconColor)
        for (i in 0 until binding.toolbar.menu.size()) {
            val item = binding.toolbar.menu.getItem(i)
            item.icon?.setTint(iconColor)
            (item.actionView as? ImageView)?.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    fun harmonizeToolbar(onSurfaceColor: Int) {
        binding.toolbar.setTitleTextColor(currentTitleColor)
        binding.toolbar.navigationIcon?.setTint(toolbarForegroundColor)
        binding.toolbar.overflowIcon?.setTint(toolbarForegroundColor)
        for (i in 0 until binding.toolbar.menu.size()) {
            val item = binding.toolbar.menu.getItem(i)
            item.icon?.setTint(toolbarForegroundColor)
            (item.actionView as? ImageView)?.imageTintList = ColorStateList.valueOf(toolbarForegroundColor)
        }
    }

    fun setState(data: ShowHabitState) {
        isNumerical = data.isNumerical
        isEffectiveReadUnavailable = false
        effectiveUnavailableNoticeText = null
        setupCohesiveToolbar(data)

        showHabitState = data

        timerActiveColor = data.theme.color(data.color).toInt()
        val timerVisible = data.isNumerical && isTimerEnabled && habitUnit.isMinuteUnit()
        isTimerVisible = timerVisible
        timerCardInstance.setColor(timerActiveColor)
        timerCardInstance.visibility = if (timerVisible) View.VISIBLE else View.GONE
    }

    /** Integrity failure must not leave a previous/manual-only result visible. Editing stays manual. */
    fun setEffectiveReadUnavailable() {
        isEffectiveReadUnavailable = true
        effectiveUnavailableNoticeText = context.getString(R.string.effective_read_unavailable)
    }

    fun setListener(presenter: ShowHabitPresenter) {
        this.presenter = presenter
    }

    fun initTimer(
        habit: Habit,
        manager: TimerSessionManager,
        completionNotifier: PomodoroCompletionNotifier,
        requestNotificationPermission: (onReady: () -> Unit) -> Unit,
        onFixAlertIssue: (PomodoroAlertIssue) -> Unit
    ) {
        if (isAttachedToWindow) this.manager?.removeListener(timerListener)
        habitUnit = habit.unit
        isTimerEnabled = habit.timerEnabled
        this.habit = habit
        this.manager = manager
        this.requestNotificationPermission = requestNotificationPermission
        this.alertHealthProvider = completionNotifier::health
        this.onFixAlertIssue = onFixAlertIssue
        manager.prepare(habit)
        if (isAttachedToWindow) manager.addListener(timerListener)

        timerCardInstance.setNotificationPermissionRequester(requestNotificationPermission)
        timerCardInstance.setAlertHealth(completionNotifier::health, onFixAlertIssue)
        timerCardInstance.setHabit(habit, manager)
        syncTimerState()
    }

    fun updateHabit(habit: Habit) {
        habitUnit = habit.unit
        isTimerEnabled = habit.timerEnabled
        this.habit = habit
        manager?.prepare(habit)
        timerCardInstance.updateHabit(habit)
        syncTimerState()
    }

    fun refreshTimerAlertHealth() {
        timerCardInstance.refreshAlertHealth()
        syncTimerState()
    }

    fun resetCalendarEditMode() = Unit

    fun updateAppearance() {
        val app = context.applicationContext as? org.isoron.uhabits.HabitsApplication
        val isClassic = app?.component?.preferences?.isClassicSectionLayout ?: false
        val palette = MainTabsThemeBridge.resolve(context)
        val surfaceSpec = resolveShowHabitSurfaceSpec(palette, isClassic)

        toolbarForegroundColor = surfaceSpec.iconColor
        currentTitleColor = surfaceSpec.iconColor

        binding.container.setBackgroundColor(surfaceSpec.canvasColor)
        binding.composeView.setBackgroundColor(surfaceSpec.canvasColor)
        binding.toolbarDivider.setBackgroundColor(surfaceSpec.dividerColor)
        binding.toolbarDivider.visibility = if (surfaceSpec.showToolbarDivider) VISIBLE else GONE
        binding.toolbar.setBackgroundColor(surfaceSpec.toolbarColor)
        binding.toolbar.setTitleTextColor(currentTitleColor)

        (context as? AppCompatActivity)?.let { activity ->
            activity.window.statusBarColor = surfaceSpec.statusBarColor
            activity.window.navigationBarColor = surfaceSpec.navigationBarColor
            val insetsController = WindowInsetsControllerCompat(activity.window, binding.root)
            insetsController.isAppearanceLightStatusBars = surfaceSpec.isLightSystemBars
            insetsController.isAppearanceLightNavigationBars = surfaceSpec.isLightSystemBars
        }

        harmonizeToolbar(toolbarForegroundColor)

        val habitColors = UHabitComposeColors.fromPalette(
            palette,
            cardCornerRadiusDp = palette.habitsCardCornerRadius,
            showCardBorders = palette.showHabitCardBorders
        )
        composeColors = habitColors
        timerCardInstance.updateColors(habitColors)
    }

    fun setFocusProgress(progress: FocusProgressPresentation?, unavailable: Boolean = false) {
        this.currentFocusProgress = progress
    }
}
