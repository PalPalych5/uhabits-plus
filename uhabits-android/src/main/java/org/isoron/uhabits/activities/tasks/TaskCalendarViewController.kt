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

package org.isoron.uhabits.activities.tasks

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TaskCalendarViewController(
    private val context: Context,
    private val container: LinearLayout,
    private var palette: SettingsThemePalette,
    private val wide: Boolean,
    private val onTaskClicked: (UserTask) -> Unit,
    private val onTaskToggleComplete: (UserTask) -> Unit,
    private val onTaskQuickMenu: (UserTask) -> Unit,
    private val onHabitClicked: (PlannedHabit) -> Unit,
    private val onDateChanged: (LocalDate) -> Unit,
    private val onScaleChanged: (CalendarScale) -> Unit
) {
    var selectedDay: LocalDate = getToday()
        set(value) {
            field = value
            calendarMonth.set(Calendar.YEAR, value.year)
            calendarMonth.set(Calendar.MONTH, value.month - 1)
            calendarMonth.set(Calendar.DAY_OF_MONTH, 1)
        }

    var scale: CalendarScale = CalendarScale.MONTH

    var calendarMonth: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }

    private var tasks: List<UserTask> = emptyList()
    private var habits: List<Habit> = emptyList()
    private var containers: Map<ContainerId, OrganizationalItem> = emptyMap()
    private var taskTags: Map<TaskId, List<TaskTag>> = emptyMap()

    private val locale: Locale
        get() = TaskDueDisplayFormatter.selectedLocale(context)

    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(context, value)

    fun updatePalette(newPalette: SettingsThemePalette) {
        palette = newPalette
    }

    fun updateData(
        newTasks: List<UserTask>,
        newHabits: List<Habit>,
        newContainers: Map<ContainerId, OrganizationalItem>,
        newTaskTags: Map<TaskId, List<TaskTag>>
    ) {
        tasks = newTasks
        habits = newHabits
        containers = newContainers
        taskTags = newTaskTags
    }

    fun render() {
        container.removeAllViews()
        container.setBackgroundColor(palette.background)

        // 1. Navigation & Period Header
        val navHeader = buildNavigationHeader()
        container.addView(navHeader)

        // 2. Scale Switcher (Segmented Control: Месяц / Неделя / День)
        val scaleSwitcher = buildScaleSwitcher()
        container.addView(scaleSwitcher)

        // Divider
        container.addView(View(context).apply {
            setBackgroundColor(palette.divider)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(1f)))

        // 3. Body according to current scale
        when (scale) {
            CalendarScale.MONTH -> renderMonthView()
            CalendarScale.WEEK -> renderWeekView()
            CalendarScale.DAY -> renderDayView()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Top Navigation Header
    // -----------------------------------------------------------------------------------------
    private fun buildNavigationHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dpInt(8f), dpInt(4f), dpInt(8f), dpInt(4f))
            minimumHeight = dpInt(48f)
        }

        val btnPrev = TextView(context).apply {
            text = "‹"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(palette.onSurface)
            setPadding(dpInt(12f), dpInt(4f), dpInt(12f), dpInt(4f))
            contentDescription = "Предыдущий период"
            setSelectableBackground()
            setOnClickListener {
                navigatePeriod(-1)
            }
        }
        row.addView(btnPrev, LinearLayout.LayoutParams(dpInt(44f), dpInt(44f)))

        val titleText = getPeriodTitle()
        val tvTitle = TextView(context).apply {
            text = titleText
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onSurface)
            gravity = Gravity.CENTER
            setSelectableBackground()
            setOnClickListener {
                // Clicking title jumps to today
                selectedDay = getToday()
                onDateChanged(selectedDay)
                render()
            }
        }
        row.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btnNext = TextView(context).apply {
            text = "›"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(palette.onSurface)
            setPadding(dpInt(12f), dpInt(4f), dpInt(12f), dpInt(4f))
            contentDescription = "Следующий период"
            setSelectableBackground()
            setOnClickListener {
                navigatePeriod(1)
            }
        }
        row.addView(btnNext, LinearLayout.LayoutParams(dpInt(44f), dpInt(44f)))

        // "Сегодня" / Today button
        val isTodaySelected = selectedDay == getToday()
        val btnToday = TextView(context).apply {
            text = if (locale.language == "ru") "Сегодня" else "Today"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (isTodaySelected) palette.accent else palette.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPaddingRelative(dpInt(10f), dpInt(6f), dpInt(10f), dpInt(6f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(16f).toFloat()
                if (isTodaySelected) {
                    setColor(palette.surfaceVariant)
                    setStroke(dpInt(1f), palette.accent)
                } else {
                    setColor(palette.surface)
                    setStroke(dpInt(1f), palette.border)
                }
            }
            setOnClickListener {
                selectedDay = getToday()
                onDateChanged(selectedDay)
                render()
            }
        }
        val todayParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dpInt(4f)
        }
        row.addView(btnToday, todayParams)

        return row
    }

    private fun getPeriodTitle(): String {
        return when (scale) {
            CalendarScale.MONTH -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, calendarMonth.get(Calendar.YEAR))
                    set(Calendar.MONTH, calendarMonth.get(Calendar.MONTH))
                    set(Calendar.DAY_OF_MONTH, 1)
                }
                SimpleDateFormat("LLLL yyyy", locale).format(cal.time).replaceFirstChar { it.uppercase(locale) }
            }
            CalendarScale.WEEK -> {
                val start = selectedDay.startOfWeek(DayOfWeek.MONDAY)
                val end = start.plus(6)
                if (start.month == end.month) {
                    val monthName = SimpleDateFormat("LLL", locale).format(Date(calendarFromLocalDate(start).timeInMillis))
                    "${start.day}–${end.day} $monthName ${start.year}"
                } else {
                    val m1 = SimpleDateFormat("LLL", locale).format(Date(calendarFromLocalDate(start).timeInMillis))
                    val m2 = SimpleDateFormat("LLL", locale).format(Date(calendarFromLocalDate(end).timeInMillis))
                    "${start.day} $m1 – ${end.day} $m2 ${end.year}"
                }
            }
            CalendarScale.DAY -> {
                val cal = calendarFromLocalDate(selectedDay)
                SimpleDateFormat("EEEE, d MMMM", locale).format(cal.time).replaceFirstChar { it.uppercase(locale) }
            }
        }
    }

    private fun navigatePeriod(delta: Int) {
        when (scale) {
            CalendarScale.MONTH -> {
                calendarMonth.add(Calendar.MONTH, delta)
                val newYear = calendarMonth.get(Calendar.YEAR)
                val newMonth = calendarMonth.get(Calendar.MONTH) + 1
                val maxDay = calendarMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
                val targetDay = minOf(selectedDay.day, maxDay)
                selectedDay = LocalDate(newYear, newMonth, targetDay)
                onDateChanged(selectedDay)
            }
            CalendarScale.WEEK -> {
                selectedDay = if (delta > 0) selectedDay.plus(7) else selectedDay.minus(7)
                onDateChanged(selectedDay)
            }
            CalendarScale.DAY -> {
                selectedDay = if (delta > 0) selectedDay.plus(1) else selectedDay.minus(1)
                onDateChanged(selectedDay)
            }
        }
        render()
    }

    // -----------------------------------------------------------------------------------------
    // Scale Switcher (Segmented Control: Месяц / Неделя / День)
    // -----------------------------------------------------------------------------------------
    private fun buildScaleSwitcher(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPaddingRelative(dpInt(16f), dpInt(4f), dpInt(16f), dpInt(8f))
        }

        val containerCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(8f).toFloat()
                setColor(palette.surface)
                setStroke(dpInt(1f), palette.border)
            }
            setPadding(dpInt(2f), dpInt(2f), dpInt(2f), dpInt(2f))
        }

        val ru = locale.language == "ru"
        val scaleItems = listOf(
            CalendarScale.MONTH to if (ru) "Месяц" else "Month",
            CalendarScale.WEEK to if (ru) "Неделя" else "Week",
            CalendarScale.DAY to if (ru) "День" else "Day"
        )

        for ((targetScale, label) in scaleItems) {
            val isSelected = scale == targetScale
            val tab = TextView(context).apply {
                text = label
                textSize = 13f
                typeface = if (isSelected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                setTextColor(if (isSelected) Color.WHITE else palette.onSurfaceVariant)
                gravity = Gravity.CENTER
                setPaddingRelative(dpInt(16f), dpInt(6f), dpInt(16f), dpInt(6f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpInt(6f).toFloat()
                    if (isSelected) {
                        setColor(palette.accent)
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                setOnClickListener {
                    if (scale != targetScale) {
                        scale = targetScale
                        onScaleChanged(scale)
                        render()
                    }
                }
            }
            containerCard.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(containerCard, LinearLayout.LayoutParams(if (wide) dpInt(360f) else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    // -----------------------------------------------------------------------------------------
    // MONTH VIEW
    // -----------------------------------------------------------------------------------------
    private fun renderMonthView() {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        container.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Weekday header (Пн, Вт, Ср, Чт, Пт, Сб, Вс)
        val weekdaysRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(dpInt(8f), dpInt(6f), dpInt(8f), dpInt(6f))
        }
        val weekdayNames = if (locale.language == "ru") {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        } else {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        }
        for (wName in weekdayNames) {
            weekdaysRow.addView(TextView(context).apply {
                text = wName
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(palette.onSurfaceVariant)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(weekdaysRow)

        // Month Grid
        val year = calendarMonth.get(Calendar.YEAR)
        val month = calendarMonth.get(Calendar.MONTH) + 1
        val daysInMonth = calendarMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        // In Calendar, SUNDAY=1, MONDAY=2... We start Monday (offset 0 for Mon, 6 for Sun)
        val offset = (calendarMonth.get(Calendar.DAY_OF_WEEK) + 5) % 7

        val taskEvents = TaskCalendarEvents.from(tasks)
        val startMonthDate = LocalDate(year, month, 1)
        val endMonthDate = LocalDate(year, month, daysInMonth)
        val monthPlannedHabits = PlannedHabitProjector.projectForRange(habits, startMonthDate, endMonthDate)

        val totalCells = offset + daysInMonth
        val weeksCount = (totalCells + 6) / 7

        for (week in 0 until weeksCount) {
            val weekRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPaddingRelative(dpInt(8f), 0, dpInt(8f), 0)
            }

            for (col in 0..6) {
                val dayNum = week * 7 + col - offset + 1
                val day = if (dayNum in 1..daysInMonth) LocalDate(year, month, dayNum) else null
                val dayTasks = if (day != null) taskEvents.filter { it.day == day } else emptyList()
                val dayHabits = if (day != null) monthPlannedHabits.filter { it.date == day } else emptyList()

                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    minimumHeight = dpInt(if (wide) 80f else 50f)
                    setPaddingRelative(dpInt(2f), dpInt(4f), dpInt(2f), dpInt(4f))
                    if (day != null) {
                        setSelectableBackground()
                        setOnClickListener {
                            selectedDay = day
                            onDateChanged(selectedDay)
                            render()
                        }
                    }
                }

                if (day != null) {
                    val isSelected = day == selectedDay
                    val isToday = day == getToday()

                    val fontScale = context.resources.configuration.fontScale
                    val circleSize = dpInt(maxOf(26f, 22f * fontScale))

                    val tvDay = TextView(context).apply {
                        text = dayNum.toString()
                        textSize = 13f
                        gravity = Gravity.CENTER
                        typeface = if (isSelected || isToday) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                        setTextColor(if (isSelected) Color.WHITE else if (isToday) palette.accent else palette.onSurface)
                        if (isSelected) {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(palette.accent)
                            }
                        } else if (isToday) {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setStroke(dpInt(1f), palette.accent)
                            }
                        }
                    }
                    cell.addView(tvDay, LinearLayout.LayoutParams(circleSize, circleSize).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    })

                    // Indicators row (Task dot, Habit dot)
                    val hasTasks = dayTasks.isNotEmpty()
                    val hasHabits = dayHabits.isNotEmpty()
                    if (hasTasks || hasHabits) {
                        val indicatorsRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER
                            setPadding(0, dpInt(2f), 0, 0)
                        }

                        if (hasTasks) {
                            // Task indicator (small circle in accent/onSurfaceVariant)
                            indicatorsRow.addView(View(context).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(if (isSelected) palette.accent else palette.onSurfaceVariant)
                                }
                            }, LinearLayout.LayoutParams(dpInt(5f), dpInt(5f)).apply {
                                marginStart = dpInt(1.5f); marginEnd = dpInt(1.5f)
                            })
                        }

                        if (hasHabits) {
                            // Habit indicator (small colored dot representing habits)
                            val firstColor = dayHabits.firstOrNull()?.color
                            val habitColor = TaskThemeUtils.resolvePaletteColor(context, firstColor, palette.accent)
                            indicatorsRow.addView(View(context).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(habitColor)
                                }
                            }, LinearLayout.LayoutParams(dpInt(5f), dpInt(5f)).apply {
                                marginStart = dpInt(1.5f); marginEnd = dpInt(1.5f)
                            })
                        }

                        cell.addView(indicatorsRow)
                    }
                }

                weekRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }

            content.addView(weekRow)
        }

        // Section divider between Month Grid and Day Agenda
        content.addView(View(context).apply {
            setBackgroundColor(palette.divider)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(1f)).apply {
            topMargin = dpInt(8f)
        })

        // Day Agenda below month grid
        renderSelectedDayAgenda(content)
    }

    private fun renderSelectedDayAgenda(parent: LinearLayout) {
        val agenda = TaskCalendarEvents.buildAgendaForDay(tasks, habits, selectedDay)

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dpInt(16f), dpInt(12f), dpInt(16f), dpInt(8f))
        }

        val cal = calendarFromLocalDate(selectedDay)
        val dayTitle = SimpleDateFormat("EEEE, d MMMM", locale).format(cal.time).replaceFirstChar { it.uppercase(locale) }

        val tvDayHeader = TextView(context).apply {
            text = dayTitle
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onSurface)
        }
        headerRow.addView(tvDayHeader, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(headerRow)

        if (!agenda.hasItems) {
            parent.addView(TextView(context).apply {
                text = if (locale.language == "ru") "Нет запланированных задач и привычек" else "No planned tasks or habits"
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                setPaddingRelative(dpInt(16f), dpInt(8f), dpInt(16f), dpInt(24f))
            })
            return
        }

        // 1. Tasks Section
        val dayTasks = agenda.allDayTasks + agenda.timedTasks.map { it.task }
        if (dayTasks.isNotEmpty()) {
            parent.addView(buildSectionHeader(if (locale.language == "ru") "Задачи" else "Tasks", dayTasks.size))

            for (task in dayTasks) {
                parent.addView(buildTaskRow(task))
            }
        }

        // 2. Habits Section
        val dayHabits = agenda.allDayHabits + agenda.timedHabits
        if (dayHabits.isNotEmpty()) {
            parent.addView(buildSectionHeader(if (locale.language == "ru") "Привычки" else "Habits", dayHabits.size))

            for (habit in dayHabits) {
                parent.addView(buildHabitRow(habit))
            }
        }

        // Bottom padding
        parent.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(24f)))
    }

    // -----------------------------------------------------------------------------------------
    // WEEK VIEW
    // -----------------------------------------------------------------------------------------
    private fun renderWeekView() {
        val weekStart = selectedDay.startOfWeek(DayOfWeek.MONDAY)
        val weekDays = (0..6).map { weekStart.plus(it) }

        val taskEvents = TaskCalendarEvents.from(tasks)
        val weekPlannedHabits = PlannedHabitProjector.projectForRange(habits, weekStart, weekDays.last())

        val ru = locale.language == "ru"
        val weekdayAbbreviations = if (ru) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        } else {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        }

        val timeColWidth = dpInt(48f)
        val hourSlotHeight = dpInt(56f)

        // Column width: on wide screen fit all 7 columns evenly; on phone use readable 104dp columns
        val dayColWidth = if (wide) 0 else dpInt(104f)
        val dayColWeight = if (wide) 1f else 0f

        val hasAnyAllDay = weekDays.any { d ->
            taskEvents.any { it.isAllDay && it.day == d } || weekPlannedHabits.any { it.isAllDay && it.date == d }
        }

        // 1. PINNED WEEK HEADER (Dates, Weekdays, and All-Day items per day column)
        val pinnedHeaderRoot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.surface)
        }

        // Left spacer to align with the vertical time column
        val leftHeaderSpacer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(palette.surface)
            minimumHeight = dpInt(48f)
            if (hasAnyAllDay) {
                addView(TextView(context).apply {
                    text = if (ru) "Весь\nдень" else "All\nday"
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(palette.onSurfaceVariant)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dpInt(2f), dpInt(4f), dpInt(2f), 0)
                })
            }
        }
        pinnedHeaderRoot.addView(leftHeaderSpacer, LinearLayout.LayoutParams(timeColWidth, ViewGroup.LayoutParams.MATCH_PARENT))

        // Right side of pinned header: HorizontalScrollView containing 7 day columns
        val headerHorizontalScroll = HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val headerColumnsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        for (i in 0..6) {
            val day = weekDays[i]
            val isSelected = day == selectedDay
            val isToday = day == getToday()
            val dayAllDayTasks = taskEvents.filter { it.isAllDay && it.day == day }
            val dayAllDayHabits = weekPlannedHabits.filter { it.isAllDay && it.date == day }

            val dayHeaderCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPaddingRelative(dpInt(4f), dpInt(6f), dpInt(4f), dpInt(6f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    if (isSelected) {
                        setColor(if (palette.isDark) 0x1AFFFFFF else 0x12000000)
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                setSelectableBackground()
                setOnClickListener {
                    if (selectedDay != day) {
                        selectedDay = day
                        onDateChanged(selectedDay)
                        render()
                    }
                }
            }

            // Weekday abbreviation ("Пн", "Вт"...)
            dayHeaderCol.addView(TextView(context).apply {
                text = weekdayAbbreviations[i]
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = if (isToday || isSelected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                setTextColor(if (isToday) palette.accent else palette.onSurfaceVariant)
            })

            // Date number ("24") with circular/rounded background
            val fontScale = context.resources.configuration.fontScale
            val circleSize = dpInt(maxOf(26f, 22f * fontScale))
            val tvDayNum = TextView(context).apply {
                text = day.day.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = if (isSelected || isToday) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                setTextColor(if (isSelected) Color.WHITE else if (isToday) palette.accent else palette.onSurface)
                if (isSelected) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(palette.accent)
                    }
                } else if (isToday) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(dpInt(1f), palette.accent)
                    }
                }
            }
            dayHeaderCol.addView(tvDayNum, LinearLayout.LayoutParams(circleSize, circleSize).apply {
                topMargin = dpInt(2f)
            })

            // All-day items for this day column (pinned, directly below date)
            if (hasAnyAllDay) {
                val dayAllDayBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dpInt(4f), 0, 0)
                }
                for (t in dayAllDayTasks) {
                    dayAllDayBox.addView(buildWeekAllDayTaskChip(t.task), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dpInt(2f)
                    })
                }
                for (h in dayAllDayHabits) {
                    dayAllDayBox.addView(buildWeekAllDayHabitChip(h), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dpInt(2f)
                    })
                }
                dayHeaderCol.addView(dayAllDayBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            headerColumnsLayout.addView(dayHeaderCol, LinearLayout.LayoutParams(dayColWidth, ViewGroup.LayoutParams.WRAP_CONTENT, dayColWeight))
        }

        headerHorizontalScroll.addView(headerColumnsLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pinnedHeaderRoot.addView(headerHorizontalScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(pinnedHeaderRoot)

        // Divider below pinned header
        container.addView(View(context).apply {
            setBackgroundColor(palette.divider)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(1f)))

        // 2. SCROLLABLE TIMELINE
        val verticalScroll = ScrollView(context).apply {
            isFillViewport = true
        }

        val timelineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Left Time Labels Column (00:00 to 23:00)
        val timeLabelCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dpInt(4f), 0, dpInt(4f), 0)
        }
        for (h in 0..23) {
            val tvHour = TextView(context).apply {
                text = String.format(Locale.getDefault(), "%02d:00", h)
                textSize = 10f
                setTextColor(palette.onSurfaceVariant)
                gravity = Gravity.TOP or Gravity.END
                minimumHeight = hourSlotHeight
                setPaddingRelative(0, dpInt(2f), dpInt(4f), 0)
            }
            timeLabelCol.addView(tvHour, LinearLayout.LayoutParams(timeColWidth, hourSlotHeight))
        }
        timelineRow.addView(timeLabelCol)

        // Right Timeline Columns (HorizontalScrollView synchronized with headerHorizontalScroll)
        val timelineHorizontalScroll = HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val timelineColumnsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)

        for (i in 0..6) {
            val day = weekDays[i]
            val isToday = day == getToday()
            val dayTimedTasks = taskEvents.filter { it.isTimed && it.day == day }
            val dayTimedHabits = weekPlannedHabits.filter { !it.isAllDay && it.date == day }

            val dayCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (day == selectedDay) {
                    setBackgroundColor(if (palette.isDark) 0x08FFFFFF else 0x06000000)
                }
            }

            for (hour in 0..23) {
                val hourCell = FrameLayout(context).apply {
                    minimumHeight = hourSlotHeight
                }

                // Top divider for each hour
                hourCell.addView(View(context).apply {
                    setBackgroundColor(palette.divider)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(1f)))

                // Right border between days
                hourCell.addView(View(context).apply {
                    setBackgroundColor(palette.divider)
                }, FrameLayout.LayoutParams(dpInt(1f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                })

                // Items in this hour
                val hourTasks = dayTimedTasks.filter { it.hour == hour }
                val hourHabits = dayTimedHabits.filter { it.reminderHour == hour }

                val itemsLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpInt(2f), dpInt(2f), dpInt(2f), dpInt(2f))
                }

                for (event in hourTasks) {
                    itemsLayout.addView(buildCompactTimelineTask(event))
                }
                for (habit in hourHabits) {
                    itemsLayout.addView(buildCompactTimelineHabit(habit))
                }
                hourCell.addView(itemsLayout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                // Current time indicator line if today and this hour
                if (isToday && hour == currentHour) {
                    val lineTop = (currentMinute / 60f * hourSlotHeight).toInt()
                    val indicator = View(context).apply {
                        setBackgroundColor(0xFFE53935.toInt()) // Red accent line
                    }
                    val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(2f)).apply {
                        topMargin = lineTop
                    }
                    hourCell.addView(indicator, params)
                }

                dayCol.addView(hourCell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hourSlotHeight))
            }

            timelineColumnsLayout.addView(dayCol, LinearLayout.LayoutParams(dayColWidth, ViewGroup.LayoutParams.WRAP_CONTENT, dayColWeight))
        }

        timelineHorizontalScroll.addView(timelineColumnsLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timelineRow.addView(timelineHorizontalScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        verticalScroll.addView(timelineRow)
        container.addView(verticalScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 3. SYNCHRONIZE HORIZONTAL SCROLLS
        if (!wide) {
            var isSyncingScroll = false
            headerHorizontalScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
                if (!isSyncingScroll) {
                    isSyncingScroll = true
                    timelineHorizontalScroll.scrollX = scrollX
                    isSyncingScroll = false
                }
            }
            timelineHorizontalScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
                if (!isSyncingScroll) {
                    isSyncingScroll = true
                    headerHorizontalScroll.scrollX = scrollX
                    isSyncingScroll = false
                }
            }

            // Scroll horizontally to selected day column
            val selectedIndex = weekDays.indexOf(selectedDay).coerceAtLeast(0)
            headerHorizontalScroll.post {
                val targetX = (selectedIndex * dayColWidth) - dpInt(16f)
                headerHorizontalScroll.scrollTo(maxOf(0, targetX), 0)
            }
        }

        // Scroll vertically to current hour or 08:00
        verticalScroll.post {
            val scrollTarget = maxOf(0, (if (weekDays.contains(getToday())) currentHour - 1 else 8) * hourSlotHeight)
            verticalScroll.scrollTo(0, scrollTarget)
        }
    }

    private fun buildWeekAllDayTaskChip(task: UserTask): View {
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpInt(26f)
            setPaddingRelative(dpInt(4f), dpInt(2f), dpInt(4f), dpInt(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(4f).toFloat()
                setColor(palette.surface)
                setStroke(dpInt(1f), palette.border)
            }
            setSelectableBackground()
            setOnClickListener { onTaskClicked(task) }
        }

        chip.addView(ImageView(context).apply {
            setImageResource(if (task.isCompleted) R.drawable.ic_tasks_completed else R.drawable.ic_cat_activity)
            setColorFilter(if (task.isCompleted) palette.accent else palette.onSurfaceVariant)
            setOnClickListener { onTaskToggleComplete(task) }
        }, LinearLayout.LayoutParams(dpInt(14f), dpInt(14f)).apply {
            marginEnd = dpInt(3f)
        })

        chip.addView(TextView(context).apply {
            text = task.title
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (task.isCompleted) palette.onSurfaceVariant else palette.onSurface)
            if (task.isCompleted) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return chip
    }

    private fun buildWeekAllDayHabitChip(habit: PlannedHabit): View {
        val habitColor = TaskThemeUtils.resolvePaletteColor(context, habit.color, palette.accent)
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpInt(26f)
            setPaddingRelative(dpInt(4f), dpInt(2f), dpInt(4f), dpInt(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(4f).toFloat()
                val bgTint = if (palette.isDark) 0x24000000 or (habitColor and 0x00FFFFFF) else 0x18000000 or (habitColor and 0x00FFFFFF)
                setColor(bgTint)
                setStroke(dpInt(1f), habitColor)
            }
            setSelectableBackground()
            setOnClickListener { onHabitClicked(habit) }
        }

        chip.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(habitColor)
            }
        }, LinearLayout.LayoutParams(dpInt(5f), dpInt(5f)).apply {
            marginEnd = dpInt(3f)
        })

        chip.addView(TextView(context).apply {
            text = habit.name
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.onSurface)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return chip
    }

    private fun buildCompactTimelineTask(event: TaskCalendarEvent): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dpInt(4f), dpInt(2f), dpInt(4f), dpInt(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(4f).toFloat()
                setColor(palette.surface)
                setStroke(dpInt(1f), palette.border)
            }
            setSelectableBackground()
            setOnClickListener { onTaskClicked(event.task) }
        }

        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", event.hour ?: 0, event.minute ?: 0)
        root.addView(TextView(context).apply {
            text = timeStr
            textSize = 9f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.accent)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(context).apply {
            text = event.task.title
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (event.task.isCompleted) palette.onSurfaceVariant else palette.onSurface)
            if (event.task.isCompleted) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            setPaddingRelative(dpInt(4f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return root
    }

    private fun buildCompactTimelineHabit(habit: PlannedHabit): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dpInt(4f), dpInt(2f), dpInt(4f), dpInt(2f))
            val habitColor = TaskThemeUtils.resolvePaletteColor(context, habit.color, palette.accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(4f).toFloat()
                setColor(palette.surface)
                setStroke(dpInt(1f), habitColor)
            }
            setSelectableBackground()
            setOnClickListener { onHabitClicked(habit) }
        }

        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", habit.reminderHour ?: 0, habit.reminderMinute ?: 0)
        val habitColor = TaskThemeUtils.resolvePaletteColor(context, habit.color, palette.accent)

        root.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(habitColor)
            }
        }, LinearLayout.LayoutParams(dpInt(6f), dpInt(6f)).apply { marginEnd = dpInt(3f) })

        root.addView(TextView(context).apply {
            text = habit.name
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.onSurface)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return root
    }

    // -----------------------------------------------------------------------------------------
    // DAY VIEW
    // -----------------------------------------------------------------------------------------
    private fun renderDayView() {
        val agenda = TaskCalendarEvents.buildAgendaForDay(tasks, habits, selectedDay)

        val ru = locale.language == "ru"

        // 1. PINNED All-Day Section (if all-day tasks or habits exist, pinned above timeline)
        if (agenda.allDayTasks.isNotEmpty() || agenda.allDayHabits.isNotEmpty()) {
            val allDayContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dpInt(16f), dpInt(8f), dpInt(16f), dpInt(8f))
                setBackgroundColor(palette.surfaceVariant)
            }

            allDayContainer.addView(TextView(context).apply {
                text = if (ru) "Весь день" else "All day"
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, 0, 0, dpInt(4f))
            })

            for (task in agenda.allDayTasks) {
                allDayContainer.addView(buildTaskRow(task))
            }
            for (habit in agenda.allDayHabits) {
                allDayContainer.addView(buildHabitRow(habit))
            }

            container.addView(allDayContainer)
            container.addView(View(context).apply {
                setBackgroundColor(palette.divider)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(1f)))
        }

        // 2. 24-Hour Timeline inside vertical ScrollView
        val verticalScroll = ScrollView(context).apply {
            isFillViewport = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 2. 24-Hour Timeline
        val hourSlotHeight = dpInt(56f)
        val now = Calendar.getInstance()
        val isToday = selectedDay == getToday()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)

        val timelineLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (hour in 0..23) {
            val hourRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = hourSlotHeight
            }

            // Left hour label
            val hourLabel = TextView(context).apply {
                text = String.format(Locale.getDefault(), "%02d:00", hour)
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(palette.onSurfaceVariant)
                gravity = Gravity.TOP or Gravity.END
                setPaddingRelative(0, dpInt(2f), dpInt(12f), 0)
            }
            hourRow.addView(hourLabel, LinearLayout.LayoutParams(dpInt(56f), ViewGroup.LayoutParams.MATCH_PARENT))

            // Right content container
            val cellFrame = FrameLayout(context).apply {
                minimumHeight = hourSlotHeight
            }

            // Hour divider line
            cellFrame.addView(View(context).apply {
                setBackgroundColor(palette.divider)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(1f)))

            // Current time indicator (if today and this hour)
            if (isToday && hour == currentHour) {
                val lineTop = (currentMinute / 60f * hourSlotHeight).toInt()
                val lineIndicator = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                // Dot
                lineIndicator.addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFE53935.toInt()) // Red indicator
                    }
                }, LinearLayout.LayoutParams(dpInt(6f), dpInt(6f)))
                // Line
                lineIndicator.addView(View(context).apply {
                    setBackgroundColor(0xFFE53935.toInt())
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(2f)))

                val lineParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(6f)).apply {
                    topMargin = lineTop
                }
                cellFrame.addView(lineIndicator, lineParams)
            }

            // Items in this hour
            val hourTasks = agenda.timedTasks.filter { it.hour == hour }
            val hourHabits = agenda.timedHabits.filter { it.reminderHour == hour }

            if (hourTasks.isNotEmpty() || hourHabits.isNotEmpty()) {
                val itemsBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPaddingRelative(dpInt(4f), dpInt(4f), dpInt(16f), dpInt(4f))
                }

                for (event in hourTasks) {
                    itemsBox.addView(buildDayTimelineTask(event))
                }
                for (habit in hourHabits) {
                    itemsBox.addView(buildHabitRow(habit))
                }
                cellFrame.addView(itemsBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            hourRow.addView(cellFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            timelineLayout.addView(hourRow)
        }

        content.addView(timelineLayout)

        // Empty state check for entire day
        if (!agenda.hasItems) {
            content.addView(TextView(context).apply {
                text = if (ru) "На этот день ничего не запланировано" else "Nothing planned for this day"
                textSize = 13f
                setTextColor(palette.onSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(dpInt(16f), dpInt(32f), dpInt(16f), dpInt(32f))
            })
        }

        // Bottom space
        content.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpInt(32f)))

        verticalScroll.addView(content)
        container.addView(verticalScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Initial scroll to current hour or 08:00
        verticalScroll.post {
            val scrollTarget = maxOf(0, (if (isToday) currentHour - 1 else 8) * hourSlotHeight)
            verticalScroll.scrollTo(0, scrollTarget)
        }
    }

    private fun buildDayTimelineTask(event: TaskCalendarEvent): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpInt(44f)
            setPaddingRelative(dpInt(8f), dpInt(4f), dpInt(8f), dpInt(4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(6f).toFloat()
                setColor(palette.surface)
                setStroke(dpInt(1f), palette.border)
            }
            setSelectableBackground()
            setOnClickListener { onTaskClicked(event.task) }
            setOnLongClickListener {
                onTaskQuickMenu(event.task)
                true
            }
        }

        // Completion checkbox
        val cbIcon = ImageView(context).apply {
            setImageResource(if (event.task.isCompleted) R.drawable.ic_tasks_completed else R.drawable.ic_cat_activity)
            setColorFilter(if (event.task.isCompleted) palette.accent else palette.onSurfaceVariant)
            setOnClickListener { onTaskToggleComplete(event.task) }
        }
        row.addView(cbIcon, LinearLayout.LayoutParams(dpInt(22f), dpInt(22f)).apply {
            marginEnd = dpInt(10f)
        })

        // Time badge
        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", event.hour ?: 0, event.minute ?: 0)
        val tvTime = TextView(context).apply {
            text = timeStr
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(4f).toFloat()
                setColor(palette.surfaceVariant)
            }
            setPaddingRelative(dpInt(6f), dpInt(2f), dpInt(6f), dpInt(2f))
        }
        row.addView(tvTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dpInt(8f)
        })

        // Task Title & details
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tvTitle = TextView(context).apply {
            text = event.task.title
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (event.task.isCompleted) palette.onSurfaceVariant else palette.onSurface)
            if (event.task.isCompleted) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }
        textCol.addView(tvTitle)

        // Container / Tags
        val containerName = event.task.containerId?.let { containers[it]?.name }
        val tags = taskTags[event.task.id].orEmpty()
        if (containerName != null || tags.isNotEmpty()) {
            val subText = buildString {
                if (containerName != null) append(containerName)
                if (tags.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(tags.joinToString(" ") { "#${it.name}" })
                }
            }
            textCol.addView(TextView(context).apply {
                text = subText
                textSize = 11f
                setTextColor(palette.onSurfaceVariant)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpInt(2f)
            })
        }

        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpInt(2f)
                bottomMargin = dpInt(2f)
            }
            layoutParams = params
        }
    }

    // -----------------------------------------------------------------------------------------
    // Shared Task & Habit Rows
    // -----------------------------------------------------------------------------------------
    private fun buildSectionHeader(title: String, count: Int): View {
        return TextView(context).apply {
            text = "$title ($count)"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.onSurfaceVariant)
            setPaddingRelative(dpInt(16f), dpInt(12f), dpInt(16f), dpInt(4f))
        }
    }

    private fun buildTaskRow(task: UserTask): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpInt(52f)
            setPaddingRelative(dpInt(16f), dpInt(6f), dpInt(16f), dpInt(6f))
            setSelectableBackground()
            setOnClickListener { onTaskClicked(task) }
            setOnLongClickListener {
                onTaskQuickMenu(task)
                true
            }
        }

        val cbIcon = ImageView(context).apply {
            setImageResource(if (task.isCompleted) R.drawable.ic_tasks_completed else R.drawable.ic_cat_activity)
            setColorFilter(if (task.isCompleted) palette.accent else palette.onSurfaceVariant)
            setOnClickListener { onTaskToggleComplete(task) }
        }
        row.addView(cbIcon, LinearLayout.LayoutParams(dpInt(22f), dpInt(22f)).apply {
            marginEnd = dpInt(12f)
        })

        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(context).apply {
            text = task.title
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (task.isCompleted) palette.onSurfaceVariant else palette.onSurface)
            if (task.isCompleted) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        })

        // Subtitle: Time if timed, container if any
        val timeStr = (task.due as? TaskDue.Timed)?.let {
            SimpleDateFormat("HH:mm", locale).format(Date(it.epochMillis))
        }
        val containerName = task.containerId?.let { containers[it]?.name }
        val tags = taskTags[task.id].orEmpty()

        val subtext = buildString {
            if (timeStr != null) append("Срок · $timeStr") else append("Срок: весь день")
            if (containerName != null) append(" · $containerName")
            if (tags.isNotEmpty()) append(" · ").append(tags.joinToString(" ") { "#${it.name}" })
        }

        labels.addView(TextView(context).apply {
            text = subtext
            textSize = 11f
            setTextColor(palette.onSurfaceVariant)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpInt(2f)
        })

        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildHabitRow(habit: PlannedHabit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpInt(48f)
            setPaddingRelative(dpInt(16f), dpInt(6f), dpInt(16f), dpInt(6f))
            setSelectableBackground()
            setOnClickListener { onHabitClicked(habit) }
        }

        val habitColor = TaskThemeUtils.resolvePaletteColor(context, habit.color, palette.accent)

        // Habit distinct badge icon
        val iconBadge = ImageView(context).apply {
            setImageResource(R.drawable.ic_cat_award)
            setColorFilter(habitColor)
        }
        row.addView(iconBadge, LinearLayout.LayoutParams(dpInt(20f), dpInt(20f)).apply {
            marginEnd = dpInt(12f)
        })

        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(context).apply {
            text = habit.name
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.onSurface)
        })

        val ru = locale.language == "ru"
        val subtitle = if (habit.reminderHour != null && habit.reminderMinute != null) {
            val time = String.format(Locale.getDefault(), "%02d:%02d", habit.reminderHour, habit.reminderMinute)
            if (ru) "Привычка · Напоминание $time" else "Habit · Reminder $time"
        } else {
            if (ru) "Привычка · По расписанию" else "Habit · Scheduled"
        }

        labels.addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
            setTextColor(palette.onSurfaceVariant)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpInt(2f)
        })

        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Distinct Pill tag "Привычка" / "Habit"
        val tagBadge = TextView(context).apply {
            text = if (ru) "Привычка" else "Habit"
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(habitColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(10f).toFloat()
                setColor(palette.surface)
                setStroke(dpInt(1f), habitColor)
            }
            setPaddingRelative(dpInt(8f), dpInt(2f), dpInt(8f), dpInt(2f))
        }
        row.addView(tagBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return row
    }

    private fun View.setSelectableBackground() {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        setBackgroundResource(value.resourceId)
        isFocusable = true
    }

    private fun calendarFromLocalDate(date: LocalDate): Calendar {
        return Calendar.getInstance().apply {
            clear()
            set(date.year, date.month - 1, date.day)
        }
    }
}
