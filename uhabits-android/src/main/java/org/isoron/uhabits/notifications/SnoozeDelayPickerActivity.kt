/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.notifications

import android.content.ContentUris
import android.os.Bundle
import android.text.format.DateFormat
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.receivers.ReminderController
import org.isoron.uhabits.utils.SystemUtils
import java.util.Calendar

class SnoozeDelayPickerActivity : FragmentActivity() {
    private var habit: Habit? = null
    private var reminderController: ReminderController? = null
    private var dialog: AlertDialog? = null
    private var showingCustomTime = false

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        val intent = intent
        if (intent == null) finish()
        val app = applicationContext as HabitsApplication
        val appComponent = app.component
        val themeSwitcher = AndroidThemeSwitcher(this, appComponent.preferences)
        themeSwitcher.setTheme()

        val data = intent.data
        if (data == null) {
            finish()
            return
        } else {
            habit = appComponent.habitList.getById(ContentUris.parseId(data))
        }
        val currentHabit = habit
        if (currentHabit == null || !org.isoron.uhabits.intents.NotificationIntentIdentity.accepts(intent, appComponent.session, currentHabit)) {
            finish()
            return
        }
        reminderController = appComponent.reminderController
        showingCustomTime = bundle?.getBoolean(STATE_CUSTOM_TIME) ?: false
        if (showingCustomTime) showTimePicker() else showPresetPicker(currentHabit)
        SystemUtils.unlockScreen(this)
    }

    private fun showPresetPicker(habit: Habit) {
        val options = resources.getStringArray(R.array.snooze_picker_names).toList()
        dialog = CustomDialogs.showSimpleListDialog(
            context = this,
            title = getString(R.string.select_snooze_delay),
            items = options
        ) { which ->
            val snoozeValues = resources.getIntArray(R.array.snooze_picker_values)
            if (snoozeValues[which] >= 0) {
                reminderController?.onSnoozeDelayPicked(habit, snoozeValues[which])
                finish()
            } else {
                showingCustomTime = true
                showTimePicker()
            }
        }.apply { setOnDismissListener { if (!showingCustomTime) finish() } }
    }

    private fun AndroidThemeSwitcher.setTheme() {
        if (this.isNightMode) {
            setTheme(R.style.BaseDialogDark)
            this.currentTheme = DarkTheme()
        } else {
            setTheme(R.style.BaseDialog)
            this.currentTheme = LightTheme()
        }
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        dialog = CustomDialogs.showTimePickerDialog(
            context = this,
            title = getString(R.string.snooze),
            initialHour = calendar[Calendar.HOUR_OF_DAY],
            initialMinute = calendar[Calendar.MINUTE],
            is24Hour = DateFormat.is24HourFormat(this),
            onConfirm = { hour, minute ->
                val currentHabit = habit
                if (currentHabit != null) {
                    reminderController?.onSnoozeTimePicked(currentHabit, hour, minute)
                }
                finish()
            }
        ).apply { setOnDismissListener { if (!isFinishing) finish() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CUSTOM_TIME, showingCustomTime)
    }

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val STATE_CUSTOM_TIME = "showingCustomTime"
    }
}
