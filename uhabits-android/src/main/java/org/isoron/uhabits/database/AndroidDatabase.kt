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

package org.isoron.uhabits.database

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import android.os.SystemClock

/** Explicit instrumentation opt-in. SQL text and bindings are never exported. */
object AndroidSqlTiming {
    @Volatile var observer: ((String, Double) -> Unit)? = null
}

class AndroidPreparedStatement(
    private val db: SQLiteDatabase,
    private val sql: String
) : PreparedStatement {
    companion object {
        private class SavepointState {
            val names = mutableListOf<String>()
            var ownsTransaction = false
        }
        private val savepointStates = ThreadLocal<java.util.WeakHashMap<SQLiteDatabase, SavepointState>>()

        private fun savepointState(db: SQLiteDatabase): SavepointState {
            val states = savepointStates.get() ?: java.util.WeakHashMap<SQLiteDatabase, SavepointState>()
                .also { savepointStates.set(it) }
            return states.getOrPut(db) { SavepointState() }
        }

        fun resetSavepointState(db: SQLiteDatabase) {
            savepointStates.get()?.remove(db)
        }
    }

    private val upperWords = sql.trim().trimEnd(';').uppercase().split(Regex("\\s+"))
    private val isTxBegin = upperWords.isNotEmpty() && upperWords[0] == "BEGIN" &&
            upperWords.drop(1).all { it in listOf("IMMEDIATE", "EXCLUSIVE", "TRANSACTION", "DEFERRED") }
    private val isTxCommit = (upperWords.size == 1 && (upperWords[0] == "COMMIT" || upperWords[0] == "END")) ||
            (upperWords.size in 2..3 && (upperWords[0] == "COMMIT" || upperWords[0] == "END") && upperWords.drop(1).all { it in listOf("TRANSACTION", "WORK") })
    private val isSavepoint = upperWords.isNotEmpty() && upperWords[0] == "SAVEPOINT"
    private val isRelease = upperWords.isNotEmpty() && upperWords[0] == "RELEASE"
    private val isRollbackTo = upperWords.size >= 3 && upperWords[0] == "ROLLBACK" &&
            (upperWords[1] == "TO" || (upperWords.size >= 4 && upperWords[1] == "TRANSACTION" && upperWords[2] == "TO"))
    private val isTxRollback = !isRollbackTo && upperWords.isNotEmpty() && upperWords[0] == "ROLLBACK" &&
            upperWords.drop(1).all { it in listOf("TRANSACTION", "WORK") }
    private val isTxControl = isTxBegin || isTxCommit || isTxRollback

    private val isChangesQuery = upperWords.size == 2 && upperWords[0] == "SELECT" && upperWords[1] == "CHANGES()"
    private val isLastInsertRowIdQuery = upperWords.size == 2 && upperWords[0] == "SELECT" && upperWords[1] == "LAST_INSERT_ROWID()"
    private var changesRowRead = false

    private val isQuery = isChangesQuery || isLastInsertRowIdQuery || sql.trimStart().uppercase().let {
        it.startsWith("SELECT") || it.startsWith("PRAGMA")
    }

    private val isMutation = sql.trimStart().uppercase().let {
        it.startsWith("INSERT") || it.startsWith("UPDATE") || it.startsWith("DELETE") || it.startsWith("REPLACE")
    }

    private val compiledStmt: SQLiteStatement? = if (!isQuery && !isTxControl) db.compileStatement(sql) else null

    private val bindings = mutableMapOf<Int, Any?>()
    private var cursor: android.database.Cursor? = null

    @Synchronized
    override fun step(): StepResult {
        val observer = AndroidSqlTiming.observer
        if (observer == null) return executeStep()
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            return executeStep()
        } finally {
            observer(sql.trimStart().takeWhile { !it.isWhitespace() }.uppercase(),
                (SystemClock.elapsedRealtimeNanos() - started) / 1e6)
        }
    }

    private fun executeStep(): StepResult {
        if (isTxBegin) {
            db.beginTransactionNonExclusive()
            return StepResult.DONE
        }
        if (isTxCommit) {
            if (db.inTransaction()) {
                db.setTransactionSuccessful()
                db.endTransaction()
            }
            resetSavepointState(db)
            return StepResult.DONE
        }
        if (isTxRollback) {
            if (db.inTransaction()) {
                db.endTransaction()
            }
            resetSavepointState(db)
            return StepResult.DONE
        }
        if (isSavepoint) {
            val state = savepointState(db)
            val startedTransaction = !db.inTransaction()
            if (startedTransaction) {
                db.beginTransactionNonExclusive()
                state.ownsTransaction = true
            }
            try {
                compiledStmt!!.execute()
                state.names.add(upperWords.last().trim('"', '`', '[', ']'))
            } catch (t: Throwable) {
                if (startedTransaction) {
                    try { db.endTransaction() } finally { resetSavepointState(db) }
                }
                throw t
            }
            return StepResult.DONE
        }
        if (isRollbackTo) {
            compiledStmt!!.execute()
            val names = savepointState(db).names
            val index = names.indexOfLast { it == upperWords.last().trim('"', '`', '[', ']') }
            if (index >= 0) while (names.size > index + 1) names.removeAt(names.lastIndex)
            return StepResult.DONE
        }
        if (isRelease) {
            compiledStmt!!.execute()
            val state = savepointState(db)
            val index = state.names.indexOfLast { it == upperWords.last().trim('"', '`', '[', ']') }
            if (index >= 0) while (state.names.size > index) state.names.removeAt(state.names.lastIndex)
            if (state.ownsTransaction && state.names.isEmpty() && db.inTransaction()) {
                try {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                } finally { resetSavepointState(db) }
            }
            return StepResult.DONE
        }
        if (isChangesQuery || isLastInsertRowIdQuery) {
            return if (!changesRowRead) {
                changesRowRead = true
                StepResult.ROW
            } else {
                StepResult.DONE
            }
        }
        if (isQuery) {
            if (cursor == null) {
                val args = buildBindArgs()
                cursor = db.rawQuery(sql, args)
            }
            return if (cursor!!.moveToNext()) StepResult.ROW else StepResult.DONE
        } else {
            if (isMutation) {
                val upperSql = sql.trimStart().uppercase()
                if (upperSql.startsWith("INSERT") || upperSql.startsWith("REPLACE")) {
                    val rowId = runCatching { compiledStmt!!.executeInsert() }.getOrElse {
                        compiledStmt!!.execute()
                        0L
                    }
                    AndroidDatabase.lastInsertRowId = rowId
                    AndroidDatabase.lastChanges = if (rowId > 0) 1L else 0L
                } else {
                    val count = runCatching { compiledStmt!!.executeUpdateDelete().toLong() }.getOrElse {
                        compiledStmt!!.execute()
                        0L
                    }
                    AndroidDatabase.lastChanges = count
                }
            } else {
                compiledStmt!!.execute()
            }
            return StepResult.DONE
        }
    }

    @Synchronized
    override fun getInt(index: Int) =
        if (isChangesQuery) AndroidDatabase.lastChanges.toInt()
        else if (isLastInsertRowIdQuery) AndroidDatabase.lastInsertRowId.toInt()
        else cursor!!.getInt(index)

    @Synchronized
    override fun getLong(index: Int) =
        if (isChangesQuery) AndroidDatabase.lastChanges
        else if (isLastInsertRowIdQuery) AndroidDatabase.lastInsertRowId
        else cursor!!.getLong(index)

    @Synchronized
    override fun getReal(index: Int) = cursor!!.getDouble(index)

    @Synchronized
    override fun getText(index: Int) =
        if (isChangesQuery) AndroidDatabase.lastChanges.toString()
        else if (isLastInsertRowIdQuery) AndroidDatabase.lastInsertRowId.toString()
        else cursor!!.getString(index)

    @Synchronized
    override fun getIntOrNull(index: Int): Int? =
        if (isChangesQuery) AndroidDatabase.lastChanges.toInt()
        else if (isLastInsertRowIdQuery) AndroidDatabase.lastInsertRowId.toInt()
        else if (cursor!!.isNull(index)) null
        else cursor!!.getInt(index)

    @Synchronized
    override fun getLongOrNull(index: Int): Long? =
        if (isChangesQuery) AndroidDatabase.lastChanges
        else if (isLastInsertRowIdQuery) AndroidDatabase.lastInsertRowId
        else if (cursor!!.isNull(index)) null
        else cursor!!.getLong(index)

    @Synchronized
    override fun getRealOrNull(index: Int): Double? =
        if (cursor!!.isNull(index)) null else cursor!!.getDouble(index)

    @Synchronized
    override fun getTextOrNull(index: Int): String? =
        if (isChangesQuery) AndroidDatabase.lastChanges.toString()
        else if (isLastInsertRowIdQuery) AndroidDatabase.lastInsertRowId.toString()
        else if (cursor!!.isNull(index)) null
        else cursor!!.getString(index)

    @Synchronized
    override fun bindInt(index: Int, value: Int) {
        if (isQuery) {
            bindings[index] = value.toString()
        } else {
            compiledStmt!!.bindLong(index, value.toLong())
        }
    }

    @Synchronized
    override fun bindLong(index: Int, value: Long) {
        if (isQuery) {
            bindings[index] = value.toString()
        } else {
            compiledStmt!!.bindLong(index, value)
        }
    }

    @Synchronized
    override fun bindReal(index: Int, value: Double) {
        if (isQuery) {
            bindings[index] = value.toString()
        } else {
            compiledStmt!!.bindDouble(index, value)
        }
    }

    @Synchronized
    override fun bindText(index: Int, value: String) {
        if (isQuery) {
            bindings[index] = value
        } else {
            compiledStmt!!.bindString(index, value)
        }
    }

    @Synchronized
    override fun bindNull(index: Int) {
        if (isQuery) {
            bindings[index] = null
        } else {
            compiledStmt!!.bindNull(index)
        }
    }

    @Synchronized
    override fun reset() {
        cursor?.close()
        cursor = null
        changesRowRead = false
        bindings.clear()
        compiledStmt?.clearBindings()
    }

    @Synchronized
    override fun finalize() {
        cursor?.close()
        cursor = null
        changesRowRead = false
        compiledStmt?.close()
    }

    private fun buildBindArgs(): Array<String>? {
        if (bindings.isEmpty()) return null
        val maxIndex = bindings.keys.max()
        return Array(maxIndex) { i -> bindings[i + 1]?.toString() ?: "" }
    }
}

