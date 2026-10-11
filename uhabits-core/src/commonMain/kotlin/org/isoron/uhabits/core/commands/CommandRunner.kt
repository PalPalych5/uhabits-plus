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
package org.isoron.uhabits.core.commands

import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import kotlin.time.TimeSource

@AppScope
@Inject
open class CommandRunner(
    private val taskRunner: TaskRunner
) {
    private val listeners: MutableList<Listener> = mutableListOf()
    /** QA-only timing hook; disabled in normal operation and contains no record contents. */
    open var timingObserver: ((String, Double) -> Unit)? = null

    open fun run(command: Command) {
        val requested = TimeSource.Monotonic.markNow()
        taskRunner.execute(
            object : Task {
                override suspend fun doInBackground() {
                    if (command is CreateRepetitionCommand) command.timingObserver = timingObserver
                    timingObserver?.invoke("command.queue", requested.elapsedNow().inWholeNanoseconds / 1e6)
                    val started = TimeSource.Monotonic.markNow()
                    command.run()
                    timingObserver?.invoke("command.persistence", started.elapsedNow().inWholeNanoseconds / 1e6)
                }
                override fun onPostExecute() {
                    notifyListeners(command)
                    timingObserver?.invoke("command.finished", requested.elapsedNow().inWholeNanoseconds / 1e6)
                }
            }
        )
    }

    open fun addListener(l: Listener) {
        listeners.add(l)
    }

    open fun notifyListeners(command: Command) {
        for (l in listeners.toList()) {
            val started = TimeSource.Monotonic.markNow()
            l.onCommandFinished(command)
            timingObserver?.invoke("observer.${l::class.simpleName}", started.elapsedNow().inWholeNanoseconds / 1e6)
        }
    }

    open fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    interface Listener {
        fun onCommandFinished(command: Command)
    }
}
