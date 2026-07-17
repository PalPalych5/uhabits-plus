package org.isoron.uhabits.activities.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
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
        val onClick: () -> Unit
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
        val radius = context.resources.getDimension(R.dimen.settings_card_radius)

        val item = items[position]
        val cardId = cardId(position) ?: return
        val isFirst = !isSameCard(position - 1, cardId)
        val isLast = !isSameCard(position + 1, cardId)
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)

        val radii = when {
            isFirst && isLast -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            isFirst -> floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            isLast -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(palette.surface)
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
        view.clipToOutline = true

        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            val marginHorizontal = context.resources.getDimensionPixelSize(R.dimen.settings_card_horizontal_margin)
            val halfGap = context.resources.getDimensionPixelSize(R.dimen.settings_card_vertical_gap_half)
            val marginTop = if (isFirst) halfGap else 0
            val marginBottom = if (isLast) halfGap else 0
            lp.setMargins(marginHorizontal, marginTop, marginHorizontal, marginBottom)
            view.layoutParams = lp
        }
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_ROW = 1
        private const val TYPE_SEGMENTED = 2
        private const val TYPE_SUBSECTION = 3
        private const val TYPE_STATUS = 4
    }

    private enum class ChangePayload {
        NAVIGATION_BUSY,
        STATUS_PROGRESS
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
                return contentSame(oldItems[oldItemPosition], newItems[newItemPosition])
            }

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                return changePayload(oldItems[oldItemPosition], newItems[newItemPosition])
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
        return when (items[position]) {
            is SettingItem.Section -> TYPE_SECTION
            is SettingItem.Subsection -> TYPE_SUBSECTION
            is SettingItem.Status -> TYPE_STATUS
            is SettingItem.SegmentedTheme -> TYPE_SEGMENTED
            else -> TYPE_ROW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return when (viewType) {
            TYPE_SECTION -> {
                val view = inflater.inflate(R.layout.item_settings_section, parent, false)
                SectionViewHolder(view)
            }
            TYPE_SUBSECTION -> SubsectionViewHolder(inflater.inflate(R.layout.item_settings_subsection, parent, false))
            TYPE_STATUS -> StatusViewHolder(inflater.inflate(R.layout.item_settings_status, parent, false))
            TYPE_SEGMENTED -> {
                val view = inflater.inflate(R.layout.item_settings_segmented, parent, false)
                SegmentedViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_settings_row, parent, false)
                RowViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val isLastInCard = item.presentation.isLast || item is SettingItem.Section
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)

        when (holder) {
            is SectionViewHolder -> {
                bindSection(holder, item as SettingItem.Section)
                applyCardDecoration(holder.itemView, position)
            }
            is SubsectionViewHolder -> {
                val subsection = item as SettingItem.Subsection
                applyCardDecoration(holder.itemView, position)
                holder.titleText.text = subsection.title
                holder.titleText.setTextColor(
                    if (subsection.isDestructive) {
                        ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.red_500), 204)
                    } else {
                        palette.accent
                    }
                )
                ViewCompat.setAccessibilityHeading(holder.titleText, true)
                holder.divider.setBackgroundColor(palette.groupDivider)
                holder.divider.visibility = if (isLastInCard) View.GONE else View.VISIBLE
            }
            is StatusViewHolder -> {
                val status = item as SettingItem.Status
                applyCardDecoration(holder.itemView, position)
                holder.iconImg.setImageResource(status.iconRes)
                holder.iconImg.imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
                holder.titleText.text = status.title
                holder.summaryText.text = status.summary
                holder.progress.visibility = when {
                    status.showProgress -> View.VISIBLE
                    status.reserveProgressSpace -> View.INVISIBLE
                    else -> View.GONE
                }
                holder.progress.indeterminateTintList = ColorStateList.valueOf(palette.accent)
                holder.titleText.setTextColor(palette.onSurface)
                holder.summaryText.setTextColor(palette.onSurfaceVariant)
                holder.divider.setBackgroundColor(palette.divider)
                holder.divider.visibility = if (isLastInCard) View.GONE else View.VISIBLE
            }
            is SegmentedViewHolder -> {
                val segmentedItem = item as SettingItem.SegmentedTheme
                
                // Card Decoration
                applyCardDecoration(holder.itemView, position)
                
                // Icon Tinting
                holder.iconImg.imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
                holder.iconImg.visibility = if (segmentedItem.presentation.showIcon) View.VISIBLE else View.GONE
                holder.iconImg.setImageResource(segmentedItem.iconRes)
                
                holder.titleText.text = segmentedItem.title
                holder.titleText.setTextColor(palette.onSurface)

                // Determine active segment index
                val activeIndex = when {
                    segmentedItem.themeValue == 0 -> 0
                    segmentedItem.themeValue == 2 -> 1
                    segmentedItem.themeValue == 1 && !segmentedItem.pureBlackValue -> 2
                    segmentedItem.themeValue == 1 && segmentedItem.pureBlackValue -> 3
                    else -> 0
                }

                // Set Russian text/localized labels for theme segments
                val themeEntries = context.resources.getStringArray(R.array.pref_theme_entries)
                holder.btnSystem.text = themeEntries[0]
                holder.btnLight.text = themeEntries[1]
                holder.btnDark.text = themeEntries[2]
                holder.btnAmoled.text = "AMOLED"

                AccentColorManager.tintSegment(holder.btnSystem, activeIndex == 0, context, prefs)
                AccentColorManager.tintSegment(holder.btnLight, activeIndex == 1, context, prefs)
                AccentColorManager.tintSegment(holder.btnDark, activeIndex == 2, context, prefs)
                AccentColorManager.tintSegment(holder.btnAmoled, activeIndex == 3, context, prefs)

                holder.btnSystem.setOnClickListener { segmentedItem.onSegmentSelected(0, segmentedItem.pureBlackValue) }
                holder.btnLight.setOnClickListener { segmentedItem.onSegmentSelected(2, segmentedItem.pureBlackValue) }
                holder.btnDark.setOnClickListener { segmentedItem.onSegmentSelected(1, false) }
                holder.btnAmoled.setOnClickListener { segmentedItem.onSegmentSelected(1, true) }

                holder.divider.setBackgroundColor(palette.divider)
                holder.divider.visibility = if (isLastInCard) View.GONE else View.VISIBLE
            }
            is RowViewHolder -> {
                // Card Decoration
                applyCardDecoration(holder.itemView, position)
                holder.divider.setBackgroundColor(palette.divider)
                holder.divider.visibility = if (isLastInCard) View.GONE else View.VISIBLE

                // Reset accessory visibilities
                holder.switchComp.visibility = View.GONE
                holder.valueText.visibility = View.GONE
                holder.colorSwatch.visibility = View.GONE
                holder.summaryText.visibility = View.GONE
                holder.chevronImg.imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)

                holder.titleText.setTextColor(palette.onSurface)
                holder.itemView.alpha = 1f
                holder.itemView.isEnabled = item.presentation.enabled
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.isClickable = false
                holder.itemView.isFocusable = false
                holder.summaryText.setOnClickListener(null)
                holder.summaryText.setOnLongClickListener(null)
                holder.summaryText.isClickable = false

                // Icon Tinting
                val baseIconColor = if (item is SettingItem.Navigation && item.isDanger) {
                    ContextCompat.getColor(context, R.color.red_500)
                } else {
                    palette.onSurfaceVariant
                }
                holder.iconImg.imageTintList = ColorStateList.valueOf(baseIconColor)
                val contentAlpha = if (item.presentation.enabled || (item is SettingItem.Navigation && item.isBusy)) 1f else 0.48f
                holder.iconImg.alpha = contentAlpha
                holder.titleText.alpha = contentAlpha
                holder.summaryText.alpha = contentAlpha
                holder.chevronImg.alpha = contentAlpha
                holder.switchComp.alpha = contentAlpha
                holder.valueText.alpha = contentAlpha
                holder.colorSwatch.alpha = contentAlpha

                when (item) {
                    is SettingItem.Navigation -> {
                        bindRowIcon(holder, item.iconRes, item.presentation.showIcon)
                        holder.titleText.text = item.title
                        if (!item.summary.isNullOrBlank()) {
                            bindSummary(holder, item.summary)
                        }
                        if (item.isDanger) {
                            holder.titleText.setTextColor(ContextCompat.getColor(context, R.color.red_500))
                        }
                        bindNavigationAccessory(holder, item)
                        if (item.onClick != null) {
                            bindRowClick(holder, item.presentation.enabled) { item.onClick.invoke() }
                        }
                    }
                    is SettingItem.Switch -> {
                        resetNavigationAccessory(holder, showChevron = false)
                        bindRowIcon(holder, item.iconRes, item.presentation.showIcon)
                        holder.titleText.text = item.title
                        if (!item.summary.isNullOrBlank()) {
                            bindSummary(holder, item.summary)
                        }
                        holder.switchComp.visibility = View.VISIBLE
                        holder.switchComp.setOnCheckedChangeListener(null)
                        holder.switchComp.configure(
                            accentColor = AccentColorManager.getAccentColor(context, prefs),
                            isDark = palette.isDark,
                            isPureBlack = palette.isPureBlack
                        )
                        holder.switchComp.bindChecked(item.checked)
                        holder.switchComp.setOnCheckedChangeListener(
                            object : SettingsSwitchView.OnCheckedChangeListener {
                                override fun onCheckedChanged(view: SettingsSwitchView, checked: Boolean) {
                                    item.onCheckedChange(checked)
                                }
                            }
                        )

                        holder.switchComp.isEnabled = item.presentation.enabled
                        bindRowClick(holder, item.presentation.enabled) {
                            holder.switchComp.setCheckedAnimated(!holder.switchComp.isChecked)
                        }
                    }
                    is SettingItem.Value -> {
                        resetNavigationAccessory(holder, showChevron = true)
                        bindRowIcon(holder, item.iconRes, item.presentation.showIcon)
                        holder.titleText.text = item.title
                        holder.valueText.text = item.valueText
                        holder.valueText.visibility = View.VISIBLE
                        bindRowClick(holder, item.presentation.enabled, item.onClick)
                    }
                    is SettingItem.ColorPicker -> {
                        resetNavigationAccessory(holder, showChevron = true)
                        bindRowIcon(holder, item.iconRes, item.presentation.showIcon)
                        holder.titleText.text = item.title
                        bindSummary(holder, item.summary)
                        
                        // Color Swatch setup
                        holder.colorSwatch.visibility = View.VISIBLE
                        val colorInt = AccentColorManager.getAccentColor(context, prefs)
                        val circleDrawable = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(colorInt)
                            setStroke(context.resources.displayMetrics.density.toInt(), palette.divider)
                        }
                        holder.colorSwatch.background = circleDrawable

                        bindRowClick(holder, item.presentation.enabled, item.onClick)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val item = items[position]
        when {
            ChangePayload.NAVIGATION_BUSY in payloads &&
                holder is RowViewHolder &&
                item is SettingItem.Navigation -> bindNavigationBusyPayload(holder, item)
            ChangePayload.STATUS_PROGRESS in payloads &&
                holder is StatusViewHolder &&
                item is SettingItem.Status -> bindStatusProgressPayload(holder, item)
            else -> onBindViewHolder(holder, position)
        }
    }

    private fun bindNavigationBusyPayload(
        holder: RowViewHolder,
        item: SettingItem.Navigation
    ) {
        holder.itemView.isEnabled = item.presentation.enabled
        holder.itemView.setOnClickListener(null)
        holder.itemView.isClickable = false
        holder.itemView.isFocusable = false
        holder.summaryText.setOnClickListener(null)
        holder.summaryText.isClickable = false
        bindNavigationAccessory(holder, item)
        item.onClick?.let { action ->
            bindRowClick(holder, item.presentation.enabled, action)
        }
    }

    private fun bindStatusProgressPayload(
        holder: StatusViewHolder,
        item: SettingItem.Status
    ) {
        holder.progress.animate().cancel()
        holder.progress.indeterminateTintList = ColorStateList.valueOf(
            AccentColorManager.getAccentColor(context, prefs)
        )
        holder.progress.visibility = when {
            item.showProgress -> View.VISIBLE
            item.reserveProgressSpace -> View.INVISIBLE
            else -> View.GONE
        }
    }

    private fun bindNavigationAccessory(
        holder: RowViewHolder,
        item: SettingItem.Navigation
    ) {
        val hasAction = item.onClick != null
        holder.chevronImg.animate().cancel()
        holder.chevronImg.alpha = 1f
        holder.chevronImg.visibility = if (hasAction) View.VISIBLE else View.GONE
    }

    private fun resetNavigationAccessory(holder: RowViewHolder, showChevron: Boolean) {
        holder.chevronImg.animate().cancel()
        holder.chevronImg.alpha = 1f
        holder.chevronImg.visibility = if (showChevron) View.VISIBLE else View.GONE
    }

    private fun bindSection(
        holder: SectionViewHolder,
        item: SettingItem.Section
    ) {
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)
        holder.iconImg.setImageResource(item.iconRes)
        holder.iconImg.imageTintList = ColorStateList.valueOf(palette.accent)
        holder.titleText.text = item.title
        holder.summaryText.text = item.summary
        holder.titleText.setTextColor(palette.onSurface)
        holder.summaryText.setTextColor(palette.onSurfaceVariant)
        holder.chevron.imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
        holder.chevron.setImageResource(item.endIconRes ?: R.drawable.ic_chevron_right)
        holder.itemView.contentDescription = item.accessibilityDescription
        ViewCompat.setStateDescription(holder.itemView, null)
        ViewCompat.setAccessibilityDelegate(holder.itemView, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
        holder.itemView.setOnClickListener { item.onClick() }
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.chevron.animate().setListener(null).cancel()
        holder.chevron.rotation = 0f
    }

    private fun bindRowIcon(holder: RowViewHolder, iconRes: Int, visible: Boolean) {
        holder.iconImg.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) holder.iconImg.setImageResource(iconRes)
        (holder.divider.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.marginStart = dp(if (visible) 52f else 18f).toInt()
            holder.divider.layoutParams = params
        }
    }

    private fun stableIdFor(key: String): Long {
        var hash = -3750763034362895579L
        key.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    private fun contentSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
        if (oldItem::class != newItem::class) return false
        return when {
            oldItem is SettingItem.Section && newItem is SettingItem.Section ->
                oldItem.sectionId == newItem.sectionId &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.endIconRes == newItem.endIconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.accessibilityDescription == newItem.accessibilityDescription
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

    private fun changePayload(oldItem: SettingItem, newItem: SettingItem): ChangePayload? {
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

    private fun dp(value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    private fun currentPaletteSignature(): String {
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)
        val accent = AccentColorManager.getAccentColor(context, prefs)
        return "$accent:${palette.isDark}:${palette.isPureBlack}"
    }

    private fun bindSummary(
        holder: RowViewHolder,
        summary: String
    ) {
        holder.summaryText.text = summary
        holder.summaryText.visibility = View.VISIBLE
        holder.summaryText.maxLines = 2
        holder.summaryText.ellipsize = TextUtils.TruncateAt.END

        if (shouldAllowSummaryPreview(summary)) {
            val showPreview = View.OnLongClickListener {
                CustomDialogs.showInfoDialog(
                    context = holder.itemView.context,
                    title = holder.titleText.text,
                    message = summary
                )
                true
            }
            holder.itemView.setOnLongClickListener(showPreview)
            holder.summaryText.setOnLongClickListener(showPreview)
        }
    }

    private fun bindRowClick(
        holder: RowViewHolder,
        enabled: Boolean,
        action: () -> Unit
    ) {
        if (!enabled) return
        holder.itemView.setOnClickListener { action() }
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        if (holder.summaryText.visibility == View.VISIBLE) {
            holder.summaryText.setOnClickListener { holder.itemView.performClick() }
            holder.summaryText.isClickable = true
        }
    }

    private fun shouldAllowSummaryPreview(summary: String?): Boolean {
        if (summary.isNullOrBlank()) return false
        return summary.length > 72 || summary.contains('\n')
    }

    override fun getItemCount(): Int = items.size

    class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconImg: ImageView = view.findViewById(R.id.sectionIcon)
        val titleText: TextView = view.findViewById(R.id.sectionTitle)
        val summaryText: TextView = view.findViewById(R.id.sectionSummary)
        val chevron: ImageView = view.findViewById(R.id.sectionChevron)
    }

    class SubsectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.subsectionTitle)
        val divider: View = view.findViewById(R.id.rowDivider)
    }

    class StatusViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconImg: ImageView = view.findViewById(R.id.statusIcon)
        val titleText: TextView = view.findViewById(R.id.statusTitle)
        val summaryText: TextView = view.findViewById(R.id.statusSummary)
        val progress: ProgressBar = view.findViewById(R.id.statusProgress)
        val divider: View = view.findViewById(R.id.rowDivider)
    }

    class SegmentedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconImg: ImageView = view.findViewById(R.id.rowIcon)
        val titleText: TextView = view.findViewById(R.id.rowTitle)
        val btnSystem: TextView = view.findViewById(R.id.segmentSystem)
        val btnLight: TextView = view.findViewById(R.id.segmentLight)
        val btnDark: TextView = view.findViewById(R.id.segmentDark)
        val btnAmoled: TextView = view.findViewById(R.id.segmentAmoled)
        val divider: View = view.findViewById(R.id.rowDivider)
    }

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconImg: ImageView = view.findViewById(R.id.rowIcon)
        val titleText: TextView = view.findViewById(R.id.rowTitle)
        val summaryText: TextView = view.findViewById(R.id.rowSummary)
        val chevronImg: ImageView = view.findViewById(R.id.accessoryChevron)
        val switchComp: SettingsSwitchView = view.findViewById(R.id.accessorySwitch)
        val valueText: TextView = view.findViewById(R.id.accessoryValue)
        val colorSwatch: View = view.findViewById(R.id.accessoryColorSwatch)
        val divider: View = view.findViewById(R.id.rowDivider)
    }

}
