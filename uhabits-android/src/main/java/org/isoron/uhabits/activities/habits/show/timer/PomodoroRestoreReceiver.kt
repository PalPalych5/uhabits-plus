package org.isoron.uhabits.activities.habits.show.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.isoron.uhabits.HabitsApplication

class PomodoroRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) return
        val component = (context.applicationContext as HabitsApplication).component
        component.timerSessionManager.rescheduleAlarmFromSystem()
    }
}
