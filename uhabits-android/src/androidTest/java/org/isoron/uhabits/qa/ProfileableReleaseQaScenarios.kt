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

package org.isoron.uhabits.qa

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Foundation for Android Baseline Profile and Startup Profile generation and validation.
 *
 * Implements bounded, deterministic synthetic workloads covering the 7 representative
 * application journeys defined in UHabit Next Phase 2.2:
 * 1. Cold application startup.
 * 2. Initial populated Habits list.
 * 3. Nested habit containers.
 * 4. Completing a habit and observing UI updates.
 * 5. Opening populated Statistics.
 * 6. Switching Statistics periods.
 * 7. Opening Tasks and calendar.
 *
 * Safety requirements:
 * - Emulators only; physical devices are strictly forbidden.
 * - Explicit disposable AVD token matching guest ro.boot.qemu.avd_name.
 * - Offline-only dataset session (all sync, remote calls, background jobs disabled).
 */
@RunWith(AndroidJUnit4::class)
class ProfileableReleaseQaScenarios {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext

    companion object {
        const val DISPOSABLE_AVD_TOKEN = "UHabit_UI_Review_Disposable_20261011"

        /**
         * Verifies that the test is executing in an authorized, disposable emulator environment.
         */
        fun verifySafetyGuards() {
            check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) {
                "Physical devices are strictly forbidden for profileable QA"
            }
            val ins = InstrumentationRegistry.getInstrumentation()
            val argToken = InstrumentationRegistry.getArguments().getString("disposableAvd")
            check(argToken == DISPOSABLE_AVD_TOKEN) {
                "Verified disposable AVD token required ('$DISPOSABLE_AVD_TOKEN', got '$argToken')"
            }
            val device = UiDevice.getInstance(ins)
            val guestAvd = device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()
            check(guestAvd == DISPOSABLE_AVD_TOKEN) {
                "Guest AVD identity mismatch: expected '$DISPOSABLE_AVD_TOKEN', actual '$guestAvd'"
            }
        }

