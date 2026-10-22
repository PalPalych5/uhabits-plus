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

package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.querySingle

data class SyncInboxRecord(
    val logId: Long,
    val deviceId: String,
    val opUuid: String,
    val entityType: String,
    val entityUuid: String,
    val operationType: String,
    val payloadJson: String,
    val createdAt: Long,
    val receivedAt: Long,
    val deferredReason: String,
    val status: String = "PENDING",
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null
)

class SyncInboxRepository(private val db: Database) {

    fun insertOrUpdate(record: SyncInboxRecord) {
        val stmt = db.prepareStatement(
            """INSERT OR REPLACE INTO SyncInbox (
                log_id, device_id, op_uuid, entity_type, entity_uuid,
                operation_type, payload_json, created_at, received_at,
                deferred_reason, status, attempts, last_attempt_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
        try {
            stmt.bindLong(1, record.logId)
            stmt.bindText(2, record.deviceId)
            stmt.bindText(3, record.opUuid)
            stmt.bindText(4, record.entityType)
            stmt.bindText(5, record.entityUuid)
            stmt.bindText(6, record.operationType)
            stmt.bindText(7, record.payloadJson)
            stmt.bindLong(8, record.createdAt)
            stmt.bindLong(9, record.receivedAt)
            stmt.bindText(10, record.deferredReason)
            stmt.bindText(11, record.status)
            stmt.bindInt(12, record.attempts)
            if (record.lastAttemptAt != null) stmt.bindLong(13, record.lastAttemptAt) else stmt.bindNull(13)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    private fun readRow(stmt: PreparedStatement): SyncInboxRecord {
        return SyncInboxRecord(
            logId = stmt.getLong(0),
            deviceId = stmt.getText(1),
            opUuid = stmt.getText(2),
            entityType = stmt.getText(3),
            entityUuid = stmt.getText(4),
            operationType = stmt.getText(5),
            payloadJson = stmt.getTextOrNull(6) ?: "{}",
            createdAt = stmt.getLong(7),
            receivedAt = stmt.getLong(8),
            deferredReason = stmt.getText(9),
            status = stmt.getText(10),
            attempts = stmt.getInt(11),
            lastAttemptAt = stmt.getLongOrNull(12)
        )
    }

    fun findPending(
        maxAttempts: Int = 5,
        backoffBaseMs: Long = 10_000L,
        now: Long = 0L,
        ignoreBackoff: Boolean = false
    ): List<SyncInboxRecord> {
        val stmt = db.prepareStatement(
            """SELECT log_id, device_id, op_uuid, entity_type, entity_uuid,
                      operation_type, payload_json, created_at, received_at,
                      deferred_reason, status, attempts, last_attempt_at
               FROM SyncInbox
               WHERE status = 'PENDING' AND attempts < ?
               ORDER BY created_at ASC, log_id ASC"""
        )
        val result = mutableListOf<SyncInboxRecord>()
        try {
            stmt.bindInt(1, maxAttempts)
            while (stmt.step() == StepResult.ROW) {
                val record = readRow(stmt)
                if (ignoreBackoff) {
                    result.add(record)
                } else {
                    val backoffMs = (1L shl record.attempts.coerceAtMost(6)) * backoffBaseMs
                    if (record.lastAttemptAt == null || (now - record.lastAttemptAt) >= backoffMs) {
                        result.add(record)
                    }
                }
            }
        } finally {
            stmt.finalize()
        }
        return result
    }

    fun findByEntity(entityType: String, entityUuid: String): List<SyncInboxRecord> {
        val stmt = db.prepareStatement(
            """SELECT log_id, device_id, op_uuid, entity_type, entity_uuid,
                      operation_type, payload_json, created_at, received_at,
                      deferred_reason, status, attempts, last_attempt_at
               FROM SyncInbox
               WHERE entity_type = ? AND entity_uuid = ?
               ORDER BY created_at ASC, log_id ASC"""
        )
        val result = mutableListOf<SyncInboxRecord>()
        try {
            stmt.bindText(1, entityType)
            stmt.bindText(2, entityUuid)
            while (stmt.step() == StepResult.ROW) {
                result.add(readRow(stmt))
            }
        } finally {
            stmt.finalize()
        }
        return result
    }

    fun deleteByLogId(logId: Long) {
        val stmt = db.prepareStatement("DELETE FROM SyncInbox WHERE log_id = ?")
        try {
            stmt.bindLong(1, logId)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    fun deleteByOpUuid(opUuid: String) {
        val stmt = db.prepareStatement("DELETE FROM SyncInbox WHERE op_uuid = ?")
        try {
            stmt.bindText(1, opUuid)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    fun purgeForEntity(entityType: String, entityUuid: String) {
        val stmt = db.prepareStatement("DELETE FROM SyncInbox WHERE entity_type = ? AND entity_uuid = ?")
        try {
            stmt.bindText(1, entityType)
            stmt.bindText(2, entityUuid)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    fun recordAttempt(
        logId: Long,
        now: Long,
        newReason: String? = null,
        maxAttempts: Int = 5
    ) {
        val stmt = db.prepareStatement(
            """UPDATE SyncInbox
               SET attempts = attempts + 1,
                   last_attempt_at = ?,
                   deferred_reason = COALESCE(?, deferred_reason),
                   status = CASE WHEN attempts + 1 >= ? THEN 'SUSPENDED' ELSE status END
               WHERE log_id = ?"""
        )
        try {
            stmt.bindLong(1, now)
            if (newReason != null) stmt.bindText(2, newReason) else stmt.bindNull(2)
            stmt.bindInt(3, maxAttempts)
            stmt.bindLong(4, logId)
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }

    fun count(): Int {
        return db.querySingle("SELECT count(*) FROM SyncInbox") { it.getInt(0) } ?: 0
    }

    fun clearAll() {
        val stmt = db.prepareStatement("DELETE FROM SyncInbox")
        try {
            stmt.step()
        } finally {
            stmt.finalize()
        }
    }
}
