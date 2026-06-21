package org.isoron.uhabits.utils

import android.content.Context
import org.isoron.platform.io.begin
import org.isoron.platform.io.commit
import org.isoron.platform.io.run
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.widgets.WidgetUpdater
import kotlin.random.Random

object DemoDataGenerator {

    fun generate(
        context: Context,
        modelFactory: SQLModelFactory,
        habitList: HabitList,
        widgetUpdater: WidgetUpdater?,
        cache: HabitCardListCache,
        isReset: Boolean
    ) {
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Demo data generation is only allowed in debug builds.")
        }
        
        val db = modelFactory.database
        val blockRepo = modelFactory.habitBlockRepository
        
        // 1. Wipe database or selectively delete demo items
        if (isReset) {
            habitList.removeAll() // Clears habits, extensions, repetitions in DB, and in-memory list
            db.run("DELETE FROM HabitBlocks")
        } else {
            // Delete only demo habits
            val toRemove = habitList.filter { it.name.startsWith("[Demo] ") }
            for (h in toRemove) {
                habitList.remove(h)
            }
            // Delete only demo blocks
            val demoBlocks = blockRepo.findAll().filter { it.name.startsWith("[Demo] ") }
            for (b in demoBlocks) {
                blockRepo.delete(b.id!!)
            }
        }
        
        // 2. Insert Habit Blocks (spheres)
        val blockIds = mutableMapOf<String, Long>()
        
        val blocks = listOf(
            HabitBlockData(name = "[Demo] Health", color = 7, icon = "favorite", position = 0),
            HabitBlockData(name = "[Demo] Study", color = 11, icon = "school", position = 1),
            HabitBlockData(name = "[Demo] Sport", color = 6, icon = "directions_run", position = 2),
            HabitBlockData(name = "[Demo] Mind", color = 13, icon = "spa", position = 3),
            HabitBlockData(name = "[Demo] Work", color = 1, icon = "work", position = 4),
            HabitBlockData(name = "[Demo] Household", color = 16, icon = "home", position = 5)
        )
        
        for (b in blocks) {
            val id = blockRepo.insert(b)
            blockIds[b.name] = id
        }
        
        // Spec definition helper class
        class DemoHabitSpec(
            val name: String,
            val description: String,
            val question: String,
            val type: HabitType,
            val targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
            val targetValue: Double = 0.0,
            val unit: String = "",
            val color: PaletteColor,
            val frequency: Frequency = Frequency.DAILY,
            val dayTier: DayTier = DayTier.NORMAL,
            val blockName: String? = null,
            val timerEnabled: Boolean = false,
            val isArchived: Boolean = false,
            val generator: (LocalDate, Int, Random) -> Int? // returns value to insert, or null if skipped/none
        )
        
        // Today and 730 days ago
        val today = getToday()
        val startDate = today.minus(730)
        
