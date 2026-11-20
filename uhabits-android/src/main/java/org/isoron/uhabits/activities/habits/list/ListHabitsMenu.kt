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

package org.isoron.uhabits.activities.habits.list

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.containers.ContainersActivity
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope

@Inject
@ActivityScope
class ListHabitsMenu(
    @ActivityContext context: Context,
    private val preferences: Preferences,
    val behavior: ListHabitsMenuBehavior
) {
    val activity = context as AppCompatActivity
    var isSearchActive = false
        private set
    var onSearchQueryChanged: ((String) -> Unit)? = null
    var onMenuStateChanged: (() -> Unit)? = null

    fun closeSearch() {
        if (!isSearchActive) return
        isSearchActive = false
        behavior.onSearchQueryChanged("")
        onSearchQueryChanged?.invoke("")
        onMenuStateChanged?.invoke()
    }

    fun openSearch() {
        isSearchActive = true
        onMenuStateChanged?.invoke()
    }

    fun normalizeSortOrder() {
        if (!preferences.isHabitSpheresEnabled && preferences.defaultPrimaryOrder == HabitList.Order.BY_SPHERE) {
            preferences.defaultPrimaryOrder = HabitList.Order.BY_POSITION
        }
        if (!preferences.isDayTiersEnabled && preferences.defaultPrimaryOrder == HabitList.Order.BY_DAY_TIER) {
            preferences.defaultPrimaryOrder = HabitList.Order.BY_POSITION
        }
    }

    internal fun buildToolbarState(
        archive: Boolean,
        syncReady: Boolean,
        isSyncing: Boolean,
        query: String = behavior.searchQuery
    ): HabitToolbarState {
        normalizeSortOrder()
        val res = activity.resources
        return buildHabitToolbarState(
            archive = archive,
            searching = isSearchActive,
            query = query,
            showArchived = preferences.showArchived,
            showCompleted = preferences.showCompleted,
            areQuestionMarksEnabled = preferences.areQuestionMarksEnabled,
            isSkipEnabled = preferences.isSkipEnabled,
            primaryOrder = preferences.defaultPrimaryOrder,
            isHabitSpheresEnabled = preferences.isHabitSpheresEnabled,
            isDayTiersEnabled = preferences.isDayTiersEnabled,
            isClassicLayout = preferences.isClassicSectionLayout,
            showCardBorders = preferences.showHabitCardBorders,
            syncReady = syncReady,
            isSyncing = isSyncing,
            labels = HabitToolbarLabels(
                habitsTitle = res.getString(R.string.habits_title),
                archiveTitle = res.getString(R.string.archive_title),
                hideArchived = res.getString(R.string.hide_archived),
                hideCompleted = res.getString(R.string.hide_completed),
                hideEntered = res.getString(R.string.hide_entered),
                sortManual = res.getString(R.string.manually),
                sortByName = res.getString(R.string.by_name),
                sortByColor = res.getString(R.string.by_color),
                sortByScore = res.getString(R.string.by_score),
                sortByStatus = res.getString(R.string.by_status),
                sortBySphere = res.getString(R.string.by_sphere),
                sortByLevel = res.getString(R.string.by_level),
                skipDay = res.getString(R.string.action_skip_day),
                search = res.getString(R.string.search)
            )
        )
    }

    fun onActionSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.actionHideArchived -> {
                behavior.onToggleShowArchived()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionHideCompleted -> {
                behavior.onToggleShowCompleted()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSortColor -> {
                behavior.onSortByColor()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSortManual -> {
                behavior.onSortByManually()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSortName -> {
                behavior.onSortByName()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSortScore -> {
                behavior.onSortByScore()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSortStatus -> {
                behavior.onSortByStatus()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSortSphere -> {
                if (!preferences.isHabitSpheresEnabled) return true
                behavior.onSortBySphere()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSortLevel -> {
                if (!preferences.isDayTiersEnabled) return true
                behavior.onSortByLevel()
                onMenuStateChanged?.invoke()
                return true
            }

            R.id.actionSearch -> {
                openSearch()
                return true
            }

            R.id.actionContainers -> {
                activity.startActivity(ContainersActivity.intent(activity))
                return true
            }

            else -> return false
        }
    }
}
