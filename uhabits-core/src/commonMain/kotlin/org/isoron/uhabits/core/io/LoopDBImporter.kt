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

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.io.Database
import org.isoron.platform.io.DatabaseOpener
import org.isoron.platform.io.FileOpener
import org.isoron.platform.io.UserFile
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.query
import org.isoron.platform.io.querySingle
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.utils.isSQLite3File

/**
 * Class that imports data from database files exported by Loop Habit Tracker.
 */
@Inject
class LoopDBImporter(
    @AppScope val habitList: HabitList,
    @AppScope val modelFactory: ModelFactory,
    @AppScope val opener: DatabaseOpener,
    @AppScope val runner: CommandRunner,
    @AppScope logging: Logging,
    @AppScope val fileOpener: FileOpener
) : AbstractImporter() {

    private val logger = logging.getLogger("LoopDBImporter")

    override suspend fun canHandle(file: UserFile): Boolean {
        if (!isSQLite3File(file)) return false
        val db = opener.open(file.pathString)
        var canHandle = true
        val count = db.querySingle(
            "select count(*) from SQLITE_MASTER where name='Habits' or name='Repetitions'"
        ) { it.getInt(0) }
        if (count == null || count != 2) {
            logger.error("Cannot handle file: tables not found")
            canHandle = false
        }
        if (db.getVersion() > DATABASE_VERSION) {
            logger.error("Cannot handle file: incompatible version: ${db.getVersion()} > $DATABASE_VERSION")
            canHandle = false
        }
        db.close()
        return canHandle
    }

    override suspend fun importHabitsFromFile(file: UserFile) {
        val db = opener.open(file.pathString)
        db.migrateTo(DATABASE_VERSION) { version ->
            val filename = org.isoron.platform.io.format("%02d.sql", version)
            fileOpener.openResourceFile("migrations/$filename").lines().joinToString("\n")
        }

        val globalStatsStart = loadGlobalStatsStart(db)
        habitList.globalStatisticsStartDate = globalStatsStart
        val habitDataList = loadHabits(db)
        for (habitData in habitDataList) {
            var habit = habitList.getByUUID(habitData.uuid)

            if (habit == null) {
                habit = modelFactory.buildHabit()
                val imported = habitData.copy(id = null)
                SQLiteHabitList.copyTo(imported, habit)
                CreateHabitCommand(modelFactory, habitList, habit).run()
            } else {
                val modified = modelFactory.buildHabit()
                SQLiteHabitList.copyTo(habitData.copy(id = habit.id), modified)
                EditHabitCommand(habitList, habit.id!!, modified).run()
            }

            habit = habitList.getByUUID(habitData.uuid)!!
            habit.goalHistory = loadGoalHistory(db, habitData.id!!).toMutableList()
            habit.statisticsStartDate = loadHabitStatsStart(db, habitData.id!!)
            habit.globalStatisticsStartDate = globalStatsStart
            val entries = habit.originalEntries

            db.query(
                "SELECT timestamp, value, notes FROM Repetitions WHERE habit = ? ORDER BY timestamp DESC",
                habitData.id.toString()
            ) { stmt ->
                val timestamp = stmt.getLongOrNull(0) ?: return@query
                val value = stmt.getIntOrNull(1) ?: return@query
                val notes = stmt.getTextOrNull(2) ?: ""
                val date = LocalDate.fromUnixTime(timestamp)
                val (_, existingValue, existingNotes) = entries.get(date)
                if (existingValue != value || existingNotes != notes) {
                    entries.add(Entry(date, value, notes))
                }
            }
            habitList.update(habit)
            habit.recompute()
        }
        habitList.resort()
        db.close()
    }

    private fun loadGlobalStatsStart(db: Database): LocalDate? {
        val hasTable = db.querySingle(
            "select count(*) from sqlite_master where type='table' and name='AppSettings'"
        ) { it.getInt(0) } ?: 0
        if (hasTable == 0) return null
        val millis = db.querySingle(
            "select long_value from AppSettings where key = 'global_stats_start_timestamp'"
        ) { it.getLongOrNull(0) }
        return millis?.let(LocalDate::fromUnixTime)
    }

    private fun loadHabitStatsStart(db: Database, habitId: Long): LocalDate? {
        val millis = db.querySingle(
            "select stats_start_timestamp from HabitExtensions where habit_id = ?",
            habitId.toString()
        ) { it.getLongOrNull(0) }
        return millis?.let(LocalDate::fromUnixTime)
    }

    private fun loadGoalHistory(db: Database, habitId: Long): List<HabitGoal> {
        val hasTable = db.querySingle(
            "select count(*) from sqlite_master where type='table' and name='HabitGoals'"
        ) { it.getInt(0) } ?: 0
        if (hasTable == 0) return emptyList()
        val goals = mutableListOf<HabitGoal>()
        db.query(
            """SELECT effective_timestamp, freq_num, freq_den, target_type, target_value, unit
               FROM HabitGoals WHERE habit_id = ? ORDER BY effective_timestamp ASC""",
            habitId.toString()
        ) { stmt ->
            goals.add(
                HabitGoal(
                    effectiveDate = LocalDate.fromUnixTime(stmt.getLong(0)),
                    frequency = Frequency(stmt.getInt(1), stmt.getInt(2)),
                    targetType = NumericalHabitType.fromInt(stmt.getInt(3)),
                    targetValue = stmt.getReal(4),
                    unit = stmt.getTextOrNull(5) ?: ""
                )
            )
        }
        return goals
    }

    private fun loadHabits(db: Database): List<HabitData> {
        val result = mutableListOf<HabitData>()
        db.query(
            "SELECT id, name, description, question, freq_num, freq_den, color, " +
                "position, reminder_hour, reminder_min, reminder_days, highlight, " +
                "archived, type, target_value, target_type, unit, uuid " +
                "FROM Habits ORDER BY position"
        ) { stmt ->
            result.add(
                HabitData(
                    id = stmt.getLongOrNull(0),
                    name = stmt.getTextOrNull(1) ?: "",
                    description = stmt.getTextOrNull(2) ?: "",
                    question = stmt.getTextOrNull(3) ?: "",
                    freqNum = stmt.getIntOrNull(4) ?: 1,
                    freqDen = stmt.getIntOrNull(5) ?: 1,
                    color = stmt.getIntOrNull(6) ?: 0,
                    position = stmt.getIntOrNull(7) ?: 0,
                    reminderHour = stmt.getIntOrNull(8),
                    reminderMin = stmt.getIntOrNull(9),
                    reminderDays = stmt.getIntOrNull(10) ?: 0,
                    highlight = stmt.getIntOrNull(11) ?: 0,
                    archived = stmt.getIntOrNull(12) ?: 0,
                    type = stmt.getIntOrNull(13) ?: 0,
                    targetValue = stmt.getRealOrNull(14) ?: 0.0,
                    targetType = stmt.getIntOrNull(15) ?: 0,
                    unit = stmt.getTextOrNull(16) ?: "",
                    uuid = stmt.getTextOrNull(17)
                )
            )
        }
        return result
    }
}
