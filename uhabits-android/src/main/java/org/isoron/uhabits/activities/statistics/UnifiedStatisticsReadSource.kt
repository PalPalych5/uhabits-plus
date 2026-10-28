package org.isoron.uhabits.activities.statistics

import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.statistics.UnifiedStatisticsService
import org.isoron.uhabits.inject.HabitsApplicationComponent

/** Construct off the main thread; schema ownership remains with dataset startup. */
internal fun HabitsApplicationComponent.unifiedStatisticsReadSource(): UnifiedStatisticsService {
    check(session.isNextArchitecture) { "Unified statistics requires a Next dataset" }
    val organization = organizationFacade
    return UnifiedStatisticsService(
        habitList, userTaskRepository, SQLiteFocusSessionRepository(db),
        requireNotNull(contributionRepository),
        requireNotNull(organization.habitPlacementQueries),
        requireNotNull(organization.containerQueries),
    )
}
