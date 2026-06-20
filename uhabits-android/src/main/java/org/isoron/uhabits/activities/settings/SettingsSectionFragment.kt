package org.isoron.uhabits.activities.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
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
        return viewBinding.root
    }

    override fun onResume() {
        super.onResume()
        binding?.let {
            (requireActivity() as AppCompatActivity).setSupportActionBar(it.toolbar)
            (requireActivity() as AppCompatActivity).supportActionBar
                ?.setDisplayHomeAsUpEnabled(false)
        }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
