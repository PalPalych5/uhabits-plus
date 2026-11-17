/* SPDX-License-Identifier: GPL-3.0-or-later */

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
