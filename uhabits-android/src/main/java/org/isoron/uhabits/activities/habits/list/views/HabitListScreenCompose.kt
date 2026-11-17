/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.habits.list.views

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.text.TextPaint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.MAX_CHECKMARK_COUNT
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitElevationAndBorders
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.utils.HabitQuantityFormatter
import org.isoron.uhabits.utils.InterfaceUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Jetpack Compose rendering of the primary Habits list body:
 * - Horizontally scrollable day header synced to [dataOffset]
 * - Virtualized [LazyColumn] of habit rows (Cards / Classic, Light / Dark / AMOLED)
 * - Long-press selection and manual drag-to-reorder in [HabitList.Order.BY_POSITION]
 * - Checkmark and numerical action cells with notes dot, lower-bound badges, and confetti anchors
 * - Loading, error/retry, empty states, and dismissible bottom hint banner
 */
@Composable
fun HabitListScreenCompose(
    state: HabitListScreenUiState,
    dataOffset: Int,
    onDataOffsetChanged: (Int) -> Unit,
    onRetryRefresh: () -> Unit,
    onRowClick: (Int) -> Unit,
    onRowLongClick: (Int) -> Unit,
    onMoveRow: (Int, Int) -> Unit,
    onCheckmarkToggle: (Habit, LocalDate, Int, String, Offset) -> Unit,
    onHabitDateEdit: (Habit, LocalDate, Offset) -> Unit,
    onDismissHint: () -> Unit,
    searchActive: Boolean = false,
    celebrationState: HabitCelebrationState? = null,
    onMeaningfulContentDrawn: () -> Unit = {},
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val density = LocalDensity.current
    val compactSearch = searchActive &&
        LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
        WindowInsets.ime.getBottom(density) > 0
    val buttonWidthDp = dimensionResource(R.dimen.checkmarkWidth)
    val buttonHeightDp = dimensionResource(R.dimen.checkmarkHeight)
    val nameWidthDp = dimensionResource(R.dimen.habitNameWidth)

    val buttonWidthPx = with(density) { buttonWidthDp.toPx() }
    val nameWidthPx = with(density) { nameWidthDp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
    ) {
        val availableWidthPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth.toFloat()
        } else {
            with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        }
        val buttonCount = remember(availableWidthPx, nameWidthPx, buttonWidthPx) {
            computeCheckmarkCount(
                availableWidthPx = availableWidthPx,
                nameWidthPx = nameWidthPx,
                buttonWidthPx = buttonWidthPx,
                maxCount = MAX_CHECKMARK_COUNT
            )
        }
        val maxDataOffset = remember(buttonCount) {
            (MAX_CHECKMARK_COUNT - buttonCount).coerceAtLeast(0)
        }
        LaunchedEffect(maxDataOffset, dataOffset) {
            if (dataOffset > maxDataOffset) {
                onDataOffsetChanged(maxDataOffset)
            }
        }

        val currentOffset = dataOffset.coerceIn(0, maxDataOffset)
        val appearance = state.appearance
        val endMarginDp = when {
            appearance.isClassic -> 0.dp
            appearance.showCardBorders -> 4.dp
            else -> 2.dp
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (!compactSearch) HabitDateHeaderStrip(
                today = state.today,
                dataOffset = currentOffset,
                maxDataOffset = maxDataOffset,
                buttonCount = buttonCount,
                buttonWidthDp = buttonWidthDp,
                buttonHeightDp = buttonHeightDp,
                trailingPadding = endMarginDp,
                isReversed = appearance.isCheckmarkSequenceReversed,
                onDataOffsetChanged = onDataOffsetChanged,
                colors = colors
            )

            if (!compactSearch) Spacer(modifier = Modifier.height(3.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.displayState) {
                    HabitListDisplayModeState.LOADING -> {
                        HabitLoadingPane(
                            text = stringResource(R.string.habits_loading),
                            showRetry = false,
                            onRetry = onRetryRefresh,
                            colors = colors
                        )
                    }

                    HabitListDisplayModeState.ERROR -> {
                        HabitLoadingPane(
                            text = stringResource(R.string.habits_loading_error),
                            showRetry = true,
                            onRetry = onRetryRefresh,
                            colors = colors
                        )
                    }

                    HabitListDisplayModeState.EMPTY_NO_HABITS -> {
                        HabitListEmptyState(mode = HabitListEmptyMode.EMPTY, colors = colors)
                    }

                    HabitListDisplayModeState.EMPTY_ALL_DONE -> {
                        HabitListEmptyState(mode = HabitListEmptyMode.ALL_DONE, colors = colors)
                    }

                    HabitListDisplayModeState.EMPTY_SEARCH -> {
                        HabitListEmptyState(mode = HabitListEmptyMode.SEARCH_NOT_FOUND, colors = colors)
                    }

                    HabitListDisplayModeState.CONTENT -> {
                        LaunchedEffect(state.rows.size) {
                            if (state.rows.isNotEmpty()) {
                                onMeaningfulContentDrawn()
                            }
                        }
                        HabitRowsLazyList(
                            state = state,
                            dataOffset = currentOffset,
                            buttonCount = if (compactSearch) 0 else buttonCount,
                            buttonWidthDp = buttonWidthDp,
                            buttonHeightDp = buttonHeightDp,
                            onRowClick = onRowClick,
                            onRowLongClick = onRowLongClick,
                            onMoveRow = onMoveRow,
                            onCheckmarkToggle = onCheckmarkToggle,
                            onHabitDateEdit = onHabitDateEdit,
                            colors = colors
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !state.activeHint.isNullOrBlank(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            state.activeHint?.let { hintText ->
                HabitHintBanner(
                    hintText = hintText,
                    onDismiss = onDismissHint
                )
            }
        }

        if (celebrationState != null) {
            HabitCelebrationOverlay(celebrationState)
        }
    }
}

@Composable
private fun HabitDateHeaderStrip(
    today: LocalDate,
    dataOffset: Int,
    maxDataOffset: Int,
    buttonCount: Int,
    buttonWidthDp: Dp,
    buttonHeightDp: Dp,
    trailingPadding: Dp,
    isReversed: Boolean,
    onDataOffsetChanged: (Int) -> Unit,
    colors: UHabitComposeColors
) {
    val locale = Locale.getDefault()
    val dateFormatter = remember(locale) { JavaLocalDateFormatter(locale) }
    val headerDates = remember(today, dataOffset, buttonCount, isReversed, locale) {
        computeHeaderDates(
            today = today,
            dataOffset = dataOffset,
            buttonCount = buttonCount,
            isReversed = isReversed,
            shortWeekdayProvider = { dateFormatter.shortWeekdayName(it) }
        )
    }

    val density = LocalDensity.current
    val buttonWidthPx = with(density) { buttonWidthDp.toPx().coerceAtLeast(1f) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Mirrors legacy HeaderView + ScrollableChart drag sign:
    // default LTR (!isReversed) places today on the left, so dragging left (deltaX < 0)
    // pulls older days from the right and increments dataOffset.
    val dragSign = remember(isReversed, isRtl) {
        var sign = -1f
        if (isReversed) sign *= -1f
        if (isRtl) sign *= -1f
        sign
    }

    val latestOffset by rememberUpdatedState(dataOffset)
    val latestMaxOffset by rememberUpdatedState(maxDataOffset)
    val latestOnOffsetChanged by rememberUpdatedState(onDataOffsetChanged)
    val scope = rememberCoroutineScope()
    val decaySpec = rememberSplineBasedDecay<Float>()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var scrollPx by remember { mutableFloatStateOf(dataOffset * buttonWidthPx) }

    LaunchedEffect(dataOffset, buttonWidthPx) {
        if (flingJob?.isActive != true) {
            val targetPx = dataOffset * buttonWidthPx
            if (abs(scrollPx - targetPx) > buttonWidthPx) {
                scrollPx = targetPx
            }
        }
    }

    val effectiveFontScale = min(density.fontScale, 1.35f)
    val dayNumberTextStyle = remember(colors.foreground, effectiveFontScale) {
        TextStyle(
            color = Color(colors.foreground),
            fontSize = (12f * (effectiveFontScale / density.fontScale)).sp,
            fontWeight = FontWeight.Bold,
            lineHeight = (14f * (effectiveFontScale / density.fontScale)).sp
        )
    }
    val regularLabelStyle = remember(colors.secondary, effectiveFontScale) {
        TextStyle(
            color = Color(colors.secondary),
            fontSize = (11f * (effectiveFontScale / density.fontScale)).sp,
            fontWeight = FontWeight.Medium,
            lineHeight = (13f * (effectiveFontScale / density.fontScale)).sp
        )
    }
    val todayLabelStyle = remember(colors.foreground, effectiveFontScale) {
        TextStyle(
            color = Color(colors.foreground),
            fontSize = (11f * (effectiveFontScale / density.fontScale)).sp,
            fontWeight = FontWeight.Bold,
            lineHeight = (13f * (effectiveFontScale / density.fontScale)).sp
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(buttonHeightDp)
            .padding(end = trailingPadding)
            .pointerInput(buttonWidthPx, dragSign) {
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        flingJob?.cancel()
                        velocityTracker.resetTracking()
                        scrollPx = latestOffset * buttonWidthPx
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val maxPx = latestMaxOffset * buttonWidthPx
                        scrollPx = (scrollPx + dragAmount * dragSign).coerceIn(0f, maxPx)
                        val newOffset = (scrollPx / buttonWidthPx).roundToInt().coerceIn(0, latestMaxOffset)
                        if (newOffset != latestOffset) {
                            latestOnOffsetChanged(newOffset)
                        }
                    },
                    onDragEnd = {
                        val velocityX = velocityTracker.calculateVelocity().x * dragSign * 0.5f
                        val maxPx = latestMaxOffset * buttonWidthPx
                        val startPx = scrollPx
                        flingJob = scope.launch {
                            val anim = androidx.compose.animation.core.Animatable(startPx)
                            anim.updateBounds(lowerBound = 0f, upperBound = maxPx)
                            anim.animateDecay(velocityX, decaySpec) {
                                scrollPx = value
                                val newOffset = (value / buttonWidthPx).roundToInt().coerceIn(0, latestMaxOffset)
                                if (newOffset != latestOffset) {
                                    latestOnOffsetChanged(newOffset)
                                }
                            }
                        }
                    }
                )
            },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        headerDates.forEach { dayUi ->
            Column(
                modifier = Modifier
                    .width(buttonWidthDp)
                    .height(buttonHeightDp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BasicText(
                    text = dayUi.dayOfWeekLabel,
                    style = if (dayUi.isToday) todayLabelStyle else regularLabelStyle,
                    maxLines = 1,
                    softWrap = false
                )
                BasicText(
                    text = dayUi.dayNumberLabel,
                    style = if (dayUi.isToday) {
                        dayNumberTextStyle
                    } else {
                        regularLabelStyle.copy(color = Color(colors.secondary))
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun HabitRowsLazyList(
    state: HabitListScreenUiState,
    dataOffset: Int,
    buttonCount: Int,
    buttonWidthDp: Dp,
    buttonHeightDp: Dp,
    onRowClick: (Int) -> Unit,
    onRowLongClick: (Int) -> Unit,
    onMoveRow: (Int, Int) -> Unit,
    onCheckmarkToggle: (Habit, LocalDate, Int, String, Offset) -> Unit,
    onHabitDateEdit: (Habit, LocalDate, Offset) -> Unit,
    colors: UHabitComposeColors
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val lazyListState = rememberLazyListState()
    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = navBottomPadding + if (!state.activeHint.isNullOrBlank()) 76.dp else 8.dp

    var draggingHabitId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val rowOrderKey = remember(state.rows) { state.rows.map { it.habitId } }
    var prevOrderKey by remember { mutableStateOf(rowOrderKey) }
    var wasAnchoredAtTop by remember { mutableStateOf(true) }

    LaunchedEffect(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset) {
        if (rowOrderKey == prevOrderKey) {
            wasAnchoredAtTop = lazyListState.firstVisibleItemIndex == 0 &&
                lazyListState.firstVisibleItemScrollOffset < 8
        }
    }

    LaunchedEffect(rowOrderKey) {
        if (rowOrderKey != prevOrderKey) {
            val anchorTop = wasAnchoredAtTop
            prevOrderKey = rowOrderKey
            if (anchorTop && draggingHabitId == null && rowOrderKey.isNotEmpty()) {
                lazyListState.scrollToItem(0)
            }
        }
    }

    val latestRows by rememberUpdatedState(state.rows)
    val latestOnMoveRow by rememberUpdatedState(onMoveRow)
    val latestIsSortable by rememberUpdatedState(state.appearance.isSortable)

    val currentDayOffsets = remember(buttonCount, state.appearance.isCheckmarkSequenceReversed) {
        computeVisibleDayOffsets(
            buttonCount = buttonCount,
            isReversed = state.appearance.isCheckmarkSequenceReversed
        )
    }
    val dayOffsetsState by rememberUpdatedState(currentDayOffsets)
    val dataOffsetState by rememberUpdatedState(dataOffset)

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        itemsIndexed(
            items = state.rows,
            key = { _, row -> row.habitId },
            contentType = { _, row -> if (row.isNumerical) "numerical" else "boolean" }
        ) { index, row ->
            val isDragging = draggingHabitId == row.habitId
            val currentRowIndex by rememberUpdatedState(index)
            val animatePlacement = !reduceMotion && draggingHabitId == null

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (animatePlacement) {
                            mod.animateItem(
                                fadeInSpec = tween(durationMillis = 160),
                                placementSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                fadeOutSpec = tween(durationMillis = 160)
                            )
                        } else {
                            mod
                        }
                    }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationY = dragOffsetY
                        }
                    }
            ) {
                if (row.groupHeading != null) {
                    HabitSectionHeader(
                        title = row.groupHeading,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        colors = colors
                    )
                } else if (row.showTopGap) {
                    Spacer(modifier = Modifier.height(22.dp))
                }

                HabitRowCard(
                    row = row,
                    today = state.today,
                    dataOffset = dataOffsetState,
                    dayOffsets = dayOffsetsState,
                    buttonWidthDp = buttonWidthDp,
                    buttonHeightDp = buttonHeightDp,
                    appearance = state.appearance,
                    showClassicTopDivider = index == 0 || row.groupHeading != null || row.showTopGap,
                    isSelectionActive = state.isSelectionActive,
                    isDragging = isDragging,
                    onClick = { onRowClick(currentRowIndex) },
                    onLongPress = { onRowLongClick(currentRowIndex) },
                    isDragSortable = { latestIsSortable },
                    onDragStart = {
                        if (latestIsSortable) {
                            draggingHabitId = row.habitId
                            dragOffsetY = 0f
                        }
                    },
                    onDragDelta = { deltaY ->
                        if (!latestIsSortable || draggingHabitId != row.habitId) return@HabitRowCard
                        dragOffsetY += deltaY
                        val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                        val currentItem = visibleItems.firstOrNull { it.key == row.habitId }
                            ?: return@HabitRowCard
                        val currentCenterY = currentItem.offset + currentItem.size / 2f + dragOffsetY
                        val currIdx = currentItem.index

                        if (dragOffsetY > 0f && currIdx + 1 < latestRows.size) {
                            val nextItem = visibleItems.firstOrNull { it.index == currIdx + 1 }
                            if (nextItem != null && currentCenterY > nextItem.offset + nextItem.size / 2f) {
                                latestOnMoveRow(currIdx, nextItem.index)
                                dragOffsetY -= nextItem.size.toFloat()
                            }
                        } else if (dragOffsetY < 0f && currIdx - 1 >= 0) {
                            val prevItem = visibleItems.firstOrNull { it.index == currIdx - 1 }
                            if (prevItem != null && currentCenterY < prevItem.offset + prevItem.size / 2f) {
                                latestOnMoveRow(currIdx, prevItem.index)
                                dragOffsetY += prevItem.size.toFloat()
                            }
                        }
                    },
                    onDragEnd = {
                        if (draggingHabitId == row.habitId) {
                            draggingHabitId = null
                            dragOffsetY = 0f
                        }
                    },
                    onCheckmarkToggle = onCheckmarkToggle,
                    onHabitDateEdit = onHabitDateEdit,
                    colors = colors
                )
            }
        }
    }
}

@Composable
private fun HabitRowCard(
    row: HabitListRowUi,
    today: LocalDate,
    dataOffset: Int,
    dayOffsets: List<Int>,
    buttonWidthDp: Dp,
    buttonHeightDp: Dp,
    appearance: HabitAppearanceState,
    showClassicTopDivider: Boolean = false,
    isSelectionActive: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    isDragSortable: () -> Boolean,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onCheckmarkToggle: (Habit, LocalDate, Int, String, Offset) -> Unit,
    onHabitDateEdit: (Habit, LocalDate, Offset) -> Unit,
    colors: UHabitComposeColors
) {
    val activeColor = Color(row.activeColorArgb)
    val isClassic = appearance.isClassic
    val showBorders = appearance.showCardBorders
    val isAmoled = colors.isPureBlack
    val isSelected = row.isSelected

    val outerMarginH = when {
        isClassic -> 0.dp
        showBorders -> 4.dp
        else -> 2.dp
    }
    val outerMarginBottom = when {
        isClassic -> 0.dp
        showBorders -> 3.dp
        else -> 2.dp
    }

    val shape = remember(isClassic, appearance.cardCornerRadiusDp) {
        if (isClassic || appearance.cardCornerRadiusDp <= 0) {
            RectangleShape
        } else {
            RoundedCornerShape(appearance.cardCornerRadiusDp.dp)
        }
    }

    val baseFillColor = when {
        isClassic -> when {
            isAmoled -> Color.Black
            else -> colors.surfaceColor
        }
        !showBorders -> Color.Transparent
        isAmoled -> Color(UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB)
        else -> colors.surfaceColor
    }
    val baseArgb = if (baseFillColor == Color.Transparent) colors.background else baseFillColor.toArgb()
    val fillColor = if (isSelected) Color(
        androidx.core.graphics.ColorUtils.blendARGB(baseArgb, colors.accent, if (colors.isDark) 0.14f else 0.06f)
    ) else baseFillColor

    val yesAutoCutoutColor = when {
        isSelected -> fillColor
        !isClassic && !showBorders -> colors.backgroundColor
        else -> fillColor
    }

    val borderStrokeColor = when {
        isAmoled -> Color(UHabitElevationAndBorders.AMOLED_CARD_BORDER_ARGB)
        else -> colors.borderColor
    }
    val hasBorder = !isClassic && showBorders
    val borderWidth = 1.dp

    val classicDividerColor = when {
        isAmoled -> Color(UHabitElevationAndBorders.AMOLED_CARD_BORDER_ARGB)
        colors.isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color.Black.copy(alpha = 0.08f)
    }

    val elevationDp = when {
        isDragging -> 6.dp
        !isClassic && showBorders && !isAmoled -> 1.dp
        else -> 0.dp
    }

    val interactionSource = remember { MutableInteractionSource() }
    val rippleIndication: Indication = remember(colors.secondaryColor) {
        ripple(color = colors.secondaryColor.copy(alpha = 0.24f))
    }
    val haptics = LocalHapticFeedback.current

    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val latestIsSortable by rememberUpdatedState(isDragSortable)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragDelta by rememberUpdatedState(onDragDelta)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    val labelSemanticDesc = remember(row.groupHeading, row.name) {
        val heading = row.groupHeading
        if (!heading.isNullOrBlank()) "$heading, ${row.name}" else row.name
    }

    val leadingCoordsHolder = remember { CoordinatesHolder() }

    val isSortable = latestIsSortable()
    val rowPointerModifier = if (!isSortable) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = { latestOnClick() },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                latestOnLongPress()
            }
        )
    } else {
        Modifier
            .onGloballyPositioned { leadingCoordsHolder.coords = it }
            .pointerInput(row.habitId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)

                    val preOutcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull RowPreGestureOutcome.Cancelled
                            if (change.changedToUpIgnoreConsumed()) {
                                return@withTimeoutOrNull if (change.isConsumed) {
                                    RowPreGestureOutcome.Cancelled
                                } else {
                                    RowPreGestureOutcome.Tapped
                                }
                            }
                            if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                return@withTimeoutOrNull RowPreGestureOutcome.Cancelled
                            }
                        }
                    }

                    when (preOutcome) {
                        RowPreGestureOutcome.Tapped -> {
                            interactionSource.tryEmit(PressInteraction.Release(press))
                            latestOnClick()
                        }
                        RowPreGestureOutcome.Cancelled -> {
                            interactionSource.tryEmit(PressInteraction.Cancel(press))
                        }
                        null -> {
                            interactionSource.tryEmit(PressInteraction.Cancel(press))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestOnLongPress()

                            latestOnDragStart()
                            var lastRootY = leadingCoordsHolder.coords?.localToRoot(down.position)?.y
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    change.consume()
                                    val rootY = leadingCoordsHolder.coords?.localToRoot(change.position)?.y
                                    if (lastRootY != null && rootY != null) {
                                        val dy = rootY - lastRootY
                                        if (dy != 0f) {
                                            lastRootY = rootY
                                            latestOnDragDelta(dy)
                                        }
                                    } else if (rootY != null) {
                                        lastRootY = rootY
                                    } else {
                                        val dy = change.positionChange().y
                                        if (dy != 0f) {
                                            latestOnDragDelta(dy)
                                        }
                                    }
                                }
                            } finally {
                                latestOnDragEnd()
                            }
                        }
                    }
                }
            }
    }

    val rowBoxModifier = Modifier
        .fillMaxWidth()
        .padding(start = outerMarginH, end = outerMarginH, bottom = outerMarginBottom)
        .let { m -> if (elevationDp > 0.dp) m.shadow(elevationDp, shape, clip = false) else m }
        .clip(shape)
        .background(fillColor)
        .let { m ->
            if (hasBorder) m.border(borderWidth, borderStrokeColor, shape) else m
        }
        .let { m ->
            if (isClassic) {
                m.drawBehind {
                    val stroke = 1.dp.toPx()
                    if (showClassicTopDivider) {
                        drawLine(
                            color = classicDividerColor,
                            start = Offset(0f, stroke / 2f),
                            end = Offset(size.width, stroke / 2f),
                            strokeWidth = stroke
                        )
                    }
                    drawLine(
                        color = classicDividerColor,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke
                    )
                }
            } else {
                m
            }
        }
        .indication(interactionSource, rippleIndication)
        .semantics(mergeDescendants = false) {
            selected = isSelected
            if (!row.groupHeading.isNullOrBlank()) {
                stateDescription = row.groupHeading
            }
        }

    Row(
        modifier = rowBoxModifier.heightIn(min = buttonHeightDp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val showRings = appearance.showScoreRings
        val showIcon = appearance.showHabitIcons &&
            row.iconRef != null &&
            row.iconRef !is org.isoron.uhabits.core.containers.IconRef.None

        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = buttonHeightDp)
                .then(rowPointerModifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showRings) {
                HabitProgressIndicator(
                    score = row.score.toFloat(),
                    color = activeColor,
                    size = 15.dp,
                    thickness = 3.dp,
                    trackAlpha = 0.15f,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            if (showIcon) {
                HabitIcon(
                    iconRef = row.iconRef,
                    color = activeColor,
                    size = 24.dp,
                    modifier = Modifier.padding(
                        start = if (showRings) 2.dp else 12.dp,
                        end = 6.dp
                    )
                )
            }

            val labelStartPadding = when {
                showIcon -> 0.dp
                showRings -> 0.dp
                else -> 14.dp
            }

            BasicText(
                text = row.name,
                style = TextStyle(
                    color = activeColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 19.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = labelStartPadding, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .semantics {
                        contentDescription = labelSemanticDesc
                    }
            )
        }

        val resolvedCells = remember(row, dataOffset, dayOffsets, appearance.areQuestionMarksEnabled) {
            dayOffsets.map { dayIndex ->
                val dataIdx = dayIndex + dataOffset
                val date = today.minus(dataIdx)
                val rawValue = row.values.getOrUnknown(dataIdx)
                val cellNotes = row.notes.getOrElse(dataIdx) { "" }

                if (row.isNumerical) {
                    val goal = row.habit.goalAt(date)
                    val cellUi = resolveNumberCell(
                        rawMilliValue = rawValue,
                        notes = cellNotes,
                        units = goal.unit,
                        targetType = goal.targetType,
                        threshold = goal.targetValue / goal.frequency.denominator,
                        areQuestionMarksEnabled = appearance.areQuestionMarksEnabled,
                        date = date,
                        habit = row.habit,
                        effectiveSnapshot = row.effectiveSnapshot
                    )
                    ResolvedCell(date, cellUi, isNumerical = true)
                } else {
                    val cellUi = resolveCheckmarkCell(
                        value = rawValue,
                        notes = cellNotes,
                        areQuestionMarksEnabled = appearance.areQuestionMarksEnabled
                    )
                    ResolvedCell(date, cellUi, isNumerical = false)
                }
            }
        }

        Row(
            modifier = Modifier.width(buttonWidthDp * dayOffsets.size),
            verticalAlignment = Alignment.CenterVertically
        ) {
            resolvedCells.forEach { cell ->
                if (cell.isNumerical) {
                    HabitNumberButtonCell(
                        cell = cell.ui as HabitNumberCellUi,
                        habitColor = activeColor,
                        buttonWidthDp = buttonWidthDp,
                        buttonHeightDp = buttonHeightDp,
                        onOpenEditor = { centerInWindow ->
                            if (isSelectionActive) onClick()
                            else onHabitDateEdit(row.habit, cell.date, centerInWindow)
                        },
                        colors = colors
                    )
                } else {
                    HabitCheckmarkButtonCell(
                        cell = cell.ui as HabitCheckmarkCellUi,
                        habitColor = activeColor,
                        cutoutFillColor = yesAutoCutoutColor,
                        buttonWidthDp = buttonWidthDp,
                        buttonHeightDp = buttonHeightDp,
                        isShortToggleEnabled = appearance.isShortToggleEnabled,
                        isSkipEnabled = appearance.isSkipEnabled,
                        areQuestionMarksEnabled = appearance.areQuestionMarksEnabled,
                        onToggle = { nextVal, notes, centerInWindow ->
                            if (isSelectionActive) onClick()
                            else onCheckmarkToggle(row.habit, cell.date, nextVal, notes, centerInWindow)
                        },
                        onEdit = { centerInWindow ->
                            if (isSelectionActive) onClick()
                            else onHabitDateEdit(row.habit, cell.date, centerInWindow)
                        },
                        colors = colors
                    )
                }
            }
        }
    }
}

private class CoordinatesHolder {
    var coords: LayoutCoordinates? = null
}

private class ResolvedCell(
    val date: LocalDate,
    val ui: Any,
    val isNumerical: Boolean
)

private const val FA_CHECK = "\uf00c"
private const val FA_TIMES = "\uf00d"
private const val FA_SKIPPED = "\uf068"
private const val FA_QUESTION = "\uf128"

private val CONDENSED_BOLD_TYPEFACE: Typeface by lazy {
    Typeface.create("sans-serif-condensed", Typeface.BOLD)
}
private val CONDENSED_NORMAL_TYPEFACE: Typeface by lazy {
    Typeface.create("sans-serif-condensed", Typeface.NORMAL)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitCheckmarkButtonCell(
    cell: HabitCheckmarkCellUi,
    habitColor: Color,
    cutoutFillColor: Color,
    buttonWidthDp: Dp,
    buttonHeightDp: Dp,
    isShortToggleEnabled: Boolean,
    isSkipEnabled: Boolean,
    areQuestionMarksEnabled: Boolean,
    onToggle: (Int, String, Offset) -> Unit,
    onEdit: (Offset) -> Unit,
    colors: UHabitComposeColors
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val fontAwesome = remember(context) { InterfaceUtils.getFontAwesome(context) }

    val lowContrast = colors.secondaryColor.copy(alpha = 0.55f)
    val mediumContrast = colors.secondaryColor.copy(alpha = 0.82f)
    val activeColor = when (cell.tone) {
        HabitCellTone.ACTIVE -> habitColor
        HabitCellTone.MEDIUM -> mediumContrast
        HabitCellTone.LOW -> lowContrast
    }

    val glyphStr = when (cell.glyph) {
        HabitCheckmarkGlyph.CHECK -> FA_CHECK
        HabitCheckmarkGlyph.TIMES -> FA_TIMES
        HabitCheckmarkGlyph.SKIPPED -> FA_SKIPPED
        HabitCheckmarkGlyph.QUESTION -> FA_QUESTION
    }

    val stateDesc = when (cell.rawValue) {
        Entry.YES_MANUAL, Entry.YES_AUTO -> stringResource(R.string.yes)
        Entry.SKIP -> stringResource(R.string.focus_credit_day_excluded)
        Entry.NO -> stringResource(R.string.no)
        else -> if (areQuestionMarksEnabled) stringResource(R.string.focus_credit_unknown) else stringResource(R.string.no)
    }

    val coordHolder = remember { CoordinatesHolder() }

    fun resolveCenter(): Offset {
        val coords = coordHolder.coords ?: return Offset.Zero
        if (!coords.isAttached) return Offset.Zero
        val topLeft = coords.positionInWindow()
        return Offset(
            x = topLeft.x + coords.size.width / 2f,
            y = topLeft.y + coords.size.height / 2f
        )
    }

    fun triggerToggle() {
        val nextValue = Entry.nextToggleValue(
            value = cell.rawValue,
            isSkipEnabled = isSkipEnabled,
            areQuestionMarksEnabled = areQuestionMarksEnabled
        )
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onToggle(nextValue, cell.notes, resolveCenter())
    }

    val paint = remember(fontAwesome) {
        TextPaint().apply {
            typeface = fontAwesome
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val notesPaint = remember {
        Paint().apply { isAntiAlias = true }
    }

    val cappedScale = min(density.fontScale, 1.35f)
    val textSizeSp = when {
        cell.glyph == HabitCheckmarkGlyph.QUESTION -> 12f
        cell.isHollowYesAuto -> 13f
        else -> 14f
    }
    val textSizePx = with(density) { textSizeSp * cappedScale * this.density }
    val yesAutoStrokePx = with(density) { 1.7.dp.toPx() }
    val notesRadiusPx = with(density) { 2.7.dp.toPx() }

    Box(
        modifier = Modifier
            .size(width = buttonWidthDp, height = buttonHeightDp)
            .onGloballyPositioned { coordHolder.coords = it }
            .combinedClickable(
                onClick = {
                    if (isShortToggleEnabled) triggerToggle() else onEdit(resolveCenter())
                },
                onLongClick = {
                    if (isShortToggleEnabled) onEdit(resolveCenter()) else triggerToggle()
                }
            )
            .semantics {
                role = Role.Button
                contentDescription = stateDesc
            }
            .drawBehind {
                paint.textSize = textSizePx
                paint.color = activeColor.toArgb()
                val em = paint.measureText("m")
                val centerY = size.height / 2f + 0.4f * em
                val centerX = size.width / 2f

                if (cell.isHollowYesAuto) {
                    paint.strokeWidth = yesAutoStrokePx
                    paint.style = Paint.Style.STROKE
                    drawContext.canvas.nativeCanvas.drawText(glyphStr, centerX, centerY, paint)

                    paint.color = cutoutFillColor.toArgb()
                    paint.style = Paint.Style.FILL
                    drawContext.canvas.nativeCanvas.drawText(glyphStr, centerX, centerY, paint)
                } else {
                    paint.strokeWidth = 0f
                    paint.style = Paint.Style.FILL
                    drawContext.canvas.nativeCanvas.drawText(glyphStr, centerX, centerY, paint)
                }

                if (cell.hasNotes) {
                    notesPaint.color = habitColor.toArgb()
                    val cy = 0.8f * em
                    drawContext.canvas.nativeCanvas.drawCircle(
                        size.width - cy,
                        cy,
                        notesRadiusPx,
                        notesPaint
                    )
                }
            }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitNumberButtonCell(
    cell: HabitNumberCellUi,
    habitColor: Color,
    buttonWidthDp: Dp,
    buttonHeightDp: Dp,
    onOpenEditor: (Offset) -> Unit,
    colors: UHabitComposeColors
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontAwesome = remember(context) { InterfaceUtils.getFontAwesome(context) }

    val lowContrast = colors.secondaryColor.copy(alpha = 0.55f)
    val mediumContrast = colors.secondaryColor.copy(alpha = 0.82f)
    val activeColor = when (cell.tone) {
        HabitCellTone.ACTIVE -> habitColor
        HabitCellTone.MEDIUM -> mediumContrast
        HabitCellTone.LOW -> lowContrast
    }

    val glyphStr = when (cell.glyph) {
        HabitCheckmarkGlyph.SKIPPED -> FA_SKIPPED
        HabitCheckmarkGlyph.QUESTION -> FA_QUESTION
        else -> null
    }

    val a11yDesc = when {
        cell.isSkipped -> stringResource(R.string.focus_credit_day_excluded)
        cell.isUnknown -> stringResource(R.string.focus_credit_unknown)
        cell.isLowerBound -> "≥ ${cell.primaryText} ${cell.unitsText}".trim()
        else -> "${cell.primaryText} ${cell.unitsText}".trim()
    }

    val coordHolder = remember { CoordinatesHolder() }

    fun resolveCenter(): Offset {
        val coords = coordHolder.coords ?: return Offset.Zero
        if (!coords.isAttached) return Offset.Zero
        val topLeft = coords.positionInWindow()
        return Offset(
            x = topLeft.x + coords.size.width / 2f,
            y = topLeft.y + coords.size.height / 2f
        )
    }

    val numberPaint = remember {
        TextPaint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val unitPaint = remember {
        TextPaint().apply {
            typeface = CONDENSED_NORMAL_TYPEFACE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val notesPaint = remember {
        Paint().apply { isAntiAlias = true }
    }

    val cappedScale = min(density.fontScale, 1.3f)
    val primarySizePx = with(density) {
        val spVal = if (cell.glyph == HabitCheckmarkGlyph.QUESTION) 12f else 14f
        spVal * cappedScale * this.density
    }
    val unitSizePx = with(density) { 12f * cappedScale * this.density }
    val notesRadiusPx = with(density) { 2.7.dp.toPx() }

    Box(
        modifier = Modifier
            .size(width = buttonWidthDp, height = buttonHeightDp)
            .onGloballyPositioned { coordHolder.coords = it }
            .combinedClickable(
                onClick = { onOpenEditor(resolveCenter()) },
                onLongClick = { onOpenEditor(resolveCenter()) }
            )
            .semantics {
                role = Role.Button
                contentDescription = a11yDesc
            }
            .drawBehind {
                numberPaint.textSize = primarySizePx
                numberPaint.color = activeColor.toArgb()
                numberPaint.typeface = if (glyphStr != null) fontAwesome else CONDENSED_BOLD_TYPEFACE
                unitPaint.textSize = unitSizePx
                unitPaint.color = activeColor.toArgb()

                val em = numberPaint.measureText("m")
                val primaryLabel = glyphStr ?: cell.primaryText
                val cx = size.width / 2f
                val cy = size.height / 2f

                if (cell.unitsText.isBlank()) {
                    drawContext.canvas.nativeCanvas.drawText(
                        primaryLabel,
                        cx,
                        cy + 0.5f * em,
                        numberPaint
                    )
                } else {
                    drawContext.canvas.nativeCanvas.drawText(
                        primaryLabel,
                        cx,
                        cy,
                        numberPaint
                    )
                    val maxUnitsWidth = size.width * 0.9f
                    var trimmedUnits = cell.unitsText
                    while (trimmedUnits.length > 2 && unitPaint.measureText(trimmedUnits) > maxUnitsWidth) {
                        trimmedUnits = trimmedUnits.dropLast(2) + "…"
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        trimmedUnits,
                        cx,
                        cy + 1.3f * em,
                        unitPaint
                    )
                }

                if (cell.hasNotes) {
                    notesPaint.color = habitColor.toArgb()
                    val nCy = 0.8f * em
                    drawContext.canvas.nativeCanvas.drawCircle(
                        size.width - nCy,
                        nCy,
                        notesRadiusPx,
                        notesPaint
                    )
                }
            }
    )
}

private enum class RowPreGestureOutcome {
    Tapped,
    Cancelled
}

@Composable
private fun HabitLoadingPane(
    text: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    colors: UHabitComposeColors
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 15.sp
            )
        )
        if (showRetry) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentColor,
                    contentColor = Color.White
                )
            ) {
                BasicText(
                    text = stringResource(R.string.habits_loading_retry),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
private fun HabitHintBanner(
    hintText: String,
    onDismiss: () -> Unit
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3F51B5))
            .clickable(onClick = onDismiss)
            .padding(
                start = 16.dp,
                top = 14.dp,
                end = 16.dp,
                bottom = 14.dp + navBottom
            )
    ) {
        BasicText(
            text = stringResource(R.string.hint_title),
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicText(
            text = hintText,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp
            )
        )
    }
}
