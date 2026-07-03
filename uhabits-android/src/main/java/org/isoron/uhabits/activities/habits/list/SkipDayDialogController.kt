package org.isoron.uhabits.activities.habits.list

import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.core.commands.BatchCreateRepetitionCommand
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList

class SkipDayDialogController(
    private val fragment: Fragment,
    private val habitList: HabitList,
    private val commandRunner: CommandRunner
) {
    private val context get() = fragment.requireContext()

    fun show() {
        val today = getToday()
        val remainingHabits = habitList.filter { habit ->
            val originalValue = habit.originalEntries.get(today).value
            if (originalValue != Entry.UNKNOWN) return@filter false

            val computedValue = habit.computedEntries.get(today).value
            computedValue != Entry.YES_MANUAL &&
                computedValue != Entry.YES_AUTO &&
                computedValue != Entry.SKIP
        }

        if (remainingHabits.isEmpty()) {
            CustomDialogs.showConfirmDialog(
                context = context,
                title = fragment.getString(R.string.skip_day_dialog_title),
                message = fragment.getString(R.string.skip_day_empty_message),
                isDestructive = false,
                positiveText = fragment.getString(android.R.string.ok)
            ) {}
            return
        }

        val blocks = habitList.getBlocks().filter { block ->
            remainingHabits.any { it.blockId == block.id }
        }
        val hasSphereless = remainingHabits.any { it.blockId == null }
        val density = fragment.resources.displayMetrics.density
        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(container)

        container.addView(
            TextView(context).apply {
                text = fragment.getString(R.string.skip_day_dialog_message, remainingHabits.size)
                textSize = 14f
            }
        )
        container.addView(
            TextView(context).apply {
                text = fragment.getString(R.string.skip_day_dialog_impact)
                textSize = 13f
                alpha = 0.72f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * density).toInt()
                }
            }
        )

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (18 * density).toInt()
            }
        }
        val radioAll = RadioButton(context).apply {
            id = View.generateViewId()
            text = fragment.getString(R.string.skip_day_all)
            isChecked = true
        }
        radioGroup.addView(radioAll)

        val radioSphere = RadioButton(context).apply {
            id = View.generateViewId()
            text = fragment.getString(R.string.skip_day_by_sphere)
        }
        if (blocks.isNotEmpty() || hasSphereless) radioGroup.addView(radioSphere)
        container.addView(radioGroup)

        val checklistContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val leftPad = (24 * density).toInt()
            val topBottomPad = (8 * density).toInt()
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
                text = fragment.getString(R.string.skip_day_other_sphere)
                isChecked = true
            }
            checklistContainer.addView(spherelessCheckbox)
        }
        container.addView(checklistContainer)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            checklistContainer.visibility = if (checkedId == radioSphere.id) View.VISIBLE else View.GONE
        }

        val noteEditText = EditText(context).apply {
            hint = fragment.getString(R.string.skip_day_note_hint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
            }
        }
        container.addView(noteEditText)

        val dialog = CustomDialogs.showCustomViewDialog(
            context = context,
            title = fragment.getString(R.string.skip_day_dialog_title),
            contentView = scrollView,
            positiveText = fragment.getString(R.string.skip_day_confirm)
        ) {}

        dialog.findViewById<Button>(R.id.button_positive)?.setOnClickListener {
            val habitsToSkip = selectedHabits(
                remainingHabits = remainingHabits,
                radioAllChecked = radioAll.isChecked,
                sphereCheckboxes = sphereCheckboxes,
                skipSphereless = spherelessCheckbox?.isChecked ?: false
            )
            if (habitsToSkip.isEmpty()) {
                Toast.makeText(context, R.string.skip_day_empty_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            commandRunner.run(
                BatchCreateRepetitionCommand(
                    habitList = habitList,
                    habits = habitsToSkip,
                    date = today,
                    value = Entry.SKIP,
                    notes = noteEditText.text.toString()
                )
            )
            dialog.dismiss()
        }
    }

    private fun selectedHabits(
        remainingHabits: List<Habit>,
        radioAllChecked: Boolean,
        sphereCheckboxes: List<CheckBox>,
        skipSphereless: Boolean
    ): List<Habit> {
        if (radioAllChecked) return remainingHabits
        val checkedBlockIds = sphereCheckboxes
            .filter { it.isChecked }
            .map { it.tag as Long }
            .toSet()
        return remainingHabits.filter { habit ->
            val blockId = habit.blockId
            if (blockId != null) checkedBlockIds.contains(blockId) else skipSphereless
        }
    }
}
