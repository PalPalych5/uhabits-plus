package org.isoron.uhabits.activities.settings

import android.view.View

data class SettingsTransitionSource(
    val sectionId: SettingsSectionId,
    val container: View,
)
