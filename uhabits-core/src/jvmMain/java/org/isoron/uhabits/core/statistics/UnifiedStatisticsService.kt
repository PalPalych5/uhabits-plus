/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionRepository
import org.isoron.uhabits.core.focus.FocusSessionRepository
import org.isoron.uhabits.core.models.EntryList
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ScoreList
import org.isoron.uhabits.core.models.StreakList
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.models.projection.HabitProjectionIntegrityException
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import java.util.concurrent.CancellationException

/** A failed domain is null with an explicit issue, never an invented zero or empty success. */
data class UnifiedStatisticsReport(
    val context: StatisticsQueryContext,
    val habits: HabitCycleStatisticsResult?,
    val tasks: TaskStatisticsResult?,
    val focus: FocusStatisticsResult?,
    val readIssues: List<StatisticsIssue>,
)

/**
 * JVM/Android read adapter. Call on the existing task runner, with the dataset's
 * revision source. It never opens/migrates a database, attributes credit or writes
 * models. The caller owns repository thread confinement and read transactions.
 */
class UnifiedStatisticsService(
    private val habitList: HabitList,
    private val tasks: UserTaskRepository,
    private val focus: FocusSessionRepository,
    private val contributions: ContributionRepository,
    private val placements: HabitPlacementQueries,
    private val containers: ContainerQueries,
) {
    fun read(
        request: StatisticsQueryContext,
        currentIdentity: () -> StatisticsReadIdentity,
        shouldCancel: () -> Boolean = { false },
    ): UnifiedStatisticsReport {
        fun checkCurrent() {
            val current = currentIdentity()
            if (shouldCancel() || current.datasetId != request.datasetId || current.revision != request.revision) {
                throw StatisticsReadInvalidatedException()
            }
        }
        checkCurrent()
        val context = try { request.copy(sectionScope = when (val scope = request.sectionScope) {
            is StatisticsSectionScope.Container -> scope.copy(
                currentDescendantIds = if (scope.includeDescendants) containers.subtreeIds(scope.id).toSet() else emptySet(),
            )
            else -> scope
        }) } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            checkCurrent()
            return UnifiedStatisticsReport(request, null, null, null, listOf(
                StatisticsIssue(StatisticsIssueCode.READ_FAILED, "sectionScope", error.message ?: "Section scope unavailable"),
            ))
        }
        checkCurrent()
        val issues = mutableListOf<StatisticsIssue>()
        fun <T> readDomain(name: String, block: () -> T): T? {
            checkCurrent()
            return try {
                block().also { checkCurrent() }
            } catch (error: StatisticsReadInvalidatedException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                checkCurrent()
                issues.add(StatisticsIssue(StatisticsIssueCode.READ_FAILED, name, error.message ?: error.javaClass.simpleName))
                null
            }
        }

        val taskResult = readDomain("tasks") {
            TaskStatisticsAggregator.aggregate(context, tasks.getAll().toList())
        }
        val focusResult = readDomain("focus") {
            FocusStatisticsAggregator.aggregate(context, focus.getByTimeRange(context.startMillis, context.endMillis), containers)
        }
        val habitResult = readDomain("habits") {
            val habits = habitList.toList().filter { habit ->
                context.sectionScope.includesCurrent(placements.current(HabitRef(requireNotNull(habit.uuid)))?.containerId)
            }.map(::detachedHabit)
            checkCurrent()
            // One ledger snapshot includes correction tombstones and the earliest
            // observation. No rule/link re-evaluation or repository write occurs.
            val refs = habits.map { HabitRef(requireNotNull(it.uuid)) }.toSet()
            val ledger = if (refs.isEmpty()) emptyList() else contributions.getByHabitsInRange(
                refs, Long.MIN_VALUE, Long.MAX_VALUE, includeRemoved = true,
            ).toList()
            val byHabit = ledger.groupBy { it.targetHabit }
            val snapshotRepository = object : ContributionRepository by contributions {
                override fun getByHabitsInRange(
                    targetHabits: Set<HabitRef>,
                    fromTimestamp: Long,
                    toTimestamp: Long,
                    includeRemoved: Boolean,
                ): List<Contribution> = targetHabits.flatMap { byHabit[it].orEmpty() }.filter {
                    it.effectiveTimestamp in fromTimestamp..toTimestamp &&
                        (includeRemoved || it.isActive)
                }.sortedWith(compareBy({ it.effectiveTimestamp }, { it.id.value }))
            }
            val projection = EffectiveHabitValueService(
                repository = snapshotRepository,
                hourOffset = context.habitHourOffset,
                minuteOffset = context.habitMinuteOffset,
                firstWeekday = context.firstWeekday,
                dateAt = context::habitDateAt,
                dayStart = context::habitDayStart,
            )
            val from = context.from.startOfMonth().startOfWeek(context.firstWeekday)
            val endMonth = context.to.startOfMonth()
            val to = endMonth.plus(endMonth.monthLength - 1).plus(7)
            val inputs = habits.map { habit ->
                checkCurrent()
                val ref = HabitRef(requireNotNull(habit.uuid))
                val observed = listOfNotNull(
                    habit.originalEntries.getEarliestDate(),
                    byHabit[ref]?.minOfOrNull { context.habitDateAt(it.effectiveTimestamp) },
                ).minOrNull()
                try {
                    StatisticsHabitInput(habit, projection.daysForRange(listOf(habit), from, to).getValue(ref), observed)
                } catch (error: HabitProjectionIntegrityException) {
                    StatisticsHabitInput(habit, emptyList(), observed, listOf(
                        StatisticsIssue(when {
                            error.message?.contains("overflow", ignoreCase = true) == true -> StatisticsIssueCode.OVERFLOW
                            error.message?.startsWith("Invalid manual value") == true -> StatisticsIssueCode.INVALID_OBSERVATION
                            else -> StatisticsIssueCode.INCOMPATIBLE_UNIT
                        }, ref.uuid, error.message ?: "Invalid Habit projection"),
                    ))
                }
            }
            checkCurrent()
            HabitCycleStatisticsAggregator.aggregate(context, inputs)
        }
        checkCurrent()
        return UnifiedStatisticsReport(context, habitResult, taskResult, focusResult, issues.toList())
    }
}

private fun detachedHabit(habit: Habit): Habit = habit.copy(
    frequency = habit.frequency.copy(),
    goalHistory = habit.goalHistory.map { it.copy(frequency = it.frequency.copy()) }.toMutableList(),
    originalEntries = EntryList().also { list -> habit.originalEntries.getKnown().forEach(list::add) },
    computedEntries = EntryList(),
    scores = ScoreList(),
    streaks = StreakList(),
)
