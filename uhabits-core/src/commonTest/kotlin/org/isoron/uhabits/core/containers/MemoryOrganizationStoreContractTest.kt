/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers

import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore

class MemoryOrganizationStoreContractTest : OrganizationStoreContractTest() {

    private var time = 1000L
    private var idCounter = 1

    override suspend fun createStoreAndService(): Pair<OrganizationStore, OrganizationService> {
        val clock = Clock { time += 10L; time }
        val idGen = IdGenerator { "mem-id-${idCounter++}" }
        val habitLookup = HabitIdentityLookup { true }

        val store = MemoryOrganizationStore(
            initialCutoverRevision = OrganizationRevision(1L),
            initialCutoverAt = 1000L,
        )
        val service = OrganizationServiceImpl(
            store = store,
            unitOfWork = store,
            containerQueries = store,
            placementQueries = store,
            clock = clock,
            idGenerator = idGen,
            habitIdentityLookup = habitLookup,
        )
        return store to service
    }
}
