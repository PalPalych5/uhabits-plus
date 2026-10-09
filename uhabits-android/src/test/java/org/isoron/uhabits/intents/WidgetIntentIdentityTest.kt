package org.isoron.uhabits.intents

import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.junit.Assert.*
import org.junit.Test

class WidgetIntentIdentityTest {
    private val production = DatasetSessionFactory.production()

    @Test fun currentIdentityIsAccepted() {
        assertTrue(WidgetIntentIdentity.accepts(production.datasetId, "uuid", production, "uuid"))
    }

    @Test fun staleDatasetOrReusedLocalIdIsRejected() {
        assertFalse(WidgetIntentIdentity.accepts("old-dataset", "uuid", production, "uuid"))
        assertFalse(WidgetIntentIdentity.accepts(production.datasetId, "old-uuid", production, "new-uuid"))
        assertFalse(WidgetIntentIdentity.accepts(production.datasetId, "uuid", production, null))
        assertFalse(WidgetIntentIdentity.accepts(production.datasetId, null, production, "uuid"))
    }

    @Test fun untaggedLegacyOnlyAndCapabilityGate() {
        assertTrue(WidgetIntentIdentity.accepts(null, null, production, "uuid"))
        val next = production.copy(mode = DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL)
        assertFalse(WidgetIntentIdentity.accepts(null, null, next, "uuid"))
        val disabled = production.copy(capabilities = production.capabilities.copy(widgetsEnabled = false))
        assertFalse(WidgetIntentIdentity.accepts(production.datasetId, "uuid", disabled, "uuid"))
    }
}
