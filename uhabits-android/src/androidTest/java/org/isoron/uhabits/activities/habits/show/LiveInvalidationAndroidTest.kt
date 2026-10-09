/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.habits.show

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.time.getToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveInvalidationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun liveRepositoryRemoveByIdUpdatesOpenDetailWithoutNavigation() {
        val app = context.applicationContext as HabitsApplication
        val originalSession = HabitsApplication.component.session
        val suffix = UUID.randomUUID().toString().take(8)
        val testDbName = "uhabits-test-inval-$suffix.db"
        val testPrefs = "uhabits-test-inval-prefs-$suffix"

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
            assertTrue("Session must be experimental", component.session.isExperimental)
            assertNotNull("EffectiveHabitReadController must be present", component.effectiveHabitReads)

            ContributionSchema.createSchema(component.db)

            val habit = component.modelFactory.buildHabit().apply {
                name = "Live Invalidation Habit"
                type = HabitType.NUMERICAL
                unit = "min"
                targetValue = 300.0
                frequency = Frequency(1, 7)
            }
            instrumentation.runOnMainSync {
                component.habitList.add(habit)
            }
            instrumentation.waitForIdleSync()

            val contributionRepo = SQLiteContributionRepository(component.db)
            val contId = ContributionId("live-inval-$suffix")
            val todayTs = startOfLocalDay(getToday()) + 3600_000L
            val contribution = Contribution(
                id = contId,
                targetHabit = HabitRef(habit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = "focus-sess-1",
                contributedValue = 60_000L,
                effectiveTimestamp = todayTs,
                createdAt = todayTs,
                updatedAt = todayTs,
            )
            contributionRepo.record(contribution)

            val intent = IntentFactory().startShowHabitActivity(context, habit)
            var originalActivityInstance: ShowHabitActivity? = null

            ActivityScenario.launch<ShowHabitActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()

                scenario.onActivity { activity ->
                    originalActivityInstance = activity
                    assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                    assertFalse("Activity must not be finishing", activity.isFinishing)

                    val notice = activity.findViewById<View>(R.id.effectiveReadNotice)
                    assertNotNull(notice)
                    assertEquals(
                        "Notice must be VISIBLE when automatic contribution is present",
                        View.VISIBLE,
                        notice.visibility,
                    )
                }

                // Call the REAL repository API on the app's active database connection.
                // Notice that ShowHabitActivity is actively resumed on screen — NO back/forward navigation.
                val removalResult = contributionRepo.removeById(contId, System.currentTimeMillis())
                assertEquals(
                    org.isoron.uhabits.core.contribution.ContributionRemovalResult.REMOVED,
                    removalResult,
                )

                // Wait for the asynchronous revision observer and screen.refresh() coroutine.
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                instrumentation.waitForIdleSync()

                // Verify the SAME activity instance has updated in-place without restart or navigation!
                scenario.onActivity { activity ->
                    assertEquals(
                        "Must be the exact same Activity instance (no recreation)",
                        originalActivityInstance,
                        activity,
                    )
                    assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                    assertFalse("Activity must not be finishing", activity.isFinishing)

                    val notice = activity.findViewById<View>(R.id.effectiveReadNotice)
                    assertNotNull(notice)
                    assertEquals(
                        "Notice must be GONE after contribution is removed live via repository API",
                        View.GONE,
                        notice.visibility,
                    )
                }
            }
        } finally {
            instrumentation.runOnMainSync { app.rebuildComponent(originalSession) }
            instrumentation.waitForIdleSync()
            context.deleteDatabase(testDbName)
        }
    }
}
