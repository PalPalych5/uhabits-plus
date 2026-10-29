package org.isoron.uhabits.core.timer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimerSessionEngineTest {
    private var now = 1_000L
    private val engine = TimerSessionEngine { now }

    @Test
    fun startsPausesAndResumesStopwatch() {
        assertTrue(engine.start(1, "Reading"))
        now += 30_000
        assertTrue(engine.pause(1))
        assertEquals(30_000, engine.snapshot().elapsedMillis)
        now += 50_000
        assertTrue(engine.start(1, "Reading"))
        now += 10_000
        assertEquals(40_000, engine.snapshot().elapsedMillis)
    }

    @Test
    fun blocksAnotherHabitWhileSessionHasProgress() {
        engine.start(1, "Reading")
        now += 1_000
        engine.pause(1)
        assertFalse(engine.start(2, "Speech"))
        assertEquals(1L, engine.snapshot().habitId)
    }

    @Test
    fun completesFocusThenBreakPaused() {
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")
        now += TimerSessionEngine.FOCUS_MILLIS
        assertEquals(PomodoroCompletion.FOCUS, engine.tick())
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)
        assertFalse(engine.snapshot().isRunning)

        engine.start(1, "Reading")
        now += TimerSessionEngine.BREAK_MILLIS
        assertEquals(PomodoroCompletion.BREAK, engine.tick())
        assertEquals(PomodoroPhase.FOCUS, engine.snapshot().phase)
        assertFalse(engine.snapshot().isRunning)
    }

    @Test
    fun usesConfiguredFocusAndBreakDurations() {
        assertTrue(engine.configurePomodoro(1, "Reading", 30_000, 10_000))
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        assertEquals(30_000, engine.snapshot().displayMillis)

        engine.start(1, "Reading")
        now += 30_000
        assertEquals(PomodoroCompletion.FOCUS, engine.tick())
        assertEquals(10_000, engine.snapshot().displayMillis)

        engine.start(1, "Reading")
        now += 10_000
        assertEquals(PomodoroCompletion.BREAK, engine.tick())
    }

    @Test
    fun blocksDurationChangeAfterSessionStarts() {
        engine.configurePomodoro(1, "Reading", 30_000, 10_000)
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")

        assertFalse(engine.configurePomodoro(1, "Reading", 45_000, 15_000))
        assertEquals(30_000, engine.snapshot().focusDurationMillis)
    }

    @Test
    fun partialFocusFinishReturnsElapsedAndResetDiscards() {
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")
        now += 90_000
        assertEquals(90_000, engine.finish(1))
        assertFalse(engine.snapshot().hasActiveSession)
        assertEquals(TimerMode.POMODORO, engine.snapshot().mode)
        assertEquals(PomodoroPhase.FOCUS, engine.snapshot().phase)
        assertEquals(TimerSessionEngine.FOCUS_MILLIS, engine.snapshot().displayMillis)

        engine.start(1, "Reading")
        now += 30_000
        assertTrue(engine.reset(1))
        assertEquals(0, engine.snapshot().elapsedMillis)
        assertEquals(TimerMode.POMODORO, engine.snapshot().mode)
    }

    @Test
    fun finishingBreakKeepsConfiguredPomodoroReadyForNextCycle() {
        engine.configurePomodoro(1, "Reading", 60_000, 30_000)
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")
        now += 60_000
        assertEquals(PomodoroCompletion.FOCUS, engine.tick())
        engine.start(1, "Reading")
        now += 30_000
        assertEquals(PomodoroCompletion.BREAK, engine.tick())

        val nextCycle = engine.snapshot()
        assertEquals(TimerMode.POMODORO, nextCycle.mode)
        assertEquals(PomodoroPhase.FOCUS, nextCycle.phase)
        assertFalse(nextCycle.hasActiveSession)
        assertEquals(60_000, nextCycle.displayMillis)
        assertEquals(0, engine.finish(1))
        assertEquals(TimerMode.POMODORO, engine.snapshot().mode)
        assertEquals(60_000, engine.snapshot().displayMillis)
    }

    @Test
    fun resettingBreakKeepsPomodoroAndItsDurations() {
        engine.configurePomodoro(1, "Reading", 60_000, 30_000)
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")
        now += 60_000
        engine.tick()

        assertTrue(engine.reset(1))
        assertEquals(TimerMode.POMODORO, engine.snapshot().mode)
        assertEquals(PomodoroPhase.FOCUS, engine.snapshot().phase)
        assertEquals(60_000, engine.snapshot().displayMillis)
    }

    @Test
    fun roundsElapsedTimeToTenthsOfMinute() {
        assertEquals(0.0, elapsedMillisToTenthsMinutes(2_999))
        assertEquals(0.1, elapsedMillisToTenthsMinutes(3_000))
        assertEquals(1.5, elapsedMillisToTenthsMinutes(90_000))
    }

    @Test
    fun restoresEngineState() {
        engine.restore(
            habitId = 42L,
            habitName = "Running",
            mode = TimerMode.POMODORO,
            phase = PomodoroPhase.BREAK,
            isRunning = true,
            accumulatedMillis = 15_000,
            startedAtMillis = 100L,
            focusDurationMillis = 50_000,
            breakDurationMillis = 20_000
        )
        val snap = engine.snapshot()
        assertEquals(42L, snap.habitId)
        assertEquals("Running", snap.habitName)
        assertEquals(TimerMode.POMODORO, snap.mode)
        assertEquals(PomodoroPhase.BREAK, snap.phase)
        assertTrue(snap.isRunning)
        assertEquals(50_000, snap.focusDurationMillis)
        assertEquals(20_000, snap.breakDurationMillis)
    }

    @Test
    fun restoresUnhandledOverdueCompletion() {
        engine.isAutoSwitch = false
        engine.restore(
            habitId = 42L,
            habitName = "Running",
            mode = TimerMode.POMODORO,
            phase = PomodoroPhase.FOCUS,
            isRunning = true,
            accumulatedMillis = 0,
            startedAtMillis = now - 30_000,
            focusDurationMillis = 20_000,
            breakDurationMillis = 10_000,
            completionTriggered = false
        )

        assertEquals(PomodoroCompletion.FOCUS, engine.tick())
        assertTrue(engine.snapshot().completionTriggered)
        assertEquals(null, engine.tick())
    }

    @Test
    fun restoresHandledOverdueCompletionWithoutRepeatingIt() {
        engine.isAutoSwitch = false
        engine.restore(
            habitId = 42L,
            habitName = "Running",
            mode = TimerMode.POMODORO,
            phase = PomodoroPhase.FOCUS,
            isRunning = true,
            accumulatedMillis = 0,
            startedAtMillis = now - 30_000,
            focusDurationMillis = 20_000,
            breakDurationMillis = 10_000,
            completionTriggered = true
        )

        assertEquals(null, engine.tick())
        assertTrue(engine.snapshot().completionTriggered)
    }

    @Test
    fun overtimeModeFocusDoesNotSwitchAutomatically() {
        engine.isAutoSwitch = false
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")

        now += TimerSessionEngine.FOCUS_MILLIS

        assertEquals(PomodoroCompletion.FOCUS, engine.tick())

        assertEquals(PomodoroPhase.FOCUS, engine.snapshot().phase)
        assertTrue(engine.snapshot().isRunning)
        assertFalse(engine.snapshot().isOvertime)

        assertEquals(0, engine.snapshot().displayMillis)

        now += 5_000

        assertEquals(null, engine.tick())
        assertEquals(5_000, engine.snapshot().displayMillis)
        assertTrue(engine.snapshot().isOvertime)

        engine.transitionToBreakManually()
        assertFalse(engine.snapshot().isRunning)
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)
        assertFalse(engine.snapshot().isOvertime)
    }

    @Test
    fun finishingOvertimeReturnsTheFullFocusTime() {
        engine.isAutoSwitch = false
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")
        now += TimerSessionEngine.FOCUS_MILLIS
        assertEquals(PomodoroCompletion.FOCUS, engine.tick())
        now += 16_000

        assertEquals(TimerSessionEngine.FOCUS_MILLIS + 16_000, engine.finish(1))
    }
