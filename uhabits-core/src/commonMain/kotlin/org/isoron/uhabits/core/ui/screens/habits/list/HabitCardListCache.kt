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

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.Synchronized
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.io.Logging
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitList.Order
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot

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
    private var matcher = HabitMatcher()

    fun getEffectiveSnapshot(habitId: Long): EffectiveHabitReadSnapshot? = data.snapshots[habitId]

    private var checkmarkCount = 0
    private var currentFetchTask: Task? = null
    private var refreshGeneration = 0L
    private var listener: Listener
    private val data: CacheData
    private var filteredHabits: HabitList
    private val taskRunner: TaskRunner

    @Synchronized
    fun cancelTasks() {
        currentFetchTask?.cancel()
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

    @get:Synchronized
    @set:Synchronized
    var primaryOrder: Order
        get() = filteredHabits.primaryOrder
        set(order) {
            allHabits.primaryOrder = order
            filteredHabits.primaryOrder = order
            refreshAllHabits()
        }

    @get:Synchronized
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
        if (command is CreateRepetitionCommand && effectiveSnapshots == null) {
            command.habit.id?.let { refreshHabit(it) }
        } else {
            refreshAllHabits()
        }
    }

    @Synchronized
    fun onDetached() {
        commandRunner.removeListener(this)
    }

    @Synchronized
    fun refreshAllHabits() {
        if (currentFetchTask != null) currentFetchTask!!.cancel()
        val task = RefreshTask(++refreshGeneration)
        currentFetchTask = task
        taskRunner.execute(task)
    }

    @Synchronized
    fun refreshHabit(id: Long) {
        taskRunner.execute(RefreshTask(id, ++refreshGeneration))
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
        fun onItemInserted(position: Int) {}
        fun onItemMoved(oldPosition: Int, newPosition: Int) {}
        fun onItemRemoved(position: Int) {}
        fun onRefreshFinished() {}
    }

    private inner class CacheData {
        val snapshots = mutableMapOf<Long?, EffectiveHabitReadSnapshot>()
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
        fun fetchHabits() {
            val load = effectiveSnapshots
            val candidates = if (load == null) filteredHabits.toList() else
                allHabits.getFiltered(matcher.copy(isCompletedAllowed = true, isEnteredAllowed = true)).toList()
            val batch = load?.invoke(candidates).orEmpty()
            val today = getToday()
            val selected = candidates.filter { h ->
                val snapshot = batch[HabitRef(requireNotNull(h.uuid))]
                snapshot == null || ((matcher.isCompletedAllowed || !snapshot.compatibilityCompletionOn(today).isCompleted) &&
                    (matcher.isEnteredAllowed || if (!snapshot.hasContributions) !h.isEnteredToday() else
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
                selected.sortWith { a, b ->
                    val first = compare(primaryOrder, a, b)
                    val second = if (first == 0) compare(secondaryOrder, a, b) else first
                    if (second != 0) second else originalPositions.getValue(a.id).compareTo(originalPositions.getValue(b.id))
                }
            }
            for (h in selected) {
                if (h.id == null) continue
                habits.add(h)
                idToHabit[h.id] = h
                batch[HabitRef(requireNotNull(h.uuid))]?.let { snapshots[h.id] = it }
            }
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
        private val newData: CacheData
        private val targetId: Long?
        private var isCancelled = false
        private var runner: TaskRunner? = null

        private val generation: Long

        constructor(generation: Long) {
            newData = CacheData()
            targetId = null
            this.generation = generation
            isCancelled = false
        }

        constructor(targetId: Long, generation: Long) {
            newData = CacheData()
            this.targetId = targetId
            this.generation = generation
        }

        @Synchronized
        override fun cancel() {
            isCancelled = true
        }

        @Synchronized
        private fun isCurrent(): Boolean = !isCancelled && generation == refreshGeneration

        @Synchronized
        override suspend fun doInBackground() {
            if (!isCurrent()) return
            newData.fetchHabits()
            newData.copyScoresFrom(data)
            newData.copyCheckmarksFrom(data)
            newData.copyNoteIndicatorsFrom(data)
            val today = getToday()
            val dateFrom = today.minus(checkmarkCount - 1)
            if (runner != null && isCurrent()) runner!!.publishProgress(this, -1)
            for (position in newData.habits.indices) {
                if (!isCurrent()) return
                val habit = newData.habits[position]
                if (targetId != null && targetId != habit.id) continue
                val snapshot = newData.snapshots[habit.id]
                newData.scores[habit.id] = if (snapshot == null) habit.scores[today].value else snapshot.scoreResult.on(today) ?: Double.NaN
                val checkmarkList = mutableListOf<Int>()
                val noteList = mutableListOf<String>()
                for ((_, value, note) in habit.computedEntries.getByInterval(dateFrom, today)) {
                    checkmarkList.add(value)
                    noteList.add(note)
                }
                newData.checkmarks[habit.id] = checkmarkList.toIntArray()
                newData.notes[habit.id] = noteList.toTypedArray()
                if (isCurrent()) runner!!.publishProgress(this, position)
            }
        }

        @Synchronized
        override fun onAttached(runner: TaskRunner) {
            this.runner = runner
        }

        @Synchronized
        override fun onPostExecute() {
            if (!isCurrent()) return
            currentFetchTask = null
            listener.onRefreshFinished()
        }

        @Synchronized
        override fun onProgressUpdate(currentPosition: Int) {
            if (!isCurrent()) return
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
            newData.snapshots[id]?.let { data.snapshots[id] = it }
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
            var unchanged = true
            if (data.snapshots[id]?.revision != newData.snapshots[id]?.revision || newData.snapshots[id] != null || data.snapshots[id] != null) unchanged = false
            if (oldScore != newScore) unchanged = false
            if (!oldCheckmarks.contentEquals(newCheckmarks)) unchanged = false
            if (!oldNoteIndicators.contentEquals(newNoteIndicators)) unchanged = false
            if (unchanged) return
            data.scores[id] = newScore
            data.checkmarks[id] = newCheckmarks
            data.notes[id] = newNoteIndicators
            data.snapshots.remove(id)
            newData.snapshots[id]?.let { data.snapshots[id] = it }
            listener.onItemChanged(position)
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

    init {
        filteredHabits = allHabits
        this.taskRunner = taskRunner
        listener = object : Listener {}
        data = CacheData()
    }
}
