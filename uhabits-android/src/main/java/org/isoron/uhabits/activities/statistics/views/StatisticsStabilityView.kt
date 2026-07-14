package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.isoron.uhabits.R
import kotlin.math.roundToInt

class StatisticsStabilityView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val streaksContainer: LinearLayout
    private val distributionBar: View
    private val legendContainer: LinearLayout

    private var perfectCount = 0
    private var partialCount = 0
    private var failedCount = 0

    private var perfectColor = 0
    private var partialColor = 0
    private var failedColor = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    init {
        orientation = VERTICAL
        val p = dp(12f).toInt()
        setPadding(p, p, p, p)

        streaksContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            weightSum = 2f
        }

        distributionBar = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                drawDistribution(canvas)
            }
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(12f).toInt()).apply {
                topMargin = dp(16f).toInt()
            }
        }

        legendContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f).toInt()
            }
        }

        addView(streaksContainer)
        addView(distributionBar)
        addView(legendContainer)
    }

    fun setData(
        currentStreak: Int,
        bestStreak: Int,
        perfect: Int,
        partial: Int,
        failed: Int,
        perfectCol: Int,
        partialCol: Int,
        failedCol: Int,
        onSurface: Int,
        onSurfaceVariant: Int
    ) {
        this.perfectCount = perfect
        this.partialCount = partial
        this.failedCount = failed
        this.perfectColor = perfectCol
        this.partialColor = partialCol
        this.failedColor = failedCol

        streaksContainer.removeAllViews()
        streaksContainer.addView(createStreakItem(context.getString(R.string.latest_streak), if (currentStreak > 0) "$currentStreak ${context.getString(R.string.reports_days_unit)}" else "—", onSurface, onSurfaceVariant))
        streaksContainer.addView(createStreakItem(context.getString(R.string.reports_metric_best_streak), if (bestStreak > 0) "$bestStreak ${context.getString(R.string.reports_days_unit)}" else "—", onSurface, onSurfaceVariant))

        legendContainer.removeAllViews()
        val total = perfect + partial + failed
        if (total > 0) {
            addLegendItem(context.getString(R.string.statistics_stability_perfect), perfect, perfectColor, onSurfaceVariant)
            addLegendItem(context.getString(R.string.statistics_stability_partial), partial, partialColor, onSurfaceVariant)
            addLegendItem(context.getString(R.string.statistics_stability_failed), failed, failedColor, onSurfaceVariant)
        }

        distributionBar.invalidate()
    }

    private fun createStreakItem(title: String, value: String, onSurface: Int, onSurfaceVariant: Int): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = title
                textSize = 12f
                setTextColor(onSurfaceVariant)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 16f
                setTextColor(onSurface)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2f).toInt()
                }
            })
        }
    }

    private fun addLegendItem(label: String, count: Int, color: Int, onSurfaceVariant: Int) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = View(context).apply {
            val size = dp(8f).toInt()
            layoutParams = LayoutParams(size, size).apply {
                rightMargin = dp(4f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        val textView = TextView(context).apply {
            text = "$label: $count"
            textSize = 11f
            setTextColor(onSurfaceVariant)
        }
        row.addView(dot)
        row.addView(textView)
        legendContainer.addView(row)
    }

    private fun drawDistribution(canvas: Canvas) {
        val total = perfectCount + partialCount + failedCount
        if (total == 0) return

        val w = distributionBar.width.toFloat()
        val h = distributionBar.height.toFloat()
        val r = h / 2f

        val perfectW = (perfectCount.toFloat() / total) * w
        val partialW = (partialCount.toFloat() / total) * w

        // Draw perfect rect
        if (perfectW > 0f) {
            barPaint.color = perfectColor
            rect.set(0f, 0f, perfectW, h)
            canvas.drawRoundRect(rect, r, r, barPaint)
        }

        // Draw partial rect
        if (partialW > 0f) {
            barPaint.color = partialColor
            rect.set(perfectW, 0f, perfectW + partialW, h)
            canvas.drawRoundRect(rect, r, r, barPaint)
        }

        // Draw failed rect
        val failedW = w - perfectW - partialW
        if (failedW > 0f) {
            barPaint.color = failedColor
            rect.set(perfectW + partialW, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, barPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
