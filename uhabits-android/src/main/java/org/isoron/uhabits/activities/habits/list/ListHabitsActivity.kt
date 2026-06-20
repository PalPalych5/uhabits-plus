package org.isoron.uhabits.activities.habits.list

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination

/** Compatibility entry point for existing widget and notification PendingIntents. */
class ListHabitsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            MainActivity.intent(this, MainDestination.HABITS).apply {
                action = intent.action
                putExtras(intent)
            }
        )
        finish()
    }

    companion object {
        const val ACTION_EDIT = "org.isoron.uhabits.ACTION_EDIT"
    }
}
