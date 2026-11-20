/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.list

import android.content.Context
import android.graphics.PointF
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsControllerCompat
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.habits.list.views.HabitAppearanceState
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.habits.list.views.HabitCardListController
import org.isoron.uhabits.activities.habits.list.views.HabitCelebrationState
import org.isoron.uhabits.activities.habits.list.views.HabitListDisplayModeState
import org.isoron.uhabits.activities.habits.list.views.HabitListRowUi
import org.isoron.uhabits.activities.habits.list.views.HabitListScreenUiState
import org.isoron.uhabits.activities.habits.list.views.HabitRowIntValues
import org.isoron.uhabits.activities.habits.list.views.HabitWorkspaceScreenCompose
import org.isoron.uhabits.activities.habits.list.views.resolveGroupUiInfo
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.core.ui.screens.habits.list.HintList
import org.isoron.uhabits.core.ui.screens.habits.list.HintListFactory
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope
import org.isoron.uhabits.utils.HabitsStartupTrace
import org.isoron.uhabits.utils.currentTheme

const val MAX_CHECKMARK_COUNT = 60

internal fun mapWindowOffsetToLocalOffset(
    windowOffset: Offset,
    hostOriginInWindow: Offset
): Offset {
    if (windowOffset == Offset.Zero) return Offset.Zero
    return Offset(
        x = windowOffset.x - hostOriginInWindow.x,
        y = windowOffset.y - hostOriginInWindow.y
    )
}

internal fun resolveHabitListDisplayModeState(
    hasLoaded: Boolean,
    lastRefreshFailed: Boolean,
    visibleCount: Int,
    isSearching: Boolean,
    hasNoHabit: Boolean
): HabitListDisplayModeState = when {
    !hasLoaded -> if (lastRefreshFailed) HabitListDisplayModeState.ERROR else HabitListDisplayModeState.LOADING
    visibleCount == 0 -> when {
        isSearching -> HabitListDisplayModeState.EMPTY_SEARCH
        hasNoHabit -> HabitListDisplayModeState.EMPTY_NO_HABITS
        else -> HabitListDisplayModeState.EMPTY_ALL_DONE
    }
    else -> HabitListDisplayModeState.CONTENT
}