        // 3. Define all the habits specs
        val specs = listOf(
            // Health sphere
            DemoHabitSpec(
                name = "Drink Water",
                description = "Keep hydrated throughout the day",
                question = "Did you drink at least 2L of water today?",
                type = HabitType.YES_NO,
                color = PaletteColor(8),
                dayTier = DayTier.MINIMUM,
                blockName = "Health"
            ) { _, dayOffset, rand ->
                // Missed period between 400 and 440
                if (dayOffset in 400..440) {
                    if (dayOffset % 7 == 0) Entry.SKIP else Entry.NO
                } else if (dayOffset in 100..300 || dayOffset in 450..600) {
                    // Long streak test
                    if (dayOffset % 15 == 0) Entry.SKIP else Entry.YES_MANUAL
                } else {
                    val randVal = rand.nextDouble()
                    if (randVal < 0.85) Entry.YES_MANUAL else if (randVal < 0.90) Entry.SKIP else Entry.NO
                }
            },
            DemoHabitSpec(
                name = "Take Vitamins",
                description = "Daily supplements",
                question = "Did you take your vitamins?",
                type = HabitType.YES_NO,
                color = PaletteColor(15),
                dayTier = DayTier.MINIMUM,
                blockName = "Health"
            ) { _, dayOffset, _ ->
                // Broken streaks: 20 days on, 5 days off
                val mod = dayOffset % 25
                if (mod < 20) {
                    Entry.YES_MANUAL
                } else {
                    if (mod == 22) Entry.SKIP else Entry.NO
                }
            },
            DemoHabitSpec(
                name = "Sleep 8 hours",
                description = "Track sleep duration",
                question = "How long did you sleep last night?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 8.0,
                unit = "h",
                color = PaletteColor(10),
                dayTier = DayTier.NORMAL,
                blockName = "Health"
            ) { _, _, rand ->
                val randVal = rand.nextDouble()
                when {
                    randVal < 0.05 -> Entry.SKIP
                    randVal < 0.15 -> 0
                    randVal < 0.40 -> (4.0 + rand.nextDouble() * 3.5).toInt() * 1000
                    randVal < 0.85 -> (8.0 + rand.nextDouble() * 2.0).toInt() * 1000
                    else -> (10.0 + rand.nextDouble() * 3.0).toInt() * 1000
                }
            },
            DemoHabitSpec(
                name = "Eat Junk Food",
                description = "Avoid unhealthy meals",
                question = "How many times did you eat junk food today?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_MOST,
                targetValue = 1.0,
                unit = "times",
                color = PaletteColor(0),
                dayTier = DayTier.OPTIONAL,
                blockName = "Health"
            ) { _, _, rand ->
                val randVal = rand.nextDouble()
                when {
                    randVal < 0.50 -> 0
                    randVal < 0.80 -> 1000
                    else -> (2 + rand.nextInt(3)) * 1000
                }
            },
            
            // Study sphere
            DemoHabitSpec(
                name = "Read Book",
                description = "Read non-fiction literature",
                question = "How many pages did you read today?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 20.0,
                unit = "pages",
                color = PaletteColor(11),
                dayTier = DayTier.NORMAL,
                blockName = "Study"
            ) { _, _, rand ->
                val randVal = rand.nextDouble()
                when {
                    randVal < 0.30 -> 0
                    randVal < 0.50 -> (5 + rand.nextInt(14)) * 1000
                    else -> (20 + rand.nextInt(15)) * 1000
                }
            },
            DemoHabitSpec(
                name = "Write Code",
                description = "Contribute to pet projects",
                question = "Did you code today?",
                type = HabitType.YES_NO,
                color = PaletteColor(12),
                dayTier = DayTier.IDEAL,
                blockName = "Study"
            ) { _, dayOffset, rand ->
                // Missed period between 400 and 460
                if (dayOffset in 400..460) {
                    Entry.NO
                } else {
                    if (rand.nextDouble() < 0.75) Entry.YES_MANUAL else Entry.NO
                }
            },
            DemoHabitSpec(
                name = "Learn Russian / Выучить русские слова",
                description = "Expand vocabulary",
                question = "How many words did you learn?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 10.0,
                unit = "words",
                color = PaletteColor(13),
                dayTier = DayTier.NORMAL,
                blockName = "Study"
            ) { _, _, rand ->
                val randVal = rand.nextDouble()
                when {
                    randVal < 0.40 -> 0
                    randVal < 0.55 -> (2 + rand.nextInt(7)) * 1000
                    else -> (10 + rand.nextInt(10)) * 1000
                }
            },
            
            // Sport sphere
            DemoHabitSpec(
                name = "Morning Stretch / Утренняя разминка",
                description = "Get blood flowing",
                question = "Did you stretch this morning?",
                type = HabitType.YES_NO,
                color = PaletteColor(5),
                dayTier = DayTier.MINIMUM,
                blockName = "Sport"
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.60) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Running",
                description = "Cardio training",
                question = "How far did you run today?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 5.0,
                unit = "km",
                color = PaletteColor(6),
                frequency = Frequency.THREE_TIMES_PER_WEEK,
                dayTier = DayTier.NORMAL,
                blockName = "Sport"
            ) { date, _, rand ->
                val dow = date.dayOfWeek
                val runDay = dow == DayOfWeek.MONDAY || dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.FRIDAY
                if (runDay && rand.nextDouble() < 0.80) {
                    ((5.0 + rand.nextDouble() * 5.0).toInt()) * 1000
                } else {
                    0
                }
            },
            DemoHabitSpec(
                name = "Gym Workout",
                description = "Strength training session",
                question = "How long was your workout?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 60.0,
                unit = "min",
                color = PaletteColor(7),
                frequency = Frequency.THREE_TIMES_PER_WEEK,
                dayTier = DayTier.NORMAL,
                blockName = "Sport",
                timerEnabled = true
            ) { date, _, rand ->
                val dow = date.dayOfWeek
                val gymDay = dow == DayOfWeek.TUESDAY || dow == DayOfWeek.THURSDAY || dow == DayOfWeek.SATURDAY
                if (gymDay && rand.nextDouble() < 0.85) {
                    ((60.0 + rand.nextDouble() * 30.0).toInt()) * 1000
                } else {
                    0
                }
            },
            
