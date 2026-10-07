package org.isoron.uhabits.core.production.preflight

import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyTimerReadinessClassifierTest {
    @Test fun inactiveIsReady() { assertEquals(TimerMigrationReadiness.READY, LegacyTimerReadinessClassifier.classify(emptyMap())) }
    @Test fun merelyConfiguredTimerIsReady() { assertEquals(TimerMigrationReadiness.READY, classify()) }
    @Test fun runningStopwatchBlocks() { assertEquals(TimerMigrationReadiness.BLOCKED_ACTIVE_WORK, classify("pref_timer_is_running" to true, "pref_timer_started_at_wall_clock_millis" to 100L)) }
    @Test fun pausedWorkBlocks() { assertEquals(TimerMigrationReadiness.BLOCKED_ACTIVE_WORK, classify("pref_timer_accumulated_elapsed_millis" to 500L)) }
    @Test fun pomodoroFocusAndOvertimeBlock() { assertEquals(TimerMigrationReadiness.BLOCKED_ACTIVE_WORK, classify("pref_timer_mode" to "POMODORO", "pref_timer_phase" to "FOCUS", "pref_timer_accumulated_elapsed_millis" to 2000000L)) }
    @Test fun breakBlocksEvenWhenPaused() { assertEquals(TimerMigrationReadiness.BLOCKED_ACTIVE_WORK, classify("pref_timer_mode" to "POMODORO", "pref_timer_phase" to "BREAK")) }
    @Test fun pendingCompletionBlocks() { assertEquals(TimerMigrationReadiness.BLOCKED_PENDING_COMPLETION, classify("pref_timer_completion_display_active" to true)) }
    @Test fun badPreferenceTypeAndOrphanRunningStateAreInconsistent() {
        assertEquals(TimerMigrationReadiness.INCONSISTENT_STATE, classify("pref_timer_is_running" to "true"))
        assertEquals(TimerMigrationReadiness.INCONSISTENT_STATE, LegacyTimerReadinessClassifier.classify(mapOf("pref_timer_is_running" to true)))
        assertEquals(TimerMigrationReadiness.INCONSISTENT_STATE, classify("pref_timer_accumulated_elapsed_millis" to -1L))
        assertEquals(TimerMigrationReadiness.INCONSISTENT_STATE, classify("pref_timer_accumulated_elapsed_millis" to 1.5))
        assertEquals(TimerMigrationReadiness.INCONSISTENT_STATE, classify("pref_timer_alarm_revision" to -1L))
        assertEquals(TimerMigrationReadiness.INCONSISTENT_STATE, classify("pref_timer_completion_event_id" to "bad"))
    }
    private fun classify(vararg values: Pair<String, Any>) = LegacyTimerReadinessClassifier.classify(mapOf("pref_timer_habit_id" to 1L) + values)
}
