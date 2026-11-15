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

import org.isoron.platform.io.runInTransaction
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.ContributionRepository
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.isoron.uhabits.core.models.projection.HabitDayAdjustment
import org.isoron.uhabits.core.models.projection.HabitDayAdjustmentRepository
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import kotlin.time.TimeSource

data class CreateRepetitionCommand(
    val habitList: HabitList,
    val habit: Habit,
    val date: LocalDate,
    val value: Int,
    val notes: String,
    val isTotalEdit: Boolean = false,
    val hourOffset: Int = 0,
    val minuteOffset: Int = 0,
    val contributionRepository: ContributionRepository? = null,
    val adjustmentRepository: HabitDayAdjustmentRepository? = null,
    val nowProvider: (() -> Long)? = null,
) : Command {
    var timingObserver: ((String, Double) -> Unit)? = null
    var trace: HabitEditTrace? = null
    private inline fun timed(stage: String, action: () -> Unit) {
        trace?.event("$stage.begin")
        try {
            val observer = timingObserver
            if (observer == null) { action(); return }
            val started = TimeSource.Monotonic.markNow()
            action()
            observer(stage, started.elapsedNow().inWholeNanoseconds / 1e6)
        } finally { trace?.event("$stage.end") }
    }
    override fun run() {
        val entries = habit.originalEntries
        val previous = entries.get(date)
        val sqlList = habitList as? SQLiteHabitList
        val db = runCatching { sqlList?.sqlModelFactory?.database }.getOrNull()
        val resolvedContribRepo = contributionRepository
            ?: sqlList?.sqlModelFactory?.contributionRepository?.takeIf { db == null || ContributionSchema.tableExists(db) }
        val resolvedAdjRepo = adjustmentRepository
            ?: sqlList?.sqlModelFactory?.habitDayAdjustmentRepository
        val habitRef = habit.uuid?.let { HabitRef(it) }

        val targetManualValue: Int
        val targetAdjustmentMillis: Long
        if (isTotalEdit && habit.isNumerical) {
            if (value == Entry.UNKNOWN || value == Entry.SKIP) {
                targetManualValue = value
                targetAdjustmentMillis = 0L
            } else {
                require(value in 0..Entry.MAX_SCALED_VALUE) {
                    "Numerical habit repetition value out of bounds: $value"
                }
                val activeFocusMillis = if (habitRef != null && resolvedContribRepo != null) {
                    val startTs = startOfLocalDay(date, hourOffset, minuteOffset)
                    val endTs = startOfLocalDay(date.plus(1), hourOffset, minuteOffset) - 1L
                    resolvedContribRepo
                        .getByHabitInRange(habitRef, startTs, endTs, includeRemoved = false)
                        .sumOf { it.contributedValue }
                        .coerceAtLeast(0L)
                } else 0L
                val desiredTotal = value.toLong()
                if (desiredTotal >= activeFocusMillis) {
                    targetManualValue = (desiredTotal - activeFocusMillis)
                        .coerceIn(0L, Entry.MAX_SCALED_VALUE.toLong())
                        .toInt()
                    targetAdjustmentMillis = 0L
                } else {
                    targetManualValue = 0
                    targetAdjustmentMillis = desiredTotal - activeFocusMillis
                }
            }
        } else {
            targetManualValue = value
            targetAdjustmentMillis = 0L
        }

        val currentAdjustmentMillis = if (isTotalEdit && habit.isNumerical && habitRef != null && resolvedAdjRepo != null) {
            resolvedAdjRepo.getByHabitAndDate(habitRef, date)?.adjustmentMillis ?: 0L
        } else 0L

        if (previous.value == targetManualValue &&
            previous.notes == notes &&
            (!isTotalEdit || currentAdjustmentMillis == targetAdjustmentMillis)
        ) {
            trace?.event("repetition.noop")
            return
        }
        if (habit.isNumerical && targetManualValue != previous.value) {
            require(targetManualValue == Entry.UNKNOWN || targetManualValue == Entry.SKIP || (targetManualValue in 0..Entry.MAX_SCALED_VALUE)) {
                "Numerical habit repetition value out of bounds: $targetManualValue"
            }
        }
        timed("repetition.sqliteWrite") {
            val entry = Entry(date, targetManualValue, notes)
            val scope = habitRef?.let { setOf(it) }
            val writeAll = {
                val writeTx = {
                    if (entries is SQLiteEntryList) entries.addTraced(entry, trace) else entries.add(entry)
                    if (isTotalEdit && habit.isNumerical && habitRef != null && resolvedAdjRepo != null) {
                        if (targetAdjustmentMillis == 0L) {
                            resolvedAdjRepo.delete(habitRef, date)
                        } else {
                            val updatedAt = nowProvider?.invoke() ?: sqlList?.syncManager?.now() ?: 0L
                            resolvedAdjRepo.upsert(
                                HabitDayAdjustment(
                                    targetHabit = habitRef,
                                    date = date,
                                    adjustmentMillis = targetAdjustmentMillis,
                                    updatedAt = updatedAt,
                                )
                            )
                        }
                    }
                }
                try {
                    if (db != null && !db.inTransaction()) {
                        db.runInTransaction { writeTx() }
                    } else {
                        writeTx()
                    }
                } catch (t: Throwable) {
                    entries.invalidate()
                    throw t
                }
            }
            if (scope != null) {
                EffectiveReadRevision.withMutationScope(scope, writeAll)
            } else {
                writeAll()
            }
        }
        timed("repetition.modelRecompute") { habit.recompute() }
        timed("repetition.modelRefresh") {
            habit.id?.let { habitList.refreshHabitFromDatabase(it, habit) }
        }
        timed("repetition.resortAndObservers") {
            org.isoron.uhabits.core.io.HabitResortDiagnostics.collect(trace?.ordinal) { habitList.onEntriesChanged() }
        }
        val syncManager = sqlList?.syncManager ?: return
        timed("repetition.syncQueue") {
            if (targetManualValue == Entry.UNKNOWN) {
                syncManager.enqueueEntryDelete(habit, date)
            } else {
                syncManager.enqueueEntrySet(habit, date, targetManualValue, notes)
            }
        }
    }
}
