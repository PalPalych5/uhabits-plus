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
package org.isoron.uhabits.intents

import android.app.AlarmManager
import android.os.Build
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.hamcrest.Matchers.equalTo
import org.isoron.uhabits.BaseAndroidTest
import org.isoron.uhabits.core.reminders.ReminderScheduler.SchedulerResult.OK
import org.isoron.uhabits.receivers.ReminderReceiver
import org.isoron.uhabits.receivers.WidgetReceiver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Native instrumentation test validating real exact-alarm permission denial (GAP-6).
 *
 * Exercises unwrapped [IntentScheduler.DefaultAlarmDelegate] when platform permission
 * (SCHEDULE_EXACT_ALARM appop) is actually denied at the OS level on Android 12+ (API 31+).
 * Ensures that reminder scheduling falls back to inexact RTC_WAKEUP alarms without wrapping,
 * fakes, or system clock alterations.
 *
 * Correlates unique reminder action and identity with the RTC_WAKEUP alarm type within the
 * exact same alarm record to rule out false satisfaction by pre-existing alarms.
 */
class RealDeniedExactAlarmTest : BaseAndroidTest() {

    private val uiDevice: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private var scheduledReminderTime: Long = 0L

    @Before
    override fun setUp() {
        super.setUp()
        appComponent.preferences.isFirstRun = false
    }

    @After
    override fun tearDown() {
        val alarmManager = targetContext.getSystemService(AlarmManager::class.java)
        if (alarmManager != null && scheduledReminderTime > 0L) {
            val habit = habitList.getByPosition(0)
            val pi = appComponent.pendingIntentFactory.showReminder(
                habit,
                scheduledReminderTime,
                scheduledReminderTime
            )
            alarmManager.cancel(pi)
            pi.cancel()
        }
        val widgetPi = appComponent.pendingIntentFactory.updateWidgets()
        alarmManager?.cancel(widgetPi)
        widgetPi.cancel()
        super.tearDown()
    }

    private fun extractAlarmRecords(dumpsys: String): List<String> {
        val records = mutableListOf<String>()
        val lines = dumpsys.lines()
        var current = StringBuilder()
        var inRecord = false

        for (line in lines) {
            val trimmed = line.trimStart()
            val isHeader = trimmed.startsWith("Alarm{") ||
                trimmed.matches(Regex("^(RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED)\\s+#\\d+:.*")) ||
                trimmed.startsWith("Batch{")
            if (isHeader) {
                if (inRecord && current.isNotBlank()) {
                    records.add(current.toString())
                    current = StringBuilder()
                }
                inRecord = true
            }
            if (inRecord) {
                current.appendLine(line)
            }
        }
        if (inRecord && current.isNotBlank()) {
            records.add(current.toString())
        }
        return records
    }

    private fun findMatchingAlarmRecord(
        dumpsys: String,
        action: String,
        packageName: String,
        expectedOrigWhen: Long? = null
    ): String? {
        val records = extractAlarmRecords(dumpsys)
        return records.firstOrNull { record ->
            val hasAction = record.contains(action)
            val hasPackage = record.contains(packageName)
            val hasWhen = expectedOrigWhen == null || record.contains("origWhen $expectedOrigWhen")
            hasAction && hasPackage && hasWhen
        }
    }

    @Test
    @MediumTest
    fun testRealDeniedExactAlarm_platformPermissionIsFalse() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val alarmManager = checkNotNull(targetContext.getSystemService(AlarmManager::class.java))
        val canScheduleExact = alarmManager.canScheduleExactAlarms()

