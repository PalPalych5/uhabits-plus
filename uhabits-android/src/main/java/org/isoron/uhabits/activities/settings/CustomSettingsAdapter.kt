package org.isoron.uhabits.activities.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.preferences.Preferences

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

class CustomSettingsAdapter(
    private val context: Context,
    private val prefs: Preferences,
    private var items: List<SettingItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var paletteSignature = currentPaletteSignature()

    init {
        setHasStableIds(true)
    }

    private fun cardId(position: Int): String? {
        if (position !in items.indices) return null
        val item = items[position]
        return if (item is SettingItem.Section) "section:${item.sectionId.name}" else item.presentation.cardId
    }

    private fun isSameCard(position: Int, cardId: String): Boolean {
        return cardId(position) == cardId
    }

    private fun applyCardDecoration(view: View, position: Int) {
        val isClassic = prefs.isClassicSectionLayout
        val showBorders = prefs.showHabitCardBorders
        val density = context.resources.displayMetrics.density
        val radius = if (isClassic) 0f else (prefs.habitsCardCornerRadius.coerceAtLeast(0) * density)

        val item = items[position]
        val cardId = cardId(position) ?: return
        val isFirst = !isSameCard(position - 1, cardId)
        val isLast = !isSameCard(position + 1, cardId)
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)

        val radii = when {
            isClassic -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            isFirst && isLast -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            isFirst -> floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            isLast -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(palette.surface)
            if (!isClassic && (showBorders || palette.isPureBlack)) {
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.border)
            }
        }

        val maskDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(android.graphics.Color.WHITE)
        }

        val rippleColor = ColorUtils.setAlphaComponent(palette.onSurface, if (palette.isDark) 28 else 18)
        
        val rippleDrawable = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            backgroundDrawable,
            maskDrawable
        )

        view.background = rippleDrawable
        view.clipToOutline = !isClassic

        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            val marginHorizontal = if (isClassic) 0 else context.resources.getDimensionPixelSize(R.dimen.settings_card_horizontal_margin)
            val halfGap = if (isClassic) 0 else context.resources.getDimensionPixelSize(R.dimen.settings_card_vertical_gap_half)
            val marginTop = if (isFirst) halfGap else 0
            val marginBottom = if (isLast) halfGap else 0
            lp.setMargins(marginHorizontal, marginTop, marginHorizontal, marginBottom)
            view.layoutParams = lp
        }
    }

    companion object {
        private const val TYPE_HOME_SECTION = 1
        private const val TYPE_DETAIL_ROW = 2
    }

    fun updateItems(newItems: List<SettingItem>) {
        check(newItems.map { it.stableKey }.distinct().size == newItems.size) {
            "Settings list contains duplicate stable keys"
        }
        val newPaletteSignature = currentPaletteSignature()
        if (newPaletteSignature != paletteSignature) {
            paletteSignature = newPaletteSignature
            items = newItems
            notifyDataSetChanged()
            return
        }

        val oldItems = items
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size

            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].stableKey == newItems[newItemPosition].stableKey
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return SettingsItemDiff.contentSame(oldItems[oldItemPosition], newItems[newItemPosition])
            }

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                return SettingsItemDiff.changePayload(oldItems[oldItemPosition], newItems[newItemPosition])
            }
        })
        items = newItems
        paletteSignature = newPaletteSignature
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long {
        return stableIdFor(items[position].stableKey)
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is SettingItem.Section) TYPE_HOME_SECTION else TYPE_DETAIL_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val composeView = ComposeView(context)
        return when (viewType) {
            TYPE_HOME_SECTION -> ComposeHolder(composeView) { state ->
                SettingsHomeCard(state.item as SettingItem.Section, state.colors)
            }
            else -> ComposeHolder(composeView) { state ->
                SettingsDetailRow(state.item, state.colors)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)
        val colors = UHabitComposeColors.fromPalette(palette)
        val composeHolder = holder as ComposeHolder

        applyCardDecoration(composeHolder.itemView, position)
        when (item) {
            is SettingItem.Section -> {
                bindSectionInteraction(composeHolder.itemView, item)
                composeHolder.bind(item, colors)
            }
            else -> {
                // DiffUtil deliberately ignores callback identity. Resolve the latest action
                // at invocation time even when an otherwise identical row is not rebound.
                composeHolder.bind(composeSettingsItem(item) { items }, colors)
            }
        }
    }

    private fun bindSectionInteraction(view: View, item: SettingItem.Section) {
        view.contentDescription = item.accessibilityDescription
        ViewCompat.setStateDescription(view, null)
        ViewCompat.setAccessibilityDelegate(view, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.animate()
                    .scaleX(SettingsTransitionSpec.PRESSED_SCALE)
                    .scaleY(SettingsTransitionSpec.PRESSED_SCALE)
                    .setDuration(SettingsTransitionSpec.PRESS_DURATION)
                    .setInterpolator(SettingsTransitionSpec.PRINCIPAL_INTERPOLATOR)
                    .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(SettingsTransitionSpec.PRESS_DURATION)
                    .setInterpolator(SettingsTransitionSpec.PRINCIPAL_INTERPOLATOR)
                    .start()
            }
            false
        }
        view.setOnClickListener {
            val current = items.firstOrNull { it.stableKey == item.stableKey } as? SettingItem.Section
            current?.onClick?.invoke(SettingsTransitionSource(current.sectionId, view))
        }
        view.isClickable = true
        view.isFocusable = true
    }

    private data class BoundComposeState(
        val item: SettingItem,
        val colors: UHabitComposeColors
    )

    private class ComposeHolder(
        composeView: ComposeView,
        content: @Composable (BoundComposeState) -> Unit
    ) : RecyclerView.ViewHolder(composeView) {
        private var boundState by mutableStateOf<BoundComposeState?>(null)

        init {
            composeView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            composeView.setContent {
                val state = boundState ?: return@setContent
                content(state)
            }
        }

        fun bind(item: SettingItem, colors: UHabitComposeColors) {
            boundState = BoundComposeState(item, colors)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        holder.itemView.animate().setListener(null).cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.alpha = 1f
        holder.itemView.isPressed = false
        super.onViewRecycled(holder)
    }

    private fun stableIdFor(key: String): Long {
        var hash = -3750763034362895579L
        key.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    private fun currentPaletteSignature(): String {
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)
        val accent = AccentColorManager.getAccentColor(context, prefs)
        return "$accent:${palette.isDark}:${palette.isPureBlack}:${palette.isClassicSectionLayout}:${palette.habitsCardCornerRadius}:${palette.showHabitCardBorders}"
    }

    override fun getItemCount(): Int = items.size
}

