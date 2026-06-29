package org.isoron.uhabits.activities.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
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
    private val expandedSummaryKeys = mutableSetOf<String>()

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
            setStroke((density).toInt().coerceAtLeast(1), palette.border)
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
        items = newItems
        notifyDataSetChanged()
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
                holder.itemView.isClickable = false
                holder.summaryText.setOnClickListener(null)
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
                            bindSummary(holder, item.key, item.summary, isInteractive = item.onClick != null)
                        }
                        if (item.isDanger) {
                            holder.titleText.setTextColor(ContextCompat.getColor(context, R.color.red_500))
                        }
                        holder.chevronImg.visibility = if (item.onClick != null) View.VISIBLE else View.GONE
                        if (item.onClick != null) {
                            holder.itemView.setOnClickListener { item.onClick.invoke() }
                        } else if (shouldAllowSummaryExpansion(item.summary)) {
                            holder.itemView.setOnClickListener {
                                toggleSummary(item.key)
                            }
                        }
                    }
                    is SettingItem.Switch -> {
                        holder.iconImg.setImageResource(item.iconRes)
                        holder.titleText.text = item.title
                        if (!item.summary.isNullOrBlank()) {
                            bindSummary(holder, item.key, item.summary, isInteractive = false)
                        }
                        holder.switchComp.visibility = View.VISIBLE
                        holder.switchComp.setOnCheckedChangeListener(null)
                        holder.switchComp.isChecked = item.checked
                        
                        // Tint switch dynamically
                        AccentColorManager.tintSwitch(holder.switchComp, context, prefs)

                        holder.itemView.setOnClickListener {
                            val nextVal = !holder.switchComp.isChecked
                            holder.switchComp.isChecked = nextVal
                            item.onCheckedChange(nextVal)
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
                        bindSummary(holder, item.key, item.summary, isInteractive = true)
                        
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
    }

    private fun bindSummary(
        holder: RowViewHolder,
        key: String,
        summary: String,
        isInteractive: Boolean
    ) {
        holder.summaryText.text = summary
        holder.summaryText.visibility = View.VISIBLE
        val isExpanded = expandedSummaryKeys.contains(key)
        holder.summaryText.maxLines = if (isExpanded) 6 else 3
        holder.summaryText.ellipsize = if (isExpanded) null else android.text.TextUtils.TruncateAt.END

        if (shouldAllowSummaryExpansion(summary)) {
            holder.summaryText.isClickable = true
            holder.summaryText.setOnClickListener { toggleSummary(key) }
            if (!isInteractive) {
                holder.itemView.setOnClickListener { toggleSummary(key) }
            }
        }
    }

    private fun shouldAllowSummaryExpansion(summary: String?): Boolean {
        if (summary.isNullOrBlank()) return false
        return summary.length > 72 || summary.contains('\n')
    }

    private fun toggleSummary(key: String) {
        if (!expandedSummaryKeys.add(key)) {
            expandedSummaryKeys.remove(key)
        }
        notifyDataSetChanged()
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
        val switchComp: SwitchCompat = view.findViewById(R.id.accessorySwitch)
        val valueText: TextView = view.findViewById(R.id.accessoryValue)
        val colorSwatch: View = view.findViewById(R.id.accessoryColorSwatch)
        val divider: View = view.findViewById(R.id.rowDivider)
    }
}
