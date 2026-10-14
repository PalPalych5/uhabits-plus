/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.models.projection

import org.isoron.platform.io.Database
import org.isoron.platform.Synchronized
import kotlin.concurrent.Volatile
import org.isoron.platform.ThreadLocalState
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.queryLong
import org.isoron.uhabits.core.containers.HabitRef

/** Dataset-owned revision. Disposal makes old callbacks inert. Consumers schedule their own refresh. */
class EffectiveReadRevision(val datasetId: String) {
    @Volatile private var currentValue: Long = 0
    val value: Long
        @Synchronized get() = currentValue
    /** Version-only observation; not a listener-completion barrier. */
    val observedValue: Long get() = currentValue
    private val listeners = linkedSetOf<() -> Unit>()
    private val rollbackListeners = linkedSetOf<() -> Unit>()
    private var disposed = false

    private val revisionHistory = mutableMapOf<Long, Set<HabitRef>?>()
    private val maxHistoryEntries = 256

    @Synchronized fun addListener(listener: () -> Unit) { if (!disposed) listeners.add(listener) }
    @Synchronized fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    @Synchronized fun addRollbackListener(listener: () -> Unit) { if (!disposed) rollbackListeners.add(listener) }
    @Synchronized fun removeRollbackListener(listener: () -> Unit) { rollbackListeners.remove(listener) }

    /** Restore speculative model caches after rollback without inventing a durable revision. */
    @Synchronized fun rolledBack() {
        if (!disposed) {
            revisionHistory.clear()
            rollbackListeners.toList().forEach { runCatching { it() } }
        }
    }

    @Synchronized fun changed(affectedHabits: Set<HabitRef>? = null) {
        if (disposed) return
        currentValue++
        revisionHistory[currentValue] = affectedHabits?.toSet()
        if (revisionHistory.size > maxHistoryEntries) {
            val minKey = revisionHistory.keys.minOrNull()
            if (minKey != null) revisionHistory.remove(minKey)
        }
        listeners.toList().forEach { runCatching { it() } }
    }

    /**
     * Returns the complete set of habits affected between [fromRevision] and [toRevision].
     * Returns an empty set if [fromRevision] == [toRevision].
     * Returns null (unknown scope / conservative fallback) if:
     * - Any revision in (fromRevision..toRevision] was an unscoped/global change (null affectedHabits)
     * - Any revision in (fromRevision..toRevision] has been evicted or is missing from history
     * - fromRevision > toRevision or fromRevision < 0
     */
    @Synchronized fun affectedHabitsBetween(fromRevision: Long, toRevision: Long): Set<HabitRef>? {
        if (fromRevision == toRevision) return emptySet()
        if (fromRevision > toRevision || fromRevision < 0L) return null
        val result = mutableSetOf<HabitRef>()
        for (r in (fromRevision + 1)..toRevision) {
            if (!revisionHistory.containsKey(r)) return null
            val affected = revisionHistory[r] ?: return null
            result.addAll(affected)
        }
        return result
    }

    @Synchronized fun dispose() {
        disposed = true
        revisionHistory.clear()
        listeners.clear()
        rollbackListeners.clear()
    }

    companion object {
        private val mutationScopeStack = ThreadLocalState { mutableListOf<Set<HabitRef>>() }

        fun <T> withMutationScope(habits: Set<HabitRef>, block: () -> T): T {
            val stack = mutationScopeStack.get()
            stack.add(habits)
            try {
                return block()
            } finally {
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            }
        }

        fun activeScope(): Set<HabitRef>? = mutationScopeStack.get().lastOrNull()
    }
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
    private data class Frame(
        val name: String?,
        var changed: Boolean = false,
        var successful: Boolean = false,
        var failed: Boolean = false,
        var poisonedScope: Boolean = false,
        var affectedHabits: MutableSet<HabitRef>? = null
    )
    private val transactionFrames = ThreadLocalState { mutableListOf<Frame>() }
    private val frames get() = transactionFrames.get()
    private val mutation = Regex("^(?:INSERT(?:\\s+OR\\s+\\w+)?\\s+INTO|REPLACE\\s+INTO|UPDATE(?:\\s+OR\\s+\\w+)?|DELETE\\s+FROM)\\s+[\"`\\[]?(\\w+)", RegexOption.IGNORE_CASE)
    private val tables = observedTables.map { it.uppercase() }.toSet()
    private val globalInvalidationTables = setOf("APPSETTINGS", "CONTAINERS", "ORGANIZATIONSTATE", "HABITPLACEMENTS")

    override fun beginTransaction() {
        // SQLite may wait for another thread's writer. Never hold an observer
        // monitor here: that writer needs this adapter to finish its transaction.
        delegate.beginTransaction()
        frames.add(Frame(null))
    }

