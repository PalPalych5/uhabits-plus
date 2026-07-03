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
package org.isoron.uhabits.core.preferences

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.utils.StringUtils.Companion.joinLongs
import org.isoron.platform.utils.StringUtils.Companion.splitLongs
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.ui.ThemeSwitcher
import kotlin.math.max
import kotlin.math.min

open class Preferences(private val storage: Storage) {
    private val listeners: MutableList<Listener>
    private var shouldReverseCheckmarks: Boolean? = null
    open fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    open fun getDefaultHabitColor(fallbackColor: Int): Int {
        return storage.getInt(
            "pref_default_habit_palette_color",
            fallbackColor
        )
    }

    open var defaultPrimaryOrder: HabitList.Order
        get() {
            val name = storage.getString("pref_default_order", "BY_POSITION")
            return try {
                HabitList.Order.valueOf(name)
            } catch (e: IllegalArgumentException) {
                defaultPrimaryOrder = HabitList.Order.BY_POSITION
                HabitList.Order.BY_POSITION
            }
        }
        set(order) {
            storage.putString("pref_default_order", order.name)
        }
    open var defaultSecondaryOrder: HabitList.Order
        get() {
            val name = storage.getString("pref_default_secondary_order", "BY_NAME_ASC")
            return try {
                HabitList.Order.valueOf(name)
            } catch (e: IllegalArgumentException) {
                defaultSecondaryOrder = HabitList.Order.BY_NAME_ASC
                HabitList.Order.BY_POSITION
            }
        }
        set(order) {
            storage.putString("pref_default_secondary_order", order.name)
        }
    open var scoreCardSpinnerPosition: Int
        get() = min(4, max(0, storage.getInt("pref_score_view_interval", 1)))
        set(position) {
            storage.putInt("pref_score_view_interval", position)
        }
    open var barCardBoolSpinnerPosition: Int
        get() = min(3, max(0, storage.getInt("pref_bar_card_bool_spinner", 0)))
        set(position) {
            storage.putInt("pref_bar_card_bool_spinner", position)
        }
    open var barCardNumericalSpinnerPosition: Int
        get() = min(4, max(0, storage.getInt("pref_bar_card_numerical_spinner", 0)))
        set(position) {
            storage.putInt("pref_bar_card_numerical_spinner", position)
        }
    open val lastHintNumber: Int
        get() = storage.getInt("last_hint_number", -1)
    open val lastHintDate: LocalDate?
        get() {
            val unixTime = storage.getLong("last_hint_timestamp", -1)
            return if (unixTime < 0) null else LocalDate.fromUnixTime(unixTime)
        }

    open var showArchived: Boolean
        get() = storage.getBoolean("pref_show_archived", false)
        set(showArchived) {
            storage.putBoolean("pref_show_archived", showArchived)
        }
    open var showCompleted: Boolean
        get() = storage.getBoolean("pref_show_completed", true)
        set(showCompleted) {
            storage.putBoolean("pref_show_completed", showCompleted)
        }

    open var theme: Int
        get() = storage.getInt("pref_theme", ThemeSwitcher.THEME_AUTOMATIC)
        set(theme) {
            storage.putInt("pref_theme", theme)
        }

    open fun incrementLaunchCount() {
        storage.putInt("launch_count", launchCount + 1)
    }

    open val launchCount: Int
        get() = storage.getInt("launch_count", 0)
    open var isDeveloper: Boolean
        get() = storage.getBoolean("pref_developer", false)
        set(isDeveloper) {
            storage.putBoolean("pref_developer", isDeveloper)
        }
    open var isFirstRun: Boolean
        get() = storage.getBoolean("pref_first_run", true)
        set(isFirstRun) {
            storage.putBoolean("pref_first_run", isFirstRun)
        }
    open var isPureBlackEnabled: Boolean
        get() = storage.getBoolean("pref_pure_black", true)
        set(enabled) {
            storage.putBoolean("pref_pure_black", enabled)
        }

    open var accentColor: String
        get() = storage.getString("pref_accent_color", "preset:blue")
        set(value) {
            storage.putString("pref_accent_color", value)
            notifySyncPreferencesChanged()
        }
    open var isShortToggleEnabled: Boolean
        get() = storage.getBoolean("pref_short_toggle", true)
        set(enabled) {
            storage.putBoolean("pref_short_toggle", enabled)
        }

