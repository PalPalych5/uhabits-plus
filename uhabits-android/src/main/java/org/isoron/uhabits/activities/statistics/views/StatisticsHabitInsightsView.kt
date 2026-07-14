package org.isoron.uhabits.activities.statistics.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitResult
import org.isoron.uhabits.intents.IntentFactory
import kotlin.math.roundToInt

class StatisticsHabitInsightsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val tabLayout: TabLayout
    private val rowsContainer: LinearLayout

    private var strongHabits: List<StatisticsHabitResult> = emptyList()
    private var attentionHabits: List<StatisticsHabitResult> = emptyList()

    private var accentColor = 0
    private var onSurface = 0
    private var onSurfaceVariant = 0
    private var divider = 0
    private var themeColorResolver: ((Int) -> Int)? = null

    init {
        orientation = VERTICAL
        val p = dp(12f).toInt()
        setPadding(p, p, p, p)

        tabLayout = TabLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addTab(newTab().setText(context.getString(R.string.statistics_insights_strong)))
            addTab(newTab().setText(context.getString(R.string.statistics_insights_attention)))
            setSelectedTabIndicatorColor(MainTabsThemeBridge.resolve(context).accent)
            setTabTextColors(MainTabsThemeBridge.resolve(context).onSurfaceVariant, MainTabsThemeBridge.resolve(context).onSurface)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        rowsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f).toInt()
            }
        }

        addView(tabLayout)
        addView(rowsContainer)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                renderRows()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    fun setData(
        strong: List<StatisticsHabitResult>,
        attention: List<StatisticsHabitResult>,
        accentColor: Int,
        onSurface: Int,
        onSurfaceVariant: Int,
        divider: Int,
        themeColorResolver: (Int) -> Int
    ) {
        this.strongHabits = strong
        this.attentionHabits = attention
        this.accentColor = accentColor
        this.onSurface = onSurface
        this.onSurfaceVariant = onSurfaceVariant
        this.divider = divider
        this.themeColorResolver = themeColorResolver

        renderRows()
    }

    private fun renderRows() {
        rowsContainer.removeAllViews()
        val showStrong = tabLayout.selectedTabPosition == 0
        val targetList = if (showStrong) strongHabits else attentionHabits

        if (targetList.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = context.getString(if (showStrong) R.string.statistics_insights_no_strong else R.string.statistics_insights_no_attention)
                textSize = 14f
                setTextColor(onSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(0, dp(16f).toInt(), 0, dp(16f).toInt())
            }
            rowsContainer.addView(emptyText)
            return
        }

        targetList.forEachIndexed { index, item ->
            if (index > 0) {
                val line = View(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(0.75f).toInt())
                    setBackgroundColor(divider)
                }
                rowsContainer.addView(line)
            }

            val row = createHabitRow(item)
            rowsContainer.addView(row)
        }
    }

    private fun createHabitRow(result: StatisticsHabitResult): View {
        val habitColor = themeColorResolver?.invoke(result.habit.color.paletteIndex) ?: accentColor

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f).toInt(), 0, dp(10f).toInt())
            isClickable = true
            isFocusable = true
            val out = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
            setOnClickListener {
                context.startActivity(IntentFactory().startShowHabitActivity(context, result.habit))
            }
        }

        // Color dot
        val dot = View(context).apply {
            val size = dp(8f).toInt()
            layoutParams = LayoutParams(size, size).apply {
                rightMargin = dp(10f).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(habitColor)
            }
        }

        // Titles
        val titles = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(context).apply {
            text = result.habit.name
            textSize = 14f
            setTextColor(onSurface)
        }
        val insightText = generateHabitInsightText(result.habit)
        val subtitleView = TextView(context).apply {
            text = insightText
            textSize = 12f
            setTextColor(onSurfaceVariant)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f).toInt()
            }
        }
        titles.addView(nameView)
        titles.addView(subtitleView)

        // Sparkline
        val sparkline = SparklineView(context).apply {
            layoutParams = LayoutParams(dp(50f).toInt(), dp(20f).toInt()).apply {
                rightMargin = dp(12f).toInt()
            }
            setHistory(getHabitHistoryData(result.habit), habitColor)
        }

        // Percentage
        val percentView = TextView(context).apply {
            text = "${(result.progress * 100).roundToInt()}%"
            textSize = 14f
            setTextColor(onSurface)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        row.addView(dot)
        row.addView(titles)
        row.addView(sparkline)
        row.addView(percentView)
        return row
    }

    private fun getHabitHistoryData(habit: Habit): List<Float> {
        val today = getToday()
        val data = mutableListOf<Float>()
        for (i in 9 downTo 0) {
            val entry = habit.computedEntries.get(today.minus(i))
            val value = when {
                entry.value == Entry.SKIP || entry.value == Entry.UNKNOWN -> 0f
                habit.isNumerical -> (entry.value / 1000.0 / habit.targetValue.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                else -> if (entry.value == Entry.YES_MANUAL || entry.value == Entry.YES_AUTO) 1f else 0f
            }
            data.add(value)
        }
        return data
    }

    private fun generateHabitInsightText(habit: Habit): String {
        val today = getToday()
        
        // 1. Check for consecutive skips
        var skips = 0
        for (i in 0..9) {
            val entry = habit.computedEntries.get(today.minus(i))
            if (entry.value == Entry.NO) {
                skips++
            } else if (entry.value == Entry.YES_MANUAL || entry.value == Entry.YES_AUTO) {
                break
            }
        }
        if (skips >= 3) {
            return context.getString(R.string.statistics_insight_consecutive_skips, skips)
        }

        // 2. Check for current streak
        val currentStreak = habit.streaks.getLatest()?.length ?: 0
        if (currentStreak >= 5) {
            return context.getString(R.string.statistics_insight_streak, currentStreak)
        }

        // 3. Fallback to weekly drops/rhythm (or default label)
        return context.getString(R.string.statistics_insight_stable)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private class SparklineView(context: Context) : View(context) {
        private var points: List<Float> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun setHistory(history: List<Float>, color: Int) {
            this.points = history
            this.paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (points.size < 2) return

            val w = width.toFloat()
            val h = height.toFloat()
            val step = w / (points.size - 1)

            path.reset()
            points.forEachIndexed { i, value ->
                val x = i * step
                val y = h - (value * (h - 4f)) - 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }
}
