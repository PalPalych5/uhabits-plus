/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.list.views

import androidx.compose.runtime.Immutable
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.activities.habits.list.MAX_CHECKMARK_COUNT
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.models.projection.HabitProjectionIntegrityException
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.utils.HabitQuantityFormatter
import kotlin.math.max
import kotlin.math.min

enum class HabitListDisplayModeState {
    LOADING,
    ERROR,
    EMPTY_NO_HABITS,
    EMPTY_ALL_DONE,
    EMPTY_SEARCH,
    CONTENT
}

enum class HabitCheckmarkGlyph {
    CHECK,
    TIMES,
    SKIPPED,
    QUESTION
}

enum class HabitCellTone {
    ACTIVE,
    MEDIUM,
    LOW
}

@Immutable
data class HabitCheckmarkCellUi(
    val glyph: HabitCheckmarkGlyph,
    val isHollowYesAuto: Boolean,
    val tone: HabitCellTone,
    val hasNotes: Boolean,
    val rawValue: Int,
    val notes: String
)

@Immutable
data class HabitNumberCellUi(
    val primaryText: String,
    val unitsText: String,
    val glyph: HabitCheckmarkGlyph?,
    val tone: HabitCellTone,
    val hasNotes: Boolean,
    val isLowerBound: Boolean,
    val isSkipped: Boolean,
    val isUnknown: Boolean,
    val notes: String
)

@Immutable
data class HabitHeaderDayUi(
    val date: LocalDate,
    val dayOfWeekLabel: String,
    val dayNumberLabel: String,
    val isToday: Boolean
)

@Immutable
data class HabitGroupUiInfo(
    val heading: String?,
    val showTopGap: Boolean
)

@Immutable
data class HabitAppearanceState(
    val isClassic: Boolean,
    val showCardBorders: Boolean,
    val cardCornerRadiusDp: Int,
    val showScoreRings: Boolean,
    val showHabitIcons: Boolean,
    val areQuestionMarksEnabled: Boolean,
    val isShortToggleEnabled: Boolean,
    val isSkipEnabled: Boolean,
    val isCheckmarkSequenceReversed: Boolean,
    val primaryOrder: HabitList.Order,
    val isSortable: Boolean,
    val isDayTiersEnabled: Boolean,
    val isHabitSpheresEnabled: Boolean,
    val isShowSphereNamesEnabled: Boolean,
    val areHabitGroupSeparatorsEnabled: Boolean
)

@Immutable
class HabitRowIntValues(private val raw: IntArray) {
    val size: Int get() = raw.size
    fun getOrUnknown(index: Int): Int = if (index in raw.indices) raw[index] else Entry.UNKNOWN

    override fun equals(other: Any?): Boolean =
        this === other || (other is HabitRowIntValues && raw.contentEquals(other.raw))

    override fun hashCode(): Int = raw.contentHashCode()
}

@Immutable
data class HabitListRowUi(
    val position: Int,
    val habitId: Long,
    val name: String,
    val activeColorArgb: Int,
    val isNumerical: Boolean,
    val score: Double,
    val values: HabitRowIntValues,
    val notes: List<String>,
    val effectiveSnapshot: EffectiveHabitReadSnapshot?,
    val iconRef: IconRef?,
    val isSelected: Boolean,
    val groupHeading: String?,
    val showTopGap: Boolean,
    val goalSignature: String,
    val habit: Habit
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HabitListRowUi) return false
        return position == other.position &&
            habitId == other.habitId &&
            name == other.name &&
            activeColorArgb == other.activeColorArgb &&
            isNumerical == other.isNumerical &&
            score == other.score &&
            values == other.values &&
            notes == other.notes &&
            effectiveSnapshot == other.effectiveSnapshot &&
            iconRef == other.iconRef &&
            isSelected == other.isSelected &&
            groupHeading == other.groupHeading &&
            showTopGap == other.showTopGap &&
            goalSignature == other.goalSignature
    }

    override fun hashCode(): Int {
        var result = position
        result = 31 * result + habitId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + activeColorArgb
        result = 31 * result + isNumerical.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + values.hashCode()
        result = 31 * result + notes.hashCode()
        result = 31 * result + (effectiveSnapshot?.hashCode() ?: 0)
        result = 31 * result + (iconRef?.hashCode() ?: 0)
        result = 31 * result + isSelected.hashCode()
        result = 31 * result + (groupHeading?.hashCode() ?: 0)
        result = 31 * result + showTopGap.hashCode()
        result = 31 * result + goalSignature.hashCode()
        return result
    }
}

@Immutable
data class HabitListScreenUiState(
    val today: LocalDate,
    val displayState: HabitListDisplayModeState,
    val rows: List<HabitListRowUi>,
    val appearance: HabitAppearanceState,
    val isSelectionActive: Boolean,
    val activeHint: String?,
    val listRevision: Long
)

