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

package org.isoron.uhabits.activities.habits.list

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.Menu
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import me.tatarka.inject.annotations.Inject
import nl.dionsegijn.konfetti.xml.KonfettiView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.common.views.ScrollableChart
import org.isoron.uhabits.activities.habits.list.views.EmptyListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.HabitCardListView
import org.isoron.uhabits.activities.habits.list.views.HabitCardListViewFactory
import org.isoron.uhabits.activities.habits.list.views.HeaderView
import org.isoron.uhabits.activities.habits.list.views.HintView
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.screens.habits.list.HintListFactory
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope
import org.isoron.uhabits.utils.addAtBottom
import org.isoron.uhabits.utils.addAtTop
import org.isoron.uhabits.utils.addBelow
import org.isoron.uhabits.utils.buildToolbar
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dim
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.setupToolbar
import org.isoron.uhabits.utils.sres
import kotlin.math.max
import kotlin.math.min

const val MAX_CHECKMARK_COUNT = 60

@Inject
@ActivityScope
class ListHabitsRootView(
    @ActivityContext context: Context,
    hintListFactory: HintListFactory,
    preferences: Preferences,
    midnightTimer: MidnightTimer,
    runner: TaskRunner,
    private val listAdapter: HabitCardListAdapter,
    habitCardListViewFactory: HabitCardListViewFactory
) : FrameLayout(context), ModelObservable.Listener {
    private val palette get() = MainTabsThemeBridge.resolve(context)

    val listView: HabitCardListView = habitCardListViewFactory.create()
    val llEmpty = EmptyListView(context)
    val tbar = buildToolbar()
    val konfettiView = KonfettiView(context).apply {
        translationZ = 10f
    }
    val hintView: HintView
    val header = HeaderView(context, preferences, midnightTimer)
    private val mainLayout: RelativeLayout
    private var containerHomeView: View? = null
    private val loadingLabel = android.widget.TextView(context).apply {
        setTextColor(palette.onSurfaceVariant)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
    }
    private val loadingRetry = android.widget.Button(context).apply {
        setText(R.string.habits_loading_retry)
        setOnClickListener { listAdapter.refresh(); updateEmptyView() }
    }
    private val loadingState = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        addView(loadingLabel, MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(loadingRetry)
    }
    private var failureNotified = false

    init {
        val hints = resources.getStringArray(R.array.hints)
        val hintList = hintListFactory.create(hints)
        hintView = HintView(context, hintList)

        mainLayout = RelativeLayout(context).apply {
            setBackgroundColor(palette.background)
            addAtTop(konfettiView)
            addAtTop(tbar)
            addBelow(header, tbar)
            addBelow(listView, header, height = MATCH_PARENT) {
                it.topMargin = dp(3f).toInt()
            }
            addBelow(llEmpty, header, height = MATCH_PARENT) {
                it.topMargin = dp(3f).toInt()
            }
            addBelow(loadingState, header, height = MATCH_PARENT)
            addAtBottom(hintView)
        }
        mainLayout.setupToolbar(
            toolbar = tbar,
            title = "",
            color = PaletteColor(17),
            displayHomeAsUpEnabled = false,
            theme = currentTheme()
        )
        tbar.background = ColorDrawable(palette.background)
        tbar.setTitleTextColor(palette.onSurface)
        (context as? AppCompatActivity)?.window?.statusBarColor = palette.background
        applyToolbarIconTint()
        addView(mainLayout, MATCH_PARENT, MATCH_PARENT)
        listAdapter.setListView(listView)
    }

    fun setContainerHomeView(view: View) {
        containerHomeView?.let { mainLayout.removeView(it) }
        containerHomeView = view
        mainLayout.addBelow(view, tbar, height = MATCH_PARENT)
    }

    fun showContainerHomeView(show: Boolean) {
        if (show) {
            header.visibility = GONE
            listView.visibility = GONE
            llEmpty.visibility = GONE
            loadingState.visibility = GONE
            hintView.setSuppressed(true)
            containerHomeView?.visibility = VISIBLE
        } else {
            header.visibility = VISIBLE
            listView.visibility = VISIBLE
            hintView.setSuppressed(false)
            containerHomeView?.visibility = GONE
            updateEmptyView()
        }
    }

    override fun onModelChange() {
        updateEmptyView()
    }

    private fun setupControllers() {
        header.setScrollController(
            object : ScrollableChart.ScrollController {
                override fun onDataOffsetChanged(newDataOffset: Int) {
                    listView.dataOffset = newDataOffset
                }
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupControllers()
        listAdapter.observable.addListener(this)
    }

    override fun onDetachedFromWindow() {
        listAdapter.observable.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val count = getCheckmarkCount()
        header.buttonCount = count
        header.setMaxDataOffset(max(MAX_CHECKMARK_COUNT - count, 0))
        listView.checkmarkCount = count
        super.onSizeChanged(w, h, oldw, oldh)
    }

    private fun getCheckmarkCount(): Int {
        val nameWidth = dim(R.dimen.habitNameWidth)
        val buttonWidth = dim(R.dimen.checkmarkWidth)
        val labelWidth = max((measuredWidth / 3).toFloat(), nameWidth)
        val buttonCount = ((measuredWidth - labelWidth) / buttonWidth).toInt()
        return min(MAX_CHECKMARK_COUNT, max(0, buttonCount))
    }

    private fun updateEmptyView() {
        loadingState.visibility = GONE
        if (containerHomeView?.visibility == VISIBLE) {
            llEmpty.hide()
            return
        }
        if (!listAdapter.hasLoaded) {
            llEmpty.hide()
            loadingState.visibility = VISIBLE
            loadingRetry.visibility = if (listAdapter.lastRefreshFailed) VISIBLE else GONE
            loadingLabel.setText(if (listAdapter.lastRefreshFailed) R.string.habits_loading_error else R.string.habits_loading)
        } else if (listAdapter.itemCount == 0) {
            if (listAdapter.isSearching()) {
                llEmpty.showNotFound()
            } else if (listAdapter.hasNoHabit()) {
                llEmpty.showEmpty()
            } else {
                llEmpty.showDone()
            }
        } else {
            llEmpty.hide()
        }
        if (!listAdapter.lastRefreshFailed) failureNotified = false
        if (listAdapter.hasLoaded && listAdapter.lastRefreshFailed && !failureNotified) {
            failureNotified = true
            android.widget.Toast.makeText(context, R.string.habits_loading_error, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun setScreenTitle(title: String) {
        tbar.title = title
    }

    fun applyToolbarIconTint(menu: Menu? = null) {
        tbar.navigationIcon?.mutate()?.let { DrawableCompat.setTint(it, palette.onSurface) }
        tbar.overflowIcon?.mutate()?.let { DrawableCompat.setTint(it, palette.onSurface) }
        menu?.let {
            for (index in 0 until it.size()) {
                val item = it.getItem(index)
                item.icon?.mutate()?.let { icon ->
                    DrawableCompat.setTint(icon, palette.onSurface)
                }
                val spinner = item.actionView?.findViewById<ProgressBar>(R.id.syncProgress)
                spinner?.indeterminateTintList = ColorStateList.valueOf(palette.onSurface)
                spinner?.indeterminateDrawable?.mutate()?.let { drawable ->
                    DrawableCompat.setTint(drawable, palette.onSurface)
                }
            }
        }
    }
}
