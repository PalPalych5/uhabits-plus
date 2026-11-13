/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.about

/**
 * Pure state machine for the 5-tap developer-mode unlock interaction on the About version row.
 */
sealed interface DeveloperUnlockOutcome {
    data object AlreadyEnabled : DeveloperUnlockOutcome
    data class Countdown(val remainingTaps: Int) : DeveloperUnlockOutcome
    data object Activated : DeveloperUnlockOutcome
}

class AboutDeveloperUnlockState(
    initialIsDeveloper: Boolean,
    private val requiredTaps: Int = DEFAULT_REQUIRED_TAPS,
    private val persistDeveloperEnabled: () -> Unit,
) {
    var isDeveloperEnabled: Boolean = initialIsDeveloper
        private set

    var remainingTaps: Int = if (initialIsDeveloper) 0 else requiredTaps
        private set

    fun onVersionTap(): DeveloperUnlockOutcome {
        if (isDeveloperEnabled) {
            return DeveloperUnlockOutcome.AlreadyEnabled
        }
        remainingTaps = (remainingTaps - 1).coerceAtLeast(0)
        return if (remainingTaps == 0) {
            isDeveloperEnabled = true
            persistDeveloperEnabled()
            DeveloperUnlockOutcome.Activated
        } else {
            DeveloperUnlockOutcome.Countdown(remainingTaps)
        }
    }

    companion object {
        const val DEFAULT_REQUIRED_TAPS = 5
    }
}

enum class AboutDestination {
    MAIN,
    CONTRIBUTORS,
    LICENSES,
    GPL_LICENSE,
    FULL_NOTICE;

    fun parent(): AboutDestination? = when (this) {
        MAIN -> null
        CONTRIBUTORS, LICENSES, GPL_LICENSE -> MAIN
        FULL_NOTICE -> LICENSES
    }
}
