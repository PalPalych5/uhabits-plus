package org.isoron.uhabits.activities.habits.show.views

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
import org.isoron.uhabits.utils.StyledResources
import kotlin.math.abs

class MinuteWheelView(context: Context) : RecyclerView(context) {
    private val wheelLayoutManager = LinearLayoutManager(context, VERTICAL, false)
    private val wheelAdapter = MinuteAdapter()
    private val snapHelper = LinearSnapHelper()
    private var minimum = 1
    private var selectedMinute = 1

    var onMinuteChanged: ((Int) -> Unit)? = null

    init {
        layoutManager = wheelLayoutManager
        adapter = wheelAdapter
        itemAnimator = null
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        val itemHeight = dp(44f)
        setPadding(0, itemHeight, 0, itemHeight)
        snapHelper.attachToRecyclerView(this)
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateItemAppearance()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != SCROLL_STATE_IDLE) return
                val snapped = snapHelper.findSnapView(wheelLayoutManager) ?: return
                val position = wheelLayoutManager.getPosition(snapped)
                if (position == NO_POSITION) return
                val minute = minimum + position
                updateItemAppearance()
                if (minute != selectedMinute) {
                    selectedMinute = minute
                    onMinuteChanged?.invoke(minute)
                }
            }
        })
    }

    fun setRange(minimum: Int, maximum: Int, selected: Int) {
        require(minimum <= maximum)
        this.minimum = minimum
        selectedMinute = selected.coerceIn(minimum, maximum)
        wheelAdapter.setRange(minimum, maximum)
        post {
            // LinearLayoutManager already positions offset zero after the
            // RecyclerView's start padding. Adding paddingTop here would move
            // the selected value down by one row.
            wheelLayoutManager.scrollToPositionWithOffset(selectedMinute - minimum, 0)
            updateItemAppearance()
        }
    }

    private fun updateItemAppearance() {
        val center = height / 2f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val distance = abs((child.top + child.bottom) / 2f - center)
            val progress = (distance / dp(44f)).coerceIn(0f, 1f)
            val scale = 1f - 0.22f * progress
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = 1f - 0.62f * progress
        }
    }

    private inner class MinuteAdapter : Adapter<MinuteViewHolder>() {
        private var values = IntRange.EMPTY

        fun setRange(minimum: Int, maximum: Int) {
            values = minimum..maximum
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MinuteViewHolder {
            val textView = TextView(parent.context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44f))
                gravity = Gravity.CENTER
                textSize = 28f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(StyledResources(context).getColor(R.attr.contrast100))
            }
            return MinuteViewHolder(textView)
        }

        override fun onBindViewHolder(holder: MinuteViewHolder, position: Int) {
            holder.textView.text = context.getString(
                R.string.pomodoro_minutes_short,
                values.first + position
            )
        }

        override fun getItemCount(): Int = values.count()
    }

    private class MinuteViewHolder(val textView: TextView) : ViewHolder(textView)

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()
}
