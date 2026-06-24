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

import android.app.backup.BackupManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
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
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.core.commands.ClearAllEntriesCommand
import org.isoron.uhabits.core.commands.SetGlobalStatisticsStartDateCommand
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.utils.DemoDataGenerator

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    private var sharedPrefs: SharedPreferences? = null
    private var ringtoneManager: RingtoneManager? = null
    private lateinit var prefs: Preferences
    private lateinit var intentFactory: IntentFactory
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
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                startActivityForResult(intent, PUBLIC_BACKUP_REQUEST_CODE)
                return true
            }
            "configureSpheres" -> {
                actionHandler().onSettingsAction(SettingsAction.MANAGE_SPHERES)
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
        if (!prefs.isDeveloper) {
            val devCategory = findPreference("devCategory") as PreferenceCategory
            devCategory.isVisible = false
        }
        findPreference("demoCategory")?.isVisible = BuildConfig.DEBUG
        findPreference("configureSpheres")?.isVisible = prefs.isHabitSpheresEnabled
        updateWeekdayPreference()
        updatePublicBackupFolderSummary()

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
        BackupManager.dataChanged("org.isoron.uhabits.plus")
        updateWeekdayPreference()
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
