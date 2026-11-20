/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "en-rUS-w320dp-h640dp-port-notnight-mdpi",
    fontScale = 1f
)
class TaskComponentsScreenshotTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate(2026, 11, 19)
    private val nowMillis = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()

    // Fixed palettes from the original component snapshots.
    private val lightColors = UHabitComposeColors(
        background = 0xFFFFFFFF.toInt(),
        foreground = 0xFF1C1B1F.toInt(),
        secondary = 0xFF79747E.toInt(),
        accent = 0xFF2E7D32.toInt(),
        surface = 0xFFF5F5F5.toInt(),
        border = 0xFFE0E0E0.toInt(),
        danger = 0xFFBA1A1A.toInt(),
        warning = 0xFF7D5700.toInt()
    )

    private val darkColors = UHabitComposeColors(
        background = 0xFF1C1B1F.toInt(),
        foreground = 0xFFE6E1E5.toInt(),
        secondary = 0xFF938F99.toInt(),
        accent = 0xFF81C784.toInt(),
        surface = 0xFF2B2930.toInt(),
        border = 0xFF49454F.toInt(),
        danger = 0xFFFFB4AB.toInt(),
        warning = 0xFFFFBA28.toInt(),
        isDark = true
    )

    private val amoledColors = darkColors.copy(
        background = 0xFF000000.toInt(),
        surface = 0xFF000000.toInt(),
        surfaceVariant = 0xFF000000.toInt(),
        border = 0xFF272727.toInt(),
        isPureBlack = true
    )

    @Before
    fun checkRuntimeConfiguration() {
        assertEquals(34, RuntimeEnvironment.getApiLevel())
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals(320, resources.configuration.screenWidthDp)
        assertEquals(160, resources.displayMetrics.densityDpi)
    }

    @Test
    fun taskSectionHeader_light_expanded() = captureSectionHeader(
        image = "task_section_header_light.png",
        title = "Сегодня",
        colors = lightColors,
        count = 3
    )

    @Test
    fun taskSectionHeader_dark_collapsed() = captureSectionHeader(
        image = "task_section_header_dark.png",
        title = "Предстоящие",
        colors = darkColors,
        count = 5,
        expanded = false
    )

    @Test
    fun taskSectionHeader_amoled() = captureSectionHeader(
        image = "task_section_header_amoled.png",
        title = "Просрочено",
        colors = amoledColors,
        count = 2
    )

    @Test
    fun taskRow_light_normal() = captureTaskRow(
        image = "task_row_light_normal.png",
        title = "Оформить квартальный отчёт",
        colors = lightColors,
        due = TaskDue.Day(TaskDueDate.from(today)),
        important = true,
        subtaskCount = 1 to 3,
        containerName = "Работа / Финансы"
    )

    @Test
    fun taskRow_dark_completed() = captureTaskRow(
        image = "task_row_dark_completed.png",
        title = "Купить билеты на поезд",
        colors = darkColors,
        isCompleted = true,
        containerName = "Личное"
    )

    @Test
    fun taskRow_amoled_selected() = captureTaskRow(
        image = "task_row_amoled_selected.png",
        title = "Проверить индексы базы данных",
        colors = amoledColors,
        due = TaskDue.Day(TaskDueDate.from(today.minus(1))),
        important = true,
        urgent = true,
        isSelected = true,
        containerName = "UHabit Next / Core"
    )

    @Test
    @Config(qualifiers = "+ru-rRU-night", fontScale = 2f)
    fun taskRow_amoled_multiline_320dp_200percent() {
        assertLargeTextConfiguration()
        captureTaskRow(
            image = "task_row_amoled_multiline_320dp_200percent.png",
            title = "Подготовить отчёт\nдля встречи команды",
            colors = productionAmoledColors(),
            due = TaskDue.Day(TaskDueDate.from(today)),
            important = true,
            subtaskCount = 2 to 5,
            containerName = "Работа / Еженедельные встречи"
        )
    }

    @Test
    @Config(qualifiers = "+ru-rRU-night", fontScale = 2f)
    fun taskSectionHeader_amoled_multiline_320dp_200percent() {
        assertLargeTextConfiguration()
        // The production chevron resolves its stroke from the activity's theme.
        composeTestRule.activityRule.scenario.onActivity {
            it.setTheme(R.style.AppBaseThemeDark_PureBlack)
        }
        captureSectionHeader(
            image = "task_section_header_amoled_multiline_320dp_200percent.png",
            title = "Работа\nи материалы",
            colors = productionAmoledColors(),
            count = 12,
            expanded = false,
            onToggle = {}
        )
    }

    private fun assertLargeTextConfiguration() {
        val configuration = RuntimeEnvironment.getApplication().resources.configuration
        assertEquals(2f, configuration.fontScale, 0f)
        assertEquals("ru-RU", configuration.locales[0].toLanguageTag())
    }

    private fun productionAmoledColors(): UHabitComposeColors {
        val context = RuntimeEnvironment.getApplication()
        val preferences = Preferences(MemoryStorage()).apply {
            theme = ThemeSwitcher.THEME_DARK
            isPureBlackEnabled = true
        }
        val palette = SettingsThemePaletteResolver.resolve(context, preferences)
        assertEquals(0xFF000000.toInt(), palette.background)
        return UHabitComposeColors.fromPalette(
            palette,
            danger = context.getColor(R.color.color_picker_danger_dark),
            warning = 0xFFFFB74D.toInt()
        )
    }

    private fun captureSectionHeader(
        image: String,
        title: String,
        colors: UHabitComposeColors,
        count: Int,
        expanded: Boolean = true,
        onToggle: (() -> Unit)? = null
    ) {
        composeTestRule.setContent {
            Box(
                Modifier.fillMaxWidth().background(colors.backgroundColor).padding(vertical = 4.dp)
            ) {
                TaskSectionHeaderCompose(
                    title = title,
                    colors = colors,
                    count = count,
                    expanded = expanded,
                    onToggle = onToggle
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(image)
    }

    private fun captureTaskRow(
        image: String,
        title: String,
        colors: UHabitComposeColors,
        containerName: String,
        due: TaskDue = TaskDue.None,
        important: Boolean = false,
        urgent: Boolean = false,
        subtaskCount: Pair<Int, Int>? = null,
        isCompleted: Boolean = false,
        isSelected: Boolean = false
    ) {
        val task = UserTask(
            id = TaskId("screenshot-task"),
            title = title,
            due = due,
            important = important,
            urgent = urgent,
            createdAt = nowMillis,
            updatedAt = nowMillis
        )
        composeTestRule.setContent {
            UHabitComposeTheme(colors = colors) {
                Box(Modifier.fillMaxWidth().background(colors.backgroundColor)) {
                    TaskRowCompose(
                        task = task,
                        isCompleted = isCompleted,
                        isSelected = isSelected,
                        selectionActive = isSelected,
                        subtaskCount = subtaskCount,
                        containerName = containerName,
                        containerColor = null,
                        tags = emptyList(),
                        today = today,
                        nowMillis = nowMillis,
                        onTaskClicked = {},
                        onToggleComplete = {},
                        onTaskLongClicked = {},
                        onShowContextMenu = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(image)
    }
}