/**
 * Matches legacy [org.isoron.uhabits.activities.habits.list.ListHabitsRootView.getCheckmarkCount]
 * sizing formula so the number of visible day columns stays consistent across layouts.
 */
fun computeCheckmarkCount(
    availableWidthPx: Float,
    nameWidthPx: Float,
    buttonWidthPx: Float,
    maxCount: Int = MAX_CHECKMARK_COUNT
): Int {
    if (availableWidthPx <= 0f || buttonWidthPx <= 0f) return 0
    val labelWidth = max(availableWidthPx / 3f, nameWidthPx)
    val count = ((availableWidthPx - labelWidth) / buttonWidthPx).toInt()
    return min(maxCount, max(0, count))
}

/**
 * Computes the visual start-to-end day offsets shown in header & row action strips
 * (before Compose RTL mirroring).
 *
 * Matches legacy [HeaderView] / [ButtonPanelView] ordering:
 * - When [isReversed] is false (default), today is at index 0 (`[0, 1, ..., count-1]`).
 * - When [isReversed] is true, today sits at the trailing edge (`[count-1, ..., 1, 0]`).
 */
fun computeVisibleDayOffsets(buttonCount: Int, isReversed: Boolean): List<Int> {
    if (buttonCount <= 0) return emptyList()
    return if (isReversed) {
        (buttonCount - 1 downTo 0).toList()
    } else {
        (0 until buttonCount).toList()
    }
}

fun computeHeaderDates(
    today: LocalDate,
    dataOffset: Int,
    buttonCount: Int,
    isReversed: Boolean,
    shortWeekdayProvider: (LocalDate) -> String
): List<HabitHeaderDayUi> {
    return computeVisibleDayOffsets(buttonCount, isReversed).map { index ->
        val date = today.minus(index + dataOffset)
        HabitHeaderDayUi(
            date = date,
            dayOfWeekLabel = shortWeekdayProvider(date).uppercase(),
            dayNumberLabel = date.day.toString(),
            isToday = date == today
        )
    }
}

fun resolveGroupUiInfo(
    metadataList: List<HabitCardListCache.RowMetadata>,
    primaryOrder: HabitList.Order,
    spheresEnabled: Boolean,
    showSphereNames: Boolean,
    dayTiersEnabled: Boolean,
    groupSeparatorsEnabled: Boolean,
    unassignedHeading: String
): List<HabitGroupUiInfo> {
    if (metadataList.isEmpty()) return emptyList()

    val isGroupedOrder = (primaryOrder == HabitList.Order.BY_SPHERE && spheresEnabled) ||
        (primaryOrder == HabitList.Order.BY_DAY_TIER && dayTiersEnabled)
    val showHeading = primaryOrder == HabitList.Order.BY_SPHERE && spheresEnabled && showSphereNames

    return metadataList.mapIndexed { position, current ->
        if (!isGroupedOrder) {
            return@mapIndexed HabitGroupUiInfo(heading = null, showTopGap = false)
        }
        val hasSeparatorBefore = if (position == 0) {
            showHeading
        } else {
            val previous = metadataList[position - 1]
            val prevKey: Any? = if (primaryOrder == HabitList.Order.BY_SPHERE) previous.rootGroupKey else previous.dayTier
            val currKey: Any? = if (primaryOrder == HabitList.Order.BY_SPHERE) current.rootGroupKey else current.dayTier
            if (prevKey == currKey) {
                false
            } else {
                showHeading || primaryOrder == HabitList.Order.BY_SPHERE || groupSeparatorsEnabled
            }
        }
        if (!hasSeparatorBefore) {
            HabitGroupUiInfo(heading = null, showTopGap = false)
        } else if (showHeading) {
            val heading = current.rootGroupName ?: unassignedHeading
            HabitGroupUiInfo(heading = heading, showTopGap = false)
        } else {
            HabitGroupUiInfo(heading = null, showTopGap = true)
        }
    }
}

fun resolveCheckmarkCell(
    value: Int,
    notes: String,
    areQuestionMarksEnabled: Boolean
): HabitCheckmarkCellUi {
    val tone = when (value) {
        Entry.YES_MANUAL, Entry.YES_AUTO, Entry.SKIP -> HabitCellTone.ACTIVE
        Entry.NO -> if (areQuestionMarksEnabled) HabitCellTone.MEDIUM else HabitCellTone.LOW
        else -> HabitCellTone.LOW
    }
    val glyph = when (value) {
        Entry.SKIP -> HabitCheckmarkGlyph.SKIPPED
        Entry.NO -> HabitCheckmarkGlyph.TIMES
        Entry.UNKNOWN -> if (areQuestionMarksEnabled) HabitCheckmarkGlyph.QUESTION else HabitCheckmarkGlyph.TIMES
        else -> HabitCheckmarkGlyph.CHECK
    }
    return HabitCheckmarkCellUi(
        glyph = glyph,
        isHollowYesAuto = value == Entry.YES_AUTO,
        tone = tone,
        hasNotes = notes.isNotBlank(),
        rawValue = value,
        notes = notes
    )
}

