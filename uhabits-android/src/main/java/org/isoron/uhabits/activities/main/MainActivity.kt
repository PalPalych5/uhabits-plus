package org.isoron.uhabits.activities.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.blocks.ManageBlocksActivity
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.ListHabitsDisplayMode
import org.isoron.uhabits.activities.habits.list.ListHabitsFragment
import org.isoron.uhabits.activities.statistics.StatisticsFragment
import org.isoron.uhabits.activities.settings.SettingsSectionFragment
import org.isoron.uhabits.activities.settings.AccentColorManager
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.database.AutoBackup
import org.isoron.uhabits.databinding.ActivityMainBinding
import org.isoron.uhabits.sync.SyncRunResult
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.view.MotionEvent
import org.isoron.uhabits.utils.applyBottomInset
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
    private lateinit var habitsTabAnimator: TabAnimator
    private lateinit var reportsTabAnimator: TabAnimator
    private lateinit var settingsTabAnimator: TabAnimator
    private var bottomNavigationNaturalHeight = 0
    private var bottomNavigationAnimationToken = 0

    private val permissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) scheduleReminders()
        else Log.i("MainActivity", "POST_NOTIFICATIONS denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeSwitcher = AndroidThemeSwitcher(this, prefs)
        themeSwitcher.apply()
        pureBlack = prefs.isPureBlackEnabled
        currentTheme = prefs.theme
        currentResolvedNightMode = themeSwitcher.isNightMode
        prefs.addListener(this)
        Thread.setDefaultUncaughtExceptionHandler(BaseExceptionHandler(this))

        binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.applyRootViewInsets()
        binding.bottomNavigationContainer.applyBottomInset()
        setContentView(binding.root)
        binding.bottomNavigationContainer.post {
            bottomNavigationNaturalHeight = binding.bottomNavigationContainer.height
        }

        val palette = MainTabsThemeBridge.resolve(this)
        binding.mainRoot.setBackgroundColor(palette.background)
        binding.mainContent.setBackgroundColor(palette.background)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.background))
        if (intent.getBooleanExtra(EXTRA_RESTORE_SUCCESS, false)) {
            Toast.makeText(this, R.string.restore_backup_success, Toast.LENGTH_LONG).show()
            intent.removeExtra(EXTRA_RESTORE_SUCCESS)
        }

        var startDest = intent.getStringExtra(EXTRA_RECREATE_DESTINATION)?.let {
            intent.removeExtra(EXTRA_RECREATE_DESTINATION)
            parseDestination(it)
        } ?: savedInstanceState?.getString(STATE_CURRENT)?.let {
            parseDestination(it)
        } ?: destinationFromIntent(intent)

        val restoredHistory = (savedInstanceState?.getStringArrayList(STATE_HISTORY)
            ?.map { parseDestination(it) }
            ?.filterNotNull() ?: emptyList())

        navigationState = MainNavigationState(
            current = startDest,
            history = restoredHistory
        )

        setupBottomNavigation()
        applyAccentColor()

        setupBackHandling()
        ensureCoreFragments()
        showDestination(navigationState.current)
        binding.root.post {
            if (savedInstanceState == null) habitsFragment().onHostStartup()
            handleIncomingIntent(intent)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBottomNavigation() {
        habitsTabAnimator = TabAnimator(binding.iconHabits, binding.labelHabits)
        reportsTabAnimator = TabAnimator(binding.iconReports, binding.labelReports)
        settingsTabAnimator = TabAnimator(binding.iconSettings, binding.labelSettings)

        binding.btnNavHabits.setOnClickListener {
            onBottomItemClicked(MainDestination.HABITS)
        }
        binding.btnNavReports.setOnClickListener {
            onBottomItemClicked(MainDestination.STATISTICS)
        }
        binding.btnNavSettings.setOnClickListener {
            onBottomItemClicked(MainDestination.SETTINGS)
        }

        setupScaleOnPress(binding.btnNavHabits)
        setupScaleOnPress(binding.btnNavReports)
        setupScaleOnPress(binding.btnNavSettings)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScaleOnPress(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
            false
        }
    }

    private fun onBottomItemClicked(destination: MainDestination) {
        if (destination == navigationState.current.bottomItemDestination) {
            return
        }
        if (isBottomTabTransitionRunning) {
            return
        }
        navigateFromBottomTab(destination)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
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
        val tag = tagFor(destination)
        supportFragmentManager.findFragmentByTag(tag)?.let { return it }
        val fragment = when (destination) {
            MainDestination.HABITS, MainDestination.ARCHIVE -> ListHabitsFragment()
            MainDestination.STATISTICS -> StatisticsFragment()
            MainDestination.SETTINGS -> SettingsSectionFragment.mainTab()
        }
        supportFragmentManager.beginTransaction()
            .add(R.id.mainContent, fragment, tag)
            .hide(fragment)
            .commitNowAllowingStateLoss()
        return fragment
    }

    private fun showDestination(destination: MainDestination, animateFrom: MainDestination? = null) {
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
        updateBottomSelection(destination, animate = true, previous = source)

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
                    if (tabTransitionToken == token) {
                        isBottomTabTransitionRunning = false
                        (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    targetView.animate().setListener(null)
                    if (cancelled || tabTransitionToken != token) return

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
                }
            })
            .start()
    }

    private fun showDestinationImmediately(
        destination: MainDestination,
        updateBottomNavigation: Boolean = true
    ) {
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

    private fun updateBottomSelection(
        destination: MainDestination,
        animate: Boolean = false,
        previous: MainDestination? = null
    ) {
        val itemId = when (destination.bottomItemDestination) {
            MainDestination.HABITS -> R.id.navigationHabits
            MainDestination.STATISTICS -> R.id.navigationReports
            MainDestination.SETTINGS -> R.id.navigationSettings
            MainDestination.ARCHIVE -> R.id.navigationHabits
        }

        val activeColor = AccentColorManager.getAccentColor(this, prefs)
        val inactiveColor = ContextCompat.getColor(
            this,
            if (AndroidThemeSwitcher(this, prefs).isNightMode) R.color.grey_500 else R.color.grey_600
        )

        val habitsActive = itemId == R.id.navigationHabits
        val reportsActive = itemId == R.id.navigationReports
        val settingsActive = itemId == R.id.navigationSettings

        // Update colors
        habitsTabAnimator.updateColors(activeColor, inactiveColor)
        reportsTabAnimator.updateColors(activeColor, inactiveColor)
        settingsTabAnimator.updateColors(activeColor, inactiveColor)

        // Animate or snap selection state
        val shouldAnimate = animate && areSystemAnimationsEnabled()
        if (shouldAnimate) {
            habitsTabAnimator.animateTo(habitsActive)
            reportsTabAnimator.animateTo(reportsActive)
            settingsTabAnimator.animateTo(settingsActive)
        } else {
            habitsTabAnimator.snapTo(habitsActive)
            reportsTabAnimator.snapTo(reportsActive)
            settingsTabAnimator.snapTo(settingsActive)
        }
    }

    private fun navigateFromBottomTab(destination: MainDestination) {
        val previous = navigationState.current
        if (!navigationState.navigate(destination)) return
        showDestination(destination, animateFrom = previous)
    }

    override fun navigate(destination: MainDestination) {
        if (isBottomTabTransitionRunning) return
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
                    if (tabTransitionToken != token) return
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
                    if (tabTransitionToken == token) isBottomTabTransitionRunning = false
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
        val destination = intent.getStringExtra(EXTRA_DESTINATION)?.let(::parseDestination)
        if (destination != null && destination != navigationState.current) navigate(destination)
        if (intent.action != null) habitsFragment().handleIntent(intent)
        intent.action = null
    }

    override fun onResume() {
        super.onResume()
        appComponent.midnightTimer.onResume()
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
        appComponent.midnightTimer.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        appComponent.syncCoordinator.scheduleBackgroundSyncIfHasChanges("app_background")
    }

    override fun onDestroy() {
        prefs.removeListener(this)
        super.onDestroy()
    }

    override fun setBottomNavigationVisible(visible: Boolean, animate: Boolean) {
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
                (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.refresh()

                prefs.syncLastUiRefreshReason = reason
                prefs.syncLastUiRefreshAt = System.currentTimeMillis()
                prefs.syncLastUiRefreshDestination = navigationState.current.name
                prefs.syncLastUiRefreshHabitCount = habitCount
            }
        }
    }

    override fun onNavigationPreferencesChanged() = Unit

    fun recreateForThemeChange() {
        currentTheme = prefs.theme
        pureBlack = prefs.isPureBlackEnabled
        currentResolvedNightMode = AndroidThemeSwitcher(this, prefs).isNightMode
        intent.removeExtra(EXTRA_DESTINATION)
        intent.putExtra(EXTRA_RECREATE_DESTINATION, MainDestination.SETTINGS.name)
        recreate()
    }

    override fun onSyncPreferencesChanged() {
        runOnUiThread {
            applyAccentColor()
        }
    }

    private fun applyAccentColor() {
        updateBottomSelection(navigationState.current)
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
        MainDestination.STATISTICS -> TAG_REPORTS
        MainDestination.SETTINGS -> TAG_SETTINGS
    }

    private fun destinationFromIntent(intent: Intent): MainDestination {
        val destName = intent.getStringExtra(EXTRA_DESTINATION)
        return parseDestination(destName) ?: MainDestination.HABITS
    }

    private fun parseDestination(destName: String?): MainDestination? {
        if (destName == "TODAY") return MainDestination.HABITS
        return destName?.let { runCatching { MainDestination.valueOf(it) }.getOrNull() }
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
            destination == MainDestination.STATISTICS ||
            destination == MainDestination.SETTINGS
    }

    private fun tabOrder(destination: MainDestination): Int {
        return when (destination.bottomItemDestination) {
            MainDestination.HABITS -> 0
            MainDestination.STATISTICS -> 1
            MainDestination.SETTINGS -> 2
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
        private const val TAG_HABITS = "main.habits"
        private const val TAG_REPORTS = "main.reports"
        private const val TAG_SETTINGS = "main.settings"

        private const val EXTRA_RESTORE_SUCCESS = "main.restore_success"
        private const val TAB_FADE_OUT_MS = 100L
        private const val TAB_FADE_IN_MS = 180L
        private const val TAB_SLIDE_DP = 12f
        private const val TAB_STATISTICS_SLIDE_DP = 4f
        private const val ARCHIVE_PUSH_MS = 280L
        private const val ARCHIVE_POP_MS = 250L

        fun intent(
            context: Context,
            destination: MainDestination,
            showRestoreSuccess: Boolean = false
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_DESTINATION, destination.name)
                putExtra(EXTRA_RESTORE_SUCCESS, showRestoreSuccess)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

    private class TabAnimator(
        private val iconView: ImageView,
        private val labelView: TextView
    ) {
        private var animator: android.animation.ValueAnimator? = null
        private var activeColor: Int = 0
        private var inactiveColor: Int = 0

        var progress: Float = 0f
            set(value) {
                field = value

                // Scale icon
                val scale = 1.0f + (0.06f * value) // Subtle 1.0 to 1.06 scale
                iconView.scaleX = scale
                iconView.scaleY = scale

                // Alpha
                val alpha = 0.54f + (0.46f * value) // 0.54 to 1.0
                iconView.alpha = alpha
                labelView.alpha = alpha

                // Color blending
                val color = blendColors(inactiveColor, activeColor, value)
                iconView.setColorFilter(color)
                labelView.setTextColor(color)
            }

        fun updateColors(active: Int, inactive: Int) {
            this.activeColor = active
            this.inactiveColor = inactive
            progress = progress // Force color re-evaluation
        }

        fun animateTo(active: Boolean) {
            val target = if (active) 1f else 0f
            if (progress == target) return
            animator?.cancel()
            animator = android.animation.ValueAnimator.ofFloat(progress, target).apply {
                duration = 200
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    progress = animator.animatedValue as Float
                }
                start()
            }
        }

        fun snapTo(active: Boolean) {
            animator?.cancel()
            progress = if (active) 1f else 0f
        }

        private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
            val inverseRatio = 1f - ratio
            val a = (android.graphics.Color.alpha(color1) * inverseRatio + android.graphics.Color.alpha(color2) * ratio).toInt()
            val r = (android.graphics.Color.red(color1) * inverseRatio + android.graphics.Color.red(color2) * ratio).toInt()
            val g = (android.graphics.Color.green(color1) * inverseRatio + android.graphics.Color.green(color2) * ratio).toInt()
            val b = (android.graphics.Color.blue(color1) * inverseRatio + android.graphics.Color.blue(color2) * ratio).toInt()
            return android.graphics.Color.argb(a, r, g, b)
        }
    }
}

