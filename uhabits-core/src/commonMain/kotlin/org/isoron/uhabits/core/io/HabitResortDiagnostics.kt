package org.isoron.uhabits.core.io

import org.isoron.platform.ThreadLocalState
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** QA-only synchronous, thread-local attribution. No collectors exist unless explicitly enabled. */
object HabitResortDiagnostics {
    data class Span(val stage: String, val subject: String?, val wallNanos: Long, val threadCpuNanos: Long?, val depth: Int)
    data class Report(val operation: Long?, val counters: Map<String, Long>, val spans: List<Span>)
    class Token internal constructor(internal val stage: String, internal val subject: String?, internal val wall: Long,
        internal val cpu: Long?, internal val depth: Int)
    private class Collector(val operation: Long?) {
        val counters = mutableMapOf<String, Long>()
        val spans = mutableListOf<Span>()
        var depth = 0
    }
    @Volatile var observer: ((Report) -> Unit)? = null
    @Volatile var wallClockNanos: (() -> Long)? = null
    @Volatile var threadCpuClockNanos: (() -> Long)? = null
    @Volatile var subjectIdentity: ((Any) -> String)? = null
    @Volatile var spanBoundary: ((String, String?, Boolean) -> Unit)? = null
    private val origin = TimeSource.Monotonic.markNow()
    private val local = ThreadLocalState { mutableListOf<Collector>() }
    val isCollecting: Boolean get() = observer != null && local.get().isNotEmpty()
    val currentOperation: Long? get() = if (observer == null) null else local.get().lastOrNull()?.operation
    private fun wall() = runCatching { wallClockNanos?.invoke() }.getOrNull() ?: origin.elapsedNow().inWholeNanoseconds
    private fun cpu() = runCatching { threadCpuClockNanos?.invoke() }.getOrNull()

    fun count(stage: String, amount: Long = 1) {
        if (observer == null) return
        val collector = local.get().lastOrNull() ?: return
        collector.counters[stage] = (collector.counters[stage] ?: 0L) + amount
    }
    fun begin(stage: String, subject: Any? = null): Token? {
        if (observer == null) return null
        val collector = local.get().lastOrNull() ?: return null
        val identity = subject?.let { runCatching { subjectIdentity?.invoke(it) ?: "${it::class.simpleName}:${it.hashCode()}" }.getOrNull() }
        runCatching { spanBoundary?.invoke(stage, identity, true) }
        return Token(stage, identity, wall(), cpu(), collector.depth++)
    }
    fun end(token: Token?) {
        if (token == null) return
        val collector = local.get().lastOrNull() ?: return
        val wallEnd = wall()
        val cpuEnd = cpu()
        collector.depth--
        collector.spans.add(Span(token.stage, token.subject, wallEnd - token.wall,
            if (token.cpu != null && cpuEnd != null) cpuEnd - token.cpu else null, token.depth))
        runCatching { spanBoundary?.invoke(token.stage, token.subject, false) }
    }
    inline fun <T> measure(stage: String, subject: Any? = null, action: () -> T): T {
        val token = begin(stage, subject)
        try { return action() } finally { end(token) }
    }
    fun <T> collect(operation: Long?, action: () -> T): T {
        val target = observer ?: return action()
        val previous = local.get().lastOrNull()
        if (previous != null) return action()
        val collector = Collector(operation)
        local.get().add(collector)
        try { return measure("command.resortAndObservers", action = action) } finally {
            local.get().removeAt(local.get().lastIndex)
            runCatching { target(Report(operation, collector.counters.toMap(), collector.spans.toList())) }
        }
    }
}