@Test
    fun transitionToBreakManuallyIsIdempotentAndDoesNotToggleBackToFocus() {
        engine.isAutoSwitch = false
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")
        now += TimerSessionEngine.FOCUS_MILLIS + 10_000
        engine.tick()

        assertTrue(engine.transitionToBreakManually())
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)
        assertFalse(engine.snapshot().isRunning)

        // Repeated calls must be no-ops (idempotent) and never flip phase back to FOCUS
        assertFalse(engine.transitionToBreakManually())
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)
        assertFalse(engine.transitionToBreakManually())
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)
    }

    @Test
    fun breakCanBeStartedAndCountsDownAfterOvertimeTransition() {
        engine.isAutoSwitch = false
        engine.configurePomodoro(1, "Reading", 60_000, 30_000)
        engine.switchMode(1, "Reading", TimerMode.POMODORO)
        engine.start(1, "Reading")
        now += 70_000 // 10s overtime
        engine.tick()
        assertTrue(engine.snapshot().isOvertime)

        assertTrue(engine.transitionToBreakManually())
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)
        assertEquals(30_000, engine.snapshot().displayMillis)

        // Starting break initiates countdown
        assertTrue(engine.start(1, "Reading"))
        assertTrue(engine.snapshot().isRunning)
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)

        now += 10_000
        assertEquals(20_000, engine.snapshot().displayMillis)
        assertEquals(null, engine.tick())

        now += 20_000
        assertEquals(PomodoroCompletion.BREAK, engine.tick())
    }

    @Test
    fun restoresRunningAndStoppedBreakWithoutAlteringSequence() {
        // Stopped break restore
        engine.restore(
            habitId = 1L,
            habitName = "Study",
            mode = TimerMode.POMODORO,
            phase = PomodoroPhase.BREAK,
            isRunning = false,
            accumulatedMillis = 5_000,
            startedAtMillis = 0L,
            focusDurationMillis = 50_000,
            breakDurationMillis = 20_000,
            completionTriggered = false
        )
        val stopped = engine.snapshot()
        assertEquals(PomodoroPhase.BREAK, stopped.phase)
        assertFalse(stopped.isRunning)
        assertEquals(15_000, stopped.displayMillis)

        // Idempotence holds on restored break
        assertFalse(engine.transitionToBreakManually())
        assertEquals(PomodoroPhase.BREAK, engine.snapshot().phase)

        // Running break restore
        engine.restore(
            habitId = 1L,
            habitName = "Study",
            mode = TimerMode.POMODORO,
            phase = PomodoroPhase.BREAK,
            isRunning = true,
            accumulatedMillis = 5_000,
            startedAtMillis = now - 5_000,
            focusDurationMillis = 50_000,
            breakDurationMillis = 20_000,
            completionTriggered = false
        )
        val running = engine.snapshot()
        assertEquals(PomodoroPhase.BREAK, running.phase)
        assertTrue(running.isRunning)
        assertEquals(10_000, running.displayMillis)
    }
}
