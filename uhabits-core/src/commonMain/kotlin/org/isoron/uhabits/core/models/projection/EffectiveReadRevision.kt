/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.models.projection

import org.isoron.platform.io.Database
import org.isoron.platform.Synchronized
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.queryLong

/** Dataset-owned revision. Disposal makes old callbacks inert. Consumers schedule their own refresh. */
class EffectiveReadRevision(val datasetId: String) {
    private var currentValue: Long = 0
    val value: Long
        @Synchronized get() = currentValue
    private val listeners = linkedSetOf<() -> Unit>()
    private val rollbackListeners = linkedSetOf<() -> Unit>()
    private var disposed = false
    @Synchronized fun addListener(listener: () -> Unit) { if (!disposed) listeners.add(listener) }
    @Synchronized fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    @Synchronized fun addRollbackListener(listener: () -> Unit) { if (!disposed) rollbackListeners.add(listener) }
    @Synchronized fun removeRollbackListener(listener: () -> Unit) { rollbackListeners.remove(listener) }
    /** Restore speculative model caches after rollback without inventing a durable revision. */
    @Synchronized fun rolledBack() { if (!disposed) rollbackListeners.toList().forEach { runCatching { it() } } }
    @Synchronized fun changed() {
        if (disposed) return
        currentValue++
        listeners.toList().forEach { runCatching { it() } }
    }
    @Synchronized fun dispose() { disposed = true; listeners.clear(); rollbackListeners.clear() }
}

/**
 * Observes successful SQL on the dataset's shared connection, including caller-owned transactions.
 * A RELEASE of an inner savepoint is not a commit notification. All writers must use this connection;
 * transactions opened through another API/connection are outside this adapter's contract.
 */
class EffectiveReadDatabase(
    private val delegate: Database,
    private val revision: EffectiveReadRevision,
    observedTables: Set<String> = setOf("CONTRIBUTIONS", "REPETITIONS", "HABITS", "HABITGOALS", "HABITEXTENSIONS", "APPSETTINGS"),
) : Database {
    private data class Frame(val name: String?, var changed: Boolean = false)
    private val frames = mutableListOf<Frame>()
    private val mutation = Regex("^(?:INSERT(?:\\s+OR\\s+\\w+)?\\s+INTO|REPLACE\\s+INTO|UPDATE(?:\\s+OR\\s+\\w+)?|DELETE\\s+FROM)\\s+[\"`\\[]?(\\w+)", RegexOption.IGNORE_CASE)
    private val tables = observedTables.map { it.uppercase() }.toSet()

    override fun prepareStatement(sql: String): PreparedStatement {
        val statement = delegate.prepareStatement(sql)
        // SELECT cannot change transaction frames or invalidate effective values. In
        // particular, do not serialize every returned history row with writers and
        // recompile/reparse SQL on each step while the UI is reading another query.
        if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) return statement
        return object : PreparedStatement by statement {
            override fun step(): org.isoron.platform.io.StepResult {
                return observedStep(sql, statement)
            }
        }
    }

    @Synchronized
    private fun observedStep(sql: String, statement: PreparedStatement): org.isoron.platform.io.StepResult {
        check(!sql.trimStart().uppercase().startsWith("BEGIN") || frames.isEmpty()) {
            "Nested BEGIN is unsupported; use SAVEPOINT on the shared connection"
        }
        val result = statement.step() // Failed writes must never publish or dirty a frame.
        observed(sql)
        return result
    }

    private fun observed(sql: String) {
        val normalized = sql.trim().trimEnd(';').uppercase()
        val words = normalized.split(Regex("\\s+"))
        when (words.firstOrNull()) {
            "BEGIN" -> frames.add(Frame(null))
            "SAVEPOINT" -> frames.add(Frame(words[1].trim('"', '`', '[', ']')))
            "ROLLBACK" -> {
                if (words.contains("TO")) {
                    val name = words.last().trim('"', '`', '[', ']')
                    val index = frames.indexOfLast { it.name == name }
                    check(index >= 0) { "Unobserved savepoint rollback" }
                    val discardedChanges = frames.drop(index).any { it.changed }
                    while (frames.size > index + 1) frames.removeAt(frames.lastIndex)
                    frames[index].changed = false
                    if (discardedChanges) revision.rolledBack()
                } else {
                    val discardedChanges = frames.any { it.changed }
                    frames.clear()
                    if (discardedChanges) revision.rolledBack()
                }
            }
            "RELEASE" -> {
                val name = words.last().trim('"', '`', '[', ']')
                val index = frames.indexOfLast { it.name == name }
                check(index >= 0) { "Unobserved savepoint release" }
                val changed = frames.drop(index).any { it.changed }
                while (frames.size > index) frames.removeAt(frames.lastIndex)
                if (changed) markChanged()
            }
            "COMMIT", "END" -> {
                val changed = frames.any { it.changed }
                frames.clear()
                if (changed) revision.changed()
            }
            else -> {
                val table = mutation.find(normalized)?.groupValues?.get(1)
                if (table in tables && delegate.queryLong("SELECT changes()") > 0L) markChanged()
            }
        }
    }

    private fun markChanged() {
        if (frames.isEmpty()) revision.changed() else frames.last().changed = true
    }
    override fun close() { frames.clear(); revision.dispose(); delegate.close() }
}
