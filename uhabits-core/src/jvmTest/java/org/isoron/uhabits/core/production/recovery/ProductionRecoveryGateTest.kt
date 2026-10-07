package org.isoron.uhabits.core.production.recovery

import java.io.File
import java.nio.file.Files
import java.net.URLClassLoader
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductionRecoveryGateTest {
    private class Fixture(
        val root: File = Files.createTempDirectory("production-recovery-").toFile(),
        createDatabase: Boolean = true,
    ) : AutoCloseable {
        val source = File(root, "uhabits.db")
        val bundles = File(root, "recovery")
        var prefs = idlePreferences()
        var leasesClosed = 0

        init {
            if (createDatabase) DriverManager.getConnection("jdbc:sqlite:${source.path}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version=29")
                    statement.execute("CREATE TABLE Habits(id INTEGER PRIMARY KEY, uuid TEXT, name TEXT)")
                    statement.execute("INSERT INTO Habits VALUES(1,'habit-original','Original')")
                    statement.execute("CREATE TABLE AppSettings(name TEXT PRIMARY KEY, value TEXT)")
                    statement.execute("INSERT INTO AppSettings VALUES('legacyMarker','original')")
                }
            }
        }

        fun inspect(file: File): RecoveryInspection {
            require(file.isFile)
            return DriverManager.getConnection("jdbc:sqlite:${file.toURI()}?mode=ro").use { connection ->
                connection.createStatement().use { statement ->
                    val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                    val integrity = statement.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) }
                    val foreignKeysOkay = statement.executeQuery("PRAGMA foreign_key_check").use { !it.next() }
                    val schema = statement.executeQuery("SELECT name,sql FROM sqlite_master ORDER BY name").use {
                        buildString { while (it.next()) append(it.getString(1)).append(':').append(it.getString(2)).append('\n') }
                    }
                    val semantics = statement.executeQuery("SELECT id,uuid,name FROM Habits ORDER BY id").use {
                        buildString { while (it.next()) append(it.getLong(1)).append(':').append(it.getString(2)).append(':').append(it.getString(3)) }
                    }
                    val count = statement.executeQuery("SELECT COUNT(*) FROM Habits").use { it.next(); it.getLong(1) }
                    val marker = statement.executeQuery("SELECT value FROM AppSettings WHERE name='legacyMarker'").use { it.next(); it.getString(1) }
                    RecoveryInspection(version, sha(semantics.toByteArray()), integrity == "ok" && foreignKeysOkay,
                        sha(schema.toByteArray()), mapOf("Habits" to count), mapOf("legacyMarker" to marker))
                }
            }
        }

        fun gate(fail: (RecoveryFailurePoint) -> Unit = {}) = ProductionRecoveryGate(bundles, ::inspect, fail) { 123456L }
        fun lease() = object : LegacySnapshotLease {
            override val source = this@Fixture.source
            override fun capturePreferences() = prefs
            override fun close() { leasesClosed++ }
        }

        fun mutate() {
            DriverManager.getConnection("jdbc:sqlite:${source.path}").use { connection ->
                connection.createStatement().use { it.execute("UPDATE Habits SET name='Changed' WHERE id=1") }
            }
        }

        override fun close() { root.deleteRecursively() }
    }

    @Test fun coherentBundleIndependentlyReopensAndKeepsSourceByteIdentical() = Fixture().use { fixture ->
        val before = fixture.source.readBytes()
        val inventory = fixture.inspect(fixture.source)
        val gate = fixture.gate()
        val ready = gate.prepare(fixture::lease)
        assertTrue(ready.migrationEligible)
        assertNotNull(ready.fingerprint)
        assertEquals(29, ready.fingerprint.userVersion)
        assertEquals(1, fixture.leasesClosed)
        val snapshot = File(fixture.bundles, "${ready.bundleId}/legacy-v29.db")
        assertContentEquals(before, snapshot.readBytes())
        assertEquals(inventory, fixture.inspect(snapshot))
        assertContentEquals(before, fixture.source.readBytes())
        assertEquals(ready, gate.recover(fixture.source, fixture.prefs))
        assertContentEquals(before, fixture.source.readBytes())
    }

    @Test fun staleDatabaseApprovalIsInvalidatedWithoutRestoringOldData() = Fixture().use { fixture ->
        val gate = fixture.gate()
        assertTrue(gate.prepare(fixture::lease).migrationEligible)
        fixture.mutate()
        val modified = fixture.source.readBytes()
        val result = gate.recover(fixture.source, fixture.prefs)
        assertFalse(result.migrationEligible)
        assertEquals(RecoveryBlocker.SOURCE_CHANGED, result.blocker)
        assertContentEquals(modified, fixture.source.readBytes())
        assertTrue(gate.prepare(fixture::lease).migrationEligible)
    }

    @Test fun changedAuthorityOrTimerPreferencesBlockWithoutSwitchingAnything() = Fixture().use { fixture ->
        val gate = fixture.gate()
        assertTrue(gate.prepare(fixture::lease).migrationEligible)
        val before = fixture.source.readBytes()
        fixture.prefs = fixture.prefs.copy(datasetMode = "CONTAINER_LOCAL_EXPERIMENTAL")
        assertEquals(RecoveryBlocker.PREFERENCES_CHANGED, gate.recover(fixture.source, fixture.prefs).blocker)
        assertEquals("CONTAINER_LOCAL_EXPERIMENTAL", fixture.prefs.datasetMode)
        assertContentEquals(before, fixture.source.readBytes())
    }

    @Test fun timerActiveOrIncompleteMetadataCannotProduceReadyBundle() = Fixture().use { fixture ->
        val before = fixture.source.readBytes()
        fixture.prefs = fixture.prefs.copy(values = fixture.prefs.values + (RecoveryPreferenceKey.TIMER_IS_RUNNING to "true"))
        assertEquals(RecoveryBlocker.PREFERENCES_NOT_READY, fixture.gate().prepare(fixture::lease).blocker)
        fixture.prefs = idlePreferences().copy(values = emptyMap())
        assertEquals(RecoveryBlocker.PREFERENCES_NOT_READY, fixture.gate().prepare(fixture::lease).blocker)
        assertContentEquals(before, fixture.source.readBytes())
    }

    @Test fun walAndHotRollbackJournalAreRejectedWithoutCheckpointOrDeletion() = Fixture().use { fixture ->
        val original = fixture.source.readBytes()
        listOf("-wal", "-journal").forEach { suffix ->
            val sidecar = File(fixture.source.path + suffix)
            sidecar.writeText("uncheckpointed or unresolved")
            val before = sidecar.readBytes()
            assertEquals(RecoveryBlocker.ACTIVE_SIDECAR, fixture.gate().prepare(fixture::lease).blocker)
            assertContentEquals(before, sidecar.readBytes())
            assertTrue(sidecar.delete())
        }
        assertContentEquals(original, fixture.source.readBytes())
    }

    @Test fun realUncheckpointedCommittedWalIsNeverCopiedAsMainFileOnly() = Fixture().use { fixture ->
        DriverManager.getConnection("jdbc:sqlite:${fixture.source.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA wal_autocheckpoint=0")
                statement.execute("INSERT INTO Habits VALUES(2,'wal-only-habit','Committed in WAL')")
                val wal = File(fixture.source.path + "-wal")
                assertTrue(wal.length() > 0)
                val beforeMain = fixture.source.readBytes()
                val beforeWal = wal.readBytes()
                assertEquals(RecoveryBlocker.ACTIVE_SIDECAR, fixture.gate().prepare(fixture::lease).blocker)
                assertContentEquals(beforeMain, fixture.source.readBytes())
                assertContentEquals(beforeWal, wal.readBytes())
                statement.executeQuery("SELECT COUNT(*) FROM Habits").use { it.next(); assertEquals(2L, it.getLong(1)) }
            }
        }
    }

    @Test fun missingDatabaseNeverCreatesEmptyCanonicalFile() = Fixture().use { fixture ->
        assertTrue(fixture.source.delete())
        val gate = fixture.gate()
        assertEquals(RecoveryBlocker.SOURCE_MISSING, gate.prepare(fixture::lease).blocker)
        assertFalse(fixture.source.exists())
        assertEquals(RecoveryBlocker.SOURCE_MISSING, gate.recover(fixture.source, fixture.prefs).blocker)
    }

    @Test fun missingAndCorruptVerifiedBundlesBlockReadiness() {
        listOf("missing-db", "missing-manifest", "corrupt-db", "corrupt-manifest").forEach { damage -> Fixture().use { fixture ->
            val gate = fixture.gate()
            val ready = gate.prepare(fixture::lease)
            val folder = File(fixture.bundles, ready.bundleId!!)
            when (damage) {
                "missing-db" -> assertTrue(File(folder, "legacy-v29.db").delete())
                "missing-manifest" -> assertTrue(File(folder, "manifest").delete())
                "corrupt-db" -> File(folder, "legacy-v29.db").appendText("corruption")
                "corrupt-manifest" -> File(folder, "manifest").appendText("corruption")
            }
            val before = fixture.source.readBytes()
            val result = gate.recover(fixture.source, fixture.prefs)
            assertFalse(result.migrationEligible)
            assertEquals(if (damage.startsWith("missing")) RecoveryBlocker.BUNDLE_MISSING else RecoveryBlocker.BUNDLE_CORRUPT, result.blocker)
            assertContentEquals(before, fixture.source.readBytes())
        } }
    }

    @Test fun forbiddenPreferenceEntryEvenWithValidChecksumIsRejected() = Fixture().use { fixture ->
        val gate = fixture.gate()
        val ready = gate.prepare(fixture::lease)
        val manifest = File(fixture.bundles, "${ready.bundleId}/manifest")
        val fields = decodeManifest(manifest)
        assertFalse(fields.keys.any { it.contains("token", true) || it.contains("password", true) || it.contains("email", true) })
        assertEquals(RecoveryPreferences.requiredTimerKeys.size, fields.keys.count { it.startsWith("pref.TIMER_") })
        assertEquals("LEGACY_PRODUCTION", fields["datasetMode"])
        writeManifest(manifest, fields + ("pref.authToken" to "forbidden-secret"))
        assertEquals(RecoveryBlocker.BUNDLE_CORRUPT, gate.recover(fixture.source, fixture.prefs).blocker)
    }

    @Test fun persistedStateChecksumFailureIsFailClosed() = Fixture().use { fixture ->
        val gate = fixture.gate()
        assertTrue(gate.prepare(fixture::lease).migrationEligible)
        File(fixture.bundles, "preparation.state").appendText("damage")
        val before = fixture.source.readBytes()
        assertEquals(RecoveryBlocker.STATE_CORRUPT, gate.recover(fixture.source, fixture.prefs).blocker)
        assertContentEquals(before, fixture.source.readBytes())
    }

    @Test fun everyCreationInterruptionKeepsSourceAndSupportsDeterministicRestartAndRetry() {
        RecoveryFailurePoint.values().filter { it != RecoveryFailurePoint.DURING_INCOMPLETE_CLEANUP }.forEach { boundary ->
            Fixture().use { fixture ->
                val before = fixture.source.readBytes()
                val result = fixture.gate { if (it == boundary) throw IllegalStateException("injected $boundary") }.prepare(fixture::lease)
                assertEquals(RecoveryBlocker.IO_FAILURE, result.blocker, boundary.name)
                assertContentEquals(before, fixture.source.readBytes(), boundary.name)
                assertEquals(1, fixture.leasesClosed)
                val restart = fixture.gate().recover(fixture.source, fixture.prefs)
                if (boundary == RecoveryFailurePoint.AFTER_VERIFICATION_BEFORE_READY) {
                    assertTrue(restart.migrationEligible)
                } else {
                    assertEquals(RecoveryPreparationState.LEGACY_STABLE, restart.state, boundary.name)
                    assertEquals(RecoveryBlocker.PREPARATION_INTERRUPTED, restart.blocker, boundary.name)
                    assertEquals(RecoveryPreparationState.LEGACY_STABLE, fixture.gate().recover(fixture.source, fixture.prefs).state)
                }
                assertTrue(fixture.gate().prepare(fixture::lease).migrationEligible, boundary.name)
                assertContentEquals(before, fixture.source.readBytes(), boundary.name)
            }
        }
    }

    @Test fun interruptionDuringIncompleteCleanupIsRetriableAndBounded() = Fixture().use { fixture ->
        val before = fixture.source.readBytes()
        val failed = fixture.gate { if (it == RecoveryFailurePoint.DURING_SNAPSHOT) error("copy interrupted") }.prepare(fixture::lease)
        val unrelated = File(fixture.bundles, "unrelated-user-file").apply { writeText("keep") }
        val cleanupFailure = fixture.gate { if (it == RecoveryFailurePoint.DURING_INCOMPLETE_CLEANUP) error("cleanup interrupted") }
            .recover(fixture.source, fixture.prefs)
        assertEquals(RecoveryBlocker.IO_FAILURE, cleanupFailure.blocker)
        assertFalse(File(fixture.bundles, "${failed.bundleId}/legacy-v29.db").exists())
        assertEquals(RecoveryPreparationState.LEGACY_STABLE, fixture.gate().recover(fixture.source, fixture.prefs).state)
        assertEquals("keep", unrelated.readText())
        assertContentEquals(before, fixture.source.readBytes())
        assertTrue(fixture.gate().prepare(fixture::lease).migrationEligible)
    }

    @Test fun changedPreferencesDuringSnapshotCannotBeApproved() = Fixture().use { fixture ->
        var captures = 0
        val result = fixture.gate().prepare {
            object : LegacySnapshotLease {
                override val source = fixture.source
                override fun capturePreferences(): RecoveryPreferences {
                    captures++
                    return if (captures == 1) fixture.prefs else fixture.prefs.copy(values = fixture.prefs.values + (RecoveryPreferenceKey.SYNC_LAST_LOG_ID to "2"))
                }
                override fun close() { }
            }
        }
        assertEquals(RecoveryBlocker.PREFERENCES_CHANGED, result.blocker)
        assertFalse(result.migrationEligible)
    }

    @Test fun changingSourceInsideSupposedQuiescenceIsDetected() = Fixture().use { fixture ->
        val result = fixture.gate { if (it == RecoveryFailurePoint.AFTER_COPY_BEFORE_MANIFEST) fixture.mutate() }.prepare(fixture::lease)
        assertEquals(RecoveryBlocker.SOURCE_CHANGED, result.blocker)
        assertFalse(result.migrationEligible)
    }

    @Test fun unexpectedVersionCannotCreateApprovedRecoveryBundle() = Fixture().use { fixture ->
        DriverManager.getConnection("jdbc:sqlite:${fixture.source.path}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=30") }
        }
        val before = fixture.source.readBytes()
        assertEquals(RecoveryBlocker.SOURCE_INELIGIBLE, fixture.gate().prepare(fixture::lease).blocker)
        assertContentEquals(before, fixture.source.readBytes())
    }

    @Test fun fingerprintStableAcrossRepeatedPreparationOfSameSource() = Fixture().use { fixture ->
        val gate = fixture.gate()
        val first = gate.prepare(fixture::lease)
        val second = gate.prepare(fixture::lease)
        assertTrue(first.migrationEligible && second.migrationEligible)
        assertEquals(first.fingerprint, second.fingerprint)
        assertFalse(first.bundleId == second.bundleId)
    }

    @Test fun independentCopyIntegrityOrSemanticFailureCannotGrantReadiness() {
        listOf(false, true).forEach { semanticFailure -> Fixture().use { fixture ->
            val before = fixture.source.readBytes()
            val gate = ProductionRecoveryGate(fixture.bundles, { file ->
                val inspection = fixture.inspect(file)
                if (file.canonicalFile == fixture.source.canonicalFile) inspection
                else if (semanticFailure) inspection.copy(semanticHash = "different") else inspection.copy(eligible = false)
            })
            assertEquals(RecoveryBlocker.BUNDLE_CORRUPT, gate.prepare(fixture::lease).blocker)
            assertContentEquals(before, fixture.source.readBytes())
        } }
    }

    @Test fun malformedSelectedPreferenceValuesBlockBeforeCopy() = Fixture().use { fixture ->
        val cases = mapOf(RecoveryPreferenceKey.SYNC_ENABLED to "yes",
            RecoveryPreferenceKey.TIMER_ACCUMULATED_ELAPSED_MILLIS to "not-a-number",
            RecoveryPreferenceKey.TIMER_FOCUS_DURATION_MILLIS to "-1",
            RecoveryPreferenceKey.FIRST_WEEKDAY to "0",
            RecoveryPreferenceKey.TIMER_MODE to "UNKNOWN",
            RecoveryPreferenceKey.SYNC_DEVICE_ID to "invalid-sync-device-id!")
        val before = fixture.source.readBytes()
        cases.forEach { (key, value) ->
            fixture.prefs = idlePreferences().copy(values = idlePreferences().values + (key to value))
            assertEquals(RecoveryBlocker.PREFERENCES_NOT_READY, fixture.gate().prepare(fixture::lease).blocker)
            assertTrue(fixture.bundles.listFiles()!!.none { it.isDirectory })
        }
        assertContentEquals(before, fixture.source.readBytes())
    }

    @Test fun manifestRequiredMetadataCannotBeOmittedEvenWithRecomputedChecksum() = Fixture().use { fixture ->
        val gate = fixture.gate()
        val ready = gate.prepare(fixture::lease)
        val manifest = File(fixture.bundles, "${ready.bundleId}/manifest")
        writeManifest(manifest, decodeManifest(manifest) - "createdAtMillis")
        assertEquals(RecoveryBlocker.BUNDLE_CORRUPT, gate.recover(fixture.source, fixture.prefs).blocker)
    }

    @Test fun abruptSubprocessDeathAtEveryBoundaryKeepsLegacyCanonicalAndRestartSafe() {
        val classpath = runtimeClasspath()
        val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        RecoveryFailurePoint.values().forEach { boundary -> Fixture().use { fixture ->
            val before = fixture.source.readBytes()
            if (boundary == RecoveryFailurePoint.DURING_INCOMPLETE_CLEANUP) {
                val interrupted = fixture.gate { if (it == RecoveryFailurePoint.DURING_SNAPSHOT) error("create incomplete snapshot") }
                    .prepare(fixture::lease)
                assertEquals(RecoveryBlocker.IO_FAILURE, interrupted.blocker)
            }
            val log = File(fixture.root, "subprocess.log")
            val process = ProcessBuilder(java.path, "-cp", classpath, ProductionRecoveryGateTest::class.java.name,
                fixture.root.path, boundary.name).redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child timed out at $boundary")
                assertEquals(73, process.exitValue(), "child did not halt at $boundary: ${log.readText()}")
            } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
            assertContentEquals(before, fixture.source.readBytes(), boundary.name)
            val restart = fixture.gate().recover(fixture.source, fixture.prefs)
            if (boundary == RecoveryFailurePoint.AFTER_VERIFICATION_BEFORE_READY) assertTrue(restart.migrationEligible)
            else assertEquals(RecoveryPreparationState.LEGACY_STABLE, restart.state, boundary.name)
            assertTrue(fixture.gate().prepare(fixture::lease).migrationEligible, boundary.name)
            assertContentEquals(before, fixture.source.readBytes(), boundary.name)
        } }
    }

    private fun runtimeClasspath(): String {
        val fromLoaders = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.getURLs().asSequence() }
            .filter { it.protocol == "file" }.map { File(it.toURI()).path }.toList()
        val inherited = System.getProperty("java.class.path").split(File.pathSeparator)
        return (fromLoaders + inherited).distinct().joinToString(File.pathSeparator)
    }

    companion object {
        /** Child deliberately halts without finally/close/unwind: genuine persisted process-death boundaries. */
        @JvmStatic fun main(args: Array<String>) {
            try {
                val fixture = Fixture(File(args[0]), createDatabase = false)
                val boundary = RecoveryFailurePoint.valueOf(args[1])
                val gate = fixture.gate { if (it == boundary) Runtime.getRuntime().halt(73) }
                if (boundary == RecoveryFailurePoint.DURING_INCOMPLETE_CLEANUP) gate.recover(fixture.source, fixture.prefs)
                else gate.prepare(fixture::lease)
                System.err.println("Expected failure boundary was not reached: $boundary")
                Runtime.getRuntime().halt(74)
            } catch (failure: Throwable) {
                failure.printStackTrace()
                Runtime.getRuntime().halt(75)
            }
        }

        private fun idlePreferences(): RecoveryPreferences {
            val values = RecoveryPreferences.requiredKeys.associateWith { "0" }.toMutableMap()
            values[RecoveryPreferenceKey.SYNC_DEVICE_ID] = ""
            listOf(RecoveryPreferenceKey.SYNC_ENABLED, RecoveryPreferenceKey.SYNC_REVIEW_REQUIRED,
                RecoveryPreferenceKey.SYNC_BOOTSTRAP_QUEUED, RecoveryPreferenceKey.SYNC_BOOTSTRAP_DONE,
                RecoveryPreferenceKey.MIDNIGHT_DELAY, RecoveryPreferenceKey.POMODORO_AUTO_SWITCH).forEach { values[it] = "false" }
            values[RecoveryPreferenceKey.FIRST_WEEKDAY] = "-1"
            values[RecoveryPreferenceKey.POMODORO_DEFAULT_FOCUS] = "25"
            values[RecoveryPreferenceKey.POMODORO_DEFAULT_BREAK] = "5"
            values[RecoveryPreferenceKey.TIMER_HABIT_ID] = "-1"
            values[RecoveryPreferenceKey.TIMER_MODE] = "STOPWATCH"
            values[RecoveryPreferenceKey.TIMER_PHASE] = "FOCUS"
            values[RecoveryPreferenceKey.TIMER_IS_RUNNING] = "false"
            values[RecoveryPreferenceKey.TIMER_COMPLETION_TRIGGERED] = "false"
            values[RecoveryPreferenceKey.TIMER_COMPLETION_DISPLAY_ACTIVE] = "false"
            values[RecoveryPreferenceKey.TIMER_FOCUS_DURATION_MILLIS] = "1500000"
            values[RecoveryPreferenceKey.TIMER_BREAK_DURATION_MILLIS] = "300000"
            return RecoveryPreferences(values = values)
        }

        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun decodeManifest(file: File): Map<String, String> {
            val decoder = Base64.getDecoder()
            return file.readLines().drop(1).filter { it.isNotEmpty() }.associate { line ->
                val pair = line.split(':', limit = 2)
                String(decoder.decode(pair[0]), Charsets.UTF_8) to String(decoder.decode(pair[1]), Charsets.UTF_8)
            }
        }

        private fun writeManifest(file: File, fields: Map<String, String>) {
            val encoder = Base64.getEncoder()
            fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
            val payload = fields.toSortedMap().entries.joinToString("\n", postfix = "\n") { encode(it.key) + ":" + encode(it.value) }.toByteArray()
            file.writeBytes((sha(payload) + "\n").toByteArray() + payload)
        }
    }
}
