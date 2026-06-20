package org.isoron.uhabits.activities.habits.today

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.ui.screens.habits.today.TodayScreenStateBuilder
import org.isoron.uhabits.intents.IntentFactory

class TodayFragment : Fragment(), CommandRunner.Listener {
    private var todayView: TodayView? = null
    private val component
        get() = (requireContext().applicationContext as HabitsApplication).component

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val activity = requireActivity() as AppCompatActivity
        return TodayView(
            activity = activity,
            context = requireContext(),
            preferences = component.preferences,
            onHabitClick = { habitId ->
                val habit = component.habitList.getById(habitId) ?: return@TodayView
                startActivity(IntentFactory().startShowHabitActivity(requireContext(), habit))
            },
            onCreateHabit = { showCreateHabitDialog() },
            onRefresh = { refresh() }
        ).also {
            todayView = it
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        }
    }

    override fun onResume() {
        super.onResume()
        todayView?.activateToolbar()
        component.commandRunner.addListener(this)
        (activity as? MainNavigationHost)?.setHabitCreationAvailable(false)
        refresh()
    }

    override fun onPause() {
        component.commandRunner.removeListener(this)
        super.onPause()
    }

    override fun onDestroyView() {
        todayView = null
        super.onDestroyView()
    }

    override fun onCommandFinished(command: Command) = refresh()

    private fun showCreateHabitDialog() {
        HabitTypeDialog().show(parentFragmentManager, "habitType")
    }

    private fun refresh() {
        todayView?.setState(TodayScreenStateBuilder.build(component.habitList))
    }
}
