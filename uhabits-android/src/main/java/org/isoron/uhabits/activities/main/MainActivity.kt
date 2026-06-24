package org.isoron.uhabits.activities.main

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
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
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.ListHabitsDisplayMode
import org.isoron.uhabits.activities.habits.list.ListHabitsFragment
import org.isoron.uhabits.activities.habits.today.TodayFragment
import org.isoron.uhabits.activities.reports.ReportsFragment
import org.isoron.uhabits.activities.settings.SettingsSectionFragment
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.ui.ThemeSwitcher.Companion.THEME_DARK
import org.isoron.uhabits.database.AutoBackup
import org.isoron.uhabits.databinding.ActivityMainBinding
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
    private var permissionAlreadyRequested = false
    private var updatingBottomNavigation = false

    private val permissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) scheduleReminders()
        else Log.i("MainActivity", "POST_NOTIFICATIONS denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidThemeSwitcher(this, prefs).apply()
        pureBlack = prefs.isPureBlackEnabled
        currentTheme = prefs.theme
        prefs.addListener(this)
        Thread.setDefaultUncaughtExceptionHandler(BaseExceptionHandler(this))

        binding = ActivityMainBinding.inflate(layoutInflater)
        binding.root.applyRootViewInsets()
        setContentView(binding.root)
        if (intent.getBooleanExtra(EXTRA_RESTORE_SUCCESS, false)) {
            Toast.makeText(this, R.string.restore_backup_success, Toast.LENGTH_LONG).show()
            intent.removeExtra(EXTRA_RESTORE_SUCCESS)
        }

        val todayVisible = prefs.isTodayTabVisible
        var startDest = savedInstanceState?.getString(STATE_CURRENT)?.let {
            runCatching { MainDestination.valueOf(it) }.getOrNull()
        } ?: destinationFromIntent(intent)

        if (startDest == MainDestination.TODAY && !todayVisible) {
            startDest = MainDestination.HABITS
        }

        val restoredHistory = (savedInstanceState?.getStringArrayList(STATE_HISTORY)
            ?.map { runCatching { MainDestination.valueOf(it) }.getOrNull() }
            ?.filterNotNull() ?: emptyList())
            .filter { it != MainDestination.TODAY || todayVisible }

        navigationState = MainNavigationState(
            current = startDest,
            history = restoredHistory
        )

        setupBottomNavigation()
        binding.bottomNavigation.menu.findItem(R.id.navigationToday)?.isVisible = todayVisible
        binding.createHabitFab.setOnClickListener {
            HabitTypeDialog().show(supportFragmentManager, "habitType")
        }
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
                R.id.navigationToday -> MainDestination.TODAY
                R.id.navigationHabits -> MainDestination.HABITS
                R.id.navigationReports -> MainDestination.REPORTS
                R.id.navigationSettings -> MainDestination.SETTINGS
                else -> return@setOnItemSelectedListener false
            }
            navigate(destination)
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
        if (supportFragmentManager.findFragmentByTag(TAG_TODAY) == null) {
            transaction.add(R.id.mainContent, TodayFragment(), TAG_TODAY)
        }
        if (supportFragmentManager.findFragmentByTag(TAG_HABITS) == null) {
            val habits = ListHabitsFragment()
            transaction.add(R.id.mainContent, habits, TAG_HABITS).hide(habits)
        }
        transaction.commitNowAllowingStateLoss()
    }

    private fun fragmentFor(destination: MainDestination): Fragment {
        val tag = tagFor(destination)
        supportFragmentManager.findFragmentByTag(tag)?.let { return it }
        val fragment = when (destination) {
            MainDestination.TODAY -> TodayFragment()
            MainDestination.HABITS, MainDestination.ARCHIVE -> ListHabitsFragment()
            MainDestination.REPORTS -> ReportsFragment()
            MainDestination.SETTINGS -> SettingsSectionFragment()
        }
        supportFragmentManager.beginTransaction()
            .add(R.id.mainContent, fragment, tag)
            .hide(fragment)
            .commitNowAllowingStateLoss()
        return fragment
    }

    private fun showDestination(destination: MainDestination) {
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
        updateBottomSelection(destination)
        setHabitCreationAvailable(
            destination == MainDestination.HABITS
        )
    }

    private fun updateBottomSelection(destination: MainDestination) {
        val itemId = when (destination.bottomItemDestination) {
            MainDestination.TODAY -> R.id.navigationToday
            MainDestination.HABITS -> R.id.navigationHabits
            MainDestination.REPORTS -> R.id.navigationReports
            MainDestination.SETTINGS -> R.id.navigationSettings
            MainDestination.ARCHIVE -> R.id.navigationHabits
        }
        updatingBottomNavigation = true
        binding.bottomNavigation.selectedItemId = itemId
        updatingBottomNavigation = false
    }

    override fun navigate(destination: MainDestination) {
        if (!navigationState.navigate(destination)) return
        showDestination(destination)
    }

    override fun navigateBack(): Boolean {
        var destination = navigationState.navigateBack()
        while (destination == MainDestination.TODAY && !prefs.isTodayTabVisible) {
            destination = navigationState.navigateBack()
        }
        if (destination == null) return false
        showDestination(destination)
        return true
    }

    override fun setHabitCreationAvailable(available: Boolean) {
        if (available) binding.createHabitFab.show() else binding.createHabitFab.hide()
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
            } catch (e: Exception) {
                Log.e("MainActivity", "Startup maintenance failed", e)
            }
        }
        if (prefs.isSyncEnabled) {
            try {
                appComponent.taskRunner.execute(object : Task {
                    override suspend fun doInBackground() {
                        try {
                            appComponent.syncCoordinator.runSync(manual = false)
                        } catch (t: Throwable) {
                            Log.e("MainActivity", "Background sync failed", t)
                        }
                    }
                })
            } catch (t: Throwable) {
                Log.e("MainActivity", "Background sync setup failed", t)
            }
        }
        if (prefs.theme != currentTheme || prefs.isPureBlackEnabled != pureBlack) {
            restartWithFade(MainActivity::class.java)
        }
    }

    override fun onPause() {
        appComponent.midnightTimer.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        prefs.removeListener(this)
        super.onDestroy()
    }

    override fun onQuestionMarksChanged() = Unit

    override fun onNavigationPreferencesChanged() {
        runOnUiThread {
            val visible = prefs.isTodayTabVisible
            binding.bottomNavigation.menu.findItem(R.id.navigationToday)?.isVisible = visible
            if (!visible && navigationState.current == MainDestination.TODAY) {
                navigate(MainDestination.HABITS)
            }
        }
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
        MainDestination.TODAY -> TAG_TODAY
        MainDestination.HABITS, MainDestination.ARCHIVE -> TAG_HABITS
        MainDestination.REPORTS -> TAG_REPORTS
        MainDestination.SETTINGS -> TAG_SETTINGS
    }

    private fun destinationFromIntent(intent: Intent): MainDestination {
        val destName = intent.getStringExtra(EXTRA_DESTINATION)
            ?: prefs.startDestinationName
        var dest = runCatching { MainDestination.valueOf(destName) }.getOrDefault(MainDestination.TODAY)
        if (dest == MainDestination.TODAY && !prefs.isTodayTabVisible) {
            dest = MainDestination.HABITS
        }
        return dest
    }

    companion object {
        private const val EXTRA_DESTINATION = "main.destination"
        private const val STATE_CURRENT = "main.current"
        private const val STATE_HISTORY = "main.history"
        private const val TAG_TODAY = "main.today"
        private const val TAG_HABITS = "main.habits"
        private const val TAG_REPORTS = "main.reports"
        private const val TAG_SETTINGS = "main.settings"

        private const val EXTRA_RESTORE_SUCCESS = "main.restore_success"

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
