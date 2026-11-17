/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.containers.ContainerId

/** ContainerQueries.subtreeIds returns descendants, without the selected container itself. */
object TaskContainerScope {
    fun ids(selected: ContainerId, descendants: Set<ContainerId>, activeIds: Set<ContainerId>): Set<ContainerId> =
        (setOf(selected) + descendants).intersect(activeIds)
}
