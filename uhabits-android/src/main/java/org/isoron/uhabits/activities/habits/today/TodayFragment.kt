package org.isoron.uhabits.activities.habits.today

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.core.commands.BatchCreateRepetitionCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.ui.screens.habits.today.TodayScreenStateBuilder
import org.isoron.uhabits.intents.IntentFactory

class TodayFragment : Fragment(), CommandRunner.Listener {
    private var todayView: TodayView? = null
    private val component
        get() = (requireContext().applicationContext as HabitsApplication).component

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

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

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.today, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.actionSkipDay) {
            showSkipDayDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showSkipDayDialog() {
        val context = requireContext()
        val todayDate = org.isoron.platform.time.getToday()
        
        val allHabits = component.habitList
        val remainingHabits = allHabits.filter { habit ->
            val origVal = habit.originalEntries.get(todayDate).value
            if (origVal != Entry.UNKNOWN) return@filter false
            
            val compVal = habit.computedEntries.get(todayDate).value
            if (compVal == Entry.YES_MANUAL || 
                compVal == Entry.YES_AUTO || 
                compVal == Entry.SKIP) {
                return@filter false
            }
            true
        }

        if (remainingHabits.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.skip_day_dialog_title)
                .setMessage(R.string.skip_day_empty_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val blocks = allHabits.getBlocks().filter { block ->
            remainingHabits.any { it.blockId == block.id }
        }
        val hasSphereless = remainingHabits.any { it.blockId == null }

        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(container)

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val radioAll = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.skip_day_all)
            isChecked = true
        }
        radioGroup.addView(radioAll)

        val radioSphere = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.skip_day_by_sphere)
        }
        if (blocks.isNotEmpty() || hasSphereless) {
            radioGroup.addView(radioSphere)
        }
        container.addView(radioGroup)

        val checklistContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val leftPad = (24 * resources.displayMetrics.density).toInt()
            val topBottomPad = (8 * resources.displayMetrics.density).toInt()
            setPadding(leftPad, topBottomPad, 0, topBottomPad)
            visibility = View.GONE
        }

        val sphereCheckboxes = blocks.map { block ->
            CheckBox(context).apply {
                text = block.name
                tag = block.id
                isChecked = true
            }
        }
        sphereCheckboxes.forEach { checklistContainer.addView(it) }

        var spherelessCheckbox: CheckBox? = null
        if (hasSphereless) {
            spherelessCheckbox = CheckBox(context).apply {
                text = context.getString(R.string.skip_day_other_sphere)
                isChecked = true
            }
            checklistContainer.addView(spherelessCheckbox)
        }
        container.addView(checklistContainer)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            checklistContainer.visibility = if (checkedId == radioSphere.id) View.VISIBLE else View.GONE
        }

        val noteEditText = EditText(context).apply {
            hint = context.getString(R.string.skip_day_note_hint)
            val topMargin = (16 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMargin, 0, 0)
            }
            layoutParams = lp
        }
        container.addView(noteEditText)

        AlertDialog.Builder(context)
            .setTitle(R.string.skip_day_dialog_title)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val note = noteEditText.text.toString()
                val habitsToSkip = if (radioAll.isChecked) {
                    remainingHabits
                } else {
                    val checkedBlockIds = sphereCheckboxes.filter { it.isChecked }.map { it.tag as Long }.toSet()
                    val skipSphereless = spherelessCheckbox?.isChecked ?: false
                    remainingHabits.filter { habit ->
                        if (habit.blockId != null) {
                            checkedBlockIds.contains(habit.blockId)
                        } else {
                            skipSphereless
                        }
                    }
                }

                if (habitsToSkip.isNotEmpty()) {
                    val cmd = BatchCreateRepetitionCommand(
                        habitList = component.habitList,
                        habits = habitsToSkip,
                        date = todayDate,
                        value = Entry.SKIP,
                        notes = note
                    )
                    component.commandRunner.run(cmd)
                }
            }
            .show()
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
