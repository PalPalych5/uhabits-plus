package org.isoron.uhabits.activities.settings

import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

            var backLabel = ""
            scenario.onActivity { activity ->
                openAppearance(activity)
                backLabel = activity.getString(R.string.settings_back)
            }
            requireNotNull(device.wait(Until.findObject(By.desc(backLabel)), 5_000L)).click()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activeSettings(activity).childFragmentManager.executePendingTransactions()
                assertHomeVisible(activity)
            }
        }
    }

    @Test
    fun developerSectionVisibilityTracksPreference() {
        val app = context.applicationContext as HabitsApplication
        val preferences = app.component.preferences
        val previous = preferences.isDeveloper
        try {
            for (isDeveloper in listOf(false, true)) {
                preferences.isDeveloper = isDeveloper
                val intent = MainActivity.intent(context, MainDestination.SETTINGS)
                ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                    var title = ""
                    scenario.onActivity { title = it.getString(R.string.pref_developer_section_title) }
                    device.waitForIdle()
                    assertEquals(isDeveloper, canFindSettingsSection(title))
                }
            }
        } finally {
            preferences.isDeveloper = previous
        }
    }

    private fun canFindSettingsSection(title: String): Boolean {
        val selector = By.descStartsWith(title)
        repeat(5) {
            if (device.hasObject(selector)) return true
            val list = device.findObject(By.scrollable(true)) ?: return false
            list.setGestureMargin(device.displayWidth / 10)
            val canContinue = list.scroll(Direction.DOWN, 0.9f)
            device.waitForIdle()
            if (!canContinue) return device.hasObject(selector)
        }
        return device.hasObject(selector)
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
