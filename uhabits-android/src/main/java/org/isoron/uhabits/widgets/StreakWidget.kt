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
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.StreakChart
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.ui.views.WidgetTheme
import org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter
import org.isoron.uhabits.widgets.views.GraphWidgetView

class StreakWidget(
    context: Context,
    id: Int,
    private val habit: Habit,
    stacked: Boolean = false
) : BaseWidget(context, id, stacked) {
    override val defaultHeight: Int = 200
    override val defaultWidth: Int = 200

    override fun getOnClickPendingIntent(context: Context): PendingIntent =
        pendingIntentFactory.showWidgetHabit(habit)

    override fun refreshData(view: View) {
        val widgetView = view as GraphWidgetView
        widgetView.setBackgroundAlpha(preferedBackgroundAlpha)
        if (preferedBackgroundAlpha >= 255) widgetView.setShadowAlpha(0x4f)
        val snapshot = effectiveSnapshot
        val model = if (snapshot != null) {
            EffectiveWidgetPresenter.streak(habit, snapshot, WidgetTheme())
        } else {
            null
        }
        if (snapshot != null && model == null) {
            widgetView.setStatus(context.getString(R.string.widget_effective_streak_unavailable))
        } else {
            widgetView.setStatus(null)
            (widgetView.dataView as StreakChart).apply {
                setColor(WidgetTheme().color(habit.color).toInt())
                if (model != null) {
                    val list = mutableListOf<Streak>()
                    model.bestStreaks.forEach { list.add(it) }
                    setStreaks(list)
                } else {
                    setStreaks(habit.streaks.getBest(maxStreakCount))
                }
            }
        }
    }

    override fun buildView(): View {
        return GraphWidgetView(context, StreakChart(context)).apply {
            setTitle(habit.name)
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
    }
}
