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
 * Loop Habit Tracker is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.habits.today

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.ui.screens.habits.today.TodayScreenStateBuilder
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.utils.applyRootViewInsets

class TodayActivity : AppCompatActivity(), CommandRunner.Listener {
    private lateinit var view: TodayView
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private val component
        get() = (applicationContext as HabitsApplication).component

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        view = TodayView(
            activity = this,
            context = this,
            preferences = component.preferences,
            onHabitClick = { habitId ->
                val habit = component.habitList.getById(habitId) ?: return@TodayView
                startActivity(IntentFactory().startShowHabitActivity(this, habit))
            },
            onRefresh = { refresh() }
        )
        view.applyRootViewInsets()
        setContentView(view)
        component.commandRunner.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        component.commandRunner.removeListener(this)
        super.onDestroy()
    }

    override fun onCommandFinished(command: Command) {
        refresh()
    }



    private fun refresh() {
        view.setState(TodayScreenStateBuilder.build(component.habitList))
    }
}
