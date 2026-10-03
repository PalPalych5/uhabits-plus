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
