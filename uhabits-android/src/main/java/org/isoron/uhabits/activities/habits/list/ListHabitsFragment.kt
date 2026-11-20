package org.isoron.uhabits.activities.habits.list

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.views.HabitCardListAdapter
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.activities.main.SettingsAction
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.inject.HabitsActivityComponent
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.sync.SyncCoordinator
import org.isoron.uhabits.sync.SyncRunResult
import org.isoron.uhabits.utils.dismissCurrentDialog
import org.isoron.uhabits.utils.restartWithFade

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
    private var archiveSearchQuery = ""
    private var initialized = false
    private var startupPending = false
    private var pendingIntent: Intent? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (!initialized) initialize()
        if (savedInstanceState != null) {
            rootView.dataOffset = savedInstanceState.getInt(STATE_DATA_OFFSET, rootView.dataOffset)
            if (savedInstanceState.getBoolean(STATE_SEARCH_ACTIVE)) {
                val restoredQuery = savedInstanceState.getString(STATE_SEARCH_QUERY).orEmpty()
                applySearchQuery(restoredQuery)
                menuController.openSearch()
            }
        }
        return rootView
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (initialized) {
            outState.putInt(STATE_DATA_OFFSET, rootView.dataOffset)
            outState.putBoolean(STATE_SEARCH_ACTIVE, menuController.isSearchActive)
            outState.putString(STATE_SEARCH_QUERY, currentSearchQuery())
        }
    }

    private fun initialize() {
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("fragment.initialize.begin")
        val activity = requireActivity() as AppCompatActivity
        appComponent = (requireContext().applicationContext as HabitsApplication).component
        component = HabitsActivityComponent::class.create(
            parent = appComponent,
            activityContext = activity
        )
        val effectiveReads = appComponent.effectiveHabitReads
        if (effectiveReads != null) {
            component.listHabitsBehavior.effectiveSnapshotProvider = { habits, from, to ->
                effectiveReads.snapshots(habits, from, to)
            }
        }
        component.themeSwitcher.apply()
        prefs = appComponent.preferences
        prefs.addListener(this)
        rootView = component.listHabitsRootView
        screen = component.listHabitsScreen
        adapter = component.habitCardListAdapter
        taskRunner = appComponent.taskRunner
        menuController = component.listHabitsMenu
        rootView.bindSelectionMenu(component.listHabitsSelectionMenu)
        rootView.onToolbarAction = { id -> onToolbarActionSelected(id) }
        rootView.onToolbarBack = { handleBack() }
        rootView.onToolbarQuery = { query ->
            applySearchQuery(query)
            refreshToolbarState(overrideQuery = query)
        }
        menuController.onSearchQueryChanged = { query ->
            if (displayMode == ListHabitsDisplayMode.ARCHIVE) {
                applyArchiveFilter(query)
            }
            refreshToolbarState()
        }
        menuController.onMenuStateChanged = {
            refreshToolbarState()
        }
        component.listHabitsSelectionMenu.onSelectionModeChanged = { selecting ->
            (activity as? MainNavigationHost)?.setHabitCreationAvailable(
                !selecting && displayMode == ListHabitsDisplayMode.NORMAL
            )
        }

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
        activity.onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!this@ListHabitsFragment.isVisible || !handleBack()) {
                        isEnabled = false
                        try {
                            activity.onBackPressedDispatcher.onBackPressed()
                        } finally {
                            isEnabled = true
                        }
                    }
                }
            }
        )
        org.isoron.uhabits.utils.HabitsStartupTrace.mark("fragment.initialize.end")
    }

    fun handleBack(): Boolean {
        if (adapter.selected.isNotEmpty()) {
            component.listHabitsSelectionMenu.cancelSelection()
            return true
        }
        if (menuController.isSearchActive) {
            menuController.closeSearch()
            return true
        }
        if (displayMode == ListHabitsDisplayMode.ARCHIVE) {
            return (activity as? MainNavigationHost)?.navigateBack() ?: false
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        appComponent.syncCoordinator.addListener(this)
        refreshToolbarState()
    }

    override fun onStop() {
        appComponent.syncCoordinator.removeListener(this)
        super.onStop()
    }

    override fun onSyncStateChanged(isSyncing: Boolean, lastResult: SyncRunResult?) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            refreshToolbarState()
            if (!isSyncing && manualSyncInitiated) {
                manualSyncInitiated = false
                if (lastResult != null) {
                    showManualSyncResultToast(lastResult)
                }
            }
        }
    }

    private fun showManualSyncResultToast(result: SyncRunResult) {
        if (!isAdded) return
        when (result) {
            is SyncRunResult.Success -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sync_result_success, result.pushed, result.pulled),
                    Toast.LENGTH_SHORT
                ).show()
            }
            is SyncRunResult.Failure -> {
                Toast.makeText(
                    requireContext(),
                    result.userMessage,
                    Toast.LENGTH_LONG
                ).show()
            }
            is SyncRunResult.Skipped -> {
                val msg = when (result.reason) {
                    "already_syncing" -> getString(R.string.sync_error_already_syncing)
                    "review_required" -> getString(R.string.sync_status_review_required)
                    "not_configured" -> getString(R.string.sync_error_config_missing)
                    "sync_disabled_in_session" -> getString(R.string.sync_status_disabled)
                    else -> getString(R.string.sync_status_error, result.reason)
                }
                Toast.makeText(
                    requireContext(),
                    msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshToolbarState()
        adapter.refresh()
        screen.onAttached()
        rootView.refreshDisplayState()
        rootView.postInvalidate()
        val selecting = component.listHabitsSelectionMenu.selectedCount > 0
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(
            !selecting && displayMode == ListHabitsDisplayMode.NORMAL
        )
    }

    override fun onPause() {
        (activity as? MainActivity)?.setHabitsSearchActive(false)
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
        if (displayMode == ListHabitsDisplayMode.NORMAL) {
            menuController.behavior.onPreferencesChanged()
        }
        refreshToolbarState()
        rootView.refreshDisplayState()
    }

    override fun onCardCornersChanged() {
        refreshToolbarState()
        rootView.refreshDisplayState()
    }

    override fun onHabitListAppearanceChanged() {
        if (!prefs.isDayTiersEnabled && adapter.primaryOrder == org.isoron.uhabits.core.models.HabitList.Order.BY_DAY_TIER) {
            adapter.primaryOrder = org.isoron.uhabits.core.models.HabitList.Order.BY_POSITION
        }
        if (!prefs.isHabitSpheresEnabled && adapter.primaryOrder == org.isoron.uhabits.core.models.HabitList.Order.BY_SPHERE) {
            adapter.primaryOrder = org.isoron.uhabits.core.models.HabitList.Order.BY_POSITION
        }
        adapter.applyDayTierSortOrder()
        refreshToolbarState()
        rootView.refreshDisplayState()
    }

    private fun currentSearchQuery(): String =
        if (displayMode == ListHabitsDisplayMode.ARCHIVE) archiveSearchQuery else menuController.behavior.searchQuery

    private fun applySearchQuery(query: String) {
        if (displayMode == ListHabitsDisplayMode.ARCHIVE) {
            archiveSearchQuery = query
            applyArchiveFilter(query)
        } else {
            menuController.behavior.onSearchQueryChanged(query)
        }
    }

    private fun applyArchiveFilter(query: String = "") {
        archiveSearchQuery = query
        adapter.setFilter(
            HabitMatcher(
                isArchivedAllowed = true,
                isArchivedRequired = true,
                searchQuery = query
            )
        )
        adapter.refresh()
    }

    private fun refreshToolbarState(overrideQuery: String? = null) {
        if (!initialized) return
        val archive = displayMode == ListHabitsDisplayMode.ARCHIVE
        val syncCoordinator = appComponent.syncCoordinator
        (activity as? MainActivity)?.setHabitsSearchActive(menuController.isSearchActive)
        rootView.updateComposeToolbar(
            menuController.buildToolbarState(
                archive = archive,
                syncReady = syncCoordinator.isSyncReady(),
                isSyncing = syncCoordinator.isSyncing,
                query = overrideQuery ?: currentSearchQuery()
            )
        )
    }

    private fun onToolbarActionSelected(itemId: Int): Boolean {
        if (itemId == R.id.actionAddHabit) {
            val intent = org.isoron.uhabits.intents.IntentFactory().startEditActivity(
                requireActivity(),
                org.isoron.uhabits.core.models.HabitType.YES_NO.value,
                displayMode == ListHabitsDisplayMode.ARCHIVE
            )
            startActivity(intent)
            return true
        }
        if (itemId == R.id.actionSync) {
            if (prefs.isSyncReviewRequired) {
                showSyncReviewDialog()
            } else {
                triggerManualSync(allowAfterReview = false)
            }
            return true
        }
        if (itemId == R.id.actionSkipDay) {
            SkipDayDialogController(
                fragment = this,
                habitList = appComponent.habitList,
                commandRunner = appComponent.commandRunner,
                organizationFacade = appComponent.organizationFacade
            ).show()
            return true
        }

        val handled = menuController.onActionSelected(itemId)
        refreshToolbarState()
        return handled
    }

    private var manualSyncInitiated = false

    private fun showSyncReviewDialog() {
        CustomDialogs.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.sync_confirm_after_restore_title),
            message = prefs.syncReviewReason.ifBlank {
                getString(R.string.sync_confirm_after_restore_message)
            },
            isDestructive = false,
            positiveText = getString(R.string.sync_now)
        ) {
            appComponent.syncCoordinator.confirmSyncReview()
            triggerManualSync(allowAfterReview = true)
        }
    }

    private fun triggerManualSync(allowAfterReview: Boolean = false) {
        manualSyncInitiated = true
        refreshToolbarState()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = appComponent.syncCoordinator.runSync(
                manual = true,
                allowAfterReview = allowAfterReview
            )
            if (manualSyncInitiated) {
                manualSyncInitiated = false
                refreshToolbarState()
                showManualSyncResultToast(result)
            }
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
        val activity = requireActivity()
        if (displayMode == ListHabitsDisplayMode.ARCHIVE) {
            applyArchiveFilter(if (menuController.isSearchActive) archiveSearchQuery else "")
        } else {
            archiveSearchQuery = ""
            menuController.behavior.onPreferencesChanged()
            adapter.refresh()
        }
        refreshToolbarState()
        rootView.refreshDisplayState()
        val selecting = component.listHabitsSelectionMenu.selectedCount > 0
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(
            !selecting && displayMode == ListHabitsDisplayMode.NORMAL
        )
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
        val acceptedByWidget = org.isoron.uhabits.intents.WidgetIntentIdentity.accepts(intent, appComponent.session, habit)
        val acceptedByNotification = org.isoron.uhabits.intents.NotificationIntentIdentity.accepts(intent, appComponent.session, habit)
        if (!acceptedByWidget && !acceptedByNotification) return
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

    companion object {
        private const val STATE_DATA_OFFSET = "habits_list_data_offset"
        private const val STATE_SEARCH_ACTIVE = "habits_search_active"
        private const val STATE_SEARCH_QUERY = "habits_search_query"
    }
}
