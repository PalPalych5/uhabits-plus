/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.intents.NotificationIntentIdentity
import org.isoron.uhabits.intents.WidgetIntentIdentity
import org.isoron.uhabits.receivers.ReminderReceiver
import org.isoron.uhabits.receivers.WidgetReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationsEffectiveAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun captureEvidence(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: context.cacheDir
        assertTrue("Evidence output directory must exist", directory.isDirectory || directory.mkdirs())
        val output = java.io.File(directory, "$name.png")
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Native screenshot must be available", screenshot)
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        android.util.Log.i("PR23NotificationsEvidence", output.absolutePath)
    }

    @Test
    fun canonicalFixture_notificationsEffectiveLifecycleAndLiveInvalidation() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-notif-$suffix.db"
        val testPrefs = "uhabits-test-notif-prefs-$suffix"

        val expSession = originalSession.copy(
            datasetId = "exp-$suffix",
            databaseFilename = testDbName,
            preferencesNamespace = testPrefs,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(remindersEnabled = true, widgetsEnabled = true),
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            assertTrue("Reminders must be enabled in test session", component.session.capabilities.remindersEnabled)
            ContributionSchema.createSchema(component.db)
            val repo = SQLiteContributionRepository(component.db)
            val today = getToday()

            // Weekly study habit: 300 min AT_LEAST
            val study = component.modelFactory.buildHabit().apply {
                name = "Study PR23"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
                targetType = NumericalHabitType.AT_LEAST
                reminder = Reminder(8, 0, WeekdayList(0x7F))
            }
            component.habitList.add(study)
            study.originalEntries.add(Entry(today, 60000)) // 60 min manual
            study.recompute()

            val hourOffset = component.preferences.midnightDelayHours
            val dayStart = startOfLocalDay(today, hourOffset, 0)

            // Contribution = 180 min -> Total = 240 min (< 300 min target)
            val contrib1 = Contribution(
                ContributionId("study-180-$suffix"),
                HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION,
                "session-1",
                180000L,
                dayStart + 1000,
                dayStart + 1000,
                dayStart + 1000
            )
            repo.record(contrib1)

            val tray = component.notificationTray
            assertNotNull("NotificationTray must be present", tray)

            // Alarm fires for today
            instrumentation.runOnMainSync {
                tray.show(study, today, 1000L)
            }
            instrumentation.waitForIdleSync()

            assertTrue("At 240m < 300m, notification must be showing", tray.isShowing(study))
            captureEvidence("pr23b-notification-active-240m")

            // Add 90 min contribution -> Total = 330 min (>= 300 min target)
            val contrib2 = Contribution(
                ContributionId("study-90-$suffix"),
                HabitRef(study.uuid!!),
                ContributionSourceType.FOCUS_SESSION,
                "session-2",
                90000L,
                dayStart + 2000,
                dayStart + 2000,
                dayStart + 2000
            )
            repo.record(contrib2)

            // Notify effective read revision change
            instrumentation.runOnMainSync {
                component.effectiveReadRevision.changed()
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)

            // Notification must be automatically dismissed!
            assertFalse("At 330m >= 300m, active notification must be dismissed", tray.isShowing(study))
            captureEvidence("pr23b-notification-dismissed-330m")

            // Tombstone the 90 min contribution via repository API
            repo.removeById(contrib2.id, dayStart + 3000)

            // Now re-evaluate or show reminder: effective is 240m again
            instrumentation.runOnMainSync {
                tray.show(study, today, 1000L)
            }
            instrumentation.waitForIdleSync()

            assertTrue("After tombstoning to 240m, notification must be unsuppressed/shown", tray.isShowing(study))
            captureEvidence("pr23b-notification-restored-240m")

            // Clean up
            instrumentation.runOnMainSync {
                tray.cancelAll()
            }
            instrumentation.waitForIdleSync()
            assertFalse("All notifications cancelled", tray.isShowing(study))

        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun pendingIntentIdentity_datasetAndHabitIsolation() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-pi-$suffix.db"
        val testPrefs = "uhabits-test-pi-prefs-$suffix"

        val expSession = originalSession.copy(
            datasetId = "exp-$suffix",
            databaseFilename = testDbName,
            preferencesNamespace = testPrefs,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(remindersEnabled = true, widgetsEnabled = true),
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            val factory = component.pendingIntentFactory
            val study = component.modelFactory.buildHabit().apply {
                name = "Study Isolation"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
                reminder = Reminder(8, 0, WeekdayList(0x7F))
            }
            component.habitList.add(study)

            // Create PendingIntents
            val showPi = factory.showHabit(study)
            val addPi = factory.addCheckmark(study, getToday())
            val removePi = factory.removeRepetition(study, getToday())
            val dismissPi = factory.dismissNotification(study)
            val snoozePi = factory.snoozeNotification(study)

            assertNotNull(showPi)
            assertNotNull(addPi)
            assertNotNull(removePi)
            assertNotNull(dismissPi)
            assertNotNull(snoozePi)

            // Test intent binding
            val rawIntent = Intent(context, ReminderReceiver::class.java).apply {
                data = Uri.parse(study.uriString)
            }
            NotificationIntentIdentity.bind(rawIntent, expSession, study)

            assertEquals(expSession.datasetId, rawIntent.getStringExtra(NotificationIntentIdentity.DATASET))
            assertEquals(study.uuid, rawIntent.getStringExtra(NotificationIntentIdentity.HABIT_UUID))
            assertEquals(expSession.datasetId, rawIntent.data?.getQueryParameter(NotificationIntentIdentity.DATASET))
            assertEquals(study.uuid, rawIntent.data?.getQueryParameter(NotificationIntentIdentity.HABIT_UUID))

            assertTrue("Matching session and habit must be accepted", NotificationIntentIdentity.accepts(rawIntent, expSession, study))

            // Cross-dataset intent rejection
            val foreignSession = expSession.copy(datasetId = "foreign-dataset-xyz")
            val foreignIntent = Intent(context, ReminderReceiver::class.java).apply {
                data = Uri.parse(study.uriString)
            }
            NotificationIntentIdentity.bind(foreignIntent, foreignSession, study)

            assertFalse("Mismatched dataset must be rejected", NotificationIntentIdentity.accepts(foreignIntent, expSession, study))

            // Test ReminderReceiver dispatch rejection
            foreignIntent.action = ReminderReceiver.ACTION_DISMISS_REMINDER
            val receiver = ReminderReceiver()
            receiver.onReceive(context, foreignIntent)

            // Test WidgetReceiver dispatch rejection with mismatched dataset
            val foreignWidgetIntent = Intent(context, WidgetReceiver::class.java).apply {
                action = WidgetReceiver.ACTION_ADD_REPETITION
                data = Uri.parse(study.uriString)
                putExtra("timestamp", getToday().unixTime)
            }
            NotificationIntentIdentity.bind(foreignWidgetIntent, foreignSession, study)
            WidgetIntentIdentity.bind(foreignWidgetIntent, foreignSession, study)

            val initialValue = study.originalEntries.get(getToday()).value
            val widgetReceiver = WidgetReceiver()
            widgetReceiver.onReceive(context, foreignWidgetIntent)

            // Verify habit was not mutated
            assertEquals("Foreign intent must not modify entries", initialValue, study.originalEntries.get(getToday()).value)

        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun atMostExceptionAndCancelAll() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-atmost-$suffix.db"
        val testPrefs = "uhabits-test-atmost-prefs-$suffix"

        val expSession = originalSession.copy(
            datasetId = "exp-$suffix",
            databaseFilename = testDbName,
            preferencesNamespace = testPrefs,
            mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL,
            organizationAuthority = OrganizationAuthorityMode.CONTAINER_LOCAL,
            capabilities = DatasetCapabilities.EXPERIMENTAL.copy(remindersEnabled = true),
        )

        instrumentation.runOnMainSync { app.rebuildComponent(expSession) }
        instrumentation.waitForIdleSync()

        try {
            val component = HabitsApplication.component
            val tray = component.notificationTray
            val today = getToday()

            val coffee = component.modelFactory.buildHabit().apply {
                name = "Coffee AT_MOST"
                type = HabitType.NUMERICAL
                unit = "cups"
                targetValue = 2.0
                frequency = Frequency(1, 1)
                targetType = NumericalHabitType.AT_MOST
                reminder = Reminder(8, 0, WeekdayList(0x7F))
            }
            component.habitList.add(coffee)
            coffee.originalEntries.add(Entry(today, 1000)) // 1 cup <= 2 cups (satisfied)
            coffee.recompute()

            instrumentation.runOnMainSync {
                tray.show(coffee, today, 1000L)
            }
            instrumentation.waitForIdleSync()

            // AT_MOST reminders are never suppressed by completion
            assertTrue("AT_MOST reminder must be shown even when satisfied", tray.isShowing(coffee))
            captureEvidence("pr23b-notification-atmost-shown")

            // Test cancelAll
            instrumentation.runOnMainSync {
                tray.cancelAll()
            }
            instrumentation.waitForIdleSync()
            assertFalse("cancelAll removes all notifications", tray.isShowing(coffee))

        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
        }
    }
}
