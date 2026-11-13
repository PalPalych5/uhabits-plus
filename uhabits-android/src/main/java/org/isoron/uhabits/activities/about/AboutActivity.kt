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

package org.isoron.uhabits.activities.about

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import org.isoron.uhabits.compose.UHabitComposeColors

/**
 * Activity that displays metadata, project links, upstream attribution, and open-source licenses.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HabitsApplication
        val prefs = app.component.preferences
        AndroidThemeSwitcher(this, prefs).apply()
        BaseExceptionHandler.install(this)

        val palette = SettingsThemePaletteResolver.resolve(this, prefs)
        val composeColors = UHabitComposeColors.fromPalette(palette)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !palette.isDark
        insetsController.isAppearanceLightNavigationBars = !palette.isDark

        val screen = AboutScreen(
            activity = this,
            intents = app.component.intentFactory,
            prefs = prefs,
        )
        val gplLicenseText = readLegalAsset("legal/LICENSE.txt")
        val fullNoticesText = readLegalAsset("legal/NOTICE.md")

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var destination by rememberSaveable { mutableStateOf(AboutDestination.MAIN) }
                var isDeveloper by rememberSaveable { mutableStateOf(screen.isDeveloper) }
                val snackbarHostState = remember { SnackbarHostState() }
                val coroutineScope = rememberCoroutineScope()

                AboutComposeScreen(
                    colors = composeColors,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    isDeveloper = isDeveloper,
                    destination = destination,
                    gplLicenseText = gplLicenseText,
                    fullNoticesText = fullNoticesText,
                    snackbarHostState = snackbarHostState,
                    onNavigate = { destination = it },
                    onBack = {
                        val parent = destination.parent()
                        if (parent != null) {
                            destination = parent
                        } else {
                            finish()
                        }
                    },
                    onVersionTap = {
                        when (screen.onPressDeveloperCountdown()) {
                            DeveloperUnlockOutcome.Activated -> {
                                isDeveloper = true
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(screen.developerActivatedMessage())
                                }
                            }

                            is DeveloperUnlockOutcome.Countdown,
                            DeveloperUnlockOutcome.AlreadyEnabled -> Unit
                        }
                    },
                    onOpenSourceCode = { screen.onViewProjectSourceCode() },
                    onReportIssue = { screen.onReportIssue() },
                    onOpenUpstreamRepository = { screen.onViewUpstreamRepository() },
                    onOpenUpstreamContributors = { screen.onViewContributors() },
                )
            }
        }
        setContentView(composeView)
    }

    private fun readLegalAsset(path: String): String =
        runCatching {
            assets.open(path).bufferedReader().use { it.readText() }
        }.getOrDefault("")
}
