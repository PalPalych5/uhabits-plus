package org.isoron.uhabits.activities.habits.show.timer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PomodoroRecoveryAndroidTest {

    private val TimerSessionSnapshot.remainingMillis: Long
        get() = (breakDurationMillis - elapsedMillis).coerceAtLeast(0L)

    @Test
    fun overtimeCupStartsRunningBreakOnlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as HabitsApplication
        val original = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val session = original.copy(
            datasetId = "pomodoro-$suffix",
            databaseFilename = "uhabits-test-pomodoro-$suffix.db",
            preferencesNamespace = "uhabits-test-pomodoro-$suffix",
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(timerEnabled = true),
        )
        instrumentation.runOnMainSync { app.rebuildComponent(session) }
        val component = HabitsApplication.component
        try {
            val habit = component.modelFactory.buildHabit().apply { name = "Pomodoro QA"; type = HabitType.YES_NO }
            component.habitList.add(habit)
            val timer = component.timerSessionManager
            instrumentation.runOnMainSync {
                component.preferences.isPomodoroAutoSwitch = false
                assertTrue(timer.updateDurations(habit, 1, 1))
                assertTrue(timer.switchMode(habit, TimerMode.POMODORO))
                assertTrue(timer.startOrPause(habit))
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 80000
            while (!timer.snapshot().isOvertime && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(250)
            assertTrue("Focus must enter overtime", timer.snapshot().isOvertime)
            instrumentation.runOnMainSync { timer.takeBreakCurrent() }
            val first = timer.snapshot()
            assertEquals(PomodoroPhase.BREAK, first.phase)
            assertTrue(first.isRunning)
            Thread.sleep(1200)
            instrumentation.runOnMainSync { timer.takeBreakCurrent() }
            val repeated = timer.snapshot()
            assertEquals(PomodoroPhase.BREAK, repeated.phase)
            assertTrue(repeated.isRunning)
            assertTrue("Break must count forward", repeated.elapsedMillis > first.elapsedMillis)
            val recorded = component.focusSessionRepository?.getByTimeRange(0, Long.MAX_VALUE).orEmpty()
            assertEquals("FocusSession base and overtime must be recorded without duplicates on repeated transition", 2, recorded.size)
            assertTrue("Base session must equal focus duration (60s)", recorded[0].durationMs == 60_000L || recorded[1].durationMs == 60_000L)
        } finally {
            instrumentation.runOnMainSync {
                component.timerSessionManager.resetCurrent()
                app.rebuildComponent(original)
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun phase1_startPomodoroBreakAndRecordPidBeforeProcessKill() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as HabitsApplication
        val original = HabitsApplication.component.session
        val session = original.copy(
            datasetId = "pomodoro-procdeath",
            databaseFilename = "uhabits-test-pomodoro-procdeath.db",
            preferencesNamespace = "uhabits-test-pomodoro-procdeath",
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(timerEnabled = true),
        )

        // Reset prior state for deterministic test run
        context.deleteDatabase("uhabits-test-pomodoro-procdeath.db")
        context.getSharedPreferences("uhabits-test-pomodoro-procdeath", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("uhabits-test-pomodoro-procdeath_timer", Context.MODE_PRIVATE).edit().clear().commit()

        instrumentation.runOnMainSync {
            app.rebuildComponent(session)
        }
        val component = HabitsApplication.component
        instrumentation.runOnMainSync {
            component.timerSessionManager.resetCurrent()
        }

        val habit = component.modelFactory.buildHabit().apply {
            name = "Pomodoro Process Death Habit"
            type = HabitType.YES_NO
        }
        component.habitList.add(habit)

        val timer = component.timerSessionManager
        instrumentation.runOnMainSync {
            component.preferences.isPomodoroAutoSwitch = false
            assertTrue(timer.updateDurations(habit, 1, 1))
            assertTrue(timer.switchMode(habit, TimerMode.POMODORO))
            assertTrue(timer.startOrPause(habit))
        }

        // Wait until focus enters overtime
        val deadline = android.os.SystemClock.elapsedRealtime() + 80000
        while (!timer.snapshot().isOvertime && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("Focus must enter overtime", timer.snapshot().isOvertime)

        // Transition from overtime into break
        instrumentation.runOnMainSync { timer.takeBreakCurrent() }
        val snapshot = timer.snapshot()
        assertEquals(PomodoroPhase.BREAK, snapshot.phase)
        assertTrue("Break must be actively running", snapshot.isRunning)

        val myPid = android.os.Process.myPid()
        val remaining = snapshot.remainingMillis
        assertTrue("Remaining break millis must be positive", remaining > 0)

        val testPrefs = context.getSharedPreferences("uhabits-test-pomodoro-procdeath", Context.MODE_PRIVATE)
        testPrefs.edit()
            .putInt("phase1_pid", myPid)
            .putLong("phase1_habit_id", habit.id!!)
            .putLong("phase1_remaining_millis", remaining)
            .commit()

        // Verify focus sessions were written to SQLiteFocusSessionRepository
        val recordedSessions = component.focusSessionRepository?.getByTimeRange(0, Long.MAX_VALUE).orEmpty()
        assertEquals("FocusSession base and overtime must be recorded before process kill", 2, recordedSessions.size)
        // State remains flushed to SQLite and SharedPreferences. The test process will be killed by orchestrator.
    }

    @Test
    fun phase2_verifyPomodoroBreakRecoveredInNewProcessAfterKill() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as HabitsApplication
        val original = HabitsApplication.component.session
        val session = original.copy(
            datasetId = "pomodoro-procdeath",
            databaseFilename = "uhabits-test-pomodoro-procdeath.db",
            preferencesNamespace = "uhabits-test-pomodoro-procdeath",
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(timerEnabled = true),
        )

        // Rebuild HabitsApplication component cold with the exact same persistent session WITHOUT resetting timerSessionManager
        instrumentation.runOnMainSync {
            app.rebuildComponent(session)
        }
        instrumentation.waitForIdleSync()
        val component = HabitsApplication.component

        val testPrefs = context.getSharedPreferences("uhabits-test-pomodoro-procdeath", Context.MODE_PRIVATE)
        val phase1Pid = testPrefs.getInt("phase1_pid", -1)
        assertTrue("Phase 1 PID must have been recorded", phase1Pid != -1)
        assertNotEquals("Must run in a brand-new OS process after kill", phase1Pid, android.os.Process.myPid())

        val phase1RemainingMillis = testPrefs.getLong("phase1_remaining_millis", 0L)
        assertTrue("Phase 1 remaining millis must have been recorded", phase1RemainingMillis > 0)

        try {
            val snapshot = component.timerSessionManager.snapshot()
            assertEquals("Pomodoro phase must be recovered as BREAK", PomodoroPhase.BREAK, snapshot.phase)
            assertTrue("Recovered break must be actively running", snapshot.isRunning)
            assertTrue("Remaining break millis must be positive", snapshot.remainingMillis > 0)
            assertTrue(
                "Remaining break millis (${snapshot.remainingMillis}) must be <= phase1 remaining ($phase1RemainingMillis)",
                snapshot.remainingMillis <= phase1RemainingMillis
            )

            val sessions = component.focusSessionRepository?.getByTimeRange(0, Long.MAX_VALUE).orEmpty()
            assertEquals("FocusSession records persisted before process death must be 100% intact", 2, sessions.size)
        } finally {
            instrumentation.runOnMainSync {
                component.timerSessionManager.resetCurrent()
                app.rebuildComponent(original)
            }
            instrumentation.waitForIdleSync()
            context.deleteDatabase("uhabits-test-pomodoro-procdeath.db")
            testPrefs.edit().clear().commit()
            context.getSharedPreferences("uhabits-test-pomodoro-procdeath_timer", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
