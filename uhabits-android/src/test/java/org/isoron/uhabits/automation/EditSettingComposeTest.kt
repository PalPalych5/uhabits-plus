/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.automation

import org.isoron.uhabits.BaseAndroidJVMTest
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class EditSettingComposeTest : BaseAndroidJVMTest() {

    @Test
    fun mapsSpinnerPositionsAndActionsSymmetrically() {
        assertEquals(ACTION_CHECK, mapAutomationSpinnerPositionToAction(isNumerical = false, itemPosition = 0))
        assertEquals(ACTION_UNCHECK, mapAutomationSpinnerPositionToAction(isNumerical = false, itemPosition = 1))
        assertEquals(ACTION_TOGGLE, mapAutomationSpinnerPositionToAction(isNumerical = false, itemPosition = 2))
        assertEquals(ACTION_INCREMENT, mapAutomationSpinnerPositionToAction(isNumerical = true, itemPosition = 0))
        assertEquals(ACTION_DECREMENT, mapAutomationSpinnerPositionToAction(isNumerical = true, itemPosition = 1))

        assertEquals(0, mapAutomationActionToSpinnerPosition(ACTION_CHECK))
        assertEquals(1, mapAutomationActionToSpinnerPosition(ACTION_UNCHECK))
        assertEquals(2, mapAutomationActionToSpinnerPosition(ACTION_TOGGLE))
        assertEquals(0, mapAutomationActionToSpinnerPosition(ACTION_INCREMENT))
        assertEquals(1, mapAutomationActionToSpinnerPosition(ACTION_DECREMENT))
    }

    @Test
    fun resolvesInitialSelectionFromArguments() {
        val modelFactory = MemoryModelFactory()
        val h1 = modelFactory.buildHabit().apply { id = 10L; name = "Read" }
        val h2 = modelFactory.buildHabit().apply { id = 20L; name = "Run" }
        val habits = listOf(h1, h2)

        assertEquals(0 to 0, resolveInitialAutomationSelection(habits, null))
        assertEquals(
            1 to 2,
            resolveInitialAutomationSelection(habits, SettingUtils.Arguments(ACTION_TOGGLE, h2)),
        )
    }
}
