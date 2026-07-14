package org.isoron.uhabits.activities.habits.show.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class PomodoroAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(triggerAtMillis: Long, revision: Long) {
        val intent = PomodoroAlarmReceiver.alarmPendingIntent(context, revision)
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }

    fun cancel() {
        alarmManager.cancel(existingPendingIntent())
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun existingPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        PomodoroAlarmReceiver.ALARM_REQUEST_CODE,
        Intent(context, PomodoroAlarmReceiver::class.java).setAction(PomodoroAlarmReceiver.ACTION_PHASE_COMPLETE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
