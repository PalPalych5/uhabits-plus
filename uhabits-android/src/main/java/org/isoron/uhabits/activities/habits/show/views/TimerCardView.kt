package org.isoron.uhabits.activities.habits.show.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertHealth
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.isoron.uhabits.utils.StyledResources

class TimerCardView : LinearLayout {
    private var habit: Habit? = null
    private var manager: TimerSessionManager? = null
    private val listener = TimerSessionManager.Listener { updateUIState() }

    private lateinit var stopwatchTab: TextView
    private lateinit var pomodoroTab: TextView
    private lateinit var statusView: TextView
    private lateinit var alertWarningRow: LinearLayout
    private lateinit var alertWarningText: TextView
    private lateinit var alertWarningButton: Button
    private lateinit var sceneContainer: FrameLayout
    private lateinit var normalContainer: LinearLayout
    private lateinit var timeDisplay: TextView
    private lateinit var editorContainer: LinearLayout
    private lateinit var focusEditorTab: TextView
    private lateinit var breakEditorTab: TextView
    private lateinit var minuteWheel: MinuteWheelView
    private lateinit var buttons: LinearLayout
    private lateinit var startPauseBtn: Button
    private lateinit var takeBreakBtn: Button
    private lateinit var finishBtn: Button
    private lateinit var resetBtn: Button
    private var activeColor: Int = 0
    private var isEditingDuration = false
    private var isEditingBreak = false
    private var transitionInProgress = false
    private var sceneAnimator: ValueAnimator? = null
    private var requestNotificationPermission: ((onReady: () -> Unit) -> Unit)? = null
    private var alertHealthProvider: (() -> PomodoroAlertHealth)? = null
    private var onFixAlertIssue: ((PomodoroAlertIssue) -> Unit)? = null
    private var currentAlertIssue: PomodoroAlertIssue? = null

    constructor(context: Context) : super(context) { initView() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { initView() }

    private fun initView() {
        orientation = VERTICAL
        val pad = dp(16f).toInt()
        setPadding(pad, pad, pad, pad)

        val modeSelector = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        stopwatchTab = modeTab { switchMode(TimerMode.STOPWATCH) }
        pomodoroTab = modeTab { switchMode(TimerMode.POMODORO) }
        modeSelector.addView(stopwatchTab, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(16f).toInt()
        })
        modeSelector.addView(pomodoroTab, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(modeSelector, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8f).toInt() })

