package org.isoron.uhabits.activities.habits.list

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.models.HabitList

internal data class HabitToolbarItem(
    val id: Int,
    val title: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val isDescending: Boolean = false,
    val supportsDirection: Boolean = false
)
internal data class HabitToolbarState(
    val title: String = "", val archive: Boolean = false, val searching: Boolean = false,
    val query: String = "", val searchEndMarginDp: Int = 4, val cardEndMarginDp: Int = 4, val syncing: Boolean = false, val syncVisible: Boolean = false,
    val filters: List<HabitToolbarItem> = emptyList(), val sorts: List<HabitToolbarItem> = emptyList(),
    val overflow: List<HabitToolbarItem> = emptyList()
)

internal data class HabitToolbarLabels(
    val habitsTitle: String,
    val archiveTitle: String,
    val hideArchived: String,
    val hideCompleted: String,
    val hideEntered: String,
    val sortManual: String,
    val sortByName: String,
    val sortByColor: String,
    val sortByScore: String,
    val sortByStatus: String,
    val sortBySphere: String,
    val sortByLevel: String,
    val skipDay: String,
    val search: String
)

internal fun buildHabitToolbarState(
    archive: Boolean,
    searching: Boolean,
    query: String,
    showArchived: Boolean,
    showCompleted: Boolean,
    areQuestionMarksEnabled: Boolean,
    isSkipEnabled: Boolean,
    primaryOrder: HabitList.Order,
    isHabitSpheresEnabled: Boolean,
    isDayTiersEnabled: Boolean,
    isClassicLayout: Boolean,
    showCardBorders: Boolean,
    syncReady: Boolean,
    isSyncing: Boolean,
    labels: HabitToolbarLabels
): HabitToolbarState {
    val endMarginDp = when {
        isClassicLayout -> 0
        showCardBorders -> 4
        else -> 2
    }
    val inNormalMode = !archive && !searching

    val filters = if (inNormalMode) {
        listOf(
            HabitToolbarItem(
                id = R.id.actionHideArchived,
                title = labels.hideArchived,
                checked = !showArchived,
                enabled = true
            ),
            HabitToolbarItem(
                id = R.id.actionHideCompleted,
                title = if (areQuestionMarksEnabled || isSkipEnabled) labels.hideEntered else labels.hideCompleted,
                checked = !showCompleted,
                enabled = true
            )
        )
    } else {
        emptyList()
    }

    val effectiveOrder = when {
        !isHabitSpheresEnabled && primaryOrder == HabitList.Order.BY_SPHERE -> HabitList.Order.BY_POSITION
        !isDayTiersEnabled && primaryOrder == HabitList.Order.BY_DAY_TIER -> HabitList.Order.BY_POSITION
        else -> primaryOrder
    }

    val selectedSortId = when (effectiveOrder) {
        HabitList.Order.BY_NAME_ASC, HabitList.Order.BY_NAME_DESC -> R.id.actionSortName
        HabitList.Order.BY_COLOR_ASC, HabitList.Order.BY_COLOR_DESC -> R.id.actionSortColor
        HabitList.Order.BY_SCORE_ASC, HabitList.Order.BY_SCORE_DESC -> R.id.actionSortScore
        HabitList.Order.BY_STATUS_ASC, HabitList.Order.BY_STATUS_DESC -> R.id.actionSortStatus
        HabitList.Order.BY_SPHERE -> R.id.actionSortSphere
        HabitList.Order.BY_DAY_TIER -> R.id.actionSortLevel
        HabitList.Order.BY_POSITION -> R.id.actionSortManual
    }
    val isDescending = effectiveOrder == HabitList.Order.BY_NAME_DESC ||
        effectiveOrder == HabitList.Order.BY_COLOR_DESC ||
        effectiveOrder == HabitList.Order.BY_SCORE_DESC ||
        effectiveOrder == HabitList.Order.BY_STATUS_DESC

    val sorts = if (inNormalMode) {
        buildList {
            add(HabitToolbarItem(R.id.actionSortManual, labels.sortManual))
            add(HabitToolbarItem(R.id.actionSortName, labels.sortByName, supportsDirection = true))
            add(HabitToolbarItem(R.id.actionSortColor, labels.sortByColor, supportsDirection = true))
            add(HabitToolbarItem(R.id.actionSortScore, labels.sortByScore, supportsDirection = true))
            add(HabitToolbarItem(R.id.actionSortStatus, labels.sortByStatus, supportsDirection = true))
            if (isHabitSpheresEnabled) {
                add(HabitToolbarItem(R.id.actionSortSphere, labels.sortBySphere))
            }
            if (isDayTiersEnabled) {
                add(HabitToolbarItem(R.id.actionSortLevel, labels.sortByLevel))
            }
        }.map { item ->
            val selected = item.id == selectedSortId
            item.copy(
                checked = selected,
                isDescending = selected && isDescending
            )
        }
    } else {
        emptyList()
    }

    val overflow = when {
        inNormalMode -> listOf(
            HabitToolbarItem(R.id.actionSkipDay, labels.skipDay),
            HabitToolbarItem(R.id.actionSearch, labels.search)
        )
        archive && !searching -> listOf(
            HabitToolbarItem(R.id.actionSearch, labels.search)
        )
        else -> emptyList()
    }

    return HabitToolbarState(
        title = if (archive) labels.archiveTitle else labels.habitsTitle,
        archive = archive,
        searching = searching,
        query = query,
        searchEndMarginDp = endMarginDp,
        cardEndMarginDp = endMarginDp,
        syncing = inNormalMode && syncReady && isSyncing,
        syncVisible = inNormalMode && syncReady,
        filters = filters,
        sorts = sorts,
        overflow = overflow
    )
}

