/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.statistics.HistoricalPathParser
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue

/** Day bucket for single-cohort task counts (e.g. T1 completed, T2 created). */
data class TaskDailyBucket(
    val date: LocalDate,
    val count: Int,
    val tasks: List<UserTask> = emptyList(),
)

/** T3 cohort summary: Due date within period P, partitioned by current state at ctx.now / ctx.today. */
data class TaskDueCohortSummary(
    val totalCount: Int,
    val completedCount: Int,
    val pendingOverdueNowCount: Int,
    val pendingNotOverdueNowCount: Int,
    val tasks: List<UserTask> = emptyList(),
    val completedTasks: List<UserTask> = emptyList(),
    val pendingOverdueNowTasks: List<UserTask> = emptyList(),
    val pendingNotOverdueNowTasks: List<UserTask> = emptyList(),
)

/** Day bucket for T3 due tasks. */
data class TaskDueDailyBucket(
    val date: LocalDate,
    val totalCount: Int,
    val completedCount: Int,
    val pendingOverdueNowCount: Int,
    val pendingNotOverdueNowCount: Int,
    val tasks: List<UserTask> = emptyList(),
)

/** T4 cohort summary: Due.None tasks created within period P, partitioned by completed/pending. */
data class TaskNoDueCohortSummary(
    val totalCount: Int,
    val completedCount: Int,
    val pendingCount: Int,
    val tasks: List<UserTask> = emptyList(),
    val completedTasks: List<UserTask> = emptyList(),
    val pendingTasks: List<UserTask> = emptyList(),
)

/** Day bucket for T4 tasks without due date created in period P. */
data class TaskNoDueDailyBucket(
    val date: LocalDate,
    val totalCount: Int,
    val completedCount: Int,
    val pendingCount: Int,
    val tasks: List<UserTask> = emptyList(),
)

/** Pure aggregated result for Tasks T1-T4. */
data class TaskStatisticsResult(
    // T1: Completed in period
    val completedCount: Int,
    val completedTasks: List<UserTask>,
    val dailyCompletedBuckets: List<TaskDailyBucket>,

    // T2: Created in period
    val createdCount: Int,
    val createdTasks: List<UserTask>,
    val dailyCreatedBuckets: List<TaskDailyBucket>,

    // T3: Due in period, current state
    val dueSummary: TaskDueCohortSummary,
    val dailyDueBuckets: List<TaskDueDailyBucket>,

    // T4: No due date, created in period
    val noDueSummary: TaskNoDueCohortSummary,
    val dailyNoDueBuckets: List<TaskNoDueDailyBucket>,

    val dataState: StatisticsDataState,
    val issues: List<StatisticsIssue> = emptyList(),
) {
    val dueCount: Int get() = dueSummary.totalCount
    val noDueCreatedCount: Int get() = noDueSummary.totalCount
}

object TaskStatisticsAggregator {

