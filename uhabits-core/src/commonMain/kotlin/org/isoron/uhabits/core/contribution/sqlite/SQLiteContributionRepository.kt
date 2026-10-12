/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.core.contribution.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionConflictException
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionRecordResult
import org.isoron.uhabits.core.contribution.ContributionRemovalResult
import org.isoron.uhabits.core.contribution.ContributionRepository
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.rules.SourceRuleId

class SQLiteContributionRepository(private val db: Database) : ContributionRepository {

    override fun record(contribution: Contribution): ContributionRecordResult {
        db.run("SAVEPOINT record_contribution")
        try {
            val stmt = db.prepareStatement(
                "INSERT OR IGNORE INTO Contributions ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            try {
                stmt.bindText(1, contribution.id.value)
                stmt.bindText(2, contribution.targetHabit.uuid)
                stmt.bindText(3, contribution.sourceType.name)
                stmt.bindText(4, contribution.sourceId)
                stmt.bindLong(5, contribution.contributedValue)
                stmt.bindLong(6, contribution.effectiveTimestamp)
                stmt.bindLong(7, contribution.createdAt)
                stmt.bindLong(8, contribution.updatedAt)
                if (contribution.removedAt != null) {
                    stmt.bindLong(9, contribution.removedAt)
                } else {
                    stmt.bindNull(9)
                }
                if (contribution.sourceRuleId != null) {
                    stmt.bindText(10, contribution.sourceRuleId.value)
                } else {
                    stmt.bindNull(10)
                }
                stmt.step()
            } finally {
                stmt.finalize()
            }

            val inserted = db.queryLong("SELECT changes()") == 1L
            if (inserted) {
                db.run("RELEASE SAVEPOINT record_contribution")
                return ContributionRecordResult.INSERTED
            }

            // Not inserted: check whether it's an identical retry or a conflicting entry
            val byId = getById(contribution.id)
            if (byId != null) {
                if (byId != contribution) {
                    throw ContributionConflictException(
                        "A different Contribution is already recorded for id ${contribution.id}",
                        contribution.id,
                    )
                }
                db.run("RELEASE SAVEPOINT record_contribution")
                return ContributionRecordResult.ALREADY_RECORDED
            }

            val byProvenance = getBySourceAndTarget(
                contribution.sourceType,
                contribution.sourceId,
                contribution.targetHabit,
            )
            if (byProvenance != null) {
                if (byProvenance.id == contribution.id && byProvenance == contribution) {
                    db.run("RELEASE SAVEPOINT record_contribution")
                    return ContributionRecordResult.ALREADY_RECORDED
                }
                throw ContributionConflictException(
                    "A contribution for source ${contribution.sourceType}:${contribution.sourceId} to habit ${contribution.targetHabit.uuid} already exists with id ${byProvenance.id}",
                    contribution.id,
                )
            }

            throw IllegalStateException("Contribution insert rejected by database constraints")
        } catch (error: Throwable) {
            try {
                db.run("ROLLBACK TO SAVEPOINT record_contribution")
            } finally {
                db.run("RELEASE SAVEPOINT record_contribution")
            }
            throw error
        }
    }

    override fun remove(
        sourceType: ContributionSourceType,
        sourceId: String,
        targetHabit: HabitRef,
        removedAt: Long,
    ): ContributionRemovalResult {
        db.run("SAVEPOINT remove_contribution")
        try {
            val existing = getBySourceAndTarget(sourceType, sourceId, targetHabit)
            if (existing == null) {
                db.run("RELEASE SAVEPOINT remove_contribution")
                return ContributionRemovalResult.NOT_FOUND
            }
            val result = applyRemoval(existing, removedAt)
            db.run("RELEASE SAVEPOINT remove_contribution")
            return result
        } catch (error: Throwable) {
            try {
                db.run("ROLLBACK TO SAVEPOINT remove_contribution")
            } finally {
                db.run("RELEASE SAVEPOINT remove_contribution")
            }
            throw error
        }
    }

