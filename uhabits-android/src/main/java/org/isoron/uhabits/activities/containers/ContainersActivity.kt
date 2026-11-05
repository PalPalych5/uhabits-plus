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

package org.isoron.uhabits.activities.containers

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.containers.statistics.ContainerStatisticsActivity
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.inject.HabitsActivityComponent
import org.isoron.uhabits.inject.create

class ContainersActivity : AppCompatActivity() {

    private val app get() = application as HabitsApplication
    private val appComponent get() = app.component
    private val facade get() = appComponent.organizationFacade
    private val habitList get() = appComponent.habitList
    private val prefs get() = appComponent.preferences

    private val activityComponent by lazy {
        HabitsActivityComponent::class.create(appComponent, this)
    }
    private val listHabitsBehavior by lazy {
        activityComponent.listHabitsBehavior
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = org.isoron.uhabits.activities.tasks.TaskDueDisplayFormatter.selectedLocale(newBase)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidThemeSwitcher(this, prefs).apply()
        BaseExceptionHandler.install(this)

        val palette = SettingsThemePaletteResolver.resolve(this, prefs)
        val composeColors = UHabitComposeColors.fromPalette(palette)
        val initialContainerId = intent.getStringExtra(EXTRA_CONTAINER_ID)?.let { ContainerId(it) }

        // Configure edge-to-edge system bars so Compose can apply WindowInsets padding accurately
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !palette.isDark
        insetsController.isAppearanceLightNavigationBars = !palette.isDark

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ContainersComposeScreen(
                    facade = facade,
                    habitList = habitList,
                    colors = composeColors,
                    initialContainerId = initialContainerId,
                    onBackFinish = { finish() },
                    onOpenStatistics = { containerId ->
                        ContainerStatisticsActivity.start(this@ContainersActivity, containerId)
                    },
                    onAddHabitInContainer = { _ ->
                        val dialog = HabitTypeDialog.newInstance()
                        dialog.show(supportFragmentManager, "habit_type")
                    },
                    onOpenHabitDetail = { habit ->
                        listHabitsBehavior.onClickHabit(habit)
                    }
                )
            }
        }
        setContentView(composeView)
    }

    companion object {
        const val EXTRA_CONTAINER_ID = "extra_container_id"

        fun intent(context: Context, containerId: ContainerId? = null): Intent {
            return Intent(context, ContainersActivity::class.java).apply {
                containerId?.let { putExtra(EXTRA_CONTAINER_ID, it.value) }
            }
        }
    }
}
