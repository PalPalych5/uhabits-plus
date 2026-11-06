/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.dialogs

import android.graphics.Color as AndroidColor
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.utils.toFixedAndroidColor
import org.isoron.uhabits.utils.toPaletteColor
import kotlin.math.roundToInt

/** Three unified color modes inspired by modern system pickers. */
enum class ColorMode(@StringRes val titleRes: Int) {
    Grid(R.string.color_picker_mode_grid),
    Spectrum(R.string.color_picker_mode_spectrum),
    Sliders(R.string.color_picker_mode_sliders),
}

/**
 * UHabit Next Production Compose Color Picker.
 *
 * Combines Apple-grade clarity, dense 12x11 square mosaic, true multihue 2D spectrum,
 * live dynamic gradient sliders, and a clean consolidated Glyph footer (✕ | Before → After | ✓).
 */
@Composable
fun UHabitColorPickerContent(
    selectedColor: PaletteColor?,
    onApply: (PaletteColor) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialMode: ColorMode = ColorMode.Grid,
    initialHsvChannels: Boolean = false,
) {
    val context = LocalContext.current
    val colors = LocalUHabitComposeColors.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val initialArgb = selectedColor?.toFixedAndroidColor() ?: ColorPickerUtils.DEFAULT_COLOR

    var draft by remember(initialArgb) {
        mutableStateOf(ComposeColorDraft.fromArgb(initialArgb))
    }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var isHsvChannels by rememberSaveable { mutableStateOf(initialHsvChannels) }
    var hexText by rememberSaveable(initialArgb) { mutableStateOf(ColorPickerUtils.toHex(initialArgb)) }
    var hexInvalid by remember { mutableStateOf(false) }

    fun updateColor(next: ComposeColorDraft) {
        draft = next
        hexText = ColorPickerUtils.toHex(next.argb)
        hexInvalid = false
    }

    fun dismissKeyboard() {
        focus.clearFocus()
        keyboard?.hide()
    }

    val isModified = draft.argb != initialArgb

    // Hoisted Loupe state across Grid and Spectrum overlays
    var loupeVisible by remember { mutableStateOf(false) }
    var loupeColor by remember { mutableIntStateOf(draft.argb) }
    var loupeAnchor by remember { mutableStateOf(Offset.Zero) }
    var loupeSelectionCenter by remember { mutableStateOf(Offset.Zero) }
    var loupeSelectionRadius by remember { mutableFloatStateOf(0f) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color(colors.surface))
            .navigationBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                dismissKeyboard()
            }
    ) {
        // Subtle Drag Handle
        Box(
            Modifier
                .padding(top = 10.dp, bottom = 10.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(colors.secondary).copy(alpha = 0.3f))
                .align(Alignment.CenterHorizontally)
        )

        // Mode Selector: Segmented Control [ Сетка | Спектр | Ползунки ] directly above content
        IosSegmentedControl(
            modes = ColorMode.entries,
            selected = mode,
            onSelect = {
                dismissKeyboard()
                loupeVisible = false
                mode = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(12.dp))

        // Content Area with smooth animated transition
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            val contentWidth = maxWidth
            val gridSide = contentWidth / 12
            val gridContentHeight = gridSide * 11
            val currentContentHeight = when (mode) {
                ColorMode.Grid -> gridContentHeight
                ColorMode.Spectrum -> 240.dp
                ColorMode.Sliders -> 300.dp
            }

            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "ColorPickerContentTransition"
            ) { targetMode ->
                when (targetMode) {
                    ColorMode.Grid -> AppleSystemColorGrid(
                        selectedArgb = draft.argb,
                        onColorPicked = ::updateColor,
                        onLoupeStateChange = { visible, color, touch, selCenter, selRadius ->
                            loupeVisible = visible
                            if (visible) {
                                loupeColor = color
                                loupeAnchor = touch
                                loupeSelectionCenter = selCenter
                                loupeSelectionRadius = selRadius
                            }
                        }
                    )
                    ColorMode.Spectrum -> AppleTrue2dSpectrum(
                        draft = draft,
                        onDraftChange = ::updateColor,
                        onLoupeStateChange = { visible, color, touch, selCenter, selRadius ->
                            loupeVisible = visible
                            if (visible) {
                                loupeColor = color
                                loupeAnchor = touch
                                loupeSelectionCenter = selCenter
                                loupeSelectionRadius = selRadius
                            }
                        }
                    )
                    ColorMode.Sliders -> IosLiveSlidersView(
                        draft = draft,
                        isHsv = isHsvChannels,
                        hex = hexText,
                        hexInvalid = hexInvalid,
                        onHsvToggle = {
                            dismissKeyboard()
                            isHsvChannels = it
                        },
                        onHexChange = { text ->
                            hexText = text
                            val parsed = ColorPickerUtils.parseHex(text)
                            if (parsed != null) {
                                hexInvalid = false
                                draft = draft.withArgb(parsed)
                            } else {
                                hexInvalid = true
                            }
                        },
                        onDraftChange = ::updateColor
                    )
                }
            }

            // Shared Floating HEX Loupe - rendered OUTSIDE AnimatedContent to guarantee zero layout clipping
            FloatingHexLoupe(
                visible = loupeVisible,
                color = loupeColor,
                touchPoint = loupeAnchor,
                selectionCenter = loupeSelectionCenter,
                selectionRadius = loupeSelectionRadius,
                contentWidth = contentWidth,
                contentHeight = currentContentHeight
            )
        }

        Spacer(Modifier.height(12.dp))

        // Consolidated Glyph Footer: Left (✕) | Center [Old] → [New] | Right (✓)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dismiss Action: Left Circular (✕)
            val cancelDesc = stringResource(R.string.color_picker_cancel)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(colors.surfaceVariant))
                    .semantics { contentDescription = cancelDesc; role = Role.Button }
                    .clickable {
                        dismissKeyboard()
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tabler_x),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.foreground)),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Center Comparison Swatches: [Initial Swatch] → [Draft Swatch]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val restoreDesc = stringResource(R.string.custom_color_restore_original)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(initialArgb))
                        .border(
                            1.dp,
                            Color(colors.divider),
                            RoundedCornerShape(8.dp)
                        )
                        .semantics {
                            contentDescription = restoreDesc
                            role = Role.Button
                            if (!isModified) disabled()
                        }
                        .clickable(enabled = isModified) {
                            dismissKeyboard()
                            updateColor(ComposeColorDraft.fromArgb(initialArgb))
                        }
                )

                Image(
                    painter = painterResource(R.drawable.ic_tabler_chevron_right),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary)),
                    modifier = Modifier.size(14.dp)
                )

                val draftDesc = "Текущий цвет"
                Box(
                    Modifier
                        .size(36.dp)
                        .shadow(1.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(draft.argb))
                        .border(
                            1.dp,
                            Color(colors.divider),
                            RoundedCornerShape(8.dp)
                        )
                        .semantics { contentDescription = draftDesc }
                )
            }

            // Apply Action: Right Circular (✓) in Accent Color
            val applyDesc = stringResource(R.string.done_label)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(colors.accent))
                    .semantics { contentDescription = applyDesc; role = Role.Button }
                    .clickable {
                        dismissKeyboard()
                        onApply(draft.argb.toPaletteColor(context))
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tabler_check),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(ColorPickerUtils.onColor(colors.accent))),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** Handles back press dispatching for Compose dialogs / bottom sheets. */
@Composable
fun ColorPickerBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity ?: return
    val current by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        val callback = object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = current()
        }
        activity.onBackPressedDispatcher.addCallback(activity, callback)
        onDispose { callback.remove() }
    }
}

