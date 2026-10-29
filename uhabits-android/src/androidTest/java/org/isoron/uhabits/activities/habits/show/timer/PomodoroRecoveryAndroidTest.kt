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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PomodoroRecoveryAndroidTest {
    @Test fun overtimeCupStartsRunningBreakOnlyOnce() {
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
}
