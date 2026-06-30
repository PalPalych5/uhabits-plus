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
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.main.SettingsAction
import org.isoron.uhabits.activities.main.SettingsActionHandler
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialogFactory
import org.isoron.uhabits.core.commands.ClearAllEntriesCommand
import org.isoron.uhabits.core.commands.SetGlobalStatisticsStartDateCommand
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.notifications.RingtoneManager
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.applyBottomInset
import org.isoron.uhabits.utils.startActivitySafely
import org.isoron.uhabits.widgets.WidgetUpdater
import org.isoron.uhabits.backup.BackupEntry
import org.isoron.uhabits.backup.BackupManager
import org.isoron.uhabits.backup.BackupSource
import org.isoron.uhabits.backup.BackupStatusStore
import org.isoron.uhabits.backup.SafBackupStorage
import org.isoron.uhabits.tasks.RestoreDatabaseTaskFactory
import org.isoron.uhabits.sync.SyncCoordinator
import org.isoron.uhabits.sync.SyncRunResult
import org.isoron.uhabits.utils.DemoDataGenerator
import java.text.DateFormat
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

class SettingsFragment : Fragment(), OnSharedPreferenceChangeListener {
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

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CustomSettingsAdapter

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RINGTONE_REQUEST_CODE -> {
                ringtoneManager?.update(data)
                rebuildSettingsList()
                return
            }
            PUBLIC_BACKUP_REQUEST_CODE -> {
                val uri = data?.data ?: return
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                sharedPrefs?.edit()?.putString("publicBackupFolder", uri.toString())?.apply()
                rebuildSettingsList()
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_custom_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val palette = SettingsThemePaletteResolver.resolve(requireContext(), prefs)
        view.setBackgroundColor(palette.background)

        recyclerView = view.findViewById(R.id.settingsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.applyBottomInset()

        adapter = CustomSettingsAdapter(requireContext(), prefs, emptyList())
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        ringtoneManager = RingtoneManager(requireActivity())
        sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        sharedPrefs!!.registerOnSharedPreferenceChangeListener(this)

        rebuildSettingsList()
    }

    override fun onPause() {
        sharedPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == "pref_widget_opacity" && widgetUpdater != null) {
            Log.d("SettingsFragment", "updating widgets")
            widgetUpdater!!.updateWidgets()
        }
        AndroidBackupManager.dataChanged("org.isoron.uhabits.plus")
        activity?.runOnUiThread {
            rebuildSettingsList()
        }
    }

