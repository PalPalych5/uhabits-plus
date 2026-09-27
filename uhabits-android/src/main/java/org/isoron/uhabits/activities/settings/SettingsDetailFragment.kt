package org.isoron.uhabits.activities.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R

class SettingsDetailFragment : SettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sectionId = checkNotNull(detailSectionId())
        val prepareMorph = arguments?.getBoolean(ARG_PREPARE_MORPH, false) == true
        arguments?.putBoolean(ARG_PREPARE_MORPH, false)
        if (prepareMorph && savedInstanceState == null) view.alpha = 0f
        val title = view.findViewById<TextView>(R.id.settingsDetailTitle)
        title.text = sectionTitle(sectionId)
        val toolbar = view.findViewById<Toolbar>(R.id.settingsDetailToolbar)
        val app = requireContext().applicationContext as HabitsApplication
        val palette = SettingsThemePaletteResolver.resolve(requireContext(), app.component.preferences)

        title.setTextColor(palette.onSurface)
        toolbar.setBackgroundColor(palette.background)
        toolbar.contentInsetStartWithNavigation = 0
        toolbar.navigationIcon = requireContext().getDrawable(R.drawable.ic_settings_back)?.mutate()?.also {
            DrawableCompat.setTintList(it, ColorStateList.valueOf(palette.onSurface))
        }
        toolbar.navigationContentDescription = getString(R.string.settings_back)
        toolbar.post {
            toolbar.findViewById<ImageButton?>(androidx.appcompat.R.id.home)?.apply {
                minimumWidth = resources.getDimensionPixelSize(R.dimen.settings_toolbar_touch_target)
                minimumHeight = resources.getDimensionPixelSize(R.dimen.settings_toolbar_touch_target)
            }
        }
        toolbar.setNavigationOnClickListener {
            (parentFragment as? SettingsNavigationController)?.popSettingsDetail()
        }
        ViewCompat.setAccessibilityHeading(toolbar, true)

        restoreListScrollState(
            (parentFragment as? SettingsNavigationController)?.detailScrollState(sectionId)
        )
    }

    override fun onDestroyView() {
        detailSectionId()?.let { sectionId ->
            (parentFragment as? SettingsNavigationController)
                ?.saveDetailScrollState(sectionId, saveListScrollState())
        }
        super.onDestroyView()
    }

    internal fun transitionToolbar(): View? = view?.findViewById(R.id.settingsDetailToolbar)

    internal fun transitionBody(): androidx.recyclerview.widget.RecyclerView? =
        view?.findViewById(R.id.settingsRecyclerView)

    private fun sectionTitle(id: SettingsSectionId): String = getString(
        when (id) {
            SettingsSectionId.APPEARANCE -> R.string.appearance
            SettingsSectionId.HABITS -> R.string.settings_section_habits_day
            SettingsSectionId.NOTIFICATIONS -> R.string.settings_section_notifications
            SettingsSectionId.POMODORO -> R.string.pref_pomodoro_category
            SettingsSectionId.STATISTICS -> R.string.pref_statistics_title
            SettingsSectionId.DATA -> R.string.pref_data_backup_title
            SettingsSectionId.SYNC -> R.string.sync_title
            SettingsSectionId.HELP -> R.string.settings_section_help_app
            SettingsSectionId.DEVELOPER -> R.string.pref_developer_section_title
        }
    )

    companion object {
        private const val ARG_PREPARE_MORPH = "prepareMorph"

        fun newInstance(
            sectionId: SettingsSectionId,
            prepareMorph: Boolean = false,
        ): SettingsDetailFragment =
            SettingsDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DETAIL_SECTION, sectionId.name)
                    putBoolean(ARG_PREPARE_MORPH, prepareMorph)
                }
            }
    }
}
