/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.dialogs

import android.graphics.Color as AndroidColor
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

    var draft by rememberSaveable(initialArgb, stateSaver = ComposeColorDraft.Saver) {
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
                mode = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(12.dp))

        // Content Area with smooth animated transition
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "ColorPickerContentTransition"
            ) { targetMode ->
                when (targetMode) {
                    ColorMode.Grid -> AppleSystemColorGrid(
                        selectedArgb = draft.argb,
                        onColorPicked = ::updateColor
                    )
                    ColorMode.Spectrum -> AppleTrue2dSpectrum(
                        draft = draft,
                        onDraftChange = ::updateColor
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
                            if (isModified) 1.5.dp else 1.dp,
                            Color(if (isModified) colors.accent else colors.divider),
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

/** 12x11 Apple system mosaic with tap/drag selection and floating magnifier loupe. */
@Composable
private fun AppleSystemColorGrid(
    selectedArgb: Int,
    onColorPicked: (ComposeColorDraft) -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val matrix = remember { generateAppleColorMatrix() }
    val currentPicked by rememberUpdatedState(onColorPicked)

    var isHovering by remember { mutableStateOf(false) }
    var hoverOffset by remember { mutableStateOf(Offset.Zero) }
    var activeHoverColor by remember { mutableIntStateOf(selectedArgb) }

    val currentNearest = remember(selectedArgb) { findNearestMatrixPos(matrix, selectedArgb) }

    fun resolveTouch(pos: Offset, width: Float, height: Float) {
        val totalCols = 12
        val totalRows = matrix.size
        val col = ((pos.x / width) * totalCols).toInt().coerceIn(0, totalCols - 1)
        val row = ((pos.y / height) * totalRows).toInt().coerceIn(0, totalRows - 1)
        val color = matrix[row][col]
        activeHoverColor = color
        hoverOffset = pos
        isHovering = true
        currentPicked(ComposeColorDraft.fromArgb(color))
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val totalCols = 12
        val totalRows = matrix.size
        val cellSide = maxWidth / totalCols
        val gridHeight = cellSide * totalRows
        val gridShape = RoundedCornerShape(12.dp)

        Box(
            Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .clip(gridShape)
                .background(Color(colors.surfaceVariant))
                .border(1.dp, Color(colors.divider), gridShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            resolveTouch(offset, size.width.toFloat(), size.height.toFloat())
                            tryAwaitRelease()
                            isHovering = false
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            resolveTouch(offset, size.width.toFloat(), size.height.toFloat())
                        },
                        onDragEnd = { isHovering = false },
                        onDragCancel = { isHovering = false }
                    ) { change, _ ->
                        resolveTouch(change.position, size.width.toFloat(), size.height.toFloat())
                        change.consume()
                    }
                }
        ) {
            Column(Modifier.fillMaxSize()) {
                matrix.forEachIndexed { r, rowColors ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(cellSide)
                    ) {
                        rowColors.forEachIndexed { c, cellColor ->
                            val isSelected = currentNearest.first == r && currentNearest.second == c
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(cellColor))
                            ) {
                                if (isSelected) {
                                    AppleSelectionBorder()
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Magnifier Loupe during touch/drag
        if (isHovering) {
            val loupeWidth = 108.dp
            val loupeHeight = 44.dp
            val density = LocalDensity.current
            val touchXDp = with(density) { hoverOffset.x.toDp() }
            val touchYDp = with(density) { hoverOffset.y.toDp() }
            val loupeX = (touchXDp - loupeWidth / 2).coerceIn(8.dp, (maxWidth - loupeWidth - 8.dp).coerceAtLeast(8.dp))
            val loupeY = (touchYDp - loupeHeight - 16.dp).coerceAtLeast(8.dp)

            Box(
                Modifier
                    .offset(x = loupeX, y = loupeY)
                    .width(loupeWidth)
                    .height(loupeHeight)
                    .shadow(8.dp, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(colors.surface))
                    .border(1.dp, Color(colors.divider), RoundedCornerShape(22.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(activeHoverColor))
                            .border(1.5.dp, Color.White, CircleShape)
                            .shadow(1.dp, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        ColorPickerUtils.toHex(activeHoverColor),
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
}

/** Contrast-safe white + dark selection outline for mosaic cells. */
@Composable
private fun BoxScope.AppleSelectionBorder() {
    Canvas(Modifier.fillMaxSize()) {
        val strokeW = 2.dp.toPx()
        val inset = 1.dp.toPx()
        drawRect(
            color = Color.Black.copy(alpha = 0.40f),
            topLeft = Offset(0f, 0f),
            size = size,
            style = Stroke(strokeW + 1.dp.toPx())
        )
        drawRect(
            color = Color.White,
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2 * inset, size.height - 2 * inset),
            style = Stroke(strokeW)
        )
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

/** True Multihue 2D Spectrum with theme border and triple-contour reticle. */
@Composable
private fun AppleTrue2dSpectrum(
    draft: ComposeColorDraft,
    onDraftChange: (ComposeColorDraft) -> Unit,
) {
    val currentChange by rememberUpdatedState(onDraftChange)
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

    fun applyTouch(p: Offset, w: Int, h: Int, inset: Float) {
        val clampedX = ((p.x - inset) / (w - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val clampedY = ((p.y - inset) / (h - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val hue = clampedY * 360f
        val s: Float
        val v: Float
        if (clampedX <= 0.45f) {
            s = (clampedX / 0.45f).coerceIn(0f, 1f)
            v = 1.0f
        } else {
            s = 1.0f
            v = (1.0f - (clampedX - 0.45f) / 0.55f).coerceIn(0f, 1f)
        }
        currentChange(ComposeColorDraft(AndroidColor.HSVToColor(floatArrayOf(hue, s, v)), hue, s, v))
    }

    val colors = LocalUHabitComposeColors.current
    val spectrumDesc = stringResource(R.string.color_picker_mode_spectrum)

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
                    detectTapGestures { applyTouch(it, size.width, size.height, 12.dp.toPx()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { applyTouch(it, size.width, size.height, 12.dp.toPx()) }) { change, _ ->
                        applyTouch(change.position, size.width, size.height, 12.dp.toPx())
                        change.consume()
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

            val ry = inset + (draft.hue / 360f).coerceIn(0f, 1f) * (size.height - 2 * inset)
            val rx = if (draft.saturation < 1.0f) {
                inset + (draft.saturation * 0.45f) * (size.width - 2 * inset)
            } else {
                inset + (0.45f + (1.0f - draft.value) * 0.55f) * (size.width - 2 * inset)
            }

            val reticleRadius = 13.dp.toPx()
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = reticleRadius + 1.25.dp.toPx(),
                center = Offset(rx, ry + 1.dp.toPx()),
                style = Stroke(3.5.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = reticleRadius,
                center = Offset(rx, ry),
                style = Stroke(2.5.dp.toPx())
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = reticleRadius - 1.25.dp.toPx(),
                center = Offset(rx, ry),
                style = Stroke(0.75.dp.toPx())
            )
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
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .pointerInput(range) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val next = range.first + ((range.last - range.first) * frac).roundToInt()
                                currentChange(next)
                            }
                        }
                        .pointerInput(range) {
                            detectDragGestures { change, _ ->
                                val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val next = range.first + ((range.last - range.first) * frac).roundToInt()
                                currentChange(next)
                                change.consume()
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
                    drawDualContourThumb(Offset(cx, cy), thumbColor)
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

/** Dual-contour custom slider thumb with white outer ring and soft drop shadow. */
private fun DrawScope.drawDualContourThumb(center: Offset, thumbFill: Color) {
    val outerRadius = 14.dp.toPx()
    val innerRadius = 11.5.dp.toPx()

    drawCircle(
        color = Color.Black.copy(alpha = 0.30f),
        radius = outerRadius + 1.25.dp.toPx(),
        center = center + Offset(0f, 1.25.dp.toPx())
    )
    drawCircle(
        color = Color.White,
        radius = outerRadius,
        center = center
    )
    drawCircle(
        color = thumbFill,
        radius = innerRadius,
        center = center
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.15f),
        radius = innerRadius,
        center = center,
        style = Stroke(0.75.dp.toPx())
    )
}