/** Standalone Dialog presentation for workflows that invoke the picker as a modal dialog. */
@Composable
fun UHabitColorPickerDialog(
    selectedColor: PaletteColor?,
    onApply: (PaletteColor) -> Unit,
    onDismiss: () -> Unit,
    initialMode: ColorMode = ColorMode.Grid,
) {
    Dialog(onDismissRequest = onDismiss) {
        UHabitColorPickerContent(
            selectedColor = selectedColor,
            onApply = onApply,
            onDismiss = onDismiss,
            initialMode = initialMode,
            modifier = Modifier.widthIn(max = 480.dp)
        )
    }
}

// ============================================================================
// Internal Components & Views
// ============================================================================

/** Segmented Control [ Сетка | Спектр | Ползунки ] */
@Composable
private fun IosSegmentedControl(
    modes: List<ColorMode>,
    selected: ColorMode,
    onSelect: (ColorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalUHabitComposeColors.current
    val containerShape = RoundedCornerShape(9.dp)

    Row(
        modifier
            .height(36.dp)
            .clip(containerShape)
            .background(Color(colors.surfaceVariant))
            .border(0.5.dp, Color(colors.divider), containerShape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { item ->
            val isSelected = item == selected
            val itemShape = RoundedCornerShape(7.dp)
            val elevation by animateDpAsState(if (isSelected) 2.dp else 0.dp, label = "SegmentElevation")

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (isSelected) Modifier.shadow(elevation, itemShape) else Modifier)
                    .clip(itemShape)
                    .background(if (isSelected) Color(colors.surface) else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(item) }
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(item.titleRes),
                    style = TextStyle(
                        color = Color(if (isSelected) colors.foreground else colors.secondary),
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}

/**
 * Pixel-clean, mathematically circular color swatch for loupe preview.
 * Uses direct subpixel Canvas.drawCircle rendering instead of clip/shadow/border modifiers,
 * avoiding Skia outline approximations or jagged polygonal edges.
 */
@Composable
private fun LoupeColorSwatch(
    color: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.size(24.dp)) {
        val r = size.minDimension / 2f
        val c = center
        // 1. Precise subpixel anti-aliased fill
        drawCircle(color = Color(color), radius = r - 1.25.dp.toPx(), center = c)
        // 2. Crisp white ring
        drawCircle(
            color = Color.White,
            radius = r - 1.25.dp.toPx(),
            center = c,
            style = Stroke(1.5.dp.toPx())
        )
        // 3. Symmetrical outer dark hairline (0.5dp) for contrast on white/light backgrounds
        drawCircle(
            color = Color.Black.copy(alpha = 0.25f),
            radius = r - 0.25.dp.toPx(),
            center = c,
            style = Stroke(0.5.dp.toPx())
        )
    }
}


/** Shared tactile spring curve for responsive physical touch feedback. */
private val TactileSpringFloat = spring<Float>(
    dampingRatio = 0.82f,
    stiffness = 420f
)

internal enum class LoupePlacementMode {
    ABOVE,
    BELOW,
    SIDE_RIGHT,
    SIDE_LEFT
}

internal data class LoupePlacementResult(
    val x: Float,
    val y: Float,
    val mode: LoupePlacementMode
)

/**
 * Geometric collision-avoidance solver for the floating loupe.
 *
 * Considers 3 regions:
 * 1. Finger touch point with comfortable finger clearance (~36dp).
 * 2. Selection indicator/reticle bounds with >= 14dp clearance (never obscured).
 * 3. Safe overlay surface bounds [6dp, host - 6dp] (never clipped).
 *
 * Stability:
 * - Hysteresis deadband (~20dp) prevents flipping loops near boundaries.
 * - Mode stickiness: maintains current mode as long as safe; reevaluates only when necessary.
 */
internal object LoupeCollisionSolver {
    const val LOUPE_WIDTH_DP = 108f
    const val LOUPE_HEIGHT_DP = 42f
    const val MARGIN_DP = 6f
    const val MIN_SEL_CLEARANCE_DP = 14f
    const val FINGER_CLEARANCE_DP = 36f
    const val HYSTERESIS_DEADBAND_DP = 20f

    fun calculatePlacement(
        touchPoint: Offset,
        selectionCenter: Offset,
        selectionRadius: Float,
        contentWidthPx: Float,
        contentHeightPx: Float,
        density: Float,
        currentMode: LoupePlacementMode = LoupePlacementMode.ABOVE
    ): LoupePlacementResult {
        val loupeWPx = LOUPE_WIDTH_DP * density
        val loupeHPx = LOUPE_HEIGHT_DP * density
        val marginPx = MARGIN_DP * density
        val minSelClearancePx = MIN_SEL_CLEARANCE_DP * density
        val fingerClearancePx = FINGER_CLEARANCE_DP * density
        val hysteresisPx = HYSTERESIS_DEADBAND_DP * density

        val maxWPx = contentWidthPx
        val maxHPx = contentHeightPx

        val selR = if (selectionRadius > 1f) selectionRadius else (14f * density)
        val selC = if (selectionCenter != Offset.Zero) selectionCenter else touchPoint
        val selLeft = selC.x - selR
        val selTop = selC.y - selR
        val selRight = selC.x + selR
        val selBottom = selC.y + selR

        // Returns true if candidate (cx, cy) is fully inside host bounds and does not intersect selection clearance
        fun isCandidateSafe(cx: Float, cy: Float): Boolean {
            val insideX = cx >= marginPx && (cx + loupeWPx) <= (maxWPx - marginPx)
            val insideY = cy >= marginPx && (cy + loupeHPx) <= (maxHPx - marginPx)
            if (!insideX || !insideY) return false

            val overlapsSelection = !(
                (cx + loupeWPx) < (selLeft - minSelClearancePx) ||
                cx > (selRight + minSelClearancePx) ||
                (cy + loupeHPx) < (selTop - minSelClearancePx) ||
                cy > (selBottom + minSelClearancePx)
            )
            return !overlapsSelection
        }

        // Candidate 1: ABOVE (preferred)
        val aboveX = (touchPoint.x - loupeWPx / 2f).coerceIn(marginPx, (maxWPx - loupeWPx - marginPx).coerceAtLeast(marginPx))
        val aboveY = minOf(touchPoint.y - fingerClearancePx, selTop - minSelClearancePx) - loupeHPx
        val isAboveSafe = isCandidateSafe(aboveX, aboveY)
        val isAboveComfortable = isAboveSafe && (aboveY >= marginPx + hysteresisPx)

        // Candidate 2: BELOW
        val belowX = (touchPoint.x - loupeWPx / 2f).coerceIn(marginPx, (maxWPx - loupeWPx - marginPx).coerceAtLeast(marginPx))
        val belowY = maxOf(touchPoint.y + fingerClearancePx, selBottom + minSelClearancePx)
        val isBelowSafe = isCandidateSafe(belowX, belowY)

        // Candidate 3: SIDE_RIGHT
        val sideRightX = maxOf(touchPoint.x + fingerClearancePx, selRight + minSelClearancePx)
        val sideRightY = (touchPoint.y - loupeHPx / 2f).coerceIn(marginPx, (maxHPx - loupeHPx - marginPx).coerceAtLeast(marginPx))
        val isSideRightSafe = isCandidateSafe(sideRightX, sideRightY)

        // Candidate 4: SIDE_LEFT
        val sideLeftX = minOf(touchPoint.x - fingerClearancePx, selLeft - minSelClearancePx) - loupeWPx
        val sideLeftY = (touchPoint.y - loupeHPx / 2f).coerceIn(marginPx, (maxHPx - loupeHPx - marginPx).coerceAtLeast(marginPx))
        val isSideLeftSafe = isCandidateSafe(sideLeftX, sideLeftY)

        // Select mode with stability, stickiness and hysteresis
        val nextMode = when (currentMode) {
            LoupePlacementMode.ABOVE -> {
                if (isAboveSafe) LoupePlacementMode.ABOVE
                else if (isBelowSafe) LoupePlacementMode.BELOW
                else if (isSideRightSafe) LoupePlacementMode.SIDE_RIGHT
                else if (isSideLeftSafe) LoupePlacementMode.SIDE_LEFT
                else LoupePlacementMode.ABOVE
            }
            LoupePlacementMode.BELOW -> {
                // Switch back to preferred ABOVE only if comfortably safe (prevents threshold oscillation)
                if (isAboveComfortable) LoupePlacementMode.ABOVE
                else if (isBelowSafe) LoupePlacementMode.BELOW
                else if (isAboveSafe) LoupePlacementMode.ABOVE
                else if (isSideRightSafe) LoupePlacementMode.SIDE_RIGHT
                else if (isSideLeftSafe) LoupePlacementMode.SIDE_LEFT
                else LoupePlacementMode.BELOW
            }
            LoupePlacementMode.SIDE_RIGHT -> {
                if (isAboveComfortable) LoupePlacementMode.ABOVE
                else if (isSideRightSafe) LoupePlacementMode.SIDE_RIGHT
                else if (isBelowSafe) LoupePlacementMode.BELOW
                else if (isAboveSafe) LoupePlacementMode.ABOVE
                else if (isSideLeftSafe) LoupePlacementMode.SIDE_LEFT
                else LoupePlacementMode.SIDE_RIGHT
            }
            LoupePlacementMode.SIDE_LEFT -> {
                if (isAboveComfortable) LoupePlacementMode.ABOVE
                else if (isSideLeftSafe) LoupePlacementMode.SIDE_LEFT
                else if (isBelowSafe) LoupePlacementMode.BELOW
                else if (isAboveSafe) LoupePlacementMode.ABOVE
                else if (isSideRightSafe) LoupePlacementMode.SIDE_RIGHT
                else LoupePlacementMode.SIDE_LEFT
            }
        }

        val finalX: Float
        val finalY: Float
        when (nextMode) {
            LoupePlacementMode.ABOVE -> {
                finalX = aboveX
                finalY = aboveY.coerceIn(marginPx, (maxHPx - loupeHPx - marginPx).coerceAtLeast(marginPx))
            }
            LoupePlacementMode.BELOW -> {
                finalX = belowX
                finalY = belowY.coerceIn(marginPx, (maxHPx - loupeHPx - marginPx).coerceAtLeast(marginPx))
            }
            LoupePlacementMode.SIDE_RIGHT -> {
                finalX = sideRightX.coerceIn(marginPx, (maxWPx - loupeWPx - marginPx).coerceAtLeast(marginPx))
                finalY = sideRightY
            }
            LoupePlacementMode.SIDE_LEFT -> {
                finalX = sideLeftX.coerceIn(marginPx, (maxWPx - loupeWPx - marginPx).coerceAtLeast(marginPx))
                finalY = sideLeftY
            }
        }

        return LoupePlacementResult(finalX, finalY, nextMode)
    }
}

/**
 * Shared floating HEX magnifier loupe with collision-avoidance geometry and pure opacity fade.
 *
 * - Quick tap: never shown.
 * - Hold (>=200ms) or drag: reveals smoothly with opacity-only transition (140ms).
 * - Collision avoidance: guarantees zero overlap with selected cell/reticle and finger.
 * - Release: fades out in-place without moving anchor or teleporting.
 * - Controlled via persistent graphicsLayer alpha (no AnimatedVisibility layout clipping).
 */
@Composable
private fun FloatingHexLoupe(
    visible: Boolean,
    color: Int,
    touchPoint: Offset,
    selectionCenter: Offset,
    selectionRadius: Float,
    contentWidth: androidx.compose.ui.unit.Dp,
    contentHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalUHabitComposeColors.current
    val density = LocalDensity.current
    val loupeWidth = 108.dp
    val loupeHeight = 42.dp

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 140 else 160, easing = FastOutSlowInEasing),
        label = "LoupeAlpha"
    )

    if (alpha > 0.001f) {
        val maxWPx = with(density) { contentWidth.toPx() }
        val maxHPx = with(density) { contentHeight.toPx() }

        var activeMode by remember { mutableStateOf(LoupePlacementMode.ABOVE) }
        LaunchedEffect(visible) {
            if (!visible) {
                activeMode = LoupePlacementMode.ABOVE
            }
        }

        val placement = LoupeCollisionSolver.calculatePlacement(
            touchPoint = touchPoint,
            selectionCenter = selectionCenter,
            selectionRadius = selectionRadius,
            contentWidthPx = maxWPx,
            contentHeightPx = maxHPx,
            density = density.density,
            currentMode = activeMode
        )
        activeMode = placement.mode

        Box(
            Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(placement.x.toInt(), placement.y.toInt())
                }
                .size(loupeWidth, loupeHeight)
                .graphicsLayer { this.alpha = alpha }
                .clip(RoundedCornerShape(21.dp))
                .background(Color(colors.surface))
                .border(1.dp, Color(colors.divider), RoundedCornerShape(21.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoupeColorSwatch(color)
                Spacer(Modifier.width(8.dp))
                BasicText(
                    ColorPickerUtils.toHex(color),
                    style = TextStyle(
                        color = Color(colors.foreground),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

/** 12x11 Apple system mosaic with reliable sliding selection reticle. */
@Composable
private fun AppleSystemColorGrid(
    selectedArgb: Int,
    onColorPicked: (ComposeColorDraft) -> Unit,
    onLoupeStateChange: (Boolean, Int, Offset, Offset, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val matrix = remember { generateAppleColorMatrix() }

    val context = LocalContext.current
    val view = LocalView.current
    val isReducedMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }

    val currentNearest = remember(selectedArgb) { findNearestMatrixPos(matrix, selectedArgb) }
    var selectedPos by remember { mutableStateOf(currentNearest) }

    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val totalCols = 12
        val totalRows = matrix.size
        val cellSide = maxWidth / totalCols
        val gridHeight = cellSide * totalRows
        val gridShape = RoundedCornerShape(12.dp)

        val density = LocalDensity.current
        val cellWPx = with(density) { cellSide.toPx() }
        val cellHPx = cellWPx

        val initialCenterX = (currentNearest.second + 0.5f) * cellWPx
        val initialCenterY = (currentNearest.first + 0.5f) * cellHPx

        // Single authoritative visual position for the indicator center (in pixels)
        var curVisualX by remember { mutableFloatStateOf(initialCenterX) }
        var curVisualY by remember { mutableFloatStateOf(initialCenterY) }
        var activeAnimJob by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(currentNearest) {
            if (currentNearest != selectedPos) {
                selectedPos = currentNearest
                val targetX = (currentNearest.second + 0.5f) * cellWPx
                val targetY = (currentNearest.first + 0.5f) * cellHPx
                activeAnimJob?.cancel()
                if (isReducedMotion) {
                    curVisualX = targetX
                    curVisualY = targetY
                } else {
                    val dx = targetX - curVisualX
                    val dy = targetY - curVisualY
                    val distCells = kotlin.math.sqrt(dx * dx + dy * dy) / cellWPx
                    if (distCells > 0.05f) {
                        val duration = (110 + (distCells * 6f).toInt()).coerceIn(120, 180)
                        activeAnimJob = coroutineScope.launch {
                            val startX = curVisualX
                            val startY = curVisualY
                            launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = startX,
                                    targetValue = targetX,
                                    animationSpec = tween(duration, easing = FastOutSlowInEasing)
                                ) { v, _ -> curVisualX = v }
                            }
                            launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = startY,
                                    targetValue = targetY,
                                    animationSpec = tween(duration, easing = FastOutSlowInEasing)
                                ) { v, _ -> curVisualY = v }
                            }
                        }
                    } else {
                        curVisualX = targetX
                        curVisualY = targetY
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .clip(gridShape)
                .background(Color(colors.surfaceVariant))
                .border(1.dp, Color(colors.divider), gridShape)
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            view.parent?.requestDisallowInterceptTouchEvent(true)

                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val cellW = w / totalCols
                            val cellH = h / totalRows

                            val downCol = ((down.position.x / w) * totalCols).toInt().coerceIn(0, totalCols - 1)
                            val downRow = ((down.position.y / h) * totalRows).toInt().coerceIn(0, totalRows - 1)
                            val downPos = Pair(downRow, downCol)

                            val targetCenterX = (downCol + 0.5f) * cellW
                            val targetCenterY = (downRow + 0.5f) * cellH

                            // Cancel any existing transit immediately on touch down
                            activeAnimJob?.cancel()

                            // Immediate color selection and position update
                            selectedPos = downPos
                            val color = matrix[downRow][downCol]
                            onColorPicked(ComposeColorDraft.fromArgb(color))

                            // Hold timer: reveal loupe ONLY if held for >= 200ms (quick tap never shows loupe!)
                            var lastInteractionOffset = down.position
                            var isLoupeShown = false
                            var holdJob: Job? = coroutineScope.launch {
                                kotlinx.coroutines.delay(200)
                                isLoupeShown = true
                                val selCenter = Offset(curVisualX, curVisualY)
                                onLoupeStateChange(true, color, lastInteractionOffset, selCenter, cellWPx / 2f)
                            }

                            // Smooth transit from current visual position to target cell center (no backward snap)
                            val startX = curVisualX
                            val startY = curVisualY
                            val dx = targetCenterX - startX
                            val dy = targetCenterY - startY
                            val distCells = kotlin.math.sqrt(dx * dx + dy * dy) / cellW

                            if (distCells > 0.05f && !isReducedMotion) {
                                val duration = (110 + (distCells * 6f).toInt()).coerceIn(120, 180)
                                activeAnimJob = coroutineScope.launch {
                                    launch {
                                        androidx.compose.animation.core.animate(
                                            initialValue = startX,
                                            targetValue = targetCenterX,
                                            animationSpec = tween(duration, easing = FastOutSlowInEasing)
                                        ) { v, _ -> curVisualX = v }
                                    }
                                    launch {
                                        androidx.compose.animation.core.animate(
                                            initialValue = startY,
                                            targetValue = targetCenterY,
                                            animationSpec = tween(duration, easing = FastOutSlowInEasing)
                                        ) { v, _ -> curVisualY = v }
                                    }
                                }
                            } else {
                                curVisualX = targetCenterX
                                curVisualY = targetCenterY
                            }

                            val pointerId = down.id
                            var isDragging = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                    if (!change.pressed) break

                                    val moveDist = (change.position - down.position).getDistance()
                                    // Immediate responsive cancellation on any pointer movement (zero drag lag)
                                    if (moveDist > 2.dp.toPx()) {
                                        if (activeAnimJob?.isActive == true) {
                                            activeAnimJob?.cancel()
                                        }
                                        isDragging = true
                                        curVisualX = change.position.x
                                        curVisualY = change.position.y
                                    }

                                    // Deliberate drag threshold for revealing loupe before 200ms hold timer
                                    if (moveDist > viewConfiguration.touchSlop) {
                                        isLoupeShown = true
                                        holdJob?.cancel()
                                        val selCenter = Offset(curVisualX, curVisualY)
                                        onLoupeStateChange(true, matrix[selectedPos.first][selectedPos.second], change.position, selCenter, cellWPx / 2f)
                                    }

                                    if (isDragging) {
                                        change.consume()
                                        lastInteractionOffset = change.position

                                        val curCol = ((change.position.x / w) * totalCols).toInt().coerceIn(0, totalCols - 1)
                                        val curRow = ((change.position.y / h) * totalRows).toInt().coerceIn(0, totalRows - 1)
                                        val curPos = Pair(curRow, curCol)
                                        if (curPos != selectedPos) {
                                            selectedPos = curPos
                                            val dragColor = matrix[curRow][curCol]
                                            onColorPicked(ComposeColorDraft.fromArgb(dragColor))
                                            if (isLoupeShown) {
                                                val selCenter = Offset(curVisualX, curVisualY)
                                                onLoupeStateChange(true, dragColor, change.position, selCenter, cellWPx / 2f)
                                            }
                                        } else if (isLoupeShown) {
                                            val selCenter = Offset(curVisualX, curVisualY)
                                            onLoupeStateChange(true, matrix[curRow][curCol], change.position, selCenter, cellWPx / 2f)
                                        }
                                    }
                                }
                            } finally {
                                holdJob?.cancel()
                                isLoupeShown = false
                                // Fade out cleanly in-place without moving anchor
                                val selCenter = Offset(curVisualX, curVisualY)
                                onLoupeStateChange(false, matrix[selectedPos.first][selectedPos.second], lastInteractionOffset, selCenter, cellWPx / 2f)
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                                val finalTargetX = (selectedPos.second + 0.5f) * cellW
                                val finalTargetY = (selectedPos.first + 0.5f) * cellH

                                if (isDragging) {
                                    activeAnimJob?.cancel()
                                    val releaseX = curVisualX
                                    val releaseY = curVisualY
                                    if (!isReducedMotion) {
                                        val distCells = kotlin.math.sqrt(
                                            (finalTargetX - releaseX) * (finalTargetX - releaseX) +
                                            (finalTargetY - releaseY) * (finalTargetY - releaseY)
                                        ) / cellW
                                        if (distCells > 0.02f) {
                                            val duration = (90 + (distCells * 30f).toInt()).coerceIn(100, 150)
                                            activeAnimJob = coroutineScope.launch {
                                                launch {
                                                    androidx.compose.animation.core.animate(
                                                        initialValue = releaseX,
                                                        targetValue = finalTargetX,
                                                        animationSpec = tween(duration, easing = FastOutSlowInEasing)
                                                    ) { v, _ -> curVisualX = v }
                                                }
                                                launch {
                                                    androidx.compose.animation.core.animate(
                                                        initialValue = releaseY,
                                                        targetValue = finalTargetY,
                                                        animationSpec = tween(duration, easing = FastOutSlowInEasing)
                                                    ) { v, _ -> curVisualY = v }
                                                }
                                            }
                                        } else {
                                            curVisualX = finalTargetX
                                            curVisualY = finalTargetY
                                        }
                                    } else {
                                        curVisualX = finalTargetX
                                        curVisualY = finalTargetY
                                    }
                                }
                            }
                        }
                    }
            ) {
                val cellW = size.width / totalCols
                val cellH = size.height / totalRows

                // 1. All 132 cells are fixed, seamless squares
                for (r in 0 until totalRows) {
                    val rowColors = matrix[r]
                    val top = r * cellH
                    for (c in 0 until totalCols) {
                        drawRect(
                            color = Color(rowColors[c]),
                            topLeft = Offset(c * cellW, top),
                            size = Size(cellW, cellH)
                        )
                    }
                }

                // 2. Continuous sliding selection contour with asymmetric outward corner radius
                val halfW = cellW / 2f
                val halfH = cellH / 2f
                val clampedX = curVisualX.coerceIn(halfW, size.width - halfW)
                val clampedY = curVisualY.coerceIn(halfH, size.height - halfH)

                val colFloat = (clampedX / cellW) - 0.5f
                val rowFloat = (clampedY / cellH) - 0.5f

                // Outer container corner proximity factors [0..1]
                val pTop = (1.0f - rowFloat).coerceIn(0f, 1f)
                val pBottom = (1.0f - (10.0f - rowFloat)).coerceIn(0f, 1f)
                val pLeft = (1.0f - colFloat).coerceIn(0f, 1f)
                val pRight = (1.0f - (11.0f - colFloat)).coerceIn(0f, 1f)

                val cornerFactorTL = pTop * pLeft
                val cornerFactorTR = pTop * pRight
                val cornerFactorBL = pBottom * pLeft
                val cornerFactorBR = pBottom * pRight

                val darkStrokePx = 1.5f.dp.toPx()
                val whiteStrokePx = 1.0f.dp.toPx()
                val halfStroke = darkStrokePx / 2f

                val left = (clampedX - halfW) + halfStroke
                val top = (clampedY - halfH) + halfStroke
                val right = (clampedX + halfW) - halfStroke
                val bottom = (clampedY + halfH) - halfStroke

                val baseR = 4f.dp.toPx()
                val containerCornerR = (12f - 0.75f).dp.toPx() // 11.25dp, concentric with 12dp outer clipped container

                val rTL = baseR + (containerCornerR - baseR) * cornerFactorTL
                val rTR = baseR + (containerCornerR - baseR) * cornerFactorTR
                val rBL = baseR + (containerCornerR - baseR) * cornerFactorBL
                val rBR = baseR + (containerCornerR - baseR) * cornerFactorBR

                val indicatorPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            topLeftCornerRadius = CornerRadius(rTL),
                            topRightCornerRadius = CornerRadius(rTR),
                            bottomRightCornerRadius = CornerRadius(rBR),
                            bottomLeftCornerRadius = CornerRadius(rBL)
                        )
                    )
                }

                // Dual-contrast rendering: outer dark hairline (1.5dp, 45% alpha) + inner crisp white stroke (1.0dp, 95% alpha)
                drawPath(indicatorPath, Color.Black.copy(alpha = 0.45f), style = Stroke(darkStrokePx))
                drawPath(indicatorPath, Color.White.copy(alpha = 0.95f), style = Stroke(whiteStrokePx))
            }
        }
    }
}

