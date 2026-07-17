package org.isoron.uhabits.activities.settings

import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.databinding.FragmentSettingsSectionBinding
import org.isoron.uhabits.utils.applyToolbarInsets

interface SettingsNavigationController {
    fun openSettingsSection(sectionId: SettingsSectionId)
    fun popSettingsDetail(): Boolean
    fun saveDetailScrollState(sectionId: SettingsSectionId, state: Parcelable?)
    fun detailScrollState(sectionId: SettingsSectionId): Parcelable?
}

class SettingsSectionFragment : Fragment(), SettingsNavigationController {
    private var binding: FragmentSettingsSectionBinding? = null
    private var navigationInFlight = false
    private var pendingPop = false
    private var externalNavigationInFlight = false
    private var backGuardUntil = 0L
    private val detailScrollStates = mutableMapOf<SettingsSectionId, Parcelable>()
    private var backCallback: OnBackPressedCallback? = null
    private val backStackChangedListener = FragmentManager.OnBackStackChangedListener {
        navigationInFlight = false
        val shouldPop = pendingPop && hasDetail()
        pendingPop = false
        updateNavigationChrome()
        if (shouldPop) binding?.root?.post { popSettingsDetail() }
    }
    private val showToolbar: Boolean
        get() = arguments?.getBoolean(ARG_SHOW_TOOLBAR, true) ?: true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        savedInstanceState?.getBundle(STATE_DETAIL_SCROLL)?.let { states ->
            SettingsSectionId.entries.forEach { id ->
                states.getParcelable<Parcelable>(id.name)?.let { detailScrollStates[id] = it }
            }
        }
    }

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
                layoutParams = layoutParams.apply { height = 0 }
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
        if (savedInstanceState == null && childFragmentManager.findFragmentByTag(TAG_HOME) == null) {
            childFragmentManager.beginTransaction()
                .add(
                    R.id.settingsContent,
                    SettingsFragment.newInstance(compactTopInset = !showToolbar),
                    TAG_HOME
                )
                .commitNow()
        }
        applyNeutralToolbarAndSystemBars()
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        normalizeLegacyStatisticsDetail()
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                popSettingsDetail()
            }
        }
        registerBackCallback()
        childFragmentManager.addOnBackStackChangedListener(backStackChangedListener)
        updateNavigationChrome(animateBottomBar = false)
        view.post { updateNavigationChrome(animateBottomBar = false) }
    }

    override fun openSettingsSection(sectionId: SettingsSectionId) {
        if (sectionId == SettingsSectionId.STATISTICS) return
        if (navigationInFlight || childFragmentManager.isStateSaved || currentDetail() != null) return
        val home = childFragmentManager.findFragmentByTag(TAG_HOME) ?: return
        navigationInFlight = true
        pendingPop = false
        backCallback?.isEnabled = true
        val transaction = childFragmentManager.beginTransaction()
        if (motionEnabled()) {
            transaction.setCustomAnimations(
                R.anim.settings_detail_enter,
                R.anim.settings_home_exit,
                R.anim.settings_home_pop_enter,
                R.anim.settings_detail_pop_exit
            )
        }
        transaction
            .hide(home)
            .setMaxLifecycle(home, Lifecycle.State.STARTED)
            .add(R.id.settingsContent, SettingsDetailFragment.newInstance(sectionId), detailTag(sectionId))
            .addToBackStack(sectionId.name)
            .commit()
        updateBottomNavigation(detailVisible = true, animate = motionEnabled())
    }

    override fun popSettingsDetail(): Boolean {
        if (externalNavigationInFlight || SystemClock.uptimeMillis() < backGuardUntil) return true
        if (navigationInFlight) {
            pendingPop = true
            backCallback?.isEnabled = true
            return true
        }
        if (!hasDetail() || childFragmentManager.isStateSaved) return false
        navigationInFlight = true
        backCallback?.isEnabled = true
        childFragmentManager.popBackStack()
        return true
    }

    fun setArchiveTransitionInFlight(inFlight: Boolean) {
        externalNavigationInFlight = inFlight
        if (!inFlight) backGuardUntil = SystemClock.uptimeMillis() + ARCHIVE_BACK_GUARD_MS
        updateNavigationChrome(animateBottomBar = false)
    }

    override fun saveDetailScrollState(sectionId: SettingsSectionId, state: Parcelable?) {
        if (state == null) detailScrollStates.remove(sectionId) else detailScrollStates[sectionId] = state
    }

    override fun detailScrollState(sectionId: SettingsSectionId): Parcelable? = detailScrollStates[sectionId]

    override fun onSaveInstanceState(outState: Bundle) {
        val states = Bundle().apply {
            detailScrollStates.forEach { (id, state) -> putParcelable(id.name, state) }
        }
        outState.putBundle(STATE_DETAIL_SCROLL, states)
        super.onSaveInstanceState(outState)
    }

    private fun currentDetail(): SettingsDetailFragment? = childFragmentManager.fragments
        .filterIsInstance<SettingsDetailFragment>()
        .lastOrNull { it.isAdded && !it.isHidden }

    private fun hasDetail(): Boolean =
        childFragmentManager.backStackEntryCount > 0 || currentDetail() != null

    private fun updateNavigationChrome(animateBottomBar: Boolean = motionEnabled()) {
        val detailVisible = hasDetail()
        backCallback?.isEnabled = !isHidden && (detailVisible || navigationInFlight)
        updateBottomNavigation(detailVisible, animateBottomBar)
    }

    private fun updateBottomNavigation(detailVisible: Boolean, animate: Boolean) {
        if (isHidden) return
        val host = activity as? MainNavigationHost ?: return
        host.setBottomNavigationVisible(
            visible = !detailVisible,
            animate = animate
        )
    }

    private fun normalizeLegacyStatisticsDetail() {
        val legacyDetail = childFragmentManager.fragments
            .filterIsInstance<SettingsDetailFragment>()
            .lastOrNull {
                it.arguments?.getString(SettingsFragment.ARG_DETAIL_SECTION) ==
                    SettingsSectionId.STATISTICS.name
            } ?: return
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStackImmediate()
        }
        if (legacyDetail.isAdded) {
            childFragmentManager.beginTransaction().remove(legacyDetail).commitNowAllowingStateLoss()
        }
        childFragmentManager.findFragmentByTag(TAG_HOME)?.let { home ->
            childFragmentManager.beginTransaction()
                .show(home)
                .setMaxLifecycle(home, Lifecycle.State.RESUMED)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onResume() {
        super.onResume()
        registerBackCallback()
        binding?.let { applyNeutralToolbarAndSystemBars() }
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
        updateNavigationChrome(animateBottomBar = false)
    }

    private fun registerBackCallback() {
        val callback = backCallback ?: return
        callback.remove()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) resetChildTransforms()
        updateNavigationChrome()
    }

    private fun resetChildTransforms() {
        childFragmentManager.fragments.forEach { fragment ->
            fragment.view?.let { view ->
                view.clearAnimation()
                view.translationX = 0f
                view.alpha = 1f
            }
        }
    }

    private fun motionEnabled(): Boolean {
        val app = requireContext().applicationContext as HabitsApplication
        return !app.component.preferences.isConfettiAnimationDisabled &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
    }

    private fun applyNeutralToolbarAndSystemBars() {
        val binding = binding ?: return
        val activity = requireActivity() as AppCompatActivity
        val appContext = activity.applicationContext as HabitsApplication
        val prefs = appContext.component.preferences
        val palette = SettingsThemePaletteResolver.resolve(requireContext(), prefs)

        binding.root.setBackgroundColor(palette.background)
        binding.settingsContent.setBackgroundColor(palette.background)
        binding.toolbar.root.setBackgroundColor(palette.background)
        binding.statusBarScrim.setBackgroundColor(palette.background)
        binding.toolbar.root.elevation = 0f
        activity.window.statusBarColor = palette.background
        activity.window.navigationBarColor = if (palette.isPureBlack) palette.background else palette.surface

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !palette.isDark
        controller.isAppearanceLightNavigationBars = !palette.isDark
    }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackChangedListener)
        backCallback = null
        navigationInFlight = false
        pendingPop = false
        externalNavigationInFlight = false
        backGuardUntil = 0L
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SHOW_TOOLBAR = "showToolbar"
        private const val TAG_HOME = "settings.home"
        private const val STATE_DETAIL_SCROLL = "settings.detail.scroll"
        private const val ARCHIVE_BACK_GUARD_MS = 350L

        private fun detailTag(sectionId: SettingsSectionId) = "settings.detail.${sectionId.name}"

        fun mainTab(): SettingsSectionFragment = SettingsSectionFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_SHOW_TOOLBAR, false) }
        }
    }
}
