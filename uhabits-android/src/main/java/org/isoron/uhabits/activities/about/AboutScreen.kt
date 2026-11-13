/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.activities.about

import org.isoron.uhabits.R
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.utils.startActivitySafely

class AboutScreen(
    private val activity: AboutActivity,
    private val intents: IntentFactory,
    private val prefs: Preferences,
) {
    private val unlockState = AboutDeveloperUnlockState(
        initialIsDeveloper = prefs.isDeveloper,
        persistDeveloperEnabled = { prefs.isDeveloper = true },
    )

    val isDeveloper: Boolean
        get() = unlockState.isDeveloperEnabled

    fun onPressDeveloperCountdown(): DeveloperUnlockOutcome {
        return unlockState.onVersionTap()
    }

    fun developerActivatedMessage(): String =
        activity.resources.getString(R.string.you_are_now_a_developer)

    fun onViewProjectSourceCode() {
        activity.startActivitySafely(intents.viewProjectSourceCode(activity))
    }

    fun onReportIssue() {
        activity.startActivitySafely(intents.reportIssue(activity))
    }

    fun onViewUpstreamRepository() {
        activity.startActivitySafely(intents.viewUpstreamRepository(activity))
    }

    fun onViewContributors() {
        activity.startActivitySafely(intents.codeContributors(activity))
    }
}
