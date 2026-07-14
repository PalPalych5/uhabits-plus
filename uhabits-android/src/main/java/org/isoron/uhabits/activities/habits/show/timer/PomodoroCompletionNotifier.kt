package org.isoron.uhabits.activities.habits.show.timer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.timer.PomodoroCompletion
import org.isoron.uhabits.intents.IntentFactory

data class PomodoroCompletionEvent(
    val eventId: Long,
    val habitId: Long,
    val habitName: String,
    val completion: PomodoroCompletion,
    val durationMillis: Long,
    val overtimeStartedAtMillis: Long? = null
)

data class PomodoroAlertHealth(
    val notificationsEnabled: Boolean,
    val channelEnabled: Boolean,
    val hasSound: Boolean,
    val hasVibration: Boolean,
    val exactAlarmsEnabled: Boolean
)

enum class PomodoroAlertIssue {
    NOTIFICATIONS_DISABLED,
    CHANNEL_DISABLED,
    CHANNEL_SILENT,
    EXACT_ALARMS_DISABLED
}

class PomodoroCompletionNotifier(
    private val context: Context,
    private val habitList: HabitList,
    private val alarmScheduler: PomodoroAlarmScheduler
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    @Volatile private var channelEnsured = false

    fun ensureChannel() {
        if (channelEnsured) return
        synchronized(this) {
            if (channelEnsured) return
            createChannel()
            channelEnsured = true
        }
    }

    private fun createChannel() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pomodoro_completion_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.pomodoro_completion_channel_description)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 350)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun health(): PomodoroAlertHealth {
        ensureChannel()
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        return PomodoroAlertHealth(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelEnabled = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE,
            hasSound = channel?.sound != null,
            hasVibration = channel?.shouldVibrate() == true,
            exactAlarmsEnabled = alarmScheduler.canScheduleExactAlarms()
        )
    }

    fun canPostCompletion(): Boolean {
        ensureChannel()
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun isCompletionVisible(): Boolean =
        notificationManager.activeNotifications.any { it.id == COMPLETION_NOTIFICATION_ID }

    @SuppressLint("MissingPermission")
    fun showCompletion(event: PomodoroCompletionEvent) {
        ensureChannel()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val isFocus = event.completion == PomodoroCompletion.FOCUS
        val title = context.getString(
            if (isFocus) R.string.pomodoro_notification_focus_title
            else R.string.pomodoro_notification_break_title
        )
        val text = context.getString(
            if (isFocus) R.string.pomodoro_notification_focus_text
            else R.string.pomodoro_notification_break_text,
            event.habitName,
            event.durationMillis / 60_000L
        )
        val builder = baseBuilder(title, text)
            .setContentIntent(contentPendingIntent(event.habitId))
            .addAction(
                R.drawable.ic_notification,
                context.getString(
                    if (isFocus) R.string.pomodoro_notification_start_break
                    else R.string.pomodoro_notification_start_focus
                ),
                PomodoroAlarmReceiver.startNextPendingIntent(context, event)
            )
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.pomodoro_notification_dismiss),
                PomodoroAlarmReceiver.dismissPendingIntent(context, event.eventId)
            )
        event.overtimeStartedAtMillis?.let { overtimeStartedAtMillis ->
            builder
                .setWhen(overtimeStartedAtMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
        }
        val notification = builder.build()
        NotificationManagerCompat.from(context).notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showTestNotification() {
        ensureChannel()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = baseBuilder(
            context.getString(R.string.pomodoro_test_notification_title),
            context.getString(R.string.pomodoro_test_notification_text)
        ).build()
        NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
    }

    fun cancelCompletion() {
        NotificationManagerCompat.from(context).cancel(COMPLETION_NOTIFICATION_ID)
    }

    private fun baseBuilder(title: String, text: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)

    private fun contentPendingIntent(habitId: Long): PendingIntent? {
        val habit = habitList.getById(habitId) ?: return null
        return PendingIntent.getActivity(
            context,
            (habitId % Int.MAX_VALUE).toInt(),
            IntentFactory().startShowHabitActivity(context, habit).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "POMODORO_COMPLETION_V1"
        private const val COMPLETION_NOTIFICATION_ID = 7351
        private const val TEST_NOTIFICATION_ID = 7352
    }
}
