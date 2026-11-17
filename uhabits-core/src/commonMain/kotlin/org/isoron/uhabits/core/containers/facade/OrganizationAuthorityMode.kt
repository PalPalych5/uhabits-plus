/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers.facade

/**
 * Defines the organizational authority boundary:
 *
 * - [LEGACY]: HabitBlock and HabitExtensions.block_id represent the organizational authority.
 *   All existing legacy read/write pathways operate exactly as before.
 *
 * - [CONTAINER_LOCAL]: Container and HabitPlacement represent the organizational authority.
 *   HabitBlocks and HabitExtensions.block_id are frozen snapshots. No dual-write is permitted.
 */
enum class OrganizationAuthorityMode {
    LEGACY,
    CONTAINER_LOCAL
}
