package org.isoron.uhabits.activities.habits.show.timer

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.timer.PomodoroCompletion
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionEngine
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.isoron.uhabits.core.timer.elapsedMillisToTenthsMinutes
import kotlin.math.roundToInt

class TimerSessionManager(
    private val context: Context,
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    private val engine: TimerSessionEngine = TimerSessionEngine { System.currentTimeMillis() }
) {
    fun interface Listener {
        fun onTimerChanged()
    }

    private val listeners = mutableSetOf<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val completion = engine.tick()
            if (completion != null) handleCompletion(completion)
            notifyListeners()
            if (engine.snapshot().isRunning) handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    fun snapshot(): TimerSessionSnapshot = engine.snapshot()

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onTimerChanged()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun switchMode(habit: Habit, mode: TimerMode): Boolean {
        val changed = engine.switchMode(habit.id!!, habit.name, mode)
        notifyListeners()
        return changed
    }

    fun startOrPause(habit: Habit): Boolean {
        val id = habit.id!!
        val state = engine.snapshot()
        val changed = if (state.habitId == id && state.isRunning) {
            engine.pause(id)
        } else {
            engine.start(id, habit.name)
        }
        restartTickerIfNeeded()
        notifyListeners()
        return changed
    }

    fun finish(habit: Habit) {
        val elapsed = engine.finish(habit.id!!)
        handler.removeCallbacks(ticker)
        if (elapsed > 0) saveElapsed(habit.id!!, elapsed)
        notifyListeners()
    }

    fun reset(habit: Habit) {
        if (engine.reset(habit.id!!)) {
            handler.removeCallbacks(ticker)
            notifyListeners()
        }
    }

    private fun restartTickerIfNeeded() {
        handler.removeCallbacks(ticker)
        if (engine.snapshot().isRunning) handler.post(ticker)
    }

    private fun handleCompletion(completion: PomodoroCompletion) {
        if (completion == PomodoroCompletion.FOCUS) {
            engine.snapshot().habitId?.let { saveElapsed(it, TimerSessionEngine.FOCUS_MILLIS) }
        }
        playAlert()
        val message = if (completion == PomodoroCompletion.FOCUS) {
            R.string.pomodoro_focus_completed
        } else {
            R.string.pomodoro_break_completed
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun saveElapsed(habitId: Long, elapsedMillis: Long) {
        val minutes = elapsedMillisToTenthsMinutes(elapsedMillis)
        if (minutes <= 0.0) return
        val habit = habitList.getById(habitId) ?: return
        val today = getToday()
        val entry = habit.computedEntries.get(today)
        val oldValue = if (entry.value == Entry.UNKNOWN || entry.value == Entry.SKIP) 0.0 else entry.value / 1000.0
        val scaledValue = ((oldValue + minutes) * 1000.0).roundToInt()
        commandRunner.run(
            CreateRepetitionCommand(habitList, habit, today, scaledValue, entry.notes)
        )
    }

    private fun playAlert() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching
            if (!vibrator.hasVibrator()) return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        }
    }

    private fun notifyListeners() = listeners.toList().forEach { it.onTimerChanged() }

    companion object {
        private const val TICK_INTERVAL_MILLIS = 500L
    }
}
