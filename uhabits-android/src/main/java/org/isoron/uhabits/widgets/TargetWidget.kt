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

package org.isoron.uhabits.widgets

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import kotlinx.coroutines.runBlocking
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.TargetChart
import org.isoron.uhabits.activities.habits.list.views.toShortString
import org.isoron.uhabits.activities.habits.show.views.TargetCardView.Companion.intervalToLabel
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardPresenter
import org.isoron.uhabits.core.ui.views.WidgetTheme
import org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter
import org.isoron.uhabits.widgets.views.GraphWidgetView

class TargetWidget(
    context: Context,
    id: Int,
    private val habit: Habit,
    stacked: Boolean = false
) : BaseWidget(context, id, stacked) {
    override val defaultHeight: Int = 200
    override val defaultWidth: Int = 200

    override fun getOnClickPendingIntent(context: Context): PendingIntent =
        pendingIntentFactory.showWidgetHabit(habit)

    override fun refreshData(view: View) = runBlocking {
        val widgetView = view as GraphWidgetView
        widgetView.setBackgroundAlpha(preferedBackgroundAlpha)
        if (preferedBackgroundAlpha >= 255) widgetView.setShadowAlpha(0x4f)
        val chart = (widgetView.dataView as TargetChart)
        val snapshot = effectiveSnapshot
        if (snapshot != null) {
            val state = EffectiveWidgetPresenter.target(snapshot, getToday())
            val progress = state.progress
            if (!state.available || progress == null) {
                widgetView.setStatus(context.getString(R.string.widget_effective_goal_unavailable))
            } else {
                val statusText = when (progress.status) {
                    HabitGoalStatus.SATISFIED -> context.getString(R.string.widget_effective_goal_reached)
                    HabitGoalStatus.NOT_SATISFIED -> context.getString(R.string.widget_effective_goal_not_reached)
                    HabitGoalStatus.SKIPPED -> context.getString(R.string.widget_effective_skipped)
                    else -> context.getString(R.string.widget_effective_unknown)
                }
                val manualStr = (progress.value.manualValue / 1000.0).toShortString()
                val focusStr = (progress.value.contributionValue / 1000.0).toShortString()
                val breakdown = context.getString(R.string.widget_effective_breakdown, manualStr, focusStr)
                val targetStr = progress.goal.targetValue.toShortString()
                val totalStr = (progress.value.totalValue?.let { it / 1000.0 } ?: 0.0).toShortString()
                val unit = habit.unit.ifEmpty { "" }
                val label = "$statusText\n$totalStr / $targetStr $unit\n$breakdown"
                widgetView.setStatus(label)
            }
        } else {
            widgetView.setStatus(null)
            val data = TargetCardPresenter.buildState(
                habit = habit,
                firstWeekday = prefs.firstWeekdayInt,
                theme = WidgetTheme()
            )
            chart.setColor(WidgetTheme().color(habit.color).toInt())
            chart.setTargets(data.targets)
            chart.setLabels(data.intervals.map { intervalToLabel(context.resources, it) })
            chart.setValues(data.values)
        }
    }

    override fun buildView(): View {
        return GraphWidgetView(context, TargetChart(context)).apply {
            setTitle(habit.name)
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
    }
}
