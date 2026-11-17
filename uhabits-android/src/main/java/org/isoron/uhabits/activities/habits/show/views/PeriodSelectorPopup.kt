/* SPDX-License-Identifier: GPL-3.0-or-later */
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