fun resolveNumberCell(
    rawMilliValue: Int,
    notes: String,
    units: String,
    targetType: NumericalHabitType,
    threshold: Double,
    areQuestionMarksEnabled: Boolean,
    date: LocalDate,
    habit: Habit?,
    effectiveSnapshot: EffectiveHabitReadSnapshot?
): HabitNumberCellUi {
    val value = rawMilliValue / 1000.0
    val effectiveDay: EffectiveHabitDay? = effectiveSnapshot?.let { snapshot ->
        if (date < snapshot.from || date > snapshot.to) null else snapshot.day(date)
    }
    val effectiveCompletion: HabitGoalStatus? = effectiveSnapshot?.let { snapshot ->
        val goal = habit?.goalAt(date)
        when {
            date < snapshot.from || date > snapshot.to -> null
            goal?.targetType == NumericalHabitType.AT_MOST -> try {
                snapshot.goalAchievementOn(date).status
            } catch (_: HabitProjectionIntegrityException) {
                HabitGoalStatus.UNKNOWN
            }
            snapshot.hasContributions -> snapshot.compatibilityCompletionOn(date).status
            else -> null
        }
    }

    val isUnknown = (effectiveDay != null && effectiveDay.totalValue == null) ||
        (effectiveDay == null && value < 0.0)
    val isSkipped = (effectiveDay != null && effectiveDay.isSkipped) ||
        (effectiveDay == null && rawMilliValue == Entry.SKIP)

    val tone = when {
        isUnknown -> HabitCellTone.LOW
        effectiveCompletion != null -> {
            if (effectiveCompletion == HabitGoalStatus.SATISFIED) HabitCellTone.ACTIVE else HabitCellTone.MEDIUM
        }
        (targetType == NumericalHabitType.AT_LEAST) && (value >= threshold) -> HabitCellTone.ACTIVE
        (targetType == NumericalHabitType.AT_MOST) && (value <= threshold) -> HabitCellTone.ACTIVE
        value <= 0.0 -> HabitCellTone.LOW
        else -> HabitCellTone.MEDIUM
    }

    return when {
        isSkipped -> HabitNumberCellUi(
            primaryText = "",
            unitsText = "",
            glyph = HabitCheckmarkGlyph.SKIPPED,
            tone = tone,
            hasNotes = notes.isNotBlank(),
            isLowerBound = false,
            isSkipped = true,
            isUnknown = false,
            notes = notes
        )

        effectiveDay != null && effectiveDay.totalValue != null -> {
            val isLowerBound = !effectiveDay.hasCompleteCoverage
            val formatted = HabitQuantityFormatter.format(
                effectiveDay.totalValue!! / 1000.0,
                units,
                isLowerBound,
                compact = true
            )
            HabitNumberCellUi(
                primaryText = formatted.number,
                unitsText = formatted.unit,
                glyph = null,
                tone = tone,
                hasNotes = notes.isNotBlank(),
                isLowerBound = isLowerBound,
                isSkipped = false,
                isUnknown = false,
                notes = notes
            )
        }

        effectiveDay == null && value >= 0.0 -> {
            val formatted = HabitQuantityFormatter.format(value, units, compact = true)
            HabitNumberCellUi(
                primaryText = formatted.number,
                unitsText = formatted.unit,
                glyph = null,
                tone = tone,
                hasNotes = notes.isNotBlank(),
                isLowerBound = false,
                isSkipped = false,
                isUnknown = false,
                notes = notes
            )
        }

        isUnknown -> {
            if (areQuestionMarksEnabled) {
                HabitNumberCellUi(
                    primaryText = "",
                    unitsText = "",
                    glyph = HabitCheckmarkGlyph.QUESTION,
                    tone = tone,
                    hasNotes = notes.isNotBlank(),
                    isLowerBound = false,
                    isSkipped = false,
                    isUnknown = true,
                    notes = notes
                )
            } else {
                HabitNumberCellUi(
                    primaryText = "0",
                    unitsText = units,
                    glyph = null,
                    tone = tone,
                    hasNotes = notes.isNotBlank(),
                    isLowerBound = false,
                    isSkipped = false,
                    isUnknown = true,
                    notes = notes
                )
            }
        }

        else -> HabitNumberCellUi(
            primaryText = "0",
            unitsText = units,
            glyph = null,
            tone = tone,
            hasNotes = notes.isNotBlank(),
            isLowerBound = false,
            isSkipped = false,
            isUnknown = false,
            notes = notes
        )
    }
}
