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

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** External isolated workload. Fixture provisioning is a separate, explicitly authorized step. */
@RunWith(AndroidJUnit4::class)
class ProfileableReleaseQaScenarios {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    companion object {
        const val TARGET_PACKAGE = "org.isoron.uhabits.plus.benchmark"
        const val MAIN_ACTIVITY = "org.isoron.uhabits.activities.main.MainActivity"
        const val FIXTURE_ACTIVITY = "org.isoron.uhabits.benchmark.BenchmarkFixtureActivity"
        const val UI_TIMEOUT_MS = 8000L
        private const val HABIT_LIST = "habitCardList"
        private const val CHECKMARK_TODAY = "habitCheckmarkToday"
    }

    @Before fun setUp() = verifySafetyAndIsolationGuards()

    fun verifySafetyAndIsolationGuards() {
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices are forbidden" }
        check(instrumentation.context.packageName == "org.isoron.uhabits.benchmark" &&
            instrumentation.targetContext.packageName == instrumentation.context.packageName) {
            "Benchmark must instrument its own isolated test process"
        }
        check(instrumentation.context.createPackageContext(TARGET_PACKAGE, 0).packageName == TARGET_PACKAGE) {
            "Isolated measured app is not installed"
        }
        check(InstrumentationRegistry.getArguments().getString("allowIsolatedQa") == "true") {
            "Explicit allowIsolatedQa=true is required"
        }
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == authorizedAvd()) {
            "Disposable AVD identity mismatch"
        }
        check(device.executeShellCommand("settings get global airplane_mode_on").trim() == "1" &&
            device.executeShellCommand("settings get global wifi_on").trim() == "0") { "Offline radio isolation required" }
    }

    private fun authorizedAvd(): String {
        val avd = InstrumentationRegistry.getArguments().getString("authorizedAvd").orEmpty()
        check(avd == "UHabit_Next_Benchmark_Disposable_API35_Phase22") {
            "Dedicated disposable benchmark AVD authorization required"
        }
        return avd
    }

    /** Verifies persisted selection, database contents and offline capabilities in the target process. */
    fun verifyFixtureReady(): String {
        val output = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --es operation verify " +
                "--es authorization provision-isolated-synthetic-v1 --es disposableAvd ${authorizedAvd()}"
        )
        check(output.contains("Status: ok")) { "Fixture verification activity failed: $output" }
        val ready = requireObject("Pre-provisioned synthetic fixture missing or invalid",
            By.pkg(TARGET_PACKAGE).desc("fixture-ready:benchmark-synthetic-v1:uhabits-container-experimental.db:32:84:5792:10:2:offline"))
            .contentDescription
        launchHabits()
        return ready
    }

    @Test fun runRepresentativeProfileCollectionWorkload() {
        val coldPid = scenario1_processColdStartup()
        runPopulatedJourneys()
        assertEquals("Target process restarted during workload", coldPid, getTargetPid())
    }

    fun runPopulatedJourneys() {
        scenario2_populatedHabitsList()
        scenario3_nestedContainers()
        scenario4_completeHabitAndObserveUi()
        scenario5_openPopulatedStatistics()
        scenario6_switchStatisticsPeriods()
        scenario7_openTasksAndCalendar()
    }

    fun scenario1_processColdStartup(): String {
        val before = verifyFixtureReady()
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        check(getTargetPid() == null) { "Target process did not terminate" }
        // AM output confirms launch only; TotalTime is not presented-frame latency.
        launchHabits()
        val pid = getTargetPid()
        assertNotNull("Cold start did not create target process", pid)
        val after = verifyFixtureReady()
        assertEquals("Dataset identity, filename or contents changed across process death", before, after)
        requireObject("Populated habits did not render after cold restart", By.res(TARGET_PACKAGE, CHECKMARK_TODAY))
        return pid!!
    }

    fun scenario2_populatedHabitsList() {
        navigateToDestination("Привычки")
        val list = requireObject("Habit list missing", By.res(TARGET_PACKAGE, HABIT_LIST))
        requireObject("Habit list is empty", By.res(TARGET_PACKAGE, CHECKMARK_TODAY))
        list.scroll(Direction.DOWN, 0.7f)
        list.scroll(Direction.UP, 0.7f)
    }

    fun scenario3_nestedContainers() {
        navigateToDestination("Задачи")
        requireObject("Tasks navigation missing", By.pkg(TARGET_PACKAGE).desc("Открыть навигацию задач")).click()
        requireObject("Root container missing", By.pkg(TARGET_PACKAGE).text("Учёба")).click()
        requireObject("Tasks navigation missing", By.pkg(TARGET_PACKAGE).desc("Открыть навигацию задач")).click()
        requireObject("Nested container missing", By.pkg(TARGET_PACKAGE).text("Курсовая работа")).click()
        navigateToDestination("Привычки")
    }

    fun scenario4_completeHabitAndObserveUi() {
        navigateToDestination("Привычки")
        verifyHabitToggle()
    }

    fun scenario5_openPopulatedStatistics() {
        navigateToDestination("Статистика")
        requireObject("Statistics workspace missing", By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "pageTransitionHost"))
        requireObject("Statistics date range missing", By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "tvDateRange"))
    }

    fun scenario6_switchStatisticsPeriods() {
        navigateToDestination("Статистика")
        requireObject("Year period missing", By.pkg(TARGET_PACKAGE).text("Год")).click()
        requireObject("Month period missing", By.pkg(TARGET_PACKAGE).text("Месяц")).click()
    }

    fun scenario7_openTasksAndCalendar() {
        navigateToDestination("Задачи")
        requireObject("Calendar toggle missing", By.pkg(TARGET_PACKAGE).desc("Показать календарь")).click()
        // TasksFragment builds a custom LinearLayout calendar, not android.widget.CalendarView.
        requireObject("Calendar previous month control missing", By.pkg(TARGET_PACKAGE).desc("Предыдущий месяц"))
        requireObject("Calendar next month control missing", By.pkg(TARGET_PACKAGE).desc("Следующий месяц"))
        requireObject("List toggle missing", By.pkg(TARGET_PACKAGE).desc("Показать список")).click()
        requireObject("Task list missing", By.pkg(TARGET_PACKAGE).res(TARGET_PACKAGE, "rvTasks"))
    }

    private fun navigateToDestination(label: String) {
        requireObject("Navigation '$label' missing", By.pkg(TARGET_PACKAGE).desc(label), By.pkg(TARGET_PACKAGE).text(label)).click()
        device.waitForIdle()
    }

    /** Every polling round queries candidates in priority order under one shared deadline. */
    private fun requireObject(message: String, vararg selectors: BySelector): UiObject2 {
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        // findObject normally waits for idle on every lookup. Disable that hidden wait only here.
        configurator.setWaitForIdleTimeout(0L)
        try {
            return pollOrderedSelectors(message, selectors)
        } finally {
            configurator.setWaitForIdleTimeout(previousIdleTimeout)
        }
    }

    private fun pollOrderedSelectors(message: String, selectors: Array<out BySelector>): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        do {
            for (selector in selectors) {
                if (SystemClock.uptimeMillis() >= deadline) break
                device.findObject(selector)?.let { return it }
            }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(minOf(50L, remaining))
        } while (SystemClock.uptimeMillis() < deadline)
        error("$message (timeout ${UI_TIMEOUT_MS}ms)")
    }

    private fun launchHabits() {
        val output = device.executeShellCommand(
            "am start -W --activity-clear-top --activity-single-top -n $TARGET_PACKAGE/$MAIN_ACTIVITY --es main.destination HABITS"
        )
        check(output.contains("Status: ok")) { "Main activity launch failed: $output" }
        requireObject("Habit list missing", By.res(TARGET_PACKAGE, HABIT_LIST))
    }

    private fun getTargetPid(): String? = device.executeShellCommand("pidof $TARGET_PACKAGE").trim().ifBlank { null }

    private fun verifyHabitToggle() {
        val before = inspectHabitValue()
        clickTargetHabitToday()
        val after = awaitHabitValue(2 - before)
        check(after != before && after in setOf(0, 2)) { "Habit toggle did not change the durable boolean entry: $before -> $after" }
        clickTargetHabitToday()
        assertEquals("Second boolean toggle did not restore fixture entry", before, awaitHabitValue(before))
        verifyFixtureReady()
    }

    private fun awaitHabitValue(expected: Int): Int {
        // This bounds retries, not ActivityManager launch duration; shell am start -W has its own wait.
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        do {
            val value = inspectHabitValue()
            if (value == expected) return value
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Durable fixture habit did not reach expected value $expected")
    }

    private fun inspectHabitValue(): Int {
        val output = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/$FIXTURE_ACTIVITY --es operation inspectHabit " +
                "--es disposableAvd ${authorizedAvd()}"
        )
        check(output.contains("Status: ok")) { "Habit verification failed: $output" }
        val report = requireObject("Durable fixture habit evidence missing",
            By.pkg(TARGET_PACKAGE).descStartsWith("fixture-habit:Изучать материалы:")).contentDescription
        val value = report.substringAfterLast(':').toInt()
        check(value in setOf(0, 2)) { "Fixture target must be a binary habit entry: $report" }
        launchHabits()
        return value
    }

    private fun clickTargetHabitToday() {
        val rowSelector = By.res(TARGET_PACKAGE, "habitCard")
            .hasDescendant(By.desc("Изучать материалы"))
        val list = requireObject("Habit list missing", By.res(TARGET_PACKAGE, HABIT_LIST))
        // Returning to the top uses the real list's scrolling API; no coordinate or row-position assumption.
        repeat(8) {
            if (device.hasObject(rowSelector)) return@repeat
            list.scroll(Direction.UP, 0.8f)
        }
        val row = requireObject("Named fixture habit missing", rowSelector)
        // The real panel assigns this ID by date offset, independently of visual ordering.
        val today = row.findObject(By.res(TARGET_PACKAGE, CHECKMARK_TODAY))
        assertNotNull("Today's boolean checkmark missing from named habit", today)
        today!!.click()
        device.waitForIdle()
    }
}
