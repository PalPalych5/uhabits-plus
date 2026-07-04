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
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
    private var updatingBottomNavigation = false
    private var isBottomTabTransitionRunning = false
    private var tabTransitionToken = 0

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
        setContentView(binding.root)
        if (intent.getBooleanExtra(EXTRA_RESTORE_SUCCESS, false)) {
            Toast.makeText(this, R.string.restore_backup_success, Toast.LENGTH_LONG).show()
            intent.removeExtra(EXTRA_RESTORE_SUCCESS)
        }

        var startDest = savedInstanceState?.getString(STATE_CURRENT)?.let {
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

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (updatingBottomNavigation) return@setOnItemSelectedListener true
            val destination = when (item.itemId) {
                R.id.navigationHabits -> MainDestination.HABITS
                R.id.navigationReports -> MainDestination.STATISTICS
                R.id.navigationSettings -> MainDestination.SETTINGS
                else -> return@setOnItemSelectedListener false
            }
            if (destination == navigationState.current.bottomItemDestination) {
                return@setOnItemSelectedListener true
            }
            if (isBottomTabTransitionRunning) {
                return@setOnItemSelectedListener false
            }
            navigateFromBottomTab(destination)
            true
        }
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
            MainDestination.SETTINGS -> SettingsSectionFragment()
        }
        supportFragmentManager.beginTransaction()
            .add(R.id.mainContent, fragment, tag)
            .hide(fragment)
            .commitNowAllowingStateLoss()
        return fragment
    }

    private fun showDestination(destination: MainDestination, animateFrom: MainDestination? = null) {
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
        if (sourceView == null || sourceView.width == 0 || binding.mainContent.width == 0) {
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

        sourceView.animate().setListener(null).cancel()
        target.view?.animate()?.setListener(null)?.cancel()
        sourceView.animate()
            .alpha(0f)
            .translationX(-slide / 2f)
            .setDuration(TAB_FADE_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    resetFragmentRoot(sourceView)
                    resetFragmentRoot(target.view)
                    if (tabTransitionToken == token) {
                        isBottomTabTransitionRunning = false
                        (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    sourceView.animate().setListener(null)
                    if (cancelled || tabTransitionToken != token) return
                    showDestinationImmediately(destination, updateBottomNavigation = false)
                    val targetView = target.view
                    if (targetView == null) {
                        resetFragmentRoot(sourceView)
                        isBottomTabTransitionRunning = false
                        (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
                        return
                    }
                    targetView.alpha = 0f
                    targetView.translationX = slide
                    targetView.animate().setListener(null).cancel()
                    targetView.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(TAB_FADE_IN_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .setListener(object : AnimatorListenerAdapter() {
                            private var innerCancelled = false

                            override fun onAnimationCancel(animation: Animator) {
                                innerCancelled = true
                                resetFragmentRoot(sourceView)
                                resetFragmentRoot(targetView)
                                if (tabTransitionToken == token) {
                                    isBottomTabTransitionRunning = false
                                    (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
                                }
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                targetView.animate().setListener(null)
                                resetFragmentRoot(sourceView)
                                resetFragmentRoot(targetView)
                                if (!innerCancelled && tabTransitionToken == token) {
                                    isBottomTabTransitionRunning = false
                                    (supportFragmentManager.findFragmentByTag(TAG_REPORTS) as? StatisticsFragment)?.onTabTransitionEnded()
                                }
                            }
                        })
                        .start()
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
    }

    private fun updateBottomSelection(
        destination: MainDestination,
        animate: Boolean = false,
        previous: MainDestination? = null
    ) {
        val previousBottom = previous?.bottomItemDestination ?: navigationState.current.bottomItemDestination
        val nextBottom = destination.bottomItemDestination
        val itemId = when (destination.bottomItemDestination) {
            MainDestination.HABITS -> R.id.navigationHabits
            MainDestination.STATISTICS -> R.id.navigationReports
            MainDestination.SETTINGS -> R.id.navigationSettings
            MainDestination.ARCHIVE -> R.id.navigationHabits
        }
        updatingBottomNavigation = true
        binding.bottomNavigation.selectedItemId = itemId
        updatingBottomNavigation = false
        animateBottomNavigationItems(previousBottom, nextBottom, animate)
    }

    private fun navigateFromBottomTab(destination: MainDestination) {
        val previous = navigationState.current
        if (!navigationState.navigate(destination)) return
        showDestination(destination, animateFrom = previous)
    }

    override fun navigate(destination: MainDestination) {
        if (!navigationState.navigate(destination)) return
        showDestination(destination)
    }

    override fun navigateBack(): Boolean {
        val destination = navigationState.navigateBack()
        if (destination == null) return false
        showDestination(destination)
        return true
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
        val destination = destinationFromIntent(intent)
        if (destination != navigationState.current) navigate(destination)
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

    override fun onSyncPreferencesChanged() {
        runOnUiThread {
            applyAccentColor()
        }
    }

    private fun applyAccentColor() {
        val colorStateList = AccentColorManager.getAccentColorStateList(this, prefs)
        val palette = MainTabsThemeBridge.resolve(this)
        binding.bottomNavigation.itemIconTintList = colorStateList
        binding.bottomNavigation.itemTextColor = colorStateList
        binding.bottomNavigation.setItemActiveIndicatorEnabled(true)
        binding.bottomNavigation.setItemActiveIndicatorColor(
            ColorStateList.valueOf(
                MainTabsThemeBridge.withAlpha(
                    palette.accent,
                    when {
                        palette.isPureBlack -> 0.14f
                        palette.isDark -> 0.16f
                        else -> 0.12f
                    }
                )
            )
        )
        binding.bottomNavigation.setItemActiveIndicatorWidth(dp(72f).toInt())
        binding.bottomNavigation.setItemActiveIndicatorHeight(dp(36f).toInt())
        binding.bottomNavigation.setItemActiveIndicatorMarginHorizontal(dp(4f).toInt())
        animateBottomNavigationItems(
            previous = navigationState.current.bottomItemDestination,
            current = navigationState.current.bottomItemDestination,
            animate = false
        )
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

    private fun animateBottomNavigationItems(
        previous: MainDestination,
        current: MainDestination,
        animate: Boolean
    ) {
        val ids = intArrayOf(
            R.id.navigationHabits,
            R.id.navigationReports,
            R.id.navigationSettings
        )
        ids.forEach { id ->
            val itemView = bottomNavigationItemView(id) ?: return@forEach
            val destination = destinationForBottomItem(id)
            val active = destination == current.bottomItemDestination
            val shouldAnimate = animate &&
                (destination == previous.bottomItemDestination || active) &&
                areSystemAnimationsEnabled()
            itemView.animate().setListener(null).cancel()
            val targetScale = if (active) 1.06f else 1f
            val targetTranslationY = if (active) -dp(1.5f) else 0f
            val targetAlpha = if (active) 1f else 0.92f
            if (shouldAnimate) {
                itemView.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .translationY(targetTranslationY)
                    .alpha(targetAlpha)
                    .setDuration(BOTTOM_NAV_ANIMATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                itemView.scaleX = targetScale
                itemView.scaleY = targetScale
                itemView.translationY = targetTranslationY
                itemView.alpha = targetAlpha
            }
        }
    }

    private fun bottomNavigationItemView(itemId: Int): View? {
        val menuView = binding.bottomNavigation.getChildAt(0) as? ViewGroup ?: return null
        val index = (0 until binding.bottomNavigation.menu.size()).firstOrNull { index ->
            binding.bottomNavigation.menu.getItem(index).itemId == itemId
        } ?: return null
        return if (index < menuView.childCount) menuView.getChildAt(index) else null
    }

    private fun destinationForBottomItem(itemId: Int): MainDestination {
        return when (itemId) {
            R.id.navigationReports -> MainDestination.STATISTICS
            R.id.navigationSettings -> MainDestination.SETTINGS
            else -> MainDestination.HABITS
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    companion object {
        private const val EXTRA_DESTINATION = "main.destination"
        private const val STATE_CURRENT = "main.current"
        private const val STATE_HISTORY = "main.history"
        private const val TAG_HABITS = "main.habits"
        private const val TAG_REPORTS = "main.reports"
        private const val TAG_SETTINGS = "main.settings"

        private const val EXTRA_RESTORE_SUCCESS = "main.restore_success"
        private const val TAB_FADE_OUT_MS = 100L
        private const val TAB_FADE_IN_MS = 180L
        private const val BOTTOM_NAV_ANIMATION_MS = 190L
        private const val TAB_SLIDE_DP = 12f
        private const val TAB_STATISTICS_SLIDE_DP = 4f

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
}
