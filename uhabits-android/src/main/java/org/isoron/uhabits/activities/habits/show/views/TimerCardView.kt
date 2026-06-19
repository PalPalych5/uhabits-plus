package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.utils.StyledResources

class TimerCardView : LinearLayout {

    private val handler = Handler(Looper.getMainLooper())
    private var onSave: ((Long) -> Unit)? = null

    private lateinit var titleView: TextView
    private lateinit var timeDisplay: TextView
    private lateinit var startPauseBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var resetBtn: Button

    private var activeColor: Int = 0

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val now = System.currentTimeMillis()
                val additionalSeconds = (now - startTime) / 1000L
                val totalElapsed = elapsedSeconds + additionalSeconds
                updateTimeDisplay(totalElapsed)
                handler.postDelayed(this, 500)
            }
        }
    }

    constructor(context: Context) : super(context) {
        initView()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initView()
    }

    private fun initView() {
        orientation = VERTICAL
        val pad = dp(16f).toInt()
        setPadding(pad, pad, pad, pad)

        titleView = TextView(context).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12f).toInt()
        })

        timeDisplay = TextView(context).apply {
            textSize = 48f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            text = "00:00"
        }
        addView(timeDisplay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16f).toInt()
        })

        val buttonsContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }

        startPauseBtn = Button(context).apply {
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
            setOnClickListener { toggleStartPause() }
        }
        buttonsContainer.addView(startPauseBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40f).toInt()).apply {
            rightMargin = dp(8f).toInt()
        })

        saveBtn = Button(context).apply {
            text = context.getString(R.string.timer_save)
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
            setOnClickListener { triggerSave() }
        }
        buttonsContainer.addView(saveBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40f).toInt()).apply {
            rightMargin = dp(8f).toInt()
        })

        resetBtn = Button(context).apply {
            text = context.getString(R.string.timer_reset)
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
            setOnClickListener { triggerReset() }
        }
        buttonsContainer.addView(resetBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(40f).toInt()))

        addView(buttonsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setHabit(habit: Habit, onSave: (Long) -> Unit) {
        if (habitId != habit.id) {
            handler.removeCallbacks(timerRunnable)
            habitId = habit.id
            isRunning = false
            elapsedSeconds = 0L
            startTime = 0L
        }
        this.onSave = onSave
        updateUIState()

        if (isRunning) {
            handler.post(timerRunnable)
        }
    }

    fun setColor(color: Int) {
        activeColor = color
        titleView.setTextColor(color)
        timeDisplay.setTextColor(color)
        styleButtons()
    }

    private fun toggleStartPause() {
        if (isRunning) {
            val now = System.currentTimeMillis()
            elapsedSeconds += (now - startTime) / 1000L
            isRunning = false
            startTime = 0L
            handler.removeCallbacks(timerRunnable)
        } else {
            isRunning = true
            startTime = System.currentTimeMillis()
            handler.post(timerRunnable)
        }
        updateUIState()
    }

    private fun triggerReset() {
        isRunning = false
        elapsedSeconds = 0L
        startTime = 0L
        handler.removeCallbacks(timerRunnable)
        updateUIState()
    }

    private fun triggerSave() {
        var secondsToSave = elapsedSeconds
        if (isRunning) {
            val now = System.currentTimeMillis()
            secondsToSave += (now - startTime) / 1000L
        }
        if (secondsToSave > 0) {
            onSave?.invoke(secondsToSave)
        }
        triggerReset()
    }

    private fun updateUIState() {
        val currentElapsed = if (isRunning) {
            val now = System.currentTimeMillis()
            elapsedSeconds + (now - startTime) / 1000L
        } else {
            elapsedSeconds
        }
        updateTimeDisplay(currentElapsed)

        titleView.text = context.getString(R.string.timer_title).uppercase()

        startPauseBtn.text = if (isRunning) {
            context.getString(R.string.timer_pause)
        } else if (elapsedSeconds > 0) {
            context.getString(R.string.timer_resume)
        } else {
            context.getString(R.string.timer_start)
        }

        saveBtn.isEnabled = currentElapsed > 0
        resetBtn.isEnabled = isRunning || elapsedSeconds > 0
        styleButtons()
    }

    private fun updateTimeDisplay(totalSeconds: Long) {
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
        val sres = StyledResources(context)
        val primaryTextColor = sres.getColor(android.R.attr.textColorPrimary)

        styleButton(startPauseBtn, activeColor, sres)
        styleButton(saveBtn, if (saveBtn.isEnabled) activeColor else sres.getColor(R.attr.contrast40), sres)
        styleButton(resetBtn, if (resetBtn.isEnabled) activeColor else sres.getColor(R.attr.contrast40), sres)
    }

    private fun styleButton(btn: Button, color: Int, sres: StyledResources) {
        btn.setTextColor(color)
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f)
            setStroke(dp(1.5f).toInt(), color)
            setColor(sres.getColor(R.attr.contrast0))
        }
    }

    private fun dp(value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(timerRunnable)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isRunning) {
            handler.post(timerRunnable)
        }
    }

    companion object {
        var habitId: Long? = null
        var isRunning: Boolean = false
        var elapsedSeconds: Long = 0L
        var startTime: Long = 0L
    }
}