        /**
         * Prepares the isolated offline QA dataset with synthetic data.
         */
        fun prepareIsolatedQaEnvironment(): HabitsApplicationComponent {
            verifySafetyGuards()
            val fixture = UiReviewCaptureTest.initializeFixture()
            val ins = InstrumentationRegistry.getInstrumentation()
            val app = ins.targetContext.applicationContext as HabitsApplication

            val qaSession = fixture.session.copy(
                mode = DatasetMode.PRODUCTION_NEXT,
                capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(
                    syncEnabled = false,
                    remoteCallsAllowed = false,
                    backgroundJobsEnabled = false,
                    widgetsEnabled = false,
                    remindersEnabled = false
                )
            )
            ins.runOnMainSync { app.rebuildComponent(qaSession) }
            val component = HabitsApplication.component
            check(!component.session.capabilities.remoteCallsAllowed && !component.session.capabilities.syncEnabled) {
                "QA session must remain offline with sync disabled"
            }
            return component
        }
    }

    /**
     * Executes the combined profile-generation workload exercising all 7 scenarios in sequence.
     * This path is designed for ART PGO profile collection.
     */
    @Test
    fun runRepresentativeProfileCollectionWorkload() {
        prepareIsolatedQaEnvironment()
        device.wakeUp()

        // Scenario 1: Cold application startup
        scenario1_coldStartup().use { scenario ->
            device.waitForIdle()

            // Scenario 2: Initial populated Habits list
            scenario2_populatedHabitsList(scenario)

            // Scenario 3: Nested habit containers
            scenario3_nestedContainers(scenario)

            // Scenario 4: Completing a habit and observing UI updates
            scenario4_completeHabitAndObserveUi(scenario)

            // Scenario 5: Opening populated Statistics
            scenario5_openPopulatedStatistics(scenario)

            // Scenario 6: Switching Statistics periods
            scenario6_switchStatisticsPeriods(scenario)

            // Scenario 7: Opening Tasks and calendar
            scenario7_openTasksAndCalendar(scenario)
        }
    }

    /**
     * Scenario 1: Cold application startup into Habits workspace.
     */
    fun scenario1_coldStartup(): ActivityScenario<MainActivity> {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("main.destination", MainDestination.HABITS.name)
        }
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        assertTrue("Main activity failed to become visible within 5s",
            device.wait(Until.hasObject(By.pkg(context.packageName)), 5000))
        return scenario
    }

    /**
     * Scenario 2: Initial populated Habits list traversal and layout.
     */
    fun scenario2_populatedHabitsList(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.navigate(MainDestination.HABITS) }
        device.waitForIdle()

        // Verify list is rendered and scroll downwards then upwards
        val listObject = device.findObject(By.res(context.packageName, "recyclerView"))
            ?: device.findObject(By.clazz("androidx.recyclerview.widget.RecyclerView"))
        if (listObject != null) {
            device.swipe(500, 1500, 500, 800, 15)
            device.waitForIdle()
            device.swipe(500, 800, 500, 1500, 15)
            device.waitForIdle()
        }
    }

    /**
     * Scenario 3: Nested habit containers interaction.
     */
    fun scenario3_nestedContainers(scenario: ActivityScenario<MainActivity>) {
        // Navigate through Tasks drawer to inspect container hierarchy
        scenario.onActivity { it.navigate(MainDestination.TASKS) }
        device.waitForIdle()

        val navButton = device.findObject(By.desc("Навигация по задачам"))
            ?: device.findObject(By.res(context.packageName, "btnTaskNavigation"))
            ?: device.findObject(By.desc("Open navigation drawer"))
        if (navButton != null) {
            navButton.click()
            device.waitForIdle()

            // Click root container "Учёба"
            val studyNode = device.findObject(By.text("Учёба"))
            if (studyNode != null) {
                studyNode.click()
                device.waitForIdle()
            }

            // Re-open and click nested container "Курсовая работа" if present
            navButton.click()
            device.waitForIdle()
            val courseworkNode = device.findObject(By.text("Курсовая работа"))
            if (courseworkNode != null) {
                courseworkNode.click()
                device.waitForIdle()
            }
        }

        // Return to Habits
        scenario.onActivity { it.navigate(MainDestination.HABITS) }
        device.waitForIdle()
    }

    /**
     * Scenario 4: Completing a habit and observing UI updates.
     */
    fun scenario4_completeHabitAndObserveUi(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.navigate(MainDestination.HABITS) }
        device.waitForIdle()

        // Find a checkmark button and perform click
        val checkmarkButton = device.findObject(
            By.clazz("org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonView")
        )
        if (checkmarkButton != null) {
            checkmarkButton.click()
            device.waitForIdle()
            // Click again to toggle back
            checkmarkButton.click()
            device.waitForIdle()
        }
    }

    /**
     * Scenario 5: Opening populated Statistics.
     */
    fun scenario5_openPopulatedStatistics(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
        device.waitForIdle()

        // Wait for statistics content to hydrate
        device.wait(Until.hasObject(By.text("Статистика")), 5000)
    }

    /**
     * Scenario 6: Switching Statistics periods.
     */
    fun scenario6_switchStatisticsPeriods(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.navigate(MainDestination.STATISTICS) }
        device.waitForIdle()

        // Toggle period chips/tabs (e.g. Month, Year, All) if visible
        for (periodLabel in listOf("Месяц", "Год", "Все", "Неделя")) {
            val periodTab = device.findObject(By.text(periodLabel))
            if (periodTab != null && periodTab.isClickable) {
                periodTab.click()
                device.waitForIdle()
            }
        }
    }

    /**
     * Scenario 7: Opening Tasks and calendar.
     */
    fun scenario7_openTasksAndCalendar(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.navigate(MainDestination.TASKS) }
        device.waitForIdle()

        // Switch to Calendar representation if toggle is available
        val calendarToggle = device.findObject(By.desc("Календарь"))
            ?: device.findObject(By.text("Календарь"))
        if (calendarToggle != null) {
            calendarToggle.click()
            device.waitForIdle()
        }

        // Switch back to List representation
        val listToggle = device.findObject(By.desc("Список"))
            ?: device.findObject(By.text("Список"))
        if (listToggle != null) {
            listToggle.click()
            device.waitForIdle()
        }
    }
}
