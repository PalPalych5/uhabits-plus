/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertHealth
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.TaskId

/**
 * Host FrameLayout embedding the shared Compose [FocusTimerContent].
 *
 * Preserves public API compatibility for ShowHabitView and existing tests
 * while fully migrating timer presentation to Jetpack Compose.
 */
class TimerCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var habit: Habit? = null
    private var taskId: TaskId? = null
    private var taskTitle: String = ""
    private var manager: TimerSessionManager? = null
    private var activeColor by mutableIntStateOf(MainTabsThemeBridge.resolve(context).accent)
    private var requestNotificationPermission: ((onReady: () -> Unit) -> Unit)? = null
    private var alertHealthProvider: (() -> PomodoroAlertHealth)? = null
    private var onFixAlertIssue: ((PomodoroAlertIssue) -> Unit)? = null

    val currentHabit: Habit? get() = habit

    private var timerUiState by mutableStateOf(TimerUiState())
    private var composeColors by mutableStateOf<UHabitComposeColors?>(null)

    private fun resolveComposeColors(): UHabitComposeColors {
        val palette = MainTabsThemeBridge.resolve(context)
        return UHabitComposeColors.fromPalette(
            palette,
            cardCornerRadiusDp = palette.habitsCardCornerRadius,
            showCardBorders = palette.showHabitCardBorders
        )
    }

    fun updateColors(colors: UHabitComposeColors = resolveComposeColors()) {
        composeColors = colors
    }

    private val listener = TimerSessionManager.Listener {
        post { syncState() }
    }

    private val composeView: ComposeView

    init {
        composeColors = resolveComposeColors()

        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val currentColors = composeColors ?: return@setContent
                UHabitComposeTheme(currentColors) {
                    val alertIssue = alertHealthProvider?.invoke()?.let { health ->
                        when {
                            !health.notificationsEnabled -> PomodoroAlertIssue.NOTIFICATIONS_DISABLED
                            !health.channelEnabled -> PomodoroAlertIssue.CHANNEL_DISABLED
                            !health.hasSound && !health.hasVibration -> PomodoroAlertIssue.CHANNEL_SILENT
                            !health.exactAlarmsEnabled -> PomodoroAlertIssue.EXACT_ALARMS_DISABLED
                            else -> null
                        }
                    }

                    FocusTimerContent(
                        state = timerUiState,
                        colors = currentColors.copy(accent = activeColor),
                        onSwitchMode = { mode ->
                            val currentMgr = manager ?: return@FocusTimerContent
                            taskId?.let { currentMgr.switchTaskMode(it, taskTitle, mode); return@FocusTimerContent }
                            habit?.let { currentMgr.switchMode(it, mode) }
                        },
                        onTogglePlayPause = {
                            toggleTimer()
                        },
                        onReset = { manager?.resetCurrent() },
                        onFinish = { manager?.finishCurrent() },
                        onTakeBreak = { manager?.takeBreakCurrent() },
                        onUpdateDurations = { focus, rest ->
                            val currentMgr = manager ?: return@FocusTimerContent
                            taskId?.let { currentMgr.updateTaskDurations(it, taskTitle, focus, rest); return@FocusTimerContent }
                            habit?.let { currentMgr.updateDurations(it, focus, rest) }
                        },
                        alertIssue = alertIssue,
                        onFixAlertIssue = { issue -> onFixAlertIssue?.invoke(issue) }
                    )
                }
            }
        }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        manager?.addListener(listener)
        syncState()
    }

    override fun onDetachedFromWindow() {
        manager?.removeListener(listener)
        super.onDetachedFromWindow()
    }

    fun setHabit(habit: Habit, manager: TimerSessionManager) {
        if (isAttachedToWindow) this.manager?.removeListener(listener)
        this.habit = habit
        this.taskId = null
        this.manager = manager
        manager.prepare(habit)
        if (isAttachedToWindow) manager.addListener(listener)
        syncState()
    }

    fun updateHabit(habit: Habit) {
        this.habit = habit
        this.taskId = null
        manager?.prepare(habit)
        syncState()
    }

    fun setTask(id: TaskId, title: String, manager: TimerSessionManager) {
        if (isAttachedToWindow) this.manager?.removeListener(listener)
        this.habit = null
        this.taskId = id
        this.taskTitle = title
        this.manager = manager
        manager.prepareTask(id, title)
        if (isAttachedToWindow) manager.addListener(listener)
        syncState()
    }

    fun setNotificationPermissionRequester(requester: (onReady: () -> Unit) -> Unit) {
        requestNotificationPermission = requester
    }

    fun setAlertHealth(
        provider: () -> PomodoroAlertHealth,
        onFixIssue: (PomodoroAlertIssue) -> Unit
    ) {
        alertHealthProvider = provider
        onFixAlertIssue = onFixIssue
        syncState()
    }

    fun refreshAlertHealth() = syncState()

    fun setColor(color: Int) {
        activeColor = color
        val palette = MainTabsThemeBridge.resolve(context)
        composeColors = UHabitComposeColors.fromPalette(palette).copy(accent = color)
        syncState()
    }

    fun startPreparedTask() {
        if (taskId != null && manager?.snapshot()?.hasActiveSession == false) toggleTimer()
    }

    private fun toggleTimer() {
        val currentManager = manager ?: return
        val currentTask = taskId
        val currentHabit = habit
        if (currentTask == null && currentHabit == null) return
        val action: () -> Unit = {
            if (currentTask != null) currentManager.toggleTask(currentTask, taskTitle)
            else currentHabit?.let { currentManager.startOrPause(it) }
            Unit
        }
        val requester = requestNotificationPermission
        if (requester != null && !currentManager.snapshot().hasActiveSession) {
            requester(action)
        } else {
            action()
        }
    }

    private fun syncState() {
        timerUiState = computeTimerUiState(
            manager = manager,
            isTask = taskId != null,
            taskId = taskId,
            habit = habit
        )
    }
}
