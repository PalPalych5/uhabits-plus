package org.isoron.uhabits.activities.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.databinding.FragmentSettingsSectionBinding
import org.isoron.uhabits.utils.applyToolbarInsets

class SettingsSectionFragment : Fragment() {
    private var binding: FragmentSettingsSectionBinding? = null
    private val showToolbar: Boolean
        get() = arguments?.getBoolean(ARG_SHOW_TOOLBAR, true) ?: true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentSettingsSectionBinding.inflate(inflater, container, false)
        binding = viewBinding
        if (showToolbar) {
            viewBinding.toolbar.root.apply {
                applyToolbarInsets()
                visibility = View.VISIBLE
            }
        } else {
            viewBinding.statusBarScrim.visibility = View.VISIBLE
            viewBinding.toolbar.root.apply {
                visibility = View.GONE
                minimumHeight = 0
                layoutParams = layoutParams.apply {
                    height = 0
                }
            }
            (viewBinding.settingsContent.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let {
                it.removeRule(android.widget.RelativeLayout.BELOW)
                it.addRule(android.widget.RelativeLayout.BELOW, R.id.statusBarScrim)
                viewBinding.settingsContent.layoutParams = it
            }
            ViewCompat.setOnApplyWindowInsetsListener(viewBinding.statusBarScrim) { scrim, insets ->
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                scrim.layoutParams = scrim.layoutParams.apply {
                    height = maxOf(statusBars.top, cutout.top)
                }
                insets
            }
        }
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settingsContent, SettingsFragment.newInstance(compactTopInset = !showToolbar))
                .commitNow()
        }
        applyNeutralToolbarAndSystemBars()
        return viewBinding.root
    }

    override fun onResume() {
        super.onResume()
        binding?.let { applyNeutralToolbarAndSystemBars() }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
    }

    private fun applyNeutralToolbarAndSystemBars() {
        val binding = binding ?: return
        val activity = requireActivity() as AppCompatActivity
        val appContext = activity.applicationContext as HabitsApplication
        val prefs = appContext.component.preferences
        val palette = SettingsThemePaletteResolver.resolve(requireContext(), prefs)

        // 2. Set neutral Toolbar background and flat elevation
        binding.root.setBackgroundColor(palette.background)
        binding.settingsContent.setBackgroundColor(palette.background)
        binding.toolbar.root.setBackgroundColor(palette.background)
        binding.statusBarScrim.setBackgroundColor(palette.background)
        binding.toolbar.root.elevation = 0f
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

    companion object {
        private const val ARG_SHOW_TOOLBAR = "showToolbar"

        fun mainTab(): SettingsSectionFragment {
            return SettingsSectionFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_SHOW_TOOLBAR, false)
                }
            }
        }
    }
}