/** Generates the 12x11 Apple system matrix (1 grayscale row + 10 hue rows across lightness). */
private fun generateAppleColorMatrix(): List<List<Int>> {
    val rows = mutableListOf<List<Int>>()
    val grays = (0..11).map { i ->
        val v = (255 - i * (255 / 11)).coerceIn(0, 255)
        AndroidColor.rgb(v, v, v)
    }
    rows.add(grays)

    val hues = listOf(210f, 225f, 245f, 275f, 305f, 345f, 10f, 30f, 48f, 75f, 120f, 165f)
    val lightLevels = listOf(
        0.35f to 0.95f,
        0.48f to 0.95f,
        0.62f to 0.95f,
        0.75f to 0.95f,
        0.88f to 0.95f,
        0.98f to 0.90f,
        0.98f to 0.72f,
        0.98f to 0.52f,
        0.99f to 0.35f,
        0.99f to 0.18f,
    )

    for ((value, saturation) in lightLevels) {
        val row = hues.map { hue ->
            AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
        }
        rows.add(row)
    }

    return rows
}

private fun findNearestMatrixPos(matrix: List<List<Int>>, target: Int): Pair<Int, Int> {
    var bestRow = 0
    var bestCol = 0
    var minDiff = Long.MAX_VALUE
    val tr = AndroidColor.red(target)
    val tg = AndroidColor.green(target)
    val tb = AndroidColor.blue(target)

    for (r in matrix.indices) {
        val row = matrix[r]
        for (c in row.indices) {
            val color = row[c]
            val dr = tr - AndroidColor.red(color)
            val dg = tg - AndroidColor.green(color)
            val db = tb - AndroidColor.blue(color)
            val diff = (dr * dr + dg * dg + db * db).toLong()
            if (diff < minDiff) {
                minDiff = diff
                bestRow = r
                bestCol = c
                if (diff == 0L) return Pair(bestRow, bestCol)
            }
        }
    }
    return Pair(bestRow, bestCol)
}

