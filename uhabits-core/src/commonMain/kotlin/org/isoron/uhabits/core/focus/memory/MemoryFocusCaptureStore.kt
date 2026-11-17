/* SPDX-License-Identifier: GPL-3.0-or-later */

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
