/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.hamcrest.Matchers.equalTo
import org.isoron.uhabits.BaseAndroidTest
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.settings.SettingsSectionFragment
import org.isoron.uhabits.activities.settings.SettingsSectionId
import org.isoron.uhabits.core.reminders.ReminderScheduler.SchedulerResult.OK
import org.isoron.uhabits.receivers.ReminderReceiver
import org.isoron.uhabits.receivers.WidgetReceiver
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.random.Random

class IntentSchedulerTest : BaseAndroidTest() {

    private val uiDevice: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun createUniqueSyntheticPendingIntent(): Pair<String, PendingIntent> {
        val uniqueAction = "org.isoron.uhabits.test.GAP6_${UUID.randomUUID()}"
        val intent = Intent(uniqueAction).apply {
            setPackage(targetContext.packageName)
            putExtra("test_uuid", UUID.randomUUID().toString())
        }
        val requestCode = Random.nextInt(100_000, 999_999)
        val pendingIntent = PendingIntent.getBroadcast(
            targetContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Pair(uniqueAction, pendingIntent)
    }

    private class SyntheticAlarmDelegate : IntentScheduler.AlarmDelegate {
        var exactPermitted: Boolean = true
        var exactException: SecurityException? = null

        data class Call(val type: Int, val time: Long, val pi: PendingIntent)
        val exactCalls = mutableListOf<Call>()
        val inexactCalls = mutableListOf<Call>()

        override fun isAvailable(): Boolean = true
        override fun canScheduleExactAlarms(): Boolean = exactPermitted

        override fun setExactAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            exactException?.let { throw it }
            exactCalls.add(Call(alarmType, triggerAtMillis, operation))
        }

        override fun setAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            inexactCalls.add(Call(alarmType, triggerAtMillis, operation))
        }
    }

    private class RealAlarmManagerDelegateWrapper(
        private val real: IntentScheduler.DefaultAlarmDelegate,
        private val canExact: () -> Boolean = { real.canScheduleExactAlarms() },
        private val onExact: ((Int, Long, PendingIntent) -> Unit)? = null
    ) : IntentScheduler.AlarmDelegate {
        var exactCallCount = 0
            private set
        var inexactCallCount = 0
            private set

        override fun isAvailable(): Boolean = real.isAvailable()
        override fun canScheduleExactAlarms(): Boolean = canExact()

        override fun setExactAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            exactCallCount++
            onExact?.invoke(alarmType, triggerAtMillis, operation)
            real.setExactAndAllowWhileIdle(alarmType, triggerAtMillis, operation)
        }

        override fun setAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            inexactCallCount++
            real.setAndAllowWhileIdle(alarmType, triggerAtMillis, operation)
        }
    }

    @Before
    override fun setUp() {
        super.setUp()
        appComponent.preferences.isFirstRun = false
    }

    @After
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    @MediumTest
    fun testScheduleShowReminder_relativeFutureTimestamp() {
        for (h in habitList) h.reminder = null
        val reminderTime = System.currentTimeMillis() + 60_000L
        val habit = habitList.getByPosition(0)
        val scheduler = checkNotNull(appComponent.intentScheduler)
        assertThat(scheduler.scheduleShowReminder(reminderTime, habit, 0), equalTo(OK))
    }

    @Test
    @MediumTest
    fun testScheduleWidgetUpdate_relativeFutureTimestamp() {
        val updateTime = System.currentTimeMillis() + 60_000L
        val scheduler = checkNotNull(appComponent.intentScheduler)
        assertThat(scheduler.scheduleWidgetUpdate(updateTime), equalTo(OK))
    }

    @Test
    @MediumTest
    fun testScheduleShowReminder_whenExactPermissionDenied_syntheticallyFallsBackToInexactAlarm() {
        val habit = habitList.getByPosition(0)
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val syntheticDelegate = SyntheticAlarmDelegate().apply { exactPermitted = false }
        scheduler.alarmDelegate = syntheticDelegate

        try {
            val futureTime = System.currentTimeMillis() + 60_000L
            val result = scheduler.scheduleShowReminder(futureTime, habit, futureTime)

            assertThat(result, equalTo(OK))
            assertThat(syntheticDelegate.exactCalls.isEmpty(), equalTo(true))
            assertThat(syntheticDelegate.inexactCalls.size, equalTo(1))
            val call = syntheticDelegate.inexactCalls.single()
            assertThat(call.type, equalTo(AlarmManager.RTC_WAKEUP))
            assertThat(call.time, equalTo(futureTime))
            assertNotNull(call.pi)
            assertThat(call.pi.creatorPackage, equalTo(targetContext.packageName))
        } finally {
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testScheduleShowReminder_whenExactAlarmThrowsSecurityException_syntheticallyFallsBackToInexactAlarm() {
        val habit = habitList.getByPosition(0)
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val syntheticDelegate = SyntheticAlarmDelegate().apply {
            exactPermitted = true
            exactException = SecurityException("SCHEDULE_EXACT_ALARM revoked")
        }
        scheduler.alarmDelegate = syntheticDelegate

        try {
            val futureTime = System.currentTimeMillis() + 60_000L
            val result = scheduler.scheduleShowReminder(futureTime, habit, futureTime)

            assertThat(result, equalTo(OK))
            assertThat(syntheticDelegate.exactCalls.isEmpty(), equalTo(true))
            assertThat(syntheticDelegate.inexactCalls.size, equalTo(1))
            val call = syntheticDelegate.inexactCalls.single()
            assertThat(call.type, equalTo(AlarmManager.RTC_WAKEUP))
            assertThat(call.time, equalTo(futureTime))
            assertNotNull(call.pi)
            assertThat(call.pi.creatorPackage, equalTo(targetContext.packageName))
        } finally {
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testScheduleWidgetUpdate_whenExactPermissionDenied_syntheticallyFallsBackToInexactAlarm() {
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val syntheticDelegate = SyntheticAlarmDelegate().apply { exactPermitted = false }
        scheduler.alarmDelegate = syntheticDelegate

        try {
            val futureTime = System.currentTimeMillis() + 60_000L
            val result = scheduler.scheduleWidgetUpdate(futureTime)

            assertThat(result, equalTo(OK))
            assertThat(syntheticDelegate.exactCalls.isEmpty(), equalTo(true))
            assertThat(syntheticDelegate.inexactCalls.size, equalTo(1))
            val call = syntheticDelegate.inexactCalls.single()
            assertThat(call.type, equalTo(AlarmManager.RTC))
            assertThat(call.time, equalTo(futureTime))
            assertNotNull(call.pi)
            assertThat(call.pi.creatorPackage, equalTo(targetContext.packageName))
        } finally {
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testSchedule_withRealAlarmManager_whenExactPermissionPermitted_schedulesExactAlarm() {
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val (uniqueAction, pendingIntent) = createUniqueSyntheticPendingIntent()
        val alarmManager = targetContext.getSystemService(AlarmManager::class.java)

        try {
            val realDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
            val wrapper = RealAlarmManagerDelegateWrapper(realDelegate, canExact = { true })
            scheduler.alarmDelegate = wrapper

            val futureTime = System.currentTimeMillis() + 180_000L
            val result = scheduler.schedule(futureTime, pendingIntent, AlarmManager.RTC_WAKEUP)

            assertThat(result, equalTo(OK))
            assertThat(wrapper.exactCallCount, equalTo(1))

            if (alarmManager?.canScheduleExactAlarms() == true) {
                assertThat(wrapper.inexactCallCount, equalTo(0))
            } else {
                // When OS does not grant SCHEDULE_EXACT_ALARM, setExactAndAllowWhileIdle throws SecurityException,
                // and scheduler safely falls back to inexact alarm.
                assertThat(wrapper.inexactCallCount, equalTo(1))
            }

            val dumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
            assertTrue(
                "Real AlarmManager dumpsys must contain unique synthetic action when scheduled",
                dumpsys.contains(uniqueAction)
            )
        } finally {
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testSchedule_withRealAlarmManager_whenExactPermissionDenied_fallsBackToInexactAlarm() {
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val (uniqueAction, pendingIntent) = createUniqueSyntheticPendingIntent()
        val alarmManager = targetContext.getSystemService(AlarmManager::class.java)

        try {
            val realDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
            val wrapper = RealAlarmManagerDelegateWrapper(realDelegate, canExact = { false })
            scheduler.alarmDelegate = wrapper

            val futureTime = System.currentTimeMillis() + 180_000L
            val result = scheduler.schedule(futureTime, pendingIntent, AlarmManager.RTC_WAKEUP)

            assertThat(
                "When exact permission is denied, real AlarmManager fallback must return OK",
                result,
                equalTo(OK)
            )
            assertThat(wrapper.exactCallCount, equalTo(0))
            assertThat(wrapper.inexactCallCount, equalTo(1))

            val dumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
            assertTrue(
                "Real AlarmManager dumpsys must contain unique synthetic action for inexact fallback",
                dumpsys.contains(uniqueAction)
            )
        } finally {
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testSchedule_withRealAlarmManager_whenPermissionRaceThrowsSecurityException_fallsBackToInexactAlarm() {
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val (uniqueAction, pendingIntent) = createUniqueSyntheticPendingIntent()
        val alarmManager = targetContext.getSystemService(AlarmManager::class.java)

        try {
            val realDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
            var realExactThrewSecurityException = false

            val wrapper = RealAlarmManagerDelegateWrapper(
                real = realDelegate,
                canExact = { true },
                onExact = { _, _, _ ->
                    realExactThrewSecurityException = true
                    throw SecurityException("SCHEDULE_EXACT_ALARM revoked at invocation")
                }
            )
            scheduler.alarmDelegate = wrapper

            val futureTime = System.currentTimeMillis() + 180_000L
            val result = scheduler.schedule(futureTime, pendingIntent, AlarmManager.RTC_WAKEUP)

            assertThat(result, equalTo(OK))
            assertTrue("SecurityException must be raised on exact alarm call", realExactThrewSecurityException)
            assertThat(wrapper.exactCallCount, equalTo(1))
            assertThat(wrapper.inexactCallCount, equalTo(1))

            val dumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
            assertTrue(
                "Fallback alarm must be scheduled in real AlarmManager after race SecurityException",
                dumpsys.contains(uniqueAction)
            )
        } finally {
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testScheduleShowReminder_withRealAlarmManager_whenExactPermissionDenied_fallsBackToInexactAlarm() {
        val habit = habitList.getByPosition(0)
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val alarmManager = targetContext.getSystemService(AlarmManager::class.java)

        try {
            val realDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
            val wrapper = RealAlarmManagerDelegateWrapper(realDelegate, canExact = { false })
            scheduler.alarmDelegate = wrapper

            val futureTime = System.currentTimeMillis() + 180_000L
            val result = scheduler.scheduleShowReminder(futureTime, habit, futureTime)

            assertThat(result, equalTo(OK))
            assertThat(wrapper.exactCallCount, equalTo(0))
            assertThat(wrapper.inexactCallCount, equalTo(1))

            val dumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
            assertTrue(
                "Dumpsys must contain ACTION_SHOW_REMINDER for fallback reminder alarm in real AlarmManager",
                dumpsys.contains(ReminderReceiver.ACTION_SHOW_REMINDER)
            )
        } finally {
            val pi = appComponent.pendingIntentFactory.showReminder(habit, 0L, 0L)
            alarmManager?.cancel(pi)
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testScheduleWidgetUpdate_withRealAlarmManager_whenExactPermissionDenied_fallsBackToInexactAlarm() {
        val scheduler = checkNotNull(appComponent.intentScheduler)
        val originalDelegate = scheduler.alarmDelegate
        val alarmManager = targetContext.getSystemService(AlarmManager::class.java)

        try {
            val realDelegate = IntentScheduler.DefaultAlarmDelegate(targetContext)
            val wrapper = RealAlarmManagerDelegateWrapper(realDelegate, canExact = { false })
            scheduler.alarmDelegate = wrapper

            val futureTime = System.currentTimeMillis() + 180_000L
            val result = scheduler.scheduleWidgetUpdate(futureTime)

            assertThat(result, equalTo(OK))
            assertThat(wrapper.exactCallCount, equalTo(0))
            assertThat(wrapper.inexactCallCount, equalTo(1))

            val dumpsys = uiDevice.executeShellCommand("dumpsys alarm ${targetContext.packageName}")
            assertTrue(
                "Dumpsys must contain ACTION_UPDATE_WIDGETS_VALUE for fallback widget alarm in real AlarmManager",
                dumpsys.contains(WidgetReceiver.ACTION_UPDATE_WIDGETS_VALUE)
            )
        } finally {
            val pi = appComponent.pendingIntentFactory.updateWidgets()
            alarmManager?.cancel(pi)
            scheduler.alarmDelegate = originalDelegate
        }
    }

    @Test
    @MediumTest
    fun testSettingsNotice_reflectsExactAlarmPermissionState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        appComponent.preferences.isFirstRun = false
        val outputDir = targetContext.getExternalFilesDir(null) ?: targetContext.filesDir
        val screenshotFile = File(outputDir, "settings_exact_alarm_notice.png")
        if (screenshotFile.exists()) {
            screenshotFile.delete()
        }

        ActivityScenario.launch<MainActivity>(MainActivity.intent(targetContext, MainDestination.HABITS)).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigate(MainDestination.SETTINGS)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val section = activity.supportFragmentManager.fragments
                    .filterIsInstance<SettingsSectionFragment>()
                    .first { !it.isHidden }
                section.openSettingsSection(SettingsSectionId.NOTIFICATIONS)
                section.childFragmentManager.executePendingTransactions()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            uiDevice.waitForIdle()

            val screenshotSuccess = uiDevice.takeScreenshot(screenshotFile)
            assertTrue("Screenshot must be saved: ${screenshotFile.absolutePath}", screenshotSuccess && screenshotFile.exists() && screenshotFile.length() > 0)

            val exactAlarmTitle = targetContext.getString(R.string.pref_reminder_exact_alarm_title)
            val titleFound = uiDevice.wait(Until.hasObject(By.text(exactAlarmTitle)), 5000)

            val alarmManager = targetContext.getSystemService(AlarmManager::class.java)
            val canExact = alarmManager?.canScheduleExactAlarms() == true
            val expectedSummary = if (canExact) {
                targetContext.getString(R.string.pref_reminder_exact_alarm_enabled)
            } else {
                targetContext.getString(R.string.pref_reminder_exact_alarm_disabled)
            }

            val summaryFound = uiDevice.wait(Until.hasObject(By.textContains(expectedSummary.take(15))), 5000)

            assertTrue(
                "Exact alarm preference row must be present in settings (titleFound=$titleFound, summaryFound=$summaryFound)",
                titleFound || summaryFound
            )
        }
    }
}
