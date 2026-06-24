package org.isoron.uhabits.activities.habits.edit

import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor

internal object HabitColorDefaults {
    val appDefault = PaletteColor(18)

    fun forBlock(blockId: Long?, blocks: List<HabitBlock>): PaletteColor =
        blocks.firstOrNull { it.id == blockId }?.color ?: appDefault

    fun afterBlockChange(
        currentColor: PaletteColor,
        newBlockId: Long?,
        hasIndividualColor: Boolean,
        blocks: List<HabitBlock>
    ): PaletteColor = if (hasIndividualColor) currentColor else forBlock(newBlockId, blocks)

    fun isIndividual(color: PaletteColor, blockId: Long?, blocks: List<HabitBlock>): Boolean =
        color != forBlock(blockId, blocks)
}
