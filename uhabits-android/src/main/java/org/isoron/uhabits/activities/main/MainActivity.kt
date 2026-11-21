package org.isoron.uhabits.activities.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.Menu
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.blocks.ManageBlocksActivity
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.ListHabitsDisplayMode
import org.isoron.uhabits.activities.habits.list.ListHabitsFragment
import org.isoron.uhabits.activities.statistics.StatisticsFragment
import org.isoron.uhabits.activities.statistics.UnifiedStatisticsFragment
import org.isoron.uhabits.activities.settings.SettingsSectionFragment
import org.isoron.uhabits.activities.settings.AccentColorManager
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.database.AutoBackup
import org.isoron.uhabits.databinding.ActivityMainBinding
import org.isoron.uhabits.sync.SyncRunResult
import androidx.core.content.ContextCompat
import org.isoron.uhabits.activities.tasks.TasksFragment
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.restartWithFade

class MainActivity : AppCompatActivity(), MainNavigationHost, SettingsActionHandler,
    Preferences.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navigationState: MainNavigationState
    private val appComponent get() = (applicationContext as HabitsApplication).component
    private val prefs get() = appComponent.preferences
    private var pureBlack = false
    private var currentTheme = 0
    private var currentResolvedNightMode = false
    private var permissionAlreadyRequested = false
    private var isBottomTabTransitionRunning = false
    private var pendingArchiveBack = false
    private var tabTransitionToken = 0
    private lateinit var navigationColors: MutableState<UHabitComposeColors>
    private lateinit var bottomSelection: MutableState<BottomWorkspaceSelection>
    private var sidebarDestination by mutableStateOf(MainDestination.HABITS)
    private var bottomNavigationNaturalHeight = 0
    private var bottomNavigationAnimationToken = 0
    private var taskQuickAddActive = false
    private var habitsSearchActive = false
    private val experimentalWide: Boolean
        get() = appComponent.session.isNextArchitecture && resources.configuration.screenWidthDp >= 600

    private var activeMidnightTimer: org.isoron.uhabits.core.utils.MidnightTimer? = null
    private val datasetListener: () -> Unit = {
        val app = application as? HabitsApplication
        if (app != null && !app.isDatasetRebuilding) {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                startMidnightTimer()
            }
        } else if (app?.isDatasetRebuilding == true) {
            stopMidnightTimer()
        }
    }

    private fun startMidnightTimer() {
        val timer = appComponent.midnightTimer
        if (activeMidnightTimer !== timer) {
            activeMidnightTimer?.onPause()
            activeMidnightTimer = timer
            timer.onResume()
        }
    }

    private fun stopMidnightTimer() {
        activeMidnightTimer?.onPause()
        activeMidnightTimer = null
    }

    private val permissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) scheduleReminders()
        else Log.i("MainActivity", "POST_NOTIFICATIONS denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("activity.create.begin")
        super.onCreate(savedInstanceState)
        val themeSwitcher = AndroidThemeSwitcher(this, prefs)
        themeSwitcher.apply()
        pureBlack = prefs.isPureBlackEnabled
        currentTheme = prefs.theme
        currentResolvedNightMode = themeSwitcher.isNightMode
        prefs.addListener(this)
        (application as? HabitsApplication)?.addDatasetListener(datasetListener)
        BaseExceptionHandler.install(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("activity.inflate.end")
        binding.root.applyRootViewInsets()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigationContainer) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            binding.bottomNavigation.visibility = if ((taskQuickAddActive || habitsSearchActive) &&
                insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            view.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(binding.root)
        binding.bottomNavigationContainer.post {
            bottomNavigationNaturalHeight = binding.bottomNavigationContainer.height
        }

        val palette = MainTabsThemeBridge.resolve(this)
        binding.mainRoot.setBackgroundColor(palette.background)
        binding.mainContent.setBackgroundColor(palette.background)
        navigationColors = mutableStateOf(UHabitComposeColors.fromPalette(palette))
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.background))
        if (intent.getBooleanExtra(EXTRA_RESTORE_SUCCESS, false)) {
            Toast.makeText(this, R.string.restore_backup_success, Toast.LENGTH_LONG).show()
            intent.removeExtra(EXTRA_RESTORE_SUCCESS)
        }
        (application as? HabitsApplication)?.sessionManager?.consumeStartupError()?.let { errorMessage ->
            CustomDialogs.showInfoDialog(
                context = this,
                title = "Внимание",
                message = errorMessage
            )
        }

        var startDest = intent.getStringExtra(EXTRA_RECREATE_DESTINATION)?.let {
            intent.removeExtra(EXTRA_RECREATE_DESTINATION)
            parseDestination(it)
        } ?: savedInstanceState?.getString(STATE_CURRENT)?.let {
            parseDestination(it)
        } ?: destinationFromIntent(intent)

        // The initial destination has been applied; recreation must use saved navigation state.
        intent.removeExtra(EXTRA_DESTINATION)

        val restoredHistory = (savedInstanceState?.getStringArrayList(STATE_HISTORY)
            ?.map { parseDestination(it) }
            ?.filterNotNull() ?: emptyList())

        navigationState = MainNavigationState(
            current = startDest,
            history = restoredHistory
        )

        setupBottomNavigation()
        setupAdaptiveNavigation()
        applyAccentColor()

        setupBackHandling()
        ensureCoreFragments()
        showDestination(navigationState.current)
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("activity.create.end")
        binding.root.post {
            // Width changes can destroy this host before its startup callback runs.
            if (isFinishing || isDestroyed || supportFragmentManager.isDestroyed) return@post
            if (savedInstanceState == null) habitsFragment().onHostStartup()
            handleIncomingIntent(intent)
        }
    }

    private fun setupBottomNavigation() {
        bottomSelection = mutableStateOf(bottomWorkspaceSelection(navigationState.current, animate = false))
        binding.bottomNavigation.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(navigationColors.value) {
                    MainBottomNavigation(
                        bottomSelection.value,
                        appComponent.session.isNextArchitecture,
                        ::onBottomItemClicked
                    )
                }
            }
        }
    }

    private fun onBottomItemClicked(destination: MainDestination) {
        if (destination == navigationState.current.bottomItemDestination) {
            return
        }
        if (isBottomTabTransitionRunning) {
            cancelTabTransitionAnimations()
        }
        navigateFromBottomTab(destination)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val habits = supportFragmentManager.findFragmentByTag(TAG_HABITS) as? ListHabitsFragment
                    if (habits != null && habits.isVisible && habits.handleBack()) {
                        return
                    }
                    val tasks = supportFragmentManager.findFragmentByTag(TAG_TASKS) as? TasksFragment
                    if (tasks != null && tasks.isVisible && tasks.onBackPressed()) return
                    if (!navigateBack()) finish()
                }
            }
        )
    }

    private fun ensureCoreFragments() {
        val transaction = supportFragmentManager.beginTransaction()
        if (supportFragmentManager.findFragmentByTag(TAG_HABITS) == null) {
            val habits = ListHabitsFragment()
            transaction.add(R.id.mainContent, habits, TAG_HABITS)
        }
        transaction.commitNowAllowingStateLoss()
    }

    private fun fragmentFor(destination: MainDestination): Fragment {
        if (destination == MainDestination.TASKS && !appComponent.session.isNextArchitecture) {
            return fragmentFor(MainDestination.HABITS)
        }
        val tag = tagFor(destination)
        supportFragmentManager.findFragmentByTag(tag)?.let {
            configureWorkspaceFragment(it)
            return it
        }
        val fragment = when (destination) {
            MainDestination.HABITS, MainDestination.ARCHIVE -> ListHabitsFragment()
            MainDestination.TASKS -> TasksFragment()
            MainDestination.STATISTICS -> {
                if (appComponent.session.isNextArchitecture) {
                    val cid = intent.getStringExtra(EXTRA_STATISTICS_CONTAINER_ID)?.takeIf { it.isNotBlank() }
                    val incDesc = intent.getBooleanExtra(EXTRA_STATISTICS_INCLUDE_DESCENDANTS, true)
                    if (cid != null) {
                        intent.removeExtra(EXTRA_STATISTICS_CONTAINER_ID)
                        intent.removeExtra(EXTRA_STATISTICS_INCLUDE_DESCENDANTS)
                        UnifiedStatisticsFragment.newInstance(cid, incDesc)
                    } else {
                        UnifiedStatisticsFragment()
                    }
                } else {
                    StatisticsFragment()
                }
            }
            MainDestination.SETTINGS -> SettingsSectionFragment.mainTab()
        }
        configureWorkspaceFragment(fragment)
        supportFragmentManager.beginTransaction()
            .add(R.id.mainContent, fragment, tag)
            .hide(fragment)
            .commitNowAllowingStateLoss()
        return fragment
    }

    private fun showDestination(destination: MainDestination, animateFrom: MainDestination? = null) {
        updateAdaptiveNavigation(destination)
        when (destination) {
            MainDestination.ARCHIVE -> setBottomNavigationVisible(false, false)
            MainDestination.SETTINGS -> Unit
            else -> setBottomNavigationVisible(true, false)
        }
        val source = animateFrom
        val shouldAnimate = source != null &&
            canAnimateMainTabTransition(source, destination) &&
            areSystemAnimationsEnabled()
        if (!shouldAnimate) {
            tabTransitionToken++
            cancelTabTransitionAnimations()
            showDestinationImmediately(destination)
            return
        }

        val sourceFragment = supportFragmentManager.findFragmentByTag(tagFor(source!!))
        val sourceView = sourceFragment?.view
        val target = fragmentFor(destination)

        // Show target fragment and set its max lifecycle to RESUMED, and source fragment to STARTED
        val transaction = supportFragmentManager.beginTransaction()
        transaction.show(target).setMaxLifecycle(target, Lifecycle.State.RESUMED)
        sourceFragment?.let {
            transaction.setMaxLifecycle(it, Lifecycle.State.STARTED)
        }
        transaction.commitNowAllowingStateLoss()

        val targetView = target.view
        if (sourceView == null || targetView == null || sourceView.width == 0 || binding.mainContent.width == 0) {
            tabTransitionToken++
            cancelTabTransitionAnimations()
            showDestinationImmediately(destination)
            return
        }

        cancelTabTransitionAnimations()
        val token = ++tabTransitionToken
        val direction = tabOrder(destination) - tabOrder(source)
        val slideDistance = if (destination == MainDestination.STATISTICS) {
            TAB_STATISTICS_SLIDE_DP
        } else {
            TAB_SLIDE_DP
        }
        val slide = dp(slideDistance) * if (direction >= 0) 1f else -1f
        isBottomTabTransitionRunning = true
        (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionStarted()
        (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? UnifiedStatisticsFragment)?.onTabTransitionStarted()
        updateBottomSelection(destination, animate = true)

        if (target is ListHabitsFragment) {
            target.setDisplayMode(
                if (destination == MainDestination.ARCHIVE) {
                    ListHabitsDisplayMode.ARCHIVE
                } else {
                    ListHabitsDisplayMode.NORMAL
                }
            )
        }
        setHabitCreationAvailable(destination == MainDestination.HABITS)

        // Cancel running animations on source and target views
        sourceView.animate().setListener(null).cancel()
        targetView.animate().setListener(null).cancel()

        // Prepare targetView
        targetView.alpha = 0f
        targetView.translationX = slide
        targetView.bringToFront()

        val duration = TAB_FADE_IN_MS

        sourceView.animate()
            .alpha(0.95f)
            .translationX(-slide * 0.3f)
            .setDuration(duration)
            .setInterpolator(AccelerateInterpolator())
            .start()

        targetView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    resetFragmentRoot(sourceView)
                    resetFragmentRoot(targetView)
                    isBottomTabTransitionRunning = false
                    (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
                    (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? UnifiedStatisticsFragment)?.onTabTransitionEnded()
                }

                override fun onAnimationEnd(animation: Animator) {
                    targetView.animate().setListener(null)
                    if (isDestroyed || supportFragmentManager.isDestroyed) {
                        isBottomTabTransitionRunning = false
                        return
                    }
                    if (cancelled || tabTransitionToken != token) {
                        if (tabTransitionToken == token) isBottomTabTransitionRunning = false
                        return
                    }

                    // Animation complete, now we hide the source fragment and any other non-target fragments
                    val hideTransaction = supportFragmentManager.beginTransaction()
                    supportFragmentManager.fragments.forEach { fragment ->
                        if (fragment == target) {
                            hideTransaction.show(fragment).setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                        } else {
                            hideTransaction.hide(fragment).setMaxLifecycle(fragment, Lifecycle.State.STARTED)
                        }
                    }
                    hideTransaction.commitNowAllowingStateLoss()

                    resetFragmentRoot(sourceView)
                    resetFragmentRoot(targetView)
                    isBottomTabTransitionRunning = false
                    (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
                    (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? UnifiedStatisticsFragment)?.onTabTransitionEnded()
                }
            })
            .start()
    }

    private fun showDestinationImmediately(
        destination: MainDestination,
        updateBottomNavigation: Boolean = true
    ) {
        updateAdaptiveNavigation(destination)
        val target = fragmentFor(destination)
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment == target) {
                transaction.show(fragment).setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            } else {
                transaction.hide(fragment).setMaxLifecycle(fragment, Lifecycle.State.STARTED)
            }
        }
        transaction.commitNowAllowingStateLoss()

        if (destination == MainDestination.TASKS && org.isoron.uhabits.utils.HabitsStartupTrace.enabled) {
            lateinit var draw: android.view.ViewTreeObserver.OnDrawListener
            draw = android.view.ViewTreeObserver.OnDrawListener {
                org.isoron.uhabits.utils.HabitsStartupTrace.mark("workspace.tasks.draw")
                binding.root.post { if (binding.root.viewTreeObserver.isAlive) binding.root.viewTreeObserver.removeOnDrawListener(draw) }
            }
            binding.root.viewTreeObserver.addOnDrawListener(draw)
        }

        if (target is ListHabitsFragment) {
            target.setDisplayMode(
                if (destination == MainDestination.ARCHIVE) {
                    ListHabitsDisplayMode.ARCHIVE
                } else {
                    ListHabitsDisplayMode.NORMAL
                }
            )
        }
        if (updateBottomNavigation) updateBottomSelection(destination)
        setHabitCreationAvailable(
            destination == MainDestination.HABITS
        )
        when (destination) {
            MainDestination.ARCHIVE -> setBottomNavigationVisible(false, false)
            MainDestination.SETTINGS -> Unit
            else -> setBottomNavigationVisible(true, false)
        }
    }

    private fun updateBottomSelection(destination: MainDestination, animate: Boolean = false) {
        bottomSelection.value = bottomWorkspaceSelection(destination, animate)
    }

    private fun bottomWorkspaceSelection(destination: MainDestination, animate: Boolean): BottomWorkspaceSelection {
        val activeColor = AccentColorManager.getAccentColor(this, prefs)
        val inactiveColor = if (appComponent.session.isNextArchitecture) MainTabsThemeBridge.resolve(this).onSurfaceVariant else ContextCompat.getColor(
            this,
            if (AndroidThemeSwitcher(this, prefs).isNightMode) R.color.grey_500 else R.color.grey_600
        )

        return BottomWorkspaceSelection(destination, activeColor, inactiveColor, animate && areSystemAnimationsEnabled())
    }

    private fun navigateFromBottomTab(destination: MainDestination) {
        val previous = navigationState.current
        if (!navigationState.navigate(destination)) return
        showDestination(destination, animateFrom = previous)
    }

    override fun navigate(destination: MainDestination) {
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("navigation.request.${destination.name}")
        if (destination == MainDestination.TASKS && !appComponent.session.isNextArchitecture) return
        if (isBottomTabTransitionRunning) {
            cancelTabTransitionAnimations()
        }
        val previous = navigationState.current
        if (!navigationState.navigate(destination)) return
        if (previous == MainDestination.SETTINGS && destination == MainDestination.ARCHIVE) {
            showArchiveTransition(previous, destination, returning = false)
        } else {
            showDestination(destination)
        }
    }

    override fun navigateBack(): Boolean {
        if (isBottomTabTransitionRunning) {
            cancelTabTransitionAnimations()
            if (navigationState.current == MainDestination.ARCHIVE) pendingArchiveBack = true
            return true
        }
        val previous = navigationState.current
        val destination = navigationState.navigateBack()
        if (destination == null) return false
        if (previous == MainDestination.ARCHIVE && destination == MainDestination.SETTINGS) {
            showArchiveTransition(previous, destination, returning = true)
        } else {
            showDestination(destination)
        }
        return true
    }

    private fun showArchiveTransition(
        sourceDestination: MainDestination,
        targetDestination: MainDestination,
        returning: Boolean
    ) {
        pendingArchiveBack = false
        setBottomNavigationVisible(false, false)
        val source = supportFragmentManager.findFragmentByTag(tagFor(sourceDestination))
        val target = fragmentFor(targetDestination)
        (source as? SettingsSectionFragment)?.setArchiveTransitionInFlight(true)
        (target as? SettingsSectionFragment)?.setArchiveTransitionInFlight(true)
        if (target is ListHabitsFragment) target.setDisplayMode(ListHabitsDisplayMode.ARCHIVE)

        supportFragmentManager.beginTransaction()
            .show(target)
            .setMaxLifecycle(target, Lifecycle.State.RESUMED)
            .apply { source?.let { setMaxLifecycle(it, Lifecycle.State.STARTED) } }
            .commitNowAllowingStateLoss()

        val sourceView = source?.view
        val targetView = target.view
        val motionEnabled = !prefs.isConfettiAnimationDisabled && areSystemAnimationsEnabled()
        if (!motionEnabled || sourceView == null || targetView == null || binding.mainContent.width == 0) {
            showDestinationImmediately(targetDestination, updateBottomNavigation = false)
            setBottomNavigationVisible(false, false)
            (source as? SettingsSectionFragment)?.setArchiveTransitionInFlight(false)
            (target as? SettingsSectionFragment)?.setArchiveTransitionInFlight(false)
            return
        }

        cancelTabTransitionAnimations()
        val token = ++tabTransitionToken
        val direction = if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        val width = binding.mainContent.width.toFloat()
        val fullSlide = width * direction
        val sourceOffset = width * 0.18f * direction
        val duration = if (returning) ARCHIVE_POP_MS else ARCHIVE_PUSH_MS
        isBottomTabTransitionRunning = true
        sourceView.animate().setListener(null).cancel()
        targetView.animate().setListener(null).cancel()

        if (returning) {
            targetView.translationX = -sourceOffset
            targetView.alpha = 0.94f
        } else {
            targetView.translationX = fullSlide
            targetView.alpha = 1f
        }
        targetView.bringToFront()

        sourceView.animate()
            .translationX(if (returning) fullSlide else -sourceOffset)
            .alpha(if (returning) 1f else 0.94f)
            .setDuration(duration)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
        targetView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    targetView.animate().setListener(null)
                    if (isDestroyed || supportFragmentManager.isDestroyed) {
                        isBottomTabTransitionRunning = false
                        return
                    }
                    if (tabTransitionToken != token) {
                        if (tabTransitionToken == token) isBottomTabTransitionRunning = false
                        return
                    }
                    val transaction = supportFragmentManager.beginTransaction()
                    supportFragmentManager.fragments.forEach { fragment ->
                        if (fragment == target) {
                            transaction.show(fragment).setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                        } else {
                            transaction.hide(fragment).setMaxLifecycle(fragment, Lifecycle.State.STARTED)
                        }
                    }
                    transaction.commitNowAllowingStateLoss()
                    resetFragmentRoot(sourceView)
                    resetFragmentRoot(targetView)
                    isBottomTabTransitionRunning = false
                    setBottomNavigationVisible(false, false)
                    (source as? SettingsSectionFragment)?.setArchiveTransitionInFlight(false)
                    (target as? SettingsSectionFragment)?.setArchiveTransitionInFlight(false)
                    if (pendingArchiveBack) {
                        pendingArchiveBack = false
                        binding.root.post { navigateBack() }
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    resetFragmentRoot(sourceView)
                    resetFragmentRoot(targetView)
                    isBottomTabTransitionRunning = false
                    (source as? SettingsSectionFragment)?.setArchiveTransitionInFlight(false)
                    (target as? SettingsSectionFragment)?.setArchiveTransitionInFlight(false)
                }
            })
            .start()
    }

    override fun setHabitCreationAvailable(available: Boolean) {
        // No-op: FAB is removed
    }

    override fun onSettingsAction(action: SettingsAction) {
        when (action) {
            SettingsAction.MANAGE_SPHERES -> startActivity(
                Intent(this, ManageBlocksActivity::class.java)
            )
            SettingsAction.OPEN_ARCHIVE -> navigate(MainDestination.ARCHIVE)
            else -> habitsFragment().handleSettingsAction(action)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_WORKSPACE_SETTINGS && appComponent.session.isNextArchitecture) {
            navigate(MainDestination.SETTINGS)
            return true
        }
        if (item.itemId == android.R.id.home && navigationState.current == MainDestination.ARCHIVE) {
            return navigateBack()
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        habitsFragment().handleActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val timerDataset = intent.getStringExtra("task.focus.dataset")
        val openTaskTimer = intent.getBooleanExtra("task.focus.open", false)
        intent.removeExtra("task.focus.open")
        intent.removeExtra("task.focus.dataset")
        if (openTaskTimer && appComponent.session.isNextArchitecture && appComponent.session.datasetId == timerDataset) {
            val manager = appComponent.timerSessionManager
            val target = manager.currentTarget
            if (target?.kind == org.isoron.uhabits.core.focus.FocusPrimaryTargetType.TASK && manager.snapshot().hasActiveSession &&
                supportFragmentManager.findFragmentByTag(org.isoron.uhabits.activities.tasks.TaskFocusBottomSheet.TAG) == null) {
                org.isoron.uhabits.activities.tasks.TaskFocusBottomSheet.newInstance(
                    org.isoron.uhabits.core.userTasks.TaskId(target.id), manager.snapshot().habitName, false
                ).show(supportFragmentManager, org.isoron.uhabits.activities.tasks.TaskFocusBottomSheet.TAG)
            }
        }
        val destination = intent.getStringExtra(EXTRA_DESTINATION)?.let(::parseDestination)
        intent.removeExtra(EXTRA_DESTINATION)
        if (destination != null && destination != navigationState.current) {
            if (isBottomTabTransitionRunning) {
                tabTransitionToken++
                cancelTabTransitionAnimations()
            }
            navigate(destination)
        }
        val statsContainerId = intent.getStringExtra(EXTRA_STATISTICS_CONTAINER_ID)?.takeIf { it.isNotBlank() }
        val statsIncludeDescendants = intent.getBooleanExtra(EXTRA_STATISTICS_INCLUDE_DESCENDANTS, true)
        if (statsContainerId != null) {
            intent.removeExtra(EXTRA_STATISTICS_CONTAINER_ID)
            intent.removeExtra(EXTRA_STATISTICS_INCLUDE_DESCENDANTS)
            (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? UnifiedStatisticsFragment)?.applyScopedContainer(
                org.isoron.uhabits.core.containers.ContainerId(statsContainerId), statsIncludeDescendants
            )
        }
        if (intent.action != null) habitsFragment().handleIntent(intent)
        intent.action = null
    }

    override fun onResume() {
        super.onResume()
        isBottomTabTransitionRunning = false
        startMidnightTimer()
        requestNotificationPermissionAndSchedule()
        appComponent.taskRunner.run {
            try {
                AutoBackup(this@MainActivity).run()
                appComponent.widgetUpdater.updateWidgets()
            } catch (t: Throwable) {
                Log.e("MainActivity", "Startup maintenance failed", t)
            }
        }
        if (prefs.isSyncEnabled) {
            try {
                appComponent.taskRunner.execute(object : Task {
                    private var syncResult: SyncRunResult? = null

                    override suspend fun doInBackground() {
                        try {
                            syncResult = appComponent.syncCoordinator.runSync(manual = false)
                        } catch (t: Throwable) {
                            Log.e("MainActivity", "Background sync failed", t)
                        }
                    }

                    override fun onPostExecute() {
                        val result = syncResult
                        if (result is SyncRunResult.Success && result.pulled > 0) {
                            reloadVisibleHabitScreens("background_sync_success")
                        }
                    }
                })
            } catch (t: Throwable) {
                Log.e("MainActivity", "Background sync setup failed", t)
            }
        }
        applyAccentColor()
        val resolvedNightMode = AndroidThemeSwitcher(this, prefs).isNightMode
        if (prefs.theme != currentTheme ||
            prefs.isPureBlackEnabled != pureBlack ||
            resolvedNightMode != currentResolvedNightMode
        ) {
            restartWithFade(MainActivity::class.java)
        }
    }

    override fun onPause() {
        cancelTabTransitionAnimations()
        stopMidnightTimer()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        appComponent.syncCoordinator.scheduleBackgroundSyncIfHasChanges("app_background")
    }

    override fun onDestroy() {
        tabTransitionToken++
        cancelTabTransitionAnimations()
        (application as? HabitsApplication)?.removeDatasetListener(datasetListener)
        prefs.removeListener(this)
        super.onDestroy()
    }

    fun setTaskQuickAddActive(active: Boolean) {
        if (taskQuickAddActive == active) return
        taskQuickAddActive = active
        androidx.core.view.ViewCompat.getRootWindowInsets(binding.bottomNavigationContainer)?.let {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(binding.bottomNavigationContainer, it)
        }
    }

    fun setHabitsSearchActive(active: Boolean) {
        if (habitsSearchActive == active) return
        habitsSearchActive = active
        androidx.core.view.ViewCompat.getRootWindowInsets(binding.bottomNavigationContainer)?.let {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(binding.bottomNavigationContainer, it)
        }
    }

    override fun setBottomNavigationVisible(visible: Boolean, animate: Boolean) {
        if (experimentalWide) {
            binding.bottomNavigationContainer.visibility = View.GONE
            return
        }
        val container = binding.bottomNavigationContainer
        if (container.height > 0) bottomNavigationNaturalHeight = container.height
        val naturalHeight = bottomNavigationNaturalHeight.takeIf { it > 0 }
            ?: (50 * resources.displayMetrics.density).toInt()
        val shouldAnimate = animate && !prefs.isConfettiAnimationDisabled && areSystemAnimationsEnabled()
        bottomNavigationAnimationToken++
        val token = bottomNavigationAnimationToken
        container.animate().setListener(null).cancel()

        if (!shouldAnimate) {
            container.visibility = if (visible) View.VISIBLE else View.GONE
            container.translationY = 0f
            container.alpha = 1f
            return
        }
        if (visible && container.visibility == View.VISIBLE && container.translationY == 0f) return
        if (!visible && container.visibility != View.VISIBLE) return

        if (visible) {
            container.visibility = View.VISIBLE
            container.translationY = naturalHeight.toFloat()
            container.alpha = 0.96f
        }
        container.animate()
            .translationY(if (visible) 0f else naturalHeight.toFloat())
            .alpha(if (visible) 1f else 0.96f)
            .setDuration(if (visible) 250L else 220L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != bottomNavigationAnimationToken) return
                    container.visibility = if (visible) View.VISIBLE else View.GONE
                    container.translationY = 0f
                    container.alpha = 1f
                }
            })
            .start()
    }

    override fun onQuestionMarksChanged() = Unit

    override fun onSyncFinished() {
        reloadVisibleHabitScreens("sync_finished_listener")
    }

    fun reloadVisibleHabitScreens(reason: String) {
        runOnUiThread {
            binding.root.post {
                val sqliteHabitList = appComponent.habitList as? SQLiteHabitList
                sqliteHabitList?.reloadAndNotify()
                val habitCount = runCatching { appComponent.habitList.size().toLong() }.getOrDefault(-1L)

                (supportFragmentManager.findFragmentByTag(TAG_HABITS) as? ListHabitsFragment)?.refresh(
                    reloadFromDatabase = true
                )
                (supportFragmentManager.findFragmentByTag(TAG_TASKS) as? TasksFragment)?.refresh()
                (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.refresh()
                (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? UnifiedStatisticsFragment)?.refresh()

                prefs.syncLastUiRefreshReason = reason
                prefs.syncLastUiRefreshAt = System.currentTimeMillis()
                prefs.syncLastUiRefreshDestination = navigationState.current.name
                prefs.syncLastUiRefreshHabitCount = habitCount
            }
        }
    }

    override fun onNavigationPreferencesChanged() = Unit

    fun recreateForThemeChange() {
        appComponent.habitCardListCache.invalidateStyles()
        currentTheme = prefs.theme
        pureBlack = prefs.isPureBlackEnabled
        currentResolvedNightMode = AndroidThemeSwitcher(this, prefs).isNightMode
        intent.removeExtra(EXTRA_DESTINATION)
        val destination = if (navigationState.current == MainDestination.TASKS) MainDestination.TASKS else MainDestination.SETTINGS
        intent.putExtra(EXTRA_RECREATE_DESTINATION, destination.name)
        recreate()
    }

    override fun onSyncPreferencesChanged() {
        runOnUiThread {
            applyAccentColor()
        }
    }

    private fun applyAccentColor() {
        navigationColors.value = UHabitComposeColors.fromPalette(MainTabsThemeBridge.resolve(this))
        updateBottomSelection(navigationState.current)
        updateAdaptiveNavigation(navigationState.current)
    }

    private fun setupAdaptiveNavigation() {
        binding.workspaceSidebarHost.apply {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { sidebar, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                sidebar.setPadding(0, maxOf(bars.top, cutout.top), 0, maxOf(bars.bottom, cutout.bottom))
                insets
            }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(navigationColors.value) {
                    MainWorkspaceSidebar(sidebarDestination, onDestination = ::navigate)
                }
            }
        }
        binding.workspaceSecondaryToolbar.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(navigationColors.value) {
                    StatisticsWorkspaceToolbar(onSettings = { navigate(MainDestination.SETTINGS) })
                }
            }
        }
    }

    private fun configureWorkspaceFragment(fragment: Fragment) {
        if (fragment is TasksFragment) {
            fragment.workspaceNavigator = { workspace ->
                val destination = MainDestination.entries.firstOrNull {
                    it.name.equals(workspace, ignoreCase = true)
                }
                if (destination != null) navigate(destination)
            }
        }
    }

    private fun updateAdaptiveNavigation(destination: MainDestination) {
        val colors = navigationColors.value
        if (appComponent.session.isNextArchitecture) {
            androidx.core.view.WindowInsetsControllerCompat(window, binding.root).apply {
                isAppearanceLightStatusBars = !colors.isDark
                isAppearanceLightNavigationBars = !colors.isDark
            }
        }
        binding.workspaceSidebarHost.setBackgroundColor(colors.surface)
        binding.workspaceSecondaryToolbar.setBackgroundColor(colors.background)
        binding.workspaceSidebarHost.visibility = if (
            experimentalWide && destination != MainDestination.TASKS
        ) View.VISIBLE else View.GONE
        sidebarDestination = destination
        binding.workspaceSecondaryToolbar.visibility = if (
            appComponent.session.isNextArchitecture && !experimentalWide && destination == MainDestination.STATISTICS
        ) View.VISIBLE else View.GONE
        androidx.core.view.ViewCompat.requestApplyInsets(binding.workspaceSecondaryToolbar)
        androidx.core.view.ViewCompat.requestApplyInsets(binding.workspaceSidebarHost)
        invalidateOptionsMenu()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val showSettings = appComponent.session.isNextArchitecture &&
            navigationState.current == MainDestination.HABITS
        val settings = menu.findItem(MENU_WORKSPACE_SETTINGS) ?: if (showSettings) {
            menu.add(0, MENU_WORKSPACE_SETTINGS, 100, R.string.settings).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        } else null
        settings?.isVisible = showSettings
        return super.onPrepareOptionsMenu(menu)
    }

    private fun requestNotificationPermissionAndSchedule() {
        if (!appComponent.reminderScheduler.hasHabitsWithReminders()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(this, POST_NOTIFICATIONS) == PERMISSION_GRANTED
        ) {
            scheduleReminders()
        } else if (!permissionAlreadyRequested) {
            permissionAlreadyRequested = true
            permissionLauncher.launch(POST_NOTIFICATIONS)
        }
    }

    private fun scheduleReminders() = appComponent.reminderScheduler.scheduleAll()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT, navigationState.current.name)
        outState.putStringArrayList(
            STATE_HISTORY,
            ArrayList(navigationState.history().map { it.name })
        )
        super.onSaveInstanceState(outState)
    }

    private fun habitsFragment() = fragmentFor(MainDestination.HABITS) as ListHabitsFragment

    private fun tagFor(destination: MainDestination) = when (destination) {
        MainDestination.HABITS, MainDestination.ARCHIVE -> TAG_HABITS
        MainDestination.TASKS -> TAG_TASKS
        MainDestination.STATISTICS -> TAG_REPORTS
        MainDestination.SETTINGS -> TAG_SETTINGS
    }

    private fun destinationFromIntent(intent: Intent): MainDestination {
        val destName = intent.getStringExtra(EXTRA_DESTINATION)
        return parseDestination(destName) ?: MainDestination.HABITS
    }

    private fun parseDestination(destName: String?): MainDestination? {
        if (destName == "TODAY") return MainDestination.HABITS
        val destination = destName?.let { runCatching { MainDestination.valueOf(it) }.getOrNull() }
        return destination?.takeUnless { it == MainDestination.TASKS && !appComponent.session.isNextArchitecture }
    }

    private fun canAnimateMainTabTransition(
        previous: MainDestination,
        destination: MainDestination
    ): Boolean {
        val from = previous.bottomItemDestination
        val to = destination.bottomItemDestination
        return from != to && isMainTab(from) && isMainTab(to)
    }

    private fun isMainTab(destination: MainDestination): Boolean {
        return destination == MainDestination.HABITS ||
            destination == MainDestination.TASKS ||
            destination == MainDestination.STATISTICS ||
            destination == MainDestination.SETTINGS
    }

    private fun tabOrder(destination: MainDestination): Int {
        return when (destination.bottomItemDestination) {
            MainDestination.HABITS -> 0
            MainDestination.TASKS -> 1
            MainDestination.STATISTICS -> 2
            MainDestination.SETTINGS -> 3
            MainDestination.ARCHIVE -> 0
        }
    }

    private fun areSystemAnimationsEnabled(): Boolean {
        return runCatching {
            android.provider.Settings.Global.getFloat(
                contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }

    private fun cancelTabTransitionAnimations() {
        isBottomTabTransitionRunning = false
        supportFragmentManager.fragments.forEach { fragment ->
            fragment.view?.let { view ->
                view.animate().setListener(null).cancel()
                resetFragmentRoot(view)
            }
        }
        (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
        (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? UnifiedStatisticsFragment)?.onTabTransitionEnded()
    }

    private fun resetFragmentRoot(view: View?) {
        view ?: return
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }



    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    companion object {
        private const val EXTRA_DESTINATION = "main.destination"
        private const val EXTRA_RECREATE_DESTINATION = "main.recreate_destination"
        private const val STATE_CURRENT = "main.current"
        private const val STATE_HISTORY = "main.history"
        private const val MENU_WORKSPACE_SETTINGS = 0x7110
        private const val TAG_HABITS = "main.habits"
        private const val TAG_TASKS = "main.tasks"
        private const val TAG_REPORTS = "main.reports"
        private const val TAG_SETTINGS = "main.settings"

        private const val EXTRA_RESTORE_SUCCESS = "main.restore_success"
        private const val TAB_FADE_OUT_MS = 100L
        private const val TAB_FADE_IN_MS = 180L
        private const val TAB_SLIDE_DP = 12f
        private const val TAB_STATISTICS_SLIDE_DP = 4f
        private const val ARCHIVE_PUSH_MS = 280L
        private const val ARCHIVE_POP_MS = 250L

        const val EXTRA_STATISTICS_CONTAINER_ID = "org.isoron.uhabits.STATISTICS_CONTAINER_ID"
        const val EXTRA_STATISTICS_INCLUDE_DESCENDANTS = "org.isoron.uhabits.STATISTICS_INCLUDE_DESCENDANTS"

        fun intent(
            context: Context,
            destination: MainDestination,
            showRestoreSuccess: Boolean = false,
            statisticsContainerId: String? = null,
            statisticsIncludeDescendants: Boolean = true,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_DESTINATION, destination.name)
                putExtra(EXTRA_RESTORE_SUCCESS, showRestoreSuccess)
                if (statisticsContainerId != null) {
                    putExtra(EXTRA_STATISTICS_CONTAINER_ID, statisticsContainerId)
                    putExtra(EXTRA_STATISTICS_INCLUDE_DESCENDANTS, statisticsIncludeDescendants)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

}

