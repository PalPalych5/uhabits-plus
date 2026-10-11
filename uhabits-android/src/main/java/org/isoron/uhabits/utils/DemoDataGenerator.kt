package org.isoron.uhabits.utils

import android.content.Context
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.containers.*
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.sqlite.*
import org.isoron.uhabits.core.rules.*
import org.isoron.uhabits.core.rules.sqlite.*
import org.isoron.uhabits.core.contribution.sqlite.*
import org.isoron.uhabits.core.userTasks.*
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.widgets.WidgetUpdater
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import kotlin.random.Random

/** Single existing developer generator. Requires an explicitly disposable, isolated offline dataset. */
object DemoDataGenerator {
    const val SEED = 20261011
    const val DISPOSABLE_MARKER = "uhabit-disposable-qa"
    fun generate(context: Context, modelFactory: SQLModelFactory, habitList: HabitList,
                 widgetUpdater: WidgetUpdater?, cache: HabitCardListCache, isReset: Boolean,
                 referenceDate: LocalDate = getToday(), seed: Int = SEED) {
        val component = HabitsApplication.component
        val session = component.session
        val marker = File(context.filesDir, "$DISPOSABLE_MARKER-${session.datasetId}")
        check(BuildConfig.DEBUG && session.isExperimental && !session.capabilities.syncEnabled &&
            !session.capabilities.remoteCallsAllowed && marker.isFile &&
            marker.readText().trim() == session.databaseFilename &&
            component.sqlModelFactory === modelFactory && component.habitList === habitList &&
            component.dbFile.canonicalFile == context.getDatabasePath(session.databaseFilename).canonicalFile &&
            session.databaseFilename != org.isoron.uhabits.core.DATABASE_FILENAME) {
            "Generate Data requires an explicitly marked disposable isolated offline QA dataset; production is forbidden"
        }
        check(!component.timerSessionManager.snapshot().hasActiveSession) { "Cannot reseed during an active Focus session" }
        check(isReset || habitList.isEmpty) { "Disposable fixture already populated; use explicit reset/reseed" }
        cache.cancelAndDrainRefreshes()
        val db = modelFactory.database
        val store = org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore(db)
        val org = OrganizationServiceImpl(store, object : UnitOfWork {
            override fun <T> executeInTransaction(block: (OrganizationTransaction) -> T): T = block(store)
        }, store, store, clock = { referenceDate.minus(181).unixTime },
            idGenerator = { error("Fixture operations require stable explicit IDs") },
            habitIdentityLookup = { ref -> modelFactory.habitRepository.findByUuid(ref.uuid) != null })
        // Reset is intentionally all-or-nothing for this disposable dataset, never prefix-delete user data.
        db.run("SAVEPOINT demo_seed")
        try {
            listOf("Contributions", "SourceRules", "FocusSessions", "UserTasks", "HabitPlacementHistory",
                "HabitPlacements", "LegacyBlockMap", "ContainerHistory").forEach { db.run("DELETE FROM $it") }
            repeat(16) { db.run("DELETE FROM Containers WHERE id NOT IN (SELECT parent_id FROM Containers WHERE parent_id IS NOT NULL)") }
            db.run("DELETE FROM OrganizationChanges WHERE revision > (SELECT cutover_revision FROM OrganizationState WHERE id = 1)")
            db.run("UPDATE OrganizationState SET current_revision = cutover_revision WHERE id = 1")
            // Normal Next APIs deliberately prohibit hard purges. This explicitly marked
            // disposable fixture reset uses the same transaction and reloads the cache.
            listOf("Repetitions", "HabitGoals", "HabitExtensions", "HabitVisualIdentities", "Habits")
                .forEach { db.run("DELETE FROM $it") }
            (habitList as org.isoron.uhabits.core.models.sqlite.SQLiteHabitList).reload()
            db.run("DELETE FROM HabitBlocks")
            val names = listOf("Учёба", "Разработка", "Проекты", "Спорт", "Здоровье", "Дом", "Личное")
            val containers = names.mapIndexed { i, name ->
                org.create(CreateContainer(name, color = PaletteColor(i + 1), opUuid = "qa-create-container-$i", explicitId = ContainerId("qa-container-$i"))).getOrThrow().id
            }.toMutableList()
            containers += org.create(CreateContainer("Курсовая работа", parentId = containers[0], opUuid = "qa-create-coursework", explicitId = ContainerId("qa-coursework"))).getOrThrow().id
            containers += org.create(CreateContainer("Мобильное приложение", parentId = containers[1], opUuid = "qa-create-android", explicitId = ContainerId("qa-android"))).getOrThrow().id
            org.create(CreateContainer("Пустой раздел", opUuid = "qa-create-empty", explicitId = ContainerId("qa-empty"))).getOrThrow()
            val titles = listOf("Читать книгу", "Изучать материалы", "Зарядка", "Прогулка", "Писать код", "Повторять лекции", "Пить воду", "Растяжка", "Порядок дома", "Планировать день", "Практика языка", "Работа над проектом", "Сон", "Экранное время", "Уборка", "Бег", "Дневник целей", "Разбор заметок", "Учебный фокус", "Ревью кода", "Отдых", "Велосипед", "Медитация", "Овощи", "Читать статьи", "Практика задач", "Домашние дела", "Подготовка занятий", "Сладости", "Социальные сети", "Архив: курс", "Новая привычка")
            val random = Random(seed)
            val habits = titles.mapIndexed { i, title ->
                modelFactory.buildHabit().apply {
                    uuid = "dec0de0a0000000000000000" + i.toString(16).padStart(8, '0')
                    name = title; description = "Демонстрационная привычка для визуальной проверки"
                    color = PaletteColor(i % 16); type = if (i % 3 == 0 || i == 18) HabitType.NUMERICAL else HabitType.YES_NO
                    targetType = if (i == 13 || i == 28 || i == 29) NumericalHabitType.AT_MOST else NumericalHabitType.AT_LEAST
                    if (i == 13 || i == 28 || i == 29) type = HabitType.NUMERICAL
                    targetValue = when (i) {
                        3, 27, 29, 30 -> 30.0
                        6 -> 8.0
                        9, 24 -> 15.0
                        12 -> 7.0
                        13, 28 -> 2.0
                        15 -> 3.0
                        18, 21 -> 45.0
                        else -> 20.0
                    }
                    unit = when (i) {
                        0 -> "стр."
                        6 -> "стак."
                        12, 13 -> "ч"
                        15 -> "км"
                        18 -> "min" // Existing Focus source rule credits minute units.
                        28 -> "порций"
                        else -> if (type == HabitType.NUMERICAL) "мин" else ""
                    }
                    frequency = if (i % 7 == 0) Frequency(12, 30) else if (i % 5 == 0) Frequency(3, 7) else Frequency.DAILY
                    isArchived = i == 30; timerEnabled = i == 18
                    habitList.add(this)
                    for (offset in 0..180) {
                        val value = when {
                            (offset + i) % 29 == 0 -> Entry.UNKNOWN
                            (offset + i) % 23 == 0 -> Entry.SKIP
                            type == HabitType.NUMERICAL -> if ((offset + i) % 11 == 0) 0 else {
                                val maximum = when (i) {
                                    6 -> 10
                                    12 -> 9
                                    13, 28 -> 4
                                    15 -> 8
                                    3, 18, 21, 27, 30 -> 90
                                    29 -> 60
                                    else -> 40
                                }
                                random.nextInt(0, maximum + 1) * 1000
                            }
                            random.nextDouble() < 0.73 -> Entry.YES_MANUAL
                            else -> Entry.NO
                        }
                        modelFactory.entryRepository.insert(EntryData(habitId = id!!, timestamp = referenceDate.minus(offset).unixTime, value = value, notes = if (offset % 31 == 1) "Плановая практика" else ""))
                    }
                    org.placeHabit(PlaceHabit(HabitRef(uuid!!), containers[i % containers.size], opUuid = "qa-place-$i", expectedRevision = org.currentRevision())).getOrThrow()
                }
            }
            val taskTitles = listOf("Завершить курсовую работу", "Повторить материалы лекции", "Реализовать экран проекта", "Проверить тесты", "Подготовить план тренировки", "Разобрать учебные заметки", "Купить продукты", "Обновить документацию", "Решить практические задачи", "Подготовить презентацию", "Прочитать главу", "Проверить ревью")
            val created = referenceDate.minus(180).unixTime
            db.run("UPDATE Containers SET created_at = $created, updated_at = $created")
            db.run("UPDATE ContainerHistory SET created_at = $created, updated_at = $created")
            db.run("UPDATE OrganizationChanges SET recorded_at = $created")
            db.run("UPDATE OrganizationState SET cutover_at = $created")
            val tasks = (0 until 84).map { i ->
                val completed = i % 5 == 0 || (i >= 72 && (i - 72) % 5 == 0)
                UserTask(TaskId("qa-task-$i"), taskTitles[i % taskTitles.size] + if (i >= 12) " · ${i / 12 + 1}" else "",
                    description = "Проверить результат и записать следующий шаг.\nДемонстрационные данные.",
                    containerId = if (i % 7 == 0) null else containers[i % containers.size],
                    status = if (completed) TaskStatus.COMPLETED else TaskStatus.PENDING,
                    plannedDate = if (i % 4 == 0) null else referenceDate.plus(i % 13 - 5),
                    deadline = if (i % 3 == 0) referenceDate.plus(i % 13 - 3).let { date -> java.util.Calendar.getInstance().apply { clear(); set(date.year, date.month - 1, date.day, 20, 0) }.timeInMillis } else null,
                    completedAt = if (completed) referenceDate.minus(i % 15).unixTime + 12 * 3600000L else null,
                    parentTaskId = if (i >= 72) TaskId("qa-task-${i - 72}") else null,
                    sortOrder = i, createdAt = created, updatedAt = referenceDate.unixTime + 23 * 3600000L)
                    .also { component.userTaskRepository.save(it) }
            }
            FocusSessionSchema.createSchema(db); SourceRuleSchema.createSchema(db); ContributionSchema.createSchema(db)
            val ruleRepo = SQLiteSourceRuleRepository(db)
            val focusRepo = SQLiteFocusSessionRepository(db)
            val target = HabitRef(habits[18].uuid!!)
            ruleRepo.record(SourceRule(SourceRuleId("qa-study-focus"), sourceContainerId = containers[0], targetHabit = target, effectiveFrom = created, createdAt = created, updatedAt = created))
            val attribution = SourceRuleAttributionService(ruleRepo, SQLiteContributionRepository(db), habitResolver = { ref -> habits.firstOrNull { it.uuid == ref.uuid }?.let { TargetHabitInfo(it.isNumerical, it.unit, it.name) } })
            for (i in 0 until 64) {
                val task = tasks[i % 72]
                val start = referenceDate.minus(i % 45 + 1).unixTime + (9 + i % 7) * 3600000L
                val duration = (15 + random.nextInt(46)) * 60000L
                val sessionFact = FocusSession(FocusSessionId("qa-focus-$i"), start, start + duration, duration, taskId = task.id, containerId = task.containerId,
                    source = FocusSessionSource.TIMER, deviceId = "disposable-qa", createdAt = start + duration, updatedAt = start + duration,
                    organizationContext = task.containerId?.let { containerId ->
                        JSONObject().put("schema", 1).put("datasetId", session.datasetId)
                            .put("organizationRevision", org.currentRevision().value)
                            .put("path", JSONArray().apply {
                                org.containerQueries.path(containerId).nodes.forEach { node ->
                                    put(JSONObject().put("id", node.value).put("name", org.containerQueries.find(node)?.name))
                                }
                            }).toString()
                    })
                focusRepo.record(sessionFact); attribution.attributeSession(sessionFact)
            }
            db.run("RELEASE SAVEPOINT demo_seed")
        } catch (failure: Throwable) {
            db.run("ROLLBACK TO SAVEPOINT demo_seed"); db.run("RELEASE SAVEPOINT demo_seed")
            (habitList as? org.isoron.uhabits.core.models.sqlite.SQLiteHabitList)?.reload()
            component.effectiveReadRevision.changed()
            cache.refreshAllHabits()
            throw failure
        }
        habitList.forEach { it.originalEntries.invalidate(); it.recompute() }
        component.effectiveReadRevision.changed()
        habitList.observable.notifyListeners(); cache.refreshAllHabits()
    }
}
