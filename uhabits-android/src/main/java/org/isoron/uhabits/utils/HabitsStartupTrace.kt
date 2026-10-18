package org.isoron.uhabits.utils

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.core.io.HabitStartupTiming
import java.io.File

/** Explicitly enabled only by the guarded offline QA fixture. No SQL, bindings or Habit names. */
object HabitsStartupTrace {
    const val MARKER = "qa-habits-startup-timing"
    @Volatile var enabled = false
        private set

    fun install(context: Context) {
        val isPermittedBuild = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "benchmark"
        enabled = isPermittedBuild && (Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) &&
            File(context.filesDir, MARKER).exists()
        HabitStartupTiming.observer = if (enabled) { stage, generation -> mark(stage, generation) } else null
    }

    fun mark(stage: String, generation: Long? = null, durationMs: Double? = null) {
        if (!enabled) return
        val at = SystemClock.elapsedRealtimeNanos()
        Log.i("UHabitStartup", "{\"stage\":\"$stage\",\"atNs\":$at,\"pid\":${Process.myPid()}," +
            "\"main\":${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()}," +
            "\"generation\":${generation ?: "null"},\"durationMs\":${durationMs ?: "null"}}")
    }

    /** Restrict, never grant, capabilities for a marked disposable dataset during cold-process QA. */
    fun offlineFixtureSession(context: Context, session: org.isoron.uhabits.core.containers.session.DatasetSession): org.isoron.uhabits.core.containers.session.DatasetSession {
        if (!enabled) return session
        val marker = File(context.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${session.datasetId}")
        if (!marker.isFile || marker.readText() != session.databaseFilename) return session
        return session.copy(capabilities = session.capabilities.copy(syncEnabled = false, remoteCallsAllowed = false,
            backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
    }
}
