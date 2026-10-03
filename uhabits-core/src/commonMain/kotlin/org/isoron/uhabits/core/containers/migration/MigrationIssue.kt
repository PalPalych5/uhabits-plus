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

package org.isoron.uhabits.core.containers.migration

sealed interface MigrationIssue {
    val message: String
    val isBlocking: Boolean get() = true

    data class BlankOrMissingBlockUuid(
        val blockId: Long,
        val blockName: String,
    ) : MigrationIssue {
        override val message: String = "HabitBlock id=$blockId ('$blockName') has blank or missing UUID"
    }

    data class DuplicateBlockUuid(
        val uuid: String,
        val blockIds: List<Long>,
    ) : MigrationIssue {
        override val message: String = "Duplicate HabitBlock UUID '$uuid' shared by blocks $blockIds"
    }

    data class BlankOrMissingHabitUuid(
        val habitId: Long,
        val habitName: String,
    ) : MigrationIssue {
        override val message: String = "Habit id=$habitId ('$habitName') has blank or missing UUID"
    }

    data class DuplicateHabitUuid(
        val uuid: String,
        val habitIds: List<Long>,
    ) : MigrationIssue {
        override val message: String = "Duplicate Habit UUID '$uuid' shared by habits $habitIds"
    }

    data class MissingReferencedBlock(
        val habitId: Long,
        val habitUuid: String?,
        val blockId: Long,
    ) : MigrationIssue {
        override val message: String =
            "Habit id=$habitId (uuid=$habitUuid) references non-existent HabitBlock id=$blockId"
    }

    data class ActiveHabitReferencingTombstonedBlock(
        val habitId: Long,
        val habitUuid: String?,
        val blockId: Long,
        val blockUuid: String?,
    ) : MigrationIssue {
        override val message: String =
            "Active Habit id=$habitId (uuid=$habitUuid) references deleted/tombstoned HabitBlock id=$blockId (uuid=$blockUuid)"
    }

    data class InconsistentPlacement(
        val habitId: Long,
        val reason: String,
    ) : MigrationIssue {
        override val message: String = "Inconsistent placement for Habit id=$habitId: $reason"
    }

    data class InvalidMapping(
        val reason: String,
    ) : MigrationIssue {
        override val message: String = "Invalid legacy mapping: $reason"
    }

    data class ImpossibleBaseline(
        val reason: String,
    ) : MigrationIssue {
        override val message: String = "Impossible baseline: $reason"
    }

    data class SourceChecksumMismatch(
        val expectedSha256: String,
        val actualSha256: String,
    ) : MigrationIssue {
        override val message: String =
            "Source DB checksum mismatch: expected $expectedSha256 but actual was $actualSha256"
    }
}
