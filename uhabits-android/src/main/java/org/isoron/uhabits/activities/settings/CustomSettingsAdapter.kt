package org.isoron.uhabits.activities.settings

import android.content.Context
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.core.preferences.Preferences

sealed class SettingItem {
    data class Header(val title: String) : SettingItem()

    data class Navigation(
        val key: String,
        val iconRes: Int,
        val title: String,
        val summary: String? = null,
        val isDanger: Boolean = false,
        val onClick: (() -> Unit)? = null
    ) : SettingItem()

    data class Switch(
        val key: String,
        val iconRes: Int,
        val title: String,
        val summary: String? = null,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit
    ) : SettingItem()

    data class Value(
        val key: String,
        val iconRes: Int,
        val title: String,
        val valueText: String,
        val onClick: () -> Unit
    ) : SettingItem()

    data class SegmentedTheme(
        val key: String,
        val iconRes: Int,
        val title: String,
        val themeValue: Int,
        val pureBlackValue: Boolean,
        val onSegmentSelected: (theme: Int, pureBlack: Boolean) -> Unit
    ) : SettingItem()

    data class ColorPicker(
        val key: String,
        val iconRes: Int,
        val title: String,
        val summary: String,
        val colorString: String,
        val onClick: () -> Unit
    ) : SettingItem()
}

