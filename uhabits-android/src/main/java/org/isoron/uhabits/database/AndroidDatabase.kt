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
    private val isChangesQuery = sql.trim().trimEnd(';').equals("SELECT changes()", ignoreCase = true)
    private var changesRowRead = false

    private val isQuery = isChangesQuery || sql.trimStart().uppercase().let {
        it.startsWith("SELECT") || it.startsWith("PRAGMA")
    }

    private val isMutation = sql.trimStart().uppercase().let {
        it.startsWith("INSERT") || it.startsWith("UPDATE") || it.startsWith("DELETE") || it.startsWith("REPLACE")
    }

    private val compiledStmt: SQLiteStatement? = if (!isQuery) db.compileStatement(sql) else null

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
        if (isChangesQuery) {
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
                val count = runCatching { compiledStmt!!.executeUpdateDelete().toLong() }.getOrElse {
                    compiledStmt!!.execute()
                    0L
                }
                AndroidDatabase.lastChanges = count
            } else {
                compiledStmt!!.execute()
            }
            return StepResult.DONE
        }
    }

    @Synchronized
    override fun getInt(index: Int) = if (isChangesQuery) AndroidDatabase.lastChanges.toInt() else cursor!!.getInt(index)
    @Synchronized
    override fun getLong(index: Int) = if (isChangesQuery) AndroidDatabase.lastChanges else cursor!!.getLong(index)
    @Synchronized
    override fun getReal(index: Int) = cursor!!.getDouble(index)
    @Synchronized
    override fun getText(index: Int) = cursor!!.getString(index)

    @Synchronized
    override fun getIntOrNull(index: Int): Int? =
        if (isChangesQuery) AndroidDatabase.lastChanges.toInt() else if (cursor!!.isNull(index)) null else cursor!!.getInt(index)

    @Synchronized
    override fun getLongOrNull(index: Int): Long? =
        if (cursor!!.isNull(index)) null else cursor!!.getLong(index)

    @Synchronized
    override fun getRealOrNull(index: Int): Double? =
        if (cursor!!.isNull(index)) null else cursor!!.getDouble(index)

    @Synchronized
    override fun getTextOrNull(index: Int): String? =
        if (cursor!!.isNull(index)) null else cursor!!.getString(index)

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
    }

    constructor(db: SQLiteDatabase, revision: org.isoron.uhabits.core.models.projection.EffectiveReadRevision?) :
        this(db, revision, null, null)
    constructor(db: SQLiteDatabase) :
        this(db, null, null, null)

    private val connection: org.isoron.platform.io.Database = object : org.isoron.platform.io.Database {
        override fun prepareStatement(sql: String): PreparedStatement = AndroidPreparedStatement(db, sql)
        override fun close() = db.close()
    }.let { raw ->
        if (revision == null) raw else org.isoron.uhabits.core.models.projection.EffectiveReadDatabase(
            raw, revision, setOf("CONTRIBUTIONS", "REPETITIONS", "HABITS", "HABITGOALS", "HABITEXTENSIONS", "APPSETTINGS", "CONTAINERS", "HABITPLACEMENTS", "ORGANIZATIONSTATE", "HABITVISUALIDENTITIES")
        )
    }.let { effective ->
        if (workspaceRevision == null) effective else org.isoron.uhabits.core.models.projection.EffectiveReadDatabase(
            effective, workspaceRevision, setOf("USERTASKS", "CONTAINERS", "ORGANIZATIONSTATE", "HABITPLACEMENTS", "HABITVISUALIDENTITIES"),
        )
    }.let { workspace ->
        if (organizationRevision == null) workspace else org.isoron.uhabits.core.models.projection.EffectiveReadDatabase(
            workspace, organizationRevision, setOf("CONTAINERS", "ORGANIZATIONSTATE", "HABITPLACEMENTS", "HABITVISUALIDENTITIES"),
        )
    }

    override fun prepareStatement(sql: String): PreparedStatement =
        connection.prepareStatement(sql)

    override fun close() = connection.close()
}
