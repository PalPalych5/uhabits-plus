/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.widgets.activities

import android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID
import android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.preferences.WidgetPreferences
import org.isoron.uhabits.widgets.WidgetUpdater

class BooleanHabitPickerDialog : HabitPickerDialog() {
    override fun shouldHideNumerical() = true
    override fun getEmptyMessage() = R.string.no_boolean_habits
}

class NumericalHabitPickerDialog : HabitPickerDialog() {
    override fun shouldHideBoolean() = true
    override fun getEmptyMessage() = R.string.no_numerical_habits
}

open class HabitPickerDialog : ComponentActivity() {

    private var widgetId = 0
    private lateinit var widgetPreferences: WidgetPreferences
    private lateinit var widgetUpdater: WidgetUpdater

    protected open fun shouldHideNumerical() = false
    protected open fun shouldHideBoolean() = false
    protected open fun getEmptyMessage() = R.string.no_habits

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = (applicationContext as HabitsApplication).component
        AndroidThemeSwitcher(this, component.preferences).applyDialog()
        val habitList = component.habitList
        widgetPreferences = component.widgetPreferences
        widgetUpdater = component.widgetUpdater
        widgetId = intent.extras?.getInt(EXTRA_APPWIDGET_ID, INVALID_APPWIDGET_ID) ?: 0

        val hideNumerical = shouldHideNumerical()
        val hideBoolean = shouldHideBoolean()
        val habits = habitList.asSequence()
            .filterNot { it.isArchived || (if (it.isNumerical) hideNumerical else hideBoolean) }
            .map { requireNotNull(it.id) to it.name }
            .toList()
        val colors = UHabitComposeColors.fromPalette(MainTabsThemeBridge.resolve(this))
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors) {
                    if (habits.isEmpty()) {
                        Box(
                            Modifier.width(250.dp).heightIn(min = 150.dp).padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(getEmptyMessage()),
                                color = colors.foregroundColor,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn {
                            itemsIndexed(habits, key = { _, habit -> habit.first }) { index, (id, name) ->
                                Box(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .clickable(role = Role.Button) { confirm(listOf(id)) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(name, color = colors.foregroundColor, fontSize = 16.sp)
                                }
                                if (index < habits.lastIndex) HorizontalDivider(color = colors.dividerColor)
                            }
                        }
                    }
                }
            }
        })
    }

    fun confirm(selectedIds: List<Long>) {
        widgetPreferences.addWidget(widgetId, selectedIds.toLongArray())
        widgetUpdater.updateWidgets()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_APPWIDGET_ID, widgetId)
            }
        )
        finish()
    }
}