class CustomSettingsAdapter(
    private val context: Context,
    private val prefs: Preferences,
    private var items: List<SettingItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var pendingAddedKeys = emptySet<String>()
    private var paletteSignature = currentPaletteSignature()

    init {
        setHasStableIds(true)
    }

    private val isNightMode: Boolean
        get() = SettingsThemePaletteResolver.resolve(context, prefs).isDark

    private fun isCardRow(position: Int): Boolean {
        if (position < 0 || position >= items.size) return false
        return items[position] !is SettingItem.Header
    }

    private fun applyCardDecoration(view: View, position: Int) {
        val density = context.resources.displayMetrics.density
        val radius = 12f * density

        val isFirst = !isCardRow(position - 1)
        val isLast = !isCardRow(position + 1)
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

        val rippleColor = ContextCompat.getColor(context, if (isNightMode) R.color.grey_800 else R.color.grey_300)
        
        val rippleDrawable = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            backgroundDrawable,
            maskDrawable
        )

        view.background = rippleDrawable
        view.clipToOutline = true

        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            val marginHorizontal = (16f * density).toInt()
            val marginTop = if (isFirst) (8f * density).toInt() else 0
            val marginBottom = if (isLast) (16f * density).toInt() else 0
            lp.setMargins(marginHorizontal, marginTop, marginHorizontal, marginBottom)
            view.layoutParams = lp
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private const val TYPE_SEGMENTED = 2
    }

    fun updateItems(newItems: List<SettingItem>) {
        val newPaletteSignature = currentPaletteSignature()
        if (newPaletteSignature != paletteSignature) {
            paletteSignature = newPaletteSignature
            items = newItems
            pendingAddedKeys = emptySet()
            notifyDataSetChanged()
            return
        }

        val oldItems = items
        val oldKeys = oldItems.mapTo(mutableSetOf()) { stableKeyFor(it) }
        val addedKeys = newItems.mapTo(mutableSetOf()) { stableKeyFor(it) }.apply {
            removeAll(oldKeys)
        }
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size

            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return stableKeyFor(oldItems[oldItemPosition]) == stableKeyFor(newItems[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return contentSame(oldItems[oldItemPosition], newItems[newItemPosition])
            }
        })
        items = newItems
        paletteSignature = newPaletteSignature
        pendingAddedKeys = if (ValueAnimator.areAnimatorsEnabled()) addedKeys else emptySet()
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long {
        return stableKeyFor(items[position]).hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SettingItem.Header -> TYPE_HEADER
            is SettingItem.SegmentedTheme -> TYPE_SEGMENTED
            else -> TYPE_ROW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_settings_header, parent, false)
                HeaderViewHolder(view)
            }
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
        val isLastInCard = !isCardRow(position + 1)
        val palette = SettingsThemePaletteResolver.resolve(context, prefs)

        when (holder) {
            is HeaderViewHolder -> {
                val headerItem = item as SettingItem.Header
                holder.titleText.text = headerItem.title
                holder.titleText.setTextColor(AccentColorManager.getAccentColor(context, prefs))
            }
            is SegmentedViewHolder -> {
                val segmentedItem = item as SettingItem.SegmentedTheme
                
                // Card Decoration
                applyCardDecoration(holder.itemView, position)
                
                // Icon Tinting
                val iconColor = ContextCompat.getColor(context, if (isNightMode) R.color.grey_400 else R.color.grey_600)
                holder.iconImg.imageTintList = ColorStateList.valueOf(iconColor)
                holder.iconImg.setImageResource(segmentedItem.iconRes)
                
                holder.titleText.text = segmentedItem.title
                holder.titleText.setTextColor(ContextCompat.getColor(context, if (isNightMode) R.color.grey_100 else R.color.grey_800))

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
                holder.chevronImg.visibility = View.GONE
                holder.switchComp.visibility = View.GONE
                holder.valueText.visibility = View.GONE
                holder.colorSwatch.visibility = View.GONE
                holder.summaryText.visibility = View.GONE

                holder.titleText.setTextColor(ContextCompat.getColor(context, if (isNightMode) R.color.grey_100 else R.color.grey_800))
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.isClickable = false
                holder.summaryText.setOnClickListener(null)
                holder.summaryText.setOnLongClickListener(null)
                holder.summaryText.isClickable = false

                // Icon Tinting
                val baseIconColor = if (item is SettingItem.Navigation && item.isDanger) {
                    ContextCompat.getColor(context, R.color.red_500)
                } else {
                    ContextCompat.getColor(context, if (isNightMode) R.color.grey_400 else R.color.grey_600)
                }
                holder.iconImg.imageTintList = ColorStateList.valueOf(baseIconColor)

                when (item) {
                    is SettingItem.Navigation -> {
                        holder.iconImg.setImageResource(item.iconRes)
                        holder.titleText.text = item.title
                        if (!item.summary.isNullOrBlank()) {
                            bindSummary(holder, item.summary)
                        }
                        if (item.isDanger) {
                            holder.titleText.setTextColor(ContextCompat.getColor(context, R.color.red_500))
                        }
                        holder.chevronImg.visibility = if (item.onClick != null) View.VISIBLE else View.GONE
                        if (item.onClick != null) {
                            holder.itemView.setOnClickListener { item.onClick.invoke() }
                        }
                    }
                    is SettingItem.Switch -> {
                        holder.iconImg.setImageResource(item.iconRes)
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

                        holder.itemView.setOnClickListener {
                            holder.switchComp.setCheckedAnimated(!holder.switchComp.isChecked)
                        }
                    }
                    is SettingItem.Value -> {
                        holder.iconImg.setImageResource(item.iconRes)
                        holder.titleText.text = item.title
                        holder.valueText.text = item.valueText
                        holder.valueText.visibility = View.VISIBLE
                        holder.chevronImg.visibility = View.VISIBLE
                        holder.itemView.setOnClickListener { item.onClick() }
                    }
                    is SettingItem.ColorPicker -> {
                        holder.iconImg.setImageResource(item.iconRes)
                        holder.titleText.text = item.title
                        bindSummary(holder, item.summary)
                        
                        // Color Swatch setup
                        holder.colorSwatch.visibility = View.VISIBLE
                        val colorInt = AccentColorManager.getAccentColor(context, prefs)
                        val circleDrawable = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(colorInt)
                            val strokeColor = ContextCompat.getColor(context, if (isNightMode) R.color.grey_700 else R.color.grey_300)
                            setStroke(1 * context.resources.displayMetrics.density.toInt(), strokeColor)
                        }
                        holder.colorSwatch.background = circleDrawable

                        holder.chevronImg.visibility = View.VISIBLE
                        holder.itemView.setOnClickListener { item.onClick() }
                    }
                    else -> Unit
                }
            }
        }
        animateAddedItemIfNeeded(holder.itemView, item)
    }

    private fun animateAddedItemIfNeeded(view: View, item: SettingItem) {
        val key = stableKeyFor(item)
        if (!pendingAddedKeys.contains(key)) return
        pendingAddedKeys = pendingAddedKeys - key
        view.animate().setListener(null).cancel()
        view.alpha = 0f
        view.translationY = dp(10f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun stableKeyFor(item: SettingItem): String {
        return when (item) {
            is SettingItem.Header -> "header:${item.title}"
            is SettingItem.Navigation -> "navigation:${item.key}"
            is SettingItem.Switch -> "switch:${item.key}"
            is SettingItem.Value -> "value:${item.key}"
            is SettingItem.SegmentedTheme -> "segmented:${item.key}"
            is SettingItem.ColorPicker -> "color:${item.key}"
        }
    }

    private fun contentSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
        if (oldItem::class != newItem::class) return false
        return when {
            oldItem is SettingItem.Header && newItem is SettingItem.Header ->
                oldItem.title == newItem.title
            oldItem is SettingItem.Navigation && newItem is SettingItem.Navigation ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.isDanger == newItem.isDanger &&
                    (oldItem.onClick != null) == (newItem.onClick != null)
            oldItem is SettingItem.Switch && newItem is SettingItem.Switch ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.checked == newItem.checked
            oldItem is SettingItem.Value && newItem is SettingItem.Value ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.valueText == newItem.valueText
            oldItem is SettingItem.SegmentedTheme && newItem is SettingItem.SegmentedTheme ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.themeValue == newItem.themeValue &&
                    oldItem.pureBlackValue == newItem.pureBlackValue
            oldItem is SettingItem.ColorPicker && newItem is SettingItem.ColorPicker ->
                oldItem.key == newItem.key &&
                    oldItem.iconRes == newItem.iconRes &&
                    oldItem.title == newItem.title &&
                    oldItem.summary == newItem.summary &&
                    oldItem.colorString == newItem.colorString
            else -> false
        }
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
        holder.summaryText.maxLines = if (shouldAllowSummaryPreview(summary)) 3 else 2
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

    private fun shouldAllowSummaryPreview(summary: String?): Boolean {
        if (summary.isNullOrBlank()) return false
        return summary.length > 72 || summary.contains('\n')
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.headerTitle)
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