internal fun computeToolbarActionCentersFromEndDp(
    actionCount: Int,
    buttonWidthDp: Float,
    endMarginDp: Float
): List<Float> {
    if (actionCount <= 0) return emptyList()
    return List(actionCount) { index ->
        val slotFromEnd = actionCount - 1 - index
        endMarginDp + buttonWidthDp * (slotFromEnd + 0.5f)
    }
}

internal fun calculateToolbarPopupPositionPx(
    anchorBounds: IntRect,
    windowSize: IntSize,
    popupSize: IntSize,
    layoutDirection: LayoutDirection,
    endScreenMarginPx: Int,
    edgeMarginPx: Int,
    verticalOffsetPx: Int,
): IntOffset {
    val safeMargin = edgeMarginPx.coerceAtLeast(0)
    val maxX = (windowSize.width - popupSize.width - safeMargin).coerceAtLeast(safeMargin)
    val desiredX = if (layoutDirection == LayoutDirection.Ltr) {
        val targetRight = minOf(anchorBounds.right, windowSize.width - endScreenMarginPx)
        targetRight - popupSize.width
    } else {
        val targetLeft = maxOf(anchorBounds.left, endScreenMarginPx)
        targetLeft
    }
    val x = desiredX.coerceIn(safeMargin, maxX)

    val maxY = (windowSize.height - popupSize.height - safeMargin).coerceAtLeast(safeMargin)
    val belowY = anchorBounds.bottom + verticalOffsetPx
    val aboveY = anchorBounds.top - popupSize.height - verticalOffsetPx
    val spaceBelow = (windowSize.height - safeMargin) - (anchorBounds.bottom + verticalOffsetPx)
    val spaceAbove = (anchorBounds.top - verticalOffsetPx) - safeMargin
    val y = when {
        popupSize.height <= spaceBelow -> belowY.coerceIn(safeMargin, maxY)
        popupSize.height <= spaceAbove -> aboveY.coerceIn(safeMargin, maxY)
        spaceAbove > spaceBelow -> aboveY.coerceIn(safeMargin, maxY)
        else -> belowY.coerceIn(safeMargin, maxY)
    }
    return IntOffset(x, y)
}

