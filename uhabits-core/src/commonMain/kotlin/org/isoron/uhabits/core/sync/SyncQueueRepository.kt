package org.isoron.uhabits.core.sync

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong

data class SyncQueueRecord(
    val queueId: Long? = null,
    val opUuid: String,
    val entityType: String,
    val entityUuid: String,
    val operationType: String,
    val payloadJson: String,
    val createdAt: Long,
    val deviceId: String,
    val pushedAt: Long? = null,
    val failedAt: Long? = null,
    val failureReason: String? = null
)

class SyncQueueRepository(private val db: Database) {
    private val insertStmt by lazy {
        db.prepareStatement(
            """INSERT INTO SyncQueue(
               op_uuid, entity_type, entity_uuid, operation_type, payload_json,
               created_at, device_id, pushed_at, failed_at, failure_reason
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
    }

    private val findPendingStmt by lazy {
        db.prepareStatement(
            """SELECT queue_id, op_uuid, entity_type, entity_uuid, operation_type, payload_json,
               created_at, device_id, pushed_at, failed_at, failure_reason
               FROM SyncQueue WHERE pushed_at IS NULL ORDER BY queue_id ASC"""
        )
    }

    private val markPushedStmt by lazy {
        db.prepareStatement(
            "UPDATE SyncQueue SET pushed_at = ?, failed_at = NULL, failure_reason = NULL WHERE queue_id = ?"
        )
    }

    private val markFailedStmt by lazy {
        db.prepareStatement(
            "UPDATE SyncQueue SET failed_at = ?, failure_reason = ? WHERE queue_id = ?"
        )
    }

    fun insert(record: SyncQueueRecord): Long {
        insertStmt.reset()
        insertStmt.bindText(1, record.opUuid)
        insertStmt.bindText(2, record.entityType)
        insertStmt.bindText(3, record.entityUuid)
        insertStmt.bindText(4, record.operationType)
        insertStmt.bindText(5, record.payloadJson)
        insertStmt.bindLong(6, record.createdAt)
        insertStmt.bindText(7, record.deviceId)
        if (record.pushedAt != null) insertStmt.bindLong(8, record.pushedAt) else insertStmt.bindNull(8)
        if (record.failedAt != null) insertStmt.bindLong(9, record.failedAt) else insertStmt.bindNull(9)
        if (record.failureReason != null) insertStmt.bindText(10, record.failureReason) else insertStmt.bindNull(10)
        insertStmt.step()
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun findPending(): List<SyncQueueRecord> {
        findPendingStmt.reset()
        val records = mutableListOf<SyncQueueRecord>()
        while (findPendingStmt.step() == StepResult.ROW) {
            records.add(
                SyncQueueRecord(
                    queueId = findPendingStmt.getLong(0),
                    opUuid = findPendingStmt.getText(1),
                    entityType = findPendingStmt.getText(2),
                    entityUuid = findPendingStmt.getText(3),
                    operationType = findPendingStmt.getText(4),
                    payloadJson = findPendingStmt.getTextOrNull(5) ?: "{}",
                    createdAt = findPendingStmt.getLong(6),
                    deviceId = findPendingStmt.getText(7),
                    pushedAt = findPendingStmt.getLongOrNull(8),
                    failedAt = findPendingStmt.getLongOrNull(9),
                    failureReason = findPendingStmt.getTextOrNull(10)
                )
            )
        }
        return records
    }

    fun markPushed(queueId: Long, pushedAt: Long) {
        markPushedStmt.reset()
        markPushedStmt.bindLong(1, pushedAt)
        markPushedStmt.bindLong(2, queueId)
        markPushedStmt.step()
    }

    fun markFailed(queueId: Long, failedAt: Long, reason: String) {
        markFailedStmt.reset()
        markFailedStmt.bindLong(1, failedAt)
        markFailedStmt.bindText(2, reason)
        markFailedStmt.bindLong(3, queueId)
        markFailedStmt.step()
    }
}
