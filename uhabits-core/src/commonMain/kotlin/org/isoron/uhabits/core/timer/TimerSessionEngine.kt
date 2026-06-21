package org.isoron.uhabits.core.timer

import kotlin.math.floor

enum class TimerMode { STOPWATCH, POMODORO }
enum class PomodoroPhase { FOCUS, BREAK }
enum class PomodoroCompletion { FOCUS, BREAK }

data class TimerSessionSnapshot(
    val habitId: Long? = null,
    val habitName: String = "",
    val mode: TimerMode = TimerMode.STOPWATCH,
    val phase: PomodoroPhase = PomodoroPhase.FOCUS,
    val isRunning: Boolean = false,
    val elapsedMillis: Long = 0,
    val displayMillis: Long = 0,
    val focusDurationMillis: Long = 25 * 60 * 1000L,
    val breakDurationMillis: Long = 5 * 60 * 1000L,
    val startedAtMillis: Long = 0,
    val accumulatedMillis: Long = 0
) {
    val hasActiveSession: Boolean
        get() = isRunning || elapsedMillis > 0 || phase == PomodoroPhase.BREAK
}

class TimerSessionEngine(private val clock: () -> Long) {
    private var habitId: Long? = null
    private var habitName: String = ""
    private var mode = TimerMode.STOPWATCH
    private var phase = PomodoroPhase.FOCUS
    private var isRunning = false
    private var accumulatedMillis = 0L
    private var startedAtMillis = 0L
    private var focusDurationMillis = FOCUS_MILLIS
    private var breakDurationMillis = BREAK_MILLIS

    fun snapshot(): TimerSessionSnapshot {
        val elapsed = currentElapsedMillis()
        val display = when (mode) {
            TimerMode.STOPWATCH -> elapsed
            TimerMode.POMODORO -> (phaseDurationMillis() - elapsed).coerceAtLeast(0)
        }
        return TimerSessionSnapshot(
            habitId = habitId,
            habitName = habitName,
            mode = mode,
            phase = phase,
            isRunning = isRunning,
            elapsedMillis = elapsed,
            displayMillis = display,
            focusDurationMillis = focusDurationMillis,
            breakDurationMillis = breakDurationMillis,
            startedAtMillis = startedAtMillis,
            accumulatedMillis = accumulatedMillis
        )
    }

    fun restore(
        habitId: Long?,
        habitName: String,
        mode: TimerMode,
        phase: PomodoroPhase,
        isRunning: Boolean,
        accumulatedMillis: Long,
        startedAtMillis: Long,
        focusDurationMillis: Long,
        breakDurationMillis: Long
    ) {
        this.habitId = habitId
        this.habitName = habitName
        this.mode = mode
        this.phase = phase
        this.isRunning = isRunning
        this.accumulatedMillis = accumulatedMillis
        this.startedAtMillis = startedAtMillis
        this.focusDurationMillis = focusDurationMillis
        this.breakDurationMillis = breakDurationMillis
    }

    fun configurePomodoro(
        habitId: Long,
        habitName: String,
        focusDurationMillis: Long,
        breakDurationMillis: Long
    ): Boolean {
        if (focusDurationMillis <= 0 || breakDurationMillis <= 0) return false
        if (hasConflict(habitId)) return false
        if (isRunning || accumulatedMillis > 0 || phase != PomodoroPhase.FOCUS) return false
        attach(habitId, habitName)
        this.focusDurationMillis = focusDurationMillis
        this.breakDurationMillis = breakDurationMillis
        return true
    }

    fun switchMode(habitId: Long, habitName: String, newMode: TimerMode): Boolean {
        if (hasConflict(habitId)) return false
        if (isRunning || accumulatedMillis > 0 || phase != PomodoroPhase.FOCUS) return false
        attach(habitId, habitName)
        mode = newMode
        return true
    }

    fun start(habitId: Long, habitName: String): Boolean {
        if (hasConflict(habitId)) return false
        attach(habitId, habitName)
        if (!isRunning) {
            isRunning = true
            startedAtMillis = clock()
        }
        return true
    }

    fun pause(habitId: Long): Boolean {
        if (this.habitId != habitId || !isRunning) return false
        accumulatedMillis = currentElapsedMillis()
        isRunning = false
        startedAtMillis = 0
        return true
    }

    fun tick(): PomodoroCompletion? {
        if (!isRunning || mode != TimerMode.POMODORO) return null
        if (currentElapsedMillis() < phaseDurationMillis()) return null
        val completed = if (phase == PomodoroPhase.FOCUS) PomodoroCompletion.FOCUS else PomodoroCompletion.BREAK
        isRunning = false
        startedAtMillis = 0
        accumulatedMillis = 0
        phase = if (phase == PomodoroPhase.FOCUS) PomodoroPhase.BREAK else PomodoroPhase.FOCUS
        return completed
    }

    fun finish(habitId: Long): Long {
        if (this.habitId != habitId) return 0
        val elapsed = currentElapsedMillis()
        val result = when {
            mode == TimerMode.STOPWATCH -> elapsed
            phase == PomodoroPhase.FOCUS -> elapsed.coerceAtMost(focusDurationMillis)
            else -> 0
        }
        reset()
        return result
    }

    fun reset(habitId: Long): Boolean {
        if (this.habitId != habitId) return false
        reset()
        return true
    }

    private fun reset() {
        habitId = null
        habitName = ""
        mode = TimerMode.STOPWATCH
        phase = PomodoroPhase.FOCUS
        isRunning = false
        accumulatedMillis = 0
        startedAtMillis = 0
        focusDurationMillis = FOCUS_MILLIS
        breakDurationMillis = BREAK_MILLIS
    }

    private fun attach(habitId: Long, habitName: String) {
        if (this.habitId != habitId && !snapshot().hasActiveSession) {
            this.habitId = habitId
            this.habitName = habitName
            mode = TimerMode.STOPWATCH
            phase = PomodoroPhase.FOCUS
            accumulatedMillis = 0
        } else if (this.habitId == null) {
            this.habitId = habitId
            this.habitName = habitName
        }
    }

    private fun hasConflict(habitId: Long): Boolean {
        val snapshot = snapshot()
        return snapshot.hasActiveSession && snapshot.habitId != habitId
    }

    private fun currentElapsedMillis(): Long {
        if (!isRunning) return accumulatedMillis
        return accumulatedMillis + (clock() - startedAtMillis).coerceAtLeast(0)
    }

    private fun phaseDurationMillis(): Long =
        if (phase == PomodoroPhase.FOCUS) focusDurationMillis else breakDurationMillis

    companion object {
        const val FOCUS_MILLIS = 25 * 60 * 1000L
        const val BREAK_MILLIS = 5 * 60 * 1000L
    }
}

fun elapsedMillisToTenthsMinutes(elapsedMillis: Long): Double =
    floor(elapsedMillis / 6_000.0 + 0.5) / 10.0
