package org.isoron.uhabits.activities.settings

import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationBackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun systemAndToolbarBackPopDetailBeforeMainNavigationIncludingAfterRecreation() {
        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigate(MainDestination.SETTINGS)
                openAppearance(activity)
            }

            device.pressBack()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertHomeVisible(activity) }

            scenario.onActivity { activity -> openAppearance(activity) }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val section = activeSettings(activity)
                assertEquals(1, section.childFragmentManager.backStackEntryCount)
            }

            device.pressBack()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertHomeVisible(activity) }

            scenario.onActivity { activity ->
                openAppearance(activity)
                val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.settingsDetailToolbar)
                assertNotNull(toolbar)
                toolbar.findViewById<android.widget.ImageButton>(androidx.appcompat.R.id.home).performClick()
                activeSettings(activity).childFragmentManager.executePendingTransactions()
                assertHomeVisible(activity)
            }
        }
    }

    @Test
    fun developerSectionVisibilityTracksPreference() {
        val app = context.applicationContext as HabitsApplication
        val title = context.getString(R.string.pref_developer_section_title)
        app.component.preferences.isDeveloper = false

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.SETTINGS)).use {
            device.waitForIdle()
            assertFalse(device.hasObject(By.text(title)))
        }

        app.component.preferences.isDeveloper = true
        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.SETTINGS)).use {
            device.waitForIdle()
            assertTrue(device.hasObject(By.text(title)))
        }
    }

    @Test
    fun archiveBackRestoresHabitsSettingsDetailIncludingAfterRecreation() {
        val app = context.applicationContext as HabitsApplication
        val previousReducedMotion = app.component.preferences.isConfettiAnimationDisabled
        app.component.preferences.isConfettiAnimationDisabled = true
        try {
            ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.navigate(MainDestination.SETTINGS)
                    val settings = activeSettings(activity)
                    settings.openSettingsSection(SettingsSectionId.HABITS)
                    settings.childFragmentManager.executePendingTransactions()
                    activity.navigate(MainDestination.ARCHIVE)
                    assertTrue(settings.isHidden)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNavigationContainer).visibility)
                }

                device.pressBack()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> assertHabitsDetailVisible(activity) }

                scenario.onActivity { activity -> activity.navigate(MainDestination.ARCHIVE) }
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(settingsFragment(activity).isHidden)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNavigationContainer).visibility)
                }

                device.pressBack()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> assertHabitsDetailVisible(activity) }
            }
        } finally {
            app.component.preferences.isConfettiAnimationDisabled = previousReducedMotion
        }
    }

    private fun openAppearance(activity: MainActivity) {
        val section = activeSettings(activity)
        section.openSettingsSection(SettingsSectionId.APPEARANCE)
        section.childFragmentManager.executePendingTransactions()
        assertEquals(1, section.childFragmentManager.backStackEntryCount)
    }

    private fun assertHomeVisible(activity: MainActivity) {
        val section = activeSettings(activity)
        section.childFragmentManager.executePendingTransactions()
        assertEquals(0, section.childFragmentManager.backStackEntryCount)
        assertTrue(section.childFragmentManager.fragments.none { it is SettingsDetailFragment && !it.isHidden })
    }

    private fun assertHabitsDetailVisible(activity: MainActivity) {
        val section = activeSettings(activity)
        section.childFragmentManager.executePendingTransactions()
        assertEquals(1, section.childFragmentManager.backStackEntryCount)
        val detail = section.childFragmentManager.fragments
            .filterIsInstance<SettingsDetailFragment>()
            .first { !it.isHidden }
        assertEquals(
            SettingsSectionId.HABITS.name,
            detail.arguments?.getString(SettingsFragment.ARG_DETAIL_SECTION)
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNavigationContainer).visibility)
    }

    private fun activeSettings(activity: MainActivity): SettingsSectionFragment =
        activity.supportFragmentManager.fragments
            .filterIsInstance<SettingsSectionFragment>()
            .first { !it.isHidden }

    private fun settingsFragment(activity: MainActivity): SettingsSectionFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<SettingsSectionFragment>().first()
}
