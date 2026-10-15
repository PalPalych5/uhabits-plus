package org.isoron.uhabits.core.models

import org.isoron.uhabits.core.io.HabitResortDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class HabitResortCoarseDiagnosticsTest {
    @Test
    fun coarseBoundariesBalanceWhenNestedMeasuredActionThrowsWithoutCollector() {
        val previousCoarse = HabitResortDiagnostics.coarseBoundary
        val previousObserver = HabitResortDiagnostics.observer
        val boundaries = mutableListOf<Pair<String, Boolean>>()
        val failure = IllegalStateException("measured action failed")
        try {
            HabitResortDiagnostics.observer = null
            HabitResortDiagnostics.coarseBoundary = { stage, begin -> boundaries.add(stage to begin) }
            val thrown = assertFailsWith<IllegalStateException> {
                HabitResortDiagnostics.measure("memory.notify") {
                    HabitResortDiagnostics.measure("memory.sort") { throw failure }
                }
            }
            assertSame(failure, thrown)
            assertEquals(listOf("memory.notify" to true, "memory.sort" to true,
                "memory.sort" to false, "memory.notify" to false), boundaries)
        } finally {
            HabitResortDiagnostics.coarseBoundary = previousCoarse
            HabitResortDiagnostics.observer = previousObserver
        }
    }
}
