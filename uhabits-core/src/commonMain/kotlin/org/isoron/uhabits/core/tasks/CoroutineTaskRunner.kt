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
package org.isoron.uhabits.core.tasks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.platform.Synchronized

class CoroutineTaskRunner(
    mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher
) : TaskRunner {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val listeners = mutableListOf<TaskRunner.Listener>()
    private val jobs = mutableListOf<Job>()
    private var activeCount = 0

    override val activeTaskCount: Int
        @Synchronized get() = activeCount

    @Synchronized
    override fun addListener(listener: TaskRunner.Listener) {
        listeners.add(listener)
    }

    @Synchronized
    override fun removeListener(listener: TaskRunner.Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun addJob(job: Job) {
        jobs.add(job)
    }

    @Synchronized
    private fun removeJob(job: Job) {
        jobs.remove(job)
    }

    @Synchronized
    private fun getJobsSnapshot(): List<Job> = jobs.toList()

    @Synchronized
    private fun clearJobs(completed: List<Job>) {
        jobs.removeAll(completed.toSet())
    }

    @Synchronized
    private fun incrementActive(): List<TaskRunner.Listener> {
        activeCount++
        return listeners.toList()
    }

    @Synchronized
    private fun decrementActive(): List<TaskRunner.Listener> {
        activeCount--
        return listeners.toList()
    }

    override fun execute(task: Task) {
        task.onAttached(this)
        val job = scope.launch {
            val startListeners = incrementActive()
            startListeners.forEach { it.onTaskStarted(task) }
            try {
                task.onPreExecute()
                if (!task.isCanceled()) {
                    withContext(ioDispatcher) {
                        task.doInBackground()
                    }
                }
                task.onPostExecute()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
            } finally {
                val finishListeners = decrementActive()
                finishListeners.forEach { it.onTaskFinished(task) }
            }
        }
        job.invokeOnCompletion { removeJob(job) }
        addJob(job)
    }

    override suspend fun await() {
        val snapshot = getJobsSnapshot()
        snapshot.joinAll()
        clearJobs(snapshot)
    }

    override fun publishProgress(task: Task, progress: Int) {
        scope.launch {
            task.onProgressUpdate(progress)
        }
    }
}
