package org.isoron.uhabits.activities.tasks

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/** Exercises the real Android Bundle/Parcel implementation, including nested Due state. */
@RunWith(AndroidJUnit4::class)
class TaskDraftBundleTest {
    @Test
    fun timedDueAndAllDraftFieldsSurviveExpandCollapseAndRecreationTransfers() {
        val zone = ZoneId.of("Asia/Novosibirsk")
        val millis = LocalDateTime.of(2026, 10, 23, 18, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val original = TaskDraft(
            title = "PR5 timed draft",
            description = "Notes retained across editor transfers",
            due = TaskDue.Timed.local(millis, zone.id),
            containerId = ContainerId("pr5-section"),
            subtasks = listOf(
                TaskDraft.DraftSubtask("First", false),
                TaskDraft.DraftSubtask("Second", true),
            ),
        )

        // Quick Add -> expanded arguments -> saved state -> collapse result -> reopen.
        var restored = original
        repeat(4) {
            restored = TaskDraft.fromBundle(parcelRoundTrip(restored.toBundle()))
            assertEquals(original, restored)
            val due = restored.due as TaskDue.Timed
            assertEquals(millis, due.epochMillis)
            assertEquals("Asia/Novosibirsk", due.originZoneId)
            assertEquals(LocalDateTime.of(2026, 10, 23, 18, 0),
                java.time.Instant.ofEpochMilli(due.epochMillis).atZone(zone).toLocalDateTime())
        }
    }

    private fun parcelRoundTrip(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(TaskDraft::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
