/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.components.UHabitActionChip
import org.isoron.uhabits.compose.components.UHabitCompactSwitch
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "en-rUS-w360dp-h640dp-port-notnight-mdpi",
    fontScale = 1f,
)
class SettingsComponentsScreenshotTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val lightCardsColors = UHabitComposeColors(
        background = 0xFFF5F6F8.toInt(),
        foreground = 0xFF1C1B1F.toInt(),
        secondary = 0xFF6B7280.toInt(),
        accent = 0xFF2E7D32.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        border = 0xFFD9DEE5.toInt(),
        divider = 0x14000000,
        danger = 0xFFBA1A1A.toInt(),
        warning = 0xFF7D5700.toInt(),
        isDark = false,
        isPureBlack = false,
        isClassicSectionLayout = false,
    )

    private val darkCardsColors = UHabitComposeColors(
        background = 0xFF121316.toInt(),
        foreground = 0xFFE6E1E5.toInt(),
        secondary = 0xFF9CA3AF.toInt(),
        accent = 0xFF81C784.toInt(),
        surface = 0xFF1C1E22.toInt(),
        border = 0x1FFFFFFF,
        divider = 0x14FFFFFF,
        danger = 0xFFFFB4AB.toInt(),
        warning = 0xFFFFBA28.toInt(),
        isDark = true,
        isPureBlack = false,
        isClassicSectionLayout = false,
    )

    private val amoledCardsColors = darkCardsColors.copy(
        background = 0xFF000000.toInt(),
        surface = 0xFF000000.toInt(),
        surfaceVariant = 0xFF000000.toInt(),
        border = 0x14FFFFFF,
        divider = 0x14FFFFFF,
        isPureBlack = true,
        isClassicSectionLayout = false,
    )

    private val amoledClassicColors = amoledCardsColors.copy(
        isClassicSectionLayout = true,
    )

    private fun sampleHomeItems(): List<SettingItem> = listOf(
        SettingItem.Section(
            sectionId = SettingsSectionId.APPEARANCE,
            iconRes = R.drawable.ic_settings_theme,
            title = "Appearance",
            summary = "Theme, accent color, cards layout",
            accessibilityDescription = "Appearance",
            onClick = {},
            presentation = SettingPresentation(cardId = "section:APPEARANCE", isFirst = true, isLast = false),
        ),
        SettingItem.Section(
            sectionId = SettingsSectionId.HABITS,
            iconRes = R.drawable.ic_settings_calendar,
            title = "Habits & Day",
            summary = "Start of week, reverse order, toggle behavior",
            accessibilityDescription = "Habits & Day",
            onClick = {},
            presentation = SettingPresentation(cardId = "section:HABITS", isFirst = false, isLast = false),
        ),
        SettingItem.Section(
            sectionId = SettingsSectionId.DATA,
            iconRes = R.drawable.ic_settings_backup,
            title = "Data & Backup",
            summary = "Local backup, export CSV, import",
            accessibilityDescription = "Data & Backup",
            onClick = {},
            presentation = SettingPresentation(cardId = "section:DATA", isFirst = false, isLast = true),
        ),
    )

    private fun sampleDetailItems(): List<SettingItem> = listOf(
        SettingItem.Subsection(
            key = "theme_sub",
            title = "Theme",
            presentation = SettingPresentation(cardId = "card_theme", isFirst = true, isLast = false),
        ),
        SettingItem.SegmentedTheme(
            key = "pref_theme",
            iconRes = R.drawable.ic_settings_theme,
            title = "App theme",
            themeValue = ThemeSwitcher.THEME_DARK,
            pureBlackValue = true,
            onSegmentSelected = { _, _ -> },
            presentation = SettingPresentation(cardId = "card_theme", isFirst = false, isLast = false),
        ),
        SettingItem.ColorPicker(
            key = "pref_accent_color",
            iconRes = R.drawable.ic_settings_droplet,
            title = "Accent color",
            summary = "Green",
            colorString = "#2E7D32",
            onClick = {},
            presentation = SettingPresentation(cardId = "card_theme", isFirst = false, isLast = true),
        ),
        SettingItem.Subsection(
            key = "layout_sub",
            title = "Layout",
            presentation = SettingPresentation(cardId = "card_layout", isFirst = true, isLast = false),
        ),
        SettingItem.Switch(
            key = "pref_classic_layout",
            iconRes = R.drawable.ic_settings_layout_list,
            title = "Classic list layout",
            summary = "Full-width sections without cards",
            checked = false,
            onCheckedChange = {},
            presentation = SettingPresentation(cardId = "card_layout", isFirst = false, isLast = false),
        ),
        SettingItem.Value(
            key = "pref_card_radius",
            iconRes = R.drawable.ic_settings_corners,
            title = "Card corner radius",
            valueText = "8 dp",
            onClick = {},
            presentation = SettingPresentation(cardId = "card_layout", isFirst = false, isLast = true),
        ),
    )

    @Test
    fun settingsHome_light_cards() {
        composeTestRule.setContent {
            SettingsListContent(items = sampleHomeItems(), colors = lightCardsColors)
        }
        composeTestRule.onRoot().captureRoboImage(
            "src/screenshotTest/screenshots/settings_home_light_cards.png",
        )
    }

    @Test
    fun settingsHome_amoled_cards() {
        composeTestRule.setContent {
            SettingsListContent(items = sampleHomeItems(), colors = amoledCardsColors)
        }
        composeTestRule.onRoot().captureRoboImage(
            "src/screenshotTest/screenshots/settings_home_amoled_cards.png",
        )
    }

    @Test
    fun settingsHome_amoled_classic() {
        composeTestRule.setContent {
            SettingsListContent(items = sampleHomeItems(), colors = amoledClassicColors)
        }
        composeTestRule.onRoot().captureRoboImage(
            "src/screenshotTest/screenshots/settings_home_amoled_classic.png",
        )
    }

    @Test
    fun settingsDetail_dark_cards() {
        composeTestRule.setContent {
            SettingsListContent(items = sampleDetailItems(), colors = darkCardsColors)
        }
        composeTestRule.onRoot().captureRoboImage(
            "src/screenshotTest/screenshots/settings_detail_dark_cards.png",
        )
    }

    @Test
    fun settingsDetail_amoled_classic_and_phase5_tokens() {
        composeTestRule.setContent {
            UHabitComposeTheme(amoledClassicColors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(amoledClassicColors.backgroundColor)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    UHabitSectionCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            UHabitActionChip(text = "Action", onClick = {})
                            UHabitCompactSwitch(checked = true, onCheckedChange = {})
                        }
                    }
                    Text(
                        text = "AMOLED Classic Surface Check",
                        color = amoledClassicColors.foregroundColor,
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            "src/screenshotTest/screenshots/settings_phase5_tokens_amoled.png",
        )
    }
}