@Composable
internal fun HabitToolbarCompose(
    state: HabitToolbarState, colors: UHabitComposeColors, onAction: (Int) -> Unit,
    onBack: () -> Unit, onQuery: (String) -> Unit
) {
    val actionWidthDp = dimensionResource(R.dimen.checkmarkWidth)
    Row(Modifier.fillMaxWidth().background(colors.backgroundColor).statusBarsPadding().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.searching) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_settings_back), stringResource(android.R.string.cancel), tint = colors.foregroundColor) }
            BasicTextField(value = state.query, onValueChange = onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foregroundColor), cursorBrush = SolidColor(colors.accentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.weight(1f).focusRequester(focus), decorationBox = { field ->
                    Box { if (state.query.isEmpty()) Text(stringResource(R.string.search_habits), color = colors.secondaryColor); field() }
                })
            IconButton(
                onClick = { if (state.query.isEmpty()) onBack() else onQuery("") },
                modifier = Modifier
                    .padding(end = state.searchEndMarginDp.dp)
                    .size(width = actionWidthDp, height = 48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_toolbar_close),
                    contentDescription = stringResource(if (state.query.isEmpty()) android.R.string.cancel else R.string.clear),
                    tint = colors.foregroundColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            if (state.archive) IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_settings_back), stringResource(android.R.string.cancel), tint = colors.foregroundColor) }
            Text(state.title, Modifier.weight(1f).padding(start = if (state.archive) 0.dp else 16.dp), color = colors.foregroundColor, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            if (!state.archive) {
                Row(
                    modifier = Modifier.padding(end = state.cardEndMarginDp.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolbarActionButton(
                        iconRes = R.drawable.ic_toolbar_add,
                        contentDescription = stringResource(R.string.create_habit),
                        tint = colors.foregroundColor,
                        buttonWidthDp = actionWidthDp,
                        onClick = { onAction(R.id.actionAddHabit) }
                    )
                    if (state.syncVisible) {
                        ToolbarActionButton(
                            iconRes = R.drawable.ic_toolbar_refresh,
                            contentDescription = stringResource(R.string.sync_now),
                            tint = colors.foregroundColor,
                            buttonWidthDp = actionWidthDp,
                            enabled = !state.syncing,
                            syncing = state.syncing,
                            onClick = { onAction(R.id.actionSync) }
                        )
                    }
                    if (state.filters.isNotEmpty() || state.sorts.isNotEmpty()) {
                        HabitFilterSortPopup(
                            filters = state.filters,
                            sorts = state.sorts,
                            buttonWidthDp = actionWidthDp,
                            colors = colors,
                            onAction = onAction
                        )
                    }
                    if (state.overflow.isNotEmpty()) {
                        HabitToolbarPopup(
                            icon = R.drawable.ic_toolbar_more_vertical,
                            description = stringResource(R.string.containers_more),
                            items = state.overflow,
                            cardEndMarginDp = state.cardEndMarginDp,
                            buttonWidthDp = actionWidthDp,
                            colors = colors,
                            onAction = onAction
                        )
                    }
                }
            } else if (state.overflow.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(end = state.cardEndMarginDp.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HabitToolbarPopup(
                        icon = R.drawable.ic_toolbar_more_vertical,
                        description = stringResource(R.string.containers_more),
                        items = state.overflow,
                        cardEndMarginDp = state.cardEndMarginDp,
                        buttonWidthDp = actionWidthDp,
                        colors = colors,
                        onAction = onAction
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarActionButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    buttonWidthDp: Dp,
    enabled: Boolean = true,
    syncing: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(buttonWidthDp)
            .height(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (syncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = tint,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = tint
            )
        }
    }
}

@Composable
private fun HabitFilterSortPopup(
    filters: List<HabitToolbarItem>,
    sorts: List<HabitToolbarItem>,
    buttonWidthDp: Dp,
    colors: UHabitComposeColors,
    onAction: (Int) -> Unit
) {
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val currentSort = sorts.firstOrNull { it.checked }?.title ?: ""

    Box {
        ToolbarActionButton(
            iconRes = R.drawable.ic_toolbar_sort_descending,
            contentDescription = stringResource(R.string.filter),
            tint = colors.foregroundColor,
            buttonWidthDp = buttonWidthDp,
            onClick = { filterMenuExpanded = true }
        )

        // 1. Main Filter Popup (Compact Two-Level Primary Surface)
        val popupShape = RoundedCornerShape(12.dp)
        val popupBorder = BorderStroke(1.dp, colors.dialogBorderColor)
        val popupWidthModifier = Modifier.widthIn(min = 228.dp, max = 268.dp)
        val sectionLabelStyle = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
        DropdownMenu(
            expanded = filterMenuExpanded,
            onDismissRequest = { filterMenuExpanded = false },
            offset = DpOffset(0.dp, 2.dp),
            shape = popupShape,
            containerColor = colors.dialogSurfaceColor,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = popupBorder,
            modifier = popupWidthModifier
        ) {
            if (filters.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.filter).uppercase(),
                    style = sectionLabelStyle,
                    color = colors.secondaryColor,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 6.dp)
                )
                filters.forEach { item ->
                    ToolbarPopupOptionRow(
                        item = item,
                        semanticsRole = Role.Checkbox,
                        colors = colors,
                        onClick = {
                            filterMenuExpanded = false
                            onAction(item.id)
                        }
                    )
                }
            }
            if (filters.isNotEmpty() && sorts.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    color = colors.borderColor
                )
            }
            if (sorts.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.sort),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = colors.foregroundColor
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentSort,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = colors.secondaryColor,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_tabler_chevron_right),
                                    contentDescription = null,
                                    tint = colors.secondaryColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .heightIn(min = 38.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    onClick = {
                        filterMenuExpanded = false
                        sortMenuExpanded = true
                    }
                )
            }
        }

        // 2. Sorting Submenu Popup (Dedicated Selection Surface)
        DropdownMenu(
            expanded = sortMenuExpanded,
            onDismissRequest = { sortMenuExpanded = false },
            offset = DpOffset(0.dp, 2.dp),
            shape = popupShape,
            containerColor = colors.dialogSurfaceColor,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = popupBorder,
            modifier = popupWidthModifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            sortMenuExpanded = false
                            filterMenuExpanded = true
                        }
                    )
                    .padding(start = 12.dp, end = 14.dp, top = 0.dp, bottom = 8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tabler_chevron_left),
                    contentDescription = stringResource(R.string.settings_back),
                    tint = colors.secondaryColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.sort).uppercase(),
                    style = sectionLabelStyle,
                    color = colors.secondaryColor
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 6.dp),
                color = colors.borderColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            sorts.forEach { item ->
                ToolbarPopupOptionRow(
                    item = item,
                    semanticsRole = Role.RadioButton,
                    colors = colors,
                    onClick = {
                        sortMenuExpanded = false
                        onAction(item.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun ToolbarPopupOptionRow(
    item: HabitToolbarItem,
    semanticsRole: Role?,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    val activeFill = if (item.checked) {
        colors.accentColor.copy(
            alpha = when {
                colors.isPureBlack -> 0.22f
                colors.isDark -> 0.16f
                else -> 0.12f
            }
        )
    } else {
        Color.Transparent
    }
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = if (item.checked) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = colors.foregroundColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (item.checked && item.supportsDirection) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        painter = painterResource(
                            if (item.isDescending) R.drawable.ic_tabler_arrow_down else R.drawable.ic_tabler_arrow_up
                        ),
                        contentDescription = null,
                        tint = colors.accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(activeFill)
            .heightIn(min = 38.dp)
            .let { mod ->
                if (semanticsRole != null) {
                    mod.semantics {
                        role = semanticsRole
                        selected = item.checked
                    }
                } else {
                    mod
                }
            },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        enabled = item.enabled,
        onClick = onClick
    )
}

@Composable
private fun HabitToolbarPopup(
    icon: Int,
    description: String,
    items: List<HabitToolbarItem>,
    cardEndMarginDp: Int,
    buttonWidthDp: Dp,
    colors: UHabitComposeColors,
    onAction: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val endScreenMarginDp = if (cardEndMarginDp == 0) 12 else cardEndMarginDp + 10
    val positionProvider = remember(density, endScreenMarginDp) {
        val endMarginPx = with(density) { endScreenMarginDp.dp.roundToPx() }
        val edgeMarginPx = with(density) { 8.dp.roundToPx() }
        val verticalOffsetPx = with(density) { 2.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset = calculateToolbarPopupPositionPx(
                anchorBounds = anchorBounds,
                windowSize = windowSize,
                popupSize = popupContentSize,
                layoutDirection = layoutDirection,
                endScreenMarginPx = endMarginPx,
                edgeMarginPx = edgeMarginPx,
                verticalOffsetPx = verticalOffsetPx,
            )
        }
    }
    Box {
        ToolbarActionButton(
            iconRes = icon,
            contentDescription = description,
            tint = colors.foregroundColor,
            buttonWidthDp = buttonWidthDp,
            onClick = { expanded = true }
        )
        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.dialogSurfaceColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, colors.dialogBorderColor)
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 136.dp, max = 232.dp)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        items.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        enabled = item.enabled,
                                        role = Role.Button,
                                        onClick = {
                                            expanded = false
                                            onAction(item.id)
                                        }
                                    )
                                    .heightIn(min = 42.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Normal,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                    ),
                                    color = if (item.enabled) colors.foregroundColor else colors.secondaryColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