    override fun removeById(id: ContributionId, removedAt: Long): ContributionRemovalResult {
        db.run("SAVEPOINT remove_contribution")
        try {
            val existing = getById(id)
            if (existing == null) {
                db.run("RELEASE SAVEPOINT remove_contribution")
                return ContributionRemovalResult.NOT_FOUND
            }
            val result = applyRemoval(existing, removedAt)
            db.run("RELEASE SAVEPOINT remove_contribution")
            return result
        } catch (error: Throwable) {
            try {
                db.run("ROLLBACK TO SAVEPOINT remove_contribution")
            } finally {
                db.run("RELEASE SAVEPOINT remove_contribution")
            }
            throw error
        }
    }

    override fun restoreById(id: ContributionId, updatedAt: Long): Boolean {
        db.run("SAVEPOINT restore_contribution")
        try {
            val existing = getById(id) ?: run {
                db.run("RELEASE SAVEPOINT restore_contribution")
                return false
            }
            if (existing.isActive) {
                db.run("RELEASE SAVEPOINT restore_contribution")
                return false
            }
            val newUpdatedAt = maxOf(existing.updatedAt, updatedAt)
            val stmt = db.prepareStatement(
                "UPDATE Contributions SET removed_at = NULL, updated_at = ? WHERE id = ? AND removed_at IS NOT NULL"
            )
            try {
                stmt.bindLong(1, newUpdatedAt)
                stmt.bindText(2, id.value)
                stmt.step()
            } finally {
                stmt.finalize()
            }
            val changed = db.queryLong("SELECT changes()") == 1L
            db.run("RELEASE SAVEPOINT restore_contribution")
            return changed
        } catch (error: Throwable) {
            try {
                db.run("ROLLBACK TO SAVEPOINT restore_contribution")
            } finally {
                db.run("RELEASE SAVEPOINT restore_contribution")
            }
            throw error
        }
    }

    private fun applyRemoval(existing: Contribution, removedAt: Long): ContributionRemovalResult {
        if (!existing.isActive) {
            return ContributionRemovalResult.ALREADY_REMOVED
        }
        val newUpdatedAt = maxOf(existing.updatedAt, removedAt)
        val stmt = db.prepareStatement(
            "UPDATE Contributions SET removed_at = ?, updated_at = ? WHERE id = ? AND removed_at IS NULL"
        )
        try {
            stmt.bindLong(1, removedAt)
            stmt.bindLong(2, newUpdatedAt)
            stmt.bindText(3, existing.id.value)
            stmt.step()
        } finally {
            stmt.finalize()
        }
        val changed = db.queryLong("SELECT changes()") == 1L
        return if (changed) ContributionRemovalResult.REMOVED else ContributionRemovalResult.ALREADY_REMOVED
    }

    override fun getById(id: ContributionId): Contribution? =
        query("WHERE id = ?") { bindText(1, id.value) }.firstOrNull()

    override fun getBySourceAndTarget(
        sourceType: ContributionSourceType,
        sourceId: String,
        targetHabit: HabitRef,
    ): Contribution? =
        query("WHERE source_type = ? AND source_id = ? AND target_habit_uuid = ?") {
            bindText(1, sourceType.name)
            bindText(2, sourceId)
            bindText(3, targetHabit.uuid)
        }.firstOrNull()

    override fun getBySource(
        sourceType: ContributionSourceType,
        sourceId: String,
        includeRemoved: Boolean,
    ): List<Contribution> {
        val condition = if (includeRemoved) {
            "WHERE source_type = ? AND source_id = ?"
        } else {
            "WHERE source_type = ? AND source_id = ? AND removed_at IS NULL"
        }
        return query(condition) {
            bindText(1, sourceType.name)
            bindText(2, sourceId)
        }
    }

    override fun getByHabit(
        targetHabit: HabitRef,
        includeRemoved: Boolean,
    ): List<Contribution> {
        val condition = if (includeRemoved) {
            "WHERE target_habit_uuid = ?"
        } else {
            "WHERE target_habit_uuid = ? AND removed_at IS NULL"
        }
        return query(condition) {
            bindText(1, targetHabit.uuid)
        }
    }

    override fun getByHabitInRange(
        targetHabit: HabitRef,
        fromTimestamp: Long,
        toTimestamp: Long,
        includeRemoved: Boolean,
    ): List<Contribution> {
        val condition = if (includeRemoved) {
            "WHERE target_habit_uuid = ? AND effective_timestamp BETWEEN ? AND ?"
        } else {
            "WHERE target_habit_uuid = ? AND effective_timestamp BETWEEN ? AND ? AND removed_at IS NULL"
        }
        return query(condition) {
            bindText(1, targetHabit.uuid)
            bindLong(2, fromTimestamp)
            bindLong(3, toTimestamp)
        }
    }

