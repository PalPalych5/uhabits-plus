/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.core.models.projection

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.HabitRef

/**
 * Signed per-habit per-day adjustment in scaled x1000 units, preserving immutable
 * Focus/Contribution provenance while letting users edit any daily total (including below Focus).
 */
data class HabitDayAdjustment(
    val targetHabit: HabitRef,
    val date: LocalDate,
    val adjustmentMillis: Long,
    val updatedAt: Long,
)

object HabitDayAdjustmentSchema {
    fun createSchema(db: Database) {
        db.run(
            """
            CREATE TABLE IF NOT EXISTS HabitDayAdjustments (
                habit_uuid TEXT NOT NULL,
                date INTEGER NOT NULL,
                adjustment_millis INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (habit_uuid, date)
            );
            """.trimIndent()
        )
        db.run(
            "CREATE INDEX IF NOT EXISTS idx_habit_day_adjustments_habit_date ON HabitDayAdjustments(habit_uuid, date);"
        )
    }

    fun tableExists(db: Database): Boolean {
        val stmt = db.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'HabitDayAdjustments' LIMIT 1"
        )
        return try {
            stmt.step() == StepResult.ROW
        } finally {
            stmt.finalize()
        }
    }
}

interface HabitDayAdjustmentRepository {
    fun getByHabitAndDate(targetHabit: HabitRef, date: LocalDate): HabitDayAdjustment?
    fun getByHabitsInDateRange(
        targetHabits: Set<HabitRef>,
        from: LocalDate,
        to: LocalDate,
    ): Map<HabitRef, Map<LocalDate, HabitDayAdjustment>>
    fun upsert(adjustment: HabitDayAdjustment)
    fun delete(targetHabit: HabitRef, date: LocalDate)
    fun deleteByHabit(targetHabit: HabitRef)
}

class MemoryHabitDayAdjustmentRepository : HabitDayAdjustmentRepository {
    private val store = LinkedHashMap<Pair<HabitRef, LocalDate>, HabitDayAdjustment>()

    override fun getByHabitAndDate(targetHabit: HabitRef, date: LocalDate): HabitDayAdjustment? =
        store[targetHabit to date]

    override fun getByHabitsInDateRange(
        targetHabits: Set<HabitRef>,
        from: LocalDate,
        to: LocalDate,
    ): Map<HabitRef, Map<LocalDate, HabitDayAdjustment>> {
        if (targetHabits.isEmpty() || from > to) return emptyMap()
        val result = LinkedHashMap<HabitRef, MutableMap<LocalDate, HabitDayAdjustment>>()
        for ((key, adj) in store) {
            val (habit, date) = key
            if (habit in targetHabits && date >= from && date <= to) {
                result.getOrPut(habit) { LinkedHashMap() }[date] = adj
            }
        }
        return result
    }

    override fun upsert(adjustment: HabitDayAdjustment) {
        if (adjustment.adjustmentMillis == 0L) {
            store.remove(adjustment.targetHabit to adjustment.date)
        } else {
            store[adjustment.targetHabit to adjustment.date] = adjustment
        }
    }

    override fun delete(targetHabit: HabitRef, date: LocalDate) {
        store.remove(targetHabit to date)
    }

    override fun deleteByHabit(targetHabit: HabitRef) {
        val keys = store.keys.filter { it.first == targetHabit }
        keys.forEach { store.remove(it) }
    }
}

class SQLiteHabitDayAdjustmentRepository(private val db: Database) : HabitDayAdjustmentRepository {

    override fun getByHabitAndDate(targetHabit: HabitRef, date: LocalDate): HabitDayAdjustment? {
        if (!HabitDayAdjustmentSchema.tableExists(db)) return null
        val stmt = db.prepareStatement(
            "SELECT adjustment_millis, updated_at FROM HabitDayAdjustments WHERE habit_uuid = ? AND date = ?"
        )
        return try {
            stmt.bindText(1, targetHabit.uuid)
            stmt.bindInt(2, date.daysSince2000)
            if (stmt.step() == StepResult.ROW) {
                HabitDayAdjustment(
                    targetHabit = targetHabit,
                    date = date,
                    adjustmentMillis = stmt.getLong(0),
                    updatedAt = stmt.getLong(1),
                )
            } else {
                null
            }
        } finally {
            stmt.finalize()
        }
    }

