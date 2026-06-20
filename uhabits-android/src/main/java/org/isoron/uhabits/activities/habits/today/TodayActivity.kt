package org.isoron.uhabits.activities.habits.today

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination

/** Compatibility entry point for shortcuts created before the main navigation host. */
class TodayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.intent(this, MainDestination.TODAY))
        finish()
    }
}
