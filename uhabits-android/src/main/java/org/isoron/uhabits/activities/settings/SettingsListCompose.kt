/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.settings

import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

enum class SettingsSectionId {
    APPEARANCE,
    HABITS,
    NOTIFICATIONS,
    POMODORO,
    STATISTICS,
    DATA,
    SYNC,
    HELP,
    DEVELOPER
}

data class SettingPresentation(
    val cardId: String? = null,
    val showIcon: Boolean = true,
    val enabled: Boolean = true,
    val isFirst: Boolean = false,
    val isLast: Boolean = false
)

sealed class SettingItem {
    abstract val stableKey: String
    open val presentation: SettingPresentation = SettingPresentation()

    data class Section(
        val sectionId: SettingsSectionId,
        val iconRes: Int,
        val endIconRes: Int? = null,
        val title: String,
        val summary: String,
        val accessibilityDescription: String,
        val onClick: (SettingsTransitionSource?) -> Unit,
        override val presentation: SettingPresentation = SettingPresentation()
    ) : SettingItem() {
        override val stableKey: String = "section:${sectionId.name}"
    }

    data class Subsection(
        val key: String,
        val title: String,
        val isDestructive: Boolean = false,
        override val presentation: SettingPresentation
    ) : SettingItem() {
        override val stableKey: String = "subsection:$key"
    }

    data class Status(
        val key: String,
        val iconRes: Int,
        val title: String,
        val summary: String,
        val showProgress: Boolean = false,
        override val presentation: SettingPresentation,
        val reserveProgressSpace: Boolean = false
    ) : SettingItem() {
        override val stableKey: String = "status:$key"
    }

    data class Navigation(
        val key: String,
        val iconRes: Int,
        val title: String,
        val summary: String? = null,
        val isDanger: Boolean = false,
        val onClick: (() -> Unit)? = null,
        val isBusy: Boolean = false,
        override val presentation: SettingPresentation = SettingPresentation()
    ) : SettingItem() {
        override val stableKey: String = "navigation:$key"
    }

    data class Switch(
        val key: String,
        val iconRes: Int,
        val title: String,
        val summary: String? = null,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        override val presentation: SettingPresentation = SettingPresentation()
    ) : SettingItem() {
        override val stableKey: String = "switch:$key"
    }

    data class Value(
        val key: String,
        val iconRes: Int,
        val title: String,
        val valueText: String,
        val onClick: () -> Unit,
        override val presentation: SettingPresentation = SettingPresentation()
    ) : SettingItem() {
        override val stableKey: String = "value:$key"
    }

    data class SegmentedTheme(
        val key: String,
        val iconRes: Int,
        val title: String,
        val themeValue: Int,
        val pureBlackValue: Boolean,
        val onSegmentSelected: (theme: Int, pureBlack: Boolean) -> Unit,
        override val presentation: SettingPresentation = SettingPresentation()
    ) : SettingItem() {
        override val stableKey: String = "segmented:$key"
    }

    data class ColorPicker(
        val key: String,
        val iconRes: Int,
        val title: String,
        val summary: String,
        val colorString: String,
        val onClick: () -> Unit,
        override val presentation: SettingPresentation = SettingPresentation()
    ) : SettingItem() {
        override val stableKey: String = "color:$key"
    }
}

internal data class SettingsRowSurfaceStyle(
    val backgroundColor: Int,
    val cornerRadiusPx: Float,
    val strokeWidthPx: Int,
    val strokeColor: Int,
    val clipToOutline: Boolean,
    val marginHorizontalPx: Int,
    val marginTopPx: Int,
    val marginBottomPx: Int,
)

