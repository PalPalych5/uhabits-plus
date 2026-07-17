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
import android.app.NotificationManager
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
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
import org.isoron.uhabits.activities.habits.show.timer.PomodoroCompletionNotifier
import org.isoron.uhabits.activities.habits.show.timer.TimerForegroundService
import org.isoron.uhabits.core.commands.ClearAllEntriesCommand
import org.isoron.uhabits.core.commands.SetGlobalStatisticsStartDateCommand
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.notifications.RingtoneManager
import org.isoron.uhabits.utils.StyledResources
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
import kotlin.math.min
import kotlin.system.exitProcess

open class SettingsFragment : Fragment(), OnSharedPreferenceChangeListener {
    private var sharedPrefs: SharedPreferences? = null
    private var ringtoneManager: RingtoneManager? = null
    private lateinit var pomodoroCompletionNotifier: PomodoroCompletionNotifier
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
    private var visibleItems: List<SettingItem> = emptyList()
    private var manualSyncInProgress = false
    private var statisticsDialog: AlertDialog? = null
    private val useCompactTopInset: Boolean
        get() = arguments?.getBoolean(ARG_COMPACT_TOP_INSET, false) ?: false

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
            pomodoroCompletionNotifier = appContext.component.pomodoroCompletionNotifier
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
        view.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val maxWidth = resources.getDimensionPixelSize(R.dimen.settings_content_max_width)
            val targetWidth = min(right - left, maxWidth)
            when (val params = recyclerView.layoutParams) {
                is FrameLayout.LayoutParams -> {
                    if (params.width != targetWidth || params.gravity != android.view.Gravity.CENTER_HORIZONTAL) {
                        params.width = targetWidth
                        params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                        recyclerView.layoutParams = params
                    }
                }
                is LinearLayout.LayoutParams -> {
                    if (params.width != targetWidth || params.gravity != android.view.Gravity.CENTER_HORIZONTAL) {
                        params.width = targetWidth
                        params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                        recyclerView.layoutParams = params
                    }
                }
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.itemAnimator = SettingsItemAnimator { motionEnabled() }
        val contentTopSpacing = ((if (useCompactTopInset) 4 else 8) * resources.displayMetrics.density).toInt()
        val contentBottomSpacing = recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { recycler, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomInset = if (useCompactTopInset) 0 else systemBarsInsets.bottom
            recycler.setPadding(
                recycler.paddingLeft,
                contentTopSpacing,
                recycler.paddingRight,
                bottomInset + contentBottomSpacing
            )
            insets
        }

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

    override fun onDestroyView() {
        statisticsDialog?.dismiss()
        statisticsDialog = null
        super.onDestroyView()
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
        if (!isAdded || !::adapter.isInitialized) return
        val sections = buildList {
            add(buildAppearanceSection())
            add(buildHabitsSection())
            add(buildNotificationsSection())
            add(buildPomodoroSection())
            add(buildStatisticsSection())
            add(buildDataSection())
            add(buildSyncSection())
            add(buildHelpSection())
            if (prefs.isDeveloper) add(buildDeveloperSection())
        }
        val detailId = detailSectionId()
        visibleItems = if (detailId == null) {
            SettingsListComposer.composeHome(
                sections = sections,
                accessibilityDescription = { section ->
                    getString(R.string.settings_section_open_accessibility, section.title, section.summary)
                },
                onSectionClick = { (parentFragment as? SettingsNavigationController)?.openSettingsSection(it) }
            )
        } else {
            val section = sections.firstOrNull { it.id == detailId }
            if (section == null) {
                (parentFragment as? SettingsNavigationController)?.popSettingsDetail()
                return
            } else {
                SettingsListComposer.composeDetail(section)
            }
        }
        adapter.updateItems(visibleItems)
    }

    protected fun detailSectionId(): SettingsSectionId? = arguments
        ?.getString(ARG_DETAIL_SECTION)
        ?.let { runCatching { SettingsSectionId.valueOf(it) }.getOrNull() }

    protected fun saveListScrollState(): android.os.Parcelable? =
        if (::recyclerView.isInitialized) recyclerView.layoutManager?.onSaveInstanceState() else null

    protected fun restoreListScrollState(state: android.os.Parcelable?) {
        if (state == null || !::recyclerView.isInitialized) return
        recyclerView.post { recyclerView.layoutManager?.onRestoreInstanceState(state) }
    }

    private fun motionEnabled(): Boolean = !prefs.isConfettiAnimationDisabled &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())

