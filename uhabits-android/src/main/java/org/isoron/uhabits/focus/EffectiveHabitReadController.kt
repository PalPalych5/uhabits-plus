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
        var totalWaitMs = 0.0
        var totalHeldMs = 0.0
        var totalPrepared = 0
        var initialCacheHits = 0

        repeat(3) { attempt ->
            val waitStart = TimeSource.Monotonic.markNow()
            traces.forEach { it.event("snapshot.lock.wait.begin", generation = generation, batch = batch) }
            val plan = synchronized(this) {
                val waitDuration = waitStart.elapsedNow()
                totalWaitMs += waitDuration.toDouble(DurationUnit.MILLISECONDS)
                traces.forEach { it.event("snapshot.lock.acquired", generation = generation, batch = batch) }
                val heldStart = TimeSource.Monotonic.markNow()
                try {
                    if (disposed) throw EffectiveReadInvalidatedException()

                    val captured = captureCalendar()
                    val capturedRevision = revision.value
                    traces.forEach { it.event("snapshot.begin", capturedRevision, generation, batch) }
                    val capturedRollback = rollbackGeneration
                    val capturedLocaleWeekday = org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale()
                    val currentContext = CacheContext(
                        from, to, revision.datasetId, capturedRevision, captured, capturedRollback, capturedLocaleWeekday
                    )
                    if (cachedContext != currentContext) {
                        cachedContext = currentContext
                        cachedSnapshots.clear()
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
                        } else {
                            toCompute.add(habit)
                        }
                    }

                    var myBatch: InFlightBatch? = null
                    if (toCompute.isNotEmpty()) {
                        val refs = toCompute.mapNotNull { it.uuid?.let { u -> HabitRef(u) } }.toSet()
                        myBatch = InFlightBatch(currentContext, refs)
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
                }
            }

            if (attempt == 0) {
                initialCacheHits = plan.cacheHits
            }

            // Compute outside controller monitor
            var preparedMap: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
            var computeError: Throwable? = null
            if (plan.myBatch != null) {
                try {
                    prepareHook?.invoke(plan.toCompute)
                    preparedMap = provider.prepare(
                        plan.toCompute, from, to, plan.context.datasetId, plan.context.revision, plan.context.calendar
                    )
                } catch (t: Throwable) {
                    computeError = t
                } finally {
                    plan.myBatch.results = preparedMap
                    plan.myBatch.failure = computeError
                    plan.myBatch.latch.countDown()
                }
            }

            // Await any concurrent in-flight batches outside controller monitor
            var awaitError: Throwable? = null
            for (b in plan.toAwait) {
                val completed = b.latch.await(10, TimeUnit.SECONDS)
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
                val publishHeldStart = TimeSource.Monotonic.markNow()
                try {
                    if (plan.myBatch != null) {
                        plan.myBatch.habits.forEach { ref ->
                            if (inFlightByHabit[ref] === plan.myBatch) {
                                inFlightByHabit.remove(ref)
                            }
                        }
                    }

                    val isValid = !disposed &&
                        plan.context.calendar == captureCalendar() &&
                        plan.context.revision == revision.value &&
                        plan.context.rollbackGeneration == rollbackGeneration &&
                        plan.context.compatibilityFirstWeekday == org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale() &&
                        cachedContext == plan.context

                    if (isValid && computeError == null && awaitError == null) {
                        if (preparedMap != null) {
                            cachedSnapshots.putAll(preparedMap)
                            totalPrepared += preparedMap.size
                        }
                        val allReady = habits.all { h ->
                            val ref = h.uuid?.let { HabitRef(it) } ?: return@all true
                            cachedSnapshots.containsKey(ref)
                        }
                        if (allReady) {
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
        }

        throw EffectiveReadInvalidatedException()
    }

    data class ControllerLockMetrics(
        val lockWaitDurationMs: Double,
        val lockHeldDurationMs: Double,
        val habitCount: Int,
        val cacheHitCount: Int,
        val preparedCount: Int,
        val retriesCount: Int,
    )

    var lockMetricsObserver: ((ControllerLockMetrics) -> Unit)? = null

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
            setToday(computeToday(current.hourOffset, current.minuteOffset))
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