            // Mind sphere
            DemoHabitSpec(
                name = "Meditation / Медитация 🧘",
                description = "Focus on breath",
                question = "How long did you meditate?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 15.0,
                unit = "min",
                color = PaletteColor(4),
                dayTier = DayTier.NORMAL,
                blockName = "Mind",
                timerEnabled = true
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.70) {
                    ((15.0 + rand.nextDouble() * 15.0).toInt()) * 1000
                } else {
                    0
                }
            },
            DemoHabitSpec(
                name = "Gratitude Journal / Дневник благодарности",
                description = "Write three things you are grateful for",
                question = "Did you write in your journal today?",
                type = HabitType.YES_NO,
                color = PaletteColor(3),
                dayTier = DayTier.OPTIONAL,
                blockName = "Mind"
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.80) Entry.YES_MANUAL else Entry.NO
            },
            
            // Work sphere
            DemoHabitSpec(
                name = "Check Email / Проверка почты",
                description = "Inbox zero",
                question = "Did you clean your inbox?",
                type = HabitType.YES_NO,
                color = PaletteColor(0xFF000000.toInt()), // Custom black
                dayTier = DayTier.MINIMUM,
                blockName = "Work"
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.95) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Deep Work / Фокусированная работа",
                description = "Uninterrupted work hours",
                question = "How many deep work hours today?",
                type = HabitType.NUMERICAL,
                targetType = NumericalHabitType.AT_LEAST,
                targetValue = 4.0,
                unit = "h",
                color = PaletteColor(0xFFFF0000.toInt()), // Custom red
                dayTier = DayTier.IDEAL,
                blockName = "Work"
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.65) {
                    ((4.0 + rand.nextDouble() * 4.0).toInt()) * 1000
                } else {
                    0
                }
            },
            
            // Household sphere
            DemoHabitSpec(
                name = "Clean Room / Уборка",
                description = "Weekly cleaning",
                question = "Did you clean your room today?",
                type = HabitType.YES_NO,
                color = PaletteColor(16),
                frequency = Frequency.WEEKLY,
                dayTier = DayTier.OPTIONAL,
                blockName = "Household"
            ) { date, _, rand ->
                if (date.dayOfWeek == DayOfWeek.SUNDAY && rand.nextDouble() < 0.90) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Water Plants",
                description = "Keep house green",
                question = "Did you water the plants?",
                type = HabitType.YES_NO,
                color = PaletteColor(0xFF00FF00.toInt()), // Custom green
                frequency = Frequency.TWO_TIMES_PER_WEEK,
                dayTier = DayTier.OPTIONAL,
                blockName = "Household"
            ) { date, _, rand ->
                val dow = date.dayOfWeek
                val waterDay = dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.SATURDAY
                if (waterDay && rand.nextDouble() < 0.95) Entry.YES_MANUAL else Entry.NO
            },
            
            // Other / Unassigned
            DemoHabitSpec(
                name = "No Sphere Habit",
                description = "Test habit without a block",
                question = "Is this done?",
                type = HabitType.YES_NO,
                color = PaletteColor(18),
                dayTier = DayTier.NORMAL
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.50) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Archived Habit",
                description = "Legacy habit",
                question = "Done?",
                type = HabitType.YES_NO,
                color = PaletteColor(19),
                dayTier = DayTier.NORMAL,
                isArchived = true
            ) { _, dayOffset, rand ->
                // Only active in first 300 days
                if (dayOffset < 300 && rand.nextDouble() < 0.80) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Extreme Color Habit (White)",
                description = "To check contrast issues",
                question = "Done?",
                type = HabitType.YES_NO,
                color = PaletteColor(0xFFFFFFFF.toInt()),
                dayTier = DayTier.NORMAL
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.70) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Extreme Color Habit (Blue)",
                description = "Check pure blue",
                question = "Done?",
                type = HabitType.YES_NO,
                color = PaletteColor(0xFF0000FF.toInt()),
                dayTier = DayTier.NORMAL
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.70) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Extreme Color Habit (Grey)",
                description = "Check mid grey",
                question = "Done?",
                type = HabitType.YES_NO,
                color = PaletteColor(0xFF808080.toInt()),
                dayTier = DayTier.NORMAL
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.70) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Very Long Name Habit: This is a habit with an extremely long name designed to test text wrapping and truncation in various cards and screens",
                description = "UI wrapping check",
                question = "Is this long name habit completed today?",
                type = HabitType.YES_NO,
                color = PaletteColor(2),
                dayTier = DayTier.NORMAL
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.60) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Short",
                description = "UI minimal check",
                question = "Done?",
                type = HabitType.YES_NO,
                color = PaletteColor(1),
                dayTier = DayTier.NORMAL
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.85) Entry.YES_MANUAL else Entry.NO
            },
            DemoHabitSpec(
                name = "Очень длинное название привычки для проверки: Длинное описание привычки на русском языке, предназначенное для проверки правильности переноса текста и ограничения длины на различных экранах приложения",
                description = "Проверка переноса текста",
                question = "Вы выполнили эту сверхдлинную привычку сегодня?",
                type = HabitType.YES_NO,
                color = PaletteColor(3),
                dayTier = DayTier.NORMAL
            ) { _, _, rand ->
                if (rand.nextDouble() < 0.60) Entry.YES_MANUAL else Entry.NO
            },
            
            // Recent active habit
            DemoHabitSpec(
                name = "New Habit (Recent)",
                description = "Added recently",
                question = "Did you complete the new habit?",
                type = HabitType.YES_NO,
                color = PaletteColor(4),
                dayTier = DayTier.NORMAL
            ) { _, dayOffset, rand ->
                // Only active in the last 15 days (day 715 to 730)
                if (dayOffset >= 715) {
                    if (rand.nextDouble() < 0.80) Entry.YES_MANUAL else Entry.NO
                } else {
                    null
                }
            }
        )
        
        // 4. Insert all habits and repetitions
        db.begin()
        try {
            val rand = Random(42) // Fixed random seed
            val entryRepo = modelFactory.entryRepository
            
            for (spec in specs) {
                // Build Habit object
                val habit = modelFactory.buildHabit()
                habit.name = "[Demo] " + spec.name // clearly label seeded data as demo data
                habit.description = spec.description
                habit.question = spec.question
                habit.type = spec.type
                habit.targetType = spec.targetType
                habit.targetValue = spec.targetValue
                habit.unit = spec.unit
                habit.color = spec.color
                habit.frequency = spec.frequency
                habit.dayTier = spec.dayTier
                habit.timerEnabled = spec.timerEnabled
                habit.isArchived = spec.isArchived
                habit.blockId = spec.blockName?.let { blockIds["[Demo] " + it] }
                
                // Add to habitList, which inserts main row + extensions and adds to in-memory list
                habitList.add(habit)
                val habitId = habit.id!!
                
                // Generate historical repetitions
                for (dayOffset in 0..730) {
                    val date = startDate.plus(dayOffset)
                    val value = spec.generator(date, dayOffset, rand)
                    if (value != null) {
                        val hasNote = value > 0 && rand.nextDouble() < 0.1
                        val data = EntryData(
                            habitId = habitId,
                            timestamp = date.unixTime,
                            value = value,
                            notes = if (hasNote) "Demo note for ${spec.name} on ${date.toCSVString()}" else ""
                        )
                        entryRepo.insert(data)
                    }
                }
            }
            
            db.commit()
        } catch (e: Exception) {
            // Commit on failure is standard for Loop DB, but let's try to roll back or re-throw
            throw e
        }
        
        // 5. Force recomputation of computed entries, scores, and streaks in-memory
        for (habit in habitList) {
            val originalEntries = habit.originalEntries
            if (originalEntries is SQLiteEntryList) {
                originalEntries.isLoaded = false // Clear cache to load from DB
            }
            habit.recompute()
        }
        
        // 6. Notify observers and update widgets/cache
        habitList.observable.notifyListeners()
        cache.refreshAllHabits()
        widgetUpdater?.updateWidgets()
    }
}