/** True Multihue 2D Spectrum with theme border, inverse projection and transient loupe. */
@Composable
private fun AppleTrue2dSpectrum(
    draft: ComposeColorDraft,
    onDraftChange: (ComposeColorDraft) -> Unit,
    onLoupeStateChange: (Boolean, Int, Offset, Offset, Float) -> Unit,
) {
    val currentChange by rememberUpdatedState(onDraftChange)
    val colors = LocalUHabitComposeColors.current
    val spectrumDesc = stringResource(R.string.color_picker_mode_spectrum)

    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val reticleRadiusPx = with(density) { 14.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val isReducedMotion = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }

    var isTouching by remember { mutableStateOf(false) }
    var touchOffset by remember { mutableStateOf(Offset.Zero) }

    val reticleScale by animateFloatAsState(
        targetValue = if (isTouching) 1.16f else 1.0f,
        animationSpec = if (isReducedMotion) snap() else TactileSpringFloat,
        label = "ReticleScale"
    )
    val reticleStrokeBoostDp by animateFloatAsState(
        targetValue = if (isTouching) 0.6f else 0f,
        animationSpec = if (isReducedMotion) snap() else TactileSpringFloat,
        label = "ReticleStrokeBoost"
    )

    val rainbowColors = remember {
        listOf(
            Color.Red,
            Color(0xFFFF7F00),
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color(0xFF8B00FF),
            Color.Red
        )
    }

    fun applyTouch(p: Offset, w: Int, h: Int, inset: Float, isDeliberateMove: Boolean) {
        touchOffset = p
        val clampedX = ((p.x - inset) / (w - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val clampedY = ((p.y - inset) / (h - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val hue = (clampedY * 360f).coerceIn(0f, 360f)
        val s: Float
        val v: Float
        val mid = 0.45f
        if (clampedX <= mid) {
            s = (clampedX / mid).coerceIn(0f, 1f)
            v = 1.0f
        } else {
            s = 1.0f
            v = (1.0f - (clampedX - mid) / (1.0f - mid)).coerceIn(0f, 1f)
        }
        val newDraft = ComposeColorDraft(AndroidColor.HSVToColor(floatArrayOf(hue, s, v)), hue, s, v)
        currentChange(newDraft)
        if (isDeliberateMove) {
            onLoupeStateChange(true, newDraft.argb, p, touchOffset, reticleRadiusPx)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, Color(colors.divider), RoundedCornerShape(14.dp))
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = spectrumDesc }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            val inset = 12.dp.toPx()
                            isTouching = true
                            applyTouch(down.position, size.width, size.height, inset, isDeliberateMove = false)

                            var lastInteractionOffset = down.position
                            var isDragging = false
                            var isLoupeShown = false
                            // Hold timer: reveal loupe only if held for >= 200ms (quick tap does not show loupe!)
                            var holdJob: Job? = coroutineScope.launch {
                                kotlinx.coroutines.delay(200)
                                isLoupeShown = true
                                onLoupeStateChange(true, draft.argb, lastInteractionOffset, touchOffset, reticleRadiusPx)
                            }

                            val pointerId = down.id
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                    if (!change.pressed) break

                                    if (!isDragging) {
                                        val dist = (change.position - down.position).getDistance()
                                        if (dist > viewConfiguration.touchSlop) {
                                            isDragging = true
                                            isLoupeShown = true
                                            holdJob?.cancel()
                                            onLoupeStateChange(true, draft.argb, change.position, touchOffset, reticleRadiusPx)
                                        }
                                    }
                                    if (isDragging) {
                                        change.consume()
                                        lastInteractionOffset = change.position
                                        applyTouch(change.position, size.width, size.height, inset, isDeliberateMove = isLoupeShown)
                                    }
                                }
                            } finally {
                                holdJob?.cancel()
                                isTouching = false
                                isLoupeShown = false
                                // Fade out cleanly in-place without moving anchor
                                onLoupeStateChange(false, draft.argb, lastInteractionOffset, touchOffset, reticleRadiusPx)
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
            ) {
                val inset = 12.dp.toPx()
                val corner = CornerRadius(14.dp.toPx())

                drawRoundRect(
                    brush = Brush.verticalGradient(rainbowColors, startY = inset, endY = size.height - inset),
                    cornerRadius = corner
                )
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        0.0f to Color.White,
                        0.45f to Color.Transparent,
                        1.0f to Color.Transparent,
                        startX = inset,
                        endX = size.width - inset
                    ),
                    cornerRadius = corner
                )
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1.0f to Color.Black,
                        startX = inset,
                        endX = size.width - inset
                    ),
                    cornerRadius = corner
                )
                drawRoundRect(Color(colors.divider), cornerRadius = corner, style = Stroke(1.dp.toPx()))

                // Well-defined continuous inverse projection for arbitrary draft HSV colors
                val mid = 0.45f
                val w = (1f - draft.saturation) * draft.value
                val b = 1f - draft.value
                val xFrac = if (b > w) {
                    mid + (1f - mid) * b
                } else {
                    mid * (1f - w)
                }.coerceIn(0f, 1f)

                val rx = inset + xFrac * (size.width - 2 * inset)
                val ry = inset + (draft.hue / 360f).coerceIn(0f, 1f) * (size.height - 2 * inset)

                val reticleRadius = 12.5.dp.toPx() * reticleScale
                val strokeWidth = (2.0f + reticleStrokeBoostDp).dp.toPx()
                val darkAlpha = if (isTouching) 0.45f else 0.30f

                // 1. Symmetrical outer dark hairline for contrast on bright/white spectrum areas
                drawCircle(
                    color = Color.Black.copy(alpha = darkAlpha),
                    radius = reticleRadius + strokeWidth / 2 + 0.5.dp.toPx(),
                    center = Offset(rx, ry),
                    style = Stroke(0.75.dp.toPx())
                )
                // 2. Crisp white ring with tactile stroke expansion
                drawCircle(
                    color = Color.White,
                    radius = reticleRadius,
                    center = Offset(rx, ry),
                    style = Stroke(strokeWidth)
                )
                // 3. Symmetrical inner dark hairline for contrast on pure white/yellow
                drawCircle(
                    color = Color.Black.copy(alpha = darkAlpha * 0.85f),
                    radius = (reticleRadius - strokeWidth / 2 - 0.5.dp.toPx()).coerceAtLeast(1.dp.toPx()),
                    center = Offset(rx, ry),
                    style = Stroke(0.75.dp.toPx())
                )
            }
        }

    }
}