internal object SettingsRowSurfaceSpec {
    fun resolve(
        palette: SettingsThemePalette,
        density: Float,
        cardMarginHorizontalPx: Int,
        cardHalfGapPx: Int,
        isFirst: Boolean,
        isLast: Boolean,
    ): SettingsRowSurfaceStyle {
        if (palette.isClassicSectionLayout) {
            return SettingsRowSurfaceStyle(
                backgroundColor = palette.background,
                cornerRadiusPx = 0f,
                strokeWidthPx = 0,
                strokeColor = android.graphics.Color.TRANSPARENT,
                clipToOutline = false,
                marginHorizontalPx = 0,
                marginTopPx = 0,
                marginBottomPx = 0,
            )
        }
        val radiusPx = palette.habitsCardCornerRadius.coerceAtLeast(0) * density
        val strokeWidthPx = if (palette.showHabitCardBorders || palette.isPureBlack) {
            (1f * density).toInt().coerceAtLeast(1)
        } else {
            0
        }
        return SettingsRowSurfaceStyle(
            backgroundColor = palette.surface,
            cornerRadiusPx = radiusPx,
            strokeWidthPx = strokeWidthPx,
            strokeColor = palette.border,
            clipToOutline = true,
            marginHorizontalPx = cardMarginHorizontalPx,
            marginTopPx = if (isFirst) cardHalfGapPx else 0,
            marginBottomPx = if (isLast) cardHalfGapPx else 0,
        )
    }
}

internal data class SettingsListGroup(
    val stableKey: String,
    val items: List<SettingItem>,
)

internal fun groupSettingsItems(items: List<SettingItem>): List<SettingsListGroup> {
    if (items.isEmpty()) return emptyList()
    val groups = mutableListOf<SettingsListGroup>()
    var currentCardId: String? = null
    var currentBucket = mutableListOf<SettingItem>()

    fun flush() {
        if (currentBucket.isNotEmpty()) {
            val firstKey = currentBucket.first().stableKey
            val groupKey = currentCardId?.let { "group:$it" } ?: "item:$firstKey"
            groups.add(SettingsListGroup(stableKey = groupKey, items = currentBucket))
            currentBucket = mutableListOf()
        }
    }

    for (item in items) {
        if (item is SettingItem.Section) {
            flush()
            currentCardId = null
            groups.add(SettingsListGroup(stableKey = item.stableKey, items = listOf(item)))
            continue
        }
        val itemCardId = item.presentation.cardId
        if (itemCardId != null && itemCardId == currentCardId) {
            currentBucket.add(item)
        } else {
            flush()
            currentCardId = itemCardId
            currentBucket.add(item)
        }
    }
    flush()
    return groups
}

