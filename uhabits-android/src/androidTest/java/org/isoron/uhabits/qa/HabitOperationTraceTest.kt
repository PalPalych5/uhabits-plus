package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitFrameTiming
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.utils.DemoDataGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** A small diagnostic validation, restricted to the existing disposable offline fixture. */
@RunWith(AndroidJUnit4::class)
class HabitOperationTraceTest {
    private class Operation(val ordinal: Long, val scenario: String, val habitId: Long?, val target: CheckmarkButtonView?) {
        @Volatile var expected: Int? = null
        lateinit var trace: HabitEditTrace
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        @Volatile var terminal: String? = null
        @Volatile var parity = false
        var drawDisposition = "unmatched"
        var publishedRevision: Long? = null
        var publishedGeneration: Long? = null
        var publishedBatch: Long? = null
        var boundRevision: Long? = null
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun card(view: View?): HabitCardView? {
        var parent = view
        while (parent != null) {
            if (parent is HabitCardView) return parent
            parent = parent.parent as? View
        }
        return null
    }

    @Test fun correlateSmallOfflineSmoke() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val fixture = UiReviewCaptureTest.initializeFixture()
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${fixture.session.datasetId}")
        check(marker.exists() && marker.readText() == fixture.session.databaseFilename)
        check(fixture.db.queryLong("PRAGMA user_version") == 30L)
        check(fixture.session.isExperimental && !fixture.session.capabilities.syncEnabled && !fixture.session.capabilities.remoteCallsAllowed)
        ProductionUiHotfixFixture.prepare(fixture)
        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        check(!component.session.capabilities.syncEnabled && !component.session.capabilities.remoteCallsAllowed &&
            !component.session.capabilities.backgroundJobsEnabled && !component.session.capabilities.widgetsEnabled &&
            !component.session.capabilities.remindersEnabled)
        val operations = Collections.synchronizedList(mutableListOf<Operation>())
        val disabled = JSONArray()
        val enabled = JSONArray()
        val observerCosts = Collections.synchronizedList(mutableListOf<Long>())
        val targets = mutableListOf<CheckmarkButtonView>()
        val habits = mutableListOf<Habit>()
        var scenarioName = "sequential"
        var ordinal = 0L
        val controller = requireNotNull(component.effectiveHabitReads)
        fun drain() { repeat(5) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        fun stored(id: Long): Long = component.db.queryLong(
            "SELECT value FROM Repetitions WHERE habit=$id AND timestamp=${getToday().unixTime}")
        fun traceFor(op: Operation): HabitEditTrace = HabitEditTrace(op.ordinal) { trace, stage, revision, generation, batch ->
            val started = System.nanoTime()
            if (stage == "input.accepted") op.expected = op.target?.value
            if (stage == "sqlite.durable") {
                trace.durableRevision = component.effectiveReadRevision.value
                op.parity = op.habitId != null && stored(op.habitId) == op.expected?.toLong()
            }
            if (stage == "cache.row.published") {
                op.publishedRevision = revision; op.publishedGeneration = generation
                op.publishedBatch = batch
            }
            if (stage in listOf("command.finished", "command.failed", "command.cancelled")) op.terminal = stage
            op.events.add(JSONObject().put("stage", stage).put("timestampNanos", started)
                .put("mainThread", Looper.myLooper() == Looper.getMainLooper())
                .put("revision", revision ?: trace.durableRevision ?: JSONObject.NULL)
                .put("generation", generation ?: JSONObject.NULL).put("batch", batch ?: JSONObject.NULL))
            if (stage == "input.down") {
                Trace.beginSection("habit.op.input.${op.ordinal}"); Trace.endSection()
            }
            observerCosts.add(System.nanoTime() - started)
        }
        fun installObservers() {
            component.habitCardListCache.operationTraces = { id -> synchronized(operations) {
                operations.filter { (id == null || it.habitId == id) && it.trace.durableRevision != null }
                    .map { it.trace }
            } }
            component.habitCardListCache.effectiveSnapshotsTraced = { selected, traces, generation, batch ->
                controller.snapshotsTraced(selected, traces, generation, batch)
            }
            targets.forEach { button -> button.operationTraceFactory = {
                val id = requireNotNull(card(button)?.habit?.id)
                Operation(++ordinal, scenarioName, id, button).also { op ->
                    op.trace = traceFor(op); operations.add(op)
                }.trace
            } }
            HabitFrameTiming.observer = { stage, view, _ ->
                if (stage == "habit.card.bind" || stage == "habit.card.entry.bind") {
                    val c = card(view)
                    val id = c?.habit?.id
                    val snapshot = id?.let { component.habitCardListCache.getEffectiveSnapshot(it) }
                    val row = id?.let { component.habitCardListCache.getPublishedOperationTraces(it) }.orEmpty()
                    if (id != null && snapshot != null) synchronized(operations) {
                        operations.filter { it.trace in row }.forEach { op ->
                            op.boundRevision = snapshot.revision
                            op.trace.event("glyph.bind", snapshot.revision, op.publishedGeneration, op.publishedBatch)
                        }
                    }
                }
                if (stage == "habit.checkmark.draw" && view is CheckmarkButtonView && view in targets) {
                    val c = requireNotNull(card(view))
                    val id = requireNotNull(c.habit?.id)
                    val snapshot = component.habitCardListCache.getEffectiveSnapshot(id)
                    val row = component.habitCardListCache.getPublishedOperationTraces(id)
                    if (snapshot != null) synchronized(operations) {
                        val eligible = operations.filter { it.habitId == id && it.trace.durableRevision?.let { r -> r <= snapshot.revision } == true }
                        // Revision alone cannot identify a glyph: require captured row lineage, bound
                        // snapshot and synthetic manual/cell equality, and select the newest mutation.
                        val latest = eligible.maxByOrNull { it.trace.durableRevision!! }
                        if (latest != null && latest.trace in row &&
                            latest.publishedRevision == snapshot.revision && latest.parity &&
                            snapshot.day(getToday()).manualEntry.value == latest.expected && view.value == latest.expected) {
                            eligible.filter { it !== latest && it.drawDisposition == "unmatched" }.forEach {
                                it.drawDisposition = "superseded"; it.trace.event("glyph.superseded", snapshot.revision)
                            }
                            if (latest.drawDisposition == "unmatched") {
                                latest.drawDisposition = "drawn"
                                latest.trace.event("glyph.draw", snapshot.revision, latest.publishedGeneration, latest.publishedBatch)
                                Trace.beginSection("habit.op.committedDraw.${latest.ordinal}"); Trace.endSection()
                            }
                        }
                    }
                }
            }
        }
        fun removeObservers() {
            targets.forEach { it.operationTraceFactory = null }
            HabitFrameTiming.observer = null
            component.habitCardListCache.operationTraces = null
            component.habitCardListCache.effectiveSnapshotsTraced = null
        }
        try {
            // Synthetic daily targets are fixture-only, keeping the glyph oracle independent of
            // automatic frequency completion. They do not replace any existing fixture records.
            repeat(2) { index ->
                val h = component.sqlModelFactory.buildHabit().apply {
                    name = "Operation tracing fixture $index"; type = HabitType.YES_NO; frequency = Frequency(1, 1)
                    blockId = component.habitList.getByPosition(0).blockId
                }
                component.habitList.add(h)
                h.originalEntries.add(Entry(getToday(), Entry.NO))
                h.recompute()
                component.habitList.reorder(h, component.habitList.getByPosition(0)!!)
                habits.add(h)
            }
            component.preferences.isShortToggleEnabled = true
            component.preferences.isFirstRun = false
            component.preferences.defaultPrimaryOrder = org.isoron.uhabits.core.models.HabitList.Order.BY_POSITION
            component.preferences.defaultSecondaryOrder = org.isoron.uhabits.core.models.HabitList.Order.BY_POSITION
            QaEmulatorUi.clearSystemUiError()
            ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { activity ->
                activity.onActivity { it.navigate(MainDestination.HABITS) }
                drain(); SystemClock.sleep(250); drain()
                activity.onActivity { a ->
                    check(component === HabitsApplication.component) { "Activity component differs from fixture" }
                    val positions = (0 until component.habitCardListCache.habitCount).filter { position ->
                        component.habitCardListCache.getHabitByPosition(position)?.id in habits.map { it.id }
                    }
                    check(positions.size == 2) { "Both fixture targets must be in the published list" }
                    descendants(a.window.decorView).filterIsInstance<HabitCardListView>().first().apply {
                        dataOffset = 0
                        scrollToPosition(positions.min())
                    }
                }
                drain()
                activity.onActivity { a ->
                    val cards = descendants(a.window.decorView).filterIsInstance<HabitCardView>().toList()
                    val diagnostics = JSONArray(cards.mapIndexed { i, c -> JSONObject().put("rowOrdinal", i)
                        .put("targetOrdinal", habits.indexOfFirst { it.id == c.habit?.id }).put("shown", c.isShown)
                        .put("dataOffset", c.dataOffset) })
                    File(ins.targetContext.getExternalFilesDir(null), "habit-operation-viewport.json").writeText(diagnostics.toString())
                    habits.forEach { h -> targets.add(requireNotNull(cards.firstOrNull { it.isShown && it.habit?.id == h.id && it.dataOffset == 0 })
                        { "Target unavailable among ${cards.size} attached rows; see viewport diagnostics" }.checkmarkPanel.buttons.first()) }
                    targets.forEach { b -> b.requestRectangleOnScreen(android.graphics.Rect(0, 0, b.width, b.height), true) }
                }
                drain()
                fun input(button: CheckmarkButtonView) {
                    QaEmulatorUi.clearSystemUiError()
                    val xy = IntArray(2)
                    activity.onActivity { a ->
                        check(a.hasWindowFocus()) { "MainActivity has no input window focus" }
                        val bounds = android.graphics.Rect()
                        check(button.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) { "Target not in visible viewport" }
                        xy[0] = bounds.centerX(); xy[1] = bounds.centerY()
                    }
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, xy[0].toFloat(), xy[1].toFloat(), 0)
                        try { ins.sendPointerSync(event) } finally { event.recycle() }
                    }
                }
                installObservers()
                repeat(3) {
                    val started = System.nanoTime(); input(targets[0]); drain()
                    enabled.put(JSONObject().put("ordinal", it).put("inputToDrainNanos", System.nanoTime() - started))
                    SystemClock.sleep(250); drain()
                }
                scenarioName = "sameHabitBurst"
                // One Main turn preserves the burst; each dispatch still follows MotionEvent→View.
                activity.onActivity {
                    repeat(5) {
                        val b = targets[0]; val time = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val e = MotionEvent.obtain(time, time, action, (b.width / 2).toFloat(), (b.height / 2).toFloat(), 0)
                            try { b.dispatchTouchEvent(e) } finally { e.recycle() }
                        }
                    }
                }
                drain(); SystemClock.sleep(250); drain()
                scenarioName = "interleavedBurst"
                activity.onActivity {
                    repeat(5) { i ->
                        val b = targets[i % 2]; val time = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val e = MotionEvent.obtain(time, time, action, (b.width / 2).toFloat(), (b.height / 2).toFloat(), 0)
                            try { b.dispatchTouchEvent(e) } finally { e.recycle() }
                        }
                    }
                }
                drain(); SystemClock.sleep(250); drain()
                scenarioName = "controlledFailure"
                synchronized(operations) {
                    habits.forEach { h ->
                        val row = operations.filter { it.habitId == h.id && it.trace.durableRevision != null }
                        val latest = row.maxByOrNull { it.trace.durableRevision!! }
                        row.filter { it !== latest && it.drawDisposition == "unmatched" }.forEach {
                            it.drawDisposition = "superseded"; it.trace.event("glyph.superseded")
                        }
                    }
                }
                val before = stored(habits[0].id!!)
                component.db.run("CREATE TEMP TRIGGER qa_operation_failure BEFORE UPDATE ON Repetitions WHEN OLD.habit=${habits[0].id} BEGIN SELECT RAISE(ABORT, 'qa controlled failure'); END")
                try { input(targets[0]); drain() } finally { component.db.run("DROP TRIGGER qa_operation_failure") }
                val failed = synchronized(operations) { operations.last() }
                failed.parity = stored(habits[0].id!!) == before
                failed.drawDisposition = "notApplicable"
                assertEquals("command.failed", failed.terminal)
                assertNull(failed.trace.durableRevision)
                val cancelled = Operation(++ordinal, "controlledCancellation", null, null)
                cancelled.trace = traceFor(cancelled); cancelled.parity = true; cancelled.drawDisposition = "notApplicable"
                operations.add(cancelled)
                component.commandRunner.run(object : Command { override fun run() { throw CancellationException("fixture") } }, cancelled.trace)
                drain()
                assertEquals("command.cancelled", cancelled.terminal)
                removeObservers()
                // Same small sequential workload, no correlation hooks/observers, for overhead context.
                component.habitCardListCache.refreshAllHabits(); drain()
                repeat(3) {
                    val start = System.nanoTime(); input(targets[0]); drain()
                    disabled.put(JSONObject().put("ordinal", it).put("inputToDrainNanos", System.nanoTime() - start))
                    SystemClock.sleep(250); drain()
                }
                assertEquals(15, operations.size)
                assertEquals(13, operations.count { it.terminal == "command.finished" })
                assertTrue(operations.all { it.parity })
                operations.forEach { op ->
                    assertEquals(1, op.events.count { it.getString("stage") == "command.accepted" })
                    assertEquals(1, op.events.count { it.getString("stage") == op.terminal })
                    if (op.terminal == "command.finished") assertNotNull(op.trace.durableRevision)
                }
                // A latest state already painted optimistically may require no subsequent target
                // draw when its semantic binding is unchanged. Retain unmatched rather than
                // forcing invalidate or attributing an unrelated future frame to the operation.
                operations.filter { it.terminal == "command.finished" && it.drawDisposition == "unmatched" }
                    .forEach { assertNotNull(it.publishedRevision) }
                habits.forEach { h -> assertEquals(h.originalEntries.get(getToday()).value.toLong(), stored(h.id!!)) }
            }
            assertEquals("ok", component.db.queryString("PRAGMA quick_check"))
            val raw = JSONArray(operations.map { op -> JSONObject().put("ordinal", op.ordinal).put("scenario", op.scenario)
                .put("terminal", op.terminal).put("sqliteParity", op.parity).put("drawDisposition", op.drawDisposition)
                .put("events", JSONArray(op.events.toList())) })
            val output = File(ins.targetContext.getExternalFilesDir(null), "habit-operation-trace").apply { mkdirs() }
            File(output, "smoke.json").writeText(JSONObject().put("pid", Process.myPid()).put("schemaVersion", 30)
                .put("referenceDate", getToday().toString()).put("seed", 20261011)
                .put("journalMode", component.db.queryString("PRAGMA journal_mode")).put("operations", raw)
                .put("enabledSequential", enabled).put("disabledSequential", disabled)
                .put("observerCallbackNanos", JSONArray(observerCosts.toList()))
                .put("habitCount", component.habitList.size()).put("repetitionCount", component.db.queryLong("SELECT count(*) FROM Repetitions"))
                .put("measurement", "Sequential system-injected input; bursts dispatch real View MotionEvents in one Main turn. Monotonic stage timestamps; committed glyph requires exact published snapshot/manual parity/latest per-row durable operation. Presentation is joined separately; no raw values or stable record identities exported.")
                .toString(2))
        } finally {
            removeObservers()
            runCatching { component.db.run("DROP TRIGGER IF EXISTS qa_operation_failure") }
            ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        }
    }
}