    override fun getByHabitsInDateRange(
        targetHabits: Set<HabitRef>,
        from: LocalDate,
        to: LocalDate,
    ): Map<HabitRef, Map<LocalDate, HabitDayAdjustment>> {
        if (targetHabits.isEmpty() || from > to) return emptyMap()
        if (!HabitDayAdjustmentSchema.tableExists(db)) return emptyMap()
        val sortedHabits = targetHabits.toList().sortedBy { it.uuid }
        val result = LinkedHashMap<HabitRef, MutableMap<LocalDate, HabitDayAdjustment>>()
        // Chunk IN (...) safely below SQLite's max host parameter limit
        val chunkSize = 200
        for (startIdx in sortedHabits.indices step chunkSize) {
            val chunk = sortedHabits.subList(startIdx, minOf(startIdx + chunkSize, sortedHabits.size))
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            val stmt = db.prepareStatement(
                """
                SELECT habit_uuid, date, adjustment_millis, updated_at
                FROM HabitDayAdjustments
                WHERE habit_uuid IN ($placeholders) AND date >= ? AND date <= ?
                """.trimIndent()
            )
            try {
                var idx = 1
                for (habit in chunk) {
                    stmt.bindText(idx++, habit.uuid)
                }
                stmt.bindInt(idx++, from.daysSince2000)
                stmt.bindInt(idx, to.daysSince2000)
                while (stmt.step() == StepResult.ROW) {
                    val ref = HabitRef(stmt.getText(0))
                    val date = LocalDate(stmt.getInt(1))
                    val adj = HabitDayAdjustment(
                        targetHabit = ref,
                        date = date,
                        adjustmentMillis = stmt.getLong(2),
                        updatedAt = stmt.getLong(3),
                    )
                    result.getOrPut(ref) { LinkedHashMap() }[date] = adj
                }
            } finally {
                stmt.finalize()
            }
        }
        return result
    }

    override fun upsert(adjustment: HabitDayAdjustment) {
        if (adjustment.adjustmentMillis == 0L) {
            delete(adjustment.targetHabit, adjustment.date)
            return
        }
        EffectiveReadRevision.withMutationScope(setOf(adjustment.targetHabit)) {
            HabitDayAdjustmentSchema.createSchema(db)
            val stmt = db.prepareStatement(
                """
                INSERT OR REPLACE INTO HabitDayAdjustments(habit_uuid, date, adjustment_millis, updated_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            )
            try {
                stmt.bindText(1, adjustment.targetHabit.uuid)
                stmt.bindInt(2, adjustment.date.daysSince2000)
                stmt.bindLong(3, adjustment.adjustmentMillis)
                stmt.bindLong(4, adjustment.updatedAt)
                stmt.step()
            } finally {
                stmt.finalize()
            }
        }
    }

    override fun delete(targetHabit: HabitRef, date: LocalDate) {
        if (!HabitDayAdjustmentSchema.tableExists(db)) return
        EffectiveReadRevision.withMutationScope(setOf(targetHabit)) {
            val stmt = db.prepareStatement(
                "DELETE FROM HabitDayAdjustments WHERE habit_uuid = ? AND date = ?"
            )
            try {
                stmt.bindText(1, targetHabit.uuid)
                stmt.bindInt(2, date.daysSince2000)
                stmt.step()
            } finally {
                stmt.finalize()
            }
        }
    }

    override fun deleteByHabit(targetHabit: HabitRef) {
        if (!HabitDayAdjustmentSchema.tableExists(db)) return
        EffectiveReadRevision.withMutationScope(setOf(targetHabit)) {
            val stmt = db.prepareStatement(
                "DELETE FROM HabitDayAdjustments WHERE habit_uuid = ?"
            )
            try {
                stmt.bindText(1, targetHabit.uuid)
                stmt.step()
            } finally {
                stmt.finalize()
            }
        }
    }
}
