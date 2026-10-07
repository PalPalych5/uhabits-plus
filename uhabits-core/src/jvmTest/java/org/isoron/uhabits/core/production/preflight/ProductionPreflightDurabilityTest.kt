package org.isoron.uhabits.core.production.preflight

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.JavaDatabase
import org.isoron.platform.io.JavaDatabaseOpener
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductionPreflightDurabilityTest {
    @Test fun actualReadOnlyFileIsByteIdenticalAndFingerprintInvalidatesOnWrite() = runTest {
        val path = Files.createTempFile("preflight-v29-", ".db")
        try {
            val seed = JavaDatabaseOpener().open(path.toString())
            try {
                seed.setVersion(8)
                seed.migrateTo(29) { TestDatabaseHelper.loadMigrationSQL(it) }
            } finally { seed.close() }
            val before = Files.readAllBytes(path)
            val sha = MessageDigest.getInstance("SHA-256").digest(before).joinToString("") { "%02x".format(it) }
            val readonly = JavaDatabase(DriverManager.getConnection("jdbc:sqlite:file:${path.toUri().rawPath}?mode=ro"))
            val report = try { ProductionPreflightService.inspect(readonly, path.toString(), sha) } finally { readonly.close() }
            assertTrue(report.sourceEligible, report.blockingIssues.toString())
            assertEquals(29, report.userVersion)
            assertContentEquals(before, Files.readAllBytes(path))
            val writer = JavaDatabaseOpener().open(path.toString())
            try {
                writer.run("INSERT INTO AppSettings(key,long_value) VALUES ('marker',7)")
                val changed = ProductionPreflightService.inspect(writer, path.toString(), sha)
                assertTrue(changed.fingerprint.semanticSha256 != report.fingerprint.semanticSha256)
            } finally { writer.close() }
        } finally { Files.deleteIfExists(path) }
    }
}