class AndroidDatabase(
    private val db: SQLiteDatabase,
    revision: org.isoron.uhabits.core.models.projection.EffectiveReadRevision? = null,
    workspaceRevision: org.isoron.uhabits.core.models.projection.EffectiveReadRevision? = null,
    organizationRevision: org.isoron.uhabits.core.models.projection.EffectiveReadRevision? = null,
) : org.isoron.platform.io.Database {

    companion object {
        private val lastChangesThreadLocal = ThreadLocal<Long>()
        var lastChanges: Long
            get() = lastChangesThreadLocal.get() ?: 0L
            set(value) = lastChangesThreadLocal.set(value)

        private val lastInsertRowIdThreadLocal = ThreadLocal<Long>()
        var lastInsertRowId: Long
            get() = lastInsertRowIdThreadLocal.get() ?: 0L
            set(value) = lastInsertRowIdThreadLocal.set(value)
    }

    constructor(db: SQLiteDatabase, revision: org.isoron.uhabits.core.models.projection.EffectiveReadRevision?) :
        this(db, revision, null, null)
    constructor(db: SQLiteDatabase) :
        this(db, null, null, null)

    private val connection: org.isoron.platform.io.Database = object : org.isoron.platform.io.Database {
        override fun prepareStatement(sql: String): PreparedStatement = AndroidPreparedStatement(db, sql)
        override fun close() {
            AndroidPreparedStatement.resetSavepointState(db)
            db.close()
        }
        override fun beginTransaction() = db.beginTransactionNonExclusive()
        override fun setTransactionSuccessful() = db.setTransactionSuccessful()
        override fun endTransaction() {
            try {
                db.endTransaction()
            } finally {
                if (!db.inTransaction()) AndroidPreparedStatement.resetSavepointState(db)
            }
        }
        override fun inTransaction(): Boolean = db.inTransaction()
    }.let { raw ->
        if (revision == null) raw else org.isoron.uhabits.core.models.projection.EffectiveReadDatabase(
            raw, revision, setOf("CONTRIBUTIONS", "REPETITIONS", "HABITS", "HABITGOALS", "HABITEXTENSIONS", "APPSETTINGS", "CONTAINERS", "HABITPLACEMENTS", "ORGANIZATIONSTATE", "HABITVISUALIDENTITIES")
        )
    }.let { effective ->
        if (workspaceRevision == null) effective else org.isoron.uhabits.core.models.projection.EffectiveReadDatabase(
            effective, workspaceRevision, setOf("USERTASKS", "TASKTAGS", "TASKTAGLINKS", "TASKTAGSTATE", "TASKTAGOUTBOX", "CONTAINERS", "ORGANIZATIONSTATE", "HABITPLACEMENTS", "HABITVISUALIDENTITIES"),
        )
    }.let { workspace ->
        if (organizationRevision == null) workspace else org.isoron.uhabits.core.models.projection.EffectiveReadDatabase(
            workspace, organizationRevision, setOf("CONTAINERS", "ORGANIZATIONSTATE", "HABITPLACEMENTS", "HABITVISUALIDENTITIES"),
        )
    }

    override fun prepareStatement(sql: String): PreparedStatement =
        connection.prepareStatement(sql)

    override fun close() = connection.close()

    override fun beginTransaction() = connection.beginTransaction()
    override fun setTransactionSuccessful() = connection.setTransactionSuccessful()
    override fun endTransaction() = connection.endTransaction()
    override fun inTransaction(): Boolean = connection.inTransaction()
}