    fun aggregate(
        context: StatisticsQueryContext,
        tasks: List<UserTask>,
    ): TaskStatisticsResult {
        if (tasks.isEmpty()) {
            return emptyResult(context)
        }

        val issues = mutableListOf<StatisticsIssue>()

        // 1. Deduplication and conflicting duplicate detection across input
        val groupedById = tasks.groupBy { it.id }
        val dedupedTasks = mutableListOf<UserTask>()

        for ((taskId, group) in groupedById) {
            if (group.size == 1) {
                dedupedTasks.add(group.first())
            } else {
                val first = group.first()
                val allIdentical = group.all { it == first }
                if (allIdentical) {
                    dedupedTasks.add(first)
                } else {
                    issues.add(
                        StatisticsIssue(
                            code = StatisticsIssueCode.CONFLICTING_DUPLICATE,
                            entityId = taskId.value,
                            message = "Conflicting duplicate task definitions for ID ${taskId.value}",
                        ),
                    )
                }
            }
        }

        // 2. Timestamp quality validation
        for (task in dedupedTasks) {
            if (task.createdAt <= 0) {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.INVALID_TIMESTAMP,
                        entityId = task.id.value,
                        message = "Task ${task.id.value} has non-positive createdAt: ${task.createdAt}",
                    ),
                )
            }
            if (task.status == TaskStatus.COMPLETED) {
                if (task.completedAt == null || task.completedAt <= 0) {
                    issues.add(
                        StatisticsIssue(
                            code = StatisticsIssueCode.INVALID_TIMESTAMP,
                            entityId = task.id.value,
                            message = "Completed task ${task.id.value} has missing or non-positive completedAt: ${task.completedAt}",
                        ),
                    )
                } else if (task.completedAt < task.createdAt) {
                    issues.add(
                        StatisticsIssue(
                            code = StatisticsIssueCode.INVALID_TIMESTAMP,
                            entityId = task.id.value,
                            message = "Task ${task.id.value} completedAt (${task.completedAt}) precedes createdAt (${task.createdAt})",
                        ),
                    )
                }
            }
            if (task.due is TaskDue.Timed && task.due.epochMillis <= 0) {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.INVALID_TIMESTAMP,
                        entityId = task.id.value,
                        message = "Task ${task.id.value} has non-positive due epochMillis: ${(task.due as TaskDue.Timed).epochMillis}",
                    ),
                )
            }
        }

        // 3. Section scope filtering
        val scopedTasks = dedupedTasks.filter { context.sectionScope.includesCurrent(it.containerId) }

        // T1: Completed in period
        // Valid completedAt > 0 in P, status COMPLETED.
        // Current pending completedAt is not fake history (must be COMPLETED).
        val completedTasks = scopedTasks.filter { task ->
            task.status == TaskStatus.COMPLETED &&
                task.completedAt != null &&
                task.completedAt > 0 &&
                context.containsInstant(task.completedAt)
        }
        val completedCount = completedTasks.size
        val completedByDate = completedTasks.groupBy { context.civilDateAt(it.completedAt!!) }
        val dailyCompletedBuckets = context.dates().map { date ->
            val bucketTasks = completedByDate[date].orEmpty()
            TaskDailyBucket(date = date, count = bucketTasks.size, tasks = bucketTasks)
        }

        // T2: Created in period
        val createdTasks = scopedTasks.filter { task ->
            task.createdAt > 0 && context.containsInstant(task.createdAt)
        }
        val createdCount = createdTasks.size
        val createdByDate = createdTasks.groupBy { context.civilDateAt(it.createdAt) }
        val dailyCreatedBuckets = context.dates().map { date ->
            val bucketTasks = createdByDate[date].orEmpty()
            TaskDailyBucket(date = date, count = bucketTasks.size, tasks = bucketTasks)
        }

        // T3: Due in period · current state
        fun isDueInPeriod(task: UserTask): Boolean = when (val due = task.due) {
            is TaskDue.None -> false
            is TaskDue.Day -> context.containsDate(due.date.toLocalDate())
            is TaskDue.Timed -> context.containsInstant(due.epochMillis)
        }

        fun taskDueDate(task: UserTask): LocalDate = when (val due = task.due) {
            is TaskDue.Day -> due.date.toLocalDate()
            is TaskDue.Timed -> context.civilDateAt(due.epochMillis)
            is TaskDue.None -> error("None due in D(P)")
        }

        val dueTasks = scopedTasks.filter { isDueInPeriod(it) }
        val dueCompletedTasks = mutableListOf<UserTask>()
        val duePendingOverdueTasks = mutableListOf<UserTask>()
        val duePendingNotOverdueTasks = mutableListOf<UserTask>()

        for (task in dueTasks) {
            when {
                task.status == TaskStatus.COMPLETED -> dueCompletedTasks.add(task)
                task.isOverdue(context.today, context.now) -> duePendingOverdueTasks.add(task)
                else -> duePendingNotOverdueTasks.add(task)
            }
        }

        val dueSummary = TaskDueCohortSummary(
            totalCount = dueTasks.size,
            completedCount = dueCompletedTasks.size,
            pendingOverdueNowCount = duePendingOverdueTasks.size,
            pendingNotOverdueNowCount = duePendingNotOverdueTasks.size,
            tasks = dueTasks,
            completedTasks = dueCompletedTasks,
            pendingOverdueNowTasks = duePendingOverdueTasks,
            pendingNotOverdueNowTasks = duePendingNotOverdueTasks,
        )

        val dueByDate = dueTasks.groupBy { taskDueDate(it) }
        val dailyDueBuckets = context.dates().map { date ->
            val bucketTasks = dueByDate[date].orEmpty()
            var cCount = 0
            var oCount = 0
            var nCount = 0
            for (t in bucketTasks) {
                when {
                    t.status == TaskStatus.COMPLETED -> cCount++
                    t.isOverdue(context.today, context.now) -> oCount++
                    else -> nCount++
                }
            }
            TaskDueDailyBucket(
                date = date,
                totalCount = bucketTasks.size,
                completedCount = cCount,
                pendingOverdueNowCount = oCount,
                pendingNotOverdueNowCount = nCount,
                tasks = bucketTasks,
            )
        }

        // T4: No due date · created in period
        val noDueCreatedTasks = scopedTasks.filter { task ->
            task.due is TaskDue.None &&
                task.createdAt > 0 &&
                context.containsInstant(task.createdAt)
        }
        val noDueCompletedTasks = mutableListOf<UserTask>()
        val noDuePendingTasks = mutableListOf<UserTask>()
        for (task in noDueCreatedTasks) {
            if (task.status == TaskStatus.COMPLETED) {
                noDueCompletedTasks.add(task)
            } else {
                noDuePendingTasks.add(task)
            }
        }

        val noDueSummary = TaskNoDueCohortSummary(
            totalCount = noDueCreatedTasks.size,
            completedCount = noDueCompletedTasks.size,
            pendingCount = noDuePendingTasks.size,
            tasks = noDueCreatedTasks,
            completedTasks = noDueCompletedTasks,
            pendingTasks = noDuePendingTasks,
        )

        val noDueByDate = noDueCreatedTasks.groupBy { context.civilDateAt(it.createdAt) }
        val dailyNoDueBuckets = context.dates().map { date ->
            val bucketTasks = noDueByDate[date].orEmpty()
            var cCount = 0
            var pCount = 0
            for (t in bucketTasks) {
                if (t.status == TaskStatus.COMPLETED) cCount++ else pCount++
            }
            TaskNoDueDailyBucket(
                date = date,
                totalCount = bucketTasks.size,
                completedCount = cCount,
                pendingCount = pCount,
                tasks = bucketTasks,
            )
        }

        val dataState = when {
            issues.isNotEmpty() -> StatisticsDataState.PARTIAL
            completedTasks.isEmpty() && createdTasks.isEmpty() && dueTasks.isEmpty() -> StatisticsDataState.EMPTY
            else -> StatisticsDataState.COMPLETE
        }

        return TaskStatisticsResult(
            completedCount = completedCount,
            completedTasks = completedTasks,
            dailyCompletedBuckets = dailyCompletedBuckets,
            createdCount = createdCount,
            createdTasks = createdTasks,
            dailyCreatedBuckets = dailyCreatedBuckets,
            dueSummary = dueSummary,
            dailyDueBuckets = dailyDueBuckets,
            noDueSummary = noDueSummary,
            dailyNoDueBuckets = dailyNoDueBuckets,
            dataState = dataState,
            issues = issues,
        )
    }

    private fun emptyResult(context: StatisticsQueryContext): TaskStatisticsResult {
        val emptyDaily = context.dates().map { TaskDailyBucket(date = it, count = 0) }
        val emptyDueDaily = context.dates().map {
            TaskDueDailyBucket(
                date = it,
                totalCount = 0,
                completedCount = 0,
                pendingOverdueNowCount = 0,
                pendingNotOverdueNowCount = 0,
            )
        }
        val emptyNoDueDaily = context.dates().map {
            TaskNoDueDailyBucket(
                date = it,
                totalCount = 0,
                completedCount = 0,
                pendingCount = 0,
            )
        }
        return TaskStatisticsResult(
            completedCount = 0,
            completedTasks = emptyList(),
            dailyCompletedBuckets = emptyDaily,
            createdCount = 0,
            createdTasks = emptyList(),
            dailyCreatedBuckets = emptyDaily,
            dueSummary = TaskDueCohortSummary(0, 0, 0, 0),
            dailyDueBuckets = emptyDueDaily,
            noDueSummary = TaskNoDueCohortSummary(0, 0, 0),
            dailyNoDueBuckets = emptyNoDueDaily,
            dataState = StatisticsDataState.EMPTY,
            issues = emptyList(),
        )
    }
}