    override fun setTransactionSuccessful() {
        delegate.setTransactionSuccessful()
        if (frames.isNotEmpty()) frames.last().successful = true
    }

    override fun endTransaction() {
        val frame = if (frames.isNotEmpty()) frames.removeAt(frames.lastIndex) else null
        val wasSuccessful = frame?.successful == true && !frame.failed
        try {
            delegate.endTransaction()
            if (frames.isEmpty()) {
                if (wasSuccessful && frame?.changed == true) {
                    val scope = if (frame.poisonedScope) null else frame.affectedHabits
                    revision.changed(scope)
                } else if (!wasSuccessful && frame?.changed == true) {
                    revision.rolledBack()
                }
            } else {
                if (frame?.changed == true) {
                    val parent = frames.last()
                    parent.changed = true
                    if (frame.poisonedScope) {
                        parent.poisonedScope = true
                        parent.affectedHabits = null
                    } else if (!parent.poisonedScope && frame.affectedHabits != null) {
                        if (parent.affectedHabits == null) parent.affectedHabits = mutableSetOf()
                        parent.affectedHabits!!.addAll(frame.affectedHabits!!)
                    }
                }
                if (!wasSuccessful) frames.last().failed = true
            }
        } catch (t: Throwable) {
            if (frame?.changed == true) revision.rolledBack()
            throw t
        }
    }

    override fun inTransaction(): Boolean = delegate.inTransaction()

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
                    val platformFailed = frames.drop(index).any { it.failed }
                    while (frames.size > index + 1) frames.removeAt(frames.lastIndex)
                    frames[index].changed = false
                    frames[index].affectedHabits = null
                    frames[index].poisonedScope = false
                    // SQL rollback-to cannot undo SQLiteSession's rollback-only
                    // state after an unsuccessful nested platform transaction.
                    if (platformFailed) frames[index].failed = true
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
                val failed = frames.drop(index).any { it.failed }
                val poisoned = frames.drop(index).any { it.poisonedScope }
                val affected = if (poisoned) null else {
                    val set = mutableSetOf<HabitRef>()
                    var hasScope = false
                    for (i in index until frames.size) {
                        val f = frames[i]
                        if (f.affectedHabits != null) {
                            set.addAll(f.affectedHabits!!)
                            hasScope = true
                        }
                    }
                    if (hasScope) set else null
                }
                while (frames.size > index) frames.removeAt(frames.lastIndex)
                if (frames.isEmpty()) {
                    if (changed) {
                        if (failed) revision.rolledBack() else revision.changed(affected)
                    }
                } else {
                    if (changed) {
                        val parent = frames.last()
                        parent.changed = true
                        if (poisoned) {
                            parent.poisonedScope = true
                            parent.affectedHabits = null
                        } else if (!parent.poisonedScope && affected != null) {
                            if (parent.affectedHabits == null) parent.affectedHabits = mutableSetOf()
                            parent.affectedHabits!!.addAll(affected)
                        }
                    }
                    if (failed) frames.last().failed = true
                }
            }
            "COMMIT", "END" -> {
                val changed = frames.any { it.changed }
                val failed = frames.any { it.failed }
                val poisoned = frames.any { it.poisonedScope }
                val affected = if (poisoned) null else {
                    val set = mutableSetOf<HabitRef>()
                    var hasScope = false
                    for (f in frames) {
                        if (f.affectedHabits != null) {
                            set.addAll(f.affectedHabits!!)
                            hasScope = true
                        }
                    }
                    if (hasScope) set else null
                }
                frames.clear()
                if (changed) {
                    if (failed) revision.rolledBack() else revision.changed(affected)
                }
            }
            else -> {
                val table = mutation.find(normalized)?.groupValues?.get(1)?.uppercase()
                if (table in tables && delegate.queryLong("SELECT changes()") > 0L) markChanged(table)
            }
        }
    }

    private fun markChanged(table: String?) {
        val isGlobal = table in globalInvalidationTables
        val active = if (isGlobal) null else EffectiveReadRevision.activeScope()
        if (frames.isEmpty()) {
            revision.changed(active)
        } else {
            val frame = frames.last()
            frame.changed = true
            if (active == null) {
                frame.poisonedScope = true
                frame.affectedHabits = null
            } else if (!frame.poisonedScope) {
                if (frame.affectedHabits == null) frame.affectedHabits = mutableSetOf()
                frame.affectedHabits!!.addAll(active)
            }
        }
    }
    override fun close() { transactionFrames.clear(); revision.dispose(); delegate.close() }
}