    open var isConfettiAnimationDisabled: Boolean
        get() = storage.getBoolean("pref_disable_animation", false)
        set(enabled) {
            storage.putBoolean("pref_disable_animation", enabled)
        }

    open var isDayTiersEnabled: Boolean
        get() = storage.getBoolean("pref_enable_day_tiers", true)
        set(enabled) {
            storage.putBoolean("pref_enable_day_tiers", enabled)
            if (!enabled && defaultPrimaryOrder == HabitList.Order.BY_DAY_TIER) {
                defaultPrimaryOrder = HabitList.Order.BY_POSITION
            }
            notifyHabitListAppearanceChanged()
        }

    open var areHabitGroupSeparatorsEnabled: Boolean
        get() = storage.getBoolean("pref_habit_group_separators", false)
        set(enabled) {
            storage.putBoolean("pref_habit_group_separators", enabled)
            notifyHabitListAppearanceChanged()
        }

    open var dayTierSortOrder: List<DayTier>
        get() {
            val stored = storage.getString("pref_day_tier_sort_order", "")
            val parsed = stored
                .split(",")
                .mapNotNull { name -> runCatching { DayTier.valueOf(name) }.getOrNull() }
                .distinct()
            return normalizeDayTierOrder(parsed)
        }
        set(order) {
            val normalized = normalizeDayTierOrder(order)
            storage.putString("pref_day_tier_sort_order", normalized.joinToString(",") { it.name })
            notifyHabitListAppearanceChanged()
        }

    private fun normalizeDayTierOrder(order: List<DayTier>): List<DayTier> {
        return (order + DayTier.entries.filterNot { it in order }).distinct()
    }

    open var habitCardCornerRadius: Int
        get() = storage.getInt("pref_habit_card_corner_radius", 8)
        set(value) {
            storage.putInt("pref_habit_card_corner_radius", value)
            notifyCardCornersChanged()
        }

    open var habitsCardCornerRadius: Int
        get() {
            val radius = storage.getInt("pref_habits_card_corner_radius", -1)
            return if (radius >= 0) radius else habitCardCornerRadius
        }
        set(value) {
            storage.putInt("pref_habits_card_corner_radius", value)
            notifyCardCornersChanged()
        }

    open var statisticsCardCornerRadius: Int
        get() {
            val radius = storage.getInt("pref_statistics_card_corner_radius", -1)
            return if (radius >= 0) radius else habitCardCornerRadius
        }
        set(value) {
            storage.putInt("pref_statistics_card_corner_radius", value)
            notifyCardCornersChanged()
        }

    open var isHabitSpheresEnabled: Boolean
        get() = storage.getBoolean("pref_enable_habit_spheres", true)
        set(enabled) {
            storage.putBoolean("pref_enable_habit_spheres", enabled)
        }

    open fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    open fun clear() {
        storage.clear()
    }

    open fun setDefaultHabitColor(color: Int) {
        storage.putInt("pref_default_habit_palette_color", color)
    }

    open fun setNotificationsSticky(sticky: Boolean) {
        storage.putBoolean("pref_sticky_notifications", sticky)
        for (l in listeners) l.onNotificationsChanged()
    }

    open fun shouldMakeNotificationsSticky(): Boolean {
        return storage.getBoolean("pref_sticky_notifications", false)
    }

    open var isCheckmarkSequenceReversed: Boolean
        get() {
            if (shouldReverseCheckmarks == null) {
                shouldReverseCheckmarks =
                    storage.getBoolean("pref_checkmark_reverse_order", true)
            }
            return shouldReverseCheckmarks!!
        }
        set(reverse) {
            shouldReverseCheckmarks = reverse
            storage.putBoolean("pref_checkmark_reverse_order", reverse)
            for (l in listeners) l.onCheckmarkSequenceChanged()
        }

    open var isMidnightDelayEnabled: Boolean
        get() = storage.getBoolean("pref_midnight_delay", true)
        set(enabled) {
            storage.putBoolean("pref_midnight_delay", enabled)
            for (l in listeners) l.onCheckmarkSequenceChanged()
        }

    open val midnightDelayHours: Int
        get() = if (isMidnightDelayEnabled) MIDNIGHT_DELAY_HOURS else 0

