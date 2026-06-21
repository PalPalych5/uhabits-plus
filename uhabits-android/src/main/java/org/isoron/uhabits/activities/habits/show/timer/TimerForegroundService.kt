package org.isoron.uhabits.activities.habits.show.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerChronometerMode
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerNotificationAction
import org.isoron.uhabits.core.timer.TimerNotificationState
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.isoron.uhabits.core.timer.toNotificationState
import org.isoron.uhabits.intents.IntentFactory
import kotlin.math.roundToLong

class TimerForegroundService : Service() {
    private lateinit var manager: TimerSessionManager
    private var isForeground = false
    private var lastRenderKey: RenderKey? = null
    private val listener = TimerSessionManager.Listener { syncNotification() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        manager = (applicationContext as HabitsApplication).component.timerSessionManager
        manager.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_COMMAND)?.let { commandName ->
            val command = runCatching { TimerNotificationAction.valueOf(commandName) }.getOrNull()
            if (command != null) handleCommand(command, intent.getLongExtra(EXTRA_HABIT_ID, -1L))
        }
        syncNotification(force = true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        manager.removeListener(listener)
        if (isForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleCommand(command: TimerNotificationAction, habitId: Long) {
        val habit = (applicationContext as HabitsApplication).component.habitList
            .getById(habitId) ?: return
        val state = manager.snapshot()
        if (state.habitId != habitId) return
        when (command) {
            TimerNotificationAction.PAUSE,
            TimerNotificationAction.RESUME,
            TimerNotificationAction.START_BREAK -> manager.startOrPause(habit)
            TimerNotificationAction.FINISH -> manager.finish(habit)
            TimerNotificationAction.RESET -> manager.reset(habit)
        }
    }

    private fun syncNotification(force: Boolean = false) {
        if (!::manager.isInitialized) return
        val snapshot = manager.snapshot()
        val state = snapshot.toNotificationState()
        if (state == null) {
            lastRenderKey = null
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
            stopSelf()
            return
        }
        val key = RenderKey.from(state)
        if (!force && key == lastRenderKey) return
        lastRenderKey = key
        val notification = buildNotification(snapshot, state)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType
        )
        isForeground = true
    }

    private fun buildNotification(
        snapshot: TimerSessionSnapshot,
        state: TimerNotificationState
    ): Notification {
        val habit = (applicationContext as HabitsApplication).component.habitList
            .getById(state.habitId)
        val contentIntent = habit?.let {
            PendingIntent.getActivity(
                this,
                (state.habitId % Int.MAX_VALUE).toInt(),
                IntentFactory().startShowHabitActivity(this, it).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.habitName)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val phaseText = phaseText(state)
        when (state.chronometerMode) {
            TimerChronometerMode.COUNT_UP -> builder
                .setContentText(phaseText)
                .setWhen(System.currentTimeMillis() - snapshot.elapsedMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
            TimerChronometerMode.COUNT_DOWN -> builder
                .setContentText(phaseText)
                .setWhen(System.currentTimeMillis() + snapshot.displayMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
            TimerChronometerMode.STATIC -> builder.setContentText(
                getString(
                    R.string.timer_notification_paused_format,
                    phaseText,
                    formatMillis(state.displayMillis)
                )
            )
        }

        state.actions.forEach { action ->
            builder.addAction(
                R.drawable.ic_notification,
                actionLabel(action),
                commandPendingIntent(action, state.habitId)
            )
        }
        return builder.build()
    }

    private fun phaseText(state: TimerNotificationState): String = when {
        state.mode == TimerMode.STOPWATCH -> getString(R.string.timer_mode_stopwatch)
        state.phase == PomodoroPhase.FOCUS -> getString(R.string.timer_notification_pomodoro_focus)
        !state.isRunning && state.actions.contains(TimerNotificationAction.START_BREAK) ->
            getString(R.string.timer_notification_focus_complete)
        else -> getString(R.string.timer_notification_pomodoro_break)
    }

    private fun actionLabel(action: TimerNotificationAction): String = getString(
        when (action) {
            TimerNotificationAction.PAUSE -> R.string.timer_pause
            TimerNotificationAction.RESUME -> R.string.timer_resume
            TimerNotificationAction.FINISH -> R.string.timer_finish
            TimerNotificationAction.RESET -> R.string.timer_reset
            TimerNotificationAction.START_BREAK -> R.string.pomodoro_start_break
        }
    )

    private fun commandPendingIntent(
        action: TimerNotificationAction,
        habitId: Long
    ): PendingIntent {
        val intent = Intent(this, TimerForegroundService::class.java).apply {
            putExtra(EXTRA_COMMAND, action.name)
            putExtra(EXTRA_HABIT_ID, habitId)
        }
        val requestCode = ((habitId % 10_000) * 10 + action.ordinal).toInt()
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.timer_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.timer_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatMillis(millis: Long): String {
        val totalSeconds = (millis / 1000.0).roundToLong().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private data class RenderKey(
        val habitId: Long,
        val mode: TimerMode,
        val phase: PomodoroPhase,
        val isRunning: Boolean,
        val actions: List<TimerNotificationAction>
    ) {
        companion object {
            fun from(state: TimerNotificationState) = RenderKey(
                state.habitId,
                state.mode,
                state.phase,
                state.isRunning,
                state.actions
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "TIMER"
        private const val NOTIFICATION_ID = 7350
        private const val EXTRA_COMMAND = "timer.command"
        private const val EXTRA_HABIT_ID = "timer.habitId"

        fun sync(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TimerForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimerForegroundService::class.java))
        }
    }
}
