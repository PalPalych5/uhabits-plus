/*
 * Copyright (C) 2016-2026 Alor & Pavel
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.google.android.material.tabs.TabLayout
import kotlin.math.abs

class StatisticsSwipeTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TabLayout(context, attrs, defStyleAttr) {

    var onHorizontalSwipe: ((Int) -> Unit)? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > abs(dy) && abs(dx) > 80 && abs(velocityX) > 200) {
                if (dx > 0) {
                    onHorizontalSwipe?.invoke(-1)
                } else {
                    onHorizontalSwipe?.invoke(1)
                }
                return true
            }
            return false
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return detector.onTouchEvent(ev) || super.onTouchEvent(ev)
    }
}
