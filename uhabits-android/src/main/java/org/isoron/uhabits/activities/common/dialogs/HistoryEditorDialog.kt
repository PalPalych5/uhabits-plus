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
package org.isoron.uhabits.activities.common.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import org.isoron.platform.gui.AndroidDataView
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.UHabitElevationAndBorders
import org.isoron.uhabits.compose.rememberHarmonizedChartTheme
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.OnDateClickedListener
import org.isoron.uhabits.focus.EffectiveHabitReadController
import java.util.Locale

class HistoryEditorDialog : AppCompatDialogFragment(), CommandRunner.Listener, ModelObservable.Listener {

    private lateinit var commandRunner: CommandRunner
    private lateinit var habit: Habit
    private lateinit var preferences: Preferences
    private var initialDatasetId: String? = null
    internal var habitBinding: HistoryEditorHabitBinding? = null
    private var effectiveReads: EffectiveHabitReadController? = null
    private val onEffectiveChange: () -> Unit = {
        if (::dataView.isInitialized) dataView.post { if (isResumed) refreshData() }
    }
    lateinit var dataView: AndroidDataView

    private var chart: HistoryChart? = null
    private var onDateClickedListener: OnDateClickedListener? = null
    private lateinit var themeSwitcher: AndroidThemeSwitcher

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        clearCurrentDialog()
        val component = (requireActivity().application as HabitsApplication).component
        commandRunner = component.commandRunner
        initialDatasetId = component.session.datasetId
        val habitId = requireArguments().getLong("habit")
        val initialHabit = component.habitList.getById(habitId)
        if (initialHabit == null) {
            dismissAllowingStateLoss()
            return Dialog(requireContext(), R.style.CustomTransparentDialogTheme)
        }
        habit = initialHabit
        habitBinding = HistoryEditorHabitBinding(component.habitList)
        habitBinding?.initialize(initialHabit)
        preferences = component.preferences
        effectiveReads = component.effectiveHabitReads

        themeSwitcher = AndroidThemeSwitcher(requireActivity(), preferences)
        themeSwitcher.apply()

        val savedOffset = savedInstanceState?.getInt("dataOffset", 0) ?: 0
        chart = HistoryChart(
            dateFormatter = JavaLocalDateFormatter(Locale.getDefault()),
            firstWeekday = preferences.firstWeekday,
            paletteColor = habit.color,
            series = emptyList(),
            defaultSquare = HistoryChart.Square.OFF,
            notesIndicators = emptyList(),
            theme = themeSwitcher.currentTheme,
            today = getToday(),
            onDateClickedListener = onDateClickedListener ?: object : OnDateClickedListener {},
            padding = 6.0
        ).apply {
            squareSpacing = 2.0
            dataOffset = savedOffset
        }
        dataView = AndroidDataView(requireContext(), null)
        dataView.view = chart!!

        val palette = SettingsThemePaletteResolver.resolve(requireContext(), preferences)
        val colors = UHabitComposeColors.fromPalette(palette)
        val dialog = Dialog(requireContext(), R.style.CustomTransparentDialogTheme)

        val composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors = colors) {
                    val currentColors = LocalUHabitComposeColors.current
                    val dialogSurface = if (currentColors.isPureBlack) {
                        androidx.compose.ui.graphics.Color(UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB)
                    } else {
                        androidx.compose.ui.graphics.Color(currentColors.surface)
                    }
                    val harmonizedTheme = rememberHarmonizedChartTheme(
                        baseTheme = themeSwitcher.currentTheme,
                        colors = currentColors,
                        surfaceColor = dialogSurface
                    )
                    chart?.theme = harmonizedTheme

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                dismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    // Consume clicks inside the dialog card
                                }
                        ) {
                            HistoryEditorDialogContent(
                                habitName = habit.name,
                                habitColorArgb = themeSwitcher.currentTheme.color(habit.color).toInt(),
                                dataView = dataView.apply {
                                    (parent as? ViewGroup)?.removeView(this)
                                },
                                onDismiss = { dismiss() },
                                colors = currentColors
                            )
                        }
                    }
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }

        currentDialog = dialog
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("dataOffset", chart?.dataOffset ?: 0)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        currentDialog = null
    }

    override fun onResume() {
        super.onResume()
        val component = (activity?.application as? HabitsApplication)?.component
        component?.habitList?.observable?.addListener(this)
        commandRunner.addListener(this)
        effectiveReads?.revision?.addListener(onEffectiveChange)
        effectiveReads?.revision?.addRollbackListener(onEffectiveChange)
        refreshData()
    }

    override fun onPause() {
        val component = (activity?.application as? HabitsApplication)?.component
        component?.habitList?.observable?.removeListener(this)
        commandRunner.removeListener(this)
        effectiveReads?.revision?.removeListener(onEffectiveChange)
        effectiveReads?.revision?.removeRollbackListener(onEffectiveChange)
        super.onPause()
    }

    override fun onModelChange() {
        if (::dataView.isInitialized) dataView.post { if (isResumed) refreshData() }
    }

    fun setOnDateClickedListener(listener: OnDateClickedListener) {
        onDateClickedListener = listener
        chart?.onDateClickedListener = listener
    }

    private fun refreshData() {
        val app = activity?.application as? HabitsApplication
        val component = app?.component
        if (habitBinding != null) {
            val resolution = habitBinding!!.resolve(
                habitId = arguments?.getLong("habit") ?: (if (::habit.isInitialized) habit.id else null),
                habitUuid = if (::habit.isInitialized) habit.uuid else null,
                isDatasetRebuilding = app?.isDatasetRebuilding == true,
                currentSessionDatasetId = component?.session?.datasetId,
                initialDatasetId = initialDatasetId
            )
            when (resolution) {
                is HistoryEditorResolutionResult.SessionMismatched,
                is HistoryEditorResolutionResult.HabitDeleted -> {
                    dismissAllowingStateLoss()
                    return
                }
                is HistoryEditorResolutionResult.Rebound -> {
                    habit = resolution.habit
                    if (resolution.habitChanged) {
                        chart?.paletteColor = resolution.habit.color
                    }
                }
            }
        }
        // Only the presentation is projected. Date clicks retain the parent's presenter,
        // whose numerical editor reads canonical manual entries from the real Habit.
        val currentTheme = chart?.theme ?: themeSwitcher.currentTheme
        val model = runCatching {
            val snapshot = effectiveReads?.snapshot(habit)
            if (snapshot == null) HistoryCardPresenter.buildState(habit, preferences.firstWeekday, currentTheme)
            else ShowHabitPresenter.buildState(habit, preferences, currentTheme, snapshot).history
        }.getOrElse {
            android.widget.Toast.makeText(requireContext(), R.string.focus_credit_unavailable, android.widget.Toast.LENGTH_LONG).show()
            dismissAllowingStateLoss()
            return
        }
        chart?.series = model.series
        chart?.defaultSquare = model.defaultSquare
        chart?.notesIndicators = model.notesIndicators
        dataView.postInvalidate()
    }

    override fun onCommandFinished(command: Command) {
        refreshData()
    }

    companion object {
        // HistoryEditorDialog handles multiple dialogs on its own,
        // because sometimes we want it to be shown under another dialog (e.g. NumberPopup)
        var currentDialog: Dialog? = null
        fun clearCurrentDialog() {
            currentDialog?.dismiss()
            currentDialog = null
        }
    }
}
