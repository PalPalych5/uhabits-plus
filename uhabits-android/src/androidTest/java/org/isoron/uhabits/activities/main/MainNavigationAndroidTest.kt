/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.main

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun workspaceTabsNavigateAndRestoreSelection() {
        assertTrue("Requires an isolated Next fixture", HabitsApplication.component.session.isExperimental)
        val intent = MainActivity.intent(instrumentation.targetContext, MainDestination.HABITS)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            assertSelected("btnNavHabits")
            clickTab("btnNavTasks")
            assertSelected("btnNavTasks")
            clickTab("btnNavReports")
            assertSelected("btnNavReports")

            scenario.recreate()
            assertSelected("btnNavReports")
            device.pressBack()
            assertSelected("btnNavTasks")
            device.pressBack()
            assertSelected("btnNavHabits")
        }
    }

    private fun clickTab(tag: String) {
        val tab = checkNotNull(device.wait(Until.findObject(By.res(tag)), 5_000L)) {
            "Workspace tab is unavailable: $tag"
        }
        tab.click()
    }

    private fun assertSelected(tag: String) {
        assertTrue(
            "Workspace tab must be selected: $tag",
            device.wait(Until.hasObject(By.res(tag).selected(true)), 5_000L)
        )
    }
}
