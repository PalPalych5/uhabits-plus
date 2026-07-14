package org.isoron.uhabits.activities.habits.show.timer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.AddNumericalEntryOpCommand
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.timer.PomodoroCompletion
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionEngine
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.isoron.uhabits.core.timer.elapsedMillisToTenthsMinutes
import org.isoron.uhabits.core.timer.shouldShowProgressNotification
import kotlin.math.roundToInt

data class PomodoroDurations(
    val focusMinutes: Int = 25,
    val breakMinutes: Int = 5
)

class TimerSessionManager(
    private val context: Context,
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    private val appPreferences: Preferences,
    private val alarmScheduler: PomodoroAlarmScheduler,
    private val completionNotifier: PomodoroCompletionNotifier,
    private val engine: TimerSessionEngine = TimerSessionEngine { System.currentTimeMillis() }
) {
    fun interface Listener {
        fun onTimerChanged()
    }

    private val listeners = mutableSetOf<Listener>()
    private val statePreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val handler = Handler(Looper.getMainLooper())
    private var alarmRevision = statePreferences.getLong(KEY_ALARM_REVISION, 0L)
    private var lastCompletionEventId = statePreferences.getLong(KEY_COMPLETION_EVENT_ID, 0L)
    private var completionDisplayActive =
        statePreferences.getBoolean(KEY_COMPLETION_DISPLAY_ACTIVE, false) &&
            completionNotifier.isCompletionVisible()
    private val ticker = object : Runnable {
        override fun run() {
            engine.isAutoSwitch = appPreferences.isPomodoroAutoSwitch
            if (!completeIfDue()) notifyListeners()
            if (engine.snapshot().isRunning) handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    init {
        val savedHabitId = statePreferences.getLong("pref_timer_habit_id", -1L)
        if (savedHabitId != -1L) {
            val habitId = savedHabitId
            val habitName = statePreferences.getString("pref_timer_habit_name", "") ?: ""
            val modeStr = statePreferences.getString("pref_timer_mode", "STOPWATCH") ?: "STOPWATCH"
            val mode = runCatching { TimerMode.valueOf(modeStr) }.getOrDefault(TimerMode.STOPWATCH)
            val phaseStr = statePreferences.getString("pref_timer_phase", "FOCUS") ?: "FOCUS"
            val phase = runCatching { PomodoroPhase.valueOf(phaseStr) }.getOrDefault(PomodoroPhase.FOCUS)
            val isRunning = statePreferences.getBoolean("pref_timer_is_running", false)
            val accumulatedMillis = statePreferences.getLong("pref_timer_accumulated_elapsed_millis", 0L)
            val startedAtWallClock = statePreferences.getLong("pref_timer_started_at_wall_clock_millis", 0L)
            val focusDuration = statePreferences.getLong("pref_timer_focus_duration_millis", 25 * 60 * 1000L)
            val breakDuration = statePreferences.getLong("pref_timer_break_duration_millis", 5 * 60 * 1000L)
            val completionTriggered = if (statePreferences.contains(KEY_COMPLETION_TRIGGERED)) {
                statePreferences.getBoolean(KEY_COMPLETION_TRIGGERED, false)
            } else {
                null
            }

            val startedAt = if (isRunning) startedAtWallClock else 0L

            engine.isAutoSwitch = appPreferences.isPomodoroAutoSwitch
            engine.restore(
                habitId = habitId,
                habitName = habitName,
                mode = mode,
                phase = phase,
                isRunning = isRunning,
                accumulatedMillis = accumulatedMillis,
                startedAtMillis = startedAt,
                focusDurationMillis = focusDuration,
                breakDurationMillis = breakDuration,
                completionTriggered = completionTriggered
            )

            if (!completeIfDue()) {
                restartTickerIfNeeded()
                refreshAlarm()
                if (engine.snapshot().hasActiveSession) syncForegroundService()
            }
        }
    }

    fun snapshot(): TimerSessionSnapshot {
        engine.isAutoSwitch = appPreferences.isPomodoroAutoSwitch
        return engine.snapshot()
    }

    fun prepare(habit: Habit) {
        applyStoredDurations(habit)
    }

    fun durations(habit: Habit): PomodoroDurations {
        val key = habitPreferenceKey(habit)
        val defaultFocus = appPreferences.pomodoroDefaultFocusMinutes
        val defaultBreak = appPreferences.pomodoroDefaultBreakMinutes
        return PomodoroDurations(
            focusMinutes = statePreferences.getInt("${key}_focus", defaultFocus),
            breakMinutes = statePreferences.getInt("${key}_break", defaultBreak)
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
        statePreferences.edit()
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
        if (changed) clearCompletionDisplay()
        persistState()
        refreshAlarm()
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
        if (changed) clearCompletionDisplay()
        restartTickerIfNeeded()
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
        return changed
    }

    fun takeBreakManually(habit: Habit) {
        val id = habit.id!!
        val state = engine.snapshot()
        if (state.habitId != id || state.mode != TimerMode.POMODORO || state.phase != PomodoroPhase.FOCUS) return

        if (!state.completionTriggered) saveElapsed(id, state.elapsedMillis)
        engine.transitionToBreakManually()

        restartTickerIfNeeded()
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
    }

    fun finish(habit: Habit) {
        val state = engine.snapshot()
        val elapsed = engine.finish(habit.id!!)
        handler.removeCallbacks(ticker)
        clearCompletionDisplay()
        if (elapsed > 0 && !state.completionTriggered) saveElapsed(habit.id!!, elapsed)
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
    }

    fun reset(habit: Habit) {
        if (engine.reset(habit.id!!)) {
            handler.removeCallbacks(ticker)
            clearCompletionDisplay()
            persistState()
            refreshAlarm()
            syncForegroundService()
            notifyListeners()
        }
    }

    private fun restartTickerIfNeeded() {
        handler.removeCallbacks(ticker)
        if (engine.snapshot().isRunning) handler.post(ticker)
    }

    fun onAlarm(revision: Long) {
        if (revision != alarmRevision) return
        if (!completeIfDue(revision)) refreshAlarm()
    }

    fun rescheduleAlarmFromSystem() {
        if (!completeIfDue()) {
            restartTickerIfNeeded()
            persistState()
            refreshAlarm()
            syncForegroundService()
            notifyListeners()
        }
    }

    @Synchronized
    fun dismissCompletionNotification(eventId: Long): Boolean {
        if (eventId != lastCompletionEventId) return false
        completionNotifier.cancelCompletion()
        return true
    }

    @Synchronized
    fun startNextPhaseFromNotification(
        habitId: Long,
        eventId: Long,
        completion: PomodoroCompletion
    ): Boolean {
        if (eventId != lastCompletionEventId) return false
        val habit = habitList.getById(habitId) ?: return false
        var state = engine.snapshot()
        if (state.hasActiveSession && state.habitId != habitId) return false

        if (state.habitId == null) {
            if (completion != PomodoroCompletion.BREAK) return false
            applyStoredDurations(habit)
            engine.switchMode(habitId, habit.name, TimerMode.POMODORO)
            state = engine.snapshot()
        }
        if (state.habitId != habitId || state.mode != TimerMode.POMODORO) return false

        val completedPhase = if (completion == PomodoroCompletion.FOCUS) {
            PomodoroPhase.FOCUS
        } else {
            PomodoroPhase.BREAK
        }
        if (state.phase == completedPhase && state.completionTriggered) {
            engine.transitionToNextPhaseManually()
            state = engine.snapshot()
        }
        val expectedPhase = if (completedPhase == PomodoroPhase.FOCUS) {
            PomodoroPhase.BREAK
        } else {
            PomodoroPhase.FOCUS
        }
        if (state.phase != expectedPhase || state.isRunning) return false
        if (!engine.start(habitId, habit.name)) return false

        clearCompletionDisplay()
        restartTickerIfNeeded()
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
        return true
    }

    @Synchronized
    private fun completeIfDue(expectedRevision: Long? = null): Boolean {
        if (expectedRevision != null && expectedRevision != alarmRevision) return false
        val before = engine.snapshot()
        val completion = engine.tick() ?: return false

        alarmScheduler.cancel()
        alarmRevision++
        lastCompletionEventId++
        val alertsEnabled = when (completion) {
            PomodoroCompletion.FOCUS -> appPreferences.isPomodoroFocusAlertEnabled
            PomodoroCompletion.BREAK -> appPreferences.isPomodoroBreakAlertEnabled
        }
        val completedHabitId = before.habitId
        val completedDurationMillis = if (completion == PomodoroCompletion.FOCUS) {
            before.focusDurationMillis
        } else {
            before.breakDurationMillis
        }
        completionDisplayActive = alertsEnabled && completedHabitId != null &&
            completionNotifier.canPostCompletion()
        persistState(commit = true)

        if (completion == PomodoroCompletion.FOCUS) {
            completedHabitId?.let { saveElapsed(it, before.focusDurationMillis) }
        }
        syncForegroundService()
        if (completionDisplayActive && completedHabitId != null) {
            val overtimeStartedAtMillis = if (engine.snapshot().completionTriggered) {
                System.currentTimeMillis() -
                    (before.elapsedMillis - completedDurationMillis).coerceAtLeast(0L)
            } else {
                null
            }
            completionNotifier.showCompletion(
                PomodoroCompletionEvent(
                    eventId = lastCompletionEventId,
                    habitId = completedHabitId,
                    habitName = before.habitName,
                    completion = completion,
                    durationMillis = completedDurationMillis,
                    overtimeStartedAtMillis = overtimeStartedAtMillis
                )
            )
        }
        notifyListeners()
        return true
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

    private fun notifyListeners() = listeners.toList().forEach { it.onTimerChanged() }

    fun shouldShowProgressNotification(): Boolean =
        engine.snapshot().shouldShowProgressNotification(completionDisplayActive)

    private fun syncForegroundService() {
        if (shouldShowProgressNotification()) {
            TimerForegroundService.sync(context)
        } else {
            TimerForegroundService.stop(context)
        }
    }

    private fun clearCompletionDisplay() {
        if (!completionDisplayActive) return
        completionDisplayActive = false
        completionNotifier.cancelCompletion()
    }

    private fun refreshAlarm() {
        alarmScheduler.cancel()
        alarmRevision++
        statePreferences.edit().putLong(KEY_ALARM_REVISION, alarmRevision).commit()
        val state = engine.snapshot()
        if (state.isRunning && state.mode == TimerMode.POMODORO && !state.completionTriggered) {
            alarmScheduler.schedule(System.currentTimeMillis() + state.displayMillis, alarmRevision)
        }
    }

    private fun persistState(commit: Boolean = false) {
        val state = engine.snapshot()
        val editor = statePreferences.edit()
            .putLong(KEY_ALARM_REVISION, alarmRevision)
            .putLong(KEY_COMPLETION_EVENT_ID, lastCompletionEventId)
            .putBoolean(KEY_COMPLETION_DISPLAY_ACTIVE, completionDisplayActive)
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
            editor.putBoolean(KEY_COMPLETION_TRIGGERED, state.completionTriggered)
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
            editor.remove(KEY_COMPLETION_TRIGGERED)
        }
        if (commit) editor.commit() else editor.apply()
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
        private const val KEY_COMPLETION_TRIGGERED = "pref_timer_completion_triggered"
        private const val KEY_ALARM_REVISION = "pref_timer_alarm_revision"
        private const val KEY_COMPLETION_EVENT_ID = "pref_timer_completion_event_id"
        private const val KEY_COMPLETION_DISPLAY_ACTIVE = "pref_timer_completion_display_active"
        const val DEFAULT_FOCUS_MINUTES = 25
        const val DEFAULT_BREAK_MINUTES = 5
        const val MIN_FOCUS_MINUTES = 1
        const val MAX_FOCUS_MINUTES = 180
        const val MIN_BREAK_MINUTES = 1
        const val MAX_BREAK_MINUTES = 60
    }
}