/** Precision Sliders View with RGB/HSV toggle and live composite dynamic gradient tracks. */
@Composable
private fun IosLiveSlidersView(
    draft: ComposeColorDraft,
    isHsv: Boolean,
    hex: String,
    hexInvalid: Boolean,
    onHsvToggle: (Boolean) -> Unit,
    onHexChange: (String) -> Unit,
    onDraftChange: (ComposeColorDraft) -> Unit,
) {
    val colors = LocalUHabitComposeColors.current

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .animateContentSize()
    ) {
        // RGB / HSV Mode Toggle Button
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            val pillShape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .width(160.dp)
                    .height(32.dp)
                    .clip(pillShape)
                    .background(Color(colors.surfaceVariant))
                    .border(0.5.dp, Color(colors.divider), pillShape)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (!isHsv) Color(colors.surface) else Color.Transparent)
                        .clickable { onHsvToggle(false) },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("RGB", style = TextStyle(color = Color(if (!isHsv) colors.foreground else colors.secondary), fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isHsv) Color(colors.surface) else Color.Transparent)
                        .clickable { onHsvToggle(true) },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("HSV", style = TextStyle(color = Color(if (isHsv) colors.foreground else colors.secondary), fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (!isHsv) {
            val r = AndroidColor.red(draft.argb)
            val g = AndroidColor.green(draft.argb)
            val b = AndroidColor.blue(draft.argb)

            IosLiveChannelRow(
                label = stringResource(R.string.custom_color_red),
                value = r,
                range = 0..255,
                stops = listOf(Color(0, g, b), Color(255, g, b)),
                fraction = r / 255f,
                thumbColor = Color(r, g, b),
                onValueChange = { onDraftChange(draft.withArgb(AndroidColor.rgb(it, g, b))) }
            )
            IosLiveChannelRow(
                label = stringResource(R.string.custom_color_green),
                value = g,
                range = 0..255,
                stops = listOf(Color(r, 0, b), Color(r, 255, b)),
                fraction = g / 255f,
                thumbColor = Color(r, g, b),
                onValueChange = { onDraftChange(draft.withArgb(AndroidColor.rgb(r, it, b))) }
            )
            IosLiveChannelRow(
                label = stringResource(R.string.custom_color_blue),
                value = b,
                range = 0..255,
                stops = listOf(Color(r, g, 0), Color(r, g, 255)),
                fraction = b / 255f,
                thumbColor = Color(r, g, b),
                onValueChange = { onDraftChange(draft.withArgb(AndroidColor.rgb(r, g, it))) }
            )
        } else {
            val h = draft.hue.roundToInt()
            val s = (draft.saturation * 100).roundToInt()
            val v = (draft.value * 100).roundToInt()
            val rainbow = remember { listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red) }

            IosLiveChannelRow(
                label = stringResource(R.string.custom_color_hue_description),
                value = h,
                range = 0..360,
                stops = rainbow,
                fraction = draft.hue / 360f,
                unit = "°",
                thumbColor = Color(draft.argb),
                onValueChange = { onDraftChange(draft.withHsv(it.toFloat(), draft.saturation, draft.value)) }
            )
            IosLiveChannelRow(
                label = stringResource(R.string.custom_color_saturation),
                value = s,
                range = 0..100,
                stops = listOf(Color.hsv(draft.hue, 0f, draft.value), Color.hsv(draft.hue, 1f, draft.value)),
                fraction = draft.saturation,
                unit = "%",
                thumbColor = Color(draft.argb),
                onValueChange = { onDraftChange(draft.withHsv(draft.hue, it / 100f, draft.value)) }
            )
            IosLiveChannelRow(
                label = stringResource(R.string.custom_color_brightness),
                value = v,
                range = 0..100,
                stops = listOf(Color.Black, Color.hsv(draft.hue, draft.saturation, 1f)),
                fraction = draft.value,
                unit = "%",
                thumbColor = Color(draft.argb),
                onValueChange = { onDraftChange(draft.withHsv(draft.hue, draft.saturation, it / 100f)) }
            )
        }

        Spacer(Modifier.height(4.dp))

        // HEX row
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "HEX",
                style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            )

            Row(
                Modifier
                    .width(112.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(colors.surfaceVariant))
                    .border(0.5.dp, Color(if (hexInvalid) 0xFFE53935.toInt() else colors.divider), RoundedCornerShape(7.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText("#", style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp, fontFamily = FontFamily.Monospace))
                Spacer(Modifier.width(4.dp))
                BasicTextField(
                    value = hex.removePrefix("#"),
                    onValueChange = { onHexChange("#" + it.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = Color(if (hexInvalid) 0xFFE53935.toInt() else colors.foreground),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
            }
        }
    }
}

/** Single channel row with live mixed track, 48dp touch target, and comfortable numeric editor. */
@Composable
private fun IosLiveChannelRow(
    label: String,
    value: Int,
    range: IntRange,
    stops: List<Color>,
    fraction: Float,
    thumbColor: Color,
    unit: String = "",
    onValueChange: (Int) -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    var editing by remember(value) { mutableStateOf(value.toString()) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = Color(colors.secondary),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        )

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Slider Track with 48dp touch target
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val currentChange by rememberUpdatedState(onValueChange)
                val context = LocalContext.current
                val view = LocalView.current
                val isReducedMotion = remember {
                    try {
                        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                    } catch (_: Exception) {
                        false
                    }
                }
                var isDragging by remember { mutableStateOf(false) }
                val thumbScale by animateFloatAsState(
                    targetValue = if (isDragging) 1.15f else 1.0f,
                    animationSpec = if (isReducedMotion) snap() else TactileSpringFloat,
                    label = "SliderThumbScale"
                )

                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .pointerInput(range) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                isDragging = true
                                val frac = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val next = range.first + ((range.last - range.first) * frac).roundToInt()
                                currentChange(next)

                                val pointerId = down.id
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val currentFrac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        val updatedVal = range.first + ((range.last - range.first) * currentFrac).roundToInt()
                                        currentChange(updatedVal)
                                    }
                                } finally {
                                    isDragging = false
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                        }
                ) {
                    val trackHeight = 16.dp.toPx()
                    val trackCorner = CornerRadius(trackHeight / 2)
                    val top = (size.height - trackHeight) / 2

                    drawRoundRect(
                        brush = Brush.horizontalGradient(stops),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, trackHeight),
                        cornerRadius = trackCorner
                    )
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.12f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, trackHeight),
                        cornerRadius = trackCorner,
                        style = Stroke(1.dp.toPx())
                    )

                    val cx = (fraction.coerceIn(0f, 1f) * size.width).coerceIn(15.dp.toPx(), size.width - 15.dp.toPx())
                    val cy = size.height / 2
                    drawDualContourThumb(Offset(cx, cy), thumbColor, 12.5f * thumbScale)
                }
            }

            Spacer(Modifier.width(12.dp))

            // Numeric input editor
            val numShape = RoundedCornerShape(7.dp)
            var isFocused by remember { mutableStateOf(false) }

            Row(
                Modifier
                    .width(56.dp)
                    .height(34.dp)
                    .clip(numShape)
                    .background(Color(colors.surfaceVariant))
                    .border(
                        0.5.dp,
                        Color(if (isFocused) colors.accent else colors.divider),
                        numShape
                    )
                    .onFocusChanged { isFocused = it.isFocused }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                BasicTextField(
                    value = editing,
                    onValueChange = { text ->
                        editing = text.filter { it.isDigit() }
                        editing.toIntOrNull()?.let { v ->
                            if (v in range) onValueChange(v)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        color = Color(colors.foreground),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (unit.isNotEmpty()) {
                    BasicText(unit, style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp))
                }
            }
        }
    }
}

/** Dual-contour custom slider thumb with concentric crisp outlines and zero fake elevation. */
private fun DrawScope.drawDualContourThumb(
    center: Offset,
    thumbFill: Color,
    radiusDp: Float = 12.5f
) {
    val outerRadius = radiusDp.dp.toPx()
    val innerRadius = (radiusDp - 2.5f).dp.toPx()

    // 1. Symmetrical outer dark hairline (0.5dp) for contrast on bright tracks
    drawCircle(
        color = Color.Black.copy(alpha = 0.25f),
        radius = outerRadius + 0.5.dp.toPx(),
        center = center,
        style = Stroke(0.5.dp.toPx())
    )
    // 2. Crisp white outer ring
    drawCircle(
        color = Color.White,
        radius = outerRadius,
        center = center
    )
    // 3. Inner filled color circle
    drawCircle(
        color = thumbFill,
        radius = innerRadius,
        center = center
    )
    // 4. Subtle inner hairline between color and white ring
    drawCircle(
        color = Color.Black.copy(alpha = 0.15f),
        radius = innerRadius,
        center = center,
        style = Stroke(0.5.dp.toPx())
    )
}
