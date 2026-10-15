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
import org.isoron.platform.time.getToday
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
import org.isoron.uhabits.core.io.HabitResortDiagnostics
import org.isoron.uhabits.database.AndroidSqlTiming
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
class PostNavigationPresentationAttributionTest {
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

    @Test fun attributePostNavigationPresentation() {
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
        val sampleCount = args.getString("sampleCount", "6").toInt()
        check(sampleCount in 2..8)
        val mixedCycleCount = args.getString("mixedCycleCount", "10").toInt()
        check(mixedCycleCount in 1..10)
        val statisticsOnlyCycles = args.getString("statisticsOnlyCycles", "false").toBooleanStrict()
        val workload = args.getString("workload", "full")
        check(workload in listOf("full", "warmOnly"))
        val resortDiagnosticsEnabled = args.getString("resortDiagnosticsEnabled", "true").toBooleanStrict()
        val rangeProbeCycles = args.getString("rangeProbeCycles", "3").toInt()
        check(rangeProbeCycles in 0..3)
        val reverseOrder = args.getString("reverseOrder", "false").toBooleanStrict()
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
        val journalMode = component.db.queryString("PRAGMA journal_mode")
        assertEquals("wal", journalMode.lowercase())
        val openerField = org.isoron.uhabits.utils.DatabaseUtils::class.java.getDeclaredField("opener").apply { isAccessible = true }
        val openHelper = requireNotNull(openerField.get(org.isoron.uhabits.utils.DatabaseUtils) as? org.isoron.uhabits.HabitsDatabaseOpener)
        check(openHelper.isWalRequested)
        val walProvenance = JSONObject().put("openHelperWalRequested", openHelper.isWalRequested)
            .put("productionWalEnabled", org.isoron.uhabits.utils.DatabaseUtils.enableWalForNext)
            .put("defaultWalRequested", org.isoron.uhabits.HabitsDatabaseOpener.defaultWalEnabled(component.session.databaseFilename, 30))
            .put("fixtureJournalOverride", false)
        val today = LocalDate(2026, 10, 11)
        setToday(today)
        fun drain() { repeat(5) { runBlocking { component.taskRunner.await() }; ins.waitForIdleSync() } }
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
        val costs = Collections.synchronizedList(mutableListOf<Long>())
        val operations = mutableListOf<JSONObject>()
        val navigationOperations = mutableListOf<JSONObject>()
        val viewportDiagnostics = JSONArray()
        val rangeSwitchProbe = JSONArray()
        val syntheticCurrentGoalAdjustments = JSONArray()
        val controlledFailures = Collections.synchronizedList(mutableListOf<JSONObject>())
        val traces = Collections.synchronizedList(mutableListOf<HabitEditTrace>())
        val targets = mutableListOf<CheckmarkButtonView>()
        val expected = Collections.synchronizedMap(mutableMapOf<Long, Int>())
        val drawn = Collections.synchronizedSet(mutableSetOf<Long>())
        val navigationDrawn = Collections.synchronizedSet(mutableSetOf<Long>())
        val publications = Collections.synchronizedMap(mutableMapOf<Long, Triple<Long?, Long?, Long?>>())
        val operationPhases = Collections.synchronizedMap(mutableMapOf<Long, String>())
        val retiredOperations = Collections.synchronizedSet(mutableSetOf<Long>())
        var originalCacheListener: org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache.Listener? = null
        fun memorySample(): JSONObject {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            return JSONObject().put("timestampNanos", System.nanoTime()).put("totalPssKb", info.totalPss)
                .put("runtimeUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("runtimeAllocatedBytes", runtime.totalMemory())
        }
        fun clocks(): JSONObject = JSONObject().put("nanoTime", System.nanoTime())
            .put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos()).put("uptimeMillis", SystemClock.uptimeMillis())
        val clockBeginning = clocks()
        var fixtureFingerprint = "uninitialized"
        val memoryBeginning = memorySample()
        var phase = "fixture"
        var ordinal = 0L
        var activeOperation = 0L
        var navigationDrawOperation = 0L
        var navigationRevision = -1L
        var navigationExpectedValue = Entry.UNKNOWN
        var navigationOrdinal = 0L
        fun event(stage: String, fields: JSONObject = JSONObject()) {
            val start = System.nanoTime()
            if (!fields.has("operation")) fields.put("operation", activeOperation)
            fields.put("stage", stage).put("timestampNanos", fields.optLong("timestampNanos", start)).put("phase", phase)
                .put("thread", Thread.currentThread().name)
                .put("mainThread", Looper.myLooper() == Looper.getMainLooper())
            events.add(fields)
            costs.add(System.nanoTime() - start)
        }
        try {
            val fixtureListLoader = component.habitCardListCache.effectiveSnapshots
            component.habitCardListCache.effectiveSnapshots = null
            // Fixture SQL must finish and models must be hydrated before an
            // implicit WAL reader can cache the pre-commit, empty history.
            drain(); SystemClock.sleep(250); drain()
            val block = component.habitList.getByPosition(0).blockId
            val habits = (0 until 10).map { index ->
                component.sqlModelFactory.buildHabit().apply {
                    name = "Post navigation attribution fixture $index"; type = HabitType.YES_NO
                    frequency = Frequency.DAILY; blockId = block
                }.also { component.habitList.add(it) }
            }
            val facade = component.organizationFacade
            check(facade.mode == org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode.CONTAINER_LOCAL)
            val roots = facade.rootContainers()
            check(roots.size >= 2)
            val firstRoot = org.isoron.uhabits.core.containers.ContainerId(roots[0].key)
            val secondRoot = org.isoron.uhabits.core.containers.ContainerId(roots[1].key)
            // Retain all surrounding data, but keep only four controlled rows in the
            // first canonical root so both committed status positions fit on screen.
            component.habitList.toList().filter { it !in habits && facade.getRootGroupKey(it.uuid!!, it.blockId) == firstRoot.value }
                .forEach { facade.placeHabit(it.uuid!!, secondRoot).getOrThrow() }
            habits.forEachIndexed { index, habit ->
                facade.placeNewHabit(habit.uuid!!, if (index < 4) firstRoot else secondRoot).getOrThrow()
            }
            check(facade.getHabitUuidsForContainer(firstRoot.value) == habits.take(4).map { it.uuid!! }.toSet())
            check(component.habitList.size() == 42)
            component.db.beginTransaction()
            try {
                val stmt = component.db.prepareStatement("INSERT OR REPLACE INTO Repetitions (habit,timestamp,value) VALUES (?,?,?)")
                habits.forEachIndexed { index, habit ->
                    for (d in 0 until depth) {
                        stmt.bindLong(1, habit.id!!); stmt.bindLong(2, today.minus(d).unixTime)
                        stmt.bindLong(3, (if (d == 0) { if (index < 4) Entry.NO else Entry.SKIP }
                            else if (d % 7 == 0) Entry.NO else Entry.YES_MANUAL).toLong())
                        stmt.step(); stmt.reset()
                    }
                    habit.originalEntries.invalidate(); habit.computedEntries.invalidate()
                }
                component.db.setTransactionSuccessful()
            } finally { component.db.endTransaction() }
            // Direct fixture SQL bypasses the command's model rebuild. Hydrate the
            // historical computed entries/scores/streaks before measuring app reads.
            habits.forEachIndexed { index, habit ->
                // A WAL reader can have populated the old model while the seed
                // transaction was still open. Invalidate after the actual commit.
                habit.originalEntries.invalidate()
                habit.recompute()
                assertEquals("SQLite seed rows for ${habit.id}", depth.toLong(),
                    component.db.queryLong("SELECT count(*) FROM Repetitions WHERE habit=${habit.id}"))
                assertEquals("Loaded original rows for ${habit.id}", depth, habit.originalEntries.getKnown().size)
                assertEquals(if (index < 4) Entry.NO else Entry.SKIP, habit.computedEntries.get(today).value)
                assertEquals(Entry.YES_MANUAL, habit.computedEntries.get(today.minus(1)).value)
                assertTrue(habit.scores[today].value > 0.0)
                assertTrue(habit.streaks.getBest(1).isNotEmpty())
            }
            component.habitList.toList().filter { it !in habits }.forEach { habit ->
                val goal = habit.goalAt(today)
                val value = if (!habit.isNumerical) Entry.SKIP else
                    if (goal.targetType == org.isoron.uhabits.core.models.NumericalHabitType.AT_MOST) 0
                    else kotlin.math.ceil(goal.targetValue * 1000.0).toInt()
                habit.originalEntries.add(Entry(today, value))
                habit.recompute()
                if (!habit.isCompletedToday() && habit.isNumerical &&
                    goal.targetType == org.isoron.uhabits.core.models.NumericalHabitType.AT_MOST) {
                    val actual = requireNotNull(habit.periodActualOn(today))
                    val adjustedTarget = maxOf(goal.targetValue, actual)
                    habit.goalHistory.add(goal.copy(effectiveDate = today, targetValue = adjustedTarget))
                    habit.targetValue = adjustedTarget
                    component.habitList.update(habit)
                    habit.recompute()
                    syntheticCurrentGoalAdjustments.put(JSONObject().put("habitName", habit.name)
                        .put("effectiveDate", today.toString()).put("previousTarget", goal.targetValue)
                        .put("newTarget", adjustedTarget).put("periodActual", actual)
                        .put("frequencyNumerator", goal.frequency.numerator).put("frequencyDenominator", goal.frequency.denominator)
                        .put("rationale", "Retain past monthly history while keeping interaction viewport"))
                }
                assertTrue("Background habit must be complete: ${habit.name}", habit.isCompletedToday())
            }
            component.habitList.reorder(habits.first(), component.habitList.getByPosition(0))
            // Keep an unchanged incomplete row before the moving target. RecyclerView
            // preserves its visible anchor when another row is inserted above it;
            // moving the target back to position zero would otherwise scroll it offscreen.
            component.habitList.reorder(habits[1], component.habitList.getByPosition(0))
            val logicalFixture = (0 until component.habitList.size()).joinToString("\n") { index ->
                val habit = component.habitList.getByPosition(index)
                val entries = JSONArray()
                val statement = component.db.prepareStatement("SELECT timestamp,value FROM Repetitions WHERE habit=? ORDER BY timestamp,value")
                try {
                    statement.bindLong(1, habit.id!!)
                    while (statement.step() == org.isoron.platform.io.StepResult.ROW) {
                        entries.put(JSONArray().put(statement.getLong(0)).put(statement.getLong(1)))
                    }
                } finally { statement.finalize() }
                JSONArray().put(index).put(habit.name).put(habit.type)
                    .put(habit.frequency.numerator).put(habit.frequency.denominator)
                    .put(habit.targetType.name).put(habit.targetValue).put(habit.goalHistory.toString())
                    .put(facade.getRootGroupKey(habit.uuid!!, habit.blockId)).put(entries).toString()
            }
            fixtureFingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(logicalFixture.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            component.preferences.isFirstRun = false
            component.preferences.isShortToggleEnabled = true
            component.preferences.areHabitGroupSeparatorsEnabled = true
            component.preferences.isDayTiersEnabled = true
            component.preferences.isHabitSpheresEnabled = true
            component.preferences.defaultPrimaryOrder = if (sortConfiguration == "entrySensitive") HabitList.Order.BY_STATUS_ASC else HabitList.Order.BY_SPHERE
            component.preferences.defaultSecondaryOrder = HabitList.Order.BY_POSITION
            drain()
            component.habitCardListCache.effectiveSnapshots = fixtureListLoader
            if (enabled) {
                if (resortDiagnosticsEnabled) {
                    HabitResortDiagnostics.wallClockNanos = System::nanoTime
                    HabitResortDiagnostics.threadCpuClockNanos = Debug::threadCpuTimeNanos
                    HabitResortDiagnostics.subjectIdentity = { subject ->
                        "${subject.javaClass.name}@${System.identityHashCode(subject)}"
                    }
                    HabitResortDiagnostics.spanBoundary = { stage, subject, begin ->
                        if (begin) Trace.beginSection(
                            "habit.resort.${HabitResortDiagnostics.currentOperation}.$stage.${subject?.substringAfterLast('.')?.takeLast(60) ?: "_"}".take(127))
                        else Trace.endSection()
                    }
                    HabitResortDiagnostics.observer = { report ->
                        event("resort.detail.report", JSONObject().put("operation", report.operation ?: JSONObject.NULL)
                            .put("counters", JSONObject(report.counters))
                            .put("threadId", Process.myTid())
                            .put("durationDefinition", "Nested inclusive wall/thread CPU spans; do not sum overlapping spans"))
                        report.spans.forEach { span ->
                            event("resort.detail.${span.stage}", JSONObject().put("operation", report.operation ?: JSONObject.NULL)
                                .put("subject", span.subject ?: JSONObject.NULL).put("depth", span.depth)
                                .put("wallNanos", span.wallNanos).put("threadCpuNanos", span.threadCpuNanos ?: JSONObject.NULL)
                                .put("threadId", Process.myTid()))
                        }
                    }
                    AndroidSqlTiming.observer = { stage, durationMs ->
                        // The core collector is thread-local: ambient UI/background SQL is excluded.
                        if (HabitResortDiagnostics.isCollecting) {
                            HabitResortDiagnostics.count("sql.$stage.count")
                            HabitResortDiagnostics.count("sql.$stage.wallNanos", (durationMs * 1_000_000).toLong())
                        }
                    }
                }
                HabitStartupTiming.observer = { stage, generation -> event(stage,
                    JSONObject().put("generation", generation ?: JSONObject.NULL)) }
                controller.requestMetricsObserver = { m -> event("controller.request", JSONObject()
                    .put("totalMs", m.totalMs).put("monitorWaitMs", m.monitorWaitMs).put("monitorHeldMs", m.monitorHeldMs)
                    .put("latchMs", m.latchMs).put("latchCount", m.latchCount).put("prepareMs", m.prepareMs)
                    .put("prepareCount", m.prepareCount).put("retries", m.retries)
                    .put("retagMs", m.retagMs).put("retagSnapshots", m.retagSnapshots).put("retagDays", m.retagDays)
                    .put("historyLookupMs", m.historyLookupMs)
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
                    traces.filter { t -> t.ordinal == activeOperation && t.durableRevision != null && (id == null || id == habits.first().id) }
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
                                if (it.ordinal == activeOperation && operationPhases[it.ordinal] == phase && lineage?.first == snapshot.revision)
                                    it.event("glyph.bind", snapshot.revision, lineage?.second, lineage?.third)
                            }
                        }
                    }
                    if (stage == "habit.checkmark.draw" && view is CheckmarkButtonView && card(view)?.habit?.id == habits.first().id &&
                        view === card(view)?.checkmarkPanel?.buttons?.firstOrNull() && card(view)?.dataOffset == 0) {
                        val glyphRect = android.graphics.Rect()
                        val glyphVisible = view.isShown && view.getGlobalVisibleRect(glyphRect) &&
                            glyphRect.width() > 0 && glyphRect.height() > 0
                        val snapshot = component.habitCardListCache.getEffectiveSnapshot(habits.first().id!!)
                        val row = component.habitCardListCache.getPublishedOperationTraces(habits.first().id!!)
                        val latest = synchronized(traces) { traces.lastOrNull { it.durableRevision != null } }
                        val lineage = latest?.let { publications[it.ordinal] }
                        if (navigationDrawOperation > 0 && glyphVisible && snapshot != null &&
                            snapshot.revision == navigationRevision && snapshot.revision == component.effectiveReadRevision.value &&
                            snapshot.day(today).manualEntry.value == navigationExpectedValue && view.value == navigationExpectedValue &&
                            navigationDrawn.add(navigationDrawOperation)) {
                            event("navigation.habits.draw", JSONObject().put("operation", 0L).put("navigationOperation", navigationDrawOperation)
                                .put("revision", snapshot.revision).put("manualValue", navigationExpectedValue))
                            Trace.beginSection("habit.nav.habitsDraw.$navigationDrawOperation"); Trace.endSection()
                        }
                        if (glyphVisible && snapshot != null && latest != null && latest.ordinal == activeOperation &&
                            operationPhases[latest.ordinal] == phase && latest in row &&
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
            // Operation-independent controlled range alternation, before any Activity exists.
            // Provider/lock callbacks retain this phase and operation=0; none qualify edit latency.
            val probeHabits = (0 until component.habitList.size()).map(component.habitList::getByPosition)
            val liveListLoader = component.habitCardListCache.effectiveSnapshots
            // This preparation probe owns explicit ranges. Drain the revision's
            // implicit default-range refresh without allowing it to race this probe.
            // The normal app loader is restored before any Activity/UI workload.
            component.habitCardListCache.effectiveSnapshots = null
            component.effectiveReadRevision.changed()
            drain(); SystemClock.sleep(250); drain()
            try { repeat(rangeProbeCycles) { cycle ->
                listOf("listRange" to today.minus(365), "todayRange" to today, "listRangeReturn" to today.minus(365)).forEach { (range, from) ->
                    phase = "rangeSwitch_${cycle}_$range"
                    val revision = component.effectiveReadRevision.value
                    val start = System.nanoTime()
                    val snapshots = controller.snapshots(probeHabits, from, today)
                    rangeSwitchProbe.put(JSONObject().put("cycle", cycle).put("range", range)
                        .put("phase", phase).put("durationNanos", System.nanoTime() - start)
                        .put("from", from.toString()).put("to", today.toString()).put("revision", revision)
                        .put("habitCount", snapshots.size).put("allRevisionQualified", snapshots.values.all { it.revision == revision }))
                    check(snapshots.size == probeHabits.size && snapshots.values.all { it.revision == revision })
                }
            } } finally { component.habitCardListCache.effectiveSnapshots = liveListLoader }
            phase = "coldLaunch"
            // Cold labels concern fresh controller projection and invalidated entry caches;
            // APK process, SQLite connection, JIT and OS page cache are already warm.
            component.effectiveReadRevision.changed()
            habits.forEach { it.originalEntries.invalidate(); it.computedEntries.invalidate() }
            QaEmulatorUi.clearSystemUiError()
            val launchStart = System.nanoTime()
            ActivityScenario.launch<MainActivity>(Intent(ins.targetContext, MainActivity::class.java)).use { activity ->
                drain(); SystemClock.sleep(250); drain()
                val listenerField = component.habitCardListCache.javaClass.getDeclaredField("listener").apply { isAccessible = true }
                val delegate = listenerField.get(component.habitCardListCache) as org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache.Listener
                originalCacheListener = delegate
                component.habitCardListCache.setListener(object : org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache.Listener by delegate {
                    override fun onItemMoved(oldPosition: Int, newPosition: Int) {
                        val started = System.nanoTime()
                        delegate.onItemMoved(oldPosition, newPosition)
                        event("cache.listener.move", JSONObject().put("oldPosition", oldPosition).put("newPosition", newPosition)
                            .put("callbackDurationNanos", System.nanoTime() - started)
                            .put("targetPositionAfterMove", (0 until component.habitCardListCache.habitCount).firstOrNull {
                                component.habitCardListCache.getHabitByPosition(it)?.id == habits.first().id } ?: -1))
                    }
                })
                operations.add(JSONObject().put("scenario", "coldLaunch").put("durationToDrainNanos", System.nanoTime() - launchStart))
                fun target(): CheckmarkButtonView {
                    var button: CheckmarkButtonView? = null
                    var viewportReady = false
                    var needsRebind = false
                    activity.onActivity { a ->
                        descendants(a.window.decorView).filterIsInstance<HabitCardListView>().first().apply {
                            dataOffset = 0
                            // Keep the whole 0..3 movement corridor visible. Anchoring the
                            // current target at the top hides it when status sorting moves
                            // it back to zero on the third (SKIP -> NO) genuine toggle.
                            (layoutManager as androidx.recyclerview.widget.LinearLayoutManager)
                                .scrollToPositionWithOffset(0, 0)
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
                fun input(button: View, requireFixtureCalendar: Boolean = false) {
                    val systemCheck = System.nanoTime()
                    QaEmulatorUi.clearSystemUiError()
                    event("harness.systemCheck.end", JSONObject().put("durationNanos", System.nanoTime() - systemCheck))
                    val xy = IntArray(2)
                    activity.onActivity { a ->
                        check(a.hasWindowFocus())
                        val rect = android.graphics.Rect()
                        check(button.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0)
                        xy[0] = rect.centerX(); xy[1] = rect.centerY()
                    }
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        if (requireFixtureCalendar) {
                            val actualToday = getToday()
                            event("fixtureCalendar.input", JSONObject().put("fixtureDay", today.toString())
                                .put("actualDay", actualToday.toString()).put("motionAction", action))
                            check(actualToday == today) { "Fixture calendar drift immediately before input: $actualToday != $today" }
                        }
                        val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, xy[0].toFloat(), xy[1].toFloat(), 0)
                        try { ins.sendPointerSync(motion) } finally { motion.recycle() }
                    }
                }
                fun listeners(owner: Any): Int = synchronized(owner) {
                    val field = owner.javaClass.getDeclaredField("listeners").apply { isAccessible = true }
                    (field.get(owner) as Collection<*>).size
                }
                fun state(label: String) {
                    activity.onActivity { a ->
                        a.supportFragmentManager.fragments.filterIsInstance<org.isoron.uhabits.activities.statistics.StatisticsFragment>().firstOrNull()?.let { statistics ->
                            fun field(name: String): Any? = statistics.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(statistics)
                            val key = field("lastRenderedReportKey")
                            val revision = key?.javaClass?.getDeclaredField("effectiveRevision")?.apply { isAccessible = true }?.get(key)
                            event("statistics.lifecycle", JSONObject().put("label", label)
                                .put("hidden", statistics.isHidden).put("resumed", statistics.isResumed)
                                .put("activeReportGeneration", field("activeReportGeneration"))
                                .put("lastRenderedEffectiveRevision", revision ?: JSONObject.NULL)
                                .put("currentEffectiveRevision", component.effectiveReadRevision.value)
                                .put("pendingReportRunnable", field("pendingReportRunnable") != null))
                        }
                    }
                    event("lifecycle.state", memorySample().put("label", label)
                        .put("revisionListenerCount", listeners(component.effectiveReadRevision))
                        .put("observableListenerCount", listeners(component.habitList.observable))
                        .put("commandListenerCount", listeners(component.commandRunner))
                        .put("activeTaskCount", component.taskRunner.activeTaskCount))
                }
                var currentUiDestination = MainDestination.HABITS
                fun navigate(destination: MainDestination) {
                    if (currentUiDestination == MainDestination.HABITS && destination != MainDestination.HABITS) {
                        // Prepare the native viewport before leaving; never repair it after return input.
                        val anchor = target()
                        check(anchor.isShown)
                    }
                    val navigationOp = ++navigationOrdinal
                    val navigationResult = JSONObject().put("navigationOperation", navigationOp).put("destination", destination.name)
                        .put("scenario", phase).put("target", habits.first().name).put("targetHabitId", habits.first().id)
                    navigationOperations.add(navigationResult)
                    val id = when (destination) {
                        MainDestination.HABITS -> org.isoron.uhabits.R.id.btnNavHabits
                        MainDestination.TASKS -> org.isoron.uhabits.R.id.btnNavTasks
                        MainDestination.STATISTICS -> org.isoron.uhabits.R.id.btnNavReports
                        else -> error("Unsupported measured destination $destination")
                    }
                    var navigationButton: View? = null
                    val returningToHabits = destination == MainDestination.HABITS
                    navigationExpectedValue = component.db.queryLong("SELECT value FROM Repetitions WHERE habit=${habits.first().id} AND timestamp=${today.unixTime}").toInt()
                    navigationRevision = component.effectiveReadRevision.value
                    activity.onActivity { a ->
                        navigationButton = a.findViewById<View>(id).also { button ->
                            check(button.isShown)
                            if (returningToHabits) button.setOnTouchListener { v, motion ->
                                // Match MainActivity.setupScaleOnPress while the measurement listener is installed.
                                when (motion.action) {
                                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start()
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                                }
                                if (motion.action == MotionEvent.ACTION_UP) {
                                    val acceptedAt = System.nanoTime()
                                    navigationDrawOperation = navigationOp
                                    navigationResult.put("acceptedInputNanos", acceptedAt).put("revision", navigationRevision)
                                        .put("expectedManualValue", navigationExpectedValue)
                                    event("navigation.input.up", JSONObject().put("operation", 0L).put("navigationOperation", navigationOp)
                                        .put("timestampNanos", acceptedAt))
                                    Trace.beginSection("habit.nav.input.$navigationOp"); Trace.endSection()
                                }
                                false
                            }
                        }
                    }
                    val start = System.nanoTime()
                    try { input(requireNotNull(navigationButton)) } finally {
                        if (returningToHabits) activity.onActivity { a ->
                            requireNotNull(navigationButton).setOnTouchListener(null)
                            a.javaClass.getDeclaredMethod("setupScaleOnPress", View::class.java).apply { isAccessible = true }
                                .invoke(a, requireNotNull(navigationButton))
                        }
                    }
                    drain(); SystemClock.sleep(250); drain()
                    navigationResult.put("durationToDrainNanos", System.nanoTime() - start)
                        .put("drainDefinition", "Injection through queue/idle/settle barrier; not presentation")
                        .put("drawDisposition", if (!enabled) "unobserved" else if (navigationOp in navigationDrawn) "drawn" else "unmatched")
                    navigationDrawOperation = 0L
                    currentUiDestination = destination
                    state("navigation_${destination}")
                }
                val scenarios = if (workload == "warmOnly") listOf("warmRepeated")
                    else listOf("warmRepeated", "afterStatistics", "afterStatisticsDateChange", "afterTasks", "afterMixedCycles")
                for (case in if (reverseOrder) scenarios.reversed() else scenarios) {
                    phase = case; activeOperation = 0L
                    when (case) {
                        "afterStatistics" -> { navigate(MainDestination.STATISTICS); navigate(MainDestination.HABITS) }
                        "afterStatisticsDateChange" -> {
                            navigate(MainDestination.STATISTICS)
                            var previousButton: View? = null
                            var previousDate: Any? = null
                            activity.onActivity { a ->
                                val statistics = a.supportFragmentManager.fragments.filterIsInstance<org.isoron.uhabits.activities.statistics.StatisticsFragment>().first()
                                val date = statistics.javaClass.getDeclaredField("currentAnchorDate").apply { isAccessible = true }
                                previousDate = date.get(statistics)
                                previousButton = requireNotNull(statistics.requireView().findViewById<View>(org.isoron.uhabits.R.id.btnPrev))
                            }
                            input(requireNotNull(previousButton))
                            activity.onActivity { a ->
                                val statistics = a.supportFragmentManager.fragments.filterIsInstance<org.isoron.uhabits.activities.statistics.StatisticsFragment>().first()
                                val date = statistics.javaClass.getDeclaredField("currentAnchorDate").apply { isAccessible = true }
                                check(date.get(statistics) != previousDate) { "Statistics date did not change" }
                                event("statistics.date.changed")
                            }
                            drain(); SystemClock.sleep(250); drain(); navigate(MainDestination.HABITS)
                        }
                        "afterTasks" -> { navigate(MainDestination.TASKS); navigate(MainDestination.HABITS) }
                        "afterMixedCycles" -> repeat(mixedCycleCount) {
                            navigate(if (statisticsOnlyCycles || it % 2 == 0) MainDestination.STATISTICS else MainDestination.TASKS)
                            navigate(MainDestination.HABITS)
                        }
                    }
                    state("${case}_before")
                    for (sample in 0 until sampleCount) {
                    val setupStart = System.nanoTime()
                    phase = "viewportSetup"; activeOperation = 0L
                    val previousToday = getToday()
                    if (previousToday != today) {
                        event("calendarDrift", JSONObject().put("previousDay", previousToday.toString())
                            .put("fixtureDay", today.toString()).put("restorationScope", "unmeasured viewport setup"))
                        setToday(today)
                        component.effectiveReadRevision.changed()
                        // Revision refresh is debounced; settle it before retrieving a dated button.
                        drain(); SystemClock.sleep(250); drain()
                    }
                    check(getToday() == today) { "Fixture calendar drift during viewport setup" }
                    val button = target(); targets.add(button)
                    activeOperation = ++ordinal
                    phase = case
                    operationPhases[activeOperation] = case
                    val targetPositionBeforeInput = (0 until component.habitCardListCache.habitCount).first {
                        component.habitCardListCache.getHabitByPosition(it)?.id == habits.first().id }
                    check(targetPositionBeforeInput in 0..3)
                    event("harness.setup.start", JSONObject().put("timestampNanos", setupStart))
                    val before = component.db.queryLong("SELECT value FROM Repetitions WHERE habit=${habits.first().id} AND timestamp=${today.unixTime}")
                    val op = activeOperation
                    var latestStatementReturn = 0L
                    // This single native span remains enabled without detailed probes.
                    // Command trace callbacks bracket resort synchronously on its worker.
                    val resortStageStart = ThreadLocal<Triple<Long, Long, Int>>()
                    if (enabled) button.operationTraceFactory = {
                        HabitEditTrace(op) traceCallback@ { trace, stage, revision, generation, batch ->
                            if (op in retiredOperations) return@traceCallback
                            if (stage == "repetition.resortAndObservers.begin") {
                                Trace.beginSection("habit.resortStage.$op")
                                resortStageStart.set(Triple(System.nanoTime(), Debug.threadCpuTimeNanos(), Process.myTid()))
                            }
                            if (stage == "repetition.resortAndObservers.end") {
                                resortStageStart.get()?.let { started ->
                                    val cpuEnd = Debug.threadCpuTimeNanos()
                                    val wallEnd = System.nanoTime()
                                    Trace.endSection()
                                    resortStageStart.remove()
                                    event("resort.stage.cpu", JSONObject().put("operation", op)
                                        .put("wallNanos", wallEnd - started.first)
                                        .put("cpuNanos", cpuEnd - started.second).put("threadId", started.third))
                                }
                            }
                            if (stage == "input.accepted") {
                                expected[op] = button.value
                                Trace.beginSection("habit.op.accepted.$op"); Trace.endSection()
                            }
                            if (stage == "sqlite.end") latestStatementReturn = System.nanoTime()
                            if (stage == "sqlite.durable") {
                                trace.durableRevision = component.effectiveReadRevision.value
                                check(latestStatementReturn > 0L)
                                event("sqlite.changingAutocommitReturn", JSONObject().put("operation", op)
                                    .put("timestampNanos", latestStatementReturn).put("retrospectivelyQualified", true))
                            }
                            if (stage == "cache.row.published") {
                                publications[op] = Triple(revision, generation, batch)
                                val published = component.habitCardListCache.getEffectiveSnapshot(habits.first().id!!)
                                event("cache.row.parity", JSONObject().put("operation", op).put("revision", published?.revision ?: JSONObject.NULL)
                                    .put("currentRevision", component.effectiveReadRevision.value)
                                    .put("manualValue", published?.day(today)?.manualEntry?.value ?: JSONObject.NULL)
                                    .put("expectedValue", expected[op] ?: JSONObject.NULL)
                                    .put("qualifiedParity", published != null && published.revision == revision &&
                                        published.revision >= (trace.durableRevision ?: Long.MAX_VALUE) && published.day(today).manualEntry.value == expected[op]))
                            }
                            if (stage == "input.down") {
                                Trace.beginSection("habit.op.input.$op"); Trace.endSection()
                            }
                            event(stage, JSONObject().put("operation", op).put("revision", revision ?: trace.durableRevision ?: JSONObject.NULL)
                                .put("generation", generation ?: JSONObject.NULL).put("batch", batch ?: JSONObject.NULL))
                        }.also { traces.add(it) }
                    }
                    val start = System.nanoTime(); event("harness.input.injection.start"); input(button, requireFixtureCalendar = true); drain()
                    val drained = System.nanoTime()
                    SystemClock.sleep(250); drain()
                    val queryToday = getToday()
                    event("fixtureCalendar.afterDrain", JSONObject().put("fixtureDay", today.toString())
                        .put("actualDay", queryToday.toString()))
                    check(queryToday == today) { "Fixture calendar drift during measured operation: $queryToday != $today" }
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
                            val currentButton = row?.checkmarkPanel?.buttons?.firstOrNull()
                            val visibleRect = android.graphics.Rect()
                            viewAvailable = currentButton != null && currentButton.isShown && currentButton.getGlobalVisibleRect(visibleRect) &&
                                visibleRect.width() > 0 && visibleRect.height() > 0
                            viewParity = currentButton?.value?.toLong() == stored
                        }
                        val observedAt = System.nanoTime()
                        if (snapshotParity && snapshotReadyNanos == null) snapshotReadyNanos = observedAt
                        if (viewParity && viewReadyNanos == null) viewReadyNanos = observedAt
                        observations.put(JSONObject().put("timestampNanos", observedAt).put("snapshotParity", snapshotParity)
                            .put("viewParity", viewParity).put("viewAvailable", viewAvailable)
                            .put("snapshotRevision", snapshot?.revision ?: JSONObject.NULL)
                            .put("durableRevision", component.effectiveReadRevision.value))
                        if (!snapshotParity || !viewAvailable || !viewParity) {
                            staleObservations++
                            if (System.nanoTime() < deadline) { SystemClock.sleep(50); drain() }
                        }
                    } while ((!snapshotParity || !viewAvailable || !viewParity) && System.nanoTime() < deadline)
                    val result = JSONObject().put("operation", op).put("scenario", phase).put("sample", sample)
                        .put("targetPositionBeforeInput", targetPositionBeforeInput)
                        .put("targetPositionAfterInput", (0 until component.habitCardListCache.habitCount).firstOrNull {
                            component.habitCardListCache.getHabitByPosition(it)?.id == habits.first().id } ?: -1)
                        .put("setupStartNanos", setupStart).put("startNanos", start).put("drainNanos", drained).put("durationToDrainNanos", drained - start)
                        .put("settleWaitNanos", System.nanoTime() - settleStart).put("staleObservations", staleObservations)
                        .put("snapshotReadyNanos", snapshotReadyNanos ?: JSONObject.NULL)
                        .put("viewReadyNanos", viewReadyNanos ?: JSONObject.NULL).put("settleObservations", observations)
                        .put("sqliteSnapshotParity", snapshotParity).put("viewParity", viewParity).put("viewAvailable", viewAvailable)
                        .put("viewStatus", if (!viewAvailable) "unavailableUnmatched" else if (viewParity) "matching" else "staleUnmatched")
                    operations.add(result)
                    check(result.getInt("targetPositionAfterInput") in 0..3)
                    retiredOperations.add(op)
                    activeOperation = 0L
                    result.put("finishedNanos", System.nanoTime())
                    assertTrue("Published snapshot failed to converge to durable revision/value within 10 seconds", snapshotParity)
                    assertTrue("Controlled target left the visible viewport after committed input", viewAvailable)
                    assertTrue("Controlled target has a stale displayed value", viewParity)
                    button.operationTraceFactory = null
                    }
                    phase = case; activeOperation = 0L; state("${case}_after")
                }
            }
            assertEquals("ok", component.db.queryString("PRAGMA quick_check"))
            operations.filter { it.has("operation") }.forEach { op ->
                op.put("drawDisposition", if (!enabled) "unobserved" else if (op.getLong("operation") in drawn) "drawn" else "unmatched")
            }
            val out = File(ins.targetContext.getExternalFilesDir(null), "remaining-latency").apply { mkdirs() }
            File(out, "$label-$depth-$enabled.json").writeText(JSONObject().put("pid", Process.myPid())
                .put("experimentId", "PI-20261014-12").put("buildLabel", label).put("historyDays", depth)
                .put("sortConfiguration", sortConfiguration).put("primaryOrder", if (sortConfiguration == "entrySensitive") "BY_STATUS_ASC" else "BY_SPHERE")
                .put("secondaryOrder", "BY_POSITION").put("mixedCycleCount", mixedCycleCount)
                .put("statisticsOnlyCycles", statisticsOnlyCycles)
                .put("controlledFirstRootKey", firstRoot.value).put("controlledOtherRootKey", secondRoot.value)
                .put("controlledVisibleRootHabitCount", 4).put("sampleCount", sampleCount).put("reverseOrder", reverseOrder)
                .put("workload", workload).put("resortDiagnosticsEnabled", resortDiagnosticsEnabled).put("rangeProbeCycles", rangeProbeCycles)
                .put("rangeSwitchProbe", rangeSwitchProbe).put("walProvenance", walProvenance)
                .put("syntheticCurrentGoalAdjustmentCount", syntheticCurrentGoalAdjustments.length())
                .put("syntheticCurrentGoalAdjustments", syntheticCurrentGoalAdjustments)
                .put("booleanFixturePolicy", "Synthetic Sunday rest-day SKIP preserves all three toggle sort ranks")
                .put("memoryBeginning", memoryBeginning).put("memoryEnd", memorySample())
                .put("clockBeginning", clockBeginning).put("clockEnd", clocks())
                .put("clockDefinition", "Within-domain monotonic differences only; paired anchors diagnose suspend and clock offsets")
                .put("fixtureLogicalSha256", fixtureFingerprint)
                .put("memoryDefinition", "Process PSS and runtime heap samples around the whole workload; not per-operation allocation or CPU")
                .put("tracingEnabled", enabled).put("referenceDate", today.toString()).put("schemaVersion", 30)
                .put("fixtureControlledHabits", 10).put("fixtureControlledRepetitions", 10 * depth)
                .put("totalHabits", component.habitList.size()).put("journalMode", component.db.queryString("PRAGMA journal_mode"))
                .put("operations", JSONArray(operations)).put("navigationOperations", JSONArray(navigationOperations)).put("events", JSONArray(events.toList()))
                .put("viewportDiagnostics", viewportDiagnostics)
                .put("controlledReaderFailureCount", controlledFailures.size)
                .put("controlledReaderFailures", JSONArray(controlledFailures.toList()))
                .put("frames", JSONArray(frames.toList())).put("observerCallbackNanos", JSONArray(costs.toList()))
                .put("coldDefinition", "Fresh projection and invalidated entries; process/JIT/SQLite/page-cache warm")
                .put("presentation", "Draw callbacks are candidates only; actual presentation and all unmatched draws must be joined from FrameTimeline externally")
                .toString(2))
        } catch (failure: Throwable) {
            event("fixtureCalendar.failure", JSONObject().put("fixtureDay", today.toString())
                .put("actualDay", getToday().toString()))
            val out = File(ins.targetContext.getExternalFilesDir(null), "remaining-latency").apply { mkdirs() }
            File(out, "$label-$depth-$enabled-failure.json").writeText(JSONObject()
                .put("pid", Process.myPid()).put("buildLabel", label).put("historyDays", depth).put("tracingEnabled", enabled)
                .put("sortConfiguration", sortConfiguration).put("primaryOrder", if (sortConfiguration == "entrySensitive") "BY_STATUS_ASC" else "BY_SPHERE")
                .put("secondaryOrder", "BY_POSITION").put("mixedCycleCount", mixedCycleCount)
                .put("controlledVisibleRootHabitCount", 4).put("sampleCount", sampleCount).put("reverseOrder", reverseOrder)
                .put("workload", workload).put("resortDiagnosticsEnabled", resortDiagnosticsEnabled).put("rangeProbeCycles", rangeProbeCycles)
                .put("rangeSwitchProbe", rangeSwitchProbe).put("walProvenance", walProvenance)
                .put("syntheticCurrentGoalAdjustmentCount", syntheticCurrentGoalAdjustments.length())
                .put("syntheticCurrentGoalAdjustments", syntheticCurrentGoalAdjustments)
                .put("booleanFixturePolicy", "Synthetic Sunday rest-day SKIP preserves all three toggle sort ranks")
                .put("memoryBeginning", memoryBeginning).put("memoryEnd", memorySample())
                .put("clockBeginning", clockBeginning).put("clockEnd", clocks())
                .put("clockDefinition", "Within-domain monotonic differences only; paired anchors diagnose suspend and clock offsets")
                .put("fixtureLogicalSha256", fixtureFingerprint)
                .put("failure", failure.toString()).put("phase", phase).put("operations", JSONArray(operations)).put("navigationOperations", JSONArray(navigationOperations))
                .put("viewportDiagnostics", viewportDiagnostics)
                .put("controlledReaderFailureCount", controlledFailures.size)
                .put("controlledReaderFailures", JSONArray(controlledFailures.toList()))
                .put("events", JSONArray(events.toList())).put("frames", JSONArray(frames.toList())).toString(2))
            throw failure
        } finally {
            AndroidSqlTiming.observer = null
            HabitResortDiagnostics.observer = null
            HabitResortDiagnostics.spanBoundary = null
            HabitResortDiagnostics.wallClockNanos = null
            HabitResortDiagnostics.threadCpuClockNanos = null
            HabitResortDiagnostics.subjectIdentity = null
            originalCacheListener?.let(component.habitCardListCache::setListener)
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
