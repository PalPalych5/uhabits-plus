/*
 * Copyright (C) 2016-2025 Alinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.activities.habits.show.views

import android.view.View
import android.view.animation.PathInterpolator
import org.isoron.uhabits.activities.common.views.CompactPopupMenu

private const val SELECTOR_ARROW_ANIMATION_MS = 160L

internal fun showPeriodSelectorPopup(
    anchor: View,
    arrow: View,
    entries: List<CompactPopupMenu.Entry>,
    onItemClick: (Int) -> Unit,
) {
    val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun rotateArrow(rotation: Float) {
        arrow.animate().cancel()
        arrow.animate()
            .rotation(rotation)
            .setDuration(SELECTOR_ARROW_ANIMATION_MS)
            .setInterpolator(interpolator)
            .start()
    }

    rotateArrow(180f)
    CompactPopupMenu.show(
        anchor = anchor,
        entries = entries,
        onDismiss = { rotateArrow(0f) },
        onItemClick = onItemClick,
    )
}