/** Section categories for disjoint F3 breakdown. */
enum class FocusSectionCategory {
    ROOT_CONTAINER,
    CHILD_CONTAINER,
    DIRECT,
    NO_SECTION,
    DELETED,
    PATHLESS,
}

/** Disjoint branch item in F3 "По разделам" breakdown. */
data class FocusSectionBreakdownItem(
    val category: FocusSectionCategory,
    val containerId: ContainerId? = null,
    val name: String,
    val durationMs: Long,
    val sessionCount: Int,
    val sessions: List<FocusSession> = emptyList(),
)

/** Grouped target item in F4 "По объектам" breakdown. */
data class FocusTargetBreakdownItem(
    val primaryTargetType: FocusPrimaryTargetType?,
    val targetId: String? = null,
    val name: String,
    val durationMs: Long,
    val sessionCount: Int,
    val sessions: List<FocusSession> = emptyList(),
)

/** Summary item grouped purely by primaryTargetType. */
data class FocusTargetTypeSummary(
    val targetType: FocusPrimaryTargetType?,
    val durationMs: Long,
    val sessionCount: Int,
    val sessions: List<FocusSession> = emptyList(),
)

/** Day bucket for focus activity in period P. */
data class FocusDailyBucket(
    val date: LocalDate,
    val durationMs: Long,
    val sessionCount: Int,
    val sessions: List<FocusSession> = emptyList(),
)

