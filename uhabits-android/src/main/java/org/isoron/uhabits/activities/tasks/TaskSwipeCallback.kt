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

package org.isoron.uhabits.activities.tasks

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.userTasks.UserTask
import kotlin.math.abs

internal class TaskSwipeCallback(
    private var palette: SettingsThemePalette,
    private val isSelectionModeActive: () -> Boolean,
    private val onSwipeChangeDue: (UserTask) -> Unit,
    private val onSwipeMoveContainer: (UserTask) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {

    var itemTouchHelper: ItemTouchHelper? = null
    var attachedRecyclerView: RecyclerView? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bgRect = RectF()

    fun updatePalette(newPalette: SettingsThemePalette) {
        palette = newPalette
    }

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        if (isSelectionModeActive()) return 0
        val taskHolder = viewHolder as? TasksAdapter.TaskViewHolder ?: return 0
        if (taskHolder.currentTask == null) return 0
        return super.getSwipeDirs(recyclerView, viewHolder)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.25f

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 1.4f

    override fun getAnimationDuration(
        recyclerView: RecyclerView,
        animationType: Int,
        animateDx: Float,
        animateDy: Float,
    ): Long = 90L

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val taskHolder = viewHolder as? TasksAdapter.TaskViewHolder ?: return
        val task = taskHolder.currentTask ?: return
        taskHolder.itemView.translationX = 0f
        val rv = attachedRecyclerView ?: (viewHolder.itemView.parent as? RecyclerView)
        val pos = viewHolder.adapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            rv?.adapter?.notifyItemChanged(pos)
        }
        // Reset ItemTouchHelper internal recover animations so the same row can be swiped repeatedly
        // even when itemAnimator is null and the user cancels the picker without mutating the row.
        val helper = itemTouchHelper
        if (helper != null && rv != null) {
            helper.attachToRecyclerView(null)
            helper.attachToRecyclerView(rv)
        }
        rv?.invalidate()

        val isRtl = viewHolder.itemView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val isEndAction = if (!isRtl) {
            direction == ItemTouchHelper.END || direction == ItemTouchHelper.RIGHT
        } else {
            direction == ItemTouchHelper.END || direction == ItemTouchHelper.LEFT
        }
        if (isEndAction) {
            onSwipeChangeDue(task)
        } else {
            onSwipeMoveContainer(task)
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.translationX = 0f
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }
        val itemView = viewHolder.itemView
        val context = itemView.context
        val maxShift = TaskThemeUtils.dp(context, 116f)
        val visualDx = dX.coerceIn(-maxShift, maxShift)

        if (abs(visualDx) > 1f) {
            val isRtl = itemView.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val swipingTowardEnd = if (!isRtl) visualDx > 0f else visualDx < 0f
            val thresholdPx = minOf(itemView.width * getSwipeThreshold(viewHolder), maxShift * 0.85f)
            val armed = abs(dX) >= thresholdPx

            bgPaint.color = if (armed) {
                MainTabsThemeBridge.withAlpha(palette.accent, if (palette.isDark) 0.22f else 0.14f)
            } else {
                MainTabsThemeBridge.withAlpha(palette.onSurface, if (palette.isDark) 0.08f else 0.05f)
            }
            if (visualDx > 0f) {
                bgRect.set(
                    itemView.left.toFloat(),
                    itemView.top.toFloat(),
                    itemView.left + visualDx,
                    itemView.bottom.toFloat(),
                )
            } else {
                bgRect.set(
                    itemView.right + visualDx,
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat(),
                )
            }

            c.save()
            c.clipRect(bgRect)
            c.drawRect(bgRect, bgPaint)

            val iconRes = if (swipingTowardEnd) R.drawable.ic_cat_calendar else R.drawable.ic_cat_folder
            val label = if (swipingTowardEnd) "Срок" else "Раздел"
            val fgColor = if (armed) palette.accent else palette.onSurfaceVariant

            val iconSize = TaskThemeUtils.dpInt(context, 18f)
            val edgePadding = TaskThemeUtils.dpInt(context, 16f)
            val gap = TaskThemeUtils.dpInt(context, 8f)
            val centerY = (itemView.top + itemView.bottom) / 2f

            textPaint.color = fgColor
            textPaint.textSize = TaskThemeUtils.dp(context, 13f)
            val textMetrics = textPaint.fontMetrics
            val textBaseline = centerY - (textMetrics.ascent + textMetrics.descent) / 2f

            val drawable = AppCompatResources.getDrawable(context, iconRes)?.mutate()
            drawable?.setTint(fgColor)

            if (visualDx > 0f) {
                val iconLeft = itemView.left + edgePadding
                val iconTop = (centerY - iconSize / 2f).toInt()
                drawable?.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                drawable?.draw(c)
                c.drawText(label, (iconLeft + iconSize + gap).toFloat(), textBaseline, textPaint)
            } else {
                val textWidth = textPaint.measureText(label)
                val iconRight = itemView.right - edgePadding
                val iconLeft = iconRight - iconSize
                val iconTop = (centerY - iconSize / 2f).toInt()
                drawable?.setBounds(iconLeft, iconTop, iconRight, iconTop + iconSize)
                drawable?.draw(c)
                c.drawText(label, iconLeft - gap - textWidth, textBaseline, textPaint)
            }
            c.restore()
        }

        // Pass raw dX to super so ItemTouchHelper's RecoverAnimation completes its settle trajectory,
        // then clamp the visual translationX so the row never flies off-screen.
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        itemView.translationX = visualDx
    }
}