    private fun buildAppearanceSection(): SettingsSectionDefinition {
        val languageTag = sharedPrefs?.getString("pref_app_language", "ru-RU") ?: "ru-RU"
        val languageValues = resources.getStringArray(R.array.pref_app_language_values)
        val languageEntries = resources.getStringArray(R.array.pref_app_language_entries)
        val languageEntry = languageEntries.getOrNull(languageValues.indexOf(languageTag))
            ?: Locale.forLanguageTag(languageTag).getDisplayName(Locale.getDefault())
        val weekday = prefs.firstWeekday.daysSinceSunday + 1
        val dayNames = JavaLocalDateFormatter(Locale.getDefault()).longWeekdayNames(DayOfWeek.SATURDAY)
        val opacityValue = sharedPrefs?.getString("pref_widget_opacity", "255") ?: "255"
        val opacityValues = resources.getStringArray(R.array.widget_opacity_values)
        val opacityEntries = resources.getStringArray(R.array.widget_opacity_entries)
        val themeEntries = resources.getStringArray(R.array.pref_theme_entries)
        val themeEntry = when {
            prefs.theme == 0 -> themeEntries[0]
            prefs.theme == 2 -> themeEntries[1]
            prefs.isPureBlackEnabled -> "AMOLED"
            else -> themeEntries[2]
        }
        return SettingsSectionModel.fromItems(
            SettingsSectionId.APPEARANCE,
            R.drawable.ic_settings_theme,
            getString(R.string.appearance),
            getString(
                R.string.settings_summary_appearance,
                themeEntry,
                AccentColorManager.getAccentColorName(requireContext(), prefs)
            ),
            listOf(
                subsection("appearance_main", getString(R.string.settings_subsection_appearance_main)),
                SettingItem.SegmentedTheme(
                    "pref_theme",
                    R.drawable.ic_settings_theme,
                    getString(R.string.theme),
                    prefs.theme,
                    prefs.isPureBlackEnabled,
                    { theme, pureBlack ->
                        prefs.theme = theme
                        prefs.isPureBlackEnabled = pureBlack
                        (requireActivity() as? MainActivity)?.recreateForThemeChange()
                            ?: requireActivity().recreate()
                    },
                    childPresentation()
                ),
                navigation("pref_app_language", R.drawable.ic_settings_globe, getString(R.string.language), languageEntry) { showLanguageDialog() },
                navigation("pref_first_weekday", R.drawable.ic_settings_calendar, getString(R.string.first_day_of_the_week), dayNames[weekday % 7]) { showWeekdayDialog() },
                SettingItem.ColorPicker(
                    "pref_accent_color",
                    R.drawable.ic_settings_droplet,
                    getString(R.string.accent_color),
                    AccentColorManager.getAccentColorName(requireContext(), prefs),
                    AccentColorManager.getAccentColorString(prefs),
                    { showAccentColorPicker() },
                    childPresentation()
                ),
                subsection("appearance_interface", getString(R.string.settings_subsection_interface)),
                settingSwitch(
                    "pref_disable_animation",
                    R.drawable.ic_settings_animation,
                    getString(R.string.pref_animations_title),
                    getString(R.string.pref_animations_description),
                    prefs.isConfettiAnimationDisabled
                ) { prefs.isConfettiAnimationDisabled = it },
                navigation(
                    "pref_widget_opacity",
                    R.drawable.ic_settings_opacity,
                    getString(R.string.widget_opacity_title),
                    opacityEntries.getOrNull(opacityValues.indexOf(opacityValue)) ?: opacityValue
                ) { showWidgetOpacityDialog() },
                navigation("pref_card_rounding", R.drawable.ic_settings_corners, getString(R.string.pref_card_rounding_title), buildCardRoundingSummary()) { showCardRoundingDialog() },
                settingSwitch(
                    "pref_show_habit_card_borders",
                    R.drawable.ic_settings_layout_list,
                    getString(R.string.settings_show_habit_card_borders_title),
                    getString(R.string.settings_show_habit_card_borders_summary),
                    prefs.showHabitCardBorders
                ) { prefs.showHabitCardBorders = it }
            )
        )
    }

