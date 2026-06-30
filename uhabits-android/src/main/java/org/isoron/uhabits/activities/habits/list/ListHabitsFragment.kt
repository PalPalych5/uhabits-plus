package org.isoron.uhabits.activities.habits.list

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.activities.main.SettingsAction
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.inject.HabitsActivityComponent
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.utils.dismissCurrentDialog
import org.isoron.uhabits.utils.restartWithFade
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.isoron.uhabits.sync.SyncCoordinator
import org.isoron.uhabits.sync.SyncRunResult

enum class ListHabitsDisplayMode { NORMAL, ARCHIVE }

class ListHabitsFragment : Fragment(), Preferences.Listener, SyncCoordinator.Listener {
    lateinit var appComponent: HabitsApplicationComponent
    lateinit var component: HabitsActivityComponent
    lateinit var taskRunner: TaskRunner
    lateinit var adapter: HabitCardListAdapter
    lateinit var rootView: ListHabitsRootView
    lateinit var screen: ListHabitsScreen
    lateinit var prefs: Preferences
    private lateinit var menuController: ListHabitsMenu
    private var displayMode = ListHabitsDisplayMode.NORMAL
    private var initialized = false
    private var startupPending = false
    private var pendingIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (!initialized) initialize()
        return rootView
    }

    private fun initialize() {
        val activity = requireActivity() as AppCompatActivity
        appComponent = (requireContext().applicationContext as HabitsApplication).component
        component = HabitsActivityComponent::class.create(
            parent = appComponent,
            activityContext = activity
        )
        component.themeSwitcher.apply()
        prefs = appComponent.preferences
        prefs.addListener(this)
        rootView = component.listHabitsRootView
        screen = component.listHabitsScreen
        adapter = component.habitCardListAdapter
        taskRunner = appComponent.taskRunner
        menuController = component.listHabitsMenu
        component.listHabitsSelectionMenu.onSelectionModeChanged = { selecting ->
            (activity as? MainNavigationHost)?.setHabitCreationAvailable(
                !selecting && displayMode == ListHabitsDisplayMode.NORMAL
            )
        }
        Thread.setDefaultUncaughtExceptionHandler(BaseExceptionHandler(activity))
        initialized = true
        applyDisplayMode()
        if (startupPending) {
            startupPending = false
            component.listHabitsBehavior.onStartup()
        }
        pendingIntent?.let {
            pendingIntent = null
            handleIntent(it)
        }
    }

    override fun onStart() {
        super.onStart()
        appComponent.syncCoordinator.addListener(this)
        activity?.invalidateOptionsMenu()
    }

    override fun onStop() {
        appComponent.syncCoordinator.removeListener(this)
        super.onStop()
    }

    override fun onSyncStateChanged(isSyncing: Boolean, lastResult: SyncRunResult?) {
        activity?.runOnUiThread {
            activity?.invalidateOptionsMenu()
            if (!isSyncing && manualSyncInitiated) {
                manualSyncInitiated = false
                if (lastResult != null) {
                    when (lastResult) {
                        is SyncRunResult.Success -> {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.sync_result_success, lastResult.pushed, lastResult.pulled),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is SyncRunResult.Failure -> {
                            Toast.makeText(
                                requireContext(),
                                lastResult.userMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is SyncRunResult.Skipped -> {
                            if (lastResult.reason != "already_syncing") {
                                Toast.makeText(
                                    requireContext(),
                                    "Sync skipped: ${lastResult.reason}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activateToolbar()
        adapter.refresh()
        screen.onAttached()
        rootView.postInvalidate()
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(
            displayMode == ListHabitsDisplayMode.NORMAL
        )
    }

    override fun onPause() {
        screen.onDetached()
        adapter.cancelRefresh()
        dismissCurrentDialog()
        super.onPause()
    }

    override fun onDestroy() {
        if (initialized) prefs.removeListener(this)
        super.onDestroy()
    }

    override fun onQuestionMarksChanged() {
        requireActivity().invalidateOptionsMenu()
        if (displayMode == ListHabitsDisplayMode.NORMAL) {
            menuController.behavior.onPreferencesChanged()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menuController.onCreate(inflater, menu)
        if (displayMode == ListHabitsDisplayMode.ARCHIVE) {
            adapter.setFilter(
                HabitMatcher(isArchivedAllowed = true, isArchivedRequired = true)
            )
        }
        updateMenuVisibility(menu)
        rootView.applyToolbarIconTint(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val syncItem = menu.findItem(R.id.actionSync)
        val syncCoordinator = appComponent.syncCoordinator
        val syncEnabled = syncCoordinator.isSyncReady()
        if (syncItem != null) {
            syncItem.isVisible = syncEnabled
            if (syncCoordinator.isSyncing) {
                syncItem.setActionView(R.layout.menu_item_sync_progress)
                syncItem.isEnabled = false
            } else {
                syncItem.setActionView(null)
                syncItem.isEnabled = true
            }
        }
        updateMenuVisibility(menu)
        rootView.applyToolbarIconTint(menu)
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.actionAddHabit) {
            HabitTypeDialog().show(parentFragmentManager, "habitType")
            return true
        }
        if (item.itemId == R.id.actionSync) {
            triggerManualSync()
            return true
        }
        if (item.itemId == android.R.id.home && displayMode == ListHabitsDisplayMode.ARCHIVE) {
            return (activity as MainNavigationHost).navigateBack()
        }
        requireActivity().invalidateOptionsMenu()
        return menuController.onItemSelected(item) || super.onOptionsItemSelected(item)
    }

    private var manualSyncInitiated = false

    private fun triggerManualSync() {
        manualSyncInitiated = true
        activity?.invalidateOptionsMenu()
        viewLifecycleOwner.lifecycleScope.launch {
            appComponent.syncCoordinator.runSync(manual = true)
        }
    }

    fun setDisplayMode(mode: ListHabitsDisplayMode) {
        displayMode = mode
        if (initialized) applyDisplayMode()
    }

    fun refresh(reloadFromDatabase: Boolean = false) {
        if (initialized && isAdded) {
            if (reloadFromDatabase) {
                (appComponent.habitList as? SQLiteHabitList)?.reloadAndNotify()
                applyDisplayMode()
            } else {
                adapter.refresh()
            }
            rootView.postInvalidate()
        }
    }

    private fun applyDisplayMode() {
        val activity = requireActivity() as AppCompatActivity
        if (displayMode == ListHabitsDisplayMode.ARCHIVE) {
            rootView.setScreenTitle(getString(R.string.archive_title))
            adapter.setFilter(
                HabitMatcher(isArchivedAllowed = true, isArchivedRequired = true)
            )
        } else {
            rootView.setScreenTitle(getString(R.string.habits_title))
            menuController.behavior.onPreferencesChanged()
        }
        adapter.refresh()
        activateToolbar()
        activity.invalidateOptionsMenu()
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(
            displayMode == ListHabitsDisplayMode.NORMAL
        )
    }

    private fun activateToolbar() {
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(rootView.tbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(
            displayMode == ListHabitsDisplayMode.ARCHIVE
        )
        rootView.applyToolbarIconTint()
    }

    private fun updateMenuVisibility(menu: Menu) {
        menu.findItem(R.id.action_filter)?.isVisible = displayMode == ListHabitsDisplayMode.NORMAL
    }

    fun onHostStartup() {
        if (initialized) component.listHabitsBehavior.onStartup()
        else startupPending = true
    }

    fun handleSettingsAction(action: SettingsAction) {
        when (action) {
            SettingsAction.IMPORT_DATA -> screen.handleSettingsAction(RESULT_IMPORT_DATA)
            SettingsAction.EXPORT_CSV -> screen.handleSettingsAction(RESULT_EXPORT_CSV)
            SettingsAction.EXPORT_DATABASE -> screen.handleSettingsAction(RESULT_EXPORT_DB)
            SettingsAction.REPAIR_DATABASE -> screen.handleSettingsAction(RESULT_REPAIR_DB)
            SettingsAction.BUG_REPORT -> screen.handleSettingsAction(RESULT_BUG_REPORT)
            else -> Unit
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        screen.onResult(requestCode, resultCode, data)
    }

    fun handleIntent(intent: Intent) {
        if (intent.action != ListHabitsActivity.ACTION_EDIT) return
        if (!initialized) {
            pendingIntent = Intent(intent)
            return
        }
        val habitId = intent.extras?.getLong("habit") ?: return
        val timestampMillis = intent.extras?.getLong("timestamp") ?: return
        val habit = appComponent.habitList.getById(habitId) ?: return
        component.listHabitsBehavior.onEdit(
            habit,
            LocalDate.fromUnixTime(timestampMillis),
            0f,
            0f
        )
    }

    fun restartHost() {
        requireActivity().restartWithFade(MainActivity::class.java)
    }
}