/** Pure aggregated result for Focus F1-F4. */
data class FocusStatisticsResult(
    val totalDurationMs: Long,
    val sessionCount: Int,
    val averageSessionDurationMs: Long?,
    val sessions: List<FocusSession>,
    val dailyBuckets: List<FocusDailyBucket>,
    val sectionBreakdowns: List<FocusSectionBreakdownItem>,
    val targetBreakdowns: List<FocusTargetBreakdownItem>,
    val targetTypeSummaries: List<FocusTargetTypeSummary>,
    val dataState: StatisticsDataState,
    val issues: List<StatisticsIssue> = emptyList(),
)

/** F4 restricted to an already-selected F3 branch; no new repository reads. */
fun FocusStatisticsResult.targetsWithin(selection: List<FocusSession>): List<FocusTargetBreakdownItem> {
    val ids = selection.mapTo(hashSetOf()) { it.id }
    return targetBreakdowns.mapNotNull { target ->
        val selected = target.sessions.filter { it.id in ids }
        if (selected.isEmpty()) null else target.copy(
            sessions = selected, sessionCount = selected.size,
            durationMs = selected.fold(0L) { total, session -> Math.addExact(total, session.durationMs) },
        )
    }.sortedWith(compareByDescending<FocusTargetBreakdownItem> { it.durationMs }.thenBy { it.name })
}

object FocusStatisticsAggregator {

