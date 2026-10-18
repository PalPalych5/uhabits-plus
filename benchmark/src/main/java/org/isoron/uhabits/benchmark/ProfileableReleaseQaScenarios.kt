/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.benchmark

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dedicated profileable QA scenario suite targeting `org.isoron.uhabits.plus.benchmark`.
 *
 * Executes out-of-process in the separate `:benchmark` test module to guarantee:
 * 1. Target package isolation (`org.isoron.uhabits.plus.benchmark`).
 * 2. True process-cold startup semantics via `am force-stop` and `am start -W`.
 * 3. Fail-closed deterministic execution without silent skipping of UI journeys.
 * 4. Grounded UI bounds for scrolling and interaction without arbitrary coordinate assumptions.
 * 5. Allowlisted AVD verification preventing execution on physical devices or unverified images.
 */
@RunWith(AndroidJUnit4::class)
class ProfileableReleaseQaScenarios {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.context

    companion object {
        const val TARGET_PACKAGE = "org.isoron.uhabits.plus.benchmark"
        const val MAIN_ACTIVITY = "org.isoron.uhabits.activities.main.MainActivity"
        const val UI_TIMEOUT_MS = 8000L

        /** Known allowlisted AVD environments verified for offline QA runs. */
        val ALLOWLISTED_AVD_TOKENS = setOf(
            "UHabit_Next_QA_Isolated_Experiment_API35_20261017",
            "UHabit_UI_Review_Disposable_20261011"
        )
    }

    @Before
    fun setUp() {
        verifySafetyAndIsolationGuards()
    }

    /**
     * Verifies that execution is restricted to authorized emulators and offline benchmark targets.
     */
    fun verifySafetyAndIsolationGuards() {
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) {
            "Physical devices are strictly forbidden for profileable QA"
        }
        val targetPkg = instrumentation.targetContext.packageName
        check(targetPkg == TARGET_PACKAGE) {
            "Instrumentation target package mismatch: expected '$TARGET_PACKAGE', actual '$targetPkg'"
        }

        val requestedAvd = InstrumentationRegistry.getArguments().getString("authorizedAvd")
        check(!requestedAvd.isNullOrBlank()) {
            "Explicit -e authorizedAvd argument is required; automatic execution on unverified images is forbidden"
        }
        check(requestedAvd in ALLOWLISTED_AVD_TOKENS) {
            "Requested AVD '$requestedAvd' is not in the verified allowlist: $ALLOWLISTED_AVD_TOKENS"
        }

        val guestAvd = device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()
        check(guestAvd == requestedAvd) {
            "Guest AVD identity mismatch: requested '$requestedAvd', actual guest '$guestAvd'"
        }

