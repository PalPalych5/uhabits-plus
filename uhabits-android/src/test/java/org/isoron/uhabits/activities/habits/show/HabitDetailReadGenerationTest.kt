package org.isoron.uhabits.activities.habits.show

import org.junit.Assert.assertEquals
import org.junit.Test

class HabitDetailReadGenerationTest {
    @Test fun olderReadCannotOverwriteNewerRenderedValue() {
        val reads = HabitDetailReadGeneration()
        val beforeCommand = reads.begin()
        val afterCommand = reads.begin()
        var rendered = "initial"
        fun finish(request: Long, value: String) {
            if (reads.accepts(request)) rendered = value
        }
        finish(afterCommand, "committed")
        finish(beforeCommand, "obsolete")
        assertEquals("committed", rendered)
    }

    @Test fun pausedActivityCannotPublishPendingReadEvenAfterResume() {
        val reads = HabitDetailReadGeneration()
        val pendingBeforePause = reads.begin()
        reads.invalidate()
        var rendered = "initial"
        if (reads.accepts(pendingBeforePause)) rendered = "obsolete"
        assertEquals("initial", rendered)
        val resumed = reads.begin()
        if (reads.accepts(resumed)) rendered = "resumed"
        if (reads.accepts(pendingBeforePause)) rendered = "obsolete"
        assertEquals("resumed", rendered)
    }
}
