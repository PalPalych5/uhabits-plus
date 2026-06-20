package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.isoron.uhabits.R
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
    private lateinit var titleView: TextView
    private lateinit var timeDisplay: TextView
    private lateinit var startPauseBtn: Button
    private lateinit var finishBtn: Button
    private lateinit var resetBtn: Button
    private var activeColor: Int = 0

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

        titleView = TextView(context).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(titleView, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12f).toInt() })

        timeDisplay = TextView(context).apply {
            textSize = 48f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        addView(timeDisplay, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16f).toInt() })

        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        startPauseBtn = actionButton { habit?.let { manager?.startOrPause(it) } }
        finishBtn = actionButton { habit?.let { manager?.finish(it) } }
        resetBtn = actionButton { habit?.let { manager?.reset(it) } }
        buttons.addView(startPauseBtn, buttonParams())
        buttons.addView(finishBtn, buttonParams())
        buttons.addView(resetBtn, LayoutParams(WRAP_CONTENT, dp(40f).toInt()))
        addView(buttons, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun setHabit(habit: Habit, manager: TimerSessionManager) {
        if (isAttachedToWindow) this.manager?.removeListener(listener)
        this.habit = habit
        this.manager = manager
        if (isAttachedToWindow) manager.addListener(listener) else updateUIState()
    }

    fun setColor(color: Int) {
        activeColor = color
        titleView.setTextColor(color)
        timeDisplay.setTextColor(color)
        updateUIState()
    }

    private fun switchMode(mode: TimerMode) {
        habit?.let { manager?.switchMode(it, mode) }
    }

    private fun updateUIState() {
        if (!::timeDisplay.isInitialized) return
        val currentHabit = habit ?: return
        val sharedState = manager?.snapshot() ?: TimerSessionSnapshot()
        val conflict = sharedState.hasActiveSession && sharedState.habitId != currentHabit.id
        val state = if (sharedState.habitId == currentHabit.id || conflict) sharedState else TimerSessionSnapshot()

        updateTimeDisplay(state.displayMillis)
        stopwatchTab.text = context.getString(R.string.timer_mode_stopwatch).uppercase()
        pomodoroTab.text = context.getString(R.string.timer_mode_pomodoro).uppercase()
        titleView.text = when {
            conflict -> context.getString(R.string.timer_active_for_other, sharedState.habitName)
            state.mode == TimerMode.STOPWATCH -> context.getString(R.string.timer_title).uppercase()
            state.phase == PomodoroPhase.FOCUS -> context.getString(
                R.string.timer_pomodoro_phase,
                context.getString(R.string.pomodoro_focus_title)
            ).uppercase()
            else -> context.getString(
                R.string.timer_pomodoro_phase,
                context.getString(R.string.pomodoro_break_title)
            ).uppercase()
        }

        startPauseBtn.text = when {
            state.isRunning -> context.getString(R.string.timer_pause)
            state.elapsedMillis > 0 -> context.getString(R.string.timer_resume)
            else -> context.getString(R.string.timer_start)
        }
        finishBtn.text = context.getString(R.string.timer_finish)
        resetBtn.text = context.getString(R.string.timer_reset)

        startPauseBtn.isEnabled = !conflict
        finishBtn.visibility = if (state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK) View.GONE else View.VISIBLE
        finishBtn.isEnabled = !conflict && state.elapsedMillis > 0
        resetBtn.isEnabled = !conflict && state.habitId == currentHabit.id && state.hasActiveSession
        styleTabs(state, conflict)
        styleButtons()
    }

    private fun styleTabs(state: TimerSessionSnapshot, conflict: Boolean) {
        val inactiveColor = StyledResources(context).getColor(R.attr.contrast60)
        val canSwitch = !conflict && !state.isRunning && state.elapsedMillis == 0L && state.phase == PomodoroPhase.FOCUS
        stopwatchTab.isEnabled = canSwitch
        pomodoroTab.isEnabled = canSwitch
        stopwatchTab.setTextColor(if (state.mode == TimerMode.STOPWATCH) activeColor else inactiveColor)
        pomodoroTab.setTextColor(if (state.mode == TimerMode.POMODORO) activeColor else inactiveColor)
        stopwatchTab.alpha = if (state.mode == TimerMode.STOPWATCH) 1f else if (canSwitch) 0.6f else 0.3f
        pomodoroTab.alpha = if (state.mode == TimerMode.POMODORO) 1f else if (canSwitch) 0.6f else 0.3f
    }

    private fun updateTimeDisplay(millis: Long) {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        timeDisplay.text = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun styleButtons() {
        val resources = StyledResources(context)
        styleButton(startPauseBtn, if (startPauseBtn.isEnabled) activeColor else resources.getColor(R.attr.contrast40), resources)
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
        manager?.removeListener(listener)
        super.onDetachedFromWindow()
    }
}
