/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
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
import org.isoron.uhabits.activities.habits.show.views.ShowHabitToolbar
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
    private var habitUnit: String = ""
    private var isTimerEnabled: Boolean = false
    private var currentFocusProgress by mutableStateOf<FocusProgressPresentation?>(null)

    private var surfaceSpec by mutableStateOf(resolveSurface())
    private var toolbarMenu by mutableStateOf<ShowHabitMenu?>(null)

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
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(composeColors) {
                    val state = showHabitState
                    Column(
                        Modifier.fillMaxSize()
                            .background(ComposeColor(surfaceSpec.canvasColor))
                            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)),
                    ) {
                        val menu = toolbarMenu
                        if (menu != null) {
                            ShowHabitToolbar(
                                title = state?.title.orEmpty(),
                                surface = surfaceSpec,
                                onBack = { (context as? AppCompatActivity)?.finish() },
                                onEdit = menu::edit,
                                onMore = { bounds -> menu.showOverflowMenu(this@ShowHabitView, bounds) },
                            )
                        }
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
                        ) {
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
        }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyWindowAppearance()
    }

    internal fun setToolbarMenu(menu: ShowHabitMenu) {
        toolbarMenu = menu
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

    private fun resolveSurface(): ShowHabitSurfaceSpec {
        val palette = MainTabsThemeBridge.resolve(context)
        return resolveShowHabitSurfaceSpec(palette, palette.isClassicSectionLayout)
    }

    private fun applyWindowAppearance() {
        val activity = context as? AppCompatActivity ?: return
        activity.window.statusBarColor = surfaceSpec.statusBarColor
        activity.window.navigationBarColor = surfaceSpec.navigationBarColor
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = surfaceSpec.isLightSystemBars
            isAppearanceLightNavigationBars = surfaceSpec.isLightSystemBars
        }
    }

    fun setState(data: ShowHabitState) {
        isEffectiveReadUnavailable = false
        effectiveUnavailableNoticeText = null

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
        val palette = MainTabsThemeBridge.resolve(context)
        surfaceSpec = resolveShowHabitSurfaceSpec(palette, palette.isClassicSectionLayout)
        applyWindowAppearance()

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
