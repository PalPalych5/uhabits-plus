package org.isoron.uhabits.activities.settings

import androidx.annotation.DrawableRes
import org.isoron.uhabits.R

object SettingsIconRegistry {
    val icons: Map<String, Int> = mapOf(
        "pref_theme" to R.drawable.ic_settings_theme,
        "pref_app_language" to R.drawable.ic_settings_globe,
        "pref_first_weekday" to R.drawable.ic_settings_calendar,
        "pref_accent_color" to R.drawable.ic_settings_droplet,
        "pref_disable_animation" to R.drawable.ic_settings_animation,
        "pref_widget_opacity" to R.drawable.ic_settings_opacity,
        "pref_card_rounding" to R.drawable.ic_settings_corners,
        "pref_show_habit_card_borders" to R.drawable.ic_settings_layout_list,
        "pref_enable_habit_spheres" to R.drawable.ic_settings_spheres,
        "configureSpheres" to R.drawable.ic_settings_configure_spheres,
        "pref_enable_day_tiers" to R.drawable.ic_settings_tiers,
        "pref_day_tier_sort_order" to R.drawable.ic_settings_arrows_sort,
        "pref_habit_group_separators" to R.drawable.ic_settings_separator_horizontal,
        "pref_short_toggle" to R.drawable.ic_settings_check,
        "pref_checkmark_reverse_order" to R.drawable.ic_settings_reverse,
        "pref_skip_enabled" to R.drawable.ic_settings_fast_forward,
        "pref_unknown_enabled" to R.drawable.ic_settings_question,
        "pref_midnight_delay" to R.drawable.ic_settings_clock,
        "openArchive" to R.drawable.ic_settings_archive,
        "pref_sticky_notifications" to R.drawable.ic_settings_notification,
        "reminderCustomize" to R.drawable.ic_settings_bell_cog,
        "reminderTest" to R.drawable.ic_settings_bell_check,
        "pref_pomodoro_default_focus_minutes" to R.drawable.ic_settings_pomodoro_focus,
        "pref_pomodoro_default_break_minutes" to R.drawable.ic_settings_pomodoro_break,
        "pref_pomodoro_auto_switch" to R.drawable.ic_settings_pomodoro_auto,
        "pref_pomodoro_focus_alert" to R.drawable.ic_settings_pomodoro_focus,
        "pref_pomodoro_break_alert" to R.drawable.ic_settings_pomodoro_break,
        "pomodoroAlertChannel" to R.drawable.ic_settings_clock_cog,
        "pomodoroTestAlert" to R.drawable.ic_settings_clock_check,
        "pomodoroProgressChannel" to R.drawable.ic_settings_lock,
        "pomodoroExactAlarm" to R.drawable.ic_settings_clock,
        "backupStatus" to R.drawable.ic_settings_backup_history,
        "exportDB" to R.drawable.ic_settings_backup_upload,
        "publicBackupFolder" to R.drawable.ic_settings_folder,
        "backupToPublicFolder" to R.drawable.ic_settings_folder_upload,
        "exportCSV" to R.drawable.ic_settings_export_csv,
        "importData" to R.drawable.ic_settings_import,
        "bugReport" to R.drawable.ic_settings_bug,
        "restoreBackup" to R.drawable.ic_settings_database_down,
        "hardResetStatistics" to R.drawable.ic_settings_trash,
        "syncStatus" to R.drawable.ic_settings_history,
        "pref_sync_enabled" to R.drawable.ic_settings_cloud_sync,
        "syncAccount" to R.drawable.ic_settings_account,
        "syncSignIn" to R.drawable.ic_settings_login,
        "syncSignOut" to R.drawable.ic_settings_logout,
        "syncNow" to R.drawable.ic_settings_sync,
        "syncReview" to R.drawable.ic_settings_git_merge,
        "help" to R.drawable.ic_settings_lifebuoy,
        "rateApp" to R.drawable.ic_settings_rate,
        "about" to R.drawable.ic_settings_info,
        "pref_developer" to R.drawable.ic_settings_terminal,
        "pref_sync_base_url" to R.drawable.ic_settings_link,
        "pref_sync_key" to R.drawable.ic_settings_key,
        "pref_encryption_key" to R.drawable.ic_settings_shield_lock,
        "exportSyncDiagnostics" to R.drawable.ic_settings_report_analytics
    )

    val allowedSemanticRepeats: Set<Set<String>> = setOf(
        setOf("pref_midnight_delay", "pomodoroExactAlarm"),
        setOf("pref_pomodoro_default_focus_minutes", "pref_pomodoro_focus_alert"),
        setOf("pref_pomodoro_default_break_minutes", "pref_pomodoro_break_alert")
    )

    @DrawableRes
    fun resolve(key: String, @DrawableRes fallback: Int): Int = icons[key] ?: fallback
}