@Inject
@ActivityScope
class ListHabitsRootView(
    @ActivityContext context: Context,
    hintListFactory: HintListFactory,
    private val preferences: Preferences,
    private val midnightTimer: MidnightTimer,
    private val listAdapter: HabitCardListAdapter,
    private val listController: Lazy<HabitCardListController>,
    private val behavior: ListHabitsBehavior
) : FrameLayout(context),
    ModelObservable.Listener,
    Preferences.Listener,
    MidnightTimer.MidnightListener {

    private val palette get() = MainTabsThemeBridge.resolve(context)

    val celebrationState = HabitCelebrationState()

    private val hintList: HintList = hintListFactory.create(resources.getStringArray(R.array.hints))
    private val composeView: ComposeView
    private var failureNotified = false
    private var startupLayoutTraced = false
    private var hostOriginInWindow: Offset? = null

    private var uiRevision by mutableLongStateOf(0L)
    var dataOffset by mutableIntStateOf(0)
    private var activeHint by mutableStateOf<String?>(null)
    private var composeColors by mutableStateOf(UHabitComposeColors.fromPalette(palette))
    private var screenUiState by mutableStateOf(buildUiState())
    private var selectionMenu by mutableStateOf<ListHabitsSelectionMenu?>(null)
    private var toolbarState by mutableStateOf(HabitToolbarState())
    var onToolbarAction: (Int) -> Unit = {}
    var onToolbarBack: () -> Unit = {}
    var onToolbarQuery: (String) -> Unit = {}

    init {
        composeView = ComposeView(context).apply {
            id = R.id.habitCardList
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = composeColors
                UHabitComposeTheme(colors = colors) {
                    HabitWorkspaceScreenCompose(
                        toolbarState = toolbarState,
                        selectionMenu = selectionMenu,
                        listState = screenUiState,
                        dataOffset = dataOffset,
                        celebrationState = celebrationState,
                        onHostOriginChanged = { origin -> hostOriginInWindow = origin },
                        onToolbarAction = onToolbarAction,
                        onToolbarBack = onToolbarBack,
                        onToolbarQuery = { query ->
                            toolbarState = toolbarState.copy(query = query)
                            onToolbarQuery(query)
                        },
                        onDataOffsetChanged = { newOffset -> dataOffset = newOffset },
                        onRetryRefresh = {
                            listAdapter.refresh()
                            publishUiState()
                        },
                        onRowClick = { pos -> listController.value.onItemClick(pos) },
                        onRowLongClick = { pos -> listController.value.onItemLongClick(pos) },
                        onMoveRow = { from, to -> listController.value.drop(from, to) },
                        onCheckmarkToggle = { habit, date, nextValue, notes, windowOffset ->
                            val pt = toKonfettiCoords(windowOffset)
                            behavior.onToggle(habit, date, nextValue, notes, pt.x, pt.y)
                        },
                        onHabitDateEdit = { habit, date, windowOffset ->
                            val pt = toKonfettiCoords(windowOffset)
                            behavior.onEdit(habit, date, pt.x, pt.y)
                        },
                        onDismissHint = { activeHint = null },
                        onMeaningfulContentDrawn = {
                            if (HabitsStartupTrace.enabled && !startupLayoutTraced) {
                                startupLayoutTraced = true
                                HabitsStartupTrace.mark("list.layout.ready")
                                HabitsStartupTrace.mark("list.meaningful.draw")
                                if (android.os.Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated) {
                                    viewTreeObserver.registerFrameCommitCallback {
                                        HabitsStartupTrace.mark("list.frame.commit")
                                    }
                                }
                            }
                        },
                        colors = colors
                    )
                }
            }
        }

        applySystemBarsAppearance(palette)
        addView(composeView, MATCH_PARENT, MATCH_PARENT)
        listAdapter.setListView(null)
    }

    fun bindSelectionMenu(menu: ListHabitsSelectionMenu) {
        selectionMenu = menu
    }

    internal fun updateComposeToolbar(state: HabitToolbarState) { toolbarState = state }

    private fun toKonfettiCoords(windowOffset: Offset): PointF {
        if (windowOffset == Offset.Zero) return PointF(0f, 0f)
        val origin = hostOriginInWindow ?: IntArray(2).let { coords ->
            composeView.getLocationInWindow(coords)
            Offset(coords[0].toFloat(), coords[1].toFloat())
        }
        val local = mapWindowOffsetToLocalOffset(windowOffset, origin)
        return PointF(local.x, local.y)
    }

    fun startCelebration(x: Float, y: Float, colors: List<androidx.compose.ui.graphics.Color>) {
        celebrationState.burst(x, y, colors)
    }

    fun refreshDisplayState() {
        publishUiState()
    }

    override fun onModelChange() {
        publishUiState()
    }

    override fun atMidnight() {
        post { publishUiState() }
    }

    override fun onCheckmarkSequenceChanged() {
        post { publishUiState() }
    }

    override fun onQuestionMarksChanged() {
        post { publishUiState() }
    }

    override fun onCardCornersChanged() {
        post { publishUiState() }
    }

    override fun onHabitListAppearanceChanged() {
        post { publishUiState() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startupLayoutTraced = false
        listAdapter.observable.addListener(this)
        preferences.addListener(this)
        midnightTimer.addListener(this)
        listAdapter.onAttached()
        maybeShowHint()
        publishUiState()
    }

    override fun onDetachedFromWindow() {
        listAdapter.onDetached()
        midnightTimer.removeListener(this)
        preferences.removeListener(this)
        listAdapter.observable.removeListener(this)
        super.onDetachedFromWindow()
    }

    private fun maybeShowHint() {
        if (!activeHint.isNullOrBlank()) return
        if (!hintList.shouldShow()) return
        val hint = hintList.pop()?.takeIf { it.isNotBlank() } ?: return
        activeHint = hint
    }

    private fun publishUiState() {
        val currentPalette = palette
        applySystemBarsAppearance(currentPalette)
        composeColors = UHabitComposeColors.fromPalette(currentPalette)
        screenUiState = buildUiState()

        if (!listAdapter.lastRefreshFailed) {
            failureNotified = false
        } else if (listAdapter.hasLoaded && !failureNotified) {
            failureNotified = true
            android.widget.Toast.makeText(
                context,
                R.string.habits_loading_error,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildUiState(): HabitListScreenUiState {
        val today = getToday()
        val theme = currentTheme()
        val count = listAdapter.itemCount

        val displayState = resolveHabitListDisplayModeState(
            hasLoaded = listAdapter.hasLoaded,
            lastRefreshFailed = listAdapter.lastRefreshFailed,
            visibleCount = count,
            isSearching = listAdapter.isSearching(),
            hasNoHabit = listAdapter.hasNoHabit()
        )

        val metadataList = ArrayList<HabitCardListCache.RowMetadata>(count)
        val habits = ArrayList<org.isoron.uhabits.core.models.Habit>(count)
        for (i in 0 until count) {
            val habit = listAdapter.getItem(i)
            val id = habit?.id
            if (habit != null && id != null) {
                habits.add(habit)
                metadataList.add(listAdapter.getMetadata(i) ?: HabitCardListCache.RowMetadata(null, null, org.isoron.uhabits.core.models.DayTier.NORMAL, null))
            }
        }

        val grouping = resolveGroupUiInfo(
            metadataList = metadataList,
            primaryOrder = listAdapter.primaryOrder,
            spheresEnabled = preferences.isHabitSpheresEnabled,
            showSphereNames = preferences.isShowSphereNamesEnabled,
            dayTiersEnabled = preferences.isDayTiersEnabled,
            groupSeparatorsEnabled = preferences.areHabitGroupSeparatorsEnabled,
            unassignedHeading = context.getString(R.string.habit_block_unassigned)
        )

        val selectedSet = listAdapter.selected.mapNotNull { it.id }.toSet()
        val rows = habits.mapIndexed { idx, habit ->
            val id = requireNotNull(habit.id)
            val meta = metadataList[idx]
            val group = grouping[idx]
            val todayGoal = habit.goalAt(today)
            val goalSignature = "${todayGoal.targetValue}|${todayGoal.targetType}|${todayGoal.unit}|${todayGoal.frequency.denominator}"
            HabitListRowUi(
                position = idx,
                habitId = id,
                name = habit.name,
                activeColorArgb = theme.color(habit.color).toInt(),
                isNumerical = habit.isNumerical,
                score = listAdapter.getScore(id),
                values = HabitRowIntValues(listAdapter.getCheckmarks(id)),
                notes = listAdapter.getNotes(id).toList(),
                effectiveSnapshot = listAdapter.getEffectiveSnapshot(id),
                iconRef = meta.iconRef,
                isSelected = selectedSet.contains(id),
                groupHeading = group.heading,
                showTopGap = group.showTopGap,
                goalSignature = goalSignature,
                habit = habit
            )
        }

        val appearance = HabitAppearanceState(
            isClassic = preferences.isClassicSectionLayout,
            showCardBorders = preferences.showHabitCardBorders,
            cardCornerRadiusDp = preferences.habitsCardCornerRadius,
            showScoreRings = preferences.showScoreRings,
            showHabitIcons = preferences.showHabitIcons,
            areQuestionMarksEnabled = preferences.areQuestionMarksEnabled,
            isShortToggleEnabled = preferences.isShortToggleEnabled,
            isSkipEnabled = preferences.isSkipEnabled,
            isCheckmarkSequenceReversed = preferences.isCheckmarkSequenceReversed,
            primaryOrder = listAdapter.primaryOrder,
            isSortable = listAdapter.primaryOrder == HabitList.Order.BY_POSITION,
            isDayTiersEnabled = preferences.isDayTiersEnabled,
            isHabitSpheresEnabled = preferences.isHabitSpheresEnabled,
            isShowSphereNamesEnabled = preferences.isShowSphereNamesEnabled,
            areHabitGroupSeparatorsEnabled = preferences.areHabitGroupSeparatorsEnabled
        )

        uiRevision++
        return HabitListScreenUiState(
            today = today,
            displayState = displayState,
            rows = rows,
            appearance = appearance,
            isSelectionActive = listAdapter.selected.isNotEmpty(),
            activeHint = activeHint,
            listRevision = uiRevision
        )
    }

    private var lastAppliedBackground: Int? = null

    private fun applySystemBarsAppearance(currentPalette: SettingsThemePalette = palette) {
        if (lastAppliedBackground != currentPalette.background) {
            lastAppliedBackground = currentPalette.background
            setBackgroundColor(currentPalette.background)
        }
        (context as? AppCompatActivity)?.window?.let { window ->
            window.statusBarColor = currentPalette.background
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !currentPalette.isDark
        }
    }
}
