package org.isoron.uhabits.core.commands

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HabitEditTraceTest {
    private class CapturingRunner : TaskRunner {
        val tasks = mutableListOf<Task>()
        override val activeTaskCount get() = tasks.size
        override fun addListener(listener: TaskRunner.Listener) {}
        override fun removeListener(listener: TaskRunner.Listener) {}
        override fun execute(task: Task) { tasks.add(task) }
        override fun publishProgress(task: Task, progress: Int) {}
        override suspend fun await() {}
    }

    @Test fun successSeparatesExecutionFromTerminalAndIgnoresObserverFailure() = runTest {
        val tasks = CapturingRunner()
        val events = mutableListOf<String>()
        val trace = HabitEditTrace(1) { _, stage, _, _, _ ->
            events.add(stage)
            if (stage == "command.fifoStart") error("observer failure")
        }
        var mutations = 0
        CommandRunner(tasks).run(object : Command { override fun run() { mutations++ } }, trace)
        tasks.tasks.single().doInBackground()
        assertEquals(1, mutations)
        assertEquals(listOf("command.accepted", "command.enqueued", "command.worker", "command.fifoStart", "command.executed"), events)
        tasks.tasks.single().onPostExecute()
        tasks.tasks.single().onCompletion(null)
        assertEquals(1, events.count { it == "command.finished" })
        assertEquals(0, events.count { it == "sqlite.durable" })
    }

    @Test fun failuresCancellationAndListenerFailureHaveOneTerminal() = runTest {
        for (cancel in listOf(false, true)) {
            val tasks = CapturingRunner()
            val events = mutableListOf<String>()
            val failure = if (cancel) CancellationException("controlled") else IllegalStateException("controlled")
            CommandRunner(tasks).run(object : Command { override fun run() { throw failure } },
                HabitEditTrace(1) { _, stage, _, _, _ -> events.add(stage) })
            try { tasks.tasks.single().doInBackground() } catch (_: Throwable) {}
            tasks.tasks.single().onCompletion(failure)
            assertEquals(listOf(if (cancel) "command.cancelled" else "command.failed"), events.filter { it in terminalStages })
        }
        val tasks = CapturingRunner()
        val events = mutableListOf<String>()
        val commands = CommandRunner(tasks)
        commands.addListener(object : CommandRunner.Listener {
            override fun onCommandFinished(command: Command) { error("listener failure") }
        })
        commands.run(object : Command { override fun run() {} }, HabitEditTrace(2) { _, stage, _, _, _ -> events.add(stage) })
        tasks.tasks.single().doInBackground()
        assertFailsWith<IllegalStateException> { tasks.tasks.single().onPostExecute() }
        tasks.tasks.single().onCompletion(null)
        assertEquals(listOf("command.failed"), events.filter { it in terminalStages })
    }

    @Test fun reverseWorkerDispatchPreservesFifo() = runTest {
        val tasks = CapturingRunner()
        val commands = CommandRunner(tasks)
        val order = mutableListOf<Int>()
        for (ordinal in 1..3) commands.run(object : Command { override fun run() { order.add(ordinal) } })
        val third = async { tasks.tasks[2].doInBackground() }
        val second = async { tasks.tasks[1].doInBackground() }
        tasks.tasks[0].doInBackground()
        second.await()
        third.await()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test fun cancellationBeforeWorkerHasTerminalWithoutExecution() {
        val tasks = CapturingRunner()
        val events = mutableListOf<String>()
        CommandRunner(tasks).run(object : Command { override fun run() { error("must not execute") } },
            HabitEditTrace(1) { _, stage, _, _, _ -> events.add(stage) })
        tasks.tasks.single().onCompletion(CancellationException("before worker"))
        assertEquals(listOf("command.accepted", "command.enqueued", "command.cancelled"), events)
    }

    private val terminalStages = setOf("command.finished", "command.failed", "command.cancelled")
}
