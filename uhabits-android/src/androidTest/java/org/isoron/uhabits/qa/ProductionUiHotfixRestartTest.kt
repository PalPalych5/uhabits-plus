package org.isoron.uhabits.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.platform.time.getToday
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run in a separate instrumentation process immediately after the guarded direct-timer test. */
@RunWith(AndroidJUnit4::class)
class ProductionUiHotfixRestartTest {
    @Test fun creditsRemainIdempotentAfterProcessRestart() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val component = UiReviewCaptureTest.initializeFixture()
        val expected = JSONObject(File(ins.targetContext.getExternalFilesDir(null), "hotfix/direct-restart.json").readText())
        repeat(3) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() }
        val habit = component.habitList.getByUUID(expected.getString("habitUuid"))!!
        ins.runOnMainSync { component.timerSessionManager.finish(habit) }
        repeat(3) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() }
        assertEquals(expected.getInt("facts"), SQLiteFocusSessionRepository(component.db).getAll().size)
        assertEquals(expected.getInt("credits"), SQLiteContributionRepository(component.db).getAll().size)
        assertEquals(expected.getString("manual"), habit.originalEntries.getKnown().toList().toString())
        assertEquals(expected.getLong("total"), component.effectiveHabitReads!!.snapshot(habit).day(getToday()).totalValue)
    }
}
