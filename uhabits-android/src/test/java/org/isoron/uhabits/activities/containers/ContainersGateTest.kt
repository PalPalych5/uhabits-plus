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

package org.isoron.uhabits.activities.containers

import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.session.ExperimentalDatasetMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainersGateTest {

    private fun isContainersEntryVisible(session: DatasetSession, isSearchActive: Boolean = false): Boolean {
        val isContainersExperimental = session.mode == DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL
        return isContainersExperimental && !isSearchActive
    }

    @Test
    fun `LEGACY UI entry is absent`() {
        val productionSession = DatasetSessionFactory.production()
        assertFalse(isContainersEntryVisible(productionSession, isSearchActive = false))
        assertFalse(isContainersEntryVisible(productionSession, isSearchActive = true))
    }

    @Test
    fun `CONTAINER_LOCAL_EXPERIMENTAL UI entry is present`() {
        val metadata = ExperimentalDatasetMetadata(
            datasetId = "exp-test",
            databaseFilename = "uhabits-exp.db",
            foundationVersion = 1,
            sourceSnapshotSha256 = "dummy",
            createdAtMillis = 1000L
        )
        val experimentalSession = DatasetSessionFactory.experimental(metadata)
        assertTrue(isContainersEntryVisible(experimentalSession, isSearchActive = false))
        assertFalse(isContainersEntryVisible(experimentalSession, isSearchActive = true))
    }
}