    private fun buildHabitsSection(): SettingsSectionDefinition {
        val summary = when {
            prefs.isHabitSpheresEnabled && prefs.isDayTiersEnabled -> getString(R.string.settings_summary_habits_both)
            prefs.isHabitSpheresEnabled -> getString(R.string.settings_summary_habits_spheres)
            prefs.isDayTiersEnabled -> getString(R.string.settings_summary_habits_tiers)
            else -> getString(R.string.settings_summary_habits_off)
        }
        return SettingsSectionModel.fromItems(
            SettingsSectionId.HABITS,
            R.drawable.ic_settings_spheres,
            getString(R.string.settings_section_habits_day),
            summary,
            buildList {
                add(subsection("habits_spheres", getString(R.string.settings_subsection_spheres)))
                add(settingSwitch("pref_enable_habit_spheres", R.drawable.ic_settings_spheres, getString(R.string.pref_enable_habit_spheres_title), getString(R.string.pref_enable_habit_spheres_summary), prefs.isHabitSpheresEnabled) { prefs.isHabitSpheresEnabled = it })
                if (prefs.isHabitSpheresEnabled) add(navigation("configureSpheres", R.drawable.ic_settings_configure_spheres, getString(R.string.configure_spheres), getString(R.string.configure_spheres_summary), showIcon = true) { actionHandler().onSettingsAction(SettingsAction.MANAGE_SPHERES) })
                add(subsection("habits_tiers", getString(R.string.settings_subsection_day_tiers)))
                add(settingSwitch("pref_enable_day_tiers", R.drawable.ic_settings_tiers, getString(R.string.pref_enable_day_tiers_title), getString(R.string.pref_enable_day_tiers_summary), prefs.isDayTiersEnabled) { prefs.isDayTiersEnabled = it })
                if (prefs.isDayTiersEnabled) add(navigation("pref_day_tier_sort_order", R.drawable.ic_settings_arrows_sort, getString(R.string.pref_day_tier_sort_order_title), dayTierSortOrderSummary()) { showDayTierSortOrderDialog() })
                add(subsection("habits_behavior", getString(R.string.settings_subsection_behavior)))
                add(settingSwitch("pref_habit_group_separators", R.drawable.ic_settings_separator_horizontal, getString(R.string.pref_habit_group_separators_title), null, prefs.areHabitGroupSeparatorsEnabled) { prefs.areHabitGroupSeparatorsEnabled = it })
                add(settingSwitch("pref_short_toggle", R.drawable.ic_settings_check, getString(R.string.settings_short_press_title), getString(R.string.settings_short_press_summary), prefs.isShortToggleEnabled) { prefs.isShortToggleEnabled = it })
                add(settingSwitch("pref_checkmark_reverse_order", R.drawable.ic_settings_reverse, getString(R.string.reverse_days), getString(R.string.reverse_days_description), prefs.isCheckmarkSequenceReversed) { prefs.isCheckmarkSequenceReversed = it })
                add(settingSwitch("pref_skip_enabled", R.drawable.ic_settings_fast_forward, getString(R.string.pref_skip_title), getString(R.string.settings_skip_summary_short), prefs.isSkipEnabled) { prefs.isSkipEnabled = it })
                add(settingSwitch("pref_unknown_enabled", R.drawable.ic_settings_question, getString(R.string.pref_unknown_title), getString(R.string.settings_unknown_summary_short), prefs.areQuestionMarksEnabled) { prefs.areQuestionMarksEnabled = it })
                add(
                    settingSwitch(
                        "pref_midnight_delay",
                        R.drawable.ic_settings_clock,
                        getString(R.string.pref_midnight_delay_title),
                        getString(
                            R.string.settings_midnight_summary_short,
                            SettingsDateFormatter.formatHour(prefs.midnightDelayHours, Locale.getDefault())
                        ),
                        prefs.isMidnightDelayEnabled
                    ) { prefs.isMidnightDelayEnabled = it }
                )
                add(subsection("habits_archive", getString(R.string.settings_subsection_archive)))
                add(navigation("openArchive", R.drawable.ic_settings_archive, getString(R.string.open_archive), getString(R.string.open_archive_summary), showIcon = true) { actionHandler().onSettingsAction(SettingsAction.OPEN_ARCHIVE) })
            }
        )
    }

    private fun buildNotificationsSection() = SettingsSectionModel.fromItems(
        SettingsSectionId.NOTIFICATIONS,
        R.drawable.ic_settings_notification_ringing,
        getString(R.string.settings_section_notifications),
        notificationOverviewSummary(),
        listOf(
            subsection("notifications_general", getString(R.string.settings_subsection_habit_reminders)),
            settingSwitch("pref_sticky_notifications", R.drawable.ic_settings_notification, getString(R.string.sticky_notifications), getString(R.string.sticky_notifications_description), prefs.shouldMakeNotificationsSticky()) { prefs.setNotificationsSticky(it) },
            navigation("reminderCustomize", R.drawable.ic_settings_bell_cog, getString(R.string.settings_reminder_channel_title), notificationChannelSummary(), showIcon = true) { openReminderChannelSettings() },
            navigation("reminderTest", R.drawable.ic_settings_bell_check, getString(R.string.settings_reminder_test_title), getString(R.string.settings_reminder_test_summary)) { testHabitReminder() },
            subsection("notifications_pomodoro", getString(R.string.settings_subsection_pomodoro_completion)),
            settingSwitch("pref_pomodoro_focus_alert", R.drawable.ic_settings_pomodoro_focus, getString(R.string.pref_pomodoro_focus_alert_title), null, prefs.isPomodoroFocusAlertEnabled) { prefs.isPomodoroFocusAlertEnabled = it },
            settingSwitch("pref_pomodoro_break_alert", R.drawable.ic_settings_pomodoro_break, getString(R.string.pref_pomodoro_break_alert_title), null, prefs.isPomodoroBreakAlertEnabled) { prefs.isPomodoroBreakAlertEnabled = it },
            navigation("pomodoroAlertChannel", R.drawable.ic_settings_clock_cog, getString(R.string.settings_pomodoro_channel_title), pomodoroChannelSummary(), showIcon = true) { openPomodoroChannelSettings() },
            navigation("pomodoroTestAlert", R.drawable.ic_settings_clock_check, getString(R.string.settings_pomodoro_test_title), getString(R.string.settings_pomodoro_test_summary)) { testPomodoroAlert() },
            subsection("notifications_delivery", getString(R.string.settings_subsection_timer_timing)),
            navigation("pomodoroProgressChannel", R.drawable.ic_settings_lock, getString(R.string.pref_pomodoro_progress_channel_title), getString(R.string.pref_pomodoro_progress_channel_summary)) { openPomodoroProgressChannelSettings() },
            navigation("pomodoroExactAlarm", R.drawable.ic_settings_clock, getString(R.string.pref_pomodoro_exact_alarm_title), pomodoroExactAlarmSummary()) { openExactAlarmSettings() }
        )
    )

