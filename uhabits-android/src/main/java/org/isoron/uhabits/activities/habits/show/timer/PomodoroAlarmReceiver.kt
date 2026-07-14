package org.isoron.uhabits.activities.habits.show.timer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.timer.PomodoroCompletion

class PomodoroAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val component = (context.applicationContext as HabitsApplication).component
        when (intent.action) {
            ACTION_PHASE_COMPLETE -> component.timerSessionManager.onAlarm(
                intent.getLongExtra(EXTRA_REVISION, -1L)
            )
            ACTION_START_NEXT -> {
                val completion = runCatching {
                    PomodoroCompletion.valueOf(intent.getStringExtra(EXTRA_COMPLETION).orEmpty())
                }.getOrNull() ?: return
                val started = component.timerSessionManager.startNextPhaseFromNotification(
                    habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L),
                    eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L),
                    completion = completion
                )
                if (started) component.pomodoroCompletionNotifier.cancelCompletion()
            }
            ACTION_DISMISS -> component.timerSessionManager.dismissCompletionNotification(
                intent.getLongExtra(EXTRA_EVENT_ID, -1L)
            )
        }
    }

    companion object {
        const val ACTION_PHASE_COMPLETE = "org.isoron.uhabits.POMODORO_PHASE_COMPLETE"
        private const val ACTION_START_NEXT = "org.isoron.uhabits.POMODORO_START_NEXT"
        private const val ACTION_DISMISS = "org.isoron.uhabits.POMODORO_DISMISS"
        private const val EXTRA_REVISION = "pomodoro.revision"
        private const val EXTRA_EVENT_ID = "pomodoro.eventId"
        private const val EXTRA_HABIT_ID = "pomodoro.habitId"
        private const val EXTRA_COMPLETION = "pomodoro.completion"
        const val ALARM_REQUEST_CODE = 7360

        fun alarmPendingIntent(context: Context, revision: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, PomodoroAlarmReceiver::class.java).apply {
                action = ACTION_PHASE_COMPLETE
                putExtra(EXTRA_REVISION, revision)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun startNextPendingIntent(
            context: Context,
            event: PomodoroCompletionEvent
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            7361,
            Intent(context, PomodoroAlarmReceiver::class.java).apply {
                action = ACTION_START_NEXT
                putExtra(EXTRA_EVENT_ID, event.eventId)
                putExtra(EXTRA_HABIT_ID, event.habitId)
                putExtra(EXTRA_COMPLETION, event.completion.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun dismissPendingIntent(context: Context, eventId: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            7362,
            Intent(context, PomodoroAlarmReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_EVENT_ID, eventId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
