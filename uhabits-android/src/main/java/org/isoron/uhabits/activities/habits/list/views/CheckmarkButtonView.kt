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

package org.isoron.uhabits.activities.habits.list.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.View.MeasureSpec.EXACTLY
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Entry.Companion.NO
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_AUTO
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.utils.drawNotesIndicator
import org.isoron.uhabits.utils.getFontAwesome
import org.isoron.uhabits.utils.sp
import org.isoron.uhabits.utils.sres
import org.isoron.uhabits.utils.toMeasureSpec

@Inject
class CheckmarkButtonViewFactory(
    @ActivityContext val context: Context,
    val preferences: Preferences
) {
    fun create() = CheckmarkButtonView(context, preferences)
}

class CheckmarkButtonView(
    context: Context,
    val preferences: Preferences
) : View(context),
    View.OnClickListener,
    View.OnLongClickListener {

    var color: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var value: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var notes = ""
        set(value) {
            field = value
            invalidate()
        }

    var onToggle: (Int, String) -> Unit = { _, _ -> }

    /** Per-view disposable-QA seam. No run-global current operation. */
    var operationTraceFactory: ((CheckmarkButtonView) -> HabitEditTrace)? = null
        set(value) {
            field = value
            if (value == null) { inputTrace = null; queuedInputTraces = null }
        }
    var onToggleTraced: ((Int, String, HabitEditTrace) -> Unit)? = null
    private var inputTrace: HabitEditTrace? = null
    private var queuedInputTraces: java.util.ArrayDeque<HabitEditTrace>? = null

    var onEdit: () -> Unit = { }

    private var drawer = Drawer()

    init {
        setOnClickListener(this)
        setOnLongClickListener(this)
    }

    fun performToggle() {
        // View may post performClick until after several UP events. Preserve each pointer's
        // context in this view's FIFO instead of reusing the last pointer or a global sample.
        val trace = queuedInputTraces?.pollFirst() ?: inputTrace.also { inputTrace = null } ?: newOperationTrace()
        toggleWithTrace(trace)
    }

    private fun toggleWithTrace(trace: HabitEditTrace?) {
        value = Entry.nextToggleValue(
            value = value,
            isSkipEnabled = preferences.isSkipEnabled,
            areQuestionMarksEnabled = preferences.areQuestionMarksEnabled
        )
        trace?.event("input.accepted")
        if (trace != null && onToggleTraced != null) onToggleTraced?.invoke(value, notes, trace)
        else onToggle(value, notes)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    override fun onClick(v: View) {
        if (preferences.isShortToggleEnabled) {
            performToggle()
        } else {
            queuedInputTraces?.pollFirst()?.event("input.rejected")
            onEdit()
        }
    }

    override fun onLongClick(v: View): Boolean {
        if (preferences.isShortToggleEnabled) {
            inputTrace?.event("input.rejected")
            inputTrace = null
            onEdit()
        } else {
            val trace = inputTrace
            inputTrace = null
            if (trace == null) performToggle() else toggleWithTrace(trace)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        HabitFrameTiming.measure("habit.checkmark.draw", this) {
            super.onDraw(canvas)
            drawer.draw(canvas)
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                inputTrace = newOperationTrace()
                inputTrace?.event("input.down")
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (inputTrace != null) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (event.x < -slop || event.y < -slop || event.x >= width + slop || event.y >= height + slop) {
                        inputTrace?.event("input.rejected")
                        inputTrace = null
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                inputTrace?.let { trace ->
                    trace.event("input.up")
                    val queue = queuedInputTraces ?: java.util.ArrayDeque<HabitEditTrace>().also { queuedInputTraces = it }
                    queue.addLast(trace)
                }
                inputTrace = null
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                inputTrace?.event("input.cancelled")
                inputTrace = null
            }
        }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> HabitFrameTiming.mark("habit.input.down", this)
            android.view.MotionEvent.ACTION_UP -> HabitFrameTiming.mark("habit.input.up", this)
        }
        return HabitFrameTiming.measure("habit.checkmark.touch", this) { super.onTouchEvent(event) }
    }

    private fun newOperationTrace(): HabitEditTrace? = try { operationTraceFactory?.invoke(this) } catch (_: Throwable) { null }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resources.getDimensionPixelSize(R.dimen.checkmarkHeight)
        val width = resources.getDimensionPixelSize(R.dimen.checkmarkWidth)
        super.onMeasure(
            width.toMeasureSpec(EXACTLY),
            height.toMeasureSpec(EXACTLY)
        )
    }

    private inner class Drawer {
        private val rect = RectF()
        private val palette = MainTabsThemeBridge.resolve(context)
        private val bgColor = palette.surface
        private val lowContrastColor = MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.55f)
        private val mediumContrastColor = MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.82f)
        private val pNotesIndicator = Paint()

        private val paint = TextPaint().apply {
            typeface = getFontAwesome()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        fun draw(canvas: Canvas) {
            paint.color = when (value) {
                YES_MANUAL, YES_AUTO, SKIP -> color
                NO -> {
                    if (preferences.areQuestionMarksEnabled) {
                        mediumContrastColor
                    } else {
                        lowContrastColor
                    }
                }
                else -> lowContrastColor
            }
            val id = when (value) {
                SKIP -> R.string.fa_skipped
                NO -> R.string.fa_times
                UNKNOWN -> {
                    if (preferences.areQuestionMarksEnabled) {
                        R.string.fa_question
                    } else {
                        R.string.fa_times
                    }
                }
                else -> R.string.fa_check
            }
            paint.textSize = when {
                id == R.string.fa_question -> sp(12.0f)
                value == YES_AUTO -> sp(13.0f)
                else -> sp(14.0f)
            }
            if (value == YES_AUTO) {
                paint.strokeWidth = 5f
                paint.style = Paint.Style.STROKE
            } else {
                paint.strokeWidth = 0f
                paint.style = Paint.Style.FILL
            }

            val label = resources.getString(id)
            val em = paint.measureText("m")

            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            rect.offset(0f, 0.4f * em)
            canvas.drawText(label, rect.centerX(), rect.centerY(), paint)

            if (value == YES_AUTO) {
                paint.color = bgColor
                paint.style = Paint.Style.FILL
                canvas.drawText(label, rect.centerX(), rect.centerY(), paint)
            }

            drawNotesIndicator(
                pNotesIndicator = pNotesIndicator,
                canvas = canvas,
                color = color,
                size = em,
                notes = notes
            )
        }
    }
}
