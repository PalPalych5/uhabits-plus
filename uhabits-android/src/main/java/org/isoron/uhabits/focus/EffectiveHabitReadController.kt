/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.projection.*
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.database.AndroidDatabase
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Read capability only: never constructs Focus capture, rules, schema or automatic writers. */
class EffectiveHabitReadController(
    private val context: Context,
    db: AndroidDatabase,
    private val preferences: Preferences,
    val revision: EffectiveReadRevision,
    private val cache: HabitCardListCache,
    private val habits: HabitList,
    private val notificationTray: org.isoron.uhabits.core.ui.NotificationTray? = null,
) : Preferences.Listener {
    internal val provider = EffectiveHabitReadSnapshotProvider(SQLiteContributionRepository(db))
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var disposed = false
    private var calendar = captureCalendar()
    private var restoreModels = false
    private data class CacheContext(
        val from: org.isoron.platform.time.LocalDate,
        val to: org.isoron.platform.time.LocalDate,
        val datasetId: String,
        val revision: Long,
        val calendar: EffectiveReadCalendarContext,
        val rollbackGeneration: Long,
        val compatibilityFirstWeekday: Int,
    )
    @Volatile private var rollbackGeneration = 0L
    // Dataset-owned snapshot cache. Repeated attach/resume refreshes must not
    // rebuild the entire historical projection when its durable inputs are unchanged.
    private var cachedContext: CacheContext? = null
    private val cachedSnapshots = mutableMapOf<HabitRef, EffectiveHabitReadSnapshot>()

    private class InFlightBatch(
        val context: CacheContext,
        val habits: Set<HabitRef>,
        val latch: CountDownLatch = CountDownLatch(1),
    ) {
        @Volatile var results: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
        @Volatile var failure: Throwable? = null
    }

    private val inFlightByHabit = mutableMapOf<HabitRef, InFlightBatch>()
    internal var prepareHook: ((List<Habit>) -> Unit)? = null
    internal var beforePublicationHook: (() -> Unit)? = null
    internal var batchAwaitHook: (() -> Unit)? = null

    private data class SnapshotPlan(
        val context: CacheContext,
        val toCompute: List<Habit>,
        val myBatch: InFlightBatch?,
        val toAwait: Set<InFlightBatch>,
        val cacheHits: Int,
    )

    private val refresh = Runnable {
        if (!disposed) {
            if (restoreModels) { habits.forEach { it.recompute() }; restoreModels = false }
            cache.refreshAllHabits()
        }
    }
    private val changed: () -> Unit = {
        if (!disposed) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 200)
            notificationTray?.onEffectiveRevisionChanged()
        }
    }
    private val rollback: () -> Unit = {
        if (!disposed) {
            synchronized(this) {
                rollbackGeneration++
                cachedContext = null
                cachedSnapshots.clear()
                inFlightByHabit.values.toSet().forEach {
                    it.failure = EffectiveReadInvalidatedException()
                    it.latch.countDown()
                }
                inFlightByHabit.clear()
            }
            habits.forEach { it.originalEntries.invalidate() }
            restoreModels = true
            changed()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { calendarChanged(force = true) }
    }

    init {
        cache.effectiveSnapshots = ::snapshots
        notificationTray?.effectiveSnapshotProvider = { habit ->
            try {
                snapshot(habit)
            } catch (t: Throwable) {
                android.util.Log.e("EffectiveHabitReadController", "Failed to compute snapshot for habit ${habit.id}", t)
                null
            }
        }
        revision.addListener(changed)
        revision.addRollbackListener(rollback)
        preferences.addListener(this)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun captureCalendar() = EffectiveReadCalendarContext(
        TimeZone.getDefault().id, preferences.firstWeekday, preferences.midnightDelayHours, 0,
    )

    val currentCalendar: EffectiveReadCalendarContext get() = captureCalendar()

    fun snapshots(
        habits: List<Habit>,
        from: org.isoron.platform.time.LocalDate,
        to: org.isoron.platform.time.LocalDate,
        traces: List<HabitEditTrace> = emptyList(),
        generation: Long? = null,
        batch: Long? = null,
    ): Map<HabitRef, EffectiveHabitReadSnapshot> {
        val observer = requestMetricsObserver
        if (observer == null) return snapshotsMeasured(habits, from, to, traces, generation, batch, null)
        val metrics = RequestMeasurement()
        var outcome = "failed"
        try {
            val result = snapshotsMeasured(habits, from, to, traces, generation, batch, metrics)
            outcome = "success"
            return result
        } catch (t: Throwable) {
            outcome = t.javaClass.simpleName
            throw t
        } finally {
            val result = RequestMetrics(
                metrics.started.elapsedNow().toDouble(DurationUnit.MILLISECONDS), metrics.monitorWaitMs,
                metrics.monitorHeldMs, metrics.latchMs, metrics.latchCount, metrics.prepareMs,
                metrics.prepareCount, metrics.retries, metrics.publicationMs, metrics.joins,
                metrics.creates, metrics.maxActive, Looper.myLooper() == Looper.getMainLooper(),
                Thread.currentThread().name, outcome, habits.size, generation, batch, metrics.cachePublicationMs,
                metrics.retagMs, metrics.retagSnapshots, metrics.retagDays, metrics.historyLookupMs,
            )
            runCatching { observer(result) }
        }
    }

    private fun snapshotsMeasured(
        habits: List<Habit>,
        from: org.isoron.platform.time.LocalDate,
        to: org.isoron.platform.time.LocalDate,
        traces: List<HabitEditTrace>,
        generation: Long?,
        batch: Long?,
        metrics: RequestMeasurement?,
    ): Map<HabitRef, EffectiveHabitReadSnapshot> {
        var totalWaitMs = 0.0
        var totalHeldMs = 0.0
        var totalPrepared = 0
        var initialCacheHits = 0

        repeat(3) { attempt ->
            // Rollback listeners own revision -> controller. Read history without
            // owning controller, then use it only for the exact context/version
            // inspected here; concurrent replacement conservatively clears instead.
            val historyContext = synchronized(this) { cachedContext }
            val historyRevision = revision.observedValue
            val historyChanges = if (historyContext != null &&
                historyContext.from == from && historyContext.to == to &&
                historyContext.revision < historyRevision
            ) {
                traces.forEach { it.event("snapshot.history.begin", historyRevision, generation, batch) }
                val historyStarted = metrics?.let { TimeSource.Monotonic.markNow() }
                val affected = revision.affectedHabitsBetween(historyContext.revision, historyRevision)
                metrics?.let { it.historyLookupMs += requireNotNull(historyStarted).elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
                traces.forEach { it.event("snapshot.history.end", historyRevision, generation, batch) }
                affected
            } else null
            val waitStart = TimeSource.Monotonic.markNow()
            traces.forEach { it.event("snapshot.lock.wait.begin", generation = generation, batch = batch) }
            val plan = synchronized(this) {
                val waitDuration = waitStart.elapsedNow()
                totalWaitMs += waitDuration.toDouble(DurationUnit.MILLISECONDS)
                metrics?.let { it.monitorWaitMs += waitDuration.toDouble(DurationUnit.MILLISECONDS) }
                traces.forEach { it.event("snapshot.lock.acquired", generation = generation, batch = batch) }
                val heldStart = TimeSource.Monotonic.markNow()
                try {
                    if (disposed) throw EffectiveReadInvalidatedException()

                    val captured = captureCalendar()
                    val capturedRevision = revision.observedValue
                    traces.forEach { it.event("snapshot.begin", capturedRevision, generation, batch) }
                    val capturedRollback = rollbackGeneration
                    val capturedLocaleWeekday = org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale()
                    val currentContext = CacheContext(
                        from, to, revision.datasetId, capturedRevision, captured, capturedRollback, capturedLocaleWeekday
                    )
                    if (cachedContext != currentContext) {
                        val prevContext = cachedContext
                        val canIncrementallyUpdate = prevContext != null &&
                            prevContext.from == currentContext.from &&
                            prevContext.to == currentContext.to &&
                            prevContext.datasetId == currentContext.datasetId &&
                            prevContext.calendar == currentContext.calendar &&
                            prevContext.rollbackGeneration == currentContext.rollbackGeneration &&
                            prevContext.compatibilityFirstWeekday == currentContext.compatibilityFirstWeekday &&
                            prevContext.revision < currentContext.revision

                        val affectedHabits = if (canIncrementallyUpdate &&
                            prevContext === historyContext && currentContext.revision == historyRevision
                        ) {
                            historyChanges
                        } else null

                        if (affectedHabits != null) {
                            for (ref in affectedHabits) {
                                cachedSnapshots.remove(ref)
                            }
                            val survivingKeys = cachedSnapshots.keys.toList()
                            val retagStarted = metrics?.let { TimeSource.Monotonic.markNow() }
                            traces.forEach { it.event("snapshot.retag.begin", currentContext.revision, generation, batch) }
                            for (ref in survivingKeys) {
                                val s = cachedSnapshots[ref]
                                if (s != null && s.revision != currentContext.revision) {
                                    metrics?.let { it.retagSnapshots++; it.retagDays += s.days.size }
                                    cachedSnapshots[ref] = s.withRevision(currentContext.revision)
                                }
                            }
                            metrics?.let { it.retagMs += requireNotNull(retagStarted).elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
                            traces.forEach { it.event("snapshot.retag.end", currentContext.revision, generation, batch) }
                        } else {
                            cachedSnapshots.clear()
                        }
                        cachedContext = currentContext
                        inFlightByHabit.values.toSet().forEach {
                            it.failure = EffectiveReadInvalidatedException()
                            it.latch.countDown()
                        }
                        inFlightByHabit.clear()
                    }
                    val missingHabits = habits.filter { habit ->
                        val ref = habit.uuid?.let { HabitRef(it) } ?: return@filter false
                        !cachedSnapshots.containsKey(ref)
                    }
                    if (missingHabits.isEmpty()) {
                        val result = HashMap<HabitRef, EffectiveHabitReadSnapshot>(habits.size)
                        for (habit in habits) {
                            val ref = habit.uuid?.let { HabitRef(it) } ?: continue
                            cachedSnapshots[ref]?.let { result[ref] = it }
                        }
                        initialCacheHits = habits.size
                        metrics?.let { it.publicationMs = it.started.elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
                        traces.forEach { it.event("snapshot.end", capturedRevision, generation, batch) }
                        val heldDuration = heldStart.elapsedNow()
                        totalHeldMs += heldDuration.toDouble(DurationUnit.MILLISECONDS)
                        lockMetricsObserver?.invoke(ControllerLockMetrics(
                            lockWaitDurationMs = totalWaitMs,
                            lockHeldDurationMs = totalHeldMs,
                            habitCount = habits.size,
                            cacheHitCount = initialCacheHits,
                            preparedCount = totalPrepared,
                            retriesCount = attempt,
                        ))
                        return result
                    }

                    val toAwait = mutableSetOf<InFlightBatch>()
                    val toCompute = mutableListOf<Habit>()
                    for (habit in missingHabits) {
                        val ref = habit.uuid?.let { HabitRef(it) } ?: continue
                        val inFlight = inFlightByHabit[ref]
                        if (inFlight != null && inFlight.context == currentContext) {
                            toAwait.add(inFlight)
                            metrics?.let { it.joins++ }
                        } else {
                            toCompute.add(habit)
                        }
                    }

                    var myBatch: InFlightBatch? = null
                    if (toCompute.isNotEmpty()) {
                        val refs = toCompute.mapNotNull { it.uuid?.let { u -> HabitRef(u) } }.toSet()
                        myBatch = InFlightBatch(currentContext, refs)
                        metrics?.let { it.creates++ }
                        refs.forEach { inFlightByHabit[it] = myBatch }
                    }

                    SnapshotPlan(
                        context = currentContext,
                        toCompute = toCompute,
                        myBatch = myBatch,
                        toAwait = toAwait,
                        cacheHits = habits.size - missingHabits.size
                    )
                } finally {
                    totalHeldMs += heldStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                    metrics?.let { it.monitorHeldMs += heldStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
                }
            }

            if (attempt == 0) {
                initialCacheHits = plan.cacheHits
            }

            // Compute outside controller monitor
            var preparedMap: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
            var computeError: Throwable? = null
            if (plan.myBatch != null) {
                val preparationStart = metrics?.let { TimeSource.Monotonic.markNow() }
                metrics?.let {
                    it.prepareCount++
                    it.maxActive = maxOf(it.maxActive, activeMeasuredPreparations.incrementAndGet())
                }
                try {
                    prepareHook?.invoke(plan.toCompute)
                    preparedMap = provider.prepare(
                        plan.toCompute, from, to, plan.context.datasetId, plan.context.revision, plan.context.calendar
                    )
                } catch (t: Throwable) {
                    computeError = t
                } finally {
                    metrics?.let {
                        it.prepareMs += requireNotNull(preparationStart).elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                        activeMeasuredPreparations.decrementAndGet()
                    }
                }

                // Publish this batch's owned subset before awaiting dependencies. A waiter
                // must never observe successful completion while its cache entries are absent.
                try {
                    beforePublicationHook?.invoke()
                } catch (t: Throwable) {
                    if (computeError == null) computeError = t
                }
                val completionWaitStart = TimeSource.Monotonic.markNow()
                synchronized(this) {
                    val completionHeldStart = TimeSource.Monotonic.markNow()
                    val waitMs = completionWaitStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                    totalWaitMs += waitMs
                    metrics?.let { it.monitorWaitMs += waitMs }
                    try {
                        // Rollback/context replacement/disposal may already have completed
                        // this batch with invalidation. Its terminal outcome is immutable.
                        if (plan.myBatch.latch.count > 0L) {
                            if (computeError != null) {
                                plan.myBatch.failure = computeError
                            } else if (isContextCurrent(plan.context)) {
                                val ready = requireNotNull(preparedMap)
                                cachedSnapshots.putAll(ready)
                                totalPrepared += ready.size
                                plan.myBatch.results = ready
                            } else {
                                plan.myBatch.failure = EffectiveReadInvalidatedException()
                            }
                        }
                    } catch (t: Throwable) {
                        computeError = t
                        plan.myBatch.failure = t
                    } finally {
                        plan.myBatch.habits.forEach { ref ->
                            if (inFlightByHabit[ref] === plan.myBatch) inFlightByHabit.remove(ref)
                        }
                        // Cache publication/terminal failure precedes notification under
                        // the same short monitor; provider and latch waits stay outside it.
                        plan.myBatch.latch.countDown()
                        val heldMs = completionHeldStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                        totalHeldMs += heldMs
                        metrics?.let {
                            it.monitorHeldMs += heldMs
                            it.cachePublicationMs += heldMs
                        }
                    }
                }
            }

            // Await any concurrent in-flight batches outside controller monitor
            var awaitError: Throwable? = null
            for (b in plan.toAwait) {
                batchAwaitHook?.invoke()
                val latchStart = metrics?.let { TimeSource.Monotonic.markNow() }
                val completed = try {
                    b.latch.await(10, TimeUnit.SECONDS)
                } finally {
                    metrics?.let {
                        it.latchCount++
                        it.latchMs += requireNotNull(latchStart).elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                    }
                }
                if (!completed) {
                    awaitError = EffectiveReadInvalidatedException()
                } else if (b.failure != null) {
                    awaitError = b.failure
                }
            }

            if (computeError != null) {
                android.util.Log.e("EffectiveHabitReadController", "Snapshot prepare failed for ${plan.toCompute.size} habits", computeError)
                throw computeError
            }
            if (awaitError != null && awaitError !is EffectiveReadInvalidatedException) {
                throw awaitError
            }

            // Re-acquire monitor briefly to publish results and validate freshness
            val publishWaitStart = TimeSource.Monotonic.markNow()
            val publishedResult = synchronized(this) {
                totalWaitMs += publishWaitStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                metrics?.let { it.monitorWaitMs += publishWaitStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
                val publishHeldStart = TimeSource.Monotonic.markNow()
                try {
                    if (isContextCurrent(plan.context) && computeError == null && awaitError == null) {
                        val allReady = habits.all { h ->
                            val ref = h.uuid?.let { HabitRef(it) } ?: return@all true
                            cachedSnapshots.containsKey(ref)
                        }
                        if (allReady) {
                            metrics?.let { it.publicationMs = it.started.elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
                            val result = HashMap<HabitRef, EffectiveHabitReadSnapshot>(habits.size)
                            for (habit in habits) {
                                val ref = habit.uuid?.let { HabitRef(it) } ?: continue
                                cachedSnapshots[ref]?.let { result[ref] = it }
                            }
                            traces.forEach { it.event("snapshot.end", plan.context.revision, generation, batch) }
                            result
                        } else null
                    } else null
                } finally {
                    totalHeldMs += publishHeldStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                    metrics?.let {
                        val heldMs = publishHeldStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                        it.monitorHeldMs += heldMs
                        it.cachePublicationMs += heldMs
                    }
                }
            }

            if (publishedResult != null) {
                lockMetricsObserver?.invoke(ControllerLockMetrics(
                    lockWaitDurationMs = totalWaitMs,
                    lockHeldDurationMs = totalHeldMs,
                    habitCount = habits.size,
                    cacheHitCount = initialCacheHits,
                    preparedCount = totalPrepared,
                    retriesCount = attempt,
                ))
                return publishedResult
            }

            traces.forEach { it.event("snapshot.retry", plan.context.revision, generation, batch) }
            metrics?.let { it.retries++ }
        }

        throw EffectiveReadInvalidatedException()
    }

    /** Caller holds the controller monitor; used at completion and final cache read. */
    private fun isContextCurrent(expected: CacheContext) = !disposed &&
        expected.datasetId == revision.datasetId &&
        expected.calendar == captureCalendar() &&
        expected.revision == revision.observedValue &&
        expected.rollbackGeneration == rollbackGeneration &&
        expected.compatibilityFirstWeekday == org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale() &&
        cachedContext == expected

    data class ControllerLockMetrics(
        val lockWaitDurationMs: Double,
        val lockHeldDurationMs: Double,
        val habitCount: Int,
        val cacheHitCount: Int,
        val preparedCount: Int,
        val retriesCount: Int,
    )

    var lockMetricsObserver: ((ControllerLockMetrics) -> Unit)? = null

    /** Disabled by default; diagnostic callbacks cannot affect snapshot success or failure. */
    var requestMetricsObserver: ((RequestMetrics) -> Unit)? = null
    private val activeMeasuredPreparations = AtomicInteger()

    data class RequestMetrics(
        val totalMs: Double, val monitorWaitMs: Double, val monitorHeldMs: Double,
        val latchMs: Double, val latchCount: Int, val prepareMs: Double, val prepareCount: Int,
        val retries: Int, val validatedPublicationMs: Double?, val joinedHabits: Int,
        val createdBatches: Int, val maxConcurrentPreparations: Int, val onMain: Boolean,
        val thread: String, val outcome: String, val habitCount: Int,
        val generation: Long?, val batch: Long?, val cachePublicationDurationMs: Double = 0.0,
        val retagMs: Double = 0.0, val retagSnapshots: Int = 0, val retagDays: Int = 0,
        val historyLookupMs: Double = 0.0,
    )

    private class RequestMeasurement {
        val started = TimeSource.Monotonic.markNow()
        var monitorWaitMs = 0.0
        var monitorHeldMs = 0.0
        var latchMs = 0.0
        var latchCount = 0
        var prepareMs = 0.0
        var prepareCount = 0
        var retries = 0
        var publicationMs: Double? = null
        var cachePublicationMs = 0.0
        var joins = 0
        var creates = 0
        var maxActive = 0
        var retagMs = 0.0
        var retagSnapshots = 0
        var retagDays = 0
        var historyLookupMs = 0.0
    }

    var snapshotMetricsObserver: ((SnapshotPreparationMetrics) -> Unit)?
        get() = provider.preparationMetricsObserver
        set(value) { provider.preparationMetricsObserver = value }

    fun snapshots(habits: List<Habit>): Map<HabitRef, EffectiveHabitReadSnapshot> {
        val today = getToday()
        return snapshots(habits, today.minus(365), today)
    }
    /** Disabled unless QA installs this loader; monitor and publication semantics stay unchanged. */
    fun snapshotsTraced(habits: List<Habit>, traces: List<HabitEditTrace>, generation: Long, batch: Long): Map<HabitRef, EffectiveHabitReadSnapshot> {
        val today = getToday()
        return try {
            snapshots(habits, today.minus(365), today, traces, generation, batch)
        } catch (t: Throwable) {
            traces.forEach { it.event("snapshot.failed", generation = generation, batch = batch) }
            throw t
        }
    }
    fun snapshot(habit: Habit): EffectiveHabitReadSnapshot = snapshots(listOf(habit)).getValue(HabitRef(requireNotNull(habit.uuid)))

    private fun calendarChanged(force: Boolean = false) {
        if (disposed) return
        val current = captureCalendar()
        if (force || current != calendar) {
            calendar = current
            val app = context.applicationContext as? org.isoron.uhabits.HabitsApplication
            setToday(app?.currentHabitDate(current.hourOffset, current.minuteOffset)
                ?: computeToday(current.hourOffset, current.minuteOffset))
            revision.changed()
        }
    }
    override fun onCheckmarkSequenceChanged() = calendarChanged()
    override fun onHabitListAppearanceChanged() = calendarChanged()
    override fun onSyncFinished() = calendarChanged()

    fun dispose() {
        synchronized(this) {
            if (disposed) return
            disposed = true
            cachedContext = null
            cachedSnapshots.clear()
            inFlightByHabit.values.toSet().forEach {
                it.failure = EffectiveReadInvalidatedException()
                it.latch.countDown()
            }
            inFlightByHabit.clear()
        }
        notificationTray?.effectiveSnapshotProvider = null
        handler.removeCallbacksAndMessages(null)
        cache.effectiveSnapshots = null
        revision.removeListener(changed)
        revision.removeRollbackListener(rollback)
        revision.dispose()
        preferences.removeListener(this)
        context.unregisterReceiver(receiver)
    }
}
