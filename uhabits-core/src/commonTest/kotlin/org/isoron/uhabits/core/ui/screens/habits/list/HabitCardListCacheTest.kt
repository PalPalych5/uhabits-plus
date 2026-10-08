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

import dev.mokkery.mock
import dev.mokkery.resetCalls
import dev.mokkery.verify
import dev.mokkery.verifyNoMoreCalls
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HabitCardListCacheTest : BaseUnitTest() {
    private lateinit var cache: HabitCardListCache
    private lateinit var listener: HabitCardListCache.Listener
    var today = LocalDate(2015, 1, 25)

    @BeforeTest
    override fun setUp() {
        super.setUp()
        habitList.removeAll()
        for (i in 0..9) {
            if (i == 3) habitList.add(fixtures.createLongHabit()) else habitList.add(fixtures.createShortHabit())
        }
        cache = HabitCardListCache(habitList, commandRunner, taskRunner, mock())
        cache.setCheckmarkCount(10)
        cache.refreshAllHabits()
        cache.onAttached()
        listener = mock()
        cache.setListener(listener)
    }

    fun tearDown() {
        cache.onDetached()
    }

    @Test
    fun testCommandListener_all() {
        assertEquals(10, cache.habitCount)
        val h = habitList.getByPosition(0)
        commandRunner.run(
            DeleteHabitsCommand(habitList, listOf(h))
        )
        verify { listener.onItemRemoved(0) }
        verify { listener.onRefreshFinished() }
        assertEquals(9, cache.habitCount)
    }

    @Test
    fun testCommandListener_single() {
        val h2 = habitList.getByPosition(2)
        commandRunner.run(CreateRepetitionCommand(habitList, h2, today, Entry.NO, ""))
        verify { listener.onItemChanged(2) }
        verify { listener.onRefreshFinished() }
        verifyNoMoreCalls(listener)
    }

    @Test
    fun testGet() {
        assertEquals(10, cache.habitCount)
        val h = habitList.getByPosition(3)
        val score = h.scores[today].value
        assertEquals(h, cache.getHabitByPosition(3))
        assertEquals(score, cache.getScore(h.id!!))
        val actualCheckmarks = cache.getCheckmarks(h.id!!)

        val expectedCheckmarks = h
            .computedEntries
            .getByInterval(today.minus(9), today)
            .map { it.value }.toIntArray()
        assertContentEquals(expectedCheckmarks, actualCheckmarks)
    }

    @Test
    fun testRemoval() {
        removeHabitAt(0)
        removeHabitAt(3)
        cache.refreshAllHabits()
        verify { listener.onItemRemoved(0) }
        verify { listener.onItemRemoved(3) }
        verify { listener.onRefreshFinished() }
        assertEquals(8, cache.habitCount)
    }

    @Test
    fun testRefreshWithNoChanges() {
        cache.refreshAllHabits()
        verify { listener.onRefreshFinished() }
        verifyNoMoreCalls(listener)
    }

    @Test
    fun testReorder_onCache() {
        val h2 = cache.getHabitByPosition(2)
        val h3 = cache.getHabitByPosition(3)
        val h7 = cache.getHabitByPosition(7)
        cache.reorder(2, 7)
        assertEquals(h3, cache.getHabitByPosition(2))
        assertEquals(h2, cache.getHabitByPosition(7))
        assertEquals(h7, cache.getHabitByPosition(6))
        verify { listener.onItemMoved(2, 7) }
        verifyNoMoreCalls(listener)
    }

    @Test
    fun testReorder_onList() {
        val h2 = habitList.getByPosition(2)
        val h3 = habitList.getByPosition(3)
        val h7 = habitList.getByPosition(7)
        assertEquals(h2, cache.getHabitByPosition(2))
        assertEquals(h7, cache.getHabitByPosition(7))
        resetCalls(listener)
        habitList.reorder(h2, h7)
        cache.refreshAllHabits()
        assertEquals(h3, cache.getHabitByPosition(2))
        assertEquals(h2, cache.getHabitByPosition(7))
        assertEquals(h7, cache.getHabitByPosition(6))
        verify { listener.onItemMoved(3, 2) }
        verify { listener.onItemMoved(4, 3) }
        verify { listener.onItemMoved(5, 4) }
        verify { listener.onItemMoved(6, 5) }
        verify { listener.onItemMoved(7, 6) }
        verify { listener.onRefreshFinished() }
        verifyNoMoreCalls(listener)
    }

    @Test
    fun testGoalEditInvalidatesCache() {
        val h = habitList.getByPosition(2)!!
        val modified = h.copy()
        modified.type = HabitType.NUMERICAL
        modified.targetValue = 99.0
        modified.unit = "мин"

        resetCalls(listener)
        commandRunner.run(EditHabitCommand(habitList, h.id!!, modified))

        verify { listener.onRefreshFinished() }
    }

    @Test
    fun testOverlappingRefreshAndCancellationPreventsStaleGenerationPublication() {
        val capturedTasks = mutableListOf<org.isoron.uhabits.core.tasks.Task>()
        val manualRunner = object : TaskRunner {
            override val activeTaskCount: Int get() = capturedTasks.size
            override fun execute(task: Task) {
                capturedTasks.add(task)
                task.onAttached(this)
            }
            override fun publishProgress(task: Task, progress: Int) {
                task.onProgressUpdate(progress)
            }
            override fun addListener(listener: TaskRunner.Listener) {}
            override fun removeListener(listener: TaskRunner.Listener) {}
            override suspend fun await() {}
        }

        val testCache = HabitCardListCache(habitList, commandRunner, manualRunner, mock())
        val mockListener = mock<HabitCardListCache.Listener>()
        testCache.setListener(mockListener)

        // Trigger first refresh -> generation 1
        testCache.refreshAllHabits()
        assertEquals(1, capturedTasks.size)
        val task1 = capturedTasks[0]

        // Trigger overlapping second refresh -> cancels task1, increments to generation 2
        testCache.refreshAllHabits()
        assertEquals(2, capturedTasks.size)
        val task2 = capturedTasks[1]

        // Execute stale task1 in background and onProgress / onPostExecute
        kotlinx.coroutines.runBlocking {
            task1.doInBackground()
        }
        task1.onProgressUpdate(-1)
        task1.onProgressUpdate(0)
        task1.onPostExecute()

        // Stale task1 MUST NOT notify listener or publish to cache
        verifyNoMoreCalls(mockListener)

        // Execute valid current task2
        kotlinx.coroutines.runBlocking {
            task2.doInBackground()
        }
        task2.onProgressUpdate(-1)
        task2.onProgressUpdate(0)
        task2.onPostExecute()

        // Current task2 MUST publish results
        verify { mockListener.onRefreshFinished() }
    }

    private fun removeHabitAt(position: Int) {
        val h = habitList.getByPosition(position)
        habitList.remove(h)
    }
}
