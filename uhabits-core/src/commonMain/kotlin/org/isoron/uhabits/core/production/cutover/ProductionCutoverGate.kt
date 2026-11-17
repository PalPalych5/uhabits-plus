/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.production.cutover

import org.isoron.uhabits.core.DATABASE_FILENAME
import kotlin.concurrent.Volatile

/**
 * Gate that protects the canonical production database from unapproved automatic
 * schema conversion or runtime authority cutover.
 *
 * In PR22:
 * - Version 30 schema conversion is implemented and verified on protected staging copies.
 * - Ordinary canonical production database startup must NOT execute cutover automatically.
 * - Staging copies and disposable non-canonical files are permitted.
 */
object ProductionCutoverGate {
    const val AUTHORITY_LEGACY_PRODUCTION = "LEGACY_PRODUCTION"
    const val AUTHORITY_LEGACY = "LEGACY"
    const val AUTHORITY_NEXT = "NEXT"

    @Volatile
    private var explicitCutoverAuthorized: Boolean = false

    @Volatile
    private var persistedCutoverCheck: ((String) -> Boolean)? = null

    /**
     * Registers a callback to check whether cutover was persisted for a database file.
     */
    fun registerPersistedCutoverCheck(check: ((String) -> Boolean)?) {
        persistedCutoverCheck = check
    }

    /**
     * Whether cutover to v30 is authorized globally.
     */
    fun isCutoverAuthorized(): Boolean = explicitCutoverAuthorized || (persistedCutoverCheck?.invoke(DATABASE_FILENAME) == true)

    /**
     * Whether cutover to v30 is authorized for the given database filename.
     * Staging / test databases (not named [DATABASE_FILENAME]) are always permitted.
     * The canonical production database requires explicit cutover authorization (false by default).
     */
    fun isCutoverAuthorized(databaseFilename: String): Boolean {
        val canonicalName = DATABASE_FILENAME
        val isCanonical = databaseFilename == canonicalName || databaseFilename.endsWith("/$canonicalName") || databaseFilename.endsWith("\\$canonicalName")
        if (!isCanonical) return true
        return explicitCutoverAuthorized || (persistedCutoverCheck?.invoke(databaseFilename) == true)
    }

    /**
     * Asserts that cutover is authorized for the given database filename, throwing [CutoverNotAuthorizedException] otherwise.
     */
    fun checkCutoverAuthorized(databaseFilename: String) {
        if (!isCutoverAuthorized(databaseFilename)) {
            throw CutoverNotAuthorizedException(databaseFilename)
        }
    }

    /**
     * Authorize cutover for the current process session (used in targeted tests).
     */
    fun setExplicitCutoverAuthorizedForTesting(authorized: Boolean) {
        explicitCutoverAuthorized = authorized
    }

    /**
     * Executes the given block with cutover authorization temporarily enabled.
     */
    fun <T> withCutoverAuthorization(block: () -> T): T {
        val prev = explicitCutoverAuthorized
        explicitCutoverAuthorized = true
        try {
            return block()
        } finally {
            explicitCutoverAuthorized = prev
        }
    }
}

class CutoverNotAuthorizedException(
    databaseFilename: String
) : IllegalStateException("Production schema cutover to version 30 is not authorized for canonical database: $databaseFilename")
