/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.habits.show

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import com.android.datetimepicker.date.DatePickerDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import org.isoron.uhabits.AndroidDirFinder
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.HabitsDirFinder
import org.isoron.uhabits.activities.common.dialogs.CheckmarkDialog
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.dialogs.ConfirmDeleteDialog
import org.isoron.uhabits.activities.common.dialogs.HistoryEditorDialog
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.PomodoroCompletionNotifier
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitMenuPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.views.OnDateClickedListener
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.dismissCurrentDialog
import org.isoron.uhabits.utils.showMessage
import org.isoron.uhabits.utils.showSendFileScreen
import org.isoron.uhabits.widgets.WidgetUpdater

class ShowHabitActivity : AppCompatActivity(), CommandRunner.Listener, org.isoron.uhabits.core.models.ModelObservable.Listener {

    private lateinit var commandRunner: CommandRunner
    private lateinit var menu: ShowHabitMenu
    private lateinit var view: ShowHabitView
    private lateinit var habit: Habit
    private lateinit var habitList: org.isoron.uhabits.core.models.HabitList
    private lateinit var preferences: Preferences
    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private lateinit var widgetUpdater: WidgetUpdater
    private var focusCredit: org.isoron.uhabits.focus.FocusCreditController? = null
    private var effectiveReads: org.isoron.uhabits.focus.EffectiveHabitReadController? = null
    private val effectiveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val effectiveRefresh = Runnable { if (!isFinishing) screen.refresh() }
    private val effectiveObserver: () -> Unit = { effectiveHandler.removeCallbacks(effectiveRefresh); effectiveHandler.post(effectiveRefresh) }
    private val focusObserver: () -> Unit = { screen.refresh() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var readsActive = false
    private var refreshJob: Job? = null
    private var popupJob: Job? = null
    private val refreshGeneration = HabitDetailReadGeneration()
    private val popupGeneration = HabitDetailReadGeneration()
    private lateinit var presenter: ShowHabitPresenter
    private val screen = Screen()
    private var pendingTimerStart: (() -> Unit)? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val action = pendingTimerStart
        pendingTimerStart = null
        if (isGranted) {
            action?.invoke()
        } else {
            action?.invoke()
            showMessage(getString(R.string.timer_notification_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appComponent = (applicationContext as HabitsApplication).component
        habitList = appComponent.habitList
        val selectedHabit = intent.data?.let { uri ->
            runCatching { habitList.getById(ContentUris.parseId(uri)) }.getOrNull()
        }
        val hasNotificationIdentity = intent.hasExtra(org.isoron.uhabits.intents.NotificationIntentIdentity.DATASET) ||
            intent.hasExtra(org.isoron.uhabits.intents.NotificationIntentIdentity.HABIT_UUID) ||
            intent.data?.getQueryParameter(org.isoron.uhabits.intents.NotificationIntentIdentity.DATASET) != null
        val hasWidgetIdentity = intent.hasExtra(org.isoron.uhabits.intents.WidgetIntentIdentity.DATASET) ||
            intent.hasExtra(org.isoron.uhabits.intents.WidgetIntentIdentity.HABIT_UUID) ||
            intent.data?.getQueryParameter(org.isoron.uhabits.intents.WidgetIntentIdentity.DATASET) != null

        if (selectedHabit == null ||
            (hasNotificationIdentity && !org.isoron.uhabits.intents.NotificationIntentIdentity.accepts(intent, appComponent.session, selectedHabit)) ||
            (hasWidgetIdentity && !org.isoron.uhabits.intents.WidgetIntentIdentity.accepts(intent, appComponent.session, selectedHabit))) {
            finish()
            return
        }
        habit = selectedHabit
        preferences = appComponent.preferences
        commandRunner = appComponent.commandRunner
        widgetUpdater = appComponent.widgetUpdater
        focusCredit = appComponent.focusCreditController
        effectiveReads = appComponent.effectiveHabitReads

        themeSwitcher = AndroidThemeSwitcher(this, preferences)
        themeSwitcher.apply()

        presenter = ShowHabitPresenter(
            commandRunner = commandRunner,
            habit = habit,
            habitList = habitList,
            preferences = preferences,
            screen = screen
        )

        view = ShowHabitView(this)

        val menuPresenter = ShowHabitMenuPresenter(
            commandRunner = commandRunner,
            habit = habit,
            habitList = habitList,
            screen = screen,
            system = HabitsDirFinder(AndroidDirFinder(this)),
            taskRunner = appComponent.taskRunner
        )
        if (effectiveReads != null) {
            menuPresenter.effectiveSnapshotProvider = { habits, from, to ->
                effectiveReads?.snapshots(habits, from, to).orEmpty()
            }
        }

        menu = ShowHabitMenu(
            activity = this,
            presenter = menuPresenter,
            preferences = preferences
        )

        scope.launch {
            val manager = withContext(Dispatchers.IO) {
                appComponent.timerSessionManager
            }
            if (!isFinishing && !isDestroyed) {
                view.initTimer(
                    habit,
                    manager,
                    appComponent.pomodoroCompletionNotifier,
                    ::ensureTimerNotificationPermission,
                    ::openPomodoroAlertSettings
                )
            }
        }
        view.setListener(presenter)
        view.applyRootViewInsets()
        setContentView(view)
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        return menu.onCreateOptionsMenu(m)
    }

    fun editHistory() = presenter.historyCardPresenter.onClickEditButton()

    val hasProgressDetails: Boolean get() = view.hasProgressDetails

    fun showProgressDetails() = view.showProgressDetails()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return menu.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        if (!::presenter.isInitialized) return
        readsActive = true
        commandRunner.addListener(this)
        habit.observable.addListener(this)
        habitList.observable.addListener(this)
        focusCredit?.addObserver(focusObserver)
        effectiveReads?.revision?.addListener(effectiveObserver)
        effectiveReads?.revision?.addRollbackListener(effectiveObserver)
        supportFragmentManager.findFragmentByTag("historyEditor")?.let {
            (it as HistoryEditorDialog).setOnDateClickedListener(presenter.historyCardPresenter)
        }
        screen.refresh()
        view.refreshTimerAlertHealth()
    }

    override fun onPause() {
        readsActive = false
        refreshGeneration.invalidate()
        popupGeneration.invalidate()
        refreshJob?.cancel()
        popupJob?.cancel()
        if (!::presenter.isInitialized) {
            super.onPause()
            return
        }
        dismissCurrentDialog()
        commandRunner.removeListener(this)
        habit.observable.removeListener(this)
        habitList.observable.removeListener(this)
        focusCredit?.removeObserver(focusObserver)
        effectiveReads?.revision?.removeListener(effectiveObserver)
        effectiveReads?.revision?.removeRollbackListener(effectiveObserver)
        effectiveHandler.removeCallbacks(effectiveRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        effectiveHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCommandFinished(command: Command) {
        screen.refresh()
    }

    override fun onModelChange() {
        screen.refresh()
    }

    private fun ensureTimerNotificationPermission(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                onReady()
            } else {
                pendingTimerStart = onReady
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            onReady()
        }
    }

    private fun openPomodoroAlertSettings(issue: PomodoroAlertIssue) {
        val intent = when (issue) {
            PomodoroAlertIssue.NOTIFICATIONS_DISABLED -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            PomodoroAlertIssue.CHANNEL_DISABLED,
            PomodoroAlertIssue.CHANNEL_SILENT -> {
                (applicationContext as HabitsApplication).component
                    .pomodoroCompletionNotifier.ensureChannel()
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, PomodoroCompletionNotifier.CHANNEL_ID)
                }
            }
            PomodoroAlertIssue.EXACT_ALARMS_DISABLED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                } else {
                    return
                }
            }
        }
        runCatching { startActivity(intent) }
    }

    inner class Screen : ShowHabitMenuPresenter.Screen, ShowHabitPresenter.Screen {
        override fun updateWidgets() {
            widgetUpdater.updateWidgets()
        }

        override fun refresh() {
            // Model observers may be called by a background command. Capture the
            // generation and touch the View only after dispatching to Main.
            scope.launch {
                if (!readsActive || isFinishing || isDestroyed) return@launch
                val generation = refreshGeneration.begin()
                refreshJob?.cancel()
                refreshJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                val revision = effectiveReads?.revision?.value
                val theme = themeSwitcher.currentTheme
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val snapshot = effectiveReads?.snapshot(habit)
                        ShowHabitPresenter.buildState(
                            habit = habit,
                            preferences = preferences,
                            theme = theme,
                            snapshot = snapshot,
                        )
                    }
                }
                if (!refreshGeneration.accepts(generation) || isFinishing || isDestroyed ||
                    revision != effectiveReads?.revision?.value) return@launch
                if (loaded.isFailure) {
                    view.setEffectiveReadUnavailable()
                    return@launch
                }
                val state = loaded.getOrThrow()
                view.setState(state)
                val controller = focusCredit
                val eligible = controller != null && runCatching {
                    org.isoron.uhabits.core.rules.HabitTimeValueConverter.validateTargetHabit(habit)
                }.isSuccess
                if (eligible) {
                    if (state.goalAchievementUnavailable) view.setFocusProgress(null, true)
                    else {
                        val progress = withContext(Dispatchers.IO) {
                            runCatching {
                                FocusProgressPresentation.from(state.goalAchievement?.progress ?: controller!!.goalProgress(habit))
                            }
                        }
                        if (!refreshGeneration.accepts(generation) || isFinishing || isDestroyed ||
                            revision != effectiveReads?.revision?.value) return@launch
                        view.setFocusProgress(progress.getOrNull(), progress.isFailure)
                    }
                } else view.setFocusProgress(null, state.quantityUnavailable)
            }
        }

        override fun showHistoryEditorDialog(listener: OnDateClickedListener) {
            val dialog = HistoryEditorDialog()
            dialog.arguments = Bundle().apply {
                putLong("habit", habit.id!!)
            }
            dialog.setOnDateClickedListener(listener)
            dialog.show(supportFragmentManager, "historyEditor")
        }

        override fun showFeedback() {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        override fun showNumberPopup(
            value: Double,
            notes: String,
            callback: NumberPickerCallback
        ) {
            showNumberPopup(value, notes, this@ShowHabitActivity.habit, null, callback)
        }

        override fun showNumberPopup(
            value: Double,
            notes: String,
            habit: Habit?,
            date: LocalDate?,
            callback: NumberPickerCallback
        ) {
            val targetHabit = habit ?: this@ShowHabitActivity.habit
            popupJob?.cancel()
            popupJob = scope.launch {
                if (!readsActive || isFinishing || isDestroyed) return@launch
                val generation = popupGeneration.begin()
                var args: Bundle? = null
                while (args == null) {
                    if (!readsActive || isFinishing || isDestroyed ||
                        effectiveReads !== (applicationContext as HabitsApplication).component.effectiveHabitReads) return@launch
                    val capturedRevision = effectiveReads?.revision?.value
                    val capturedDataset = effectiveReads?.revision?.datasetId
                    val capturedCalendar = effectiveReads?.currentCalendar
                    val loaded = withContext(Dispatchers.IO) {
                        val dialogArgs = Bundle()
                        dialogArgs.apply {
                            putDouble("value", value)
                            putString("notes", notes)
                            putString("unit", targetHabit.unit)
                            if (date != null) {
                                val orig = targetHabit.originalEntries.get(date)
                                putDouble("value", orig.value.toDouble() / 1000)
                                putString("notes", orig.notes)
                                putBoolean("hasManualEntry", orig.value != Entry.UNKNOWN)
                            }
                        }
                        if (date != null) {
                            val appComponent = (applicationContext as HabitsApplication).component
                            val cachedDay = targetHabit.id?.let { appComponent.habitCardListCache.getEffectiveSnapshot(it) }
                                ?.takeIf { snapshot ->
                                    snapshot.revision == effectiveReads?.revision?.value &&
                                        snapshot.datasetId == effectiveReads?.revision?.datasetId &&
                                        snapshot.calendar == effectiveReads?.currentCalendar
                                }?.dayOrNull(date)
                            val day = cachedDay ?: if (effectiveReads != null) {
                                runCatching {
                                    effectiveReads?.snapshots(listOf(targetHabit), date, date)?.get(HabitRef(requireNotNull(targetHabit.uuid)))?.dayOrNull(date)
                                }.getOrNull()
                            } else null
                            if (day != null) {
                                dialogArgs.putDouble("focusValue", day.contributionValue / 1000.0)
                                dialogArgs.putDouble("tombstonedFocusValue", day.tombstonedContributionValue / 1000.0)
                                dialogArgs.putStringArrayList("activeContributionIds", ArrayList(day.contributions.map { it.id.value }))
                                dialogArgs.putStringArrayList("tombstonedContributionIds", ArrayList(day.tombstonedContributions.map { it.id.value }))
                                dialogArgs.putBoolean("hasManualEntry", day.manualValue != null)
                                dialogArgs.putBoolean("hasCompleteCoverage", day.hasCompleteCoverage)
                            }
                        }
                        dialogArgs
                    }
                    if (capturedRevision == effectiveReads?.revision?.value &&
                        capturedDataset == (applicationContext as HabitsApplication).component.effectiveHabitReads?.revision?.datasetId &&
                        capturedCalendar == effectiveReads?.currentCalendar) args = loaded
                }
                if (!popupGeneration.accepts(generation) || !readsActive || isFinishing || isDestroyed) return@launch
                val dialog = NumberDialog()
                dialog.arguments = args
                dialog.onRevokeContributions = { ids ->
                    ids.forEach { id ->
                        focusCredit?.revokeContribution(ContributionId(id))
                    }
                }
                dialog.onRestoreContributions = { ids ->
                    ids.forEach { id ->
                        focusCredit?.restoreContribution(ContributionId(id))
                    }
                }
                dialog.onToggle = { v, n -> callback.onNumberPicked(v, n) }
                dialog.dismissCurrentAndShow(supportFragmentManager, "numberDialog")
            }
        }

        override fun showCheckmarkPopup(
            selectedValue: Int,
            notes: String,
            color: PaletteColor,
            callback: CheckMarkDialogCallback
        ) {
            val theme = view.currentTheme()
            val dialog = CheckmarkDialog()
            dialog.arguments = Bundle().apply {
                putInt("color", theme.color(color).toInt())
                putInt("value", selectedValue)
                putString("notes", notes)
            }
            dialog.onToggle = { v, n -> callback.onNotesSaved(v, n) }
            dialog.dismissCurrentAndShow(supportFragmentManager, "checkmarkDialog")
        }

        private fun getPopupAnchor(): View? {
            val dialog =
                supportFragmentManager.findFragmentByTag("historyEditor") as HistoryEditorDialog?
            return dialog?.dataView
        }

        override fun showEditHabitScreen(habit: Habit) {
            startActivity(IntentFactory().startEditActivity(this@ShowHabitActivity, habit))
        }

        override fun showMessage(m: ShowHabitMenuPresenter.Message?) {
            when (m) {
                ShowHabitMenuPresenter.Message.COULD_NOT_EXPORT -> {
                    showMessage(resources.getString(R.string.could_not_export))
                }

                ShowHabitMenuPresenter.Message.HABIT_ARCHIVED -> {
                    showMessage(
                        resources.getQuantityString(
                            R.plurals.toast_habits_archived,
                            1
                        )
                    )
                }

                ShowHabitMenuPresenter.Message.HABIT_UNARCHIVED -> {
                    showMessage(
                        resources.getQuantityString(
                            R.plurals.toast_habits_unarchived,
                            1
                        )
                    )
                }

                else -> {}
            }
        }

        override fun showSendFileScreen(filename: String) {
            this@ShowHabitActivity.showSendFileScreen(filename)
        }

        override fun showDeleteConfirmationScreen(callback: OnConfirmedCallback) {
            ConfirmDeleteDialog(this@ShowHabitActivity, callback, 1).dismissCurrentAndShow()
        }

        override fun showStatisticsStartDateDialog(
            current: LocalDate?,
            callback: (LocalDate?) -> Unit
        ) {
            showStatisticsStartDateDialogInternal(callback)
        }

        override fun showHardResetStatisticsConfirmation(callback: OnConfirmedCallback) {
            CustomDialogs.showConfirmDialog(
                context = this@ShowHabitActivity,
                title = getString(R.string.reset_statistics),
                message = getString(R.string.delete_entries_forever) + "\n\n" +
                        getString(R.string.action_cannot_be_undone) + "\n" +
                        getString(R.string.reset_statistics_backup_hint),
                isDestructive = true,
                positiveText = getString(R.string.delete)
            ) {
                callback.onConfirmed()
            }
        }

        override fun close() {
            this@ShowHabitActivity.finish()
        }
    }

    private fun showStatisticsStartDateDialogInternal(callback: (LocalDate?) -> Unit) {
        val options = listOf(
            getString(R.string.today),
            getString(R.string.this_monday),
            getString(R.string.next_monday),
            getString(R.string.select_date)
        )
        CustomDialogs.showSingleChoiceDialog(
            context = this,
            title = getString(R.string.count_statistics_from_date),
            options = options,
            selectedIndex = -1,
            neutralText = getString(R.string.clear),
            onNeutral = { callback(null) }
        ) { which ->
            when (which) {
                0 -> callback(getToday())
                1 -> callback(thisMonday())
                2 -> callback(thisMonday().plus(7))
                else -> showDatePicker(callback)
            }
        }
    }

    private fun showDatePicker(callback: (LocalDate?) -> Unit) {
        val today = getToday()
        CustomDialogs.showDatePickerDialog(
            context = this,
            title = getString(R.string.select_date),
            initialDate = today
        ) { date ->
            callback(date)
        }
    }

    private fun thisMonday(): LocalDate {
        val firstWeekday = DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1]
        return getToday().startOfWeek(firstWeekday)
    }
}
