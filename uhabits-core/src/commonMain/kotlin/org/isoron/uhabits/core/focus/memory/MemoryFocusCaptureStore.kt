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

package org.isoron.uhabits.core.focus.memory

import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionConflictException
import org.isoron.uhabits.core.focus.runtime.FocusCaptureJournal
import org.isoron.uhabits.core.focus.runtime.FocusCaptureStore

class MemoryFocusCaptureStore : FocusCaptureStore {
    private var journal: FocusCaptureJournal? = null
    val repository = MemoryFocusSessionRepository()

    override fun load(): FocusCaptureJournal? = journal

    override fun commit(journal: FocusCaptureJournal, facts: List<FocusSession>) {
        // Validate the entire transaction before publishing either facts or journal.
        val prospective = mutableMapOf<String, FocusSession>()
        facts.forEach { fact ->
            val existing = prospective[fact.id.value] ?: repository.getById(fact.id)
            if (existing != null && existing != fact) throw FocusSessionConflictException(fact.id)
            prospective[fact.id.value] = fact
        }
        facts.forEach { repository.record(it) }
        this.journal = journal
    }
}
