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

package org.isoron.uhabits.core.rules.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.rules.*

class SQLiteSourceRuleRepository(private val db: Database) : SourceRuleRepository {

    override fun replaceForTarget(targetHabit: HabitRef, replacement: SourceRule?, endedAt: Long) {
        require(replacement == null || replacement.targetHabit == targetHabit)
        db.run("SAVEPOINT replace_source_rule")
        try {
            // Acquire the write lock before reading the current configuration.
            db.run("UPDATE SourceRules SET updated_at = updated_at WHERE target_habit_uuid = ?") { bindText(1, targetHabit.uuid) }
            getByTargetHabit(targetHabit).forEach { remove(it.id, endedAt) }
            if (replacement != null) record(replacement)
            db.run("RELEASE SAVEPOINT replace_source_rule")
        } catch (error: Throwable) {
            try { db.run("ROLLBACK TO SAVEPOINT replace_source_rule") }
            finally { db.run("RELEASE SAVEPOINT replace_source_rule") }
            throw error
        }
    }

    override fun record(rule: SourceRule): SourceRuleRecordResult {
        db.run("SAVEPOINT record_source_rule")
        try {
            val stmt = db.prepareStatement(
                "INSERT OR IGNORE INTO SourceRules ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )
            try {
                stmt.bindText(1, rule.id.value)
                stmt.bindText(2, rule.sourceKind.name)
                stmt.bindText(3, rule.sourceContainerId.value)
                stmt.bindLong(4, if (rule.includeDescendants) 1L else 0L)
                stmt.bindText(5, rule.targetHabit.uuid)
                stmt.bindText(6, rule.valueMapping.name)
                stmt.bindLong(7, if (rule.isEnabled) 1L else 0L)
                stmt.bindLong(8, rule.effectiveFrom)
                stmt.bindLong(9, rule.createdAt)
                stmt.bindLong(10, rule.updatedAt)
                if (rule.removedAt != null) {
                    stmt.bindLong(11, rule.removedAt)
                } else {
                    stmt.bindNull(11)
                }
                stmt.step()
            } finally {
                stmt.finalize()
            }

            val inserted = db.queryLong("SELECT changes()") == 1L
            if (inserted) {
                db.run("RELEASE SAVEPOINT record_source_rule")
                return SourceRuleRecordResult.INSERTED
            }

            // Not inserted: check if exact identical retry or conflicting entry
            val byId = getById(rule.id)
            if (byId != null) {
                if (byId != rule) {
                    throw SourceRuleConflictException(
                        "A different SourceRule is already recorded for id ${rule.id}",
                        rule.id,
                    )
                }
                db.run("RELEASE SAVEPOINT record_source_rule")
                return SourceRuleRecordResult.ALREADY_RECORDED
            }

            val existing = query("WHERE source_kind = ? AND source_container_id = ? AND target_habit_uuid = ? AND is_enabled = 1 AND removed_at IS NULL") {
                bindText(1, rule.sourceKind.name)
                bindText(2, rule.sourceContainerId.value)
                bindText(3, rule.targetHabit.uuid)
            }.firstOrNull()

            if (existing != null) {
                if (existing.id == rule.id && existing == rule) {
                    db.run("RELEASE SAVEPOINT record_source_rule")
                    return SourceRuleRecordResult.ALREADY_RECORDED
                }
                throw SourceRuleConflictException(
                    "A SourceRule for container ${rule.sourceContainerId.value} to habit ${rule.targetHabit.uuid} already exists with id ${existing.id}",
                    rule.id,
                )
            }

            throw IllegalStateException("SourceRule insert rejected by database constraints")
        } catch (error: Throwable) {
            try {
                db.run("ROLLBACK TO SAVEPOINT record_source_rule")
            } finally {
                db.run("RELEASE SAVEPOINT record_source_rule")
            }
            throw error
        }
    }

    override fun remove(id: SourceRuleId, removedAt: Long): SourceRuleRemovalResult {
        db.run("SAVEPOINT remove_source_rule")
        try {
            val existing = getById(id)
            if (existing == null) {
                db.run("RELEASE SAVEPOINT remove_source_rule")
                return SourceRuleRemovalResult.NOT_FOUND
            }
            if (!existing.isActive) {
                db.run("RELEASE SAVEPOINT remove_source_rule")
                return SourceRuleRemovalResult.ALREADY_REMOVED
            }

            val newUpdatedAt = maxOf(existing.updatedAt, removedAt)
            val stmt = db.prepareStatement(
                "UPDATE SourceRules SET removed_at = ?, updated_at = ? WHERE id = ? AND removed_at IS NULL"
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
            db.run("RELEASE SAVEPOINT remove_source_rule")
            return if (changed) SourceRuleRemovalResult.REMOVED else SourceRuleRemovalResult.ALREADY_REMOVED
        } catch (error: Throwable) {
            try {
                db.run("ROLLBACK TO SAVEPOINT remove_source_rule")
            } finally {
                db.run("RELEASE SAVEPOINT remove_source_rule")
            }
            throw error
        }
    }

    override fun getById(id: SourceRuleId): SourceRule? =
        query("WHERE id = ?") { bindText(1, id.value) }.firstOrNull()

    override fun getByTargetHabit(targetHabit: HabitRef, includeRemoved: Boolean): List<SourceRule> {
        val condition = if (includeRemoved) {
            "WHERE target_habit_uuid = ?"
        } else {
            "WHERE target_habit_uuid = ? AND is_enabled = 1 AND removed_at IS NULL"
        }
        return query(condition) { bindText(1, targetHabit.uuid) }
    }

    override fun getByContainer(containerId: ContainerId, includeRemoved: Boolean): List<SourceRule> {
        val condition = if (includeRemoved) {
            "WHERE source_container_id = ?"
        } else {
            "WHERE source_container_id = ? AND is_enabled = 1 AND removed_at IS NULL"
        }
        return query(condition) { bindText(1, containerId.value) }
    }

    override fun getAll(includeRemoved: Boolean): List<SourceRule> {
        val condition = if (includeRemoved) "" else "WHERE is_enabled = 1 AND removed_at IS NULL"
        return query(condition)
    }

    override fun getActiveRules(): List<SourceRule> =
        query("WHERE is_enabled = 1 AND removed_at IS NULL")

    private fun query(where: String, bind: PreparedStatement.() -> Unit = {}): List<SourceRule> {
        val stmt = db.prepareStatement(
            "SELECT $COLUMNS FROM SourceRules $where ORDER BY created_at, id"
        )
        return try {
            stmt.bind()
            buildList {
                while (stmt.step() == StepResult.ROW) {
                    add(
                        SourceRule(
                            id = SourceRuleId(stmt.getText(0)),
                            sourceKind = SourceRuleSourceKind.valueOf(stmt.getText(1)),
                            sourceContainerId = ContainerId(stmt.getText(2)),
                            includeDescendants = stmt.getLong(3) == 1L,
                            targetHabit = HabitRef(stmt.getText(4)),
                            valueMapping = SourceRuleValueMapping.valueOf(stmt.getText(5)),
                            isEnabled = stmt.getLong(6) == 1L,
                            effectiveFrom = stmt.getLong(7),
                            createdAt = stmt.getLong(8),
                            updatedAt = stmt.getLong(9),
                            removedAt = stmt.getLongOrNull(10),
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
            "id, source_kind, source_container_id, include_descendants, target_habit_uuid, value_mapping, is_enabled, effective_from, created_at, updated_at, removed_at"
    }
}
