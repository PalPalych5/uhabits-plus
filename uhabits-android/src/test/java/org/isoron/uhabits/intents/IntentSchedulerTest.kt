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
import android.content.ContextWrapper
import org.isoron.uhabits.BaseAndroidJVMTest
import org.isoron.uhabits.core.reminders.ReminderScheduler.SchedulerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntentSchedulerTest : BaseAndroidJVMTest() {

    private class FakeContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    private class FakeAlarmDelegate : IntentScheduler.AlarmDelegate {
        var available: Boolean = true
        var exactAlarmsPermitted: Boolean = true
        var exactAlarmException: Throwable? = null
        var inexactAlarmException: Throwable? = null

        data class ScheduledAlarm(
            val alarmType: Int,
            val triggerAtMillis: Long,
            val operation: PendingIntent
        )

        val exactCalls = mutableListOf<ScheduledAlarm>()
        val inexactCalls = mutableListOf<ScheduledAlarm>()

        override fun isAvailable(): Boolean = available
        override fun canScheduleExactAlarms(): Boolean = exactAlarmsPermitted

        override fun setExactAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            exactAlarmException?.let { throw it }
            exactCalls.add(ScheduledAlarm(alarmType, triggerAtMillis, operation))
        }

        override fun setAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            inexactAlarmException?.let { throw it }
            inexactCalls.add(ScheduledAlarm(alarmType, triggerAtMillis, operation))
        }
    }

    private lateinit var scheduler: IntentScheduler
    private lateinit var fakeAlarmDelegate: FakeAlarmDelegate
    private lateinit var defaultPendingIntent: PendingIntent

    private val now: Long
        get() = System.currentTimeMillis()

    private val futureTime: Long
        get() = now + 60_000L

    private val pastTime: Long
        get() = now - 10_000L

    private fun createTestPendingIntent(): PendingIntent {
        val constructor = PendingIntent::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance()
    }

    @Before
    override fun setUp() {
        super.setUp()
        val context = FakeContext()
        val intentFactory = IntentFactory()
        val pendingIntentFactory = PendingIntentFactory(context, intentFactory)

        scheduler = IntentScheduler(context, pendingIntentFactory)
        fakeAlarmDelegate = FakeAlarmDelegate()
        defaultPendingIntent = createTestPendingIntent()

        scheduler.alarmDelegate = fakeAlarmDelegate
    }

    @Test
    fun testSchedule_whenExactAlarmPermitted_schedulesExactAlarmWithActualPendingIntent() {
        fakeAlarmDelegate.exactAlarmsPermitted = true
        val targetTime = futureTime

        val result = scheduler.schedule(targetTime, defaultPendingIntent, AlarmManager.RTC_WAKEUP)

        assertEquals(SchedulerResult.OK, result)
        assertEquals(1, fakeAlarmDelegate.exactCalls.size)
        val call = fakeAlarmDelegate.exactCalls.single()
        assertEquals(AlarmManager.RTC_WAKEUP, call.alarmType)
        assertEquals(targetTime, call.triggerAtMillis)
        assertSame(defaultPendingIntent, call.operation)
        assertTrue(fakeAlarmDelegate.inexactCalls.isEmpty())
    }

    @Test
    fun testSchedule_whenExactAlarmPermissionDenied_fallsBackToInexactAlarm() {
        fakeAlarmDelegate.exactAlarmsPermitted = false
        val targetTime = futureTime

        val result = scheduler.schedule(targetTime, defaultPendingIntent, AlarmManager.RTC_WAKEUP)

        assertEquals(
            "When exact alarm permission is denied, scheduler must return OK via inexact fallback",
            SchedulerResult.OK,
            result
        )
        assertTrue(fakeAlarmDelegate.exactCalls.isEmpty())
        assertEquals(1, fakeAlarmDelegate.inexactCalls.size)
        val call = fakeAlarmDelegate.inexactCalls.single()
        assertEquals(AlarmManager.RTC_WAKEUP, call.alarmType)
        assertEquals(targetTime, call.triggerAtMillis)
        assertSame(defaultPendingIntent, call.operation)
    }

    @Test
    fun testSchedule_whenPermissionRaceThrowsSecurityException_fallsBackToInexactAlarm() {
        fakeAlarmDelegate.exactAlarmsPermitted = true
        fakeAlarmDelegate.exactAlarmException = SecurityException("Caller needs SCHEDULE_EXACT_ALARM")
        val targetTime = futureTime

        val result = scheduler.schedule(targetTime, defaultPendingIntent, AlarmManager.RTC_WAKEUP)

        assertEquals(
            "When exact alarm throws SecurityException due to race condition, scheduler must return OK via inexact fallback",
            SchedulerResult.OK,
            result
        )
        assertTrue("Exact alarm call threw SecurityException", fakeAlarmDelegate.exactCalls.isEmpty())
        assertEquals(1, fakeAlarmDelegate.inexactCalls.size)
        val call = fakeAlarmDelegate.inexactCalls.single()
        assertEquals(AlarmManager.RTC_WAKEUP, call.alarmType)
        assertEquals(targetTime, call.triggerAtMillis)
        assertSame(defaultPendingIntent, call.operation)
    }

    @Test
    fun testSchedule_whenInexactFallbackThrowsSecurityException_returnsIgnored() {
        fakeAlarmDelegate.exactAlarmsPermitted = false
        fakeAlarmDelegate.inexactAlarmException = SecurityException("Inexact alarm scheduling blocked by system policy")
        val targetTime = futureTime

        val result = scheduler.schedule(targetTime, defaultPendingIntent, AlarmManager.RTC_WAKEUP)

        assertEquals(
            "When inexact fallback itself throws SecurityException, scheduler must safely return IGNORED without crashing",
            SchedulerResult.IGNORED,
            result
        )
        assertTrue(fakeAlarmDelegate.exactCalls.isEmpty())
        assertTrue(fakeAlarmDelegate.inexactCalls.isEmpty())
    }

    @Test
    fun testSchedule_whenTimestampInPast_returnsIgnoredWithoutScheduling() {
        val result = scheduler.schedule(pastTime, defaultPendingIntent, AlarmManager.RTC_WAKEUP)

        assertEquals(SchedulerResult.IGNORED, result)
        assertTrue(fakeAlarmDelegate.exactCalls.isEmpty())
        assertTrue(fakeAlarmDelegate.inexactCalls.isEmpty())
    }

    @Test
    fun testSchedule_whenIntentIsNull_returnsIgnoredWithoutCallingAlarmDelegate() {
        val result = scheduler.schedule(futureTime, null, AlarmManager.RTC_WAKEUP)

        assertEquals(
            "When PendingIntent is null, scheduler must return IGNORED instead of false success OK",
            SchedulerResult.IGNORED,
            result
        )
        assertTrue(fakeAlarmDelegate.exactCalls.isEmpty())
        assertTrue(fakeAlarmDelegate.inexactCalls.isEmpty())
    }

    @Test
    fun testSchedule_whenAlarmManagerNotAvailable_returnsIgnored() {
        fakeAlarmDelegate.available = false

        val result = scheduler.schedule(futureTime, defaultPendingIntent, AlarmManager.RTC_WAKEUP)

        assertEquals(SchedulerResult.IGNORED, result)
        assertTrue(fakeAlarmDelegate.exactCalls.isEmpty())
        assertTrue(fakeAlarmDelegate.inexactCalls.isEmpty())
    }

    @Test
    fun testCanScheduleExactAlarms_reflectsDelegateAndAvailability() {
        fakeAlarmDelegate.available = true
        fakeAlarmDelegate.exactAlarmsPermitted = true
        assertTrue(scheduler.canScheduleExactAlarms())

        fakeAlarmDelegate.exactAlarmsPermitted = false
        assertFalse(scheduler.canScheduleExactAlarms())

        fakeAlarmDelegate.available = false
        fakeAlarmDelegate.exactAlarmsPermitted = true
        assertFalse(scheduler.canScheduleExactAlarms())
    }

    @Test
    fun testDefaultAlarmDelegate_whenAlarmManagerNull_failsExplicitly() {
        val fakeContext = FakeContext()
        val delegate = IntentScheduler.DefaultAlarmDelegate(fakeContext)

        assertFalse(delegate.isAvailable())
        assertFalse(delegate.canScheduleExactAlarms())

        assertThrows(IllegalStateException::class.java) {
            delegate.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, futureTime, defaultPendingIntent)
        }

        assertThrows(IllegalStateException::class.java) {
            delegate.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, futureTime, defaultPendingIntent)
        }
    }

    @Test
    fun testRescheduling_sequentialInexactRemindersSucceed() {
        val targetTime1 = now + 3600_000L
        val targetTime2 = now + 7200_000L
        fakeAlarmDelegate.exactAlarmsPermitted = false

        val result1 = scheduler.schedule(targetTime1, defaultPendingIntent, AlarmManager.RTC_WAKEUP)
        assertEquals(SchedulerResult.OK, result1)

        val result2 = scheduler.schedule(targetTime2, defaultPendingIntent, AlarmManager.RTC_WAKEUP)
        assertEquals(SchedulerResult.OK, result2)

        assertEquals(2, fakeAlarmDelegate.inexactCalls.size)
        assertEquals(targetTime1, fakeAlarmDelegate.inexactCalls[0].triggerAtMillis)
        assertSame(defaultPendingIntent, fakeAlarmDelegate.inexactCalls[0].operation)
        assertEquals(targetTime2, fakeAlarmDelegate.inexactCalls[1].triggerAtMillis)
        assertSame(defaultPendingIntent, fakeAlarmDelegate.inexactCalls[1].operation)
    }
}
