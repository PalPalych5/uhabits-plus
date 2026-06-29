package org.isoron.uhabits.activities.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.databinding.FragmentSettingsSectionBinding
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.setupToolbar

class SettingsSectionFragment : Fragment() {
    private var binding: FragmentSettingsSectionBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentSettingsSectionBinding.inflate(inflater, container, false)
        binding = viewBinding
        val activity = requireActivity() as AppCompatActivity
        viewBinding.root.setupToolbar(
            toolbar = viewBinding.toolbar,
            title = getString(R.string.settings),
            color = PaletteColor(11),
            displayHomeAsUpEnabled = false,
            theme = viewBinding.root.currentTheme()
        )
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settingsContent, SettingsFragment())
                .commitNow()
        }
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        applyNeutralToolbarAndSystemBars()
        return viewBinding.root
    }

    override fun onResume() {
        super.onResume()
        binding?.let {
            val activity = requireActivity() as AppCompatActivity
            activity.setSupportActionBar(it.toolbar)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)
            applyNeutralToolbarAndSystemBars()
        }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
    }

    private fun applyNeutralToolbarAndSystemBars() {
        val binding = binding ?: return
        val activity = requireActivity() as AppCompatActivity
        val appContext = activity.applicationContext as HabitsApplication
        val prefs = appContext.component.preferences
        val palette = SettingsThemePaletteResolver.resolve(requireContext(), prefs)

        // 2. Set neutral Toolbar background and flat elevation
        binding.toolbar.background = android.graphics.drawable.ColorDrawable(palette.background)
        binding.toolbar.elevation = 0f

        val titleColor = androidx.core.content.ContextCompat.getColor(
            requireContext(),
            if (palette.isDark) R.color.grey_100 else R.color.grey_800
        )
        binding.toolbar.setTitleTextColor(titleColor)

        // 3. Status bar and Navigation Bar colors and icon appearance
        activity.window.statusBarColor = palette.background
        activity.window.navigationBarColor = if (palette.isPureBlack) palette.background else palette.surface

        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = !palette.isDark
        windowInsetsController.isAppearanceLightNavigationBars = !palette.isDark
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
