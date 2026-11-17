/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.containers

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

        var externalRefreshTrigger by mutableStateOf(0)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ContainersComposeScreen(
                    facade = facade,
                    habitList = habitList,
                    colors = composeColors,
                    initialContainerId = initialContainerId,
                    externalRefreshTrigger = externalRefreshTrigger,
                    onBackFinish = { finish() },
                    onOpenStatistics = { containerId ->
                        ContainerStatisticsActivity.start(this@ContainersActivity, containerId)
                    },
                    onAddHabitInContainer = { _ ->
                        val intent = org.isoron.uhabits.intents.IntentFactory().startEditActivity(
                            this@ContainersActivity,
                            org.isoron.uhabits.core.models.HabitType.YES_NO.value,
                            false
                        )
                        startActivity(intent)
                    },
                    onOpenHabitDetail = { habit ->
                        listHabitsBehavior.onClickHabit(habit)
                    }
                )
            }
        }
        setContentView(composeView)

        if (intent.getBooleanExtra(EXTRA_SHOW_CREATE_DIALOG, false) && savedInstanceState == null) {
            val dialog = CreateContainerDialog.newInstance().apply {
                containerQueries = facade.containerQueries
                onSaveWithIcon = { name, parentId, color, icon ->
                    facade.createContainer(
                        name = name,
                        parentId = parentId,
                        color = color,
                        icon = org.isoron.uhabits.core.containers.IconRef.toStorageString(icon)
                    )
                    externalRefreshTrigger++
                }
            }
            dialog.show(supportFragmentManager, "create_container_dialog")
        }
    }

    companion object {
        const val EXTRA_CONTAINER_ID = "extra_container_id"
        const val EXTRA_SHOW_CREATE_DIALOG = "extra_show_create_dialog"

        fun intent(
            context: Context,
            containerId: ContainerId? = null,
            showCreateDialog: Boolean = false
        ): Intent {
            return Intent(context, ContainersActivity::class.java).apply {
                containerId?.let { putExtra(EXTRA_CONTAINER_ID, it.value) }
                if (showCreateDialog) putExtra(EXTRA_SHOW_CREATE_DIALOG, true)
            }
        }
    }
}
