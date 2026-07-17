package org.isoron.uhabits.activities.main

enum class MainDestination {
    HABITS,
    ARCHIVE,
    STATISTICS,
    SETTINGS;

    val bottomItemDestination: MainDestination
        get() = if (this == ARCHIVE) HABITS else this
}

interface MainNavigationHost {
    fun navigate(destination: MainDestination)
    fun navigateBack(): Boolean
    fun setHabitCreationAvailable(available: Boolean)
    fun setBottomNavigationVisible(visible: Boolean, animate: Boolean)
}

enum class SettingsAction {
    IMPORT_DATA,
    EXPORT_CSV,
    EXPORT_DATABASE,
    REPAIR_DATABASE,
    BUG_REPORT,
    MANAGE_SPHERES,
    OPEN_ARCHIVE
}

interface SettingsActionHandler {
    fun onSettingsAction(action: SettingsAction)
}
