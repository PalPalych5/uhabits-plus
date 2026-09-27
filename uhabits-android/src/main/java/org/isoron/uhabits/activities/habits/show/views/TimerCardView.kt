package org.isoron.uhabits.activities.habits.show.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.os.Build
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import androidx.core.graphics.ColorUtils
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
    private val standardInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val expandInterpolator = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)
    private val collapseInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    private var currentPrimaryIconRes: Int = 0
    private var isPrimaryIconTransitioning: Boolean = false
    private var isCollapsingRow: Boolean = false
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
    private lateinit var startPauseBtn: ImageButton
    private lateinit var takeBreakBtn: ImageButton
    private lateinit var finishBtn: ImageButton
    private lateinit var resetBtn: ImageButton
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
            rightMargin = dp(8f).toInt()
        })
        modeSelector.addView(pomodoroTab, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(modeSelector, LayoutParams(MATCH_PARENT, dp(48f).toInt()).apply { bottomMargin = dp(8f).toInt() })

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
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
            clipChildren = false
            clipToPadding = false
        }
        val iconSize = dp(48f).toInt()
        val iconGap = dp(16f).toInt()
        val deltaX = dp(64f)
        val resources = StyledResources(context)
        val secondarySurface = resources.getColor(R.attr.contrast0)
        val secondaryStroke = resources.getColor(R.attr.contrast40)
        val strokeWidth = dp(1f).toInt()
        val baseRipple = (resources.getColor(R.attr.contrast100) and 0x00FFFFFF) or 0x18000000

        resetBtn = timerIconButton(R.drawable.ic_timer_reset) {
            habit?.let { manager?.reset(it) }
        }.apply {
            background = createCircularRipple(secondarySurface, baseRipple, secondaryStroke, strokeWidth)
            contentDescription = context.getString(R.string.timer_reset)
            tooltipText = contentDescription
            visibility = View.INVISIBLE
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            translationX = deltaX
        }
        startPauseBtn = timerIconButton(R.drawable.ic_timer_play) { toggleTimer() }.apply {
            background = createCircularRipple(secondarySurface, baseRipple, secondaryStroke, strokeWidth)
            translationZ = dp(2f)
        }
        takeBreakBtn = timerIconButton(R.drawable.ic_settings_pomodoro_break) {
            habit?.let { manager?.takeBreakManually(it) }
        }.apply {
            background = createCircularRipple(secondarySurface, baseRipple, secondaryStroke, strokeWidth)
            contentDescription = context.getString(R.string.pomodoro_start_break)
            tooltipText = contentDescription
            visibility = View.GONE
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            translationX = 0f
        }
        finishBtn = timerIconButton(R.drawable.ic_timer_finish) {
            habit?.let { manager?.finish(it) }
        }.apply {
            background = createCircularRipple(secondarySurface, baseRipple, secondaryStroke, strokeWidth)
            contentDescription = context.getString(R.string.timer_finish)
            tooltipText = contentDescription
            visibility = View.INVISIBLE
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            translationX = -deltaX
        }

        buttons.addView(resetBtn, LayoutParams(iconSize, iconSize).apply { marginEnd = iconGap })
        buttons.addView(startPauseBtn, LayoutParams(iconSize, iconSize).apply { marginEnd = iconGap })
        buttons.addView(takeBreakBtn, LayoutParams(iconSize, iconSize).apply { marginEnd = iconGap })
        buttons.addView(finishBtn, LayoutParams(iconSize, iconSize))
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
        if (isAttachedToWindow) manager.addListener(listener)
        updateUIState()
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
        timeDisplay.background = RippleDrawable(
            ColorStateList.valueOf((color and 0x00FFFFFF) or 0x22000000),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f)
                setColor(Color.WHITE)
            }
        )
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

        val isOvertimeFocus = state.mode == TimerMode.POMODORO &&
            state.phase == PomodoroPhase.FOCUS &&
            state.isOvertime &&
            !conflict

        val hasElapsed = state.elapsedMillis > 0L
        val isSessionActive = state.hasActiveSession || hasElapsed
        val isCurrentHabitSession = state.habitId == currentHabit.id

        startPauseBtn.isEnabled = !conflict
        val primaryIconRes = if (state.isRunning) R.drawable.ic_timer_pause else R.drawable.ic_timer_play
        updatePrimaryIcon(primaryIconRes)
        val primaryAction = when {
            state.isRunning -> context.getString(R.string.timer_pause)
            hasElapsed -> context.getString(R.string.timer_resume)
            state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK ->
                context.getString(R.string.pomodoro_start_break)
            else -> context.getString(R.string.timer_start)
        }
        startPauseBtn.contentDescription = primaryAction
        startPauseBtn.tooltipText = primaryAction

        val canReset = !conflict && isCurrentHabitSession && (isSessionActive || state.phase == PomodoroPhase.BREAK)
        resetBtn.isEnabled = canReset

        takeBreakBtn.isEnabled = isOvertimeFocus

        val isBreakPhase = state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK
        val canFinish = !conflict && (isBreakPhase || hasElapsed)
        finishBtn.isEnabled = canFinish

        updateButtonRow(canReset, isOvertimeFocus, canFinish)
        val finishAction = if (isBreakPhase) {
            context.getString(R.string.pomodoro_break_completed)
        } else {
            context.getString(R.string.timer_finish)
        }
        finishBtn.contentDescription = finishAction
        finishBtn.tooltipText = finishAction
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
        val disabledColor = resources.getColor(R.attr.contrast40)

        startPauseBtn.imageTintList = ColorStateList.valueOf(
            if (startPauseBtn.isEnabled) activeColor else disabledColor
        )
        resetBtn.imageTintList = ColorStateList.valueOf(
            if (resetBtn.isEnabled) activeColor else disabledColor
        )
        takeBreakBtn.imageTintList = ColorStateList.valueOf(
            if (takeBreakBtn.isEnabled) activeColor else disabledColor
        )
        finishBtn.imageTintList = ColorStateList.valueOf(
            if (finishBtn.isEnabled) activeColor else disabledColor
        )
    }

    private fun updatePrimaryIcon(iconRes: Int) {
        if (currentPrimaryIconRes == iconRes) return
        val wasSet = currentPrimaryIconRes != 0
        currentPrimaryIconRes = iconRes
        if (isAttachedToWindow && wasSet) {
            if (isPrimaryIconTransitioning) {
                startPauseBtn.setImageResource(iconRes)
                startPauseBtn.imageAlpha = 255
                return
            }
            isPrimaryIconTransitioning = true
            val fadeOut = ObjectAnimator.ofInt(startPauseBtn, "imageAlpha", 255, 0).apply {
                duration = 80L
                interpolator = standardInterpolator
            }
            val fadeIn = ObjectAnimator.ofInt(startPauseBtn, "imageAlpha", 0, 255).apply {
                duration = 140L
                interpolator = expandInterpolator
            }
            fadeOut.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startPauseBtn.setImageResource(iconRes)
                    fadeIn.start()
                }
            })
            fadeIn.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isPrimaryIconTransitioning = false
                }
            })
            fadeOut.start()
        } else {
            startPauseBtn.setImageResource(iconRes)
            startPauseBtn.imageAlpha = 255
        }
    }

    private fun updateButtonRow(
        canReset: Boolean,
        isOvertimeFocus: Boolean,
        canFinish: Boolean
    ) {
        val deltaX = dp(64f)

        if (!isAttachedToWindow) {
            resetBtn.visibility = if (canReset) View.VISIBLE else View.INVISIBLE
            resetBtn.alpha = if (canReset) 1f else 0f
            resetBtn.scaleX = if (canReset) 1f else 0.5f
            resetBtn.scaleY = if (canReset) 1f else 0.5f
            resetBtn.translationX = if (canReset) 0f else deltaX

            takeBreakBtn.visibility = if (isOvertimeFocus) View.VISIBLE else View.GONE
            takeBreakBtn.alpha = if (isOvertimeFocus) 1f else 0f
            takeBreakBtn.scaleX = if (isOvertimeFocus) 1f else 0.5f
            takeBreakBtn.scaleY = if (isOvertimeFocus) 1f else 0.5f
            takeBreakBtn.translationX = 0f

            finishBtn.visibility = if (canFinish) View.VISIBLE else View.INVISIBLE
            finishBtn.alpha = if (canFinish) 1f else 0f
            finishBtn.scaleX = if (canFinish) 1f else 0.5f
            finishBtn.scaleY = if (canFinish) 1f else 0.5f
            finishBtn.translationX = if (canFinish) 0f else -deltaX
            return
        }

        val targetAnySideVisible = canReset || canFinish || isOvertimeFocus
        val currently4Buttons = takeBreakBtn.visibility == View.VISIBLE

        if (!targetAnySideVisible) {
            if (isCollapsingRow) return
            val anyCurrentlyVisible = (resetBtn.visibility == View.VISIBLE && resetBtn.alpha > 0.1f) ||
                (finishBtn.visibility == View.VISIBLE && finishBtn.alpha > 0.1f) ||
                (takeBreakBtn.visibility == View.VISIBLE && takeBreakBtn.alpha > 0.1f)

            if (!anyCurrentlyVisible) {
                resetBtn.visibility = View.INVISIBLE
                finishBtn.visibility = View.INVISIBLE
                takeBreakBtn.visibility = View.GONE
                return
            }

            isCollapsingRow = true
            resetBtn.animate().cancel()
            finishBtn.animate().cancel()
            takeBreakBtn.animate().cancel()

            val finishCollapseOffsetX = if (currently4Buttons) -dp(128f) else -dp(64f)

            resetBtn.animate()
                .translationX(deltaX)
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(260L)
                .setInterpolator(collapseInterpolator)
                .start()

            if (currently4Buttons) {
                takeBreakBtn.animate()
                    .translationX(-deltaX)
                    .alpha(0f)
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .setDuration(260L)
                    .setInterpolator(collapseInterpolator)
                    .start()
            }

            finishBtn.animate()
                .translationX(finishCollapseOffsetX)
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(260L)
                .setInterpolator(collapseInterpolator)
                .withEndAction {
                    isCollapsingRow = false
                    resetBtn.visibility = View.INVISIBLE
                    resetBtn.translationX = deltaX
                    resetBtn.alpha = 0f
                    resetBtn.scaleX = 0.5f
                    resetBtn.scaleY = 0.5f

                    finishBtn.visibility = View.INVISIBLE
                    finishBtn.translationX = -deltaX
                    finishBtn.alpha = 0f
                    finishBtn.scaleX = 0.5f
                    finishBtn.scaleY = 0.5f

                    if (currently4Buttons) {
                        takeBreakBtn.visibility = View.GONE
                        takeBreakBtn.translationX = 0f
                        takeBreakBtn.alpha = 0f
                        takeBreakBtn.scaleX = 0.5f
                        takeBreakBtn.scaleY = 0.5f
                        TransitionManager.beginDelayedTransition(
                            buttons,
                            ChangeBounds().apply {
                                duration = 200L
                                interpolator = expandInterpolator
                            }
                        )
                    }
                }
                .start()
            return
        }

        if (isOvertimeFocus && takeBreakBtn.visibility != View.VISIBLE) {
            TransitionManager.beginDelayedTransition(
                buttons,
                ChangeBounds().apply {
                    duration = 300L
                    interpolator = expandInterpolator
                }
            )
            takeBreakBtn.visibility = View.VISIBLE
            takeBreakBtn.alpha = 0f
            takeBreakBtn.scaleX = 0.4f
            takeBreakBtn.scaleY = 0.4f
            takeBreakBtn.translationX = 0f
            takeBreakBtn.animate().cancel()
            takeBreakBtn.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(expandInterpolator)
                .start()
        } else if (!isOvertimeFocus && takeBreakBtn.visibility == View.VISIBLE && !isCollapsingRow) {
            takeBreakBtn.animate().cancel()
            takeBreakBtn.animate()
                .alpha(0f)
                .scaleX(0.4f)
                .scaleY(0.4f)
                .setDuration(220L)
                .setInterpolator(collapseInterpolator)
                .withEndAction {
                    TransitionManager.beginDelayedTransition(
                        buttons,
                        ChangeBounds().apply {
                            duration = 240L
                            interpolator = expandInterpolator
                        }
                    )
                    takeBreakBtn.visibility = View.GONE
                    takeBreakBtn.translationX = 0f
                }
                .start()
        }

        if (canReset && (resetBtn.visibility != View.VISIBLE || resetBtn.alpha < 0.5f)) {
            resetBtn.animate().cancel()
            resetBtn.visibility = View.VISIBLE
            if (resetBtn.alpha < 0.1f) {
                resetBtn.alpha = 0f
                resetBtn.scaleX = 0.5f
                resetBtn.scaleY = 0.5f
                resetBtn.translationX = deltaX
            }
            resetBtn.animate()
                .translationX(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320L)
                .setInterpolator(expandInterpolator)
                .start()
        }

        if (canFinish && (finishBtn.visibility != View.VISIBLE || finishBtn.alpha < 0.5f)) {
            finishBtn.animate().cancel()
            finishBtn.visibility = View.VISIBLE
            if (finishBtn.alpha < 0.1f) {
                finishBtn.alpha = 0f
                finishBtn.scaleX = 0.5f
                finishBtn.scaleY = 0.5f
                finishBtn.translationX = if (isOvertimeFocus) -dp(128f) else -deltaX
            }
            finishBtn.animate()
                .translationX(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320L)
                .setInterpolator(expandInterpolator)
                .start()
        }
    }

    private fun createCircularRipple(
        contentColor: Int,
        rippleColor: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 0
    ): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(contentColor)
            if (strokeColor != null && strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun styleButton(button: Button, color: Int, resources: StyledResources) {
        button.setTextColor(color)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f)
            setStroke(dp(1.5f).toInt(), color)
            setColor(resources.getColor(R.attr.contrast0))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f)
            setColor(Color.WHITE)
        }
        val rippleColor = (color and 0x00FFFFFF) or 0x26000000
        button.background = RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
        button.stateListAnimator = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.defaultFocusHighlightEnabled = false
        }
    }

    private fun modeTab(onClick: () -> Unit) = TextView(context).apply {
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
        val ripple = RippleDrawable(
            ColorStateList.valueOf(StyledResources(context).getColor(R.attr.contrast20)),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f)
                setColor(Color.WHITE)
            }
        )
        background = ripple
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        setOnClickListener { onClick() }
    }

    private fun editorTab(textRes: Int, onClick: () -> Unit) = TextView(context).apply {
        text = context.getString(textRes).uppercase()
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(StyledResources(context).getColor(R.attr.contrast20)),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f)
                setColor(Color.WHITE)
            }
        )
        background = ripple
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        setOnClickListener { onClick() }
    }

    private fun actionButton(onClick: () -> Unit) = Button(context).apply {
        setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
        setOnClickListener { onClick() }
    }

    private fun timerIconButton(iconRes: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(12f).toInt()
        setPadding(pad, pad, pad, pad)
        background = null
        foreground = null
        isFocusable = false
        clipToOutline = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        setOnTouchListener { v, event ->
            if (isEnabled) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.94f)
                            .scaleY(0.94f)
                            .setDuration(80L)
                            .setInterpolator(standardInterpolator)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(120L)
                            .setInterpolator(standardInterpolator)
                            .start()
                    }
                }
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun buttonParams() = LayoutParams(WRAP_CONTENT, dp(40f).toInt()).apply {
        rightMargin = dp(8f).toInt()
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        manager?.addListener(listener)
        updateUIState()
    }

    override fun onDetachedFromWindow() {
        sceneAnimator?.cancel()
        normalContainer.animate().cancel()
        editorContainer.animate().cancel()
        startPauseBtn.animate().cancel()
        resetBtn.animate().cancel()
        takeBreakBtn.animate().cancel()
        finishBtn.animate().cancel()
        isCollapsingRow = false
        isPrimaryIconTransitioning = false
        manager?.removeListener(listener)
        super.onDetachedFromWindow()
    }
}
