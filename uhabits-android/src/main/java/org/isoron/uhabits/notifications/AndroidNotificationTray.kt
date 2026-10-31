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

package org.isoron.uhabits.notifications

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory.decodeResource
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat.Action
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationCompat.WearableExtender
import androidx.core.app.NotificationManagerCompat
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.inject.AppContext
import org.isoron.uhabits.intents.PendingIntentFactory

@Inject
@AppScope
class AndroidNotificationTray(
    @AppContext private val context: Context,
    private val pendingIntents: PendingIntentFactory,
    private val preferences: Preferences,
    private val ringtoneManager: RingtoneManager? = null
) : NotificationTray.SystemTray {
    private var active = HashSet<Int>()

    @VisibleForTesting
    internal var notificationPoster: (Int, Notification) -> Unit = { id, notification ->
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    @VisibleForTesting
    internal var notificationBuilder: (Habit, Long, LocalDate, Boolean, Boolean) -> Notification =
        { habit, reminderTime, date, disableSound, isTest ->
            buildNotification(habit, reminderTime, date, disableSound, isTest)
        }

    @VisibleForTesting
    internal var channelCreator: (Context) -> Unit = {
        createAndroidNotificationChannel(it)
    }

    override fun log(msg: String) {
        Log.d("AndroidNotificationTray", msg)
    }

    override fun removeNotification(notificationId: Int) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(notificationId)
        active.remove(notificationId)
    }

    override fun showNotification(
        habit: Habit,
        notificationId: Int,
        date: LocalDate,
        reminderTime: Long
    ) {
        val posted = notify(habit, notificationId, date, reminderTime, isTest = false)
        if (posted) {
            active.add(notificationId)
        }
    }

    fun showTestNotification(habit: Habit, date: LocalDate, reminderTime: Long) {
        notify(habit, TEST_NOTIFICATION_ID, date, reminderTime, isTest = true)
    }

    @VisibleForTesting
    internal fun isNotificationActive(notificationId: Int): Boolean = active.contains(notificationId)

    @VisibleForTesting
    internal fun notify(
        habit: Habit,
        notificationId: Int,
        date: LocalDate,
        reminderTime: Long,
        isTest: Boolean
    ): Boolean {
        val notification = notificationBuilder(habit, reminderTime, date, false, isTest)
        channelCreator(context)
        return try {
            notificationPoster(notificationId, notification)
            true
        } catch (e: SecurityException) {
            Log.w(
                "AndroidNotificationTray",
                "Failed to show notification. Permission denied.",
                e
            )
            false
        } catch (e: RuntimeException) {
            // Some Xiaomi phones produce a RuntimeException if custom notification sounds are used.
            Log.i(
                "AndroidNotificationTray",
                "Failed to show notification. Retrying without sound."
            )
            val n = notificationBuilder(
                habit,
                reminderTime,
                date,
                true,
                isTest
            )
            try {
                notificationPoster(notificationId, n)
                true
            } catch (retryException: SecurityException) {
                Log.w(
                    "AndroidNotificationTray",
                    "Failed to show notification on soundless retry. Permission denied.",
                    retryException
                )
                false
            } catch (retryException: RuntimeException) {
                Log.w(
                    "AndroidNotificationTray",
                    "Failed to show notification even without sound.",
                    retryException
                )
                false
            }
        }
    }

    fun buildNotification(
        habit: Habit,
        reminderTime: Long,
        date: LocalDate,
        disableSound: Boolean = false,
        isTest: Boolean = false
    ): Notification {
        val defaultText = context.getString(R.string.default_reminder_question)
        val builder = Builder(context, REMINDERS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(habit.name)
            .setContentText(if (habit.question.isBlank()) defaultText else habit.question)
            .setContentIntent(pendingIntents.showHabit(habit))
            .setSound(null)
            .setWhen(reminderTime)
            .setShowWhen(true)
            .setAutoCancel(isTest)
            .setOngoing(!isTest && preferences.shouldMakeNotificationsSticky())

        if (!isTest) {
            val addRepetitionAction = Action(
                R.drawable.ic_action_check,
                context.getString(R.string.yes),
                pendingIntents.addCheckmark(habit, date)
            )
            val removeRepetitionAction = Action(
                R.drawable.ic_action_cancel,
                context.getString(R.string.no),
                pendingIntents.removeRepetition(habit, date)
            )
            val enterAction = Action(
                R.drawable.ic_action_check,
                context.getString(R.string.enter),
                pendingIntents.showNumberPicker(habit, date)
            )
            val wearableExtender = WearableExtender()
                .setBackground(decodeResource(context.resources, R.drawable.stripe))

            builder.setDeleteIntent(pendingIntents.dismissNotification(habit))
            if (habit.isNumerical) {
                wearableExtender.addAction(enterAction)
                builder.addAction(enterAction)
            } else {
                wearableExtender
                    .addAction(addRepetitionAction)
                    .addAction(removeRepetitionAction)
                builder
                    .addAction(addRepetitionAction)
                    .addAction(removeRepetitionAction)
            }

            if (SDK_INT < Build.VERSION_CODES.S) {
                val snoozeAction = Action(
                    R.drawable.ic_action_snooze,
                    context.getString(R.string.snooze),
                    pendingIntents.snoozeNotification(habit)
                )
                wearableExtender.addAction(snoozeAction)
                builder.addAction(snoozeAction)
            }
            builder.extend(wearableExtender)
        }

        if (!disableSound && ringtoneManager != null) {
            builder.setSound(ringtoneManager.getURI())
        }

        return builder.build()
    }

    companion object {
        private const val REMINDERS_CHANNEL_ID = "REMINDERS"
        private const val TEST_NOTIFICATION_ID = Int.MAX_VALUE
        fun createAndroidNotificationChannel(context: Context) {
            val notificationManager = context.getSystemService(Activity.NOTIFICATION_SERVICE)
                as NotificationManager
            val channel = NotificationChannel(
                REMINDERS_CHANNEL_ID,
                context.resources.getString(R.string.reminder),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}
