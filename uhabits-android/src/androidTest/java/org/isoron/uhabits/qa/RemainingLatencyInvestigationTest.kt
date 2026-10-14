package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Build
import android.os.Debug
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
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.HabitFrameTiming
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.core.io.HabitStartupTiming
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.utils.DemoDataGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bounded paired workload; only the established disposable, offline v30 AVD may run it. */
@RunWith(AndroidJUnit4::class)
class RemainingLatencyInvestigationTest {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun card(view: View?): HabitCardView? {
        var current = view
        while (current != null) {
            if (current is HabitCardView) return current
            current = current.parent as? View
        }
        return null
    }

    @Test fun attributeBoundedLatency() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(ins)
        // Check the device before fixture initialization can perform any writes.
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"))
        check(args.getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.serialno").trim() == "EMULATOR36X6X11X0")
        check(device.executeShellCommand("settings get global airplane_mode_on").trim() == "1")
        val depth = args.getString("historyDays", "90").toInt()
        check(depth in listOf(90, 1825))
        val enabled = args.getString("tracingEnabled", "true").toBooleanStrict()
        val label = args.getString("buildLabel", "unspecified")
        check(label.matches(Regex("[A-Za-z0-9_-]+")))
        val sortConfiguration = args.getString("sortConfiguration", "grouped")
        check(sortConfiguration in listOf("grouped", "entrySensitive"))
        val fixture = UiReviewCaptureTest.initializeFixture()
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${fixture.session.datasetId}")
        check(marker.exists() && marker.readText() == fixture.session.databaseFilename)
        check(fixture.db.queryLong("PRAGMA user_version") == 30L)
        setToday(LocalDate(2026, 10, 11))
        ProductionUiHotfixFixture.prepare(fixture)
        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        check(component.session.mode == DatasetMode.PRODUCTION_NEXT)
        check(!component.session.capabilities.syncEnabled && !component.session.capabilities.remoteCallsAllowed &&
            !component.session.capabilities.backgroundJobsEnabled && !component.session.capabilities.widgetsEnabled &&
            !component.session.capabilities.remindersEnabled)
        val controller = requireNotNull(component.effectiveHabitReads)
        val today = LocalDate(2026, 10, 11)
        setToday(today)
        fun drain() { repeat(5) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
        val costs = Collections.synchronizedList(mutableListOf<Long>())
        val operations = mutableListOf<JSONObject>()
        val viewportDiagnostics = JSONArray()
        val controlledFailures = Collections.synchronizedList(mutableListOf<JSONObject>())
        val traces = Collections.synchronizedList(mutableListOf<HabitEditTrace>())
        val targets = mutableListOf<CheckmarkButtonView>()
        val expected = Collections.synchronizedMap(mutableMapOf<Long, Int>())
        val drawn = Collections.synchronizedSet(mutableSetOf<Long>())
        val publications = Collections.synchronizedMap(mutableMapOf<Long, Triple<Long?, Long?, Long?>>())
        fun memorySample(): JSONObject {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            return JSONObject().put("timestampNanos", System.nanoTime()).put("totalPssKb", info.totalPss)
                .put("runtimeUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("runtimeAllocatedBytes", runtime.totalMemory())
        }
        val memoryBeginning = memorySample()
        var phase = "fixture"
        var ordinal = 0L
        var activeOperation = 0L
        var navigationDrawOperation = 0L
        var navigationRevision = -1L
        fun event(stage: String, fields: JSONObject = JSONObject()) {
            val start = System.nanoTime()
            if (!fields.has("operation")) fields.put("operation", activeOperation)
            fields.put("stage", stage).put("timestampNanos", start).put("phase", phase)
                .put("thread", Thread.currentThread().name)
                .put("mainThread", Looper.myLooper() == Looper.getMainLooper())
            events.add(fields)
            costs.add(System.nanoTime() - start)
        }
        try {
            val block = component.habitList.getByPosition(0).blockId
            val habits = (0 until 10).map { index ->
                component.sqlModelFactory.buildHabit().apply {
                    name = "Remaining latency fixture $index"; type = HabitType.YES_NO
                    frequency = Frequency.DAILY; blockId = block
                }.also { component.habitList.add(it) }
            }
            component.db.beginTransaction()
            try {
                val stmt = component.db.prepareStatement("INSERT OR REPLACE INTO Repetitions (habit,timestamp,value) VALUES (?,?,?)")
                habits.forEach { habit ->
                    for (d in 0 until depth) {
                        stmt.bindLong(1, habit.id!!); stmt.bindLong(2, today.minus(d).unixTime)
                        stmt.bindLong(3, (if (d == 0 || d % 7 == 0) Entry.NO else Entry.YES_MANUAL).toLong())
                        stmt.step(); stmt.reset()
                    }
                    habit.originalEntries.invalidate(); habit.computedEntries.invalidate()
                }
                component.db.setTransactionSuccessful()
            } finally { component.db.endTransaction() }
            component.habitList.reorder(habits.first(), component.habitList.getByPosition(0))
            component.preferences.isFirstRun = false
            component.preferences.isShortToggleEnabled = true
            component.preferences.areHabitGroupSeparatorsEnabled = true
            component.preferences.isDayTiersEnabled = true
            component.preferences.defaultPrimaryOrder = if (sortConfiguration == "grouped")
                HabitList.Order.BY_DAY_TIER else HabitList.Order.BY_STATUS_DESC
            component.preferences.defaultSecondaryOrder = HabitList.Order.BY_POSITION
            drain()
            if (enabled) {
                HabitStartupTiming.observer = { stage, generation -> event(stage,
                    JSONObject().put("generation", generation ?: JSONObject.NULL)) }
                controller.requestMetricsObserver = { m -> event("controller.request", JSONObject()
                    .put("totalMs", m.totalMs).put("monitorWaitMs", m.monitorWaitMs).put("monitorHeldMs", m.monitorHeldMs)
                    .put("latchMs", m.latchMs).put("latchCount", m.latchCount).put("prepareMs", m.prepareMs)
                    .put("prepareCount", m.prepareCount).put("retries", m.retries)
                    .put("validatedPublicationMs", m.validatedPublicationMs ?: JSONObject.NULL)
                    .put("joinedHabits", m.joinedHabits).put("createdBatches", m.createdBatches)
                    .put("maxConcurrentPreparations", m.maxConcurrentPreparations).put("onMain", m.onMain)
                    .put("outcome", m.outcome).put("habitCount", m.habitCount)
                    .put("generation", m.generation ?: JSONObject.NULL).put("batch", m.batch ?: JSONObject.NULL)) }
                controller.lockMetricsObserver = { m -> event("controller.lock", JSONObject()
                    .put("waitMs", m.lockWaitDurationMs).put("heldMs", m.lockHeldDurationMs)
                    .put("hits", m.cacheHitCount).put("prepared", m.preparedCount)) }
                controller.snapshotMetricsObserver = { m -> event("provider.prepare", JSONObject()
                    .put("totalMs", m.totalPrepareDurationMs).put("habitCount", m.habitCount)
                    .put("processedDays", m.processedDays).put("copyMs", m.copyDetachedDurationMs)
                    .put("contributionsMs", m.contributionsFetchDurationMs).put("discoveryMs", m.domainDiscoveryDurationMs)
                    .put("daysMs", m.daysForRangeDurationMs).put("initMs", m.snapshotInitDurationMs)
                    .put("scoresMs", m.scoresAndStreaksDurationMs)) }
                component.habitCardListCache.operationTraces = { id -> synchronized(traces) {
                    traces.filter { t -> t.durableRevision != null && (id == null || id == habits.first().id) }
                } }
                component.habitCardListCache.effectiveSnapshotsTraced = { selected, row, generation, batch ->
                    controller.snapshots(selected, today.minus(365), today, row, generation, batch)
                }
                HabitFrameTiming.observer = { stage, view, duration ->
                    frames.add(JSONObject().put("stage", stage).put("timestampNanos", System.nanoTime())
                        .put("durationNanos", duration).put("phase", phase).put("operation", activeOperation)
                        .put("targetRow", card(view)?.habit?.id == habits.first().id))
                    if (stage == "habit.card.bind" || stage == "habit.card.entry.bind") {
                        val id = card(view)?.habit?.id
                        if (id == habits.first().id) {
                            val snapshot = component.habitCardListCache.getEffectiveSnapshot(id!!)
                            if (snapshot != null) component.habitCardListCache.getPublishedOperationTraces(id).forEach {
                                val lineage = publications[it.ordinal]
                                if (lineage?.first == snapshot.revision) it.event("glyph.bind", snapshot.revision, lineage?.second, lineage?.third)
                            }
                        }
                    }
                    if (stage == "habit.checkmark.draw" && view is CheckmarkButtonView && card(view)?.habit?.id == habits.first().id &&
                        view === card(view)?.checkmarkPanel?.buttons?.firstOrNull() && card(view)?.dataOffset == 0) {
                        val snapshot = component.habitCardListCache.getEffectiveSnapshot(habits.first().id!!)
                        val row = component.habitCardListCache.getPublishedOperationTraces(habits.first().id!!)
                        val latest = synchronized(traces) { traces.lastOrNull { it.durableRevision != null } }
                        val lineage = latest?.let { publications[it.ordinal] }
                        if (navigationDrawOperation > 0 && snapshot?.revision == navigationRevision && drawn.add(navigationDrawOperation)) {
                            event("navigation.habits.draw", JSONObject().put("operation", navigationDrawOperation))
                            Trace.beginSection("habit.nav.habitsDraw.$navigationDrawOperation"); Trace.endSection()
                        }
                        if (snapshot != null && latest != null && latest in row &&
                            lineage?.first == snapshot.revision &&
                            snapshot.revision >= latest.durableRevision!! &&
                            snapshot.day(today).manualEntry.value == expected[latest.ordinal] &&
                            snapshot.day(today).manualEntry.value == view.value && drawn.add(latest.ordinal)) {
                            latest.event("glyph.draw", snapshot.revision, lineage?.second, lineage?.third)
                            Trace.beginSection("habit.op.committedDraw.${latest.ordinal}"); Trace.endSection()
                        }
                    }
                }
            }
            // Start two real readers together, without changing production scheduling or
            // blocking provider hooks. Retain timestamps; overlap is measured, not assumed.
            for (cold in listOf(true, false)) {
                phase = if (cold) "coldConcurrentReaders" else "warmConcurrentReaders"
                if (cold) component.effectiveReadRevision.changed()
                val ready = CountDownLatch(2); val start = CountDownLatch(1)
                val readers = listOf(habits, listOf(habits.first())).mapIndexed { i, selected ->
                    Thread({
                        ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                        val began = System.nanoTime()
                        try {
                            val result = controller.snapshots(selected)
                            assertEquals(selected.size, result.size)
                            assertTrue(result.values.all { it.revision == component.effectiveReadRevision.value })
                            event("controlled.reader.finished", JSONObject().put("reader", i)
                                .put("startNanos", began).put("totalNanos", System.nanoTime() - began))
                        } catch (t: Throwable) {
                            val failure = JSONObject().put("reader", i).put("failureClass", t.javaClass.name)
                                .put("startNanos", began).put("totalNanos", System.nanoTime() - began)
                            event("controlled.reader.failure", failure)
                            controlledFailures.add(failure)
                        }
                    }, "qa-latency-reader-$i")
                }
                readers.forEach { it.start() }; check(ready.await(10, TimeUnit.SECONDS)); start.countDown()
                readers.forEach { it.join(30000); check(!it.isAlive) }
            }
            phase = "coldLaunch"
            // Cold labels concern fresh controller projection and invalidated entry caches;
            // APK process, SQLite connection, JIT and OS page cache are already warm.
            component.effectiveReadRevision.changed()
            habits.forEach { it.originalEntries.invalidate(); it.computedEntries.invalidate() }
            QaEmulatorUi.clearSystemUiError()
            val launchStart = System.nanoTime()
            ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { activity ->
                activity.onActivity { it.navigate(MainDestination.HABITS) }
                drain(); SystemClock.sleep(250); drain()
                operations.add(JSONObject().put("scenario", "coldLaunch").put("durationToDrainNanos", System.nanoTime() - launchStart))
                fun target(): CheckmarkButtonView {
                    var button: CheckmarkButtonView? = null
                    var viewportReady = false
                    var needsRebind = false
                    activity.onActivity { a ->
                        val position = (0 until component.habitCardListCache.habitCount).first {
                            component.habitCardListCache.getHabitByPosition(it)?.id == habits.first().id }
                        descendants(a.window.decorView).filterIsInstance<HabitCardListView>().first().apply {
                            dataOffset = 0; scrollToPosition(position)
                        }
                    }
                    drain()
                    repeat(20) {
                        if (!viewportReady) {
                            activity.onActivity { a ->
                                val list = descendants(a.window.decorView).filterIsInstance<HabitCardListView>().first()
                                viewportReady = list.isLaidOut && list.width > 0 && list.checkmarkCount > 0
                            }
                            if (!viewportReady) { SystemClock.sleep(50); ins.waitForIdleSync() }
                        }
                    }
                    check(viewportReady) { "Grouped list has no laid-out checkmark columns" }
                    activity.onActivity { a ->
                        val list = descendants(a.window.decorView).filterIsInstance<HabitCardListView>().first()
                        val rows = descendants(a.window.decorView).filterIsInstance<HabitCardView>().toList()
                        viewportDiagnostics.put(JSONObject().put("stage", "beforeSetupRebind").put("phase", phase)
                            .put("operation", activeOperation).put("checkmarkCount", list.checkmarkCount).put("width", list.width)
                            .put("rows", JSONArray(rows.mapIndexed { index, row -> JSONObject().put("rowOrdinal", index)
                                .put("targetOrdinal", habits.indexOfFirst { it.id == row.habit?.id })
                                .put("shown", row.isShown).put("buttonCount", row.checkmarkPanel.buttons.size)
                                .put("width", row.width).put("dataOffset", row.dataOffset) })))
                        val row = descendants(a.window.decorView).filterIsInstance<HabitCardView>()
                            .firstOrNull { it.isShown && it.habit?.id == habits.first().id && it.dataOffset == 0 }
                        needsRebind = row?.checkmarkPanel?.buttons?.isEmpty() == true
                    }
                    if (needsRebind) {
                        // Root size can arrive after initial holder binding. Exercise the existing
                        // refresh path to establish the viewport before measured input, without
                        // assigning product View properties or claiming this as user workload.
                        val previousPhase = phase; phase = "viewportSetupRebind"
                        component.habitCardListCache.refreshAllHabits(); drain()
                        activity.onActivity { a ->
                            descendants(a.window.decorView).filterIsInstance<HabitCardListView>().first()
                                .adapter?.notifyDataSetChanged()
                        }
                        drain()
                        phase = previousPhase
                    }
                    activity.onActivity { a ->
                        val list = descendants(a.window.decorView).filterIsInstance<HabitCardListView>().first()
                        val rows = descendants(a.window.decorView).filterIsInstance<HabitCardView>().toList()
                        viewportDiagnostics.put(JSONObject().put("stage", "afterSetupRebind").put("phase", phase)
                            .put("operation", activeOperation).put("checkmarkCount", list.checkmarkCount).put("width", list.width)
                            .put("rows", JSONArray(rows.mapIndexed { index, row -> JSONObject().put("rowOrdinal", index)
                                .put("targetOrdinal", habits.indexOfFirst { it.id == row.habit?.id })
                                .put("shown", row.isShown).put("buttonCount", row.checkmarkPanel.buttons.size)
                                .put("width", row.width).put("dataOffset", row.dataOffset) })))
                    }
                    repeat(20) {
                        if (button == null) {
                            activity.onActivity { a ->
                                button = descendants(a.window.decorView).filterIsInstance<HabitCardView>()
                                    .firstOrNull { it.isShown && it.habit?.id == habits.first().id && it.dataOffset == 0 }
                                    ?.checkmarkPanel?.buttons?.firstOrNull { b -> b.isLaidOut && b.width > 0 && b.height > 0 }
                            }
                            if (button == null) { SystemClock.sleep(50); ins.waitForIdleSync() }
                        }
                    }
                    return requireNotNull(button) { "Target has no visible laid-out checkmark after bounded viewport preparation" }
                }
                fun input(button: CheckmarkButtonView) {
                    val xy = IntArray(2)
                    activity.onActivity { a ->
                        check(a.hasWindowFocus())
                        val rect = android.graphics.Rect()
                        check(button.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0)
                        xy[0] = rect.centerX(); xy[1] = rect.centerY()
                    }
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, xy[0].toFloat(), xy[1].toFloat(), 0)
                        try { ins.sendPointerSync(motion) } finally { motion.recycle() }
                    }
                }
                for (sample in 0..2) {
                    phase = "viewportSetup"; activeOperation = 0L
                    val button = target(); targets.add(button)
                    activeOperation = ++ordinal
                    phase = if (sample == 0) "coldProjectionEdit" else "warmEdit"
                    if (sample == 0) {
                        component.effectiveReadRevision.changed()
                        habits.forEach { it.originalEntries.invalidate(); it.computedEntries.invalidate() }
                    }
                    val before = component.db.queryLong("SELECT value FROM Repetitions WHERE habit=${habits.first().id} AND timestamp=${today.unixTime}")
                    val op = activeOperation
                    if (enabled) button.operationTraceFactory = {
                        HabitEditTrace(op) { trace, stage, revision, generation, batch ->
                            if (stage == "input.accepted") expected[op] = button.value
                            if (stage == "sqlite.durable") trace.durableRevision = component.effectiveReadRevision.value
                            if (stage == "cache.row.published") publications[op] = Triple(revision, generation, batch)
                            if (stage == "input.down") {
                                Trace.beginSection("habit.op.input.$op"); Trace.endSection()
                            }
                            event(stage, JSONObject().put("operation", op).put("revision", revision ?: trace.durableRevision ?: JSONObject.NULL)
                                .put("generation", generation ?: JSONObject.NULL).put("batch", batch ?: JSONObject.NULL))
                        }.also { traces.add(it) }
                    }
                    val start = System.nanoTime(); input(button); drain()
                    val drained = System.nanoTime()
                    SystemClock.sleep(250); drain()
                    val stored = component.db.queryLong("SELECT value FROM Repetitions WHERE habit=${habits.first().id} AND timestamp=${today.unixTime}")
                    assertNotEquals(before, stored)
                    assertEquals(stored, habits.first().originalEntries.get(today).value.toLong())
                    // Queue idle is not a freshness endpoint: a delayed refresh can still be
                    // pending. Observe existing publication only; never force a refresh here.
                    val settleStart = System.nanoTime()
                    val deadline = settleStart + TimeUnit.SECONDS.toNanos(10)
                    val observations = JSONArray()
                    var snapshotParity = false
                    var viewParity = false
                    var viewAvailable = false
                    var staleObservations = 0
                    var snapshotReadyNanos: Long? = null
                    var viewReadyNanos: Long? = null
                    do {
                        val snapshot = component.habitCardListCache.getEffectiveSnapshot(habits.first().id!!)
                        snapshotParity = snapshot != null && snapshot.revision == component.effectiveReadRevision.value &&
                            snapshot.day(today).manualEntry.value.toLong() == stored
                        activity.onActivity { a ->
                            val row = descendants(a.window.decorView).filterIsInstance<HabitCardView>()
                                .firstOrNull { it.isShown && it.habit?.id == habits.first().id && it.dataOffset == 0 }
                            viewAvailable = row?.checkmarkPanel?.buttons?.firstOrNull() != null
                            viewParity = row?.checkmarkPanel?.buttons?.firstOrNull()?.value?.toLong() == stored
                        }
                        val observedAt = System.nanoTime()
                        if (snapshotParity && snapshotReadyNanos == null) snapshotReadyNanos = observedAt
                        if (viewParity && viewReadyNanos == null) viewReadyNanos = observedAt
                        observations.put(JSONObject().put("timestampNanos", observedAt).put("snapshotParity", snapshotParity)
                            .put("viewParity", viewParity).put("viewAvailable", viewAvailable)
                            .put("snapshotRevision", snapshot?.revision ?: JSONObject.NULL)
                            .put("durableRevision", component.effectiveReadRevision.value))
                        if (!snapshotParity) {
                            staleObservations++
                            if (System.nanoTime() < deadline) { SystemClock.sleep(50); drain() }
                        }
                    } while (!snapshotParity && System.nanoTime() < deadline)
                    val result = JSONObject().put("operation", op).put("scenario", phase).put("sample", sample)
                        .put("startNanos", start).put("drainNanos", drained).put("durationToDrainNanos", drained - start)
                        .put("settleWaitNanos", System.nanoTime() - settleStart).put("staleObservations", staleObservations)
                        .put("snapshotReadyNanos", snapshotReadyNanos ?: JSONObject.NULL)
                        .put("viewReadyNanos", viewReadyNanos ?: JSONObject.NULL).put("settleObservations", observations)
                        .put("sqliteSnapshotParity", snapshotParity).put("viewParity", viewParity).put("viewAvailable", viewAvailable)
                        .put("viewStatus", if (!viewAvailable) "unavailableUnmatched" else if (viewParity) "matching" else "staleUnmatched")
                    operations.add(result)
                    assertTrue("Published snapshot failed to converge to durable revision/value within 10 seconds", snapshotParity)
                    button.operationTraceFactory = null
                }
                for (sample in 0..1) {
                    activeOperation = ++ordinal; phase = "warmNavigation"
                    val revision = component.effectiveReadRevision.value
                    val rows = component.db.queryLong("SELECT count(*) FROM Repetitions")
                    val start = System.nanoTime()
                    if (enabled) {
                        event("navigation.tasks.request")
                        Trace.beginSection("habit.nav.request.$activeOperation"); Trace.endSection()
                    }
                    activity.onActivity { it.navigate(MainDestination.TASKS) }; drain()
                    if (enabled) event("navigation.habits.request")
                    navigationRevision = revision; navigationDrawOperation = activeOperation
                    activity.onActivity { it.navigate(MainDestination.HABITS) }; drain()
                    val drained = System.nanoTime(); SystemClock.sleep(250); drain()
                    assertEquals(revision, component.effectiveReadRevision.value)
                    assertEquals(rows, component.db.queryLong("SELECT count(*) FROM Repetitions"))
                    operations.add(JSONObject().put("operation", activeOperation).put("scenario", phase).put("sample", sample)
                        .put("startNanos", start).put("drainNanos", drained).put("durationToDrainNanos", drained - start)
                        .put("unchangedRevision", true))
                    navigationDrawOperation = 0L
                }
            }
            assertEquals("ok", component.db.queryString("PRAGMA quick_check"))
            operations.filter { it.has("operation") }.forEach { op ->
                op.put("drawDisposition", if (!enabled) "unobserved" else if (op.getLong("operation") in drawn) "drawn" else "unmatched")
            }
            val out = File(ins.targetContext.getExternalFilesDir(null), "remaining-latency").apply { mkdirs() }
            File(out, "$label-$depth-$enabled.json").writeText(JSONObject().put("pid", Process.myPid())
                .put("experimentId", "PI-20261013-04").put("buildLabel", label).put("historyDays", depth)
                .put("sortConfiguration", sortConfiguration)
                .put("memoryBeginning", memoryBeginning).put("memoryEnd", memorySample())
                .put("memoryDefinition", "Process PSS and runtime heap samples around the whole workload; not per-operation allocation or CPU")
                .put("tracingEnabled", enabled).put("referenceDate", today.toString()).put("schemaVersion", 30)
                .put("fixtureControlledHabits", 10).put("fixtureControlledRepetitions", 10 * depth)
                .put("totalHabits", component.habitList.size()).put("journalMode", component.db.queryString("PRAGMA journal_mode"))
                .put("operations", JSONArray(operations)).put("events", JSONArray(events.toList()))
                .put("viewportDiagnostics", viewportDiagnostics)
                .put("controlledReaderFailureCount", controlledFailures.size)
                .put("controlledReaderFailures", JSONArray(controlledFailures.toList()))
                .put("frames", JSONArray(frames.toList())).put("observerCallbackNanos", JSONArray(costs.toList()))
                .put("coldDefinition", "Fresh projection and invalidated entries; process/JIT/SQLite/page-cache warm")
                .put("presentation", "Draw callbacks are candidates only; actual presentation and all unmatched draws must be joined from FrameTimeline externally")
                .toString(2))
        } catch (failure: Throwable) {
            val out = File(ins.targetContext.getExternalFilesDir(null), "remaining-latency").apply { mkdirs() }
            File(out, "$label-$depth-$enabled-failure.json").writeText(JSONObject()
                .put("pid", Process.myPid()).put("buildLabel", label).put("historyDays", depth).put("tracingEnabled", enabled)
                .put("sortConfiguration", sortConfiguration)
                .put("memoryBeginning", memoryBeginning).put("memoryEnd", memorySample())
                .put("failure", failure.toString()).put("phase", phase).put("operations", JSONArray(operations))
                .put("viewportDiagnostics", viewportDiagnostics)
                .put("controlledReaderFailureCount", controlledFailures.size)
                .put("controlledReaderFailures", JSONArray(controlledFailures.toList()))
                .put("events", JSONArray(events.toList())).put("frames", JSONArray(frames.toList())).toString(2))
            throw failure
        } finally {
            targets.forEach { it.operationTraceFactory = null }
            HabitFrameTiming.observer = null
            HabitStartupTiming.observer = null
            component.habitCardListCache.operationTraces = null
            component.habitCardListCache.effectiveSnapshotsTraced = null
            controller.lockMetricsObserver = null
            controller.requestMetricsObserver = null
            controller.snapshotMetricsObserver = null
            ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        }
    }
}
