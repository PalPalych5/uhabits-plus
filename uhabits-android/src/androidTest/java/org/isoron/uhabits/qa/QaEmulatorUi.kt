package org.isoron.uhabits.qa

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File

/** API 37's System UI can show its own ANR; never dismiss an application ANR. */
object QaEmulatorUi {
    fun clearSystemUiError() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)
        val title = device.findObject(By.res("android", "alertTitle"))?.text ?: return
        if (!title.contains("Интерфейс системы") && !title.contains("System UI")) return
        val directory = File(ins.targetContext.getExternalFilesDir(null), "ui-review/emulator-diagnostics").apply { mkdirs() }
        val stem = "system-ui-anr-${System.currentTimeMillis()}"
        device.takeScreenshot(File(directory, "$stem.png"))
        device.dumpWindowHierarchy(File(directory, "$stem.xml"))
        device.findObject(By.res("android", "aerr_close"))?.click()
        device.waitForIdle()
    }
}