    private fun safeAdd(a: Long, b: Long, onOverflow: () -> Unit): Long {
        return try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            onOverflow()
            Long.MAX_VALUE
        }
    }

    fun aggregate(
        context: StatisticsQueryContext,
        sessions: List<FocusSession>,
        containerQueries: ContainerQueries? = null,
    ): FocusStatisticsResult {
        // One lookup per distinct container in this immutable report, not per session.
        // Cache lifetime is deliberately one read, so tree edits never reuse old names.
        val lookup = containerQueries?.let { source ->
            object : ContainerQueries by source {
                val containers = mutableMapOf<Pair<ContainerId, Boolean>, org.isoron.uhabits.core.containers.Container?>()
                val roots = mutableMapOf<ContainerId, ContainerId?>()
                override fun find(id: ContainerId, includeDeleted: Boolean): org.isoron.uhabits.core.containers.Container? {
                    val key = id to includeDeleted
                    if (!containers.containsKey(key)) containers[key] = source.find(id, includeDeleted)
                    return containers[key]
                }
                override fun rootId(id: ContainerId): ContainerId? {
                    if (!roots.containsKey(id)) roots[id] = source.rootId(id)
                    return roots[id]
                }
            }
        }
        if (sessions.isEmpty()) {
            return emptyResult(context)
        }

        val issues = mutableListOf<StatisticsIssue>()

        // 1. Deduplication and conflicting duplicate detection across input
        val groupedById = sessions.groupBy { it.id }
        val dedupedSessions = mutableListOf<FocusSession>()

        for ((sessionId, group) in groupedById) {
            if (group.size == 1) {
                dedupedSessions.add(group.first())
            } else {
                val first = group.first()
                val allIdentical = group.all { it == first }
                if (allIdentical) {
                    dedupedSessions.add(first)
                } else {
                    issues.add(
                        StatisticsIssue(
                            code = StatisticsIssueCode.CONFLICTING_DUPLICATE,
                            entityId = sessionId.value,
                            message = "Conflicting duplicate focus session definitions for ID ${sessionId.value}",
                        ),
                    )
                }
            }
        }

        // 2. Validate session timestamps & duration
        val validSessions = mutableListOf<FocusSession>()
        for (session in dedupedSessions) {
            val validTimestamps = session.startedAt >= 0 &&
                session.endedAt > session.startedAt &&
                session.durationMs > 0 &&
                session.durationMs <= (session.endedAt - session.startedAt)
            if (validTimestamps) {
                validSessions.add(session)
            } else {
                issues.add(
                    StatisticsIssue(
                        code = StatisticsIssueCode.INVALID_TIMESTAMP,
                        entityId = session.id.value,
                        message = "Invalid focus session timing for ${session.id.value}: startedAt=${session.startedAt}, endedAt=${session.endedAt}, durationMs=${session.durationMs}",
                    ),
                )
            }
        }

        // 3. Selection window: finalized session endedAt in period P [startMillis, endMillis)
        val inPeriodSessions = validSessions.filter { context.containsInstant(it.endedAt) }

        // 4. Section scope matching and historical path evaluation
        data class ResolvedSession(
            val session: FocusSession,
            val category: FocusSectionCategory,
            val branchContainerId: ContainerId?,
            val branchName: String,
        )

        val scopedList = mutableListOf<ResolvedSession>()

        for (session in inPeriodSessions) {
            val path = HistoricalPathParser.parsePath(session.organizationContext)
            val hasFrozenPath = path.isNotEmpty()

            when (val scope = context.sectionScope) {
                StatisticsSectionScope.All -> {
                    if (hasFrozenPath) {
                        val rootNode = path.first()
                        val container = lookup?.find(rootNode.id, includeDeleted = true)
                        val isDeleted = container != null && container.deletedAt != null
                        val category = if (isDeleted) FocusSectionCategory.DELETED else FocusSectionCategory.ROOT_CONTAINER
                        val name = rootNode.name ?: container?.name ?: rootNode.id.value
                        scopedList.add(ResolvedSession(session, category, rootNode.id, name))
                    } else {
                        if (session.containerId == null) {
                            scopedList.add(ResolvedSession(session, FocusSectionCategory.NO_SECTION, null, "Без раздела"))
                        } else {
                            issues.add(
                                StatisticsIssue(
                                    code = StatisticsIssueCode.MISSING_HISTORICAL_PATH,
                                    entityId = session.id.value,
                                    message = "Session ${session.id.value} missing frozen historical path; fell back to current container placement",
                                ),
                            )
                            if (lookup != null) {
                                val c = lookup.find(session.containerId, includeDeleted = true)
                                val isDeleted = c != null && c.deletedAt != null
                                if (isDeleted) {
                                    scopedList.add(ResolvedSession(session, FocusSectionCategory.DELETED, session.containerId, c?.name ?: session.containerId.value))
                                } else {
                                    val rootId = lookup.rootId(session.containerId) ?: session.containerId
                                    val rootC = lookup.find(rootId, includeDeleted = true)
                                    val name = rootC?.name ?: rootId.value
                                    val category = if (rootC?.deletedAt != null) FocusSectionCategory.DELETED else FocusSectionCategory.ROOT_CONTAINER
                                    scopedList.add(ResolvedSession(session, category, rootId, name))
                                }
                            } else {
                                scopedList.add(ResolvedSession(session, FocusSectionCategory.PATHLESS, session.containerId, session.containerId.value))
                            }
                        }
                    }
                }

                StatisticsSectionScope.Unassigned -> {
                    if (hasFrozenPath) {
                        // Belongs to container path, not unassigned
                    } else if (session.containerId == null) {
                        scopedList.add(ResolvedSession(session, FocusSectionCategory.NO_SECTION, null, "Без раздела"))
                    }
                }

                is StatisticsSectionScope.Container -> {
                    val match = HistoricalPathParser.matchSession(
                        session = session,
                        targetContainer = scope.id,
                        currentSubtreeIds = scope.currentDescendantIds,
                        containerQueries = lookup,
                    )
                    if (match != null) {
                        val qualifies = if (scope.includeDescendants) {
                            match.isDirect || match.isDescendant
                        } else {
                            match.isDirect
                        }
                        if (qualifies) {
                            if (!hasFrozenPath && session.containerId != null) {
                                issues.add(
                                    StatisticsIssue(
                                        code = StatisticsIssueCode.MISSING_HISTORICAL_PATH,
                                        entityId = session.id.value,
                                        message = "Session ${session.id.value} missing frozen historical path; fell back to current container placement",
                                    ),
                                )
                            }
                            if (match.isDirect) {
                                val targetName = lookup?.find(scope.id, includeDeleted = true)?.name ?: scope.id.value
                                scopedList.add(ResolvedSession(session, FocusSectionCategory.DIRECT, scope.id, targetName))
                            } else {
                                val branch = match.immediateChildBranch
                                if (branch != null) {
                                    val branchName = branch.name
                                        ?: lookup?.find(branch.id, includeDeleted = true)?.name
                                        ?: branch.id.value
                                    scopedList.add(ResolvedSession(session, FocusSectionCategory.CHILD_CONTAINER, branch.id, branchName))
                                } else {
                                    scopedList.add(ResolvedSession(session, FocusSectionCategory.PATHLESS, session.containerId, "Без пути"))
                                }
                            }
                        }
                    }
                }
            }
        }

        val scopedSessions = scopedList.map { it.session }

        // 5. Overlapping distinct session IDs detection
        // "preserve differentIDoverlaps and flag overlap (notdoublecountsameID)"
        val sortedByStart = scopedSessions.sortedBy { it.startedAt }
        val reportedPairs = mutableSetOf<String>()
        for (i in 0 until sortedByStart.size) {
            val s1 = sortedByStart[i]
            for (j in i + 1 until sortedByStart.size) {
                val s2 = sortedByStart[j]
                if (s2.startedAt >= s1.endedAt) break
                if (s1.startedAt < s2.endedAt && s2.startedAt < s1.endedAt && s1.id != s2.id) {
                    val pairKey = if (s1.id.value <= s2.id.value) "${s1.id.value}:${s2.id.value}" else "${s2.id.value}:${s1.id.value}"
                    if (reportedPairs.add(pairKey)) {
                        issues.add(
                            StatisticsIssue(
                                code = StatisticsIssueCode.OVERLAPPING_SESSIONS,
                                entityId = "${s1.id.value},${s2.id.value}",
                                message = "Overlapping focus sessions: ${s1.id.value} and ${s2.id.value}",
                            ),
                        )
                    }
                }
            }
        }

        // 6. Safe total duration calculation
        var hasTotalOverflow = false
        var totalDurationMs = 0L
        for (s in scopedSessions) {
            totalDurationMs = safeAdd(totalDurationMs, s.durationMs) { hasTotalOverflow = true }
        }
        if (hasTotalOverflow) {
            issues.add(
                StatisticsIssue(
                    code = StatisticsIssueCode.OVERFLOW,
                    message = "Focus total duration overflowed Long.MAX_VALUE",
                ),
            )
        }

        val sessionCount = scopedSessions.size
        val averageSessionDurationMs = if (sessionCount > 0 && !hasTotalOverflow) totalDurationMs / sessionCount else null

        // 7. Day buckets
        val sessionsByDate = scopedSessions.groupBy { context.civilDateAt(it.endedAt) }
        val dailyBuckets = context.dates().map { date ->
            val daySessions = sessionsByDate[date].orEmpty()
            var dayDuration = 0L
            for (ds in daySessions) {
                dayDuration = safeAdd(dayDuration, ds.durationMs) { }
            }
            FocusDailyBucket(
                date = date,
                durationMs = dayDuration,
                sessionCount = daySessions.size,
                sessions = daySessions,
            )
        }

        // 8. F3 Section breakdown (Disjoint branches summing to total)
        // Group by (category, branchContainerId)
        val sectionGroups = scopedList.groupBy { Pair(it.category, it.branchContainerId) }
        val sectionBreakdowns = sectionGroups.map { (key, groupList) ->
            val (category, containerId) = key
            val sample = groupList.first()
            var groupDuration = 0L
            for (item in groupList) {
                groupDuration = safeAdd(groupDuration, item.session.durationMs) { }
            }
            FocusSectionBreakdownItem(
                category = category,
                containerId = containerId,
                name = sample.branchName,
                durationMs = groupDuration,
                sessionCount = groupList.size,
                sessions = groupList.map { it.session },
            )
        }.sortedWith(compareByDescending<FocusSectionBreakdownItem> { it.durationMs }.thenBy { it.name })

        // 9. F4 Target breakdown (By primaryTargetType and targetId)
        // Task with linked habit remains TASK! "credit — подпись ledger, не второй slice; do not addContribution/currentTaskHabitlink"
        fun resolveTarget(s: FocusSession): Triple<FocusPrimaryTargetType?, String?, String> {
            return when {
                s.primaryTargetType == FocusPrimaryTargetType.TASK -> {
                    Triple(FocusPrimaryTargetType.TASK, s.taskId?.value, s.taskId?.value ?: "")
                }
                s.primaryTargetType == FocusPrimaryTargetType.HABIT -> {
                    Triple(FocusPrimaryTargetType.HABIT, s.habit?.uuid, s.habit?.uuid ?: "")
                }
                s.primaryTargetType == FocusPrimaryTargetType.CONTAINER -> {
                    val cid = s.containerId?.value
                    val name = if (s.containerId != null && lookup != null) {
                        lookup.find(s.containerId, includeDeleted = true)?.name ?: cid ?: ""
                    } else {
                        cid ?: ""
                    }
                    Triple(FocusPrimaryTargetType.CONTAINER, cid, name)
                }
                else -> {
                    Triple(null, null, "Не указан")
                }
            }
        }

        val targetGroups = scopedSessions.groupBy { resolveTarget(it) }
        val targetBreakdowns = targetGroups.map { (targetInfo, groupSessions) ->
            val (type, id, name) = targetInfo
            var groupDuration = 0L
            for (s in groupSessions) {
                groupDuration = safeAdd(groupDuration, s.durationMs) { }
            }
            FocusTargetBreakdownItem(
                primaryTargetType = type,
                targetId = id,
                name = name,
                durationMs = groupDuration,
                sessionCount = groupSessions.size,
                sessions = groupSessions,
            )
        }.sortedWith(compareByDescending<FocusTargetBreakdownItem> { it.durationMs }.thenBy { it.name })

        // Target type summary (TASK, HABIT, CONTAINER, UNKNOWN)
        val typeGroups = scopedSessions.groupBy {
            when {
                it.primaryTargetType == FocusPrimaryTargetType.TASK -> FocusPrimaryTargetType.TASK
                it.primaryTargetType == FocusPrimaryTargetType.HABIT -> FocusPrimaryTargetType.HABIT
                it.primaryTargetType == FocusPrimaryTargetType.CONTAINER -> FocusPrimaryTargetType.CONTAINER
                else -> null
            }
        }
        val targetTypeSummaries = typeGroups.map { (type, groupSessions) ->
            var typeDuration = 0L
            for (s in groupSessions) {
                typeDuration = safeAdd(typeDuration, s.durationMs) { }
            }
            FocusTargetTypeSummary(
                targetType = type,
                durationMs = typeDuration,
                sessionCount = groupSessions.size,
                sessions = groupSessions,
            )
        }.sortedByDescending { it.durationMs }

        val dataState = when {
            hasTotalOverflow -> StatisticsDataState.UNAVAILABLE
            issues.isNotEmpty() -> StatisticsDataState.PARTIAL
            scopedSessions.isEmpty() -> StatisticsDataState.EMPTY
            else -> StatisticsDataState.COMPLETE
        }

        return FocusStatisticsResult(
            totalDurationMs = totalDurationMs,
            sessionCount = sessionCount,
            averageSessionDurationMs = averageSessionDurationMs,
            sessions = scopedSessions,
            dailyBuckets = dailyBuckets,
            sectionBreakdowns = sectionBreakdowns,
            targetBreakdowns = targetBreakdowns,
            targetTypeSummaries = targetTypeSummaries,
            dataState = dataState,
            issues = issues,
        )
    }

    private fun emptyResult(context: StatisticsQueryContext): FocusStatisticsResult {
        val emptyDaily = context.dates().map {
            FocusDailyBucket(
                date = it,
                durationMs = 0L,
                sessionCount = 0,
            )
        }
        return FocusStatisticsResult(
            totalDurationMs = 0L,
            sessionCount = 0,
            averageSessionDurationMs = null,
            sessions = emptyList(),
            dailyBuckets = emptyDaily,
            sectionBreakdowns = emptyList(),
            targetBreakdowns = emptyList(),
            targetTypeSummaries = emptyList(),
            dataState = StatisticsDataState.EMPTY,
            issues = emptyList(),
        )
    }
}
