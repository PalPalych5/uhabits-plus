/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers

import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository

class SQLiteOrganizationStoreContractTest : OrganizationStoreContractTest() {

    private var time = 1000L
    private var idCounter = 1

    override suspend fun createStoreAndService(): Pair<OrganizationStore, OrganizationService> {
        val clock = Clock { time += 10L; time }
        val idGen = IdGenerator { "sql-id-${idCounter++}" }
        val habitLookup = HabitIdentityLookup { true }

        val db = TestDatabaseHelper.createEmptyDatabase()
        OrganizationSchema.createSchema(db)

        val habitRepo = HabitRepository(db)
        for (uuid in listOf("h1", "h2", "h3")) {
            habitRepo.insert(
                HabitData(
                    name = uuid,
                    uuid = uuid,
                )
            )
        }

        val store = SQLiteOrganizationStore(db)
        store.seedBaseline(
            revision = OrganizationRevision(1L),
            recordedAt = 1000L,
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
