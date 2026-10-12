package org.isoron.uhabits.core.ui.screens.habits.list

import dev.mokkery.mock
import dev.mokkery.verifyNoMoreCalls
import dev.mokkery.verify
import dev.mokkery.resetCalls
import kotlinx.coroutines.runBlocking
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.platform.time.getToday

class HabitCardListCacheConcurrencyTest : BaseUnitTest() {
    @Test
    fun overlappingDifferentHabitRefreshesPublishIndependently() {
        val first = fixtures.createShortHabit(); habitList.add(first)
        val second = fixtures.createShortHabit(); habitList.add(second)
        val captured = mutableListOf<Task>()
        val runner = object : TaskRunner {
            override val activeTaskCount get() = captured.size
            override fun execute(task: Task) { captured.add(task); task.onAttached(this) }
            override fun publishProgress(task: Task, progress: Int) {}
            override fun addListener(listener: TaskRunner.Listener) {}
            override fun removeListener(listener: TaskRunner.Listener) {}
            override suspend fun await() {}
        }
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        cache.setCheckmarkCount(10)
        cache.refreshAllHabits()
        runBlocking { captured.last().doInBackground() }; captured.last().onPostExecute()
        CreateRepetitionCommand(habitList, first, getToday(), Entry.NO, "first").run()
        cache.refreshHabit(first.id!!)
        val firstTask = captured.last()
        runBlocking { firstTask.doInBackground() }
        CreateRepetitionCommand(habitList, second, getToday(), Entry.NO, "second").run()
        cache.refreshHabit(second.id!!)
        val secondTask = captured.last()
        runBlocking { secondTask.doInBackground() }
        secondTask.onPostExecute()
        firstTask.onPostExecute()
        assertEquals("first", cache.getNotes(first.id!!)[0])
        assertEquals("second", cache.getNotes(second.id!!)[0])
        assertEquals(Entry.NO, cache.getCheckmarks(first.id!!)[0])
        assertEquals(Entry.NO, cache.getCheckmarks(second.id!!)[0])
    }

    @Test
    fun olderFullRefreshCannotOverwriteNewCommittedTargetRefresh() {
        val habit = fixtures.createShortHabit()
        habitList.add(habit)
        val captured = mutableListOf<Task>()
        val runner = object : TaskRunner {
            override val activeTaskCount get() = captured.size
            override fun execute(task: Task) { captured.add(task); task.onAttached(this) }
            override fun publishProgress(task: Task, progress: Int) {}
            override fun addListener(listener: TaskRunner.Listener) {}
            override fun removeListener(listener: TaskRunner.Listener) {}
            override suspend fun await() {}
        }
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        cache.setCheckmarkCount(10)
        CreateRepetitionCommand(habitList, habit, getToday(), Entry.NO, "").run()
        cache.refreshAllHabits()
        runBlocking { captured.last().doInBackground() }
        captured.last().onPostExecute()

        cache.refreshAllHabits()
        val oldFull = captured.last()
        runBlocking { oldFull.doInBackground() }
        CreateRepetitionCommand(habitList, habit, getToday(), Entry.YES_MANUAL, "latest").run()
        cache.refreshHabit(habit.id!!)
        val latest = captured.last()
        runBlocking { latest.doInBackground() }
        latest.onPostExecute()
        assertEquals(Entry.YES_MANUAL, cache.getCheckmarks(habit.id!!)[0])
        oldFull.onPostExecute()
        assertEquals(Entry.YES_MANUAL, cache.getCheckmarks(habit.id!!)[0])
        assertEquals("latest", cache.getNotes(habit.id!!)[0])
    }

    @Test
    fun initialReadFailureIsReportedWithoutPublishingFakeRowsAndRetryLoadsNormally() {
        habitList.add(fixtures.createShortHabit())
        val captured = mutableListOf<Task>()
        val progress = mutableListOf<Pair<Task, Int>>()
        val runner = object : TaskRunner {
            override val activeTaskCount get() = captured.size
            override fun execute(task: Task) { captured.add(task); task.onAttached(this) }
            override fun publishProgress(task: Task, value: Int) { progress.add(task to value) }
            override fun addListener(listener: TaskRunner.Listener) {}
            override fun removeListener(listener: TaskRunner.Listener) {}
            override suspend fun await() {}
        }
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        val listener = mock<HabitCardListCache.Listener>()
        cache.setListener(listener)
        cache.setCheckmarkCount(10)
        cache.effectiveSnapshots = { throw IllegalStateException("QA read failure") }
        cache.refreshAllHabits()
        assertFailsWith<IllegalStateException> { runBlocking { captured.last().doInBackground() } }
        progress.last().let { it.first.onProgressUpdate(it.second) }
        assertFalse(cache.hasLoaded)
        assertTrue(cache.lastRefreshFailed)
        assertEquals(0, cache.habitCount)
        verify { listener.onRefreshFailed() }
        resetCalls(listener)
        cache.effectiveSnapshots = null
        cache.refreshAllHabits()
        // A delayed failure from the old generation must not overwrite the retry state.
        progress.last().let { it.first.onProgressUpdate(it.second) }
        assertFalse(cache.lastRefreshFailed)
        verifyNoMoreCalls(listener)
        runBlocking { captured.last().doInBackground() }
        captured.last().onPostExecute()
        assertTrue(cache.hasLoaded)
        assertEquals(1, cache.habitCount)
    }

