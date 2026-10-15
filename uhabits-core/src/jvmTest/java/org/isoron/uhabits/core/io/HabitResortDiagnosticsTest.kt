package org.isoron.uhabits.core.io

import org.isoron.uhabits.core.models.ModelObservable
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HabitResortDiagnosticsTest {
    private fun isolated(action: () -> Unit) {
        val observer = HabitResortDiagnostics.observer
        val wall = HabitResortDiagnostics.wallClockNanos
        val cpu = HabitResortDiagnostics.threadCpuClockNanos
        val identity = HabitResortDiagnostics.subjectIdentity
        val boundary = HabitResortDiagnostics.spanBoundary
        try {
            HabitResortDiagnostics.observer = null
            HabitResortDiagnostics.wallClockNanos = null
            HabitResortDiagnostics.threadCpuClockNanos = null
            HabitResortDiagnostics.subjectIdentity = null
            HabitResortDiagnostics.spanBoundary = null
            action()
        } finally {
            HabitResortDiagnostics.observer = observer
            HabitResortDiagnostics.wallClockNanos = wall
            HabitResortDiagnostics.threadCpuClockNanos = cpu
            HabitResortDiagnostics.subjectIdentity = identity
            HabitResortDiagnostics.spanBoundary = boundary
        }
    }

    @Test fun disabledCollectionNeverInvokesDiagnosticHooks() = isolated {
        var hookCalls = 0
        HabitResortDiagnostics.wallClockNanos = { hookCalls++; 0L }
        HabitResortDiagnostics.threadCpuClockNanos = { hookCalls++; 0L }
        HabitResortDiagnostics.subjectIdentity = { hookCalls++; "unexpected" }
        HabitResortDiagnostics.spanBoundary = { _, _, _ -> hookCalls++ }
        var calls = 0
        val observable = ModelObservable().apply { addListener { calls++ } }
        val value = HabitResortDiagnostics.collect(1) {
            assertFalse(HabitResortDiagnostics.isCollecting)
            assertNull(HabitResortDiagnostics.currentOperation)
            assertNull(HabitResortDiagnostics.begin("disabled", observable))
            HabitResortDiagnostics.count("disabled")
            HabitResortDiagnostics.measure("disabled", observable) { observable.notifyListeners(); 17 }
        }
        assertEquals(17, value)
        assertEquals(1, calls)
        assertEquals("Disabled collection must not call any probe hook", 0, hookCalls)
    }

    @Test fun collectionIsCommandThreadLocalAndIgnoresUnscopedWork() = isolated {
        val reports = mutableListOf<HabitResortDiagnostics.Report>()
        HabitResortDiagnostics.observer = { reports.add(it) }
        val error = AtomicReference<Throwable?>()
        HabitResortDiagnostics.count("outside")
        HabitResortDiagnostics.collect(42) {
            HabitResortDiagnostics.count("command", 2)
            val unrelated = Thread {
                try {
                    assertFalse(HabitResortDiagnostics.isCollecting)
                    assertNull(HabitResortDiagnostics.currentOperation)
                    assertNull(HabitResortDiagnostics.begin("unrelated"))
                    HabitResortDiagnostics.measure("unrelated") { HabitResortDiagnostics.count("unrelated", 99) }
                } catch (t: Throwable) { error.set(t) }
            }
            unrelated.start(); unrelated.join(2000)
            assertFalse(unrelated.isAlive)
            if (error.get() != null) throw AssertionError("Unrelated thread", error.get())
            assertEquals(42L, HabitResortDiagnostics.currentOperation)
        }
        assertEquals(1, reports.size)
        assertEquals(42L, reports.single().operation)
        assertEquals(mapOf("command" to 2L), reports.single().counters)
        assertEquals(listOf("command.resortAndObservers"), reports.single().spans.map { it.stage })
        assertFalse(HabitResortDiagnostics.isCollecting)
    }

    @Test fun nestedSpansKeepDepthCpuAndOuterOperationWithoutDuplicateReports() = isolated {
        val reports = mutableListOf<HabitResortDiagnostics.Report>()
        val boundaries = mutableListOf<String>()
        var wall = 0L
        var cpu = 0L
        HabitResortDiagnostics.observer = { reports.add(it) }
        HabitResortDiagnostics.wallClockNanos = { wall += 10; wall }
        HabitResortDiagnostics.threadCpuClockNanos = { cpu += 3; cpu }
        HabitResortDiagnostics.subjectIdentity = { "stable-subject" }
        HabitResortDiagnostics.spanBoundary = { stage, subject, begin -> boundaries.add("$stage:$subject:$begin") }
        HabitResortDiagnostics.collect(7) {
            HabitResortDiagnostics.measure("outer", Any()) {
                HabitResortDiagnostics.collect(999) {
                    assertEquals(7L, HabitResortDiagnostics.currentOperation)
                    HabitResortDiagnostics.measure("inner") { HabitResortDiagnostics.count("nested") }
                }
            }
        }
        val report = reports.single()
        assertEquals(7L, report.operation)
        assertEquals(listOf("inner", "outer", "command.resortAndObservers"), report.spans.map { it.stage })
        assertEquals(listOf(2, 1, 0), report.spans.map { it.depth })
        assertEquals(listOf(10L, 30L, 50L), report.spans.map { it.wallNanos })
        assertEquals(listOf(3L, 9L, 15L), report.spans.map { it.threadCpuNanos })
        assertEquals("stable-subject", report.spans[1].subject)
        assertEquals(mapOf("nested" to 1L), report.counters)
        assertEquals(listOf("command.resortAndObservers:null:true", "outer:stable-subject:true", "inner:null:true",
            "inner:null:false", "outer:stable-subject:false", "command.resortAndObservers:null:false"), boundaries)
    }

    @Test fun enabledProbePreservesDuplicateOrderAndListenerExceptionsAndClearsScope() = isolated {
        val reports = mutableListOf<HabitResortDiagnostics.Report>()
        HabitResortDiagnostics.observer = { reports.add(it); error("Observer must not replace command outcome") }
        val events = mutableListOf<String>()
        val observable = ModelObservable()
        val duplicate = ModelObservable.Listener { events.add("duplicate") }
        val expected = IllegalStateException("listener failure")
        observable.addListener(duplicate); observable.addListener(duplicate)
        observable.addListener { events.add("throws"); throw expected }
        observable.addListener { events.add("unreachable") }
        try {
            HabitResortDiagnostics.collect(8) { observable.notifyListeners() }
            fail("Listener exception must propagate")
        } catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals(listOf("duplicate", "duplicate", "throws"), events)
        val report = reports.single()
        assertEquals(1L, report.counters["observable.notifications"])
        assertEquals(3L, report.counters["observable.callbacks"])
        assertEquals(3, report.spans.count { it.stage == "observable.callback" })
        assertFalse(HabitResortDiagnostics.isCollecting)
        assertNull(HabitResortDiagnostics.currentOperation)
        HabitResortDiagnostics.count("leaked")
        assertNull(HabitResortDiagnostics.begin("leaked"))
        events.clear()
        observable.removeListener(duplicate)
        try { observable.notifyListeners() } catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals(listOf("duplicate", "throws"), events)
        assertEquals(1, reports.size)
        assertEquals(23, HabitResortDiagnostics.collect(9) { 23 })
        assertEquals(2, reports.size)
        assertEquals(listOf(0), reports.last().spans.map { it.depth })
        assertTrue(reports.last().counters.isEmpty())
    }
}
