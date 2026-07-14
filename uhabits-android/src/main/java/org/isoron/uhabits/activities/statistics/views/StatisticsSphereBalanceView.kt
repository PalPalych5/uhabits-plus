package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.isoron.uhabits.R
import kotlin.math.roundToInt

class StatisticsSphereBalanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class SphereItem(
        val name: String,
        val color: Int,
        val valueText: String,
        val progress: Double
    )

    init {
        orientation = VERTICAL
        val p = dp(12f).toInt()
        setPadding(p, p, p, p)
    }

    fun setData(items: List<SphereItem>, dividerColor: Int, onSurface: Int) {
        removeAllViews()
        if (items.isEmpty()) return

        items.forEachIndexed { index, item ->
            if (index > 0) {
                // Add spacing
                val spacer = View(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(8f).toInt())
                }
                addView(spacer)
            }

            val rowContainer = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }

            val header = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }

            val dot = View(context).apply {
                val size = dp(8f).toInt()
                layoutParams = LayoutParams(size, size).apply {
                    rightMargin = dp(8f).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.color)
                }
            }

            val nameView = TextView(context).apply {
                text = item.name
                textSize = 14f
                setTextColor(onSurface)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }

            val valueView = TextView(context).apply {
                text = item.valueText
                textSize = 14f
                setTextColor(onSurface)
            }

            header.addView(dot)
            header.addView(nameView)
            header.addView(valueView)

            val progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (item.progress.coerceIn(0.0, 1.0) * 1000).toInt()
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(4f).toInt()).apply {
                    topMargin = dp(6f).toInt()
                }
                progressDrawable = GradientDrawable().apply {
                    setColor(item.color)
                    cornerRadius = dp(2f)
                }.let { filled ->
                    val track = GradientDrawable().apply {
                        setColor(dividerColor)
                        cornerRadius = dp(2f)
                    }
                    val ld = LayerDrawable(arrayOf(track, filled))
                    ld.setId(0, android.R.id.background)
                    ld.setId(1, android.R.id.progress)
                    ld
                }
            }

            rowContainer.addView(header)
            rowContainer.addView(progressBar)
            addView(rowContainer)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
