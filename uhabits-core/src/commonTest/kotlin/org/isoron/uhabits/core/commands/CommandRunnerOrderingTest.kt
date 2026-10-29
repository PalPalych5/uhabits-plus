package org.isoron.uhabits.core.commands

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandRunnerOrderingTest {
    private class CapturingRunner : TaskRunner {
        val tasks = mutableListOf<Task>()
        override val activeTaskCount get() = tasks.size
        override fun execute(task: Task) { tasks.add(task); task.onAttached(this) }
        override fun publishProgress(task: Task, progress: Int) {}
        override fun addListener(listener: TaskRunner.Listener) {}
        override fun removeListener(listener: TaskRunner.Listener) {}
        override suspend fun await() {}
    }

    @Test fun reverseWorkerSchedulingPreservesSubmissionOrder() = runTest {
        val tasks = CapturingRunner()
        val runner = CommandRunner(tasks)
        val persisted = mutableListOf<Int>()
        runner.run(object : Command { override fun run() { persisted.add(1) } })
        runner.run(object : Command { override fun run() { persisted.add(2) } })
        val second = async { tasks.tasks[1].doInBackground() }
        runCurrent()
        assertEquals(emptyList(), persisted)
        tasks.tasks[0].doInBackground()
        second.await()
        assertEquals(listOf(1, 2), persisted)
    }

    @Test fun failedCommandReleasesFollowingCommands() = runTest {
        val tasks = CapturingRunner()
        val runner = CommandRunner(tasks)
        var persisted = false
        runner.run(object : Command { override fun run() { error("failed write") } })
        runner.run(object : Command { override fun run() { persisted = true } })
        assertFailsWith<IllegalStateException> { tasks.tasks[0].doInBackground() }
        tasks.tasks[1].doInBackground()
        assertEquals(true, persisted)
    }

    @Test fun completionIsDeliveredOnceAfterTaskFinishes() = runTest {
        val tasks = CapturingRunner()
        val runner = CommandRunner(tasks)
        var persisted = false
        val results = mutableListOf<Throwable?>()
        runner.runWithCompletion(object : Command { override fun run() { persisted = true } }) { results.add(it) }
        assertEquals(0, results.size)
        tasks.tasks.single().doInBackground()
        assertEquals(true, persisted)
        assertEquals(0, results.size)
        tasks.tasks.single().onPostExecute()
        tasks.tasks.single().onCompletion(null)
        tasks.tasks.single().onCompletion(null)
        assertEquals(listOf<Throwable?>(null), results)
    }

    @Test fun cancellationBeforeExecutionReleasesQueueAndReportsFailure() = runTest {
        val tasks = CapturingRunner()
        val runner = CommandRunner(tasks)
        val failure = IllegalStateException("cancelled")
        val results = mutableListOf<Throwable?>()
        runner.runWithCompletion(object : Command { override fun run() {} }) { results.add(it) }
        var persisted = false
        runner.run(object : Command { override fun run() { persisted = true } })
        tasks.tasks[0].onCompletion(failure)
        tasks.tasks[0].onCompletion(null)
        tasks.tasks[1].doInBackground()
        assertEquals(listOf<Throwable?>(failure), results)
        assertEquals(true, persisted)
    }
}
