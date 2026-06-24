package org.isoron.uhabits.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BackupCatalogTest {
    @Test
    fun filterKeepsOnlyBackupPattern() {
        val dir = createTempDirectory().toFile()
        val backup = File(dir, "Loop Habits Backup 2026-06-24 120000.db").apply {
            writeText("a")
            setLastModified(2000L)
        }
        File(dir, "random.txt").writeText("b")

        val filtered = BackupCatalog.filterLocalBackupFiles(dir.listFiles()?.toList().orEmpty())

        assertEquals(listOf(backup), filtered)
    }

    @Test
    fun retentionKeepsNewestBackupsAndIgnoresForeignFiles() {
        val dir = createTempDirectory().toFile()
        val kept = (1..3).map {
            File(dir, "Loop Habits Backup 2026-06-24 12000$it.db").apply {
                writeText("x")
                setLastModified(it.toLong())
            }
        }
        val foreign = File(dir, "foreign.db").apply { writeText("y") }

        BackupCatalog.applyRetentionToLocalFiles(dir.listFiles()?.toList().orEmpty(), keep = 2)

        assertFalse(kept[0].exists())
        assertTrue(kept[1].exists())
        assertTrue(kept[2].exists())
        assertTrue(foreign.exists())
    }
}
