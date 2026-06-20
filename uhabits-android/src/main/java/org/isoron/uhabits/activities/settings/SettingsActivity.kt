package org.isoron.uhabits.activities.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination

/** Compatibility entry point for existing internal intents. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.intent(this, MainDestination.SETTINGS))
        finish()
    }
}
