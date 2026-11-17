/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.production

import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.production.cutover.CutoverNotAuthorizedException
import org.isoron.uhabits.core.production.cutover.ProductionCutoverGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCutoverGateAndroidTest {

    @Test
    fun cutoverIsUnauthorizedByDefault() {
        assertFalse(ProductionCutoverGate.isCutoverAuthorized())
        assertFalse(ProductionCutoverGate.isCutoverAuthorized("uhabits.db"))
    }

    @Test
    fun withCutoverAuthorizationSetsAndResetsFlag() {
        assertFalse(ProductionCutoverGate.isCutoverAuthorized())

        val result = ProductionCutoverGate.withCutoverAuthorization {
            assertTrue(ProductionCutoverGate.isCutoverAuthorized())
            assertTrue(ProductionCutoverGate.isCutoverAuthorized("uhabits.db"))
            42
        }

        assertEquals(42, result)
        assertFalse(ProductionCutoverGate.isCutoverAuthorized())
    }

    @Test
    fun withCutoverAuthorizationResetsFlagEvenOnException() {
        assertFalse(ProductionCutoverGate.isCutoverAuthorized())

        assertThrows(IllegalStateException::class.java) {
            ProductionCutoverGate.withCutoverAuthorization {
                assertTrue(ProductionCutoverGate.isCutoverAuthorized())
                error("Simulated failure inside cutover block")
            }
        }

        assertFalse(ProductionCutoverGate.isCutoverAuthorized())
    }

    @Test
    fun checkCutoverAuthorizedThrowsForCanonicalWhenUnauthorized() {
        assertFalse(ProductionCutoverGate.isCutoverAuthorized("uhabits.db"))
        assertThrows(CutoverNotAuthorizedException::class.java) {
            ProductionCutoverGate.checkCutoverAuthorized("uhabits.db")
        }
        assertThrows(CutoverNotAuthorizedException::class.java) {
            ProductionCutoverGate.checkCutoverAuthorized("/data/data/org.isoron.uhabits/databases/uhabits.db")
        }
    }

    @Test
    fun checkCutoverAuthorizedPassesForNonCanonicalStagingFiles() {
        // Staging and disposable files are permitted without global authorization
        assertTrue(ProductionCutoverGate.isCutoverAuthorized("staging_migration_123.db"))
        assertTrue(ProductionCutoverGate.isCutoverAuthorized("test.db"))
        ProductionCutoverGate.checkCutoverAuthorized("staging_migration_123.db")
        ProductionCutoverGate.checkCutoverAuthorized("test.db")
    }

    @Test
    fun checkCutoverAuthorizedPassesForCanonicalWhenExplicitlyAuthorized() {
        ProductionCutoverGate.withCutoverAuthorization {
            assertTrue(ProductionCutoverGate.isCutoverAuthorized("uhabits.db"))
            ProductionCutoverGate.checkCutoverAuthorized("uhabits.db")
            ProductionCutoverGate.checkCutoverAuthorized("/data/data/org.isoron.uhabits/databases/uhabits.db")
        }
    }

    @Test
    fun canonicalDatabaseVersionIs29ByDefault() {
        assertEquals(29, LEGACY_DATABASE_VERSION)
        assertEquals(36, DATABASE_VERSION)
    }

    @Test
    fun persistedCutoverCheckAuthorizesWhenRegistered() {
        assertFalse(ProductionCutoverGate.isCutoverAuthorized("uhabits.db"))
        try {
            ProductionCutoverGate.registerPersistedCutoverCheck { filename -> filename == "uhabits.db" }
            assertTrue(ProductionCutoverGate.isCutoverAuthorized("uhabits.db"))
        } finally {
            ProductionCutoverGate.registerPersistedCutoverCheck(null)
            assertFalse(ProductionCutoverGate.isCutoverAuthorized("uhabits.db"))
        }
    }
}