    companion object {
        const val MIDNIGHT_DELAY_HOURS = 3
    }

    open fun updateLastHint(number: Int, date: LocalDate) {
        storage.putInt("last_hint_number", number)
        storage.putLong("last_hint_timestamp", date.unixTime)
    }

    open var pomodoroDefaultFocusMinutes: Int
        get() = storage.getInt("pref_pomodoro_default_focus_minutes", 25)
        set(value) = storage.putInt("pref_pomodoro_default_focus_minutes", value)

    open var pomodoroDefaultBreakMinutes: Int
        get() = storage.getInt("pref_pomodoro_default_break_minutes", 5)
        set(value) = storage.putInt("pref_pomodoro_default_break_minutes", value)

    open var isPomodoroAutoSwitch: Boolean
        get() = storage.getBoolean("pref_pomodoro_auto_switch", true)
        set(value) = storage.putBoolean("pref_pomodoro_auto_switch", value)


    open var lastAppVersion: Int
        get() = storage.getInt("last_version", 0)
        set(version) {
            storage.putInt("last_version", version)
        }
    open var widgetOpacity: Int
        get() = storage.getString("pref_widget_opacity", "255").toInt()
        set(value) {
            storage.putString("pref_widget_opacity", value.toString())
        }
    open var isSkipEnabled: Boolean
        get() = storage.getBoolean("pref_skip_enabled", true)
        set(value) {
            storage.putBoolean("pref_skip_enabled", value)
        }

    open var areQuestionMarksEnabled: Boolean
        get() = storage.getBoolean("pref_unknown_enabled", false)
        set(value) {
            storage.putBoolean("pref_unknown_enabled", value)
            for (l in listeners) l.onQuestionMarksChanged()
        }

    /**
     * @return An integer representing the first day of the week. Sunday
     * corresponds to 1, Monday to 2, and so on, until Saturday, which is
     * represented by 7. By default, this is based on the current system locale,
     * unless the user changed this in the settings.
     */
    @get:Deprecated("")
    open val firstWeekdayInt: Int
        get() {
            val weekday = storage.getString("pref_first_weekday", "")
            return if (weekday.isEmpty()) getFirstWeekdayNumberAccordingToLocale() else weekday.toInt()
        }
    open val firstWeekday: DayOfWeek
        get() {
            var weekday = storage.getString("pref_first_weekday", "-1").toInt()
            if (weekday < 0) weekday = getFirstWeekdayNumberAccordingToLocale()
            return when (weekday) {
                1 -> DayOfWeek.SUNDAY
                2 -> DayOfWeek.MONDAY
                3 -> DayOfWeek.TUESDAY
                4 -> DayOfWeek.WEDNESDAY
                5 -> DayOfWeek.THURSDAY
                6 -> DayOfWeek.FRIDAY
                7 -> DayOfWeek.SATURDAY
                else -> throw IllegalArgumentException()
            }
        }

    open var isSyncEnabled: Boolean
        get() = storage.getBoolean("pref_sync_enabled", false)
        set(enabled) {
            storage.putBoolean("pref_sync_enabled", enabled)
            notifySyncPreferencesChanged()
        }

    open var syncStatus: String
        get() = storage.getString("pref_sync_status", "disabled")
        set(value) {
            storage.putString("pref_sync_status", value)
        }

    open var syncStatusDetail: String
        get() = storage.getString("pref_sync_status_detail", "")
        set(value) {
            storage.putString("pref_sync_status_detail", value)
        }

    open var syncLastUiRefreshReason: String
        get() = storage.getString("pref_sync_last_ui_refresh_reason", "never")
        set(value) {
            storage.putString("pref_sync_last_ui_refresh_reason", value)
        }

    open var syncLastUiRefreshAt: Long
        get() = storage.getLong("pref_sync_last_ui_refresh_at", 0L)
        set(value) {
            storage.putLong("pref_sync_last_ui_refresh_at", value)
        }

    open var syncLastUiRefreshDestination: String
        get() = storage.getString("pref_sync_last_ui_refresh_destination", "")
        set(value) {
            storage.putString("pref_sync_last_ui_refresh_destination", value)
        }

    open var syncLastUiRefreshHabitCount: Long
        get() = storage.getLong("pref_sync_last_ui_refresh_habit_count", -1L)
        set(value) {
            storage.putLong("pref_sync_last_ui_refresh_habit_count", value)
        }