    private fun buildPomodoroSection(): SettingsSectionDefinition {
        return SettingsSectionModel.fromItems(
            SettingsSectionId.POMODORO,
            R.drawable.ic_settings_pomodoro_tomato,
            getString(R.string.pref_pomodoro_category),
            getString(
                R.string.settings_summary_pomodoro,
                prefs.pomodoroDefaultFocusMinutes,
                prefs.pomodoroDefaultBreakMinutes
            ),
            listOf(
                subsection("pomodoro_intervals", getString(R.string.settings_subsection_intervals)),
                navigation("pref_pomodoro_default_focus_minutes", R.drawable.ic_settings_pomodoro_focus, getString(R.string.pref_pomodoro_default_focus_title), getString(R.string.pomodoro_minutes_short, prefs.pomodoroDefaultFocusMinutes)) { showPomodoroDefaultMinutesDialog(false) },
                navigation("pref_pomodoro_default_break_minutes", R.drawable.ic_settings_pomodoro_break, getString(R.string.pref_pomodoro_default_break_title), getString(R.string.pomodoro_minutes_short, prefs.pomodoroDefaultBreakMinutes)) { showPomodoroDefaultMinutesDialog(true) },
                subsection("pomodoro_automation", getString(R.string.settings_subsection_automation)),
                settingSwitch("pref_pomodoro_auto_switch", R.drawable.ic_settings_pomodoro_auto, getString(R.string.pref_pomodoro_auto_switch_title), getString(R.string.pref_pomodoro_auto_switch_summary), prefs.isPomodoroAutoSwitch) { prefs.isPomodoroAutoSwitch = it }
            )
        )
    }

    private fun buildStatisticsSection(): SettingsSectionDefinition {
        val start = (requireContext().applicationContext as HabitsApplication)
            .component.habitList.globalStatisticsStartDate
        val summary = start?.let {
            getString(
                R.string.settings_statistics_from_date,
                SettingsDateFormatter.formatDate(it, Locale.getDefault())
            )
        }
            ?: getString(R.string.settings_statistics_all_time)
        return SettingsSectionModel.fromItems(
            SettingsSectionId.STATISTICS,
            R.drawable.ic_settings_statistics,
            getString(R.string.pref_statistics_title),
            summary,
            emptyList(),
            homeAction = { showGlobalStatisticsStartDateDialog() }
        )
    }

    private fun buildDataSection(): SettingsSectionDefinition {
        val backupSummary = getBackupStatusSummaryText()
        val publicFolder = sharedPrefs?.getString("publicBackupFolder", null)
        return SettingsSectionModel.fromItems(
            SettingsSectionId.DATA,
            R.drawable.ic_settings_backup_history,
            getString(R.string.pref_data_backup_title),
            backupSummary,
            buildList {
                add(subsection("data_status", getString(R.string.settings_subsection_last_backup)))
                add(SettingItem.Status("backupStatus", R.drawable.ic_settings_backup_history, getString(R.string.backup_status_title), backupSummary, false, childPresentation()))
                add(subsection("data_create", getString(R.string.settings_subsection_create_backup)))
                add(navigation("exportDB", R.drawable.ic_settings_backup_upload, getString(R.string.backup_now), getString(R.string.export_full_backup_summary), showIcon = true) { actionHandler().onSettingsAction(SettingsAction.EXPORT_DATABASE) })
                add(navigation("publicBackupFolder", R.drawable.ic_settings_folder, getString(R.string.select_public_backup_folder), getPublicBackupFolderSummaryText(publicFolder), showIcon = true) { launchPublicBackupFolderPicker() })
                add(navigation("backupToPublicFolder", R.drawable.ic_settings_folder_upload, getString(R.string.backup_to_public_folder), getString(R.string.backup_to_public_folder_summary), showIcon = true) { performPublicBackup() })
                add(subsection("data_transfer", getString(R.string.settings_subsection_import_export)))
                add(navigation("exportCSV", R.drawable.ic_settings_export_csv, getString(R.string.export_to_csv), getString(R.string.export_as_csv_summary), showIcon = true) { actionHandler().onSettingsAction(SettingsAction.EXPORT_CSV) })
                add(navigation("importData", R.drawable.ic_settings_import, getString(R.string.import_data), getString(R.string.import_data_summary), showIcon = true) { actionHandler().onSettingsAction(SettingsAction.IMPORT_DATA) })
                add(subsection("data_restore", getString(R.string.settings_subsection_restore)))
                add(navigation("restoreBackup", R.drawable.ic_settings_database_down, getString(R.string.restore_backup), getString(R.string.restore_backup_local_summary), showIcon = true) { showRestoreBackupDialog() })
                add(subsection("data_delete", getString(R.string.settings_subsection_delete_data), destructive = true))
                add(navigation("hardResetStatistics", R.drawable.ic_settings_trash, getString(R.string.reset_statistics), getString(R.string.delete_entries_forever), showIcon = true, danger = true) { showHardResetStatisticsDialog() })
            }
        )
    }

