package org.isoron.uhabits.activities.main

class MainNavigationState(
    current: MainDestination = MainDestination.HABITS,
    history: List<MainDestination> = emptyList()
) {
    var current: MainDestination = current
        private set

    private val history = history.toMutableList()

    fun navigate(destination: MainDestination): Boolean {
        if (destination == current) return false
        history.add(current)
        current = destination
        return true
    }

    fun navigateBack(): MainDestination? {
        if (history.isEmpty()) return null
        current = history.removeAt(history.lastIndex)
        return current
    }

    fun history(): List<MainDestination> = history.toList()
}
