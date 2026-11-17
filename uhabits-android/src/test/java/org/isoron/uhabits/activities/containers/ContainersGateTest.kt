/* SPDX-License-Identifier: GPL-3.0-or-later */

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
