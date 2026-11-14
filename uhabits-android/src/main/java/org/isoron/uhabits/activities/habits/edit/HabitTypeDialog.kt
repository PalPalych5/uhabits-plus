/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.intents.IntentFactory

class HabitTypeDialog : AppCompatDialogFragment() {
    private val createArchived: Boolean
        get() = arguments?.getBoolean(ARG_CREATE_ARCHIVED) == true

    override fun getTheme() = R.style.Translucent

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette)
                HabitTypeDialogContent(
                    colors = colors,
                    onSelectYesNo = {
                        val intent = IntentFactory().startEditActivity(
                            requireActivity(),
                            HabitType.YES_NO.value,
                            createArchived
                        )
                        startActivity(intent)
                        dismiss()
                    },
                    onSelectMeasurable = {
                        val intent = IntentFactory().startEditActivity(
                            requireActivity(),
                            HabitType.NUMERICAL.value,
                            createArchived
                        )
                        startActivity(intent)
                        dismiss()
                    },
                    onDismiss = { dismiss() }
                )
            }
        }
    }

    companion object {
        private const val ARG_CREATE_ARCHIVED = "createArchived"

        fun newInstance(createArchived: Boolean = false): HabitTypeDialog {
            return HabitTypeDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_CREATE_ARCHIVED, createArchived)
                }
            }
        }
    }
}

@Composable
internal fun HabitTypeDialogContent(
    colors: UHabitComposeColors,
    onSelectYesNo: () -> Unit,
    onSelectMeasurable: () -> Unit,
    onDismiss: () -> Unit
) {
    UHabitComposeTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HabitTypeCard(
                    title = stringResource(R.string.yes_or_no),
                    example = stringResource(R.string.yes_or_no_example),
                    colors = colors,
                    onClick = onSelectYesNo
                )
                HabitTypeCard(
                    title = stringResource(R.string.measurable),
                    example = stringResource(R.string.measurable_example),
                    colors = colors,
                    onClick = onSelectMeasurable
                )
            }
        }
    }
}

@Composable
private fun HabitTypeCard(
    title: String,
    example: String,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foregroundColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = example,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryColor
            )
        }
    }
}
