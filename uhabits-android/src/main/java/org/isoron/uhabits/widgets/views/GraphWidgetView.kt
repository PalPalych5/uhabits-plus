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
package org.isoron.uhabits.widgets.views

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.R

class GraphWidgetView(context: Context?, val dataView: View) : HabitWidgetView(context) {
    private lateinit var title: TextView
    private lateinit var status: TextView
    fun setStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) GONE else VISIBLE
        dataView.visibility = if (text == null) VISIBLE else GONE
    }
    fun setTitle(text: String?) {
        title.text = text
    }

    override val innerLayoutId: Int
        get() = R.layout.widget_graph

    private fun init() {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1f
        )
        dataView.layoutParams = params
        val innerFrame = findViewById<View>(R.id.innerFrame) as ViewGroup
        innerFrame.addView(dataView)
        status = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(StyledResources(context).getColor(R.attr.contrast100))
            textSize = 14f
            visibility = GONE
            maxLines = 8
            setPadding(4, 4, 4, 4)
        }
        innerFrame.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        title = findViewById<View>(R.id.title) as TextView
        title.visibility = VISIBLE
    }

    init {
        init()
    }
}
