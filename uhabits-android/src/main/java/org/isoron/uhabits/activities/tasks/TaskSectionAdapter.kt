package org.isoron.uhabits.activities.tasks

import android.view.ViewGroup
import android.view.Gravity
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.activities.settings.SettingsThemePalette

/** Compact disclosure heading; completed headings deliberately have no count. */
internal class TaskSectionAdapter(
    private var title: String,
    private val onToggle: (() -> Unit)? = null,
) : RecyclerView.Adapter<TaskSectionAdapter.Holder>() {
    private var visible = false
    private var expanded = true
    private lateinit var palette: SettingsThemePalette

    fun update(show: Boolean, open: Boolean, colors: SettingsThemePalette, label: String = title) {
        val wasVisible = visible
        visible = show
        expanded = open
        palette = colors
        title = label
        when {
            !wasVisible && show -> notifyItemInserted(0)
            wasVisible && !show -> notifyItemRemoved(0)
            show -> notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (visible) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        return Holder(TextView(context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            minHeight = TaskThemeUtils.dpInt(context, 48f)
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(TaskThemeUtils.dpInt(context, 24f), TaskThemeUtils.dpInt(context, 8f), TaskThemeUtils.dpInt(context, 16f), TaskThemeUtils.dpInt(context, 8f))
            textSize = 13f
            maxLines = Int.MAX_VALUE
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            if (onToggle != null) {
                val value = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
            }
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        (holder.itemView as TextView).apply {
            text = title
            val chevron = if (onToggle == null) null else androidx.appcompat.content.res.AppCompatResources.getDrawable(context,
                if (expanded) org.isoron.uhabits.R.drawable.ic_chevron_up else org.isoron.uhabits.R.drawable.ic_chevron_down)?.mutate()?.apply {
                setTint(palette.onSurfaceVariant)
                val size = TaskThemeUtils.dpInt(context, 16f)
                setBounds(0, 0, size, size)
            }
            setCompoundDrawablesRelative(null, null, chevron, null)
            setTextColor(palette.onSurfaceVariant)
            androidx.core.view.ViewCompat.setStateDescription(this, if (onToggle == null) null else if (expanded) "Развёрнуто" else "Свёрнуто")
            setOnClickListener(if (onToggle == null) null else android.view.View.OnClickListener { onToggle.invoke() })
            isClickable = onToggle != null
            isFocusable = onToggle != null
        }
    }
    class Holder(view: TextView) : RecyclerView.ViewHolder(view)
}
