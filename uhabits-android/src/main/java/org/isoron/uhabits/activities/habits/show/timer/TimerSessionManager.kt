package org.isoron.uhabits.activities.habits.show.timer

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.core.commands.AddNumericalEntryOpCommand
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.timer.PomodoroCompletion
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionEngine
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.isoron.uhabits.core.timer.elapsedMillisToTenthsMinutes
import kotlin.math.roundToInt

data class PomodoroDurations(
    val focusMinutes: Int = 25,
    val breakMinutes: Int = 5
)

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
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            engine.isAutoSwitch = preferences.getBoolean("pref_pomodoro_auto_switch", true)
            val completion = engine.tick()
            if (completion != null) {
                handleCompletion(completion)
                persistState()
                syncForegroundService()
            }
            notifyListeners()
            if (engine.snapshot().isRunning) handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    init {
        val savedHabitId = preferences.getLong("pref_timer_habit_id", -1L)
        if (savedHabitId != -1L) {
            val habitId = savedHabitId
            val habitName = preferences.getString("pref_timer_habit_name", "") ?: ""
            val modeStr = preferences.getString("pref_timer_mode", "STOPWATCH") ?: "STOPWATCH"
            val mode = runCatching { TimerMode.valueOf(modeStr) }.getOrDefault(TimerMode.STOPWATCH)
            val phaseStr = preferences.getString("pref_timer_phase", "FOCUS") ?: "FOCUS"
            val phase = runCatching { PomodoroPhase.valueOf(phaseStr) }.getOrDefault(PomodoroPhase.FOCUS)
            val isRunning = preferences.getBoolean("pref_timer_is_running", false)
            val accumulatedMillis = preferences.getLong("pref_timer_accumulated_elapsed_millis", 0L)
            val startedAtWallClock = preferences.getLong("pref_timer_started_at_wall_clock_millis", 0L)
            val focusDuration = preferences.getLong("pref_timer_focus_duration_millis", 25 * 60 * 1000L)
            val breakDuration = preferences.getLong("pref_timer_break_duration_millis", 5 * 60 * 1000L)

            val startedAt = if (isRunning) startedAtWallClock else 0L

            engine.isAutoSwitch = preferences.getBoolean("pref_pomodoro_auto_switch", true)
            engine.restore(
                habitId = habitId,
                habitName = habitName,
                mode = mode,
                phase = phase,
                isRunning = isRunning,
                accumulatedMillis = accumulatedMillis,
                startedAtMillis = startedAt,
                focusDurationMillis = focusDuration,
                breakDurationMillis = breakDuration
            )

            restartTickerIfNeeded()
            if (engine.snapshot().hasActiveSession) {
                syncForegroundService()
            }
        }
    }

    fun snapshot(): TimerSessionSnapshot {
        engine.isAutoSwitch = preferences.getBoolean("pref_pomodoro_auto_switch", true)
        return engine.snapshot()
    }

    fun prepare(habit: Habit) {
        applyStoredDurations(habit)
    }

    fun durations(habit: Habit): PomodoroDurations {
        val key = habitPreferenceKey(habit)
        val defaultFocus = preferences.getInt("pref_pomodoro_default_focus_minutes", DEFAULT_FOCUS_MINUTES)
        val defaultBreak = preferences.getInt("pref_pomodoro_default_break_minutes", DEFAULT_BREAK_MINUTES)
        return PomodoroDurations(
            focusMinutes = preferences.getInt("${key}_focus", defaultFocus),
            breakMinutes = preferences.getInt("${key}_break", defaultBreak)
        )
    }

    fun updateDurations(habit: Habit, focusMinutes: Int, breakMinutes: Int): Boolean {
        if (focusMinutes !in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES) return false
        if (breakMinutes !in MIN_BREAK_MINUTES..MAX_BREAK_MINUTES) return false
        val changed = engine.configurePomodoro(
            habit.id!!,
            habit.name,
            focusMinutes.minutesToMillis(),
            breakMinutes.minutesToMillis()
        )
        if (!changed) return false
        val key = habitPreferenceKey(habit)
        preferences.edit()
            .putInt("${key}_focus", focusMinutes)
            .putInt("${key}_break", breakMinutes)
            .apply()
        persistState()
        notifyListeners()
        return true
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onTimerChanged()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun switchMode(habit: Habit, mode: TimerMode): Boolean {
        if (mode == TimerMode.POMODORO) applyStoredDurations(habit)
        val changed = engine.switchMode(habit.id!!, habit.name, mode)
        persistState()
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
        persistState()
        syncForegroundService()
        notifyListeners()
        return changed
    }

    fun takeBreakManually(habit: Habit) {
        val id = habit.id!!
        val state = engine.snapshot()
        if (state.habitId != id || state.mode != TimerMode.POMODORO || state.phase != PomodoroPhase.FOCUS) return

        saveElapsed(id, state.elapsedMillis)
        engine.transitionToBreakManually()

        restartTickerIfNeeded()
        persistState()
        syncForegroundService()
        notifyListeners()
    }

    fun finish(habit: Habit) {
        val elapsed = engine.finish(habit.id!!)
        handler.removeCallbacks(ticker)
        if (elapsed > 0) saveElapsed(habit.id!!, elapsed)
        persistState()
        syncForegroundService()
        notifyListeners()
    }

    fun reset(habit: Habit) {
        if (engine.reset(habit.id!!)) {
            handler.removeCallbacks(ticker)
            persistState()
            syncForegroundService()
            notifyListeners()
        }
    }

    private fun restartTickerIfNeeded() {
        handler.removeCallbacks(ticker)
        if (engine.snapshot().isRunning) handler.post(ticker)
    }

    private fun handleCompletion(completion: PomodoroCompletion) {
        if (completion == PomodoroCompletion.FOCUS) {
            val state = engine.snapshot()
            state.habitId?.let { saveElapsed(it, state.focusDurationMillis) }
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
        commandRunner.run(
            AddNumericalEntryOpCommand(
                habitList = habitList,
                habit = habit,
                date = today,
                deltaValue = (minutes * 1000.0).roundToInt(),
                notes = entry.notes
            )
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

    private fun syncForegroundService() {
        if (engine.snapshot().hasActiveSession) {
            TimerForegroundService.sync(context)
        } else {
            TimerForegroundService.stop(context)
        }
    }

    private fun persistState() {
        val state = engine.snapshot()
        val editor = preferences.edit()
        if (state.hasActiveSession) {
            editor.putLong("pref_timer_habit_id", state.habitId ?: -1L)
            editor.putString("pref_timer_habit_name", state.habitName)
            editor.putString("pref_timer_mode", state.mode.name)
            editor.putString("pref_timer_phase", state.phase.name)
            editor.putBoolean("pref_timer_is_running", state.isRunning)
            editor.putLong("pref_timer_accumulated_elapsed_millis", state.accumulatedMillis)
            editor.putLong("pref_timer_started_at_wall_clock_millis", state.startedAtMillis)
            editor.putLong("pref_timer_focus_duration_millis", state.focusDurationMillis)
            editor.putLong("pref_timer_break_duration_millis", state.breakDurationMillis)
        } else {
            editor.remove("pref_timer_habit_id")
            editor.remove("pref_timer_habit_name")
            editor.remove("pref_timer_mode")
            editor.remove("pref_timer_phase")
            editor.remove("pref_timer_is_running")
            editor.remove("pref_timer_accumulated_elapsed_millis")
            editor.remove("pref_timer_started_at_wall_clock_millis")
            editor.remove("pref_timer_focus_duration_millis")
            editor.remove("pref_timer_break_duration_millis")
        }
        editor.apply()
    }

    private fun applyStoredDurations(habit: Habit): Boolean {
        val durations = durations(habit)
        return engine.configurePomodoro(
            habit.id!!,
            habit.name,
            durations.focusMinutes.minutesToMillis(),
            durations.breakMinutes.minutesToMillis()
        )
    }

    private fun habitPreferenceKey(habit: Habit): String =
        "pomodoro_${habit.uuid ?: habit.id}"

    private fun Int.minutesToMillis(): Long = this * 60_000L

    companion object {
        private const val TICK_INTERVAL_MILLIS = 500L
        const val DEFAULT_FOCUS_MINUTES = 25
        const val DEFAULT_BREAK_MINUTES = 5
        const val MIN_FOCUS_MINUTES = 1
        const val MAX_FOCUS_MINUTES = 180
        const val MIN_BREAK_MINUTES = 1
        const val MAX_BREAK_MINUTES = 60
    }
}