    open var syncLastSuccessAt: Long
        get() = storage.getLong("pref_sync_last_success_at", 0L)
        set(value) {
            storage.putLong("pref_sync_last_success_at", value)
        }

    open var syncLastLogId: Long
        get() = storage.getLong("pref_sync_last_log_id", 0L)
        set(value) {
            storage.putLong("pref_sync_last_log_id", value)
        }

    open var isSyncReviewRequired: Boolean
        get() = storage.getBoolean("pref_sync_review_required", false)
        set(required) {
            storage.putBoolean("pref_sync_review_required", required)
        }

    open var syncReviewReason: String
        get() = storage.getString("pref_sync_review_reason", "")
        set(value) {
            storage.putString("pref_sync_review_reason", value)
        }

    open var syncAccountEmail: String?
        get() = storage.getString("pref_sync_account_email", "").ifBlank { null }
        set(value) {
            if (value.isNullOrBlank()) storage.remove("pref_sync_account_email")
            else storage.putString("pref_sync_account_email", value)
            notifySyncPreferencesChanged()
        }

    open var isSyncBootstrapQueued: Boolean
        get() = storage.getBoolean("pref_sync_bootstrap_queued", false)
        set(value) {
            storage.putBoolean("pref_sync_bootstrap_queued", value)
        }

    open var isSyncBootstrapDone: Boolean
        get() = storage.getBoolean("pref_sync_bootstrap_done", false)
        set(value) {
            storage.putBoolean("pref_sync_bootstrap_done", value)
        }

    open var syncSupabaseUrl: String
        get() = storage.getString("pref_sync_base_url", "")
        set(value) {
            storage.putString("pref_sync_base_url", value)
            notifySyncPreferencesChanged()
        }

    open var syncSupabaseAnonKey: String
        get() = storage.getString("pref_sync_key", "")
        set(value) {
            storage.putString("pref_sync_key", value)
            notifySyncPreferencesChanged()
        }

    open var syncLastBackgroundSyncReason: String
        get() = storage.getString("pref_sync_last_bg_reason", "never")
        set(value) {
            storage.putString("pref_sync_last_bg_reason", value)
        }

    open var syncLastBackgroundSyncScheduledAt: Long
        get() = storage.getLong("pref_sync_last_bg_scheduled_at", 0L)
        set(value) {
            storage.putLong("pref_sync_last_bg_scheduled_at", value)
        }

    open var syncLastBackgroundSyncResult: String
        get() = storage.getString("pref_sync_last_bg_result", "")
        set(value) {
            storage.putString("pref_sync_last_bg_result", value)
        }

    open fun notifySyncFinished() {
        for (l in listeners) l.onSyncFinished()
    }

    open fun notifySyncPreferencesChanged() {
        for (l in listeners) l.onSyncPreferencesChanged()
    }

    open fun notifyCardCornersChanged() {
        for (l in listeners) l.onCardCornersChanged()
    }

    open fun notifyHabitListAppearanceChanged() {
        for (l in listeners) l.onHabitListAppearanceChanged()
    }

    interface Listener {
        fun onCheckmarkSequenceChanged() {}
        fun onNotificationsChanged() {}
        fun onQuestionMarksChanged() {}
        fun onNavigationPreferencesChanged() {}
        fun onSyncFinished() {}
        fun onSyncPreferencesChanged() {}
        fun onCardCornersChanged() {}
        fun onHabitListAppearanceChanged() {}
    }

    interface Storage {
        fun clear()
        fun getBoolean(key: String, defValue: Boolean): Boolean
        fun getInt(key: String, defValue: Int): Int
        fun getLong(key: String, defValue: Long): Long
        fun getString(key: String, defValue: String): String
        fun onAttached(preferences: Preferences) {}
        fun putBoolean(key: String, value: Boolean)
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun putString(key: String, value: String)
        fun remove(key: String)
        fun putLongArray(key: String, values: LongArray) {
            putString(key, joinLongs(values))
        }

        fun getLongArray(key: String, defValue: LongArray): LongArray {
            val string = getString(key, "")
            return if (string.isEmpty()) {
                defValue
            } else {
                splitLongs(
                    string
                )
            }
        }
    }

    init {
        listeners = mutableListOf()
        storage.onAttached(this)
    }
}
