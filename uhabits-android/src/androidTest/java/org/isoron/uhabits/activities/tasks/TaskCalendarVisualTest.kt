/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.activities.tasks

import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable
import java.util.regex.Pattern
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.isoron.uhabits.activities.containers.ContainersActivity

/** Run explicitly with TaskDraftQaRunner on an owned, offline temporary AVD. */
@RunWith(AndroidJUnit4::class)
class TaskCalendarVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)
    private val harness = TaskDraftLifecycleTest()
    private val phase = args.getString("phase", "light")!!.also { check(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
    private val output get() = File(context.getExternalFilesDir(null), "pr11/$phase").apply { check(mkdirs() || isDirectory) }

    private val zone = ZoneId.of("Asia/Novosibirsk")

    @Test
    fun composeControlsSmoke() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        seedHabits()
        val today = getToday()
        seedTasks(today, java.time.LocalDate.of(today.year, today.month, today.day))
        CalendarQaActivityController().use { controller ->
            openCalendar(controller)
            fun click(selector: androidx.test.uiautomator.BySelector) {
                val target = device.wait(Until.findObject(selector), 5000)
                assertNotNull("Missing control: $selector", target)
                target.click()
                idle()
            }
            fun current(action: (TaskCalendarViewController) -> Unit) {
                controller.onActivity { activity -> action(requireNotNull(field<TaskCalendarViewController?>(fragment(activity), "calendarViewController"))) }
            }
            capture("smoke-month")
            click(By.desc(Pattern.compile("Next period|Следующий период")))
            current { assertNotEquals(today, it.selectedDay) }
            click(By.text(Pattern.compile("Today|Сегодня")))
            current { assertEquals(today, it.selectedDay) }
            click(By.text(Pattern.compile("Week|Неделя")))
            current { assertEquals(CalendarScale.WEEK, it.scale) }
            capture("smoke-week")
            click(By.text(Pattern.compile("Day|День")))
            current { assertEquals(CalendarScale.DAY, it.scale) }
            click(By.text(Pattern.compile("Month|Месяц")))
            current { assertEquals(CalendarScale.MONTH, it.scale) }
            click(By.desc(Pattern.compile("Plan, switch to history|План, переключить на историю")))
            current { assertEquals(CalendarMode.HISTORY, it.mode) }
            click(By.desc(Pattern.compile("History, switch to plan|История, переключить на план")))
            current { assertEquals(CalendarMode.PLANNING, it.mode) }
            var initial = false
            current { initial = it.showHabits }
            controller.onActivity { activity -> field<View>(fragment(activity), "btnTasksOverflow").performClick() }
            idle()
            click(By.text(Pattern.compile("Show habits|Показывать привычки")))
            current { assertEquals(!initial, it.showHabits) }
            controller.onActivity { activity -> field<View>(fragment(activity), "btnTasksOverflow").performClick() }
            idle()
            click(By.text(Pattern.compile("Show habits|Показывать привычки")))
            current { assertEquals(initial, it.showHabits) }
        }
    }

    @Test
    fun containersComposeSmoke() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        component.preferences.isHabitSpheresEnabled = true

        val facade = component.organizationFacade
        val health = facade.createContainer("Здоровье", color = PaletteColor(2), icon = "vector:heart").getOrThrow()
        val sports = facade.createContainer("Спорт", parentId = health.id, color = PaletteColor(3)).getOrThrow()
        val growth = facade.createContainer("Развитие", color = PaletteColor(5), icon = "vector:book").getOrThrow()

        val factory = component.sqlModelFactory
        val list = component.habitList

        fun addHabit(title: String, colorIdx: Int, containerId: ContainerId?) {
            val h = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = title
                frequency = Frequency.DAILY
                color = PaletteColor(colorIdx)
            }
            list.add(h)
            if (containerId != null) {
                facade.placeHabit(h.uuid!!, containerId)
            }
        }

        addHabit("Утренняя пробежка", 2, sports.id)
        addHabit("Пить воду 2л", 2, health.id)
        addHabit("Чтение книги", 5, growth.id)
        addHabit("Неприкаянная привычка", 1, null)

        val ruLocale = java.util.Locale("ru", "RU")
        java.util.Locale.setDefault(ruLocale)
        val config = android.content.res.Configuration(instrumentation.targetContext.resources.configuration)
        config.setLocale(ruLocale)
        instrumentation.targetContext.resources.updateConfiguration(config, instrumentation.targetContext.resources.displayMetrics)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ru-RU"))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(instrumentation.targetContext).edit().putString("pref_app_language", "ru-RU").commit()

        val intent = ContainersActivity.intent(instrumentation.targetContext)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ContainersActivity
        try {
            idle()
            capture("containers-browse-root")

            val item = device.wait(Until.findObject(By.text("Здоровье")), 5000)
            assertNotNull("Здоровье card must exist", item)
            item.click()
            idle()
            capture("containers-detail-subsections")
        } finally {
            activity.finish()
        }
    }

    @Test
    fun adaptiveColorPickerInSphereEditor() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ru-RU"))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putString("pref_app_language", "ru-RU").commit()
        val facade = component.organizationFacade
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        fun click(label: String) { requireNotNull(device.wait(Until.findObject(By.text(label)), 7000)) { "Missing $label" }.click(); idle() }
        try {
            for (variant in listOf("light", "dark", "amoled")) {
                component.preferences.theme = if (variant == "light") 0 else 1
                component.preferences.isPureBlackEnabled = variant == "amoled"
                val container = facade.createContainer("Здоровье $variant", color = PaletteColor(0xff123456.toInt())).getOrThrow()
                val intent = ContainersActivity.intent(context, container.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = instrumentation.startActivitySync(intent) as ContainersActivity
                try {
                    instrumentation.runOnMainSync {
                        val palette = org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver.resolve(activity, component.preferences)
                        val colors = org.isoron.uhabits.compose.UHabitComposeColors.fromPalette(palette)
                        activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply {
                            setContent {
                                org.isoron.uhabits.activities.containers.ContainersComposeScreen(
                                    facade = facade, habitList = component.habitList, colors = colors,
                                    initialContainerId = container.id, onBackFinish = { activity.finish() },
                                    onOpenStatistics = {}, onAddHabitInContainer = {}, onOpenHabitDetail = {},
                                    previewComposeColorPicker = true,
                                )
                            }
                        })
                    }
                    idle()
                    requireNotNull(device.wait(Until.findObject(By.desc("Изменить")), 7000)).click(); idle()
                    val name = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
                    name.click(); name.text = "Черновик $variant"; idle(); device.pressBack(); idle()
                    click("Выбрать цвет")
                    if (variant == "light") capture("adaptive-palette-light")
                    click("Настроить цвет"); SystemClock.sleep(600); idle()
                    capture("adaptive-hex-$variant")
                    val field = requireNotNull(device.wait(Until.findObject(By.desc(activity.getString(R.string.custom_color_field_description))), 7000)).visibleBounds
                    device.swipe(field.left + field.width() / 4, field.top + field.height() / 4, field.right - field.width() / 4, field.bottom - field.height() / 4, 15); idle()
                    val hue = requireNotNull(device.wait(Until.findObject(By.desc(activity.getString(R.string.custom_color_hue_description))), 7000)).visibleBounds
                    device.swipe(hue.left + hue.width() / 4, hue.centerY(), hue.right - hue.width() / 4, hue.centerY(), 15); idle()
                    if (variant == "light") {
                        click("HSV"); SystemClock.sleep(350); capture("adaptive-hsv-light")
                        for (label in listOf("H", "S", "V")) assertNotNull(device.wait(Until.findObject(By.desc("Канал $label")), 7000))
                        click("RGB"); SystemClock.sleep(350); capture("adaptive-rgb-light")
                        for (label in listOf("R", "G", "B")) assertNotNull(device.wait(Until.findObject(By.desc("Канал $label")), 7000))
                    }
                    device.pressBack(); idle(); device.pressBack(); idle()
                    assertNotNull("Color cancel must return to sphere editor", device.wait(Until.findObject(By.text("Сохранить")), 7000))
                    assertNotNull("Sphere draft name must survive picker", device.wait(Until.findObject(By.text("Черновик $variant")), 7000))
                    assertEquals("#123456", facade.containerQueries?.find(container.id)?.color?.toCsvColor())
                    if (variant == "light") {
                        click("Выбрать цвет"); click("Настроить цвет")
                        val hex = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
                        hex.click(); hex.text = "#A1B2C3"; idle(); device.pressBack(); idle()
                        requireNotNull(device.wait(Until.findObject(By.desc("Применить цвет")), 7000)).click(); idle()
                        assertEquals("Picker apply is only a form draft", "#123456", facade.containerQueries?.find(container.id)?.color?.toCsvColor())
                        click("Сохранить")
                        assertEquals("#A1B2C3", facade.containerQueries?.find(container.id)?.color?.toCsvColor())
                    }
                } finally { activity.finish(); idle() }
            }
        } finally { component.preferences.theme = oldTheme; component.preferences.isPureBlackEnabled = oldBlack }
    }

    @Test
    fun composeColorDraftSurvivesRotation() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        val app = context.applicationContext as android.app.Application
        val active = java.util.concurrent.atomic.AtomicReference<ContainersActivity>()
        val callback = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityPostCreated(activity: android.app.Activity, state: android.os.Bundle?) {
                if (activity !is ContainersActivity) return
                val palette = org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver.resolve(activity, component.preferences)
                val colors = org.isoron.uhabits.compose.UHabitComposeColors.fromPalette(palette)
                activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply {
                    id = 0x00C0FFEE
                    setContent {
                        org.isoron.uhabits.compose.UHabitComposeTheme(colors) {
                            org.isoron.uhabits.activities.common.dialogs.ComposeColorPickerDesignC(PaletteColor(0xff123456.toInt()), "Проверка", {}, {})
                        }
                    }
                })
                active.set(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(callback) }
        var activity: ContainersActivity? = null
        try {
            activity = instrumentation.startActivitySync(ContainersActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ContainersActivity
            idle()
            requireNotNull(device.wait(Until.findObject(By.text("Настроить цвет")), 7000)).click(); idle()
            val hex = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
            hex.click(); hex.text = "#A1B2C3"; idle(); device.pressBack(); idle()
            requireNotNull(device.wait(Until.findObject(By.text("RGB")), 7000)).click(); idle()
            val before = activity
            instrumentation.runOnMainSync { before!!.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val deadline = SystemClock.uptimeMillis() + 15000
            while (active.get() === before && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertNotSame("Rotation must recreate the Activity", before, active.get())
            idle()
            assertNotNull("Precision destination must survive rotation", device.wait(Until.findObject(By.desc("Назад к палитре")), 7000))
            capture("rotation-landscape")
            repeat(4) {
                if (!device.hasObject(By.desc("Канал R"))) {
                    val contentEdge = device.displayWidth / 2 - (device.displayHeight * 0.85f / 2).toInt() + 10
                    device.swipe(contentEdge, device.displayHeight * 4 / 5, contentEdge, device.displayHeight / 3, 15)
                    idle()
                }
            }
            assertNotNull("RGB mode must survive rotation", device.wait(Until.findObject(By.desc("Канал R")), 7000))
            requireNotNull(device.wait(Until.findObject(By.text("HEX")), 7000)).click(); idle()
            val restored = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
            assertEquals("#A1B2C3", restored.text.uppercase())
        } finally {
            instrumentation.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callback); active.get()?.finish() }
        }
    }

    @Test
    fun composeColorDraftContract() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        component.preferences.theme = 1
        component.preferences.isPureBlackEnabled = false
        val activity = instrumentation.startActivitySync(ContainersActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ContainersActivity
        var selected = PaletteColor(0xff123456.toInt())
        var applyCount = 0
        var cancelCount = 0
        try {
            instrumentation.runOnMainSync {
                val palette = org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver.resolve(activity, component.preferences)
                val colors = org.isoron.uhabits.compose.UHabitComposeColors.fromPalette(palette)
                activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply {
                    setContent {
                        var visible by remember { mutableStateOf(true) }
                        var selectedState by remember { mutableStateOf(selected) }
                        org.isoron.uhabits.compose.UHabitComposeTheme(colors) {
                            if (visible) {
                                org.isoron.uhabits.activities.common.dialogs.ComposeColorPickerDesignC(selectedState, "Проверка", {
                                    selected = it; selectedState = it; applyCount++; visible = false
                                }, { cancelCount++; visible = false })
                            } else BasicText("Открыть", modifier = Modifier.clickable { visible = true })
                        }
                    }
                })
            }
            fun click(label: String) { requireNotNull(device.wait(Until.findObject(By.text(label)), 7000)).click(); idle() }
            fun value(): String {
                repeat(20) {
                    try {
                        device.findObject(By.clazz("android.widget.EditText"))?.text?.let { return it.uppercase() }
                    } catch (_: androidx.test.uiautomator.StaleObjectException) { }
                    SystemClock.sleep(100)
                }
                error("Color input did not settle")
            }
            idle()
            click("Настроить цвет")
            val original = value()
            click("HSV")
            val channelS = requireNotNull(device.wait(Until.findObject(By.desc("Канал S")), 7000)).visibleBounds
            device.swipe(channelS.left + channelS.width() / 4, channelS.centerY(), channelS.right - channelS.width() / 4, channelS.centerY(), 15); idle()
            click("HEX")
            val afterHsv = value()
            assertNotEquals("HSV slider updates HEX", original, afterHsv)
            click("RGB")
            val channelR = requireNotNull(device.wait(Until.findObject(By.desc("Канал R")), 7000)).visibleBounds
            device.swipe(channelR.left + channelR.width() / 4, channelR.centerY(), channelR.right - channelR.width() / 4, channelR.centerY(), 15); idle()
            click("HEX")
            assertNotEquals("RGB slider updates HEX", afterHsv, value())
            val hex = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
            hex.click(); hex.text = "#A1B2C3"; idle(); device.pressBack(); idle()
            device.pressBack(); idle()
            assertNotNull(device.wait(Until.findObject(By.textContains("#A1B2C3")), 7000))
            assertEquals("Picker draft must not apply on Back", 0, applyCount)
            requireNotNull(device.wait(Until.findObject(By.desc("Применить цвет")), 7000)).click(); idle()
            assertEquals("#A1B2C3", selected.toCsvColor())
            assertEquals(1, applyCount)
            click("Открыть")
            assertNotNull("Applied custom color must reopen exactly", device.wait(Until.findObject(By.textContains("#A1B2C3")), 7000))
            click("Настроить цвет")
            val nextHex = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
            nextHex.click(); nextHex.text = "#FF0000"; idle(); device.pressBack(); idle()
            device.pressBack(); idle(); device.pressBack(); idle()
            assertEquals("Cancel must not apply a second color", 1, applyCount)
            assertEquals(1, cancelCount)
            assertEquals("#A1B2C3", selected.toCsvColor())
            click("Открыть"); click("Настроить цвет")
            val invalidHex = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
            invalidHex.click(); invalidHex.text = "#11223344"; idle(); device.pressBack(); idle()
            requireNotNull(device.wait(Until.findObject(By.desc("Применить цвет")), 7000)).click(); idle()
            assertEquals("An overlong HEX must not invoke Apply", 1, applyCount)
            val correctedHex = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
            correctedHex.click(); correctedHex.text = "#EF9A9A"; idle(); device.pressBack(); idle()
            requireNotNull(device.wait(Until.findObject(By.desc("Применить цвет")), 7000)).click(); idle()
            assertEquals("Themed palette collision must preserve exact RGB", "#EF9A9A", selected.toCsvColor())
            click("Открыть")
            assertNotNull("Themed collision must reopen exactly", device.wait(Until.findObject(By.textContains("#EF9A9A")), 7000))
        } finally { activity.finish(); component.preferences.theme = oldTheme; component.preferences.isPureBlackEnabled = oldBlack }
    }

    @Test
    fun composeColorCandidateThemes() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        val originalTheme = component.preferences.theme
        val originalBlack = component.preferences.isPureBlackEnabled
        try {
            for (variant in listOf("light", "dark", "amoled")) {
                component.preferences.theme = if (variant == "light") 0 else 1
                component.preferences.isPureBlackEnabled = variant == "amoled"
                val intent = ContainersActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = instrumentation.startActivitySync(intent) as ContainersActivity
                var applied: PaletteColor? = null
                var dismissed = false
                try {
                    instrumentation.runOnMainSync {
                        val palette = org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver.resolve(activity, component.preferences)
                        val composeColors = org.isoron.uhabits.compose.UHabitComposeColors.fromPalette(palette)
                        activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply {
                            setContent {
                                org.isoron.uhabits.compose.UHabitComposeTheme(composeColors) {
                                    org.isoron.uhabits.activities.common.dialogs.ComposeColorPickerDesignC(PaletteColor(0xff123456.toInt()), "Здоровье", { applied = it }, { dismissed = true })
                                }
                            }
                        })
                    }
                    idle()
                    capture("compose-palette-$variant")
                    requireNotNull(device.wait(Until.findObject(By.text("Настроить цвет")), 7000)).click()
                    idle(); SystemClock.sleep(2000)
                    capture("compose-custom-$variant")
                    val field = requireNotNull(device.wait(Until.findObject(By.desc(activity.getString(R.string.custom_color_field_description))), 7000))
                    val bounds = field.visibleBounds
                    device.swipe(bounds.left + bounds.width() / 4, bounds.top + bounds.height() / 4, bounds.right - bounds.width() / 4, bounds.bottom - bounds.height() / 4, 15)
                    idle()
                    val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
                    assertNotEquals("SV drag must update the draft", "#123456", input.text.uppercase())
                    val hue = requireNotNull(device.wait(Until.findObject(By.desc(activity.getString(R.string.custom_color_hue_description))), 7000))
                    val hueBounds = hue.visibleBounds
                    val before = input.text
                    device.swipe(hueBounds.left + hueBounds.width() / 4, hueBounds.centerY(), hueBounds.right - hueBounds.width() / 4, hueBounds.centerY(), 15)
                    idle()
                    assertNotEquals("Hue drag must update the draft", before, input.text)
                    if (variant == "light") {
                        requireNotNull(device.wait(Until.findObject(By.text("HSV")), 7000)).click(); idle()
                        capture("compose-hsv-light")
                        for (label in listOf("H", "S", "V")) {
                            assertNotNull("Missing HSV channel $label", device.wait(Until.findObject(By.desc("Канал $label")), 7000))
                            assertNotNull("Missing HSV value $label", device.wait(Until.findObject(By.desc("Значение $label")), 7000))
                        }
                        val saturation = requireNotNull(device.findObject(By.desc("Канал S"))).visibleBounds
                        device.swipe(saturation.left + saturation.width() / 3, saturation.centerY(), saturation.right - saturation.width() / 3, saturation.centerY(), 15); idle()
                        requireNotNull(device.wait(Until.findObject(By.text("RGB")), 7000)).click(); idle()
                        capture("compose-rgb-light")
                        for (label in listOf("R", "G", "B")) {
                            assertNotNull("Missing RGB channel $label", device.wait(Until.findObject(By.desc("Канал $label")), 7000))
                            assertNotNull("Missing RGB value $label", device.wait(Until.findObject(By.desc("Значение $label")), 7000))
                        }
                        val red = requireNotNull(device.findObject(By.desc("Канал R"))).visibleBounds
                        device.swipe(red.left + red.width() / 3, red.centerY(), red.right - red.width() / 3, red.centerY(), 15); idle()
                        requireNotNull(device.wait(Until.findObject(By.text("HEX")), 7000)).click(); idle()
                        val hex = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 7000))
                        hex.click(); hex.text = "#A1B2C3"; idle(); device.pressBack(); idle()
                        device.pressBack(); idle()
                        assertNotNull("Back retains the draft", device.wait(Until.findObject(By.textContains("#A1B2C3")), 7000))
                        requireNotNull(device.wait(Until.findObject(By.desc("Применить цвет")), 7000)).click(); idle()
                        assertEquals("#A1B2C3", applied?.toCsvColor())
                        assertFalse("Apply is distinct from cancel", dismissed)
                    }
                } finally { activity.finish(); idle() }
            }
        } finally { component.preferences.theme = originalTheme; component.preferences.isPureBlackEnabled = originalBlack }
    }

    @Test
    fun containerOriginalColorPickerThemes() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ru-RU"))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putString("pref_app_language", "ru-RU").commit()
        val container = component.organizationFacade.createContainer("Здоровье", color = PaletteColor(0xff123456.toInt())).getOrThrow()
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        try {
            for (variant in listOf("light", "dark", "amoled")) {
                component.preferences.theme = if (variant == "light") 0 else 1
                component.preferences.isPureBlackEnabled = variant == "amoled"
                val intent = ContainersActivity.intent(context, container.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = instrumentation.startActivitySync(intent) as ContainersActivity
                try {
                    idle()
                    requireNotNull(device.wait(Until.findObject(By.desc("Изменить")), 7000)).click()
                    idle()
                    requireNotNull(device.wait(Until.findObject(By.text("Выбрать цвет")), 7000)).click()
                    idle()
                    val picker = activity.supportFragmentManager.findFragmentByTag("compose_container_color_picker") as? org.isoron.uhabits.activities.common.dialogs.ColorPickerDialog
                    assertNotNull("Original fullscreen picker must be shown", picker)
                    requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "custom_color_row")), 7000)).click()
                    idle()
                    assertNotNull(picker?.childFragmentManager?.findFragmentByTag("customColorPicker"))
                    SystemClock.sleep(2000)
                    capture("original-custom-$variant")
                    device.pressBack(); idle(); device.pressBack(); idle()
                    assertEquals("#123456", component.organizationFacade.containerQueries?.find(container.id)?.color?.toCsvColor())
                } finally { activity.finish(); idle() }
            }
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    @Test
    fun composePickerRoundTrip() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ru-RU"))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putString("pref_app_language", "ru-RU").commit()
        val facade = component.organizationFacade
        val original = facade.createContainer("Проверка пикеров", color = PaletteColor(0xff123456.toInt()), icon = "vector:book").getOrThrow()
        val intent = ContainersActivity.intent(context, original.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ContainersActivity
        fun text(label: String) = requireNotNull(device.wait(Until.findObject(By.text(label)), 7000)) { "Missing text: $label" }
        fun click(label: String) { text(label).click(); idle() }
        fun clickId(id: String) { requireNotNull(device.wait(Until.findObject(By.res(context.packageName, id)), 7000)).click(); idle() }
        fun edit() { requireNotNull(device.wait(Until.findObject(By.desc("Изменить")), 7000)).click(); idle() }
        fun assertStoredColor(expected: Int) {
            val saved = requireNotNull(facade.containerQueries?.find(original.id)?.color)
            assertEquals(expected, android.graphics.Color.parseColor(saved.toCsvColor()))
        }
        try {
            idle(); edit(); click("Выбрать цвет"); capture("picker-palette")
            clickId("custom_color_row"); capture("picker-custom")
            val hex = requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "custom_color_hex_input")), 5000))
            assertEquals("#123456", hex.text.uppercase())
            hex.click(); hex.text = "#A1B2C3"
            device.pressBack(); idle()
            clickId("custom_color_done"); clickId("color_picker_apply"); click("Сохранить"); assertStoredColor(0xffa1b2c3.toInt())
            edit(); click("Выбрать цвет"); clickId("custom_color_row")
            val secondHex = requireNotNull(device.wait(Until.findObject(By.res(context.packageName, "custom_color_hex_input")), 5000))
            assertEquals("#A1B2C3", secondHex.text.uppercase())
            secondHex.click(); secondHex.text = "#FF0000"
            device.pressBack(); idle(); clickId("custom_color_cancel"); clickId("color_picker_cancel"); click("Сохранить"); assertStoredColor(0xffa1b2c3.toInt())
            edit(); click("Выбран"); capture("picker-icons")
            click("Tabler")
            val search = device.findObjects(By.clazz("android.widget.EditText")).last()
            search.click(); search.text = "address-book"; idle(); device.pressBack(); idle(); capture("picker-search")
            val entry = requireNotNull(device.wait(Until.findObject(By.desc("Address Book")), 5000))
            entry.click(); idle(); click("Сохранить")
            assertTrue(facade.containerQueries?.find(original.id)?.icon.orEmpty().contains("address-book"))
            edit(); click("Выбран"); click("Эмодзи"); click("🎓"); click("Сохранить")
            assertTrue(facade.containerQueries?.find(original.id)?.icon.orEmpty().contains("🎓"))
            edit(); click("Выбран"); click("Без значка"); click("Сохранить")
            assertTrue(org.isoron.uhabits.core.containers.IconRef.fromString(facade.containerQueries?.find(original.id)?.icon) is org.isoron.uhabits.core.containers.IconRef.None)
        } finally { activity.finish() }
    }

    @Test
    fun habitsSphereHeadingsSmoke() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()
        component.preferences.isFirstRun = false
        component.preferences.isHabitSpheresEnabled = true
        component.preferences.isShowSphereNamesEnabled = true
        component.preferences.defaultPrimaryOrder = HabitList.Order.BY_SPHERE

        val facade = component.organizationFacade
        val health = facade.createContainer("Здоровье").getOrThrow()
        val growth = facade.createContainer("Развитие").getOrThrow()
        val work = facade.createContainer("Работа").getOrThrow()

        val factory = component.sqlModelFactory
        val list = component.habitList

        fun addHabit(title: String, colorIdx: Int, containerId: ContainerId) {
            val h = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = title
                frequency = Frequency.DAILY
                color = PaletteColor(colorIdx)
            }
            list.add(h)
            facade.placeHabit(h.uuid!!, containerId)
        }

        addHabit("Утренняя пробежка", 1, health.id)
        addHabit("Пить воду 2л", 2, health.id)
        addHabit("Чтение книги", 3, growth.id)
        addHabit("Медитация 15м", 4, growth.id)
        addHabit("Планирование дня", 5, work.id)
        addHabit("Ревью кода", 6, work.id)

        CalendarQaActivityController().use { controller ->
            controller.onActivity { activity ->
                activity.navigate(MainDestination.HABITS)
            }
            idle()
            capture("smoke-habits-spheres")
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 15)
            idle()
            capture("smoke-habits-spheres-scrolled")
        }
    }

    @Test
    fun calendarJourneys() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()

        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled

        try {
            component.preferences.theme = if (phase.startsWith("light") || phase.startsWith("narrow")) ThemeSwitcher.THEME_LIGHT else ThemeSwitcher.THEME_DARK
            component.preferences.isPureBlackEnabled = phase.startsWith("amoled")
            component.preferences.isFirstRun = false

            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            idle()

            val today = getToday()
            val jToday = java.time.LocalDate.of(today.year, today.month, today.day)

            // 1. Seed Habits
            seedHabits()

            // 2. Seed Tasks with specific calendar dues
            val tasks = seedTasks(today, jToday)

            CalendarQaActivityController().use { controller ->
                // Navigate to Tasks and enable Calendar mode
                openCalendar(controller)
                idle()

                // Check Calendar panel is visible
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<View>(frag, "calendarPanel")
                    assertEquals(View.VISIBLE, panel.visibility)
                    assertEquals(CalendarScale.MONTH, field<CalendarScale>(frag, "calendarScale"))
                }

                // --- 1. MONTH VIEW ---
                capture("01-month-populated")

                // Click tomorrow in month view to select it and update agenda below
                val tomorrow = today.plus(1)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val calController = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    assertNotNull("Controller must be attached", calController)
                    calController?.let {
                        it.selectedDay = tomorrow
                        it.render()
                    }
                }
                idle()
                capture("01b-month-selected-tomorrow")

                // --- 2. WEEK VIEW ---
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarScale", CalendarScale.WEEK)
                    val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    cal?.scale = CalendarScale.WEEK
                    cal?.render()
                }
                idle()
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    assertEquals(CalendarScale.WEEK, field<CalendarScale>(frag, "calendarScale"))
                }
                capture("02-week-view")

                // Scroll week timeline down a bit
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, 400)
                }
                idle()
                capture("02b-week-scrolled")

                // --- 3. DAY VIEW ---
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    // Reset selected day back to today for day view
                    set(frag, "selectedDay", today)
                    set(frag, "calendarScale", CalendarScale.DAY)
                    val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    cal?.selectedDay = today
                    cal?.scale = CalendarScale.DAY
                    cal?.render()
                }
                idle()
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    assertEquals(CalendarScale.DAY, field<CalendarScale>(frag, "calendarScale"))
                    assertEquals(today, field<LocalDate>(frag, "selectedDay"))
                }
                capture("03-day-view")

                // Scroll day timeline to timed events (~10:00 - 15:00)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                    findScrollView(panel)?.smoothScrollTo(0, 600)
                }
                idle()
                capture("03b-day-scrolled")

                // --- 4. EMPTY DAY ---
                val farFuture = today.plus(35)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "selectedDay", farFuture)
                    val calController = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    calController?.selectedDay = farFuture
                    calController?.render()
                }
                idle()
                capture("04-day-empty")

                // Return to Today via Today button logic
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "selectedDay", today)
                    val calController = field<TaskCalendarViewController?>(frag, "calendarViewController")
                    calController?.selectedDay = today
                    calController?.render()
                }
                idle()
                capture("04b-day-today-restored")

                // --- 5. SCALE SWITCH PRESERVES SELECTED DATE ---
                val targetDay = today.plus(2)
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "selectedDay", targetDay)
                    val c = field<TaskCalendarViewController?>(frag, "calendarViewController")!!
                    c.selectedDay = targetDay
                    // Switch Day -> Week -> Month -> Day
                    c.scale = CalendarScale.WEEK
                    assertEquals(targetDay, c.selectedDay)
                    c.scale = CalendarScale.MONTH
                    assertEquals(targetDay, c.selectedDay)
                    c.scale = CalendarScale.DAY
                    assertEquals(targetDay, c.selectedDay)
                    c.render()
                }
                idle()

                // --- 6. SWITCHING BETWEEN MODES (List, Calendar, Matrix) ---
                // Switch to List
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarMode", false)
                    set(frag, "matrixMode", false)
                    frag.loadTasks()
                }
                idle(); waitLoad(controller)
                capture("05-mode-list")

                // Switch to Matrix
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarMode", false)
                    set(frag, "matrixMode", true)
                    frag.loadTasks()
                }
                idle(); waitLoad(controller)
                capture("06-mode-matrix")

                // Switch back to Calendar in Week mode
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    set(frag, "calendarMode", true)
                    set(frag, "matrixMode", false)
                    set(frag, "calendarScale", CalendarScale.WEEK)
                    set(frag, "selectedDay", today)
                    frag.loadTasks()
                }
                idle(); waitLoad(controller)
                capture("07-mode-calendar-restored")

                // --- 7. ACTIVITY RECREATION ---
                controller.recreate()
                idle()
                controller.onActivity { activity ->
                    val frag = fragment(activity)
                    assertTrue("Calendar mode must survive recreation", field<Boolean>(frag, "calendarMode"))
                    assertEquals("Week scale must survive recreation", CalendarScale.WEEK, field<CalendarScale>(frag, "calendarScale"))
                    assertEquals("Selected day must survive recreation", today, field<LocalDate>(frag, "selectedDay"))
                }
                capture("08-recreated")
            }

            evidence("phase=$phase today=$today tasks=${tasks.size} calendarScale=MONTH,WEEK,DAY verified")
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    @Test
    fun targetedVisualVerification() {
        TaskListVisualTest::class.java.getDeclaredMethod("bootstrapDisposableSession").apply { isAccessible = true }.invoke(TaskListVisualTest())
        harness.guard()

        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled

        try {
            component.preferences.theme = if (phase.contains("dark")) ThemeSwitcher.THEME_DARK else ThemeSwitcher.THEME_LIGHT
            component.preferences.isPureBlackEnabled = phase.contains("amoled")
            component.preferences.isFirstRun = false

            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            idle()

            val density = context.resources.displayMetrics.density
            fun dp(px: Float): Int = (px * density).toInt()

            val today = getToday()
            val jToday = java.time.LocalDate.of(today.year, today.month, today.day)
            val weekStart = today.startOfWeek(org.isoron.platform.time.DayOfWeek.MONDAY)

            seedHabits()
            val tasks = seedTasks(today, jToday)

            CalendarQaActivityController().use { controller ->
                openCalendar(controller)
                idle()

                if (phase.contains("narrow")) {
                    // --- Scenario 6: Narrow Screen 1.3x Week View ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "calendarScale", CalendarScale.WEEK)
                        set(frag, "selectedDay", today)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.scale = CalendarScale.WEEK
                        cal?.selectedDay = today
                        cal?.render()
                    }
                    idle(); waitLoad(controller)
                    capture("06-narrow-font13-week")
                } else {
                    // --- Scenario 1: Beginning of Week View (Monday selected) ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "calendarScale", CalendarScale.WEEK)
                        set(frag, "selectedDay", weekStart)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.scale = CalendarScale.WEEK
                        cal?.selectedDay = weekStart
                        cal?.render()
                    }
                    idle(); waitLoad(controller)
                    capture("01-week-start")

                    // --- Scenario 2: Week Scrolled Horizontally (Friday-Sunday visible) ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                        val hScrolls = mutableListOf<android.widget.HorizontalScrollView>()
                        fun findHScrolls(v: View) {
                            if (v is android.widget.HorizontalScrollView) hScrolls.add(v)
                            if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) findHScrolls(v.getChildAt(i))
                            }
                        }
                        findHScrolls(panel)
                        for (hs in hScrolls) {
                            hs.smoothScrollTo(dp(300f), 0)
                        }
                    }
                    idle()
                    capture("02-week-scrolled-horiz")

                    // --- Scenario 3: Week Scrolled Vertically (Header & All-Day Stay Pinned) ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        val panel = field<android.widget.LinearLayout>(frag, "calendarPanel")
                        val hScrolls = mutableListOf<android.widget.HorizontalScrollView>()
                        fun findHScrolls(v: View) {
                            if (v is android.widget.HorizontalScrollView) hScrolls.add(v)
                            if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) findHScrolls(v.getChildAt(i))
                            }
                        }
                        findHScrolls(panel)
                        for (hs in hScrolls) {
                            hs.scrollTo(0, 0)
                        }
                        findScrollView(panel)?.smoothScrollTo(0, dp(400f))
                    }
                    idle()
                    capture("03-week-scrolled-vert")

                    // --- Scenario 4: Day View with Pinned All-Day Section & Timeline Scrolled to Current Hour ---
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "calendarScale", CalendarScale.DAY)
                        set(frag, "selectedDay", today)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.scale = CalendarScale.DAY
                        cal?.selectedDay = today
                        cal?.render()
                    }
                    idle(); waitLoad(controller)
                    capture("04-day-allday-visible")

                    // --- Scenario 5: Calendar on a Different Date (Title shows "Календарь", not "Сегодня") ---
                    val tomorrow = today.plus(1)
                    controller.onActivity { activity ->
                        val frag = fragment(activity)
                        set(frag, "selectedDay", tomorrow)
                        val cal = field<TaskCalendarViewController?>(frag, "calendarViewController")
                        cal?.selectedDay = tomorrow
                        cal?.render()
                        val updateSmartView = frag.javaClass.getDeclaredMethod("updateSmartViewLabel")
                        updateSmartView.isAccessible = true
                        updateSmartView.invoke(frag)
                    }
                    idle(); waitLoad(controller)
                    capture("05-calendar-different-date")
                }
            }

            evidence("targeted verification passed: phase=$phase")
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    private fun seedHabits() {
        val list = component.habitList
        val factory = component.sqlModelFactory

        // 1. Daily habit (Run in the morning)
        val dailyName = "PR11 Утренняя пробежка"
        if (list.none { it.name == dailyName }) {
            val habit = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = dailyName
                frequency = Frequency.DAILY
                color = PaletteColor(1) // Blue
            }
            list.add(habit)
        }

        // 2. Weekday habit with reminder (English practice: Mon, Wed, Fri at 19:30)
        val englishName = "PR11 Английский язык"
        if (list.none { it.name == englishName }) {
            val weekdays = BooleanArray(7) { false }
            weekdays[2] = true // Monday
            weekdays[4] = true // Wednesday
            weekdays[6] = true // Friday
            val habit = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = englishName
                frequency = Frequency(3, 7)
                color = PaletteColor(4) // Green
                reminder = Reminder(19, 30, WeekdayList(weekdays))
            }
            list.add(habit)
        }

        // 3. Weekly habit without reminder days (Reading: 3 times a week, should be omitted from calendar)
        val readingName = "PR11 Чтение книги"
        if (list.none { it.name == readingName }) {
            val habit = factory.buildHabit().apply {
                uuid = java.util.UUID.randomUUID().toString()
                name = readingName
                frequency = Frequency(3, 7)
                color = PaletteColor(6) // Orange
            }
            list.add(habit)
        }
    }

    private fun seedTasks(today: LocalDate, jToday: java.time.LocalDate): List<UserTask> {
        val repo = component.userTaskRepository
        val service = component.userTaskService

        fun timed(jDate: java.time.LocalDate, hour: Int, minute: Int): TaskDue.Timed {
            val millis = jDate.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            return TaskDue.Timed.local(millis, zone.id)
        }

        fun dayDue(jDate: java.time.LocalDate): TaskDue.Day {
            return TaskDue.Day(TaskDueDate.parse(jDate.toString()))
        }

        fun seed(title: String, due: TaskDue, important: Boolean? = null, urgent: Boolean? = null): UserTask {
            val existing = repo.getRoots().find { it.title == title }
            return if (existing == null) {
                service.createTask(title, due = due, important = important, urgent = urgent)
            } else {
                service.updateTask(existing.id, due = due, important = important, urgent = urgent)
            }
        }

        return listOf(
            // Today All-Day
            seed("PR11 Подготовить релиз календаря", dayDue(jToday), important = true, urgent = true),
            // Today Timed 10:30
            seed("PR11 Синхронизация с командой", timed(jToday, 10, 30), important = true, urgent = false),
            // Today Timed 15:00
            seed("PR11 Демо планировщика", timed(jToday, 15, 0), important = false, urgent = true),
            // Tomorrow All-Day
            seed("PR11 Ретроспектива PR11", dayDue(jToday.plusDays(1))),
            // Tomorrow Timed 14:00
            seed("PR11 Планирование спринта", timed(jToday.plusDays(1), 14, 0)),
            // Day After Tomorrow Timed 11:30
            seed("PR11 Архитектурное ревью", timed(jToday.plusDays(2), 11, 30)),
            // Overdue task
            seed("PR11 Долг по тестам", dayDue(jToday.minusDays(3)), important = true)
        )
    }

    private fun openCalendar(controller: CalendarQaActivityController) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.TASKS)
            activity.supportFragmentManager.executePendingTransactions()
            val frag = fragment(activity)
            set(frag, "calendarMode", true)
            set(frag, "matrixMode", false)
            set(frag, "calendarScale", CalendarScale.MONTH)
            set(frag, "selectedDay", getToday())
        }
        idle(); waitLoad(controller)
        controller.onActivity { fragment(it).loadTasks() }
        idle(); waitLoad(controller)
    }

    private fun findScrollView(root: View): ScrollView? {
        if (root is ScrollView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findScrollView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun waitLoad(controller: CalendarQaActivityController) {
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) {
            var busy = false
            controller.onActivity { busy = field<kotlinx.coroutines.Job?>(fragment(it), "loadJob")?.isActive == true }
            if (!busy) break
            SystemClock.sleep(50)
        }
        idle()
    }

    inner class CalendarQaActivityController : Closeable {
        init {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("input keyevent 82")
            SystemClock.sleep(300)
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitResumed()
        }

        fun onActivity(action: (MainActivity) -> Unit) {
            awaitResumed()
            instrumentation.runOnMainSync {
                val current = resumed()
                check(current.isNotEmpty()) { "Expected at least one resumed MainActivity" }
                action(current.first())
            }
        }

        fun recreate() {
            var previous: MainActivity? = null
            onActivity { activity -> previous = activity; activity.recreate() }
            awaitResumed(previous)
        }

        private fun resumed(): List<MainActivity> = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            .filter { !it.isFinishing && !it.isDestroyed }

        private fun awaitResumed(previous: MainActivity? = null) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            var dump = ""
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    val current = resumed()
                    ready = current.isNotEmpty() && current.first() !== previous
                    if (!ready) {
                        val all = Stage.values().flatMap { stage ->
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).map {
                                "${it.javaClass.simpleName}@${it.hashCode()}:$stage:finishing=${it.isFinishing}"
                            }
                        }
                        dump = all.joinToString(", ")
                    }
                }
                if (ready) return
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
                SystemClock.sleep(100)
            }
            val focus = device.executeShellCommand("dumpsys window | grep mCurrentFocus")
            error("No current resumed MainActivity appeared within 20s. Stages: $dump. Focus: $focus")
        }

        override fun close() {
            instrumentation.runOnMainSync {
                Stage.values().flatMap { stage ->
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                }.filterIsInstance<MainActivity>().distinct().forEach {
                    if (!it.isFinishing) it.finish()
                }
            }
        }
    }

    private fun fragment(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment

    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(250)
    }

    private fun capture(name: String) {
        idle()
        SystemClock.sleep(400)
        val file = File(output, "$name.png")
        check(device.takeScreenshot(file)) { "Failed to take screenshot: ${file.absolutePath}" }
    }

    private fun evidence(line: String) {
        File(output, "evidence.txt").appendText("$line\n")
    }

    private fun set(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
}
