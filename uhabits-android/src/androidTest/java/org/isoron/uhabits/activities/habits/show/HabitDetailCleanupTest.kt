package org.isoron.uhabits.activities.habits.show

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.isoron.uhabits.BaseViewTest
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.intents.IntentFactory
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly invoked on a fresh disposable guest; BaseViewTest replaces its test fixtures. */
@RunWith(AndroidJUnit4::class)
class HabitDetailCleanupTest : BaseViewTest() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val before get() = args.getString("cleanupPhase") == "before"

    @Before
    override fun setUp() {
        check(args.getString("disposableAvd") == "UHabit_PR1_Disposable_20261019")
        check(Build.MODEL.contains("sdk"))
        check(UiDevice.getInstance(instrumentation).executeShellCommand(
            "getprop ro.boot.qemu.avd_name").trim() == "UHabit_PR1_Disposable_20261019")
        super.setUp()
    }

    @Test
    fun captureAndVerifyCleanup() {
        val booleanHabit = fixtures.createLongHabit()
        val numericalHabit = fixtures.createLongNumericalHabit().apply {
            unit = "min"
            targetValue = 30.0
            question = "How many minutes did you read today?"
        }
        for (dark in listOf(false, true)) {
            prefs.theme = if (dark) 1 else 2
            setTheme(if (dark) R.style.AppBaseThemeDark else R.style.AppBaseTheme)
            val theme = if (dark) DarkTheme() else LightTheme()
            for ((name, habit) in listOf("boolean" to booleanHabit, "numerical" to numericalHabit)) {
                ActivityScenario.launch<ShowHabitActivity>(IntentFactory().startShowHabitActivity(targetContext, habit)).use { scenario ->
                    instrumentation.waitForIdleSync()
                    UiDevice.getInstance(instrumentation).waitForIdle(1500)
                    UiDevice.getInstance(instrumentation).takeScreenshot(File(output(), "${if (dark) "dark" else "light"}-$name-screen.png"))
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ShowHabitView
                        val state = ShowHabitPresenter.buildState(habit, prefs, theme)
                        root.setState(state)
                        root.setFocusProgress(null)
                        val prefix = "${if (dark) "dark" else "light"}-$name"
                        capture(root, prefix)
                        if (!before) {
                            assertEquals(View.GONE, root.named("edit").visibility)
                            for (name in listOf("scoreCard", "streakCard", "frequencyCard",
                                "historyCard", "barCard")) assertEquals(View.VISIBLE, root.named(name).visibility)
                            assertEquals(View.GONE, root.named("effectiveReadNotice").visibility)
                            val item = PopupMenu(activity, root).menu.add(0, R.id.action_edit_habit_history, 0, "Edit entries")
                            assertTrue(activity.onOptionsItemSelected(item))
                            activity.supportFragmentManager.executePendingTransactions()
                            val editor = activity.supportFragmentManager.findFragmentByTag("historyEditor") as DialogFragment?
                            assertNotNull("History editing stays available from the menu", editor)
                            editor!!.dismissNow()
                        }
                        if (habit.isNumerical) verifyProgress(root, state, prefix)
                    }
                }
            }
        }
    }

    private fun verifyProgress(root: ShowHabitView, state: org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState, prefix: String) {
        val progress = FocusProgressPresentation(54_000, false, 12_000, 42_000, 0,
            HabitGoalStatus.SATISFIED, "min", 30.0, false)
        root.setState(state.copy(hasAutomaticQuantity = true))
        root.setFocusProgress(progress)
        capture(root, "$prefix-focus")
        if (!before) {
            assertEquals(View.GONE, root.named("effectiveReadNotice").visibility)
            assertTrue((root.named("focusValue") as TextView).text.contains("54"))
            assertTrue((root.named("focusGoal") as TextView).text.contains("30"))
            assertTrue("Progress details stay available in the menu", (root.context as ShowHabitActivity).hasProgressDetails)
        }
        root.setState(state.copy(hasAutomaticQuantity = true, hasIncompleteCoverage = true,
            scoreUnavailable = true, streakUnavailable = true))
        root.setFocusProgress(progress.copy(lowerBound = true))
        capture(root, "$prefix-incomplete")
        assertEquals(View.VISIBLE, root.named("effectiveReadNotice").visibility)
        assertEquals(View.VISIBLE, root.named("focusCoverage").visibility)
        root.setState(state.copy(quantityUnavailable = true))
        root.setFocusProgress(null, true)
        assertEquals(View.VISIBLE, root.named("effectiveReadNotice").visibility)
        assertEquals(View.VISIBLE, root.named("focusValue").visibility)
        root.setEffectiveReadUnavailable()
        capture(root, "$prefix-error")
        assertEquals(View.VISIBLE, root.named("effectiveReadNotice").visibility)
        assertEquals(View.GONE, root.named("historyCard").visibility)
    }

    private fun capture(root: ShowHabitView, name: String) {
        val content = root.named("linearLayout")
        val width = root.width.coerceAtLeast(1080)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, width, content.measuredHeight)
        val output = output()
        fun save(view: View, suffix: String) {
            if (view.visibility != View.VISIBLE || view.height == 0) return
            File(output, "$name$suffix.png").outputStream().use { renderView(view).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        save(content, "")
        for (name in listOf("overviewCard", "scoreCard", "streakCard",
            "frequencyCard", "barCard")) save(root.named(name), "-$name")
        save(root.named("historyCard").named("chart"), "-historyChart")
        root.named("scrollView").requestLayout()
    }

    // IDs can move between baseline and candidate APKs when resources are added.
    private fun View.named(name: String): View = findViewById(resources.getIdentifier(name, "id", context.packageName))

    private fun output() = File(targetContext.getExternalFilesDir(null), "habit-detail-cleanup/${args.getString("captureRun") ?: "default"}/${if (before) "before" else "after"}").apply { mkdirs() }
}
