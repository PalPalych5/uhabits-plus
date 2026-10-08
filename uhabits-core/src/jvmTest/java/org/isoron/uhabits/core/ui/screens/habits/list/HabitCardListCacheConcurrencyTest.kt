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

class HabitCardListCacheConcurrencyTest : BaseUnitTest() {
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
        cache.refreshAllHabits()
        runBlocking { captured.last().doInBackground() }
        captured.last().onPostExecute()
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