    override fun getByHabitsInRange(
        targetHabits: Set<HabitRef>,
        fromTimestamp: Long,
        toTimestamp: Long,
        includeRemoved: Boolean,
    ): List<Contribution> {
        if (targetHabits.isEmpty() || fromTimestamp > toTimestamp) return emptyList()
        val results = targetHabits.toList().chunked(400).flatMap { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val activeCondition = if (includeRemoved) "" else " AND removed_at IS NULL"
            query("WHERE target_habit_uuid IN ($placeholders) AND effective_timestamp BETWEEN ? AND ?$activeCondition") {
                chunk.forEachIndexed { index, habit -> bindText(index + 1, habit.uuid) }
                bindLong(chunk.size + 1, fromTimestamp)
                bindLong(chunk.size + 2, toTimestamp)
            }
        }
        return results.sortedWith(compareBy<Contribution> { it.effectiveTimestamp }.thenBy { it.id.value })
    }

    override fun getAll(includeRemoved: Boolean): List<Contribution> {
        val condition = if (includeRemoved) "" else "WHERE removed_at IS NULL"
        return query(condition)
    }

    override fun getActiveTotalForHabit(targetHabit: HabitRef): Long {
        val stmt = db.prepareStatement(
            "SELECT COALESCE(SUM(contributed_value), 0) FROM Contributions WHERE target_habit_uuid = ? AND removed_at IS NULL"
        )
        return try {
            stmt.bindText(1, targetHabit.uuid)
            if (stmt.step() == StepResult.ROW) stmt.getLong(0) else 0L
        } finally {
            stmt.finalize()
        }
    }

    override fun getActiveTotalForHabitInRange(
        targetHabit: HabitRef,
        fromTimestamp: Long,
        toTimestamp: Long,
    ): Long {
        val stmt = db.prepareStatement(
            """
            SELECT COALESCE(SUM(contributed_value), 0) FROM Contributions
            WHERE target_habit_uuid = ? AND effective_timestamp BETWEEN ? AND ? AND removed_at IS NULL
            """.trimIndent()
        )
        return try {
            stmt.bindText(1, targetHabit.uuid)
            stmt.bindLong(2, fromTimestamp)
            stmt.bindLong(3, toTimestamp)
            if (stmt.step() == StepResult.ROW) stmt.getLong(0) else 0L
        } finally {
            stmt.finalize()
        }
    }

    override fun getByRule(
        ruleId: SourceRuleId,
        includeRemoved: Boolean,
    ): List<Contribution> {
        val condition = if (includeRemoved) {
            "WHERE source_rule_id = ?"
        } else {
            "WHERE source_rule_id = ? AND removed_at IS NULL"
        }
        return query(condition) {
            bindText(1, ruleId.value)
        }
    }

    private fun query(where: String, bind: PreparedStatement.() -> Unit = {}): List<Contribution> {
        val stmt = db.prepareStatement(
            "SELECT $COLUMNS FROM Contributions $where ORDER BY effective_timestamp, id"
        )
        return try {
            stmt.bind()
            buildList {
                while (stmt.step() == StepResult.ROW) {
                    add(
                        Contribution(
                            id = ContributionId(stmt.getText(0)),
                            targetHabit = HabitRef(stmt.getText(1)),
                            sourceType = ContributionSourceType.valueOf(stmt.getText(2)),
                            sourceId = stmt.getText(3),
                            contributedValue = stmt.getLong(4),
                            effectiveTimestamp = stmt.getLong(5),
                            createdAt = stmt.getLong(6),
                            updatedAt = stmt.getLong(7),
                            removedAt = stmt.getLongOrNull(8),
                            sourceRuleId = stmt.getTextOrNull(9)?.let(::SourceRuleId),
                        )
                    )
                }
            }
        } finally {
            stmt.finalize()
        }
    }

    companion object {
        private const val COLUMNS =
            "id, target_habit_uuid, source_type, source_id, contributed_value, effective_timestamp, created_at, updated_at, removed_at, source_rule_id"
    }
}
