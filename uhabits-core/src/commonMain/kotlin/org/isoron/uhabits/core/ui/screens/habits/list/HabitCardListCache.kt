/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.ui.screens.habits.list

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import org.isoron.uhabits.core.io.HabitStartupTiming
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.Synchronized
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.io.Logging
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitList.Order
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.isoron.uhabits.core.models.projection.CompatibilityCompletion
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.platform.time.LocalDate

/**
 * A HabitCardListCache fetches and keeps a cache of all the data necessary to
 * render a HabitCardListView.
 *
 *
 * This is needed since performing database lookups during scrolling can make
 * the ListView very slow. It also registers itself as an observer of the
 * models, in order to update itself automatically.
 *
 *
 * Note that this class is singleton-scoped, therefore it is shared among all
 * activities.
 */
@AppScope
@Inject
class HabitCardListCache(
    private val allHabits: HabitList,
    private val commandRunner: CommandRunner,
    taskRunner: TaskRunner,
    logging: Logging
) : CommandRunner.Listener {

    private val logger = logging.getLogger("HabitCardListCache")
    /** Optional dataset read capability; production legacy leaves this unset. Batch called once per refresh. */
    var effectiveSnapshots: ((List<Habit>) -> Map<HabitRef, EffectiveHabitReadSnapshot>)? = null
    /** QA-only stage timings. Disabled unless the capture test explicitly installs a listener. */
    var timingObserver: ((String, Double) -> Unit)? = null
    /** QA-only lineage captured at request time; IDs remain internal and are never exported. */
    var operationTraces: ((Long?) -> List<HabitEditTrace>)? = null
        @Synchronized set(value) {
            field = value
            if (value == null) publishedOperationTraces.clear()
        }
    var effectiveSnapshotsTraced: ((List<Habit>, List<HabitEditTrace>, Long, Long) -> Map<HabitRef, EffectiveHabitReadSnapshot>)? = null
    private var nextTraceBatch = 0L
    private val publishedOperationTraces = mutableMapOf<Long, List<HabitEditTrace>>()

    @Synchronized
    fun getPublishedOperationTraces(habitId: Long): List<HabitEditTrace> =
        publishedOperationTraces[habitId].orEmpty()

    private var matcher = HabitMatcher()

    @Synchronized
    fun getEffectiveSnapshot(habitId: Long): EffectiveHabitReadSnapshot? = data.snapshots[habitId]

    /** Metadata is prepared with the card generation, including absent icons/groups. */
    data class RowMetadata(val iconRef: IconRef?, val rootGroupKey: Any?, val dayTier: DayTier)

    // Capture mutable Habit attributes on the worker. Comparing live Habit
    // references cannot detect a title/color/goal edit after the model changes.
    private data class BindingStyle(
        val name: String, val color: PaletteColor, val type: HabitType,
        val frequency: Frequency, val targetType: NumericalHabitType,
        val targetValue: Double, val unit: String, val goals: List<HabitGoal>,
        val today: LocalDate,
    )
    private data class EffectiveCellBinding(
        val day: EffectiveHabitDay?, val completion: CompatibilityCompletion?,
    )
    private data class EffectiveBinding(
        val calendar: EffectiveReadCalendarContext, val cells: List<EffectiveCellBinding>,
    )

    @Synchronized
    fun getMetadata(habitId: Long): RowMetadata =
        data.metadata[habitId] ?: RowMetadata(null, null, DayTier.NORMAL)

    private var checkmarkCount = 0
    private var currentFetchTask: Task? = null
    private val pendingRefreshTasks = mutableSetOf<RefreshTask>()
    @Volatile
    private var shutDown = false
    private var refreshGeneration = 0L
    var hasLoaded: Boolean = false
        private set
    var lastRefreshFailed: Boolean = false
        private set
    private var listener: Listener
    private val data: CacheData
    private var filteredHabits: HabitList
    private val taskRunner: TaskRunner

    @Synchronized
    fun cancelTasks() {
        currentFetchTask?.cancel()
    }

    /** Disposable fixture resets must finish existing SQL readers before replacing rows. */
    fun cancelAndDrainRefreshes() {
        cancelPendingRefreshes().forEach { it.awaitBackgroundCompletion() }
    }

    @Synchronized
    private fun cancelPendingRefreshes(): List<RefreshTask> {
        refreshGeneration++
        val tasks = pendingRefreshTasks.toList()
        tasks.forEach { it.cancel() }
        currentFetchTask = null
        return tasks
    }

    /** Drain SQL readers before their dataset connection is closed. Never hold the cache lock while waiting. */
    fun shutdown() {
        beginShutdown().forEach { it.awaitBackgroundCompletion() }
    }

    @Synchronized
    private fun beginShutdown(): List<RefreshTask> {
        shutDown = true
        refreshGeneration++
        val tasks = pendingRefreshTasks.toList()
        tasks.forEach { it.cancel() }
        pendingRefreshTasks.clear()
        currentFetchTask = null
        return tasks
    }

    @Synchronized
    private fun finished(task: RefreshTask) {
        pendingRefreshTasks.remove(task)
    }

    @Synchronized
    fun getCheckmarks(habitId: Long): IntArray {
        return data.checkmarks[habitId]!!
    }

    @Synchronized
    fun getNotes(habitId: Long): Array<String> {
        return data.notes[habitId]!!
    }

    @Synchronized
    fun hasNoHabit(): Boolean {
        return allHabits.isEmpty
    }

    /**
     * Returns the habits that occupies a certain position on the list.
     *
     * @param position the position of the habit
     * @return the habit at given position or null if position is invalid
     */
    @Synchronized
    fun getHabitByPosition(position: Int): Habit? {
        return if (position < 0 || position >= data.habits.size) null else data.habits[position]
    }

    @get:Synchronized
    val habitCount: Int
        get() = data.habits.size

    @set:Synchronized
    var primaryOrder: Order
        get() = filteredHabits.primaryOrder
        set(order) {
            allHabits.primaryOrder = order
            filteredHabits.primaryOrder = order
            refreshAllHabits()
        }

    @set:Synchronized
    var secondaryOrder: Order
        get() = filteredHabits.secondaryOrder
        set(order) {
            allHabits.secondaryOrder = order
            filteredHabits.secondaryOrder = order
            refreshAllHabits()
        }

    @Synchronized
    fun setDayTierSortOrder(order: List<DayTier>) {
        allHabits.dayTierSortOrder = order
        filteredHabits.dayTierSortOrder = order
        refreshAllHabits()
    }

    @Synchronized
    fun getScore(habitId: Long): Double {
        return data.scores[habitId]!!
    }

    @Synchronized
    fun onAttached() {
        refreshAllHabits()
        commandRunner.addListener(this)
    }

    @Synchronized
    override fun onCommandFinished(command: Command) {
        if (command is CreateRepetitionCommand) {
            command.habit.id?.let { refreshHabit(it) }
        } else {
            refreshAllHabits()
        }
    }

    private val habitRefreshGenerations = mutableMapOf<Long, Long>()

    @Synchronized
    fun onDetached() {
        commandRunner.removeListener(this)
        (filteredHabits as? MemoryHabitList)?.detachFromParent()
    }

    var refreshObserver: ((type: String, habitId: Long?, reason: String?) -> Unit)? = null

    @Synchronized
    fun refreshAllHabits() {
        if (shutDown) return
        refreshObserver?.invoke("full", null, null)
        lastRefreshFailed = false
        if (currentFetchTask != null) currentFetchTask!!.cancel()
        val task = RefreshTask(++refreshGeneration)
        HabitStartupTiming.mark("cache.refresh.request", refreshGeneration)
        pendingRefreshTasks.add(task)
        currentFetchTask = task
        taskRunner.execute(task)
    }

    @Synchronized
    fun refreshHabit(id: Long) {
        if (shutDown) return
        val entryOrders = setOf(Order.BY_SCORE_ASC, Order.BY_SCORE_DESC, Order.BY_STATUS_ASC, Order.BY_STATUS_DESC)
        // An entry can change membership/order. An unfinished full read can also
        // otherwise publish its older values after this targeted read.
        if (currentFetchTask != null || !matcher.isCompletedAllowed || !matcher.isEnteredAllowed ||
            primaryOrder in entryOrders || secondaryOrder in entryOrders) {
            val reason = if (currentFetchTask != null) "task_in_flight" else "filter_or_order"
            refreshObserver?.invoke("targeted_fallback", id, reason)
            refreshAllHabits()
            return
        }
        refreshObserver?.invoke("targeted", id, null)
        lastRefreshFailed = false
        val gen = (habitRefreshGenerations[id] ?: 0L) + 1L
        habitRefreshGenerations[id] = gen
        val task = RefreshTask(id, gen, refreshGeneration)
        pendingRefreshTasks.add(task)
        taskRunner.execute(task)
    }

    @Synchronized
    fun remove(id: Long) {
        val h = data.idToHabit[id] ?: return
        val position = data.habits.indexOf(h)
        data.habits.removeAt(position)
        data.idToHabit.remove(id)
        data.checkmarks.remove(id)
        data.notes.remove(id)
        data.scores.remove(id)
        data.snapshots.remove(id)
        data.metadata.remove(id)
        data.bindingStyles.remove(id)
        data.effectiveBindings.remove(id)
        listener.onItemRemoved(position)
    }

    @Synchronized
    fun reorder(from: Int, to: Int) {
        val fromHabit = data.habits[from]
        data.habits.removeAt(from)
        data.habits.add(to, fromHabit)
        listener.onItemMoved(from, to)
    }

    @Synchronized
    fun setCheckmarkCount(checkmarkCount: Int) {
        this.checkmarkCount = checkmarkCount
    }

    @Synchronized
    fun setFilter(matcher: HabitMatcher) {
        this.matcher = matcher
        (filteredHabits as? MemoryHabitList)?.detachFromParent()
        filteredHabits = allHabits.getFiltered(matcher)
    }

    @Synchronized
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Interface definition for a callback to be invoked when the data on the
     * cache has been modified.
     */
    interface Listener {
        fun onItemChanged(position: Int) {}
        fun onEntryChanged(position: Int) { onItemChanged(position) }
        fun onItemInserted(position: Int) {}
        fun onItemMoved(oldPosition: Int, newPosition: Int) {}
        fun onItemRemoved(position: Int) {}
        fun onRefreshFinished() {}
        fun onRefreshFailed() {}
    }

    private inner class CacheData {
        val metadata = mutableMapOf<Long?, RowMetadata>()
        val snapshots = mutableMapOf<Long?, EffectiveHabitReadSnapshot>()
        val bindingStyles = mutableMapOf<Long?, BindingStyle>()
        val effectiveBindings = mutableMapOf<Long?, EffectiveBinding?>()
        val idToHabit: MutableMap<Long?, Habit> = mutableMapOf()
        val habits: MutableList<Habit>
        val checkmarks: MutableMap<Long?, IntArray>
        val scores: MutableMap<Long?, Double>
        val notes: MutableMap<Long?, Array<String>>

        @Synchronized
        fun copyCheckmarksFrom(oldData: CacheData) {
            val empty = IntArray(checkmarkCount)
            for (id in idToHabit.keys) {
                if (oldData.checkmarks.containsKey(id)) {
                    checkmarks[id] =
                        oldData.checkmarks[id]!!
                } else {
                    checkmarks[id] = empty
                }
            }
        }

        @Synchronized
        fun copyNoteIndicatorsFrom(oldData: CacheData) {
            val empty = (0..checkmarkCount).map { "" }.toTypedArray()
            for (id in idToHabit.keys) {
                if (oldData.notes.containsKey(id)) {
                    notes[id] =
                        oldData.notes[id]!!
                } else {
                    notes[id] = empty
                }
            }
        }

        @Synchronized
        fun copyScoresFrom(oldData: CacheData) {
            for (id in idToHabit.keys) {
                if (oldData.scores.containsKey(id)) {
                    scores[id] =
                        oldData.scores[id]!!
                } else {
                    scores[id] = 0.0
                }
            }
        }

        @Synchronized
        fun fetchHabits(generation: Long, targetId: Long?, previous: CacheData,
                        requestedMatcher: HabitMatcher, load: ((List<Habit>) -> Map<HabitRef, EffectiveHabitReadSnapshot>)?,
                        requestedPrimary: Order, requestedSecondary: Order) {
            HabitStartupTiming.mark("cache.fetch.begin", generation)
            val snapshotStarted = TimeSource.Monotonic.markNow()
            val today = getToday()

            if (targetId != null && previous.habits.isNotEmpty()) {
                habits.addAll(previous.habits)
                idToHabit.putAll(previous.idToHabit)
                snapshots.putAll(previous.snapshots)

                val targetHabit = idToHabit[targetId]
                if (targetHabit != null && load != null) {
                    val singleBatch = load.invoke(listOf(targetHabit))
                    val ref = targetHabit.uuid?.let { HabitRef(it) }
                    if (ref != null && singleBatch.containsKey(ref)) {
                        snapshots[targetId] = singleBatch.getValue(ref)
                    }
                }
                timingObserver?.invoke("cache.effectiveSnapshots", snapshotStarted.elapsedNow().inWholeNanoseconds / 1e6)
                HabitStartupTiming.mark("cache.fetch.end", generation)
                return
            }

            val candidateMatcher = if (load == null) requestedMatcher else
                requestedMatcher.copy(isCompletedAllowed = true, isEnteredAllowed = true)
            // Read the root list rather than a filtered list detached during navigation.
            val candidates = allHabits.filter { candidateMatcher.matches(it) }
            HabitStartupTiming.mark("cache.effective.begin", generation)
            val batch = load?.invoke(candidates).orEmpty()
            HabitStartupTiming.mark("cache.effective.end", generation)
            timingObserver?.invoke("cache.effectiveSnapshots", snapshotStarted.elapsedNow().inWholeNanoseconds / 1e6)
            val selected = candidates.filter { h ->
                val snapshot = batch[HabitRef(requireNotNull(h.uuid))]
                snapshot == null || ((requestedMatcher.isCompletedAllowed || !snapshot.compatibilityCompletionOn(today).isCompleted) &&
                    (requestedMatcher.isEnteredAllowed || if (!snapshot.hasContributions) !h.isEnteredToday() else
                        (snapshot.day(today).manualEntry.value == org.isoron.uhabits.core.models.Entry.UNKNOWN &&
                            snapshot.day(today).contributions.isEmpty())))
            }.toMutableList()
            if (load != null && batch.values.any { it.hasContributions }) {
                val originalPositions = candidates.withIndex().associate { it.value.id to it.index }
                fun compare(order: Order, a: Habit, b: Habit): Int {
                    val sa = batch.getValue(HabitRef(requireNotNull(a.uuid)))
                    val sb = batch.getValue(HabitRef(requireNotNull(b.uuid)))
                    return when (order) {
                        Order.BY_SCORE_DESC, Order.BY_SCORE_ASC -> {
                            val va = sa.scoreResult.on(today); val vb = sb.scoreResult.on(today)
                            val c = when { va == null && vb == null -> 0; va == null -> 1; vb == null -> -1; else -> va.compareTo(vb) }
                            if (order == Order.BY_SCORE_ASC && va != null && vb != null) -c else c
                        }
                        Order.BY_STATUS_DESC, Order.BY_STATUS_ASC -> {
                            val c = sb.compatibilityCompletionOn(today).isCompleted.compareTo(sa.compatibilityCompletionOn(today).isCompleted)
                            val type = b.isNumerical.compareTo(a.isNumerical)
                            val value = (sb.day(today).totalValue ?: sb.day(today).manualEntry.value.toLong())
                                .compareTo(sa.day(today).totalValue ?: sa.day(today).manualEntry.value.toLong())
                            val result = if (c != 0) c else if (type != 0) type else value
                            if (order == Order.BY_STATUS_ASC) -result else result
                        }
                        Order.BY_NAME_ASC -> a.name.compareTo(b.name)
                        Order.BY_NAME_DESC -> b.name.compareTo(a.name)
                        Order.BY_COLOR_ASC -> a.color.compareTo(b.color)
                        Order.BY_COLOR_DESC -> b.color.compareTo(a.color)
                        Order.BY_POSITION -> a.position.compareTo(b.position)
                        // These comparators include position/name tie-breaks in the legacy model.
                        else -> originalPositions.getValue(a.id).compareTo(originalPositions.getValue(b.id))
                    }
                }
                val primOrder = requestedPrimary
                val secOrder = requestedSecondary
                selected.sortWith { a, b ->
                    val first = compare(primOrder, a, b)
                    val second = if (first == 0) compare(secOrder, a, b) else first
                    if (second != 0) second else originalPositions.getValue(a.id).compareTo(originalPositions.getValue(b.id))
                }
            }
            for (h in selected) {
                if (h.id == null) continue
                habits.add(h)
                idToHabit[h.id] = h
                batch[HabitRef(requireNotNull(h.uuid))]?.let { snapshots[h.id] = it }
            }
            HabitStartupTiming.mark("cache.fetch.end", generation)
        }

        /**
         * Creates a new CacheData without any content.
         */
        init {
            habits = mutableListOf()
            checkmarks = mutableMapOf()
            scores = mutableMapOf()
            notes = mutableMapOf()
        }
    }

    private inner class RefreshTask : Task {
        // Constructed under the cache monitor; workers never traverse published maps.
        private val previous = CacheData().apply {
            habits.addAll(data.habits)
            idToHabit.putAll(data.idToHabit)
            snapshots.putAll(data.snapshots)
            scores.putAll(data.scores)
            checkmarks.putAll(data.checkmarks)
            notes.putAll(data.notes)
            metadata.putAll(data.metadata)
            bindingStyles.putAll(data.bindingStyles)
            effectiveBindings.putAll(data.effectiveBindings)
        }
        private val requestedMatcher = matcher
        private val baseLoad = effectiveSnapshots
        private var traceBatch: Long? = null
        private var traces: List<HabitEditTrace> = emptyList()
        private var tracesByHabit: Map<Long, List<HabitEditTrace>> = emptyMap()
        private val tracedLoad = effectiveSnapshotsTraced
        private val requestedLoad: ((List<Habit>) -> Map<HabitRef, EffectiveHabitReadSnapshot>)?
            get() = if (traces.isNotEmpty() && tracedLoad != null) {
                { habits -> tracedLoad.invoke(habits, traces, generation, requireNotNull(traceBatch)) }
            } else baseLoad

        private fun trace(stage: String) {
            traces.forEach { it.event(stage, generation = generation, batch = traceBatch) }
        }

        private fun captureTraces() {
            val select = operationTraces ?: return
            traceBatch = ++nextTraceBatch
            traces = runCatching { select(targetId).toList() }.getOrDefault(emptyList())
            tracesByHabit = if (targetId != null) mapOf(targetId!! to traces) else
                allHabits.mapNotNull { habit -> habit.id?.let { id ->
                    val rowTraces = runCatching { select(id).filter { it in traces }.toList() }.getOrDefault(emptyList())
                    id to rowTraces
                } }.toMap()
            trace("cache.request")
        }

        private fun publishTrace(id: Long?) {
            if (id == null || traces.isEmpty() || operationTraces == null) return
            // Refresh lineage is candidate provenance, never proof of the expected cell state.
            val rowTraces = tracesByHabit[id].orEmpty()
            publishedOperationTraces[id] = rowTraces
            val revision = newData.snapshots[id]?.revision
            rowTraces.forEach { it.event("cache.row.published", revision, generation, traceBatch) }
        }
        private val requestedPrimary = primaryOrder
        private val requestedSecondary = secondaryOrder
        private val requestedCheckmarkCount = checkmarkCount
        private val requestedOrganization = (allHabits as? MemoryHabitList)?.organizationFacade
        private val newData: CacheData
        private val targetId: Long?
        @Volatile
        private var isCancelled = false
        @Volatile
        private var backgroundComplete = false
        @Volatile private var fetchFailure: Exception? = null
        private var runner: TaskRunner? = null

        private val generation: Long
        private val baseFullGeneration: Long

        constructor(generation: Long) {
            newData = CacheData()
            targetId = null
            this.generation = generation
            this.baseFullGeneration = generation
            isCancelled = false
            captureTraces()
        }

        constructor(targetId: Long, generation: Long, baseFullGeneration: Long) {
            newData = CacheData()
            this.targetId = targetId
            this.generation = generation
            this.baseFullGeneration = baseFullGeneration
            isCancelled = false
            captureTraces()
        }

        override fun cancel() {
            isCancelled = true
            trace("cache.cancelled")
        }

        private fun isCurrent(): Boolean = withCacheLock {
            if (shutDown || isCancelled) return@withCacheLock false
            if (targetId == null) {
                generation == refreshGeneration
            } else {
                generation == (habitRefreshGenerations[targetId] ?: 0L) && baseFullGeneration >= refreshGeneration
            }
        }

        @Synchronized
        fun awaitBackgroundCompletion() {
            // Acquiring this monitor waits for doInBackground; queued tasks observe shutDown.
        }

        @Synchronized
        override suspend fun doInBackground() {
            try {
                prepareInBackground()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                trace("cache.failed")
                fetchFailure = e
                runCatching { logger.error(e) }
                runner?.publishProgress(this, Int.MIN_VALUE)
                throw e
            }
        }

        private suspend fun prepareInBackground() {
            if (!isCurrent()) { trace("cache.superseded"); return }
            trace("cache.background.begin")
            HabitStartupTiming.mark("cache.worker.begin", generation)
            val started = TimeSource.Monotonic.markNow()
            try {
                newData.fetchHabits(generation, targetId, previous, requestedMatcher, requestedLoad, requestedPrimary, requestedSecondary)
            } catch (_: org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException) {
                // Keep the last published generation. A concurrent revision requests a new one.
                isCancelled = true
                trace("cache.snapshot.invalidated")
                return
            }
            newData.copyScoresFrom(previous)
            newData.copyCheckmarksFrom(previous)
            newData.copyNoteIndicatorsFrom(previous)
            val today = getToday()
            val dateFrom = today.minus(requestedCheckmarkCount - 1)
            for (position in newData.habits.indices) {
                if (!isCurrent()) { trace("cache.superseded"); return }
                val habit = newData.habits[position]
                if (targetId != null && targetId != habit.id) continue
                val snapshot = newData.snapshots[habit.id]
                val facade = requestedOrganization
                newData.metadata[habit.id] = RowMetadata(
                    iconRef = facade?.getHabitVisualIdentity(habit.uuid ?: "")?.iconRef,
                    rootGroupKey = if (facade == null) habit.blockId else
                        facade.getRootGroupKey(habit.uuid ?: "", habit.blockId),
                    dayTier = habit.dayTier,
                )
                newData.bindingStyles[habit.id] = BindingStyle(
                    habit.name, habit.color, habit.type, habit.frequency, habit.targetType,
                    habit.targetValue, habit.unit, habit.goalHistory.toList(), today,
                )
                // The number panel uses canonical day/coverage/completion data.
                // Prepare its equality key off Main, for every horizontally
                // accessible date, without making revision freshness a repaint.
                newData.effectiveBindings[habit.id] = snapshot?.takeIf { it.hasContributions }?.let { current ->
                    EffectiveBinding(current.calendar, List(requestedCheckmarkCount) { index ->
                        val date = today.minus(index)
                        if (date < current.from || date > current.to) EffectiveCellBinding(null, null)
                        else EffectiveCellBinding(current.day(date), current.compatibilityCompletionOn(date))
                    })
                }
                newData.scores[habit.id] = if (snapshot == null) habit.scores[today].value else snapshot.scoreResult.on(today) ?: Double.NaN
                val checkmarkList = mutableListOf<Int>()
                val noteList = mutableListOf<String>()
                for ((_, value, note) in habit.computedEntries.getByInterval(dateFrom, today)) {
                    checkmarkList.add(value)
                    noteList.add(note)
                }
                newData.checkmarks[habit.id] = checkmarkList.toIntArray()
                newData.notes[habit.id] = noteList.toTypedArray()
            }
            backgroundComplete = true
            trace("cache.background.end")
            HabitStartupTiming.mark("cache.cards.ready", generation)
            timingObserver?.invoke("cache.background", started.elapsedNow().inWholeNanoseconds / 1e6)
        }

        @Synchronized
        override fun onAttached(runner: TaskRunner) {
            this.runner = runner
        }

        override fun onPostExecute() = withCacheLock {
            finished(this)
            if (!isCurrent()) { trace("cache.superseded"); return@withCacheLock }
            trace("cache.publish.begin")
            if (targetId == null) {
                currentFetchTask = null
            }
            HabitStartupTiming.mark("cache.publish.begin", generation)
            val started = TimeSource.Monotonic.markNow()
            if (targetId != null) {
                val position = data.habits.indexOfFirst { it.id == targetId }
                if (position >= 0) {
                    performUpdate(targetId, position)
                }
            } else {
                // Publish only after the worker releases its reader monitor. Posting progress
                // while it owns that monitor made the main thread wait for all SQL/snapshots.
                processRemovedHabits()
                for (position in newData.habits.indices) processPosition(position)
            }
            hasLoaded = true
            trace("cache.publish.end")
            listener.onRefreshFinished()
            HabitStartupTiming.mark("cache.publish.end", generation)
            timingObserver?.invoke("cache.publish", started.elapsedNow().inWholeNanoseconds / 1e6)
        }

        override fun onProgressUpdate(currentPosition: Int) = withCacheLock {
            if (currentPosition == Int.MIN_VALUE) {
                finished(this)
                if (!isCurrent() || fetchFailure == null) return@withCacheLock
                if (targetId == null) currentFetchTask = null
                lastRefreshFailed = true
                listener.onRefreshFailed()
                return@withCacheLock
            }
            if (!backgroundComplete || !isCurrent()) return@withCacheLock
            if (targetId != null) return@withCacheLock
            if (currentPosition < 0) processRemovedHabits() else processPosition(currentPosition)
        }

        @Synchronized
        private fun performInsert(habit: Habit, position: Int) {
            val id = habit.id
            data.habits.add(position, habit)
            data.idToHabit[id] = habit
            data.scores[id] = newData.scores[id]!!
            data.checkmarks[id] = newData.checkmarks[id]!!
            data.notes[id] = newData.notes[id]!!
            data.metadata[id] = requireNotNull(newData.metadata[id])
            data.bindingStyles[id] = requireNotNull(newData.bindingStyles[id])
            data.effectiveBindings[id] = newData.effectiveBindings[id]
            newData.snapshots[id]?.let { data.snapshots[id] = it }
            publishTrace(id)
            listener.onItemInserted(position)
        }

        @Synchronized
        private fun performMove(
            habit: Habit,
            fromPosition: Int,
            toPosition: Int
        ) {
            data.habits.removeAt(fromPosition)

            // Workaround for https://github.com/iSoron/uhabits/issues/968
            val checkedToPosition = if (toPosition > data.habits.size) {
                logger.error("performMove: $toPosition is strictly higher than ${data.habits.size}")
                data.habits.size
            } else {
                toPosition
            }

            data.habits.add(checkedToPosition, habit)
            listener.onItemMoved(fromPosition, checkedToPosition)
        }

        @Synchronized
        private fun performUpdate(id: Long, position: Int) {
            val oldScore = data.scores[id]!!
            val oldCheckmarks = data.checkmarks[id]
            val oldNoteIndicators = data.notes[id]
            val newScore = newData.scores[id]!!
            val newCheckmarks = newData.checkmarks[id]!!
            val newNoteIndicators = newData.notes[id]!!
            val styleChanged = data.metadata[id] != newData.metadata[id] ||
                data.bindingStyles[id] != newData.bindingStyles[id]
            val scoreChanged = oldScore != newScore && !(oldScore.isNaN() && newScore.isNaN())
            val entriesChanged = scoreChanged || !oldCheckmarks.contentEquals(newCheckmarks) ||
                !oldNoteIndicators.contentEquals(newNoteIndicators) ||
                data.effectiveBindings[id] != newData.effectiveBindings[id]
            data.scores[id] = newScore
            data.checkmarks[id] = newCheckmarks
            data.notes[id] = newNoteIndicators
            data.metadata[id] = requireNotNull(newData.metadata[id])
            data.bindingStyles[id] = requireNotNull(newData.bindingStyles[id])
            data.effectiveBindings[id] = newData.effectiveBindings[id]
            // Publication always advances to the latest verified revision,
            // including rows whose visible values did not change.
            data.snapshots.remove(id)
            newData.snapshots[id]?.let { data.snapshots[id] = it }
            publishTrace(id)
            if (styleChanged) listener.onItemChanged(position)
            else if (entriesChanged) listener.onEntryChanged(position)
        }

        @Synchronized
        private fun processPosition(currentPosition: Int) {
            val habit = newData.habits[currentPosition]
            val id = habit.id
            val prevPosition = data.habits.indexOfFirst { it.id == id }
            if (prevPosition < 0) {
                performInsert(habit, currentPosition)
            } else {
                if (prevPosition != currentPosition) {
                    performMove(
                        habit,
                        prevPosition,
                        currentPosition
                    )
                }
                if (id == null) throw NullPointerException()
                performUpdate(id, currentPosition)
            }
        }

        @Synchronized
        private fun processRemovedHabits() {
            val before: Set<Long?> = data.idToHabit.keys
            val after: Set<Long?> = newData.idToHabit.keys
            val removed = before - after
            for (id in removed) remove(id!!)
        }
    }

    @Synchronized
    private fun <T> withCacheLock(action: () -> T): T = action()

    init {
        filteredHabits = allHabits
        this.taskRunner = taskRunner
        listener = object : Listener {}
        data = CacheData()
    }
}
