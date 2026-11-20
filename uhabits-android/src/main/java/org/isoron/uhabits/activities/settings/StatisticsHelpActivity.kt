/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

/** Local, readable explanation of the aggregate statistics report. */
class StatisticsHelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = (applicationContext as HabitsApplication).component.preferences
        AndroidThemeSwitcher(this, prefs).apply()
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val palette = SettingsThemePaletteResolver.resolve(this, prefs)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !palette.isDark
        insetsController.isAppearanceLightNavigationBars = !palette.isDark

        val composeColors = UHabitComposeColors.fromPalette(
            palette,
            cardCornerRadiusDp = palette.habitsCardCornerRadius,
            showCardBorders = palette.showHabitCardBorders
        )

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors = composeColors) {
                    StatisticsHelpComposeScreen(
                        onBack = { finish() }
                    )
                }
            }
        }
        setContentView(composeView)
    }
}