internal fun composeSettingsItem(item: SettingItem, currentItems: () -> List<SettingItem>): SettingItem =
    when (item) {
        is SettingItem.Navigation -> item.copy(onClick = item.onClick?.let {
            {
                val current = currentItems().firstOrNull { it.stableKey == item.stableKey } as? SettingItem.Navigation
                if (current != null && current.presentation.enabled && !current.isBusy) current.onClick?.invoke()
                Unit
            }
        })
        is SettingItem.Switch -> item.copy(onCheckedChange = { value ->
            val current = currentItems().firstOrNull { it.stableKey == item.stableKey } as? SettingItem.Switch
            if (current?.presentation?.enabled == true) current.onCheckedChange(value)
        })
        else -> item
    }

internal object SettingsItemDiff {
    enum class ChangePayload { NAVIGATION_BUSY, STATUS_PROGRESS }

    fun contentSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
        if (oldItem::class != newItem::class) return false
        return when {
            oldItem is SettingItem.Section && newItem is SettingItem.Section ->
                oldItem.sectionId == newItem.sectionId &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.endIconRes == newItem.endIconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.accessibilityDescription == newItem.accessibilityDescription &&
                    oldItem.presentation == newItem.presentation
            oldItem is SettingItem.Subsection && newItem is SettingItem.Subsection ->
                oldItem.key == newItem.key && oldItem.title == newItem.title &&
                    oldItem.isDestructive == newItem.isDestructive &&
                    oldItem.presentation == newItem.presentation
            oldItem is SettingItem.Status && newItem is SettingItem.Status ->
                oldItem.key == newItem.key && oldItem.iconRes == newItem.iconRes && oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary && oldItem.showProgress == newItem.showProgress &&
                    oldItem.reserveProgressSpace == newItem.reserveProgressSpace &&
                    oldItem.presentation == newItem.presentation
            oldItem is SettingItem.Navigation && newItem is SettingItem.Navigation ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.isDanger == newItem.isDanger &&
                    oldItem.isBusy == newItem.isBusy &&
                    (oldItem.onClick != null) == (newItem.onClick != null) &&
                    oldItem.presentation == newItem.presentation
            oldItem is SettingItem.Switch && newItem is SettingItem.Switch ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.checked == newItem.checked &&
                    oldItem.presentation == newItem.presentation
            oldItem is SettingItem.Value && newItem is SettingItem.Value ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.valueText == newItem.valueText &&
                    oldItem.presentation == newItem.presentation
            oldItem is SettingItem.SegmentedTheme && newItem is SettingItem.SegmentedTheme ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.themeValue == newItem.themeValue &&
                    oldItem.pureBlackValue == newItem.pureBlackValue &&
                    oldItem.presentation == newItem.presentation
            oldItem is SettingItem.ColorPicker && newItem is SettingItem.ColorPicker ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.colorString == newItem.colorString &&
                    oldItem.presentation == newItem.presentation
            else -> false
        }
    }

    fun changePayload(oldItem: SettingItem, newItem: SettingItem): ChangePayload? {
        if (oldItem is SettingItem.Navigation && newItem is SettingItem.Navigation) {
            val sameStaticContent = oldItem.key == newItem.key &&
                oldItem.iconRes == newItem.iconRes &&
                oldItem.title == newItem.title &&
                oldItem.summary == newItem.summary &&
                oldItem.isDanger == newItem.isDanger &&
                (oldItem.onClick != null) == (newItem.onClick != null) &&
                oldItem.presentation.copy(enabled = newItem.presentation.enabled) == newItem.presentation
            if (sameStaticContent &&
                (oldItem.isBusy != newItem.isBusy ||
                    oldItem.presentation.enabled != newItem.presentation.enabled)
            ) {
                return ChangePayload.NAVIGATION_BUSY
            }
        }
        if (oldItem is SettingItem.Status && newItem is SettingItem.Status) {
            val sameStaticContent = oldItem.key == newItem.key &&
                oldItem.iconRes == newItem.iconRes &&
                oldItem.title == newItem.title &&
                oldItem.summary == newItem.summary &&
                oldItem.reserveProgressSpace == newItem.reserveProgressSpace &&
                oldItem.presentation == newItem.presentation
            if (sameStaticContent && oldItem.showProgress != newItem.showProgress) {
                return ChangePayload.STATUS_PROGRESS
            }
        }
        return null
    }
}