        assertFalse(
            "Platform AlarmManager.canScheduleExactAlarms() must return false when SCHEDULE_EXACT_ALARM appop is denied",
            canScheduleExact
        )
    }

    @Test
    @MediumTest
    fun testRealDeniedExactAlarm_unwrappedDefaultAlarmDelegateReflectsDeniedPlatformPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val scheduler = checkNotNull(appComponent.intentScheduler)
        val unwrappedDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
        scheduler.alarmDelegate = unwrappedDelegate

        assertTrue("Unwrapped DefaultAlarmDelegate must report isAvailable() == true", unwrappedDelegate.isAvailable())
        assertFalse(
            "Unwrapped DefaultAlarmDelegate.canScheduleExactAlarms() must return false when platform permission is denied",
            unwrappedDelegate.canScheduleExactAlarms()
        )
        assertFalse(
            "IntentScheduler.canScheduleExactAlarms() must return false with unwrapped delegate",
            scheduler.canScheduleExactAlarms()
        )
    }

    @Test
    @MediumTest
    fun testScheduleShowReminder_withUnwrappedDelegate_whenExactAlarmPermissionDenied_schedulesRtcWakeupFallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        // 1. Explicitly assert platform denied state
        val alarmManager = checkNotNull(targetContext.getSystemService(AlarmManager::class.java))
        val canScheduleExactPlatform = alarmManager.canScheduleExactAlarms()
        assertFalse(
            "Platform AlarmManager.canScheduleExactAlarms() must return false under OS-level AppOps denial",
            canScheduleExactPlatform
        )

        // 2. Exercise unwrapped DefaultAlarmDelegate
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val unwrappedDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
        scheduler.alarmDelegate = unwrappedDelegate

        assertTrue("Unwrapped DefaultAlarmDelegate must report isAvailable() == true", unwrappedDelegate.isAvailable())
        assertFalse(
            "Unwrapped DefaultAlarmDelegate.canScheduleExactAlarms() must return false when platform permission is denied",
            unwrappedDelegate.canScheduleExactAlarms()
        )
        assertFalse(
            "IntentScheduler.canScheduleExactAlarms() must return false with unwrapped delegate",
            scheduler.canScheduleExactAlarms()
        )

        val habit = habitList.getByPosition(0)
        assertNotNull("Habit list must contain at least one fixture habit", habit)

        // Future timestamp relative to current system time without altering system clock
        scheduledReminderTime = System.currentTimeMillis() + 180_000L

        // Verify no pre-existing reminder alarm exists for this specific action and timestamp
        val preDumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
        val preExistingRecord = findMatchingAlarmRecord(
            dumpsys = preDumpsys,
            action = ReminderReceiver.ACTION_SHOW_REMINDER,
            packageName = targetContext.packageName,
            expectedOrigWhen = scheduledReminderTime
        )
        assertNull(
            "No pre-existing alarm record should exist for action=${ReminderReceiver.ACTION_SHOW_REMINDER} origWhen=$scheduledReminderTime before scheduling.\nFound pre-existing:\n$preExistingRecord",
            preExistingRecord
        )

        val result = scheduler.scheduleShowReminder(scheduledReminderTime, habit, scheduledReminderTime)

        assertThat(
            "IntentScheduler must successfully schedule fallback alarm and return OK",
            result,
            equalTo(OK)
        )

        val dumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
        assertNotNull("Dumpsys output must not be null", dumpsys)

        // 3. Correlate unique scheduled reminder action/identity and RTC_WAKEUP in the SAME alarm record
        val matchingRecord = findMatchingAlarmRecord(
            dumpsys = dumpsys,
            action = ReminderReceiver.ACTION_SHOW_REMINDER,
            packageName = targetContext.packageName,
            expectedOrigWhen = scheduledReminderTime
        )
        assertNotNull(
            "Dumpsys alarm output must contain an individual alarm record correlating action (${ReminderReceiver.ACTION_SHOW_REMINDER}), package (${targetContext.packageName}), and origWhen ($scheduledReminderTime).\nDumpsys:\n$dumpsys",
            matchingRecord
        )

        val recordText = matchingRecord!!
        val isRtcWakeupInRecord = recordText.contains("RTC_WAKEUP") ||
            recordText.contains("type 0") ||
            recordText.contains("type=0")
        assertTrue(
            "The correlated alarm record for ${ReminderReceiver.ACTION_SHOW_REMINDER} must be an RTC_WAKEUP (type 0) alarm.\nAlarm record:\n$recordText",
            isRtcWakeupInRecord
        )
    }

    @Test
    @MediumTest
    fun testScheduleWidgetUpdate_withUnwrappedDelegate_whenExactAlarmPermissionDenied_schedulesInexactFallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val alarmManager = checkNotNull(targetContext.getSystemService(AlarmManager::class.java))
        assertFalse(
            "Platform AlarmManager.canScheduleExactAlarms() must be false under AppOps denial",
            alarmManager.canScheduleExactAlarms()
        )

        val scheduler = checkNotNull(appComponent.intentScheduler)
        val unwrappedDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
        scheduler.alarmDelegate = unwrappedDelegate

        val futureTime = System.currentTimeMillis() + 180_000L
        val result = scheduler.scheduleWidgetUpdate(futureTime)

        assertThat(
            "IntentScheduler must return OK when falling back for widget update",
            result,
            equalTo(OK)
        )

        val dumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
        val matchingRecord = findMatchingAlarmRecord(
            dumpsys = dumpsys,
            action = WidgetReceiver.ACTION_UPDATE_WIDGETS_VALUE,
            packageName = targetContext.packageName,
            expectedOrigWhen = futureTime
        )
        assertNotNull(
            "Dumpsys alarm output must contain an individual alarm record for widget update (${WidgetReceiver.ACTION_UPDATE_WIDGETS_VALUE}) with origWhen $futureTime.\nDumpsys:\n$dumpsys",
            matchingRecord
        )
        val isRtc = matchingRecord!!.contains("RTC") ||
            matchingRecord.contains("type 1") ||
            matchingRecord.contains("type=1")
        assertTrue(
            "Widget update fallback alarm record must be RTC (type 1).\nAlarm record:\n$matchingRecord",
            isRtc
        )
    }
}