    private fun buildSyncSection(): SettingsSectionDefinition {
        val email = runCatching { syncCoordinator.currentAccountEmail() }.getOrNull()
        val syncing = manualSyncInProgress || prefs.syncStatus == "syncing"
        val stableStatusSummary = getSyncStatusSummaryText(preserveLastSuccessWhileSyncing = syncing)
        return SettingsSectionModel.fromItems(
            SettingsSectionId.SYNC,
            R.drawable.ic_settings_cloud_sync,
            getString(R.string.sync_title),
            stableStatusSummary,
            buildList {
                add(subsection("sync_account", getString(R.string.settings_subsection_account_status)))
                add(
                    SettingItem.Status(
                        key = "syncStatus",
                        iconRes = R.drawable.ic_settings_history,
                        title = getString(R.string.sync_status_title),
                        summary = stableStatusSummary,
                        showProgress = syncing,
                        presentation = childPresentation(),
                        reserveProgressSpace = true
                    )
                )
                add(settingSwitch("pref_sync_enabled", R.drawable.ic_settings_cloud_sync, getString(R.string.sync_enable_title), getString(R.string.sync_enable_summary), prefs.isSyncEnabled) { prefs.isSyncEnabled = it })
                add(
                    SettingItem.Status(
                        "syncAccount",
                        R.drawable.ic_settings_account,
                        getString(R.string.sync_account_title),
                        email?.let { getString(R.string.sync_account_connected, it) }
                            ?: getString(R.string.sync_signed_out),
                        false,
                        childPresentation()
                    )
                )
                if (email == null) {
                    add(navigation("syncSignIn", R.drawable.ic_settings_login, getString(R.string.sync_sign_in), getString(R.string.sync_sign_in_summary), showIcon = true) { showSyncSignInDialog() })
                } else {
                    add(navigation("syncSignOut", R.drawable.ic_settings_logout, getString(R.string.sync_sign_out), getString(R.string.sync_sign_out_summary), showIcon = true) { performSignOut() })
                }
                add(subsection("sync_actions", getString(R.string.settings_subsection_actions)))
                add(navigation(
                    "syncNow",
                    R.drawable.ic_settings_sync,
                    getString(R.string.sync_now),
                    getString(R.string.sync_now_summary),
                    showIcon = true,
                    enabled = !syncing,
                    busy = syncing
                ) { if (prefs.isSyncReviewRequired) showSyncReviewDialog() else performSyncNow(false) })
                if (prefs.isSyncReviewRequired) {
                    add(navigation("syncReview", R.drawable.ic_settings_git_merge, getString(R.string.sync_review_required_title), getString(R.string.sync_review_required_summary), showIcon = true) { showSyncReviewDialog() })
                }
            }
        )
    }

