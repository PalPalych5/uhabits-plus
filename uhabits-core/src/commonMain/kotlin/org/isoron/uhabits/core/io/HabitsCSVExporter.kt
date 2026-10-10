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
package org.isoron.uhabits.core.io

import org.isoron.platform.io.ZipWriter
import org.isoron.platform.io.csvLine
import org.isoron.platform.io.format
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.EntryList
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import kotlin.math.min

/**
 * Class that exports the application data to CSV files inside a ZIP archive.
 */
class HabitsCSVExporter(
    private val allHabits: HabitList,
    private val selectedHabits: List<Habit>,
    private val snapshotProvider: ((List<Habit>, LocalDate, LocalDate) -> Map<HabitRef, EffectiveHabitReadSnapshot>)? = null
) {
    private val delimiter = ","

    suspend fun writeArchive(): ByteArray {
        val zip = ZipWriter()
        zip.addEntry("Habits.csv", allHabits.writeCSV())

        val legacyTimeframe = getTimeframe()
        val legacyOldest = legacyTimeframe[0]
        val legacyNewest = legacyTimeframe[1]
        val today = getToday()

        val requestFrom = if (legacyOldest.year > 2500) today.minus(365) else minOf(legacyOldest, today.minus(365))
        val requestTo = if (legacyNewest.year < 2000) today else maxOf(legacyNewest, today)

        val snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = if (snapshotProvider != null && selectedHabits.isNotEmpty()) {
            val habitsWithUuid = selectedHabits.filter { it.uuid != null }
            if (habitsWithUuid.isNotEmpty()) {
                var result: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
                var attempts = 0
                while (attempts < 3 && result == null) {
                    try {
                        result = snapshotProvider.invoke(habitsWithUuid, requestFrom, requestTo)
                    } catch (e: Exception) {
                        attempts++
                        if (attempts >= 3) throw e
                    }
                }
                result
            } else null
        } else null

        val hasAnyContributions = snapshots?.values?.any { it.hasHistoricalContributions } ?: false

        var effectiveOldest = legacyOldest
        var effectiveNewest = legacyNewest
        if (hasAnyContributions && snapshots != null) {
            val earliestObserved = snapshots.values.mapNotNull { it.observedFrom }.minOrNull()
            if (earliestObserved != null) {
                effectiveOldest = if (effectiveOldest.year > 2500) earliestObserved else minOf(effectiveOldest, earliestObserved)
            }
            val latestObserved = snapshots.values.mapNotNull { it.observedTo }.maxOrNull()
            if (latestObserved != null) {
                effectiveNewest = if (effectiveNewest.year < 2000) latestObserved else maxOf(effectiveNewest, latestObserved)
            }
        }

        for (h in selectedHabits) {
            val habitDirName = habitDirName(h)
            zip.addEntry("${habitDirName}Scores.csv", writeScores(h))
            zip.addEntry("${habitDirName}Checkmarks.csv", writeEntries(h.computedEntries))

            if (hasAnyContributions && snapshots != null) {
                val ref = h.uuid?.let { HabitRef(it) }
                val snapshot = ref?.let { snapshots[it] }
                if (snapshot != null) {
                    zip.addEntry("${habitDirName}Effective.csv", writeEffectiveEntries(h, snapshot))
                    zip.addEntry("${habitDirName}EffectiveScores.csv", writeEffectiveScores(h, snapshot, effectiveOldest, today))
                }
            }
        }

        zip.addEntry("Scores.csv", writeMultipleHabitsScores(legacyOldest, today))
        zip.addEntry("Checkmarks.csv", writeMultipleHabitsCheckmarks(legacyOldest, today))

        if (hasAnyContributions && snapshots != null) {
            zip.addEntry("Effective.csv", writeMultipleHabitsEffective(effectiveOldest, today, snapshots))
            zip.addEntry("EffectiveScores.csv", writeMultipleHabitsEffectiveScores(effectiveOldest, today, snapshots))
        }

        return zip.toBytes()
    }

    private fun habitDirName(h: Habit): String {
        val sane = sanitizeFilename(h.name)
        return format("%03d", allHabits.indexOf(h) + 1) + " " + sane.trim() + "/"
    }

    private fun sanitizeFilename(name: String): String {
        val s = name.replace("[^ a-zA-Z0-9._\\p{L}\\p{N}-]+".toRegex(), "")
        val truncated = s.substring(0, min(s.length, 100)).trim()
        return if (truncated.isEmpty()) "Habit" else truncated
    }

    private fun writeScores(habit: Habit): String {
        val sb = StringBuilder()
        val today = getToday()
        var oldest = today
        val known = habit.computedEntries.getKnown()
        if (known.isNotEmpty()) oldest = known[known.size - 1].date
        sb.append(csvLine(arrayOf("Date", "Score")))
        for (s in habit.scores.getByInterval(oldest, today)) {
            sb.append(csvLine(arrayOf(s.date.toCSVString(), format("%.4f", s.value))))
        }
        return sb.toString()
    }

    private fun writeEntries(entries: EntryList): String {
        val sb = StringBuilder()
        sb.append(csvLine(arrayOf("Date", "Value", "Notes")))
        for (entry in entries.getKnown()) {
            sb.append(csvLine(arrayOf(entry.date.toCSVString(), entry.formattedValue, entry.notes)))
        }
        return sb.toString()
    }

    private fun writeEffectiveEntries(habit: Habit, snapshot: EffectiveHabitReadSnapshot): String {
        val sb = StringBuilder()
        val header = arrayOf(
            "Date",
            "ManualValue",
            "ContributionValue",
            "TombstonedValue",
            "EffectiveValue",
            "EffectiveLowerBound",
            "Coverage",
            "GoalStatus",
            "Unit",
            "Notes"
        )
        sb.append(csvLine(header))

        val observedDays = snapshot.days.filter { day ->
            day.manualEntry.value != Entry.UNKNOWN ||
                day.contributions.isNotEmpty() ||
                day.tombstonedContributions.isNotEmpty()
        }.sortedBy { it.manualEntry.date }.reversed()

        for (day in observedDays) {
            val dateStr = day.manualEntry.date.toCSVString()
            val manualStr = if (!habit.isNumerical) {
                day.manualEntry.formattedValue
            } else {
                when {
                    day.manualEntry.value == Entry.SKIP -> "SKIP"
                    day.manualEntry.value == Entry.UNKNOWN -> "UNKNOWN"
                    day.manualValue != null -> format("%.1f", day.manualValue / 1000.0)
                    else -> "UNKNOWN"
                }
            }

            val contribStr = if (habit.isNumerical) {
                format("%.1f", day.contributionValue / 1000.0)
            } else {
                "0.0"
            }

            val tombstonedStr = if (habit.isNumerical) {
                format("%.1f", day.tombstonedContributionValue / 1000.0)
            } else {
                "0.0"
            }

            val effectiveStr = if (!habit.isNumerical) {
                day.manualEntry.formattedValue
            } else {
                when {
                    day.isSkipped -> "SKIP"
                    !day.hasCompleteCoverage -> "UNKNOWN"
                    day.totalValue != null -> format("%.1f", day.totalValue / 1000.0)
                    else -> "UNKNOWN"
                }
            }

            val lowerBoundStr = if (habit.isNumerical) {
                format("%.1f", day.effectiveLowerBound / 1000.0)
            } else {
                ""
            }

            val coverageStr = if (day.hasCompleteCoverage) "COMPLETE" else "INCOMPLETE"
            val goalStatusStr = snapshot.goalAchievementOn(day.manualEntry.date).status.name
            val unitStr = if (habit.isNumerical) habit.unit else ""
            val notesStr = day.manualEntry.notes

            sb.append(csvLine(arrayOf(
                dateStr,
                manualStr,
                contribStr,
                tombstonedStr,
                effectiveStr,
                lowerBoundStr,
                coverageStr,
                goalStatusStr,
                unitStr,
                notesStr
            )))
        }
        return sb.toString()
    }

    private fun writeEffectiveScores(
        habit: Habit,
        snapshot: EffectiveHabitReadSnapshot,
        oldest: LocalDate,
        newest: LocalDate
    ): String {
        val sb = StringBuilder()
        sb.append(csvLine(arrayOf("Date", "Score")))
        val hOldest = minOf(snapshot.observedFrom ?: oldest, oldest)
        val days = hOldest.daysUntil(newest)
        if (days >= 0) {
            for (i in 0..days) {
                val date = hOldest.plus(i)
                val scoreStr = if (snapshot.scoreResult.scores == null) {
                    "UNAVAILABLE"
                } else {
                    val score = snapshot.scoreResult.on(date)
                    if (score != null) format("%.4f", score) else "UNAVAILABLE"
                }
                sb.append(csvLine(arrayOf(date.toCSVString(), scoreStr)))
            }
        }
        return sb.toString()
    }

    private fun writeMultipleHabitsScores(oldest: LocalDate, newest: LocalDate): String {
        val sb = StringBuilder()
        writeMultipleHabitsHeader(sb)
        val scores = selectedHabits.map { ArrayList(it.scores.getByInterval(oldest, newest)) }
        val days = oldest.daysUntil(newest)
        if (days >= 0) {
            for (i in 0..days) {
                val date = newest.minus(i).toCSVString()
                sb.append(date).append(delimiter)
                for (j in selectedHabits.indices) {
                    val score = format("%.4f", scores[j][i].value)
                    sb.append(score).append(delimiter)
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun writeMultipleHabitsCheckmarks(oldest: LocalDate, newest: LocalDate): String {
        val sb = StringBuilder()
        writeMultipleHabitsHeader(sb)
        val checkmarks = selectedHabits.map { ArrayList(it.computedEntries.getByInterval(oldest, newest)) }
        val days = oldest.daysUntil(newest)
        if (days >= 0) {
            for (i in 0..days) {
                val date = newest.minus(i).toCSVString()
                sb.append(date).append(delimiter)
                for (j in selectedHabits.indices) {
                    sb.append(escapeCsvField(checkmarks[j][i].formattedValue)).append(delimiter)
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun writeMultipleHabitsEffective(
        oldest: LocalDate,
        newest: LocalDate,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>
    ): String {
        val sb = StringBuilder()
        writeMultipleHabitsHeader(sb)
        val days = oldest.daysUntil(newest)
        if (days >= 0) {
            for (i in 0..days) {
                val date = newest.minus(i)
                sb.append(date.toCSVString()).append(delimiter)
                for (habit in selectedHabits) {
                    val ref = habit.uuid?.let { HabitRef(it) }
                    val snapshot = ref?.let { snapshots[it] }
                    val cellValue = if (snapshot != null) {
                        val day = snapshot.dayOrNull(date)
                        if (day == null) {
                            "UNKNOWN"
                        } else if (!habit.isNumerical) {
                            day.manualEntry.formattedValue
                        } else {
                            when {
                                day.isSkipped -> "SKIP"
                                !day.hasCompleteCoverage && day.effectiveLowerBound > 0 -> ">= " + format("%.1f", day.effectiveLowerBound / 1000.0)
                                !day.hasCompleteCoverage -> "UNKNOWN"
                                day.totalValue != null -> format("%.1f", day.totalValue / 1000.0)
                                else -> "UNKNOWN"
                            }
                        }
                    } else {
                        val entry = habit.computedEntries.get(date)
                        entry.formattedValue
                    }
                    sb.append(escapeCsvField(cellValue)).append(delimiter)
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun writeMultipleHabitsEffectiveScores(
        oldest: LocalDate,
        newest: LocalDate,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>
    ): String {
        val sb = StringBuilder()
        writeMultipleHabitsHeader(sb)
        val days = oldest.daysUntil(newest)
        if (days >= 0) {
            for (i in 0..days) {
                val date = newest.minus(i)
                sb.append(date.toCSVString()).append(delimiter)
                for (habit in selectedHabits) {
                    val ref = habit.uuid?.let { HabitRef(it) }
                    val snapshot = ref?.let { snapshots[it] }
                    val cellValue = if (snapshot != null) {
                        if (snapshot.scoreResult.scores == null) {
                            "UNAVAILABLE"
                        } else {
                            val score = snapshot.scoreResult.on(date)
                            if (score != null) format("%.4f", score) else "UNAVAILABLE"
                        }
                    } else {
                        "UNAVAILABLE"
                    }
                    sb.append(escapeCsvField(cellValue)).append(delimiter)
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun writeMultipleHabitsHeader(sb: StringBuilder) {
        sb.append("Date$delimiter")
        for (habit in selectedHabits) {
            sb.append(escapeCsvField(habit.name)).append(delimiter)
        }
        sb.append("\n")
    }

    private fun escapeCsvField(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }

    private fun getTimeframe(): Array<LocalDate> {
        var oldest = LocalDate(1000000)
        var newest = LocalDate(0)
        for (habit in selectedHabits) {
            val entries = habit.originalEntries.getKnown()
            if (entries.isEmpty()) continue
            val currNew = entries[0].date
            val currOld = entries[entries.size - 1].date
            oldest = if (currOld.isOlderThan(oldest)) currOld else oldest
            newest = if (currNew.isNewerThan(newest)) currNew else newest
        }
        return arrayOf(oldest, newest)
    }
}
