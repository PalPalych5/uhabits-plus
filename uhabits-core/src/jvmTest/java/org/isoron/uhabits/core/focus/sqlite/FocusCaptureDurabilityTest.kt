/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.focus.sqlite

import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.runtime.FocusCaptureTarget
import org.isoron.uhabits.core.focus.runtime.FocusCapturingTimer
import org.isoron.uhabits.core.timer.PomodoroCompletion
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionEngine
import java.nio.file.Files
import kotlin.test.*

class FocusCaptureDurabilityTest {
    @Test fun reopenBetweenStartAndCompletionKeepsStableIdentityAndCommittedLatch() {
        val path = Files.createTempFile("focus-capture-", ".db")
        var now = 1_000L
        val target = FocusCaptureTarget(FocusPrimaryTargetType.TASK, "task", organizationContext = "{\"path\":\"original\"}")
        try {
            val first = JavaDatabaseOpener().open(path.toString())
            try {
                val timer = FocusCapturingTimer(TimerSessionEngine { now }, SQLiteFocusCaptureStore(first),
                    { now }, { "stable-id" }, "original-device", { target })
                timer.configurePomodoro(-2, "Task", 1_000, 300)
                timer.switchMode(-2, "Task", TimerMode.POMODORO)
                timer.start(-2, "Task")
            } finally { first.close() }
            now += 1_100
            val second = JavaDatabaseOpener().open(path.toString())
            try {
                val restored = FocusCapturingTimer(TimerSessionEngine { now }, SQLiteFocusCaptureStore(second),
                    { now }, { "must-not-replace-stable-id" }, "changed-device")
                assertEquals(target, restored.currentTarget)
                assertEquals(PomodoroCompletion.FOCUS, restored.tick())
                val fact = SQLiteFocusSessionRepository(second).getAll().single()
                assertEquals("stable-id", fact.id.value)
                assertEquals("original-device", fact.deviceId)
                assertEquals(1_000L, fact.durationMs)
                assertEquals(target.organizationContext, fact.organizationContext)
            } finally { second.close() }
            // Simulates death after the DB commit but before legacy preference persistence.
            val third = JavaDatabaseOpener().open(path.toString())
            try {
                val restored = FocusCapturingTimer(TimerSessionEngine { now }, SQLiteFocusCaptureStore(third),
                    { now }, { "unused" })
                assertNull(restored.tick())
                assertEquals(1, SQLiteFocusSessionRepository(third).getAll().size)
            } finally { third.close() }
        } finally { Files.deleteIfExists(path) }
    }
}