    @Test
    fun unstableSnapshotDoesNotPublishAndSubsequentStableRefreshSucceeds() {
        habitList.add(fixtures.createShortHabit())
        val captured = mutableListOf<Task>()
        val runner = object : TaskRunner {
            override val activeTaskCount get() = captured.size
            override fun execute(task: Task) { captured.add(task); task.onAttached(this) }
            override fun publishProgress(task: Task, progress: Int) { task.onProgressUpdate(progress) }
            override fun addListener(listener: TaskRunner.Listener) {}
            override fun removeListener(listener: TaskRunner.Listener) {}
            override suspend fun await() {}
        }
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        val listener = mock<HabitCardListCache.Listener>()
        cache.setListener(listener)
        cache.setCheckmarkCount(10)
        assertFalse(cache.hasLoaded)
        cache.refreshAllHabits()
        runBlocking { captured.last().doInBackground() }
        assertFalse(cache.hasLoaded)
        captured.last().onPostExecute()
        assertTrue(cache.hasLoaded)
        val previousCount = cache.habitCount
        resetCalls(listener)
        cache.effectiveSnapshots = {
            throw org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException()
        }
        cache.refreshAllHabits()
        runBlocking { captured.last().doInBackground() }
        captured.last().onProgressUpdate(-1)
        captured.last().onPostExecute()
        assertEquals(previousCount, cache.habitCount)
        verifyNoMoreCalls(listener)

        cache.effectiveSnapshots = null
        cache.refreshAllHabits()
        runBlocking { captured.last().doInBackground() }
        captured.last().onPostExecute()
        verify { listener.onRefreshFinished() }

        cache.effectiveSnapshots = { throw IllegalStateException("unrelated failure") }
        cache.refreshAllHabits()
        assertFailsWith<IllegalStateException> { runBlocking { captured.last().doInBackground() } }
    }

    @Test
    fun cancellationIsNonblockingButShutdownDrainsTheInFlightReader() {
        habitList.add(fixtures.createShortHabit())
        val captured = mutableListOf<Task>()
        val runner = object : TaskRunner {
            override val activeTaskCount get() = captured.size
            override fun execute(task: Task) { captured.add(task); task.onAttached(this) }
            override fun publishProgress(task: Task, progress: Int) { task.onProgressUpdate(progress) }
            override fun addListener(listener: TaskRunner.Listener) {}
            override fun removeListener(listener: TaskRunner.Listener) {}
            override suspend fun await() {}
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        val listener = mock<HabitCardListCache.Listener>()
        cache.setListener(listener)
        cache.setCheckmarkCount(10)
        cache.effectiveSnapshots = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            emptyMap()
        }
        cache.refreshAllHabits()
        val task = captured.single()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val read = executor.submit { runBlocking { task.doInBackground() } }
            check(entered.await(5, TimeUnit.SECONDS))
            // A queued UI progress callback must never wait for the reader monitor.
            // Previously this call blocked until the entire snapshot/SQL batch completed.
            executor.submit { task.onProgressUpdate(-1) }.get(100, TimeUnit.MILLISECONDS)
            // Must finish while the reader still holds the RefreshTask monitor.
            executor.submit { cache.cancelTasks() }.get(2, TimeUnit.SECONDS)
            cache.refreshHabit(habitList.getByPosition(0).id!!)
            val queuedTask = captured.last()
            val shutdownEntered = CountDownLatch(1)
            val shutdown = executor.submit {
                shutdownEntered.countDown()
                cache.shutdown()
            }
            check(shutdownEntered.await(2, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { shutdown.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            read.get(5, TimeUnit.SECONDS)
            shutdown.get(5, TimeUnit.SECONDS)
            cache.effectiveSnapshots = { error("Queued task read after shutdown") }
            runBlocking { queuedTask.doInBackground() }
            queuedTask.onPostExecute()
            task.onProgressUpdate(-1)
            task.onPostExecute()
            verifyNoMoreCalls(listener)
            val taskCount = captured.size
            cache.refreshAllHabits()
            cache.refreshHabit(habitList.getByPosition(0).id!!)
            assertEquals(taskCount, captured.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