        statusView = TextView(context).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(statusView, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(8f).toInt()
        })

        alertWarningRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        alertWarningText = TextView(context).apply {
            textSize = 13f
            setTextColor(StyledResources(context).getColor(R.attr.contrast60))
        }
        alertWarningButton = actionButton {
            currentAlertIssue?.let { onFixAlertIssue?.invoke(it) }
        }.apply {
            text = context.getString(R.string.pomodoro_alert_fix)
            minHeight = dp(40f).toInt()
            minimumHeight = dp(40f).toInt()
        }
        alertWarningRow.addView(alertWarningText, LayoutParams(0, WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8f).toInt()
        })
        alertWarningRow.addView(alertWarningButton, LayoutParams(WRAP_CONTENT, dp(40f).toInt()))
        addView(alertWarningRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(8f).toInt()
        })

        sceneContainer = FrameLayout(context)
        normalContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }

        timeDisplay = TextView(context).apply {
            textSize = 48f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
            contentDescription = context.getString(R.string.pomodoro_edit_duration)
            setOnClickListener { enterDurationEditor() }
        }
        normalContainer.addView(timeDisplay, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(16f).toInt()
        })

        editorContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val editorTabs = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        focusEditorTab = editorTab(R.string.pomodoro_focus_title) { showEditorPhase(false) }
        breakEditorTab = editorTab(R.string.pomodoro_break_title) { showEditorPhase(true) }
        editorTabs.addView(focusEditorTab, buttonParams())
        editorTabs.addView(breakEditorTab, LayoutParams(WRAP_CONTENT, dp(40f).toInt()))
        editorContainer.addView(editorTabs, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        minuteWheel = MinuteWheelView(context).apply {
            contentDescription = context.getString(R.string.pomodoro_minute_wheel)
            onMinuteChanged = { minute -> saveEditedMinute(minute) }
        }
        editorContainer.addView(minuteWheel, LayoutParams(MATCH_PARENT, dp(132f).toInt()))
        editorContainer.addView(
            editorTab(R.string.pomodoro_done) { exitDurationEditor() },
            LayoutParams(WRAP_CONTENT, dp(40f).toInt())
        )
        buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        startPauseBtn = actionButton { toggleTimer() }
        takeBreakBtn = actionButton { habit?.let { manager?.takeBreakManually(it) } }
        finishBtn = actionButton { habit?.let { manager?.finish(it) } }
        resetBtn = actionButton { habit?.let { manager?.reset(it) } }
        buttons.addView(startPauseBtn, buttonParams())
        buttons.addView(takeBreakBtn, buttonParams())
        buttons.addView(finishBtn, buttonParams())
        buttons.addView(resetBtn, LayoutParams(WRAP_CONTENT, dp(40f).toInt()))
        normalContainer.addView(buttons, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sceneContainer.addView(
            normalContainer,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        sceneContainer.addView(
            editorContainer,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        addView(sceneContainer, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun setHabit(habit: Habit, manager: TimerSessionManager) {
        if (isAttachedToWindow) this.manager?.removeListener(listener)
        this.habit = habit
        this.manager = manager
        manager.prepare(habit)
        if (isAttachedToWindow) manager.addListener(listener) else updateUIState()
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
        updateUIState()
    }

    fun refreshAlertHealth() = updateUIState()

    fun setColor(color: Int) {
        activeColor = color
        statusView.setTextColor(color)
        timeDisplay.setTextColor(color)
        if (::focusEditorTab.isInitialized) updateEditorTabs()
        updateUIState()
    }

    private fun switchMode(mode: TimerMode) {
        habit?.let { manager?.switchMode(it, mode) }
    }

    private fun toggleTimer() {
        val currentHabit = habit ?: return
        val currentManager = manager ?: return
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

    private fun updateUIState() {
        if (!::timeDisplay.isInitialized) return
        val currentHabit = habit ?: return
        val sharedState = manager?.snapshot() ?: TimerSessionSnapshot()
        val conflict = sharedState.hasActiveSession && sharedState.habitId != currentHabit.id
        val state = if (sharedState.habitId == currentHabit.id || conflict) sharedState else TimerSessionSnapshot()

        updateTimeDisplay(state.displayMillis, state.isOvertime)
        stopwatchTab.text = context.getString(R.string.timer_mode_stopwatch).uppercase()
        pomodoroTab.text = context.getString(R.string.timer_mode_pomodoro).uppercase()
        statusView.text = when {
            conflict -> context.getString(R.string.timer_active_for_other, sharedState.habitName)
            state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK -> context.getString(
                R.string.timer_pomodoro_phase,
                context.getString(R.string.pomodoro_break_title)
            ).uppercase()
            else -> ""
        }
        statusView.visibility = if (statusView.text.isEmpty()) View.GONE else View.VISIBLE

        startPauseBtn.text = when {
            state.isRunning -> context.getString(R.string.timer_pause)
            state.elapsedMillis > 0 -> context.getString(R.string.timer_resume)
            state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK ->
                context.getString(R.string.pomodoro_start_break)
            else -> context.getString(R.string.timer_start)
        }
        takeBreakBtn.text = context.getString(R.string.pomodoro_start_break)
        finishBtn.text = context.getString(R.string.timer_finish)
        resetBtn.text = context.getString(R.string.timer_reset)

        startPauseBtn.isEnabled = !conflict
        takeBreakBtn.visibility = if (state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.FOCUS && state.isOvertime) View.VISIBLE else View.GONE
        takeBreakBtn.isEnabled = !conflict
        finishBtn.visibility = if (state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK) View.GONE else View.VISIBLE
        finishBtn.isEnabled = !conflict && state.elapsedMillis > 0
        resetBtn.isEnabled = !conflict && state.habitId == currentHabit.id && state.hasActiveSession
        val canConfigureDuration = !conflict && !state.isRunning &&
            state.elapsedMillis == 0L && state.phase == PomodoroPhase.FOCUS &&
            state.mode == TimerMode.POMODORO
        timeDisplay.isClickable = canConfigureDuration
        timeDisplay.isFocusable = canConfigureDuration
        if (isEditingDuration && !canConfigureDuration) exitDurationEditor()
        styleTabs(state, conflict)
        styleButtons()
        updateAlertWarning(state, conflict)
    }

    private fun updateAlertWarning(state: TimerSessionSnapshot, conflict: Boolean) {
        currentAlertIssue = null
        if (conflict || state.mode != TimerMode.POMODORO) {
            alertWarningRow.visibility = View.GONE
            return
        }
        val health = alertHealthProvider?.invoke() ?: run {
            alertWarningRow.visibility = View.GONE
            return
        }
        currentAlertIssue = when {
            !health.notificationsEnabled -> PomodoroAlertIssue.NOTIFICATIONS_DISABLED
            !health.channelEnabled -> PomodoroAlertIssue.CHANNEL_DISABLED
            !health.hasSound && !health.hasVibration -> PomodoroAlertIssue.CHANNEL_SILENT
            !health.exactAlarmsEnabled -> PomodoroAlertIssue.EXACT_ALARMS_DISABLED
            else -> null
        }
        val issue = currentAlertIssue
        if (issue == null) {
            alertWarningRow.visibility = View.GONE
            return
        }
        alertWarningText.setText(
            when (issue) {
                PomodoroAlertIssue.NOTIFICATIONS_DISABLED -> R.string.pomodoro_alert_notifications_disabled
                PomodoroAlertIssue.CHANNEL_DISABLED -> R.string.pomodoro_alert_channel_disabled
                PomodoroAlertIssue.CHANNEL_SILENT -> R.string.pomodoro_alert_channel_silent
                PomodoroAlertIssue.EXACT_ALARMS_DISABLED -> R.string.pomodoro_alert_exact_disabled
            }
        )
        alertWarningRow.visibility = View.VISIBLE
        val resources = StyledResources(context)
        styleButton(alertWarningButton, activeColor, resources)
    }

    private fun enterDurationEditor() {
        if (transitionInProgress) return
        val currentHabit = habit ?: return
        val state = manager?.snapshot() ?: return
        if (state.habitId != currentHabit.id || state.mode != TimerMode.POMODORO ||
            state.isRunning || state.elapsedMillis > 0L || state.phase != PomodoroPhase.FOCUS
        ) return
        isEditingDuration = true
        isEditingBreak = false
        showEditorPhase(false)
        animateEditorVisibility(showEditor = true)
    }

    private fun exitDurationEditor() {
        if (!isEditingDuration || transitionInProgress) return
        isEditingDuration = false
        animateEditorVisibility(showEditor = false)
        updateUIState()
    }

    private fun showEditorPhase(editBreak: Boolean) {
        if (transitionInProgress) return
        val currentHabit = habit ?: return
        val currentManager = manager ?: return
        isEditingBreak = editBreak
        val durations = currentManager.durations(currentHabit)
        if (editBreak) {
            minuteWheel.setRange(1, 60, durations.breakMinutes)
        } else {
            minuteWheel.setRange(1, 180, durations.focusMinutes)
        }
        updateEditorTabs()
    }

    private fun saveEditedMinute(minute: Int) {
        val currentHabit = habit ?: return
        val currentManager = manager ?: return
        val durations = currentManager.durations(currentHabit)
        if (isEditingBreak) {
            currentManager.updateDurations(currentHabit, durations.focusMinutes, minute)
        } else {
            currentManager.updateDurations(currentHabit, minute, durations.breakMinutes)
        }
    }

    private fun animateEditorVisibility(showEditor: Boolean) {
        sceneAnimator?.cancel()
        normalContainer.animate().cancel()
        editorContainer.animate().cancel()

        val oldLayer = if (showEditor) normalContainer else editorContainer
        val newLayer = if (showEditor) editorContainer else normalContainer
        transitionInProgress = true
        timeDisplay.isClickable = false
        startPauseBtn.isEnabled = false
        takeBreakBtn.isEnabled = false
        finishBtn.isEnabled = false
        resetBtn.isEnabled = false
        focusEditorTab.isEnabled = false
        breakEditorTab.isEnabled = false
        stopwatchTab.isEnabled = false
        pomodoroTab.isEnabled = false

        oldLayer.animate()
            .alpha(0f)
            .setDuration(80L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                oldLayer.visibility = View.GONE
                oldLayer.alpha = 1f
                newLayer.alpha = 0f
                newLayer.visibility = View.INVISIBLE

                val width = sceneContainer.width.coerceAtLeast(1)
                newLayer.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val startHeight = sceneContainer.height.coerceAtLeast(1)
                val targetHeight = newLayer.measuredHeight.coerceAtLeast(1)
                newLayer.visibility = View.VISIBLE
                newLayer.animate()
                    .alpha(1f)
                    .setStartDelay(20L)
                    .setDuration(160L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                sceneAnimator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
                    duration = 180L
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        sceneContainer.layoutParams = sceneContainer.layoutParams.apply {
                            height = animator.animatedValue as Int
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            finishSceneTransition(showEditor)
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            finishSceneTransition(showEditor)
                        }
                    })
                    start()
                }
            }
            .start()
    }

    private fun finishSceneTransition(showEditor: Boolean) {
        val visibleLayer = if (showEditor) editorContainer else normalContainer
        val hiddenLayer = if (showEditor) normalContainer else editorContainer
        visibleLayer.animate().cancel()
        visibleLayer.alpha = 1f
        visibleLayer.visibility = View.VISIBLE
        hiddenLayer.animate().cancel()
        hiddenLayer.alpha = 1f
        hiddenLayer.visibility = View.GONE
        sceneContainer.layoutParams = sceneContainer.layoutParams.apply { height = WRAP_CONTENT }
        sceneAnimator = null
        transitionInProgress = false
        focusEditorTab.isEnabled = true
        breakEditorTab.isEnabled = true
        updateUIState()
    }

    private fun updateEditorTabs() {
        val inactiveColor = StyledResources(context).getColor(R.attr.contrast60)
        focusEditorTab.setTextColor(if (!isEditingBreak) activeColor else inactiveColor)
        breakEditorTab.setTextColor(if (isEditingBreak) activeColor else inactiveColor)
        focusEditorTab.alpha = if (!isEditingBreak) 1f else 0.55f
        breakEditorTab.alpha = if (isEditingBreak) 1f else 0.55f
    }

    private fun styleTabs(state: TimerSessionSnapshot, conflict: Boolean) {
        val inactiveColor = StyledResources(context).getColor(R.attr.contrast60)
        val canSwitch = !isEditingDuration && !transitionInProgress && !conflict && !state.isRunning &&
            state.elapsedMillis == 0L && state.phase == PomodoroPhase.FOCUS
        stopwatchTab.isEnabled = canSwitch
        pomodoroTab.isEnabled = canSwitch
        stopwatchTab.setTextColor(if (state.mode == TimerMode.STOPWATCH) activeColor else inactiveColor)
        pomodoroTab.setTextColor(if (state.mode == TimerMode.POMODORO) activeColor else inactiveColor)
        stopwatchTab.alpha = if (state.mode == TimerMode.STOPWATCH) 1f else if (canSwitch) 0.6f else 0.3f
        pomodoroTab.alpha = if (state.mode == TimerMode.POMODORO) 1f else if (canSwitch) 0.6f else 0.3f
    }

    private fun updateTimeDisplay(millis: Long, isOvertime: Boolean = false) {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val prefix = if (isOvertime) "+" else ""
        timeDisplay.text = if (hours > 0) {
            String.format("%s%02d:%02d:%02d", prefix, hours, minutes, seconds)
        } else {
            String.format("%s%02d:%02d", prefix, minutes, seconds)
        }
    }

    private fun styleButtons() {
        val resources = StyledResources(context)
        styleButton(startPauseBtn, if (startPauseBtn.isEnabled) activeColor else resources.getColor(R.attr.contrast40), resources)
        styleButton(takeBreakBtn, if (takeBreakBtn.isEnabled) activeColor else resources.getColor(R.attr.contrast40), resources)
        styleButton(finishBtn, if (finishBtn.isEnabled) activeColor else resources.getColor(R.attr.contrast40), resources)
        styleButton(resetBtn, if (resetBtn.isEnabled) activeColor else resources.getColor(R.attr.contrast40), resources)
    }

    private fun styleButton(button: Button, color: Int, resources: StyledResources) {
        button.setTextColor(color)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f)
            setStroke(dp(1.5f).toInt(), color)
            setColor(resources.getColor(R.attr.contrast0))
        }
    }

    private fun modeTab(onClick: () -> Unit) = TextView(context).apply {
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
        setOnClickListener { onClick() }
    }

    private fun editorTab(textRes: Int, onClick: () -> Unit) = TextView(context).apply {
        text = context.getString(textRes).uppercase()
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
        setOnClickListener { onClick() }
    }

    private fun actionButton(onClick: () -> Unit) = Button(context).apply {
        setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
        setOnClickListener { onClick() }
    }

    private fun buttonParams() = LayoutParams(WRAP_CONTENT, dp(40f).toInt()).apply {
        rightMargin = dp(8f).toInt()
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        manager?.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        sceneAnimator?.cancel()
        normalContainer.animate().cancel()
        editorContainer.animate().cancel()
        manager?.removeListener(listener)
        super.onDetachedFromWindow()
    }
}