    private fun rebuildSettingsList() {
        if (!isAdded) return
        val items = mutableListOf<SettingItem>()

        // 1. Внешний вид
        items.add(SettingItem.Header(getString(R.string.appearance)))
        
        // Тема (Segmented)
        items.add(
            SettingItem.SegmentedTheme(
                key = "pref_theme",
                iconRes = R.drawable.ic_settings_theme,
                title = getString(R.string.theme),
                themeValue = prefs.theme,
                pureBlackValue = prefs.isPureBlackEnabled,
                onSegmentSelected = { newTheme, newPureBlack ->
                    prefs.theme = newTheme
                    prefs.isPureBlackEnabled = newPureBlack
                    val switcher = AndroidThemeSwitcher(requireContext(), prefs)
                    switcher.apply()
                    reloadSettingsScreen()
                }
            )
        )

        // Язык
        val languageTag = sharedPrefs?.getString("pref_app_language", "ru-RU") ?: "ru-RU"
        val languageIndex = resources.getStringArray(R.array.pref_app_language_values).indexOf(languageTag)
        val languageEntry = if (languageIndex >= 0) {
            resources.getStringArray(R.array.pref_app_language_entries)[languageIndex]
        } else {
            languageTag
        }
        items.add(
            SettingItem.Navigation(
                key = "pref_app_language",
                iconRes = R.drawable.ic_settings_globe,
                title = getString(R.string.language),
                summary = languageEntry,
                onClick = { showLanguageDialog() }
            )
        )

        // Первый день недели
        val currentFirstWeekday = prefs.firstWeekday.daysSinceSunday + 1
        val dayNames = JavaLocalDateFormatter(Locale.getDefault()).longWeekdayNames(DayOfWeek.SATURDAY)
        val weekdaySummary = dayNames[currentFirstWeekday % 7]
        items.add(
            SettingItem.Navigation(
                key = "pref_first_weekday",
                iconRes = R.drawable.ic_settings_calendar,
                title = getString(R.string.first_day_of_the_week),
                summary = weekdaySummary,
                onClick = { showWeekdayDialog() }
            )
        )

        // Акцентный цвет
        val accentColorString = AccentColorManager.getAccentColorString(prefs)
        val accentColorName = AccentColorManager.getAccentColorName(requireContext(), prefs)
        items.add(
            SettingItem.ColorPicker(
                key = "pref_accent_color",
                iconRes = R.drawable.ic_settings_droplet,
                title = getString(R.string.accent_color),
                summary = accentColorName,
                colorString = accentColorString,
                onClick = { showAccentColorPicker() }
            )
        )

        // Анимации
        items.add(
            SettingItem.Switch(
                key = "pref_disable_animation",
                iconRes = R.drawable.ic_settings_animation,
                title = getString(R.string.pref_animations_title),
                summary = getString(R.string.pref_animations_description),
                checked = prefs.isConfettiAnimationDisabled,
                onCheckedChange = { checked ->
                    prefs.isConfettiAnimationDisabled = checked
                    rebuildSettingsList()
                }
            )
        )

        // Непрозрачность виджета
        val opacityValue = sharedPrefs?.getString("pref_widget_opacity", "255") ?: "255"
        val opacityIndex = resources.getStringArray(R.array.widget_opacity_values).indexOf(opacityValue)
        val opacityEntry = if (opacityIndex >= 0) {
            resources.getStringArray(R.array.widget_opacity_entries)[opacityIndex]
        } else {
            opacityValue
        }
        items.add(
            SettingItem.Navigation(
                key = "pref_widget_opacity",
                iconRes = R.drawable.ic_settings_opacity,
                title = getString(R.string.widget_opacity_title),
                summary = opacityEntry,
                onClick = { showWidgetOpacityDialog() }
            )
        )

        // 2. Привычки и Сегодня
        items.add(SettingItem.Header(getString(R.string.pref_habits_today_title)))

        items.add(
            SettingItem.Switch(
                key = "pref_enable_habit_spheres",
                iconRes = R.drawable.ic_settings_spheres,
                title = getString(R.string.pref_enable_habit_spheres_title),
                summary = getString(R.string.pref_enable_habit_spheres_summary),
                checked = prefs.isHabitSpheresEnabled,
                onCheckedChange = { checked ->
                    prefs.isHabitSpheresEnabled = checked
                    rebuildSettingsList()
                }
            )
        )

        if (prefs.isHabitSpheresEnabled) {
            items.add(
                SettingItem.Navigation(
                    key = "configureSpheres",
                    iconRes = R.drawable.ic_settings_configure_spheres,
                    title = getString(R.string.configure_spheres),
                    summary = getString(R.string.configure_spheres_summary),
                    onClick = { actionHandler().onSettingsAction(SettingsAction.MANAGE_SPHERES) }
                )
            )
        }

        items.add(
            SettingItem.Switch(
                key = "pref_enable_day_tiers",
                iconRes = R.drawable.ic_settings_tiers,
                title = getString(R.string.pref_enable_day_tiers_title),
                summary = getString(R.string.pref_enable_day_tiers_summary),
                checked = prefs.isDayTiersEnabled,
                onCheckedChange = { checked ->
                    prefs.isDayTiersEnabled = checked
                    rebuildSettingsList()
                }
            )
        )

        items.add(
            SettingItem.Switch(
                key = "pref_short_toggle",
                iconRes = R.drawable.ic_settings_check,
                title = getString(R.string.pref_toggle_title),
                summary = getString(R.string.pref_toggle_description_2),
                checked = prefs.isShortToggleEnabled,
                onCheckedChange = { checked ->
                    prefs.isShortToggleEnabled = checked
                    rebuildSettingsList()
                }
            )
        )

        items.add(
            SettingItem.Switch(
                key = "pref_checkmark_reverse_order",
                iconRes = R.drawable.ic_settings_reverse,
                title = getString(R.string.reverse_days),
                summary = getString(R.string.reverse_days_description),
                checked = prefs.isCheckmarkSequenceReversed,
                onCheckedChange = { checked ->
                    prefs.isCheckmarkSequenceReversed = checked
                    rebuildSettingsList()
                }
            )
        )

        items.add(
            SettingItem.Switch(
                key = "pref_skip_enabled",
                iconRes = R.drawable.ic_settings_fast_forward,
                title = getString(R.string.pref_skip_title),
                summary = getString(R.string.pref_skip_description),
                checked = prefs.isSkipEnabled,
                onCheckedChange = { checked ->
                    prefs.isSkipEnabled = checked
                    rebuildSettingsList()
                }
            )
        )

        items.add(
            SettingItem.Switch(
                key = "pref_unknown_enabled",
                iconRes = R.drawable.ic_settings_question,
                title = getString(R.string.pref_unknown_title),
                summary = getString(R.string.pref_unknown_description),
                checked = prefs.areQuestionMarksEnabled,
                onCheckedChange = { checked ->
                    prefs.areQuestionMarksEnabled = checked
                    rebuildSettingsList()
                }
            )
        )

        // Стартовый раздел
        val startDestValue = prefs.startDestinationName
        val startDestIndex = resources.getStringArray(R.array.pref_start_destination_values).indexOf(startDestValue)
        val startDestEntry = if (startDestIndex >= 0) {
            resources.getStringArray(R.array.pref_start_destination_entries)[startDestIndex]
        } else {
            startDestValue
        }
        items.add(
            SettingItem.Navigation(
                key = "pref_start_destination",
                iconRes = R.drawable.ic_settings_start,
                title = getString(R.string.pref_start_destination_title),
                summary = startDestEntry,
                onClick = { showStartDestinationDialog() }
            )
        )

        items.add(
            SettingItem.Switch(
                key = "pref_show_today_tab",
                iconRes = R.drawable.ic_settings_today,
                title = getString(R.string.pref_show_today_tab_title),
                summary = getString(R.string.pref_show_today_tab_description),
                checked = prefs.isTodayTabVisible,
                onCheckedChange = { checked ->
                    prefs.isTodayTabVisible = checked
                    rebuildSettingsList()
                }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "openArchive",
                iconRes = R.drawable.ic_settings_archive,
                title = getString(R.string.open_archive),
                summary = getString(R.string.open_archive_summary),
                onClick = { actionHandler().onSettingsAction(SettingsAction.OPEN_ARCHIVE) }
            )
        )

        items.add(
            SettingItem.Switch(
                key = "pref_midnight_delay",
                iconRes = R.drawable.ic_settings_clock,
                title = getString(R.string.pref_midnight_delay_title),
                summary = getString(R.string.pref_midnight_delay_description),
                checked = prefs.isMidnightDelayEnabled,
                onCheckedChange = { checked ->
                    prefs.isMidnightDelayEnabled = checked
                    rebuildSettingsList()
                }
            )
        )

        items.add(
            SettingItem.Switch(
                key = "pref_sticky_notifications",
                iconRes = R.drawable.ic_settings_notification_ringing,
                title = getString(R.string.sticky_notifications),
                summary = getString(R.string.sticky_notifications_description),
                checked = prefs.shouldMakeNotificationsSticky(),
                onCheckedChange = { checked ->
                    prefs.setNotificationsSticky(checked)
                    rebuildSettingsList()
                }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "reminderCustomize",
                iconRes = R.drawable.ic_settings_notification,
                title = getString(R.string.customize_notification),
                summary = getString(R.string.customize_notification_summary),
                onClick = {
                    org.isoron.uhabits.notifications.AndroidNotificationTray.createAndroidNotificationChannel(requireContext())
                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, org.isoron.uhabits.core.ui.NotificationTray.REMINDERS_CHANNEL_ID)
                    }
                    startActivity(intent)
                }
            )
        )

        // 3. Статистика
        items.add(SettingItem.Header(getString(R.string.pref_statistics_title)))

        items.add(
            SettingItem.Navigation(
                key = "softResetStatistics",
                iconRes = R.drawable.ic_settings_statistics,
                title = getString(R.string.start_statistics_over),
                summary = getString(R.string.count_statistics_from_date),
                onClick = { showGlobalStatisticsStartDateDialog() }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "hardResetStatistics",
                iconRes = R.drawable.ic_settings_trash,
                title = getString(R.string.reset_statistics),
                summary = getString(R.string.delete_entries_forever),
                isDanger = true,
                onClick = { showHardResetStatisticsDialog() }
            )
        )

        // 4. Данные и резервное копирование
        items.add(SettingItem.Header(getString(R.string.pref_data_backup_title)))

        val backupStatusText = getBackupStatusSummaryText()
        items.add(
            SettingItem.Navigation(
                key = "backupStatus",
                iconRes = R.drawable.ic_settings_backup_history,
                title = getString(R.string.backup_status_title),
                summary = backupStatusText
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "exportDB",
                iconRes = R.drawable.ic_settings_backup_upload,
                title = getString(R.string.backup_now),
                summary = getString(R.string.export_full_backup_summary),
                onClick = { actionHandler().onSettingsAction(SettingsAction.EXPORT_DATABASE) }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "restoreBackup",
                iconRes = R.drawable.ic_settings_backup_restore,
                title = getString(R.string.restore_backup),
                summary = getString(R.string.restore_backup_local_summary),
                onClick = { showRestoreBackupDialog(BackupSource.PRIVATE) }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "exportCSV",
                iconRes = R.drawable.ic_settings_export_csv,
                title = getString(R.string.export_to_csv),
                summary = getString(R.string.export_as_csv_summary),
                onClick = { actionHandler().onSettingsAction(SettingsAction.EXPORT_CSV) }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "importData",
                iconRes = R.drawable.ic_settings_import,
                title = getString(R.string.import_data),
                summary = getString(R.string.import_data_summary),
                onClick = { actionHandler().onSettingsAction(SettingsAction.IMPORT_DATA) }
            )
        )

        val publicFolderUriString = sharedPrefs?.getString("publicBackupFolder", null)
        val publicFolderSummary = getPublicBackupFolderSummaryText(publicFolderUriString)
        items.add(
            SettingItem.Navigation(
                key = "publicBackupFolder",
                iconRes = R.drawable.ic_settings_folder,
                title = getString(R.string.select_public_backup_folder),
                summary = publicFolderSummary,
                onClick = { launchPublicBackupFolderPicker() }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "backupToPublicFolder",
                iconRes = R.drawable.ic_settings_folder_upload,
                title = getString(R.string.backup_to_public_folder),
                summary = getString(R.string.backup_to_public_folder_summary),
                onClick = { performPublicBackup() }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "restorePublicBackup",
                iconRes = R.drawable.ic_settings_folder_restore,
                title = getString(R.string.restore_public_backup),
                summary = getString(R.string.restore_public_backup_summary),
                onClick = { showRestoreBackupDialog(BackupSource.PUBLIC) }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "repairDB",
                iconRes = R.drawable.ic_settings_repair,
                title = getString(R.string.repair_database),
                onClick = { actionHandler().onSettingsAction(SettingsAction.REPAIR_DATABASE) }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "bugReport",
                iconRes = R.drawable.ic_settings_bug,
                title = getString(R.string.generate_bug_report),
                onClick = { actionHandler().onSettingsAction(SettingsAction.BUG_REPORT) }
            )
        )

        // 5. Синхронизация
        items.add(SettingItem.Header(getString(R.string.sync_title)))

        items.add(
            SettingItem.Switch(
                key = "pref_sync_enabled",
                iconRes = R.drawable.ic_settings_cloud_sync,
                title = getString(R.string.sync_enable_title),
                summary = getString(R.string.sync_enable_summary),
                checked = prefs.isSyncEnabled,
                onCheckedChange = { checked ->
                    prefs.isSyncEnabled = checked
                    rebuildSettingsList()
                }
            )
        )

        val accountEmail = runCatching { syncCoordinator.currentAccountEmail() }.getOrNull()
        items.add(
            SettingItem.Navigation(
                key = "syncAccount",
                iconRes = R.drawable.ic_settings_account,
                title = getString(R.string.sync_account_title),
                summary = accountEmail ?: getString(R.string.sync_signed_out)
            )
        )

        if (accountEmail == null) {
            items.add(
                SettingItem.Navigation(
                    key = "syncSignIn",
                    iconRes = R.drawable.ic_settings_login,
                    title = getString(R.string.sync_sign_in),
                    summary = getString(R.string.sync_sign_in_summary),
                    onClick = { showSyncSignInDialog() }
                )
            )
        } else {
            items.add(
                SettingItem.Navigation(
                    key = "syncSignOut",
                    iconRes = R.drawable.ic_settings_logout,
                    title = getString(R.string.sync_sign_out),
                    summary = getString(R.string.sync_sign_out_summary),
                    onClick = { performSignOut() }
                )
            )
        }

        val syncStatusText = getSyncStatusSummaryText()
        items.add(
            SettingItem.Navigation(
                key = "syncStatus",
                iconRes = R.drawable.ic_settings_backup_history,
                title = getString(R.string.sync_status_title),
                summary = syncStatusText
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "syncNow",
                iconRes = R.drawable.ic_settings_sync,
                title = getString(R.string.sync_now),
                summary = getString(R.string.sync_now_summary),
                onClick = {
                    if (prefs.isSyncReviewRequired) {
                        showSyncReviewDialog()
                    } else {
                        performSyncNow(allowAfterReview = false)
                    }
                }
            )
        )

        if (prefs.isSyncReviewRequired) {
            val reviewSummary = prefs.syncReviewReason.ifBlank { getString(R.string.sync_review_required_summary) }
            items.add(
                SettingItem.Navigation(
                    key = "syncReview",
                    iconRes = R.drawable.ic_settings_git_merge,
                    title = getString(R.string.sync_review_required_title),
                    summary = reviewSummary,
                    onClick = { showSyncReviewDialog() }
                )
            )
        }

        // 6. Справка
        items.add(SettingItem.Header(getString(R.string.help_category)))

        items.add(
            SettingItem.Navigation(
                key = "help",
                iconRes = R.drawable.ic_settings_help,
                title = getString(R.string.help),
                onClick = { activity?.startActivitySafely(intentFactory.viewFAQ(requireContext())) }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "rateApp",
                iconRes = R.drawable.ic_settings_rate,
                title = getString(R.string.pref_rate_this_app),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.playStoreURL)))
                    activity?.startActivitySafely(intent)
                }
            )
        )

        items.add(
            SettingItem.Navigation(
                key = "about",
                iconRes = R.drawable.ic_settings_info,
                title = getString(R.string.about),
                onClick = { startActivity(intentFactory.startAboutActivity(requireContext())) }
            )
        )

        // 7. Настройки разработчика (prefs.isDeveloper)
        val isDevMode = prefs.isDeveloper
        items.add(SettingItem.Header(getString(R.string.pref_developer_section_title)))

        items.add(
            SettingItem.Switch(
                key = "pref_developer",
                iconRes = R.drawable.ic_settings_terminal,
                title = getString(R.string.developer_mode_title),
                checked = prefs.isDeveloper,
                onCheckedChange = { checked ->
                    prefs.isDeveloper = checked
                    rebuildSettingsList()
                }
            )
        )

        if (isDevMode) {
            items.add(
                SettingItem.Navigation(
                    key = "pref_sync_base_url",
                    iconRes = R.drawable.ic_settings_link,
                    title = getString(R.string.supabase_url_title),
                    summary = sharedPrefs?.getString("pref_sync_base_url", "") ?: "",
                    onClick = { showDeveloperEditTextDialog("pref_sync_base_url", getString(R.string.supabase_url_title)) }
                )
            )

            items.add(
                SettingItem.Navigation(
                    key = "pref_sync_key",
                    iconRes = R.drawable.ic_settings_key,
                    title = getString(R.string.supabase_anon_key_title),
                    summary = sharedPrefs?.getString("pref_sync_key", "") ?: "",
                    onClick = { showDeveloperEditTextDialog("pref_sync_key", getString(R.string.supabase_anon_key_title)) }
                )
            )

            items.add(
                SettingItem.Navigation(
                    key = "pref_encryption_key",
                    iconRes = R.drawable.ic_settings_lock,
                    title = "Encryption key",
                    summary = sharedPrefs?.getString("pref_encryption_key", "") ?: "",
                    onClick = { showDeveloperEditTextDialog("pref_encryption_key", "Encryption key") }
                )
            )

            items.add(
                SettingItem.Navigation(
                    key = "exportSyncDiagnostics",
                    iconRes = R.drawable.ic_settings_bug,
                    title = getString(R.string.sync_export_diagnostics_title),
                    onClick = { exportSyncDiagnostics() }
                )
            )

            items.add(
                SettingItem.Navigation(
                    key = "refreshScreens",
                    iconRes = R.drawable.ic_settings_sync,
                    title = getString(R.string.sync_refresh_screens_title),
                    onClick = { refreshScreensForDiagnostics() }
                )
            )

            items.add(
                SettingItem.Navigation(
                    key = "seedDemoData",
                    iconRes = R.drawable.ic_settings_demo_data,
                    title = getString(R.string.demo_data_seed_title),
                    summary = getString(R.string.demo_data_seed_summary),
                    onClick = { showSeedConfirmationDialog(isReset = false) }
                )
            )

            items.add(
                SettingItem.Navigation(
                    key = "resetDemoData",
                    iconRes = R.drawable.ic_settings_trash,
                    title = getString(R.string.demo_data_reset_title),
                    summary = getString(R.string.demo_data_reset_summary),
                    isDanger = true,
                    onClick = { showSeedConfirmationDialog(isReset = true) }
                )
            )
        }

        adapter.updateItems(items)
    }

    private fun showLanguageDialog() {
        val entries = resources.getStringArray(R.array.pref_app_language_entries)
        val values = resources.getStringArray(R.array.pref_app_language_values)
        val currentVal = sharedPrefs?.getString("pref_app_language", "ru-RU") ?: "ru-RU"
        val selectedIndex = values.indexOf(currentVal)

        CustomDialogs.showSingleChoiceDialog(
            context = requireContext(),
            title = getString(R.string.language),
            options = entries.toList(),
            selectedIndex = selectedIndex
        ) { which ->
                val selectedVal = values[which]
                sharedPrefs?.edit()?.putString("pref_app_language", selectedVal)?.apply()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedVal))

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isAdded) {
                        reloadSettingsScreen()
                    }
                }, 500)
        }
    }

    private fun showWeekdayDialog() {
        val dayNames = JavaLocalDateFormatter(Locale.getDefault()).longWeekdayNames(DayOfWeek.SATURDAY)
        val dayValues = arrayOf("7", "1", "2", "3", "4", "5", "6")
        val currentVal = sharedPrefs?.getString("pref_first_weekday", "") ?: ""
        val currentInt = if (currentVal.isEmpty()) {
            getFirstWeekdayNumberAccordingToLocale().toString()
        } else {
            currentVal
        }
        val selectedIndex = dayValues.indexOf(currentInt)

        CustomDialogs.showSingleChoiceDialog(
            context = requireContext(),
            title = getString(R.string.first_day_of_the_week),
            options = dayNames.toList(),
            selectedIndex = selectedIndex
        ) { which ->
                val selectedVal = dayValues[which]
                sharedPrefs?.edit()?.putString("pref_first_weekday", selectedVal)?.apply()
                rebuildSettingsList()
        }
    }

    private fun showWidgetOpacityDialog() {
        val entries = resources.getStringArray(R.array.widget_opacity_entries)
        val values = resources.getStringArray(R.array.widget_opacity_values)
        val currentVal = sharedPrefs?.getString("pref_widget_opacity", "255") ?: "255"
        val selectedIndex = values.indexOf(currentVal)

        CustomDialogs.showSingleChoiceDialog(
            context = requireContext(),
            title = getString(R.string.widget_opacity_title),
            options = entries.toList(),
            selectedIndex = selectedIndex
        ) { which ->
                val selectedVal = values[which]
                sharedPrefs?.edit()?.putString("pref_widget_opacity", selectedVal)?.apply()
                widgetUpdater?.updateWidgets()
                rebuildSettingsList()
        }
    }

    private fun showDeveloperEditTextDialog(key: String, title: String) {
        val initialValue = sharedPrefs?.getString(key, "") ?: ""
        CustomDialogs.showInputDialog(
            context = requireContext(),
            title = title,
            initialValue = initialValue,
            hint = title
        ) { newValue ->
            sharedPrefs?.edit()?.putString(key, newValue)?.apply()
            if (key == "pref_sync_base_url" || key == "pref_sync_key") {
                prefs.isSyncBootstrapQueued = false
                prefs.isSyncBootstrapDone = false
                prefs.syncLastLogId = 0L
            }
            rebuildSettingsList()
        }
    }

    private fun showAccentColorPicker() {
        val themeSwitcher = AndroidThemeSwitcher(requireContext(), prefs)
        themeSwitcher.apply()
        val currentTheme = themeSwitcher.currentTheme
        val factory = ColorPickerDialogFactory(requireActivity())
        
        val currentValString = AccentColorManager.getAccentColorString(prefs)
        val currentPaletteColor = AccentColorManager.toPaletteColor(currentValString)

        val picker = factory.create(
            color = currentPaletteColor,
            theme = currentTheme,
            previewName = "uHabits Plus",
            defaultColor = PaletteColor(11)
        )
        picker.setListener { paletteColor ->
            val newAccentString = AccentColorManager.fromPaletteColor(paletteColor)
            AccentColorManager.setAccentColorString(prefs, newAccentString)
            rebuildSettingsList()
        }
        picker.show(childFragmentManager, "accentColorPicker")
    }

    private fun reloadSettingsScreen() {
        val intent = MainActivity.intent(requireContext(), MainDestination.SETTINGS)
        activity?.finish()
        activity?.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(intent)
    }

    private fun getBackupStatusSummaryText(): String {
        val status = backupStatusStore.load()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        return if (status.lastSuccessAt != null) {
            getString(
                R.string.backup_status_last_success,
                dateFormat.format(status.lastSuccessAt),
                formatSize(status.lastBackupSizeBytes ?: 0L)
            )
        } else {
            getString(R.string.backup_status_never)
        }
    }

    private fun getPublicBackupFolderSummaryText(uriString: String?): String {
        if (uriString == null) {
            return getString(R.string.no_public_backup_folder_selected)
        }
        if (!backupManager.isPublicBackupFolderAvailable()) {
            return getString(R.string.backup_external_folder_permission_lost)
        }
        val uri = Uri.parse(uriString)
        val path = fullPathFor(uri)
        return path ?: uriString
    }

    private fun getSyncStatusSummaryText(): String {
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        return when (prefs.syncStatus) {
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

    private fun actionHandler(): SettingsActionHandler =
        requireActivity() as SettingsActionHandler

    private fun launchPublicBackupFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, PUBLIC_BACKUP_REQUEST_CODE)
    }

    private fun showRestoreBackupDialog(source: BackupSource) {
        if (source == BackupSource.PUBLIC && !ensurePublicBackupFolderReady()) return

        val backups = when (source) {
            BackupSource.PRIVATE -> backupManager.listLocalBackups()
            BackupSource.PUBLIC -> backupManager.listPublicBackups()
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        val items = backups.map { it.name }
        val summaries = backups.map { "${dateFormat.format(it.modifiedAt)} • ${formatSize(it.sizeBytes)}" }
        val emptyMessageId = if (source == BackupSource.PUBLIC) {
            R.string.backup_restore_no_public_backups
        } else {
            R.string.backup_restore_no_backups
        }

        CustomDialogs.showSimpleListDialog(
            context = requireContext(),
            title = getString(R.string.restore_backup),
            items = items,
            summaries = summaries,
            emptyStateMessage = getString(emptyMessageId)
        ) { which ->
            showRestoreBackupConfirmation(backups[which])
        }
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
                        rebuildSettingsList()
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
        CustomDialogs.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.select_public_backup_folder),
            message = getString(messageId),
            isDestructive = false,
            positiveText = getString(R.string.choose_folder)
        ) {
            launchPublicBackupFolderPicker()
        }
    }

    private fun showRestoreBackupConfirmation(entry: BackupEntry) {
        CustomDialogs.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.restore_backup),
            message = getString(R.string.restore_backup_warning),
            isDestructive = true,
            positiveText = getString(R.string.restore_backup_confirm)
        ) {
            performRestore(entry)
        }
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
        CustomDialogs.showCredentialsDialog(
            context = requireContext(),
            title = getString(R.string.sync_sign_in_dialog_title),
            primaryHint = getString(R.string.sync_sign_in_email_hint),
            secondaryHint = getString(R.string.sync_sign_in_password_hint),
            initialPrimaryValue = prefs.syncAccountEmail ?: "",
            positiveText = getString(R.string.sync_sign_in)
        ) { email, password ->
            performSignIn(email, password)
        }
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
                    rebuildSettingsList()
                    performSyncNow(allowAfterReview = false)
                }
                is SyncRunResult.Failure -> {
                    Toast.makeText(requireContext(), result.userMessage, Toast.LENGTH_LONG).show()
                    rebuildSettingsList()
                }
                else -> Unit
            }
        }
    }

    private fun performSignOut() {
        viewLifecycleOwner.lifecycleScope.launch {
            val hasPending = withContext(Dispatchers.IO) {
                syncCoordinator.hasPendingLocalChanges()
            }
            if (hasPending) {
                Toast.makeText(requireContext(), "Отправка несохраненных изменений перед выходом...", Toast.LENGTH_SHORT).show()
                val syncResult = syncCoordinator.runSync(manual = true)
                if (syncResult is SyncRunResult.Failure) {
                    CustomDialogs.showConfirmDialog(
                        context = requireContext(),
                        title = getString(R.string.sync_sign_out_warning_title),
                        message = getString(R.string.sync_sign_out_warning_message),
                        isDestructive = true,
                        positiveText = getString(R.string.sync_sign_out)
                    ) {
                        executeSignOut()
                    }
                    return@launch
                }
            }
            executeSignOut()
        }
    }

    private fun executeSignOut() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                syncCoordinator.signOut()
            }.getOrElse {
                SyncRunResult.Failure(
                    "Синхронизация сейчас недоступна.",
                    it.message ?: it::class.simpleName
                )
            }
            if (isAdded) {
                if (result is SyncRunResult.Success) {
                    Toast.makeText(requireContext(), R.string.sync_result_signed_out, Toast.LENGTH_LONG).show()
                } else if (result is SyncRunResult.Failure) {
                    Toast.makeText(requireContext(), result.userMessage, Toast.LENGTH_LONG).show()
                }
                rebuildSettingsList()
            }
        }
    }

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
            syncCoordinator.confirmSyncReview()
            rebuildSettingsList()
            performSyncNow(allowAfterReview = true)
        }
    }

    private fun performSyncNow(allowAfterReview: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            rebuildSettingsList()
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
                    reloadVisibleHabitScreens("manual_sync_success")
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
            rebuildSettingsList()
        }
    }

    private fun exportSyncDiagnostics() {
        CoroutineScope(Dispatchers.Main).launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val report = syncCoordinator.buildDiagnosticsReport(
                        appVersionName = BuildConfig.VERSION_NAME,
                        appVersionCode = BuildConfig.VERSION_CODE
                    )
                    val file = File(
                        requireContext().cacheDir,
                        "sync-diagnostics-${System.currentTimeMillis()}.json"
                    )
                    file.writeText(report, Charsets.UTF_8)
                    file
                }
            }
            result.fold(
                onSuccess = { shareSyncDiagnostics(it) },
                onFailure = {
                    Log.e("SettingsFragment", "Failed to export sync diagnostics", it)
                    Toast.makeText(
                        requireContext(),
                        R.string.sync_diagnostics_export_error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun shareSyncDiagnostics(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "org.isoron.uhabits.plus",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity?.startActivitySafely(
            Intent.createChooser(intent, getString(R.string.sync_export_diagnostics_title))
        )
    }

    private fun refreshScreensForDiagnostics() {
        reloadVisibleHabitScreens("debug_refresh_screens")
        Toast.makeText(requireContext(), R.string.sync_screens_refreshed, Toast.LENGTH_SHORT).show()
    }

    private fun reloadVisibleHabitScreens(reason: String) {
        (activity as? MainActivity)?.reloadVisibleHabitScreens(reason)
            ?: syncCoordinator.refreshLocalViewsForDiagnostics("${reason}_no_main_activity")
    }

    private fun showSeedConfirmationDialog(isReset: Boolean) {
        val title = if (isReset) getString(R.string.demo_data_confirm_reset_title) else getString(R.string.demo_data_confirm_title)
        val message = if (isReset) getString(R.string.demo_data_confirm_reset_message) else getString(R.string.demo_data_confirm_message)
        CustomDialogs.showConfirmDialog(
            context = requireContext(),
            title = title,
            message = message,
            isDestructive = isReset
        ) {
            val habitsApp = requireContext().applicationContext as HabitsApplication
            val component = habitsApp.component
            
            Toast.makeText(requireContext(), R.string.demo_data_generating, Toast.LENGTH_SHORT).show()
            
            CoroutineScope(Dispatchers.Main).launch {
                component.syncCoordinator.isAutoSyncPaused = true
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
                    } finally {
                        component.syncCoordinator.isAutoSyncPaused = false
                    }
                }
                if (success) {
                    Toast.makeText(requireContext(), R.string.demo_data_generated_success, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), R.string.could_not_import, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showGlobalStatisticsStartDateDialog() {
        val options = listOf(
            getString(R.string.today),
            getString(R.string.this_monday),
            getString(R.string.next_monday),
            getString(R.string.select_date)
        )
        CustomDialogs.showSingleChoiceDialog(
            context = requireContext(),
            title = getString(R.string.count_statistics_from_date),
            options = options,
            selectedIndex = -1,
            neutralText = getString(R.string.clear),
            onNeutral = { applyGlobalStatisticsStart(null) }
        ) { which ->
            when (which) {
                0 -> applyGlobalStatisticsStart(getToday())
                1 -> applyGlobalStatisticsStart(thisMonday())
                2 -> applyGlobalStatisticsStart(thisMonday().plus(7))
                else -> showDatePicker { date -> applyGlobalStatisticsStart(date) }
            }
        }
    }

    private fun applyGlobalStatisticsStart(date: LocalDate?) {
        val app = requireContext().applicationContext as HabitsApplication
        app.component.commandRunner.run(
            SetGlobalStatisticsStartDateCommand(app.component.habitList, date)
        )
    }

    private fun showHardResetStatisticsDialog() {
        val message = getString(R.string.delete_entries_forever) + "\n\n" +
                getString(R.string.action_cannot_be_undone) + "\n" +
                getString(R.string.reset_statistics_backup_hint)
        CustomDialogs.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.reset_statistics),
            message = message,
            isDestructive = true
        ) {
            val app = requireContext().applicationContext as HabitsApplication
            app.component.commandRunner.run(ClearAllEntriesCommand(app.component.habitList))
        }
    }

    private fun showDatePicker(callback: (LocalDate?) -> Unit) {
        val today = getToday()
        CustomDialogs.showDatePickerDialog(
            context = requireContext(),
            title = getString(R.string.select_date),
            initialDate = today
        ) { date ->
            callback(date)
        }
    }

    private fun thisMonday(): LocalDate {
        val firstWeekday = DayOfWeek.values()[getFirstWeekdayNumberAccordingToLocale() - 1]
        return getToday().startOfWeek(firstWeekday)
    }

    private fun showStartDestinationDialog() {
        val entries = resources.getStringArray(R.array.pref_start_destination_entries)
        val values = resources.getStringArray(R.array.pref_start_destination_values)
        val currentVal = prefs.startDestinationName
        val selectedIndex = values.indexOf(currentVal)

        CustomDialogs.showSingleChoiceDialog(
            context = requireContext(),
            title = getString(R.string.pref_start_destination_title),
            options = entries.toList(),
            selectedIndex = selectedIndex
        ) { which ->
            prefs.startDestinationName = values[which]
            rebuildSettingsList()
        }
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
            "file" -> File(uri.path!!).absolutePath
            else -> null
        }
    }

    companion object {
        private const val RINGTONE_REQUEST_CODE = 1
        private const val PUBLIC_BACKUP_REQUEST_CODE = 2
    }
}