    private fun buildHelpSection() = SettingsSectionModel.fromItems(
        SettingsSectionId.HELP,
        R.drawable.ic_settings_lifebuoy,
        getString(R.string.settings_section_help_app),
        getString(R.string.version_n, BuildConfig.VERSION_NAME),
        listOf(
            navigation("help", R.drawable.ic_settings_lifebuoy, getString(R.string.help), showIcon = true) { activity?.startActivitySafely(intentFactory.viewFAQ(requireContext())) },
            navigation("rateApp", R.drawable.ic_settings_rate, getString(R.string.pref_rate_this_app), showIcon = true) {
                activity?.startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.playStoreURL))))
            },
            navigation("about", R.drawable.ic_settings_info, getString(R.string.about), getString(R.string.version_n, BuildConfig.VERSION_NAME), showIcon = true) {
                startActivity(intentFactory.startAboutActivity(requireContext()))
            }
        )
    )

    private fun buildDeveloperSection() = SettingsSectionModel.fromItems(
        SettingsSectionId.DEVELOPER,
        R.drawable.ic_settings_terminal,
        getString(R.string.pref_developer_section_title),
        getString(R.string.settings_summary_developer),
        listOf(
            settingSwitch("pref_developer", R.drawable.ic_settings_terminal, getString(R.string.developer_mode_title), null, prefs.isDeveloper) { prefs.isDeveloper = it },
            subsection("developer_connection", getString(R.string.settings_subsection_connection)),
            navigation("pref_sync_base_url", R.drawable.ic_settings_link, getString(R.string.supabase_url_title), getString(R.string.supabase_url_summary)) { showDeveloperEditTextDialog("pref_sync_base_url", getString(R.string.supabase_url_title)) },
            navigation("pref_sync_key", R.drawable.ic_settings_key, getString(R.string.supabase_anon_key_title), getString(R.string.supabase_anon_key_summary)) { showDeveloperEditTextDialog("pref_sync_key", getString(R.string.supabase_anon_key_title)) },
            subsection("developer_diagnostics", getString(R.string.settings_subsection_diagnostics)),
            navigation("exportSyncDiagnostics", R.drawable.ic_settings_report_analytics, getString(R.string.sync_export_diagnostics_title), getString(R.string.sync_export_diagnostics_summary), showIcon = true) { exportSyncDiagnostics() },
            navigation("bugReport", R.drawable.ic_settings_bug, getString(R.string.generate_bug_report), getString(R.string.generate_bug_report_summary), showIcon = true) { actionHandler().onSettingsAction(SettingsAction.BUG_REPORT) }
        )
    )

    private fun childPresentation(showIcon: Boolean = true, enabled: Boolean = true) =
        SettingPresentation(showIcon = showIcon, enabled = enabled)

    private fun subsection(key: String, title: String, destructive: Boolean = false) =
        SettingItem.Subsection(key, title, destructive, childPresentation())

    private fun navigation(
        key: String,
        iconRes: Int,
        title: String,
        summary: String? = null,
        showIcon: Boolean = true,
        enabled: Boolean = true,
        busy: Boolean = false,
        danger: Boolean = false,
        onClick: (() -> Unit)?
    ) = SettingItem.Navigation(
        key = key,
        iconRes = SettingsIconRegistry.resolve(key, iconRes),
        title = title,
        summary = summary,
        isDanger = danger,
        onClick = onClick,
        isBusy = busy,
        presentation = childPresentation(showIcon, enabled)
    )

    private fun settingSwitch(
        key: String,
        iconRes: Int,
        title: String,
        summary: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) = SettingItem.Switch(
        key,
        SettingsIconRegistry.resolve(key, iconRes),
        title,
        summary,
        checked,
        onCheckedChange,
        childPresentation()
    )

    private fun notificationChannelSummary(): String {
        if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            return getString(R.string.settings_notifications_off)
        }
        val channel = requireContext().getSystemService(NotificationManager::class.java)
            .getNotificationChannel(org.isoron.uhabits.core.ui.NotificationTray.REMINDERS_CHANNEL_ID)
            ?: return getString(R.string.settings_notifications_ready)
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return getString(R.string.settings_notification_channel_off)
        }
        return getString(
            when {
                channel.sound != null && channel.shouldVibrate() -> R.string.pref_pomodoro_alert_sound_vibration
                channel.sound != null -> R.string.pref_pomodoro_alert_sound_only
                channel.shouldVibrate() -> R.string.pref_pomodoro_alert_vibration_only
                else -> R.string.pref_pomodoro_alert_silent
            }
        )
    }

    private fun notificationOverviewSummary(): String =
        if (NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            getString(R.string.settings_notifications_overview)
        } else {
            getString(R.string.settings_notifications_off)
        }

    private fun showPomodoroDefaultMinutesDialog(isBreak: Boolean) {
        val initialValue = if (isBreak) {
            prefs.pomodoroDefaultBreakMinutes.toString()
        } else {
            prefs.pomodoroDefaultFocusMinutes.toString()
        }
        val title = if (isBreak) {
            getString(R.string.pref_pomodoro_default_break_title)
        } else {
            getString(R.string.pref_pomodoro_default_focus_title)
        }
        CustomDialogs.showInputDialog(
            context = requireContext(),
            title = title,
            initialValue = initialValue,
            inputType = android.text.InputType.TYPE_CLASS_NUMBER,
            hint = title
        ) { newValue ->
            val minutes = newValue.toIntOrNull()
            val minLimit = 1
            val maxLimit = if (isBreak) 60 else 180
            if (minutes != null && minutes in minLimit..maxLimit) {
                if (isBreak) {
                    prefs.pomodoroDefaultBreakMinutes = minutes
                } else {
                    prefs.pomodoroDefaultFocusMinutes = minutes
                }
                rebuildSettingsList()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.pref_pomodoro_invalid_range, minLimit, maxLimit),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pomodoroChannelSummary(): String {
        val health = pomodoroCompletionNotifier.health()
        return when {
            !health.notificationsEnabled -> getString(R.string.pref_pomodoro_alert_notifications_off)
            !health.channelEnabled -> getString(R.string.pref_pomodoro_alert_channel_off)
            health.hasSound && health.hasVibration -> getString(R.string.pref_pomodoro_alert_sound_vibration)
            health.hasSound -> getString(R.string.pref_pomodoro_alert_sound_only)
            health.hasVibration -> getString(R.string.pref_pomodoro_alert_vibration_only)
            else -> getString(R.string.pref_pomodoro_alert_silent)
        }
    }

    private fun pomodoroExactAlarmSummary(): String = if (pomodoroCompletionNotifier.health().exactAlarmsEnabled) {
        getString(R.string.pref_pomodoro_exact_alarm_enabled)
    } else {
        getString(R.string.pref_pomodoro_exact_alarm_disabled)
    }

    private fun openPomodoroChannelSettings() {
        pomodoroCompletionNotifier.ensureChannel()
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, PomodoroCompletionNotifier.CHANNEL_ID)
        }
        runCatching { startActivity(intent) }
    }

    private fun openReminderChannelSettings() {
        org.isoron.uhabits.notifications.AndroidNotificationTray.createAndroidNotificationChannel(requireContext())
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, org.isoron.uhabits.core.ui.NotificationTray.REMINDERS_CHANNEL_ID)
        }
        runCatching { startActivity(intent) }
    }

    private fun openPomodoroProgressChannelSettings() {
        TimerForegroundService.ensureNotificationChannel(requireContext())
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, TimerForegroundService.CHANNEL_ID)
        }
        runCatching { startActivity(intent) }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        runCatching { startActivity(intent) }
    }

    private fun testPomodoroAlert() {
        val health = pomodoroCompletionNotifier.health()
        if (!health.notificationsEnabled || !health.channelEnabled) {
            Toast.makeText(
                requireContext(),
                R.string.pref_pomodoro_test_alert_unavailable,
                Toast.LENGTH_LONG
            ).show()
            openPomodoroChannelSettings()
            return
        }
        pomodoroCompletionNotifier.showTestNotification()
    }

    private fun testHabitReminder() {
        org.isoron.uhabits.notifications.AndroidNotificationTray.createAndroidNotificationChannel(requireContext())
        val channel = requireContext().getSystemService(NotificationManager::class.java)
            .getNotificationChannel(org.isoron.uhabits.core.ui.NotificationTray.REMINDERS_CHANNEL_ID)
        if (
            !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled() ||
            channel?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            Toast.makeText(
                requireContext(),
                R.string.settings_reminder_test_unavailable,
                Toast.LENGTH_LONG
            ).show()
            openReminderChannelSettings()
            return
        }

        val app = requireContext().applicationContext as HabitsApplication
        val habit = app.component.habitList.firstOrNull { !it.isArchived && it.hasReminder() }
        if (habit == null) {
            Toast.makeText(
                requireContext(),
                R.string.settings_reminder_test_no_habit,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        app.component.androidNotificationTray.showTestNotification(
            habit,
            getToday(),
            System.currentTimeMillis()
        )
    }

    private fun dayTierSortOrderSummary(): String {
        return prefs.dayTierSortOrder.joinToString(" · ") { dayTierLabel(it) }
    }

    private fun showDayTierSortOrderDialog() {
        val order = prefs.dayTierSortOrder.toMutableList()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpInt(4f), 0, dpInt(4f))
        }

        fun renderRows() {
            content.removeAllViews()
            order.forEachIndexed { index, tier ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dpInt(4f), 0, dpInt(4f))
                }
                row.addView(
                    TextView(requireContext()).apply {
                        text = "${index + 1}. ${dayTierLabel(tier)}"
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                row.addView(
                    Button(requireContext()).apply {
                        text = getString(R.string.move_up)
                        isEnabled = index > 0
                        setOnClickListener {
                            if (index <= 0) return@setOnClickListener
                            java.util.Collections.swap(order, index, index - 1)
                            renderRows()
                        }
                    }
                )
                row.addView(
                    Button(requireContext()).apply {
                        text = getString(R.string.move_down)
                        isEnabled = index < order.lastIndex
                        setOnClickListener {
                            if (index >= order.lastIndex) return@setOnClickListener
                            java.util.Collections.swap(order, index, index + 1)
                            renderRows()
                        }
                    }
                )
                content.addView(row)
            }
        }

        renderRows()
        CustomDialogs.showCustomViewDialog(
            context = requireContext(),
            title = getString(R.string.pref_day_tier_sort_order_title),
            contentView = content,
            positiveText = getString(android.R.string.ok),
            neutralText = getString(R.string.reset_default),
            onNeutral = {
                prefs.dayTierSortOrder = DayTier.entries
                rebuildSettingsList()
            }
        ) {
            prefs.dayTierSortOrder = order
            rebuildSettingsList()
        }
    }

    private fun dayTierLabel(tier: DayTier): String {
        return when (tier) {
            DayTier.MINIMUM -> getString(R.string.day_tier_badge_minimum)
            DayTier.NORMAL -> getString(R.string.day_tier_badge_normal)
            DayTier.IDEAL -> getString(R.string.day_tier_badge_ideal)
            DayTier.OPTIONAL -> getString(R.string.day_tier_optional)
        }
    }

    private fun dpInt(value: Float): Int {
        return (value * resources.displayMetrics.density).toInt()
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

    private fun formatCornerRadiusSummary(radius: Int): String {
        val entries = resources.getStringArray(R.array.pref_habit_card_corners_entries)
        val values = resources.getStringArray(R.array.pref_habit_card_corners_values)
        val index = values.indexOf(radius.toString())
        return if (index >= 0) entries[index] else "$radius dp"
    }

    private fun buildCardRoundingSummary(): String {
        return listOf(
            "${getString(R.string.habits_title)} ${prefs.habitsCardCornerRadius} dp",
            "${getString(R.string.reports_title)} ${prefs.statisticsCardCornerRadius} dp"
        ).joinToString(" · ")
    }

    private fun showCardRoundingDialog() {
        val values = resources.getStringArray(R.array.pref_habit_card_corners_values)
        val allowedValues = values.map { it.toIntOrNull() ?: 8 }
        var habitsRadius = prefs.habitsCardCornerRadius
        var statisticsRadius = prefs.statisticsCardCornerRadius
        val palette = SettingsThemePaletteResolver.resolve(requireContext(), prefs)

        fun sliderIndexFor(radius: Int): Float {
            val fallbackIndex = allowedValues.indexOf(8).takeIf { it >= 0 } ?: 0
            return (allowedValues.indexOf(radius).takeIf { it >= 0 } ?: fallbackIndex).toFloat()
        }

        fun sliderBlock(
            title: String,
            initialRadius: Int,
            onRadiusChanged: (Int) -> Unit
        ): View {
            val density = resources.displayMetrics.density
            val valueView = TextView(requireContext()).apply {
                text = "$initialRadius dp"
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
            }
            return LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            TextView(requireContext()).apply {
                                text = title
                                textSize = 14f
                                setTextColor(palette.onSurface)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                        )
                        addView(valueView)
                    }
                )
                addView(
                    Slider(requireContext()).apply {
                        valueFrom = 0f
                        valueTo = allowedValues.lastIndex.toFloat()
                        stepSize = 1f
                        value = sliderIndexFor(initialRadius)
                        haloRadius = 0
                        thumbRadius = (8 * density).toInt()
                        trackHeight = (4 * density).toInt()
                        labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
                        trackActiveTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                        trackInactiveTintList = android.content.res.ColorStateList.valueOf(palette.border)
                        thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (4 * density).toInt()
                        }
                        addOnChangeListener { _, sliderValue, _ ->
                            val mappedValue = allowedValues[sliderValue.toInt().coerceIn(0, allowedValues.lastIndex)]
                            valueView.text = "$mappedValue dp"
                            onRadiusChanged(mappedValue)
                        }
                    }
                )
            }
        }

        val density = resources.displayMetrics.density
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            addView(sliderBlock(getString(R.string.habits_title), habitsRadius) { habitsRadius = it }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            })
            addView(sliderBlock(getString(R.string.reports_title), statisticsRadius) { statisticsRadius = it }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            })
        }

        CustomDialogs.showCustomViewDialog(
            context = requireContext(),
            title = getString(R.string.pref_card_rounding_title),
            contentView = content
        ) {
            prefs.habitsCardCornerRadius = habitsRadius
            prefs.statisticsCardCornerRadius = statisticsRadius
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
        return path ?: getString(R.string.backup_external_folder_selected)
    }

    private fun getSyncStatusSummaryText(preserveLastSuccessWhileSyncing: Boolean = false): String {
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        if (preserveLastSuccessWhileSyncing && prefs.syncLastSuccessAt > 0L) {
            return getString(
                R.string.sync_status_success,
                dateFormat.format(prefs.syncLastSuccessAt)
            )
        }
        return when (prefs.syncStatus) {
            "success" -> getString(
                R.string.sync_status_success,
                dateFormat.format(prefs.syncLastSuccessAt)
            )
            "error" -> getString(
                R.string.sync_status_error,
                getString(R.string.sync_error_short)
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

    private fun showRestoreBackupDialog() {
        val backups = backupManager.listBackups()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        val items = backups.map { it.name }
        val summaries = backups.map { entry ->
            val source = getString(
                if (entry.source == BackupSource.PRIVATE) R.string.backup_source_local
                else R.string.backup_source_public
            )
            "$source • ${dateFormat.format(entry.modifiedAt)} • ${formatSize(entry.sizeBytes)}"
        }

        CustomDialogs.showSimpleListDialog(
            context = requireContext(),
            title = getString(R.string.restore_backup),
            items = items,
            summaries = summaries,
            emptyStateMessage = getString(R.string.backup_restore_no_backups)
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
        if (manualSyncInProgress) return
        manualSyncInProgress = true
        viewLifecycleOwner.lifecycleScope.launch {
            rebuildSettingsList()
            try {
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
            } finally {
                manualSyncInProgress = false
                if (isAdded) rebuildSettingsList()
            }
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
        if (statisticsDialog?.isShowing == true) return
        val options = listOf(
            getString(R.string.today),
            getString(R.string.this_monday),
            getString(R.string.next_monday),
            getString(R.string.select_date)
        )
        statisticsDialog = CustomDialogs.showSingleChoiceDialog(
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
        }.also { dialog -> dialog.setOnDismissListener { if (statisticsDialog === dialog) statisticsDialog = null } }
    }

    private fun applyGlobalStatisticsStart(date: LocalDate?) {
        val app = requireContext().applicationContext as HabitsApplication
        app.component.commandRunner.run(
            SetGlobalStatisticsStartDateCommand(app.component.habitList, date)
        )
        rebuildSettingsList()
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
        statisticsDialog = CustomDialogs.showDatePickerDialog(
            context = requireContext(),
            title = getString(R.string.select_date),
            initialDate = today
        ) { date ->
            callback(date)
        }.also { dialog -> dialog.setOnDismissListener { if (statisticsDialog === dialog) statisticsDialog = null } }
    }

    private fun thisMonday(): LocalDate {
        val firstWeekday = DayOfWeek.values()[getFirstWeekdayNumberAccordingToLocale() - 1]
        return getToday().startOfWeek(firstWeekday)
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
        private const val ARG_COMPACT_TOP_INSET = "compactTopInset"
        internal const val ARG_DETAIL_SECTION = "settingsDetailSection"

        fun newInstance(compactTopInset: Boolean = false): SettingsFragment {
            return SettingsFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_COMPACT_TOP_INSET, compactTopInset)
                }
            }
        }
    }
}
