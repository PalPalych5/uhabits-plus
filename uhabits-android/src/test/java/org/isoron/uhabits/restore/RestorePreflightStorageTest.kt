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

package org.isoron.uhabits.restore

import org.isoron.uhabits.backup.BackupRestoreException
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit test suite for GAP-12: Restore free-space preflight checks.
 *
 * Verifies:
 * 1. Zero available bytes (0L) is strictly rejected with InsufficientStorageException (GAP-12 reproduction & fix).
 * 2. Negative or unavailable space (-1L) is strictly rejected with InsufficientStorageException.
 * 3. Exact boundary condition (availableBytes < requiredBytes fails, availableBytes >= requiredBytes passes).
 * 4. Negative database size and arithmetic overflow in requirement computation fail closed.
 * 5. Pure internal decision helper operates without global mutable state or validator overrides.
 */
class RestorePreflightStorageTest {

    @Test
    fun computeRequiredRestoreBytes_calculatesCorrectOverhead() {
        // Base overhead is 10 MB (10 * 1024 * 1024 = 10_485_760 bytes)
        // Multiplier is 3x database size
        val tenMb = 10 * 1024 * 1024L
        assertEquals(tenMb, DatabaseUtils.computeRequiredRestoreBytes(0L))
        assertEquals(tenMb + 3000L, DatabaseUtils.computeRequiredRestoreBytes(1000L))
        assertEquals(tenMb + 30_000_000L, DatabaseUtils.computeRequiredRestoreBytes(10_000_000L))
    }

    @Test
    fun computeRequiredRestoreBytes_failsClosedOnNegativeDatabaseSize() {
        // Malicious or corrupted negative size must not clamp to zero; must fail closed
        val ex1 = assertThrows(BackupRestoreException.CorruptedArchiveException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(-1L)
        }
        assertTrue(ex1.message!!.contains("Invalid negative database size"))

        val ex2 = assertThrows(BackupRestoreException.CorruptedArchiveException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(-5000L)
        }
        assertTrue(ex2.message!!.contains("-5000"))
    }

    @Test
    fun computeRequiredRestoreBytes_failsClosedOnArithmeticOverflow() {
        // Extreme database size causing 64-bit multiplication overflow must fail closed
        val ex1 = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(Long.MAX_VALUE)
        }
        assertTrue(ex1.message!!.contains("overflow"))

        val ex2 = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.computeRequiredRestoreBytes(Long.MAX_VALUE / 2)
        }
        assertTrue(ex2.message!!.contains("overflow"))
        assertTrue(ex2.message!!.contains("overflow"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_reproducesAndRejectsZeroAvailableSpace() {
        val requiredBytes = DatabaseUtils.computeRequiredRestoreBytes(1000L)

        // GAP-12 defect: previously 'parent.usableSpace in 1 until requiredBytes'
        // allowed 0L to bypass preflight. Here we verify that 0L is rejected.
        val ex = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.checkRestoreFreeSpace(availableBytes = 0L, requiredBytes = requiredBytes)
        }
        assertTrue(ex.message!!.contains("required $requiredBytes bytes"))
        assertTrue(ex.message!!.contains("available 0 bytes"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_rejectsNegativeOrUnavailableSpace() {
        val requiredBytes = DatabaseUtils.computeRequiredRestoreBytes(1000L)

        val ex = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.checkRestoreFreeSpace(availableBytes = -1L, requiredBytes = requiredBytes)
        }
        assertTrue(ex.message!!.contains("available -1 bytes"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_rejectsStrictlyInsufficientSpace() {
        val requiredBytes = 10_000_000L

        val ex = assertThrows(BackupRestoreException.InsufficientStorageException::class.java) {
            DatabaseUtils.checkRestoreFreeSpace(availableBytes = requiredBytes - 1L, requiredBytes = requiredBytes)
        }
        assertTrue(ex.message!!.contains("available ${requiredBytes - 1L} bytes"))
    }

    @Test
    fun checkRestoreFreeSpace_pureDecision_acceptsExactBoundaryAndGreaterSpace() {
        val requiredBytes = 10_000_000L

        // Exact boundary: available == requiredBytes -> passes without throwing
        DatabaseUtils.checkRestoreFreeSpace(availableBytes = requiredBytes, requiredBytes = requiredBytes)

        // Above boundary: available == requiredBytes + 1L -> passes without throwing
        DatabaseUtils.checkRestoreFreeSpace(availableBytes = requiredBytes + 1L, requiredBytes = requiredBytes)
    }
}
