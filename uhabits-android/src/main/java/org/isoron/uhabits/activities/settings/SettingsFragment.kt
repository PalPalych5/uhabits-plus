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
package org.isoron.uhabits.activities.settings

import android.app.backup.BackupManager as AndroidBackupManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.datetimepicker.date.DatePickerDialog
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.SettingsAction
import org.isoron.uhabits.activities.main.SettingsActionHandler
import org.isoron.uhabits.core.preferences.Preferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.notifications.AndroidNotificationTray.Companion.createAndroidNotificationChannel
import org.isoron.uhabits.notifications.RingtoneManager
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.startActivitySafely
import org.isoron.uhabits.widgets.WidgetUpdater
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.backup.BackupEntry
import org.isoron.uhabits.backup.BackupManager
import org.isoron.uhabits.backup.BackupSource
import org.isoron.uhabits.backup.BackupStatusStore
import org.isoron.uhabits.backup.SafBackupStorage
import org.isoron.uhabits.core.commands.ClearAllEntriesCommand
import org.isoron.uhabits.core.commands.SetGlobalStatisticsStartDateCommand
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.tasks.RestoreDatabaseTaskFactory
import org.isoron.uhabits.sync.SyncCoordinator
import org.isoron.uhabits.sync.SyncRunResult
import org.isoron.uhabits.utils.DemoDataGenerator
import java.text.DateFormat
import java.util.Locale
import kotlin.system.exitProcess

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    private var sharedPrefs: SharedPreferences? = null
    private var ringtoneManager: RingtoneManager? = null
    private lateinit var prefs: Preferences
    private lateinit var intentFactory: IntentFactory
    private lateinit var backupManager: BackupManager
    private lateinit var backupStatusStore: BackupStatusStore
    private lateinit var safBackupStorage: SafBackupStorage
    private lateinit var restoreTaskFactory: RestoreDatabaseTaskFactory
    private lateinit var syncCoordinator: SyncCoordinator
    private var widgetUpdater: WidgetUpdater? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RINGTONE_REQUEST_CODE -> {
                ringtoneManager!!.update(data)
                updateRingtoneDescription()
                return
            }
            PUBLIC_BACKUP_REQUEST_CODE -> {
                val uri = data?.data ?: return
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                sharedPrefs?.edit()?.putString("publicBackupFolder", uri.toString())?.apply()
                updatePublicBackupFolderSummary()
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        val appContext = requireContext().applicationContext
        if (appContext is HabitsApplication) {
            prefs = appContext.component.preferences
            widgetUpdater = appContext.component.widgetUpdater
            intentFactory = appContext.component.intentFactory
            backupManager = appContext.component.backupManager
            backupStatusStore = BackupStatusStore(requireContext())
            safBackupStorage = SafBackupStorage(requireContext())
            restoreTaskFactory = RestoreDatabaseTaskFactory(appContext, backupManager)
            syncCoordinator = appContext.component.syncCoordinator
        }
        setActionOnPreferenceClick("importData", SettingsAction.IMPORT_DATA)
        setActionOnPreferenceClick("exportCSV", SettingsAction.EXPORT_CSV)
        setActionOnPreferenceClick("exportDB", SettingsAction.EXPORT_DATABASE)
        setActionOnPreferenceClick("repairDB", SettingsAction.REPAIR_DATABASE)
        setActionOnPreferenceClick("bugReport", SettingsAction.BUG_REPORT)
    }

    override fun onCreatePreferences(bundle: Bundle?, s: String?) {
        // NOP
    }

    override fun onPause() {
        sharedPrefs!!.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sr = StyledResources(context!!)
        view.setBackgroundColor(sr.getColor(R.attr.contrast0))
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater?,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): RecyclerView? {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState)
            .also { it.applyBottomInset() }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val key = preference.key ?: return false
        when (key) {
            "reminderSound" -> {
                showRingtonePicker()
                return true
            }
            "reminderCustomize" -> {
                createAndroidNotificationChannel(requireContext())
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, NotificationTray.REMINDERS_CHANNEL_ID)
                startActivity(intent)
                return true
            }
            "rateApp" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.playStoreURL)))
                activity?.startActivitySafely(intent)
                return true
            }
            "publicBackupFolder" -> {
                launchPublicBackupFolderPicker()
                return true
            }
            "restoreBackup" -> {
                showRestoreBackupDialog(BackupSource.PRIVATE)
                return true
            }
            "backupToPublicFolder" -> {
                performPublicBackup()
                return true
            }
            "restorePublicBackup" -> {
                showRestoreBackupDialog(BackupSource.PUBLIC)
                return true
            }
            "configureSpheres" -> {
                actionHandler().onSettingsAction(SettingsAction.MANAGE_SPHERES)
                return true
            }
            "syncSignIn" -> {
                showSyncSignInDialog()
                return true
            }
            "syncSignOut" -> {
                performSignOut()
                return true
            }
            "syncNow" -> {
                if (prefs.isSyncReviewRequired) {
                    showSyncReviewDialog()
                } else {
                    performSyncNow(allowAfterReview = false)
                }
                return true
            }
            "syncReview" -> {
                showSyncReviewDialog()
                return true
            }
            "openArchive" -> {
                actionHandler().onSettingsAction(SettingsAction.OPEN_ARCHIVE)
                return true
            }
            "about" -> {
                startActivity(intentFactory.startAboutActivity(requireContext()))
                return true
            }
            "help" -> {
                activity?.startActivitySafely(intentFactory.viewFAQ(requireContext()))
                return true
            }
            "seedDemoData" -> {
                showSeedConfirmationDialog(isReset = false)
                return true
            }
            "resetDemoData" -> {
                showSeedConfirmationDialog(isReset = true)
                return true
            }
            "softResetStatistics" -> {
                showGlobalStatisticsStartDateDialog()
                return true
            }
            "hardResetStatistics" -> {
                showHardResetStatisticsDialog()
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onResume() {
        super.onResume()
        ringtoneManager = RingtoneManager(requireActivity())
        sharedPrefs = preferenceManager.sharedPreferences
        sharedPrefs!!.registerOnSharedPreferenceChangeListener(this)
        val devCategory = findPreference("devCategory") as PreferenceCategory
        devCategory.isVisible = BuildConfig.DEBUG || prefs.isDeveloper
        findPreference("demoCategory")?.isVisible = BuildConfig.DEBUG
        findPreference("configureSpheres")?.isVisible = prefs.isHabitSpheresEnabled
        updateWeekdayPreference()
        updatePublicBackupFolderSummary()
        updateBackupStatusSummary()
        updateSyncPreferences()

        findPreference("reminderSound").isVisible = false
    }

    private fun updateWeekdayPreference() {
        val weekdayPref = findPreference("pref_first_weekday") as ListPreference
        val currentFirstWeekday = prefs.firstWeekday.daysSinceSunday + 1
        val dayNames = JavaLocalDateFormatter(Locale.getDefault()).longWeekdayNames(DayOfWeek.SATURDAY)
        val dayValues = arrayOf("7", "1", "2", "3", "4", "5", "6")
        weekdayPref.entries = dayNames
        weekdayPref.entryValues = dayValues
        weekdayPref.setDefaultValue(currentFirstWeekday.toString())
        weekdayPref.summary = dayNames[currentFirstWeekday % 7]
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        if (key == "pref_enable_habit_spheres") {
            findPreference("configureSpheres")?.isVisible = prefs.isHabitSpheresEnabled
        }
        if (key == "pref_widget_opacity" && widgetUpdater != null) {
            Log.d("SettingsFragment", "updating widgets")
            widgetUpdater!!.updateWidgets()
        }
        if (key == "pref_theme" || key == "pref_pure_black") {
            val switcher = AndroidThemeSwitcher(requireContext(), prefs)
            switcher.apply()
            val intent = MainActivity.intent(requireContext(), MainDestination.SETTINGS)
            Handler().postDelayed({
                activity?.finish()
                activity?.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                startActivity(intent)
            }, 500)
        }
        if (key == "pref_app_language") {
            val languageTag = sharedPreferences.getString("pref_app_language", "ru-RU") ?: "ru-RU"
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag)
            )
            val intent = MainActivity.intent(requireContext(), MainDestination.SETTINGS)
            Handler().postDelayed({
                activity?.finish()
                activity?.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                startActivity(intent)
            }, 500)
        }
        if (key == "pref_sync_enabled") {
            updateSyncPreferences()
        }
        AndroidBackupManager.dataChanged("org.isoron.uhabits.plus")
        updateWeekdayPreference()
    }

    private fun updateSyncPreferences() {
        val accountPref = findPreference("syncAccount") ?: return
        val signInPref = findPreference("syncSignIn") ?: return
        val signOutPref = findPreference("syncSignOut") ?: return
        val statusPref = findPreference("syncStatus") ?: return
        val nowPref = findPreference("syncNow") ?: return
        val reviewPref = findPreference("syncReview") ?: return
        val accountEmail = runCatching { syncCoordinator.currentAccountEmail() }
            .getOrElse {
                prefs.syncStatus = "error"
                prefs.syncStatusDetail = "Sync UI unavailable: ${it.message ?: it::class.simpleName}"
                null
            }
        accountPref.summary = accountEmail ?: getString(R.string.sync_signed_out)
        signInPref.isVisible = accountEmail == null
        signOutPref.isVisible = accountEmail != null
        nowPref.isEnabled = prefs.isSyncEnabled && accountEmail != null
        reviewPref.isVisible = prefs.isSyncReviewRequired
        reviewPref.summary = prefs.syncReviewReason.ifBlank { getString(R.string.sync_review_required_summary) }

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        statusPref.summary = when (prefs.syncStatus) {
            "success" -> getString(
                R.string.sync_status_success,
                dateFormat.format(prefs.syncLastSuccessAt)
            )
            "error" -> getString(
                R.string.sync_status_error,
                prefs.syncStatusDetail.ifBlank { getString(R.string.could_not_export) }
            )
            "syncing" -> getString(R.string.sync_status_syncing)
            "signed_out" -> getString(R.string.sync_status_signed_out)
            "review_required" -> getString(R.string.sync_status_review_required)
            "idle" -> getString(R.string.sync_status_idle)
            else -> getString(R.string.sync_status_disabled)
        }
    }

    private fun setActionOnPreferenceClick(key: String, action: SettingsAction) {
        val pref = findPreference(key)
        pref.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                actionHandler().onSettingsAction(action)
                true
            }
    }

    private fun actionHandler(): SettingsActionHandler =
        requireActivity() as SettingsActionHandler

    private fun showRingtonePicker() {
        val existingRingtoneUri = ringtoneManager!!.getURI()
        val defaultRingtoneUri = Settings.System.DEFAULT_NOTIFICATION_URI
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
            android.media.RingtoneManager.TYPE_NOTIFICATION
        )
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            defaultRingtoneUri
        )
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            existingRingtoneUri
        )
        startActivityForResult(intent, RINGTONE_REQUEST_CODE)
    }

    private fun updateRingtoneDescription() {
        val ringtoneName = ringtoneManager!!.getName() ?: return
        val ringtonePreference = findPreference("reminderSound")
        ringtonePreference.summary = ringtoneName
    }

    private fun updatePublicBackupFolderSummary() {
        val pref = findPreference("publicBackupFolder")
        val uriString = sharedPrefs?.getString("publicBackupFolder", null)
        if (uriString == null) {
            pref.summary = getString(R.string.no_public_backup_folder_selected)
            return
        }
        if (!backupManager.isPublicBackupFolderAvailable()) {
            pref.summary = getString(R.string.backup_external_folder_permission_lost)
            return
        }
        val uri = Uri.parse(uriString)
        val path = fullPathFor(uri)
        pref.summary = path ?: uriString
    }

    private fun fullPathFor(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val (type, rel) = docId.split(":", limit = 2).let {
                    it[0] to it.getOrElse(1) { "" }
                }
                val base = if (type.equals("primary", true)) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "/storage/$type"
                }
                if (rel.isEmpty()) base else "$base/$rel"
            }
            "file" -> java.io.File(uri.path!!).absolutePath
            else -> null
        }
    }

    private fun updateBackupStatusSummary() {
        val pref = findPreference("backupStatus") ?: return
        val status = backupStatusStore.load()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        pref.summary = if (status.lastSuccessAt != null) {
            getString(
                R.string.backup_status_last_success,
                dateFormat.format(status.lastSuccessAt),
                formatSize(status.lastBackupSizeBytes ?: 0L)
            )
        } else {
            getString(R.string.backup_status_never)
        }
    }

    private fun showRestoreBackupDialog(source: BackupSource) {
        if (source == BackupSource.PUBLIC && !ensurePublicBackupFolderReady()) return

        val backups = when (source) {
            BackupSource.PRIVATE -> backupManager.listLocalBackups()
            BackupSource.PUBLIC -> backupManager.listPublicBackups()
        }
        if (backups.isEmpty()) {
            val messageId = if (source == BackupSource.PUBLIC) {
                R.string.backup_restore_no_public_backups
            } else {
                R.string.backup_restore_no_backups
            }
            Toast.makeText(requireContext(), messageId, Toast.LENGTH_LONG).show()
            return
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        val items = backups.map {
            "${it.name}\n${dateFormat.format(it.modifiedAt)} • ${formatSize(it.sizeBytes)}"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.restore_backup)
            .setItems(items) { _, which ->
                showRestoreBackupConfirmation(backups[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performPublicBackup() {
        if (!ensurePublicBackupFolderReady()) return

        val app = requireContext().applicationContext as HabitsApplication
        app.component.taskRunner.execute(object : Task {
            private var result: Result<String>? = null

            override suspend fun doInBackground() {
                result = runCatching {
                    backupManager.backupToPublicFolder().location
                }
            }

            override fun onPostExecute() {
                result?.fold(
                    onSuccess = {
                        updateBackupStatusSummary()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.backup_external_success),
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = {
                        Toast.makeText(
                            requireContext(),
                            it.message ?: getString(R.string.could_not_export),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        })
    }

    private fun ensurePublicBackupFolderReady(): Boolean {
        return when {
            !backupManager.isPublicBackupFolderConfigured() -> {
                showChoosePublicBackupFolderDialog(R.string.backup_external_folder_not_selected)
                false
            }
            !backupManager.isPublicBackupFolderAvailable() -> {
                showChoosePublicBackupFolderDialog(R.string.backup_external_folder_permission_lost)
                false
            }
            else -> true
        }
    }

    private fun showChoosePublicBackupFolderDialog(messageId: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_public_backup_folder)
            .setMessage(messageId)
            .setPositiveButton(R.string.choose_folder) { _, _ ->
                launchPublicBackupFolderPicker()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchPublicBackupFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, PUBLIC_BACKUP_REQUEST_CODE)
    }

    private fun showRestoreBackupConfirmation(entry: BackupEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.restore_backup)
            .setMessage(R.string.restore_backup_warning)
            .setPositiveButton(R.string.restore_backup_confirm) { _, _ ->
                performRestore(entry)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performRestore(entry: BackupEntry) {
        val app = requireContext().applicationContext as HabitsApplication
        app.component.taskRunner.execute(
            restoreTaskFactory.create(entry) { error ->
                if (error == null) {
                    app.scheduleProcessRestart(MainDestination.SETTINGS)
                    activity?.finishAffinity()
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                } else {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return "0 B"
        val kb = 1024L
        val mb = kb * kb
        return when {
            sizeBytes >= mb -> String.format(Locale.US, "%.1f MB", sizeBytes.toDouble() / mb)
            sizeBytes >= kb -> String.format(Locale.US, "%.1f KB", sizeBytes.toDouble() / kb)
            else -> "$sizeBytes B"
        }
    }

    private fun showSyncSignInDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        val emailInput = EditText(requireContext()).apply {
            hint = getString(R.string.sync_sign_in_email_hint)
            setText(prefs.syncAccountEmail ?: "")
        }
        val passwordInput = EditText(requireContext()).apply {
            hint = getString(R.string.sync_sign_in_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(emailInput)
        container.addView(passwordInput)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_sign_in_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.sync_sign_in) { _, _ ->
                performSignIn(emailInput.text.toString(), passwordInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performSignIn(email: String, password: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    syncCoordinator.signIn(email, password)
                }
            }.getOrElse {
                SyncRunResult.Failure(
                    "Синхронизация сейчас недоступна.",
                    it.message ?: it::class.simpleName
                )
            }
            when (result) {
                is SyncRunResult.Success -> {
                    Toast.makeText(requireContext(), R.string.sync_result_signed_in, Toast.LENGTH_LONG).show()
                    updateSyncPreferences()
                }
                is SyncRunResult.Failure -> {
                    Toast.makeText(requireContext(), result.userMessage, Toast.LENGTH_LONG).show()
                    updateSyncPreferences()
                }
                else -> Unit
            }
        }
    }

    private fun performSignOut() {
        CoroutineScope(Dispatchers.Main).launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    syncCoordinator.signOut()
                }
            }.getOrElse {
                SyncRunResult.Failure(
                    "Синхронизация сейчас недоступна.",
                    it.message ?: it::class.simpleName
                )
            }
            if (result is SyncRunResult.Success) {
                Toast.makeText(requireContext(), R.string.sync_result_signed_out, Toast.LENGTH_LONG).show()
            } else if (result is SyncRunResult.Failure) {
                Toast.makeText(requireContext(), result.userMessage, Toast.LENGTH_LONG).show()
            }
            updateSyncPreferences()
        }
    }

    private fun showSyncReviewDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_confirm_after_restore_title)
            .setMessage(
                prefs.syncReviewReason.ifBlank {
                    getString(R.string.sync_confirm_after_restore_message)
                }
            )
            .setPositiveButton(R.string.sync_now) { _, _ ->
                syncCoordinator.confirmSyncReview()
                updateSyncPreferences()
                performSyncNow(allowAfterReview = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performSyncNow(allowAfterReview: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            updateSyncPreferences()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    syncCoordinator.runSync(manual = true, allowAfterReview = allowAfterReview)
                }
            }.getOrElse {
                SyncRunResult.Failure(
                    "Синхронизация сейчас недоступна.",
                    it.message ?: it::class.simpleName
                )
            }
            when (result) {
                is SyncRunResult.Success -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.sync_result_success, result.pushed, result.pulled),
                        Toast.LENGTH_LONG
                    ).show()
                }
                is SyncRunResult.Failure -> {
                    Toast.makeText(requireContext(), result.userMessage, Toast.LENGTH_LONG).show()
                }
                is SyncRunResult.Skipped -> Unit
            }
            updateSyncPreferences()
        }
    }

    private fun showSeedConfirmationDialog(isReset: Boolean) {
        val title = if (isReset) getString(R.string.demo_data_confirm_reset_title) else getString(R.string.demo_data_confirm_title)
        val message = if (isReset) getString(R.string.demo_data_confirm_reset_message) else getString(R.string.demo_data_confirm_message)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.demo_data_confirm_proceed) { _, _ ->
                val habitsApp = requireContext().applicationContext as HabitsApplication
                val component = habitsApp.component
                
                // Show a loading toast
                Toast.makeText(requireContext(), R.string.demo_data_generating, Toast.LENGTH_SHORT).show()
                
                CoroutineScope(Dispatchers.Main).launch {
                    val success = withContext(Dispatchers.IO) {
                        try {
                            DemoDataGenerator.generate(
                                context = requireContext(),
                                modelFactory = component.modelFactory as SQLModelFactory,
                                habitList = component.habitList,
                                widgetUpdater = component.widgetUpdater,
                                cache = component.habitCardListCache,
                                isReset = isReset
                            )
                            true
                        } catch (e: Exception) {
                            Log.e("SettingsFragment", "Failed to generate demo data", e)
                            false
                        }
                    }
                    if (success) {
                        Toast.makeText(requireContext(), R.string.demo_data_generated_success, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), R.string.could_not_import, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGlobalStatisticsStartDateDialog() {
        val options = arrayOf(
            getString(R.string.today),
            getString(R.string.this_monday),
            getString(R.string.next_monday),
            getString(R.string.select_date)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.count_statistics_from_date)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyGlobalStatisticsStart(getToday())
                    1 -> applyGlobalStatisticsStart(thisMonday())
                    2 -> applyGlobalStatisticsStart(thisMonday().plus(7))
                    else -> showDatePicker { date -> applyGlobalStatisticsStart(date) }
                }
            }
            .setNeutralButton(R.string.clear) { _, _ -> applyGlobalStatisticsStart(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyGlobalStatisticsStart(date: LocalDate?) {
        val app = requireContext().applicationContext as HabitsApplication
        app.component.commandRunner.run(
            SetGlobalStatisticsStartDateCommand(app.component.habitList, date)
        )
    }

    private fun showHardResetStatisticsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_statistics)
            .setMessage(
                getString(R.string.delete_entries_forever) + "\n\n" +
                    getString(R.string.action_cannot_be_undone) + "\n" +
                    getString(R.string.reset_statistics_backup_hint)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                val app = requireContext().applicationContext as HabitsApplication
                app.component.commandRunner.run(ClearAllEntriesCommand(app.component.habitList))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDatePicker(callback: (LocalDate?) -> Unit) {
        val today = getToday()
        val dialog = DatePickerDialog.newInstance(
            object : DatePickerDialog.OnDateSetListener {
                override fun onDateSet(
                    dialog: DatePickerDialog?,
                    year: Int,
                    monthOfYear: Int,
                    dayOfMonth: Int
                ) {
                    callback(LocalDate(year, monthOfYear + 1, dayOfMonth))
                }

                override fun onDateCleared(dialog: DatePickerDialog?) {
                    callback(null)
                }
            },
            today.year,
            today.month - 1,
            today.day
        )
        dialog.show(requireActivity().fragmentManager, "settingsStatisticsDatePicker")
    }

    private fun thisMonday(): LocalDate {
        val firstWeekday = DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1]
        return getToday().startOfWeek(firstWeekday)
    }

    companion object {
        private const val RINGTONE_REQUEST_CODE = 1
        private const val PUBLIC_BACKUP_REQUEST_CODE = 2
    }
}