@Composable
internal fun SettingsListContent(
    items: List<SettingItem>,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(top = 8.dp, bottom = 16.dp),
    motionEnabled: Boolean = true,
    hiddenSectionId: SettingsSectionId? = null,
    onSectionBoundsChanged: (SettingsSectionId, RectF) -> Unit = { _, _ -> },
    onSectionClick: (SettingItem.Section) -> Unit = { it.onClick(null) },
) {
    val groups = remember(items) { groupSettingsItems(items) }
    val cardHorizontalMargin = dimensionResource(R.dimen.settings_card_horizontal_margin)
    val cardVerticalHalfGap = dimensionResource(R.dimen.settings_card_vertical_gap_half)
    val maxWidth = dimensionResource(R.dimen.settings_content_max_width)

    UHabitComposeTheme(colors) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(Color(colors.background)),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!constraints.hasBoundedHeight) return@BoxWithConstraints
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = maxWidth)
                    .fillMaxWidth(),
                contentPadding = contentPadding,
            ) {
                items(
                    items = groups,
                    key = { it.stableKey },
                ) { group ->
                    val singleItem = group.items.singleOrNull()
                    if (singleItem is SettingItem.Section) {
                        SettingsHomeSectionContainer(
                            item = singleItem,
                            colors = colors,
                            cardHorizontalMargin = cardHorizontalMargin,
                            cardVerticalHalfGap = cardVerticalHalfGap,
                            motionEnabled = motionEnabled,
                            isHidden = hiddenSectionId == singleItem.sectionId,
                            onBoundsChanged = { rect -> onSectionBoundsChanged(singleItem.sectionId, rect) },
                            onClick = { onSectionClick(singleItem) },
                        )
                    } else {
                        SettingsDetailGroupContainer(
                            items = group.items,
                            colors = colors,
                            cardHorizontalMargin = cardHorizontalMargin,
                            cardVerticalHalfGap = cardVerticalHalfGap,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHomeSectionContainer(
    item: SettingItem.Section,
    colors: UHabitComposeColors,
    cardHorizontalMargin: androidx.compose.ui.unit.Dp,
    cardVerticalHalfGap: androidx.compose.ui.unit.Dp,
    motionEnabled: Boolean,
    isHidden: Boolean,
    onBoundsChanged: (RectF) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && motionEnabled) SettingsTransitionSpec.PRESSED_SCALE else 1f,
        animationSpec = tween(durationMillis = SettingsTransitionSpec.PRESS_DURATION.toInt()),
        label = "settingsHomeCardScale",
    )
    val isClassic = colors.isClassicSectionLayout
    val shape = if (isClassic) {
        RoundedCornerShape(0.dp)
    } else {
        RoundedCornerShape(colors.habitsCardCornerRadiusDp.coerceAtLeast(0).dp)
    }
    val showBorder = !isClassic && (colors.showCardBorders || colors.isPureBlack)
    val surfaceColor = Color(if (isClassic) colors.background else colors.surface)
    val rippleColor = Color(colors.foreground).copy(alpha = if (colors.isDark) 0.11f else 0.07f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isClassic) {
                    Modifier
                } else {
                    Modifier.padding(
                        horizontal = cardHorizontalMargin,
                        vertical = cardVerticalHalfGap,
                    )
                },
            )
            .graphicsLayer {
                alpha = if (isHidden) 0f else 1f
                scaleX = scale
                scaleY = scale
            }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val size = coords.size
                if (size.width > 0 && size.height > 0) {
                    onBoundsChanged(
                        RectF(
                            pos.x,
                            pos.y,
                            pos.x + size.width.toFloat(),
                            pos.y + size.height.toFloat(),
                        ),
                    )
                }
            }
            .clip(shape)
            .background(surfaceColor, shape)
            .then(
                if (showBorder) {
                    Modifier.border(1.dp, Color(colors.border), shape)
                } else {
                    Modifier
                },
            )
            .testTag("settings_section_${item.sectionId.name}")
            .semantics {
                contentDescription = item.accessibilityDescription
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = rippleColor),
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        SettingsHomeCard(item = item, colors = colors)
    }
}

@Composable
private fun SettingsDetailGroupContainer(
    items: List<SettingItem>,
    colors: UHabitComposeColors,
    cardHorizontalMargin: androidx.compose.ui.unit.Dp,
    cardVerticalHalfGap: androidx.compose.ui.unit.Dp,
) {
    val isClassic = colors.isClassicSectionLayout
    val shape = if (isClassic) {
        RoundedCornerShape(0.dp)
    } else {
        RoundedCornerShape(colors.habitsCardCornerRadiusDp.coerceAtLeast(0).dp)
    }
    val showBorder = !isClassic && (colors.showCardBorders || colors.isPureBlack)
    val surfaceColor = Color(if (isClassic) colors.background else colors.surface)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isClassic) {
                    Modifier
                } else {
                    Modifier.padding(
                        horizontal = cardHorizontalMargin,
                        vertical = cardVerticalHalfGap,
                    )
                },
            )
            .clip(shape)
            .background(surfaceColor, shape)
            .then(
                if (showBorder) {
                    Modifier.border(1.dp, Color(colors.border), shape)
                } else {
                    Modifier
                },
            ),
    ) {
        items.forEach { item ->
            SettingsDetailRow(item = item, colors = colors)
        }
    }
}
