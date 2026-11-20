/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

data class SettingsTransitionSource(
    val sectionId: SettingsSectionId,
    val hostView: View,
    val boundsInHost: () -> RectF?,
    val setHidden: (Boolean) -> Unit = {},
) {
    fun boundsInAncestor(ancestor: ViewGroup): RectF? {
        if (!hostView.isAttachedToWindow || !ancestor.isAttachedToWindow) return null
        if (hostView.width <= 0 || hostView.height <= 0 || ancestor.width <= 0 || ancestor.height <= 0) {
            return null
        }
        val localRect = boundsInHost() ?: return null
        if (!localRect.left.isFinite() || !localRect.top.isFinite() ||
            !localRect.right.isFinite() || !localRect.bottom.isFinite() ||
            localRect.width() <= 1f || localRect.height() <= 1f
        ) {
            return null
        }
        val hostLocation = IntArray(2)
        val ancestorLocation = IntArray(2)
        hostView.getLocationOnScreen(hostLocation)
        ancestor.getLocationOnScreen(ancestorLocation)
        val dx = (hostLocation[0] - ancestorLocation[0]).toFloat()
        val dy = (hostLocation[1] - ancestorLocation[1]).toFloat()
        return RectF(
            localRect.left + dx,
            localRect.top + dy,
            localRect.right + dx,
            localRect.bottom + dy,
        )
    }

    fun captureBitmap(): Bitmap? {
        if (!hostView.isAttachedToWindow || hostView.width <= 0 || hostView.height <= 0) return null
        val localRect = boundsInHost() ?: return null
        val width = localRect.width().roundToInt()
        val height = localRect.height().roundToInt()
        if (width <= 0 || height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.translate(-localRect.left, -localRect.top)
                hostView.draw(canvas)
            }
        }.getOrNull()
    }
}
