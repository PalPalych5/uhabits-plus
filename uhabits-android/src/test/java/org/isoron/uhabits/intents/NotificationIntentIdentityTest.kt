package org.isoron.uhabits.intents

import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.junit.Assert.*
import org.junit.Test

class NotificationIntentIdentityTest {
    private val production = DatasetSessionFactory.production()

    @Test
    fun currentIdentityIsAccepted() {
        assertTrue(NotificationIntentIdentity.accepts(production.datasetId, "uuid", production, "uuid"))
    }

    @Test
    fun staleDatasetOrReusedLocalIdIsRejected() {
        assertFalse(NotificationIntentIdentity.accepts("old-dataset", "uuid", production, "uuid"))
        assertFalse(NotificationIntentIdentity.accepts(production.datasetId, "old-uuid", production, "new-uuid"))
        assertFalse(NotificationIntentIdentity.accepts(production.datasetId, "uuid", production, null))
        assertFalse(NotificationIntentIdentity.accepts(production.datasetId, null, production, "uuid"))
    }

    @Test
    fun untaggedLegacyOnlyAndCapabilityGate() {
        assertTrue(NotificationIntentIdentity.accepts(null, null, production, "uuid"))
        val next = production.copy(mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL)
        assertFalse(NotificationIntentIdentity.accepts(null, null, next, "uuid"))
        val disabled = production.copy(capabilities = production.capabilities.copy(remindersEnabled = false))
        assertFalse(NotificationIntentIdentity.accepts(production.datasetId, "uuid", disabled, "uuid"))
    }

    @Test
    fun constantsMatchExpectedValues() {
        assertEquals("notification.dataset", NotificationIntentIdentity.DATASET)
        assertEquals("notification.habit.uuid", NotificationIntentIdentity.HABIT_UUID)
    }
}
