/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.database

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Database-only tests must not start HabitsApplication's legacy test reset path. */
class TaskDueMigrationRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
