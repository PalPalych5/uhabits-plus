package org.isoron.uhabits.qa

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.slider.Slider
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialog
import org.isoron.uhabits.activities.common.dialogs.CustomColorBottomSheet
import org.isoron.uhabits.activities.common.dialogs.SatValView
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run only with TaskDraftQaRunner on an owned, offline disposable AVD. */
@RunWith(AndroidJUnit4::class)
class CustomColorPickerAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun fieldAndHexStayInSyncAndCancelRestoresOriginalDraft() {
        val args = InstrumentationRegistry.getArguments()
        val avd = args.getString("authorizedAvd").orEmpty()
        check(avd.matches(Regex("UHabit_Temp_[a-f0-9]{32}")))
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == avd)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0)

        val variant = args.getString("colorPickerVariant") ?: "light"
        val theme = when (variant) {
            "light" -> ColorPickerDialog.THEME_LIGHT
            "dark" -> ColorPickerDialog.THEME_DARK
            "amoled" -> ColorPickerDialog.THEME_AMOLED
            else -> error("Unknown colorPickerVariant: $variant")
        }
        val output = File(context.getExternalFilesDir(null), "color-picker-qa/$variant")
        check(output.mkdirs() || output.isDirectory)
        val initial = Color.RED
        var parent: ColorPickerDialog? = null
        var sheet: CustomColorBottomSheet? = null
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU"))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("pref_app_language", "ru-RU").commit()

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.HABITS)).use { scenario ->
            scenario.onActivity { activity ->
                parent = ColorPickerDialog.newInstance(initial, initial, "Тестовая привычка", theme)
                parent!!.show(activity.supportFragmentManager, "qa-color-picker")
                activity.supportFragmentManager.executePendingTransactions()
                val customRowId = context.resources.getIdentifier("custom_color_row", "id", context.packageName)
                check(customRowId != 0)
                parent!!.requireView().findViewById<View>(customRowId).performClick()
                parent!!.childFragmentManager.executePendingTransactions()
                sheet = parent!!.childFragmentManager.findFragmentByTag("customColorPicker") as CustomColorBottomSheet
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            check(device.takeScreenshot(File(output, "01-initial.png")))
            if (args.getString("baseline") == "true") return@use

            scenario.onActivity {
                val root = sheet!!.requireView()
                val field = root.findViewById<SatValView>(R.id.custom_color_sat_val)
                assertTrue("Saturation/value field must be laid out", field.width > 0 && field.height > 0)
                val down = SystemClock.uptimeMillis()
                val x = field.width * 0.5f
                val y = field.height * 0.5f
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    field.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val root = sheet!!.requireView()
                val hueControl = root.findViewById<Slider>(R.id.custom_color_hue_control)
                val hueBefore = hueControl.value
                val hueTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(hueTime, SystemClock.uptimeMillis(), action, hueControl.width * 0.6f, hueControl.height * 0.5f, 0)
                    hueControl.dispatchTouchEvent(event)
                    event.recycle()
                }
                assertNotEquals("Hue drag changes the selection", hueBefore, hueControl.value)
                val hex = root.findViewById<EditText>(R.id.custom_color_hex_input)
                hex.setText("#804040")
                assertEquals(50f, root.findViewById<Slider>(R.id.custom_color_saturation).value, 1f)
                assertEquals(50f, root.findViewById<Slider>(R.id.custom_color_value).value, 1f)
                hex.setText("#00FF00")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val root = sheet!!.requireView()
                assertEquals(120f, root.findViewById<Slider>(R.id.custom_color_hue).value, 1f)
                assertEquals(100f, root.findViewById<Slider>(R.id.custom_color_saturation).value, 1f)
                assertEquals(100f, root.findViewById<Slider>(R.id.custom_color_value).value, 1f)
                assertEquals(120f, root.findViewById<Slider>(R.id.custom_color_hue_control).value, 1f)
                val field = root.findViewById<SatValView>(R.id.custom_color_sat_val)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, field.width.toFloat(), field.height * 0.5f, 0)
                    field.dispatchTouchEvent(event)
                    event.recycle()
                }
                assertEquals("#008000", root.findViewById<EditText>(R.id.custom_color_hex_input).text.toString().uppercase())
                assertNotEquals(initial, Color.parseColor(root.findViewById<EditText>(R.id.custom_color_hex_input).text.toString()))
                assertEquals(0f, root.findViewById<Slider>(R.id.custom_color_red).value, 1f)
                assertEquals(128f, root.findViewById<Slider>(R.id.custom_color_green).value, 1f)
                assertEquals(0f, root.findViewById<Slider>(R.id.custom_color_blue).value, 1f)

                // Test edge values: top-left corner (White: S=0%, V=100%)
                val evTopLeft = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 0f, 0f, 0)
                field.dispatchTouchEvent(evTopLeft)
                evTopLeft.recycle()
                assertEquals(0f, root.findViewById<Slider>(R.id.custom_color_saturation).value, 1f)
                assertEquals(100f, root.findViewById<Slider>(R.id.custom_color_value).value, 1f)
                assertEquals("#FFFFFF", root.findViewById<EditText>(R.id.custom_color_hex_input).text.toString().uppercase())

                // Test edge values: bottom-right corner (Black: V=0%)
                val evBottomRight = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, field.width.toFloat(), field.height.toFloat(), 0)
                field.dispatchTouchEvent(evBottomRight)
                evBottomRight.recycle()
                assertEquals(0f, root.findViewById<Slider>(R.id.custom_color_value).value, 1f)
                assertEquals("#000000", root.findViewById<EditText>(R.id.custom_color_hex_input).text.toString().uppercase())

                // Restore green state
                val evRestore = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, field.width.toFloat(), field.height * 0.5f, 0)
                field.dispatchTouchEvent(evRestore)
                evRestore.recycle()

                root.findViewById<View>(R.id.custom_color_tab_hsv).performClick()
            }
            SystemClock.sleep(600)
            device.waitForIdle()
            check(device.takeScreenshot(File(output, "02-field-changed.png")))
            scenario.onActivity {
                sheet!!.requireView().findViewById<View>(R.id.custom_color_tab_rgb).performClick()
            }
            SystemClock.sleep(600)
            device.waitForIdle()
            check(device.takeScreenshot(File(output, "03-rgb.png")))
            scenario.onActivity {
                sheet!!.requireView().findViewById<View>(R.id.custom_color_tab_hsv).performClick()
                sheet!!.requireView().findViewById<EditText>(R.id.custom_color_hex_input).setText("#FFFF00")
            }
            SystemClock.sleep(600)
            device.waitForIdle()
            check(device.takeScreenshot(File(output, "04-contrast-yellow.png")))
            scenario.onActivity {
                for (id in listOf(R.id.custom_color_cancel, R.id.custom_color_done)) {
                    val action = sheet!!.requireView().findViewById<View>(id)
                    val visible = Rect()
                    assertTrue("Action must be fully visible", action.getGlobalVisibleRect(visible) && visible.height() >= action.height)
                }
                sheet!!.requireView().findViewById<View>(R.id.custom_color_cancel).performClick()
                parent!!.childFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(initial, parent!!.snapshotDraftColor()) }
            scenario.onActivity {
                parent!!.requireView().findViewById<View>(R.id.custom_color_row).performClick()
                parent!!.childFragmentManager.executePendingTransactions()
                sheet = parent!!.childFragmentManager.findFragmentByTag("customColorPicker") as CustomColorBottomSheet
                sheet!!.requireView().findViewById<EditText>(R.id.custom_color_hex_input).setText("#487FC6")
                sheet!!.requireView().findViewById<View>(R.id.custom_color_done).performClick()
                parent!!.childFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(Color.parseColor("#487FC6"), parent!!.snapshotDraftColor()) }
        }
    }
}
