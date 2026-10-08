package org.isoron.uhabits.activities.habits.show.timer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.AddNumericalEntryOpCommand
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
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
import java.util.UUID
import org.isoron.uhabits.core.focus.runtime.FocusCapturingTimer
import org.isoron.uhabits.core.focus.runtime.FocusCaptureStore
import org.isoron.uhabits.core.focus.runtime.FocusCaptureTarget
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.containers.ContainerId

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
    engine: TimerSessionEngine = TimerSessionEngine { System.currentTimeMillis() },
    val session: DatasetSession = DatasetSessionFactory.production(),
    private val captureStore: FocusCaptureStore? = null,
    focusTargetProvider: (Long) -> FocusCaptureTarget? = { null },
    deviceId: String? = null,
    private val captureTargetResolver: (FocusPrimaryTargetType, String) -> Pair<String, FocusCaptureTarget>? = { _, _ -> null }
) {
    private var disposed = false
    val currentTarget: FocusCaptureTarget? get() = if (disposed) null else runtime.currentTarget

    fun isCurrentTask(id: TaskId): Boolean = !disposed && runtime.currentTarget?.let {
        it.kind == FocusPrimaryTargetType.TASK && it.id == id.value
    } == true

    fun isTaskTarget(id: TaskId): Boolean = isCurrentTask(id)

    fun taskDurations(id: TaskId): PomodoroDurations = PomodoroDurations(
        statePreferences.getInt("pomodoro_task_${id.value}_focus", appPreferences.pomodoroDefaultFocusMinutes),
        statePreferences.getInt("pomodoro_task_${id.value}_break", appPreferences.pomodoroDefaultBreakMinutes)
    )

    fun prepareTask(id: TaskId, title: String): Boolean {
        if (disposed || !session.isExperimental || captureStore == null) return false
        if (runtime.snapshot().hasActiveSession) return isCurrentTask(id)
        val durations = taskDurations(id)
        val changed = runtime.configurePomodoro(GENERIC_TARGET_ID, title,
            durations.focusMinutes.minutesToMillis(), durations.breakMinutes.minutesToMillis())
        if (changed) { clearCompletionDisplay(); refreshRuntimePresentation() }
        return changed
    }

    fun updateTaskDurations(id: TaskId, title: String, focusMinutes: Int, breakMinutes: Int): Boolean {
        if (disposed || !session.isExperimental || captureStore == null) return false
        if (focusMinutes !in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES || breakMinutes !in MIN_BREAK_MINUTES..MAX_BREAK_MINUTES) return false
        if (runtime.snapshot().hasActiveSession && !isCurrentTask(id)) return false
        if (!runtime.configurePomodoro(GENERIC_TARGET_ID, title, focusMinutes.minutesToMillis(), breakMinutes.minutesToMillis())) return false
        statePreferences.edit().putInt("pomodoro_task_${id.value}_focus", focusMinutes)
            .putInt("pomodoro_task_${id.value}_break", breakMinutes).apply()
        refreshRuntimePresentation()
        return true
    }

    fun switchTaskMode(id: TaskId, title: String, mode: TimerMode): Boolean {
        if (disposed || !session.isExperimental || captureStore == null) return false
        if (runtime.snapshot().hasActiveSession && !isCurrentTask(id)) return false
        if (mode == TimerMode.POMODORO && !prepareTask(id, title)) return false
        val changed = runtime.switchMode(GENERIC_TARGET_ID, title, mode)
        if (changed) { clearCompletionDisplay(); refreshRuntimePresentation() }
        return changed
    }

    fun toggleTask(id: TaskId, title: String): Boolean {
        if (disposed || !session.isExperimental || captureStore == null) return false
        val state = runtime.snapshot()
        if (state.hasActiveSession) return if (isCurrentTask(id)) pauseOrResumeCurrent() else false
        return startTask(id, state.mode)
    }

    fun interface Listener {
        fun onTimerChanged()
    }

    private val runtime = FocusCapturingTimer(engine, captureStore, { System.currentTimeMillis() },
        { UUID.randomUUID().toString() }, deviceId, focusTargetProvider)
    private val listeners = mutableSetOf<Listener>()
    private val statePreferences = if (session.isExperimental)
        context.getSharedPreferences(session.preferencesNamespace + "_timer", Context.MODE_PRIVATE)
        else PreferenceManager.getDefaultSharedPreferences(context)
    private val handler = Handler(Looper.getMainLooper())
    private var alarmRevision = statePreferences.getLong(KEY_ALARM_REVISION, 0L)
    private var lastCompletionEventId = statePreferences.getLong(KEY_COMPLETION_EVENT_ID, 0L)
    private var completionDisplayActive =
        statePreferences.getBoolean(KEY_COMPLETION_DISPLAY_ACTIVE, false) &&
            completionNotifier.isCompletionVisible()
    private val ticker = object : Runnable {
        override fun run() {
            if (disposed || !session.capabilities.timerEnabled) return
            runtime.isAutoSwitch = appPreferences.isPomodoroAutoSwitch
            if (!completeIfDue()) notifyListeners()
            if (runtime.snapshot().isRunning) handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    init {
        if (session.capabilities.timerEnabled) {
            val savedHabitId = statePreferences.getLong("pref_timer_habit_id", -1L)
            if (savedHabitId != -1L && captureStore == null) {
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

                runtime.isAutoSwitch = appPreferences.isPomodoroAutoSwitch
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
                    if (runtime.snapshot().hasActiveSession) syncForegroundService()
                }
            }
        }
    }

    init {
        if (captureStore != null) runtime.isAutoSwitch = appPreferences.isPomodoroAutoSwitch
        if (captureStore != null && session.capabilities.timerEnabled && !completeIfDue()) {
            restartTickerIfNeeded()
            refreshAlarm()
            if (runtime.snapshot().hasActiveSession) syncForegroundService()
        }
    }

    fun acceptsDatasetCallback(datasetId: String?): Boolean =
        !disposed && (datasetId == session.datasetId || (!session.isExperimental && datasetId == null))

    fun snapshot(): TimerSessionSnapshot {
        if (disposed) return TimerSessionSnapshot()
        runtime.isAutoSwitch = appPreferences.isPomodoroAutoSwitch
        return runtime.snapshot()
    }

    fun prepare(habit: Habit) {
        if (disposed || !session.capabilities.timerEnabled) return
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
        if (disposed || !session.capabilities.timerEnabled) return false
        if (focusMinutes !in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES) return false
        if (breakMinutes !in MIN_BREAK_MINUTES..MAX_BREAK_MINUTES) return false
        val changed = runtime.configurePomodoro(
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
        if (disposed) return
        listeners.add(listener)
        listener.onTimerChanged()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun switchMode(habit: Habit, mode: TimerMode): Boolean {
        if (disposed || !session.capabilities.timerEnabled) return false
        if (mode == TimerMode.POMODORO) applyStoredDurations(habit)
        val changed = runtime.switchMode(habit.id!!, habit.name, mode)
        if (changed) clearCompletionDisplay()
        persistState()
        refreshAlarm()
        notifyListeners()
        return changed
    }

    fun startOrPause(habit: Habit): Boolean {
        if (disposed || !session.capabilities.timerEnabled) return false
        val id = habit.id!!
        val state = runtime.snapshot()
        val changed = if (state.habitId == id && state.isRunning) {
            runtime.pause(id)
        } else {
            runtime.start(id, habit.name)
        }
        if (changed) clearCompletionDisplay()
        restartTickerIfNeeded()
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
        return changed
    }

    /** Reuses the existing engine/service; Task and Container launch UI can use this entry point. */
    fun startTask(id: TaskId, mode: TimerMode = TimerMode.STOPWATCH): Boolean =
        if (disposed) false else captureTargetResolver(FocusPrimaryTargetType.TASK, id.value)?.let { startTarget(it.second, it.first, mode) } ?: false

    fun startContainer(id: ContainerId, mode: TimerMode = TimerMode.STOPWATCH): Boolean =
        if (disposed) false else captureTargetResolver(FocusPrimaryTargetType.CONTAINER, id.value)?.let { startTarget(it.second, it.first, mode) } ?: false

    fun startTarget(target: FocusCaptureTarget, title: String, mode: TimerMode = TimerMode.STOPWATCH): Boolean {
        if (disposed || !session.isExperimental || captureStore == null || !session.capabilities.timerEnabled) return false
        val before = runtime.snapshot()
        if (before.hasActiveSession && runtime.currentTarget?.let { it.kind != target.kind || it.id != target.id } != false) return false
        if (!before.hasActiveSession) {
            if (target.kind == FocusPrimaryTargetType.TASK && !prepareTask(TaskId(target.id), title)) return false
            if (!runtime.switchMode(GENERIC_TARGET_ID, title, mode)) return false
        } else if (before.mode != mode) return false
        if (!runtime.start(GENERIC_TARGET_ID, title, target)) return false
        clearCompletionDisplay()
        refreshRuntimePresentation()
        return true
    }

    fun pauseOrResumeCurrent(): Boolean {
        if (disposed) return false
        val state = runtime.snapshot()
        val id = state.habitId ?: return false
        if (id != GENERIC_TARGET_ID) habitList.getById(id)?.let { return startOrPause(it) }
        if (!session.isExperimental || captureStore == null) return false
        val changed = if (state.isRunning) runtime.pause(id) else {
            val target = runtime.currentTarget ?: return false
            runtime.start(id, state.habitName, target)
        }
        if (changed) { clearCompletionDisplay(); refreshRuntimePresentation() }
        return changed
    }

    fun finishCurrent() {
        if (disposed) return
        val state = runtime.snapshot()
        val id = state.habitId ?: return
        if (id != GENERIC_TARGET_ID) habitList.getById(id)?.let { finish(it); return }
        if (!session.isExperimental || captureStore == null) return
        runtime.finish(id)
        handler.removeCallbacks(ticker)
        clearCompletionDisplay()
        refreshRuntimePresentation()
    }

    fun resetCurrent() {
        if (disposed) return
        val id = runtime.snapshot().habitId ?: return
        if (id != GENERIC_TARGET_ID) habitList.getById(id)?.let { reset(it); return }
        if (!session.isExperimental || captureStore == null) return
        runtime.reset(id)
        handler.removeCallbacks(ticker)
        clearCompletionDisplay()
        refreshRuntimePresentation()
    }

    fun takeBreakCurrent() {
        if (disposed) return
        val state = runtime.snapshot()
        val id = state.habitId ?: return
        if (id != GENERIC_TARGET_ID) habitList.getById(id)?.let { takeBreakManually(it); return }
        if (!session.isExperimental || captureStore == null || state.mode != TimerMode.POMODORO || state.phase != PomodoroPhase.FOCUS) return
        runtime.transitionToBreakManually()
        clearCompletionDisplay()
        refreshRuntimePresentation()
    }

    private fun refreshRuntimePresentation() {
        restartTickerIfNeeded()
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
    }

    fun takeBreakManually(habit: Habit) {
        if (disposed || !session.capabilities.timerEnabled) return
        val id = habit.id!!
        val state = runtime.snapshot()
        if (state.habitId != id || state.mode != TimerMode.POMODORO || state.phase != PomodoroPhase.FOCUS) return

        val unrecorded = if (state.completionTriggered) {
            (state.elapsedMillis - state.focusDurationMillis).coerceAtLeast(0L)
        } else {
            state.elapsedMillis
        }
        saveElapsed(id, unrecorded)
        runtime.transitionToBreakManually()

        restartTickerIfNeeded()
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
    }

    fun finish(habit: Habit) {
        if (disposed || !session.capabilities.timerEnabled) return
        val state = runtime.snapshot()
        if (state.habitId != habit.id) return
        val elapsed = runtime.finish(habit.id!!)
        handler.removeCallbacks(ticker)
        clearCompletionDisplay()
        val unrecorded = if (state.completionTriggered && state.phase == PomodoroPhase.FOCUS) {
            (elapsed - state.focusDurationMillis).coerceAtLeast(0L)
        } else {
            elapsed
        }
        if (unrecorded > 0) saveElapsed(habit.id!!, unrecorded)
        persistState()
        refreshAlarm()
        syncForegroundService()
        notifyListeners()
    }

    fun reset(habit: Habit) {
        if (disposed || !session.capabilities.timerEnabled) return
        if (runtime.reset(habit.id!!)) {
            handler.removeCallbacks(ticker)
            clearCompletionDisplay()
            persistState()
            refreshAlarm()
            syncForegroundService()
            notifyListeners()
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        handler.removeCallbacks(ticker)
        listeners.clear()
        alarmScheduler.cancel()
        clearCompletionDisplay()
        TimerForegroundService.stop(context)
    }

    private fun restartTickerIfNeeded() {
        handler.removeCallbacks(ticker)
        if (session.capabilities.timerEnabled && runtime.snapshot().isRunning) handler.post(ticker)
    }

    fun onAlarm(revision: Long) {
        if (disposed || !session.capabilities.timerEnabled) return
        if (revision != alarmRevision) return
        if (!completeIfDue(revision)) refreshAlarm()
    }

    fun rescheduleAlarmFromSystem() {
        if (disposed || !session.capabilities.timerEnabled) return
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
        if (disposed) return false
        if (eventId != lastCompletionEventId) return false
        clearCompletionDisplay()
        persistState()
        syncForegroundService()
        notifyListeners()
        return true
    }

    @Synchronized
    fun startNextPhaseFromNotification(
        habitId: Long,
        eventId: Long,
        completion: PomodoroCompletion
    ): Boolean {
        if (disposed || !session.capabilities.timerEnabled) return false
        if (eventId != lastCompletionEventId) return false
        val habit = habitList.getById(habitId)
        if (habit == null && habitId != GENERIC_TARGET_ID && (!session.isExperimental || captureStore == null)) return false
        var state = runtime.snapshot()
        if (state.hasActiveSession && state.habitId != habitId) return false

        if (state.habitId == null) {
            if (completion != PomodoroCompletion.BREAK) return false
            if (habit == null) return false
            applyStoredDurations(habit)
            runtime.switchMode(habitId, habit.name, TimerMode.POMODORO)
            state = runtime.snapshot()
        }
        if (state.habitId != habitId || state.mode != TimerMode.POMODORO) return false

        val completedPhase = if (completion == PomodoroCompletion.FOCUS) {
            PomodoroPhase.FOCUS
        } else {
            PomodoroPhase.BREAK
        }
        if (state.phase == completedPhase && state.completionTriggered) {
            runtime.transitionToNextPhaseManually()
            state = runtime.snapshot()
        }
        val expectedPhase = if (completedPhase == PomodoroPhase.FOCUS) {
            PomodoroPhase.BREAK
        } else {
            PomodoroPhase.FOCUS
        }
        if (state.phase != expectedPhase || state.isRunning) return false
        val started = if (habit != null) runtime.start(habitId, habit.name)
            else runtime.currentTarget?.let { runtime.start(habitId, state.habitName, it) } ?: false
        if (!started) return false

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
        if (disposed || !session.capabilities.timerEnabled) return false
        if (expectedRevision != null && expectedRevision != alarmRevision) return false
        val before = runtime.snapshot()
        val completion = runtime.tick() ?: return false

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
            val overtimeStartedAtMillis = if (runtime.snapshot().completionTriggered) {
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
        // Preserve legacy accrual. Experimental work facts await explicit Contribution rules.
        if (session.isExperimental) return
        val minutes = elapsedMillisToTenthsMinutes(elapsedMillis)
        if (minutes <= 0.0) return
        val habit = habitList.getById(habitId) ?: return
        val today = getToday()
        val entry = habit.originalEntries.get(today)
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
        if (disposed || !session.capabilities.timerEnabled) false else runtime.snapshot().shouldShowProgressNotification(completionDisplayActive)

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
        if (disposed || !session.capabilities.timerEnabled) return
        alarmRevision++
        statePreferences.edit().putLong(KEY_ALARM_REVISION, alarmRevision).commit()
        val state = runtime.snapshot()
        if (state.isRunning && state.mode == TimerMode.POMODORO && !state.completionTriggered) {
            alarmScheduler.schedule(System.currentTimeMillis() + state.displayMillis, alarmRevision)
        }
    }

    private fun persistState(commit: Boolean = false) {
        if (disposed || !session.capabilities.timerEnabled) return
        val state = runtime.snapshot()
        val editor = statePreferences.edit()
            .putLong(KEY_ALARM_REVISION, alarmRevision)
            .putLong(KEY_COMPLETION_EVENT_ID, lastCompletionEventId)
            .putBoolean(KEY_COMPLETION_DISPLAY_ACTIVE, completionDisplayActive)
        if (state.habitId != null) {
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
        return runtime.configurePomodoro(
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
        private const val GENERIC_TARGET_ID = -2L
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
