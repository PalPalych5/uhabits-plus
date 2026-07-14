package org.isoron.uhabits.core.timer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerNotificationStateTest {
    @Test
    fun completionCardReplacesActiveProgressCard() {
        val overtime = TimerSessionSnapshot(
            habitId = 1,
            mode = TimerMode.POMODORO,
            isRunning = true,
            elapsedMillis = 61_000,
            completionTriggered = true
        )

        assertFalse(overtime.shouldShowProgressNotification(completionDisplayActive = true))
        assertTrue(overtime.shouldShowProgressNotification(completionDisplayActive = false))
    }

    @Test
    fun inactiveSessionHasNoNotification() {
        assertNull(TimerSessionSnapshot().toNotificationState())
    }

    @Test
    fun runningStopwatchCountsUpAndCanPauseOrFinish() {
        val state = TimerSessionSnapshot(
            habitId = 1,
            habitName = "Reading",
            mode = TimerMode.STOPWATCH,
            isRunning = true,
            elapsedMillis = 12_000,
            displayMillis = 12_000
        ).toNotificationState()!!

        assertEquals(TimerChronometerMode.COUNT_UP, state.chronometerMode)
        assertEquals(
            listOf(TimerNotificationAction.PAUSE, TimerNotificationAction.FINISH),
            state.actions
        )
    }

    @Test
    fun pausedPomodoroCanResumeOrReset() {
        val state = TimerSessionSnapshot(
            habitId = 1,
            habitName = "Reading",
            mode = TimerMode.POMODORO,
            elapsedMillis = 30_000,
            displayMillis = 1_470_000
        ).toNotificationState()!!

        assertEquals(TimerChronometerMode.STATIC, state.chronometerMode)
        assertEquals(
            listOf(TimerNotificationAction.RESUME, TimerNotificationAction.RESET),
            state.actions
        )
    }

    @Test
    fun completedFocusOffersManualBreak() {
        val state = TimerSessionSnapshot(
            habitId = 1,
            habitName = "Reading",
            mode = TimerMode.POMODORO,
            phase = PomodoroPhase.BREAK,
            displayMillis = 300_000
        ).toNotificationState()!!

        assertEquals(TimerChronometerMode.STATIC, state.chronometerMode)
        assertEquals(
            listOf(TimerNotificationAction.START_BREAK, TimerNotificationAction.RESET),
            state.actions
        )
    }

    @Test
    fun runningBreakCountsDown() {
        val state = TimerSessionSnapshot(
            habitId = 1,
            habitName = "Reading",
            mode = TimerMode.POMODORO,
            phase = PomodoroPhase.BREAK,
            isRunning = true,
            elapsedMillis = 10_000,
            displayMillis = 290_000
        ).toNotificationState()!!

        assertEquals(TimerChronometerMode.COUNT_DOWN, state.chronometerMode)
        assertEquals(
            listOf(TimerNotificationAction.PAUSE, TimerNotificationAction.RESET),
            state.actions
        )
    }
}
