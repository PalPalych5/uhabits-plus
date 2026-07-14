package org.isoron.uhabits.core.timer

enum class TimerNotificationAction {
    PAUSE,
    RESUME,
    FINISH,
    RESET,
    START_BREAK
}

enum class TimerChronometerMode { COUNT_UP, COUNT_DOWN, STATIC }

data class TimerNotificationState(
    val habitId: Long,
    val habitName: String,
    val mode: TimerMode,
    val phase: PomodoroPhase,
    val isRunning: Boolean,
    val displayMillis: Long,
    val chronometerMode: TimerChronometerMode,
    val actions: List<TimerNotificationAction>,
    val isOvertime: Boolean = false
)

fun TimerSessionSnapshot.shouldShowProgressNotification(completionDisplayActive: Boolean): Boolean =
    hasActiveSession && !completionDisplayActive

fun TimerSessionSnapshot.toNotificationState(): TimerNotificationState? {
    val id = habitId ?: return null
    if (!hasActiveSession) return null
    val chronometerMode = when {
        !isRunning -> TimerChronometerMode.STATIC
        mode == TimerMode.STOPWATCH -> TimerChronometerMode.COUNT_UP
        isOvertime -> TimerChronometerMode.COUNT_UP
        else -> TimerChronometerMode.COUNT_DOWN
    }
    val primaryAction = when {
        isRunning -> TimerNotificationAction.PAUSE
        mode == TimerMode.POMODORO && phase == PomodoroPhase.BREAK && elapsedMillis == 0L ->
            TimerNotificationAction.START_BREAK
        else -> TimerNotificationAction.RESUME
    }
    val terminalAction = if (mode == TimerMode.STOPWATCH) {
        TimerNotificationAction.FINISH
    } else {
        TimerNotificationAction.RESET
    }

    val actionsList = if (isOvertime && phase == PomodoroPhase.FOCUS) {
        listOf(primaryAction, TimerNotificationAction.START_BREAK, terminalAction)
    } else {
        listOf(primaryAction, terminalAction)
    }

    return TimerNotificationState(
        habitId = id,
        habitName = habitName,
        mode = mode,
        phase = phase,
        isRunning = isRunning,
        displayMillis = displayMillis,
        chronometerMode = chronometerMode,
        actions = actionsList,
        isOvertime = isOvertime
    )
}