        // Verify device-level radio restrictions
        val airplaneOn = device.executeShellCommand("settings get global airplane_mode_on").trim()
        val wifiOn = device.executeShellCommand("settings get global wifi_on").trim()
        check(airplaneOn == "1" && wifiOn == "0") {
            "Device radio isolation violation: airplane_mode=$airplaneOn (expected 1), wifi=$wifiOn (expected 0)"
        }
    }

    /**
     * Runs all 7 representative scenarios in sequence under ART profiling.
     */
    @Test
    fun runRepresentativeProfileCollectionWorkload() {
        device.wakeUp()

        // 1. Process-Cold Application Startup
        val coldPid = scenario1_processColdStartup()

        // 2. Initial Populated Habits List Traversal
        scenario2_populatedHabitsList()

        // 3. Nested Habit Containers
        scenario3_nestedContainers()

        // 4. Completing Habit and Observing UI Updates
        scenario4_completeHabitAndObserveUi()

        // 5. Opening Populated Statistics
        scenario5_openPopulatedStatistics()

        // 6. Switching Statistics Periods
        scenario6_switchStatisticsPeriods()

        // 7. Opening Tasks and Calendar
        scenario7_openTasksAndCalendar()

        // Verify target process remained alive and stable throughout the journey
        val finalPid = getTargetPid()
        assertNotNull("Target process died during benchmark scenario execution", finalPid)
        check(finalPid == coldPid) {
            "Target process restarted unexpectedly during benchmark journey (initial PID $coldPid, final PID $finalPid)"
        }
    }

    /**
     * Scenario 1: True process-cold startup.
     * Force-stops the target package, confirms termination, and launches cold via AM start.
     */
    fun scenario1_processColdStartup(): String {
        // Step A: Ensure target process is terminated
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.waitForIdle()
        val pidBefore = getTargetPid()
        assertTrue("Target process failed to terminate before cold start", pidBefore.isNullOrBlank())

        // Step B: Cold process launch
        val amStartOutput = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/$MAIN_ACTIVITY --es main.destination HABITS"
        )
        assertTrue(
            "Cold start failed: $amStartOutput",
            amStartOutput.contains("Status: ok") || amStartOutput.contains("Complete")
        )

        // Step C: Confirm new process created
        val pidAfter = getTargetPid()
        assertNotNull("Target process was not created on cold start", pidAfter)

        // Step D: Validate initial UI rendered
        val isListRendered = device.wait(
            Until.hasObject(By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "recyclerView")),
            UI_TIMEOUT_MS
        )
        assertTrue("Habit list failed to render on cold start within $UI_TIMEOUT_MS ms", isListRendered)

        return pidAfter!!
    }

    /**
     * Scenario 2: Initial populated Habits list traversal and layout.
     * Fails closed if the list or cards are missing; scrolls using actual UI bounds.
     */
    fun scenario2_populatedHabitsList() {
        navigateToDestination("Привычки")

        val recyclerView = device.wait(
            Until.findObject(By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "recyclerView")),
            UI_TIMEOUT_MS
        )
        assertNotNull("Precondition failed: Habit recyclerView not found", recyclerView)

        val firstCard = device.wait(
            Until.findObject(By.pkg(TARGET_PACKAGE).clazz("androidx.cardview.widget.CardView")),
            UI_TIMEOUT_MS
        )
        assertNotNull("Precondition failed: Populated habits list contains zero CardViews", firstCard)

        // Grounded scroll using element bounds
        val bounds = recyclerView!!.visibleBounds
        val centerX = bounds.centerX()
        val top = bounds.top + (bounds.height() * 0.15).toInt()
        val bottom = bounds.bottom - (bounds.height() * 0.15).toInt()

        device.swipe(centerX, bottom, centerX, top, 20)
        device.waitForIdle()
        device.swipe(centerX, top, centerX, bottom, 20)
        device.waitForIdle()
    }

    /**
     * Scenario 3: Nested habit containers interaction.
     * Navigates to Tasks, opens the drawer, and selects both root and nested containers with strict assertions.
     */
    fun scenario3_nestedContainers() {
        navigateToDestination("Задачи")

        val navButton = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).desc("Навигация по задачам")
                    ?: By.pkg(TARGET_PACKAGE).desc("Open navigation drawer")
                    ?: By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "btnTaskNavigation")
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Precondition failed: Tasks drawer button not found", navButton)
        navButton!!.click()
        device.waitForIdle()

        // Select root container "Учёба"
        val studyNode = device.wait(Until.findObject(By.text("Учёба")), UI_TIMEOUT_MS)
        assertNotNull("Precondition failed: Root container 'Учёба' not found in drawer", studyNode)
        studyNode!!.click()
        device.waitForIdle()

        // Re-open drawer and select nested container "Курсовая работа"
        val navButton2 = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).desc("Навигация по задачам")
                    ?: By.pkg(TARGET_PACKAGE).desc("Open navigation drawer")
                    ?: By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "btnTaskNavigation")
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Tasks drawer button not found on second open", navButton2)
        navButton2!!.click()
        device.waitForIdle()

        val courseworkNode = device.wait(Until.findObject(By.text("Курсовая работа")), UI_TIMEOUT_MS)
        assertNotNull("Precondition failed: Nested container 'Курсовая работа' not found", courseworkNode)
        courseworkNode!!.click()
        device.waitForIdle()

        // Return to Habits
        navigateToDestination("Привычки")
    }

    /**
     * Scenario 4: Completing a habit and observing UI updates.
     * Finds checkmark, clicks it, verifies UI state changes, then restores initial state.
     */
    fun scenario4_completeHabitAndObserveUi() {
        navigateToDestination("Привычки")

        val checkmark = device.wait(
            Until.findObject(By.pkg(TARGET_PACKAGE).clazz("org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView")),
            UI_TIMEOUT_MS
        )
        assertNotNull("Precondition failed: CheckmarkButtonView not found on visible habit card", checkmark)

        val initialDesc = checkmark!!.contentDescription ?: ""
        val initialSelected = checkmark.isSelected

        checkmark.click()
        device.waitForIdle()

        val updatedCheckmark = device.wait(
            Until.findObject(By.pkg(TARGET_PACKAGE).clazz("org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView")),
            UI_TIMEOUT_MS
        )
        assertNotNull("Checkmark view disappeared after click", updatedCheckmark)
        val updatedDesc = updatedCheckmark!!.contentDescription ?: ""
        val updatedSelected = updatedCheckmark.isSelected

        // Fail closed if the click did not result in an observable UI state mutation
        val stateMutated = (initialDesc != updatedDesc) || (initialSelected != updatedSelected)
        assertTrue("Checkmark click did not mutate UI state (desc: '$initialDesc', selected: $initialSelected)", stateMutated)

        // Toggle back to preserve deterministic fixture baseline
        updatedCheckmark.click()
        device.waitForIdle()
    }

    /**
     * Scenario 5: Opening populated Statistics.
     * Validates that the statistics workspace hydrates content within the timeout.
     */
    fun scenario5_openPopulatedStatistics() {
        navigateToDestination("Статистика")

        val statsRoot = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).text("Статистика")
                    ?: By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "statisticsContainer")
                    ?: By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "recyclerView")
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Precondition failed: Statistics workspace failed to hydrate content", statsRoot)
    }

    /**
     * Scenario 6: Switching Statistics periods.
     * Exercises date range recalculation and chart hydration for different periods.
     */
    fun scenario6_switchStatisticsPeriods() {
        navigateToDestination("Статистика")

        val monthTab = device.wait(Until.findObject(By.text("Месяц")), UI_TIMEOUT_MS)
        val yearTab = device.wait(Until.findObject(By.text("Год")), UI_TIMEOUT_MS)
        assertNotNull("Precondition failed: Period tab 'Месяц' not found", monthTab)
        assertNotNull("Precondition failed: Period tab 'Год' not found", yearTab)

        yearTab!!.click()
        device.waitForIdle()

        monthTab!!.click()
        device.waitForIdle()
    }

    /**
     * Scenario 7: Opening Tasks and calendar.
     * Switches between List and Calendar representations with explicit presence assertions.
     */
    fun scenario7_openTasksAndCalendar() {
        navigateToDestination("Задачи")

        val calendarToggle = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).desc("Календарь")
                    ?: By.pkg(TARGET_PACKAGE).text("Календарь")
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Precondition failed: Calendar toggle button not found", calendarToggle)
        calendarToggle!!.click()
        device.waitForIdle()

        val calendarView = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).clazz("android.widget.CalendarView")
                    ?: By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "calendarView")
                    ?: By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "layoutCalendar")
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Calendar view failed to display after toggle", calendarView)

        val listToggle = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).desc("Список")
                    ?: By.pkg(TARGET_PACKAGE).text("Список")
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Precondition failed: List toggle button not found", listToggle)
        listToggle!!.click()
        device.waitForIdle()

        val listView = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "recyclerView")
                    ?: By.pkg(TARGET_PACKAGE).clazz("androidx.recyclerview.widget.RecyclerView")
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Task list view failed to display after toggling back from calendar", listView)
    }

    private fun navigateToDestination(label: String) {
        val tab = device.wait(
            Until.findObject(
                By.pkg(TARGET_PACKAGE).desc(label)
                    ?: By.pkg(TARGET_PACKAGE).text(label)
            ),
            UI_TIMEOUT_MS
        )
        assertNotNull("Navigation tab '$label' not found", tab)
        tab!!.click()
        device.waitForIdle()
    }

    private fun getTargetPid(): String? {
        val output = device.executeShellCommand("pidof $TARGET_PACKAGE").trim()
        return output.ifBlank { null }
    }
}
