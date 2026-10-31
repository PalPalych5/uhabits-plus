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
import android.app.AlarmManager.RTC
import android.app.AlarmManager.RTC_WAKEUP
import android.app.PendingIntent
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.time.DateFormats
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.reminders.ReminderScheduler.SchedulerResult
import org.isoron.uhabits.core.reminders.ReminderScheduler.SystemScheduler
import org.isoron.uhabits.inject.AppContext
import java.util.Date
import kotlin.math.min

@Inject
@AppScope
open class IntentScheduler(
    @AppContext private val context: Context,
    private val pendingIntents: PendingIntentFactory
) : SystemScheduler {

    interface AlarmDelegate {
        fun isAvailable(): Boolean = true
        fun canScheduleExactAlarms(): Boolean
        fun setExactAndAllowWhileIdle(alarmType: Int, triggerAtMillis: Long, operation: PendingIntent)
        fun setAndAllowWhileIdle(alarmType: Int, triggerAtMillis: Long, operation: PendingIntent)
    }

    class DefaultAlarmDelegate(private val context: Context) : AlarmDelegate {
        private val manager: AlarmManager?
            get() = context.getSystemService(ALARM_SERVICE) as? AlarmManager

        override fun isAvailable(): Boolean = manager != null

        override fun canScheduleExactAlarms(): Boolean {
            val mgr = manager ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mgr.canScheduleExactAlarms()
            } else {
                true
            }
        }

        override fun setExactAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            val mgr = manager ?: throw IllegalStateException("AlarmManager is not available.")
            mgr.setExactAndAllowWhileIdle(alarmType, triggerAtMillis, operation)
        }

        override fun setAndAllowWhileIdle(
            alarmType: Int,
            triggerAtMillis: Long,
            operation: PendingIntent
        ) {
            val mgr = manager ?: throw IllegalStateException("AlarmManager is not available.")
            mgr.setAndAllowWhileIdle(alarmType, triggerAtMillis, operation)
        }
    }

    @VisibleForTesting
    open var alarmDelegate: AlarmDelegate = DefaultAlarmDelegate(context)

    open fun canScheduleExactAlarms(): Boolean {
        return alarmDelegate.isAvailable() && alarmDelegate.canScheduleExactAlarms()
    }

    open fun schedule(timestamp: Long, intent: PendingIntent?, alarmType: Int): SchedulerResult {
        val now = System.currentTimeMillis()
        Log.d("IntentScheduler", "timestamp=$timestamp now=$now")
        if (timestamp < now) {
            Log.e(
                "IntentScheduler",
                "Ignoring attempt to schedule intent in the past."
            )
            return SchedulerResult.IGNORED
        }
        if (intent == null) {
            Log.e("IntentScheduler", "PendingIntent is null; cannot schedule alarm.")
            return SchedulerResult.IGNORED
        }
        if (!alarmDelegate.isAvailable()) {
            Log.e("IntentScheduler", "AlarmManager is not available.")
            return SchedulerResult.IGNORED
        }

        if (alarmDelegate.canScheduleExactAlarms()) {
            try {
                alarmDelegate.setExactAndAllowWhileIdle(alarmType, timestamp, intent)
                Log.d("IntentScheduler", "Scheduled exact alarm at $timestamp")
                return SchedulerResult.OK
            } catch (e: SecurityException) {
                Log.w(
                    "IntentScheduler",
                    "SecurityException scheduling exact alarm; falling back to inexact alarm (exact delivery time not guaranteed)",
                    e
                )
            }
        } else {
            Log.w(
                "IntentScheduler",
                "No permission to schedule exact alarms; falling back to inexact alarm (exact delivery time not guaranteed)"
            )
        }

        return try {
            alarmDelegate.setAndAllowWhileIdle(alarmType, timestamp, intent)
            Log.d(
                "IntentScheduler",
                "Scheduled inexact fallback alarm at $timestamp (exact delivery time not guaranteed)"
            )
            SchedulerResult.OK
        } catch (e: SecurityException) {
            Log.e(
                "IntentScheduler",
                "SecurityException scheduling inexact fallback alarm at $timestamp",
                e
            )
            SchedulerResult.IGNORED
        }
    }

    override fun scheduleShowReminder(
        reminderTime: Long,
        habit: Habit,
        timestamp: Long
    ): SchedulerResult {
        val intent = pendingIntents.showReminder(habit, reminderTime, timestamp)
        logReminderScheduled(habit, reminderTime)
        return schedule(reminderTime, intent, RTC_WAKEUP)
    }

    override fun scheduleWidgetUpdate(updateTime: Long): SchedulerResult {
        val intent = pendingIntents.updateWidgets()
        return schedule(updateTime, intent, RTC)
    }

    override fun log(componentName: String, msg: String) {
        Log.d(componentName, msg)
    }

    private fun logReminderScheduled(habit: Habit, reminderTime: Long) {
        val min = min(5, habit.name.length)
        val name = habit.name.substring(0, min)
        val df = DateFormats.getBackupDateFormat()
        val time = df.format(Date(reminderTime))
        Log.i(
            "ReminderHelper",
            String.format("Setting alarm (%s): %s", time, name)
        )
    }
}
