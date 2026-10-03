/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.core.containers.migration

import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.run
import org.isoron.uhabits.core.database.SQLParser

object SemanticMigrationFixtures {

    private fun Database.execScript(script: String) {
        for (stmt in SQLParser.parse(script)) {
            val trimmed = stmt.trim()
            if (trimmed.isNotEmpty()) {
                run(trimmed)
            }
        }
    }

    /**
     * Creates and migrates an empty database to v29, then seeds a comprehensive set
     * of semantic fixtures covering all edge cases specified in the Foundation plan.
     */
    suspend fun createAndSeedV29Database(): Database {
        val db = TestDatabaseHelper.createEmptyDatabase()
        seedAllFixtures(db)
        return db
    }

    fun seedAllFixtures(db: Database) {
        // 1. HabitBlocks:
        // - Block 1: Active ("Интеллект / обучение", uuid = b-intellect)
        // - Block 2: Active ("Тело", uuid = b-body)
        // - Block 3: Archived ("Архивный блок", uuid = b-archived, is_archived = 1)
        // - Block 4: Tombstoned ("Удаленный блок", uuid = b-tombstoned, deleted_at = 150000)
        // - Block 5: Duplicate name ("Тело", uuid = b-body-dup)
        db.execScript(
            """
            DELETE FROM HabitBlocks;
            INSERT INTO HabitBlocks (id, uuid, name, color, icon, position, is_archived, updated_at, deleted_at) VALUES
            (1, 'b-uuid-1', 'Интеллект / обучение', 11, 'school', 0, 0, 1000, NULL),
            (2, 'b-uuid-2', 'Тело', 6, 'directions_run', 1, 0, 1000, NULL),
            (3, 'b-uuid-3', 'Архивный блок', 3, 'archive', 2, 1, 1000, NULL),
            (4, 'b-uuid-4', 'Удаленный блок', 1, 'delete', 3, 0, 1000, 150000),
            (5, 'b-uuid-5', 'Тело', 8, 'fitness', 4, 0, 1000, NULL);
            """.trimIndent()
        )

        // 2. Habits:
        // - Habit 1: Active, Boolean, daily ("Чтение", assigned to block 1)
        // - Habit 2: Active, Numerical min AT_LEAST, daily, scaling x1000 ("Бег", assigned to block 2)
        // - Habit 3: Active, Numerical AT_MOST, weekly ("Кофе", assigned to block 2)
        // - Habit 4: Active, Boolean, monthly ("Отчет", unassigned: block_id = null)
        // - Habit 5: Archived ("Старая привычка", archived = 1, assigned to block 3)
        // - Habit 6: Tombstoned ("Удаленная привычка", deleted_at = 200000, extension missing)
        // - Habit 7: Active with duplicated name ("Чтение", uuid = h-read-2, assigned to block 1)
        db.execScript(
            """
            DELETE FROM Habits;
            INSERT INTO Habits (
                id, uuid, name, description, question, freq_num, freq_den, color, position,
                reminder_hour, reminder_min, reminder_days, highlight, archived, type,
                target_value, target_type, unit, updated_at, deleted_at
            ) VALUES
            (1, 'h-uuid-1', 'Чтение', 'Книги', 'Читал?', 1, 1, 11, 0, 20, 0, 127, 0, 0, 0, 0.0, 0, '', 1000, NULL),
            (2, 'h-uuid-2', 'Бег', 'Пробежка', 'Бегал?', 1, 1, 6, 1, 7, 30, 127, 1, 0, 1, 30.0, 0, 'мин.', 1000, NULL),
            (3, 'h-uuid-3', 'Кофе', 'Лимит', 'Сколько чашек?', 3, 7, 6, 2, NULL, NULL, 0, 0, 0, 1, 5.0, 1, 'чашек', 1000, NULL),
            (4, 'h-uuid-4', 'Отчет', 'Месячный', 'Сдал?', 1, 30, 0, 3, NULL, NULL, 0, 0, 0, 0, 0.0, 0, '', 1000, NULL),
            (5, 'h-uuid-5', 'Старая привычка', 'Архив', 'Делал?', 1, 1, 3, 4, NULL, NULL, 0, 0, 1, 0, 0.0, 0, '', 1000, NULL),
            (6, 'h-uuid-6', 'Удаленная привычка', 'Делит', 'Делал?', 1, 1, 1, 5, NULL, NULL, 0, 0, 0, 0, 0.0, 0, '', 1000, 200000),
            (7, 'h-uuid-7', 'Чтение', 'Статьи', 'Читал статьи?', 1, 1, 11, 6, NULL, NULL, 0, 0, 0, 0, 0.0, 0, '', 1000, NULL);
            """.trimIndent()
        )

        // 3. HabitExtensions:
        // Habits 1, 2, 3, 4, 5, 7 have extensions. Habit 6 has missing extension (tombstoned legacy scenario).
        db.execScript(
            """
            DELETE FROM HabitExtensions;
            INSERT INTO HabitExtensions (habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp) VALUES
            (1, 'NORMAL', 0, 1, NULL),
            (2, 'IDEAL', 1, 2, 50000),
            (3, 'MINIMUM', 0, 2, NULL),
            (4, 'OPTIONAL', 0, NULL, NULL),
            (5, 'NORMAL', 0, 3, NULL),
            (7, 'NORMAL', 0, 1, NULL);
            """.trimIndent()
        )

        // 4. HabitGoals:
        // Habit 2 has goal history: initial 30.0 (30000 millisecs/minutes), then increased to 42.0 effective at timestamp 500000.
        db.execScript(
            """
            DELETE FROM HabitGoals;
            INSERT INTO HabitGoals (id, habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value, unit, uuid, updated_at, deleted_at) VALUES
            (1, 1, 0, 1, 1, 0, 0.0, '', 'g-uuid-1', 1000, NULL),
            (2, 2, 0, 1, 1, 0, 30.0, 'мин.', 'g-uuid-2-1', 1000, NULL),
            (3, 2, 500000, 1, 1, 0, 42.0, 'мин.', 'g-uuid-2-2', 500000, NULL),
            (4, 3, 0, 3, 7, 1, 5.0, 'чашек', 'g-uuid-3', 1000, NULL),
            (5, 4, 0, 1, 30, 0, 0.0, '', 'g-uuid-4', 1000, NULL),
            (6, 5, 0, 1, 1, 0, 0.0, '', 'g-uuid-5', 1000, NULL),
            (7, 7, 0, 1, 1, 0, 0.0, '', 'g-uuid-7', 1000, NULL);
            """.trimIndent()
        )

        // 5. Repetitions:
        // Boolean values: YES_MANUAL=2, YES_AUTO=3, NO=0, UNKNOWN=-1, SKIP=-2.
        // Numerical values: explicit zero=0, numerical UNKNOWN=-1000, numerical SKIP=-2000, value x1000 (e.g. 42000 for 42.0).
        // Notes included on entries.
        db.execScript(
            """
            DELETE FROM Repetitions;
            INSERT INTO Repetitions (habit, timestamp, value, notes, uuid, updated_at, deleted_at) VALUES
            (1, 10000, 2, 'Читал главу 1', 'h-uuid-1:10000', 10000, NULL),
            (1, 20000, 3, 'Авто-отметка', 'h-uuid-1:20000', 20000, NULL),
            (1, 30000, 0, 'Не читал', 'h-uuid-1:30000', 30000, NULL),
            (1, 40000, -1, NULL, 'h-uuid-1:40000', 40000, NULL),
            (1, 50000, -2, 'Пропуск по болезни', 'h-uuid-1:50000', 50000, NULL),
            (2, 10000, 0, 'Явный ноль', 'h-uuid-2:10000', 10000, NULL),
            (2, 20000, -1000, NULL, 'h-uuid-2:20000', 20000, NULL),
            (2, 30000, -2000, 'Отдых', 'h-uuid-2:30000', 30000, NULL),
            (2, 40000, 42000, 'Пробежал 42 мин', 'h-uuid-2:40000', 40000, NULL);
            """.trimIndent()
        )

        // 6. EntryOps:
        db.execScript(
            """
            DELETE FROM EntryOps;
            INSERT INTO EntryOps (op_uuid, habit_uuid, entry_timestamp, delta_value, op_type, notes, device_id, created_at, deleted_at) VALUES
            ('op-uuid-1', 'h-uuid-1', 10000, 2, 'SET', 'Читал главу 1', 'dev-1', 10000, NULL),
            ('op-uuid-2', 'h-uuid-2', 40000, 42000, 'SET', 'Пробежал 42 мин', 'dev-1', 40000, NULL);
            """.trimIndent()
        )

        // 7. SyncQueue:
        db.execScript(
            """
            DELETE FROM SyncQueue;
            INSERT INTO SyncQueue (queue_id, op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id, pushed_at, failed_at, failure_reason) VALUES
            (1, 'op-uuid-1', 'ENTRY', 'h-uuid-1', 'UPSERT', '{"val":2}', 10000, 'dev-1', NULL, NULL, NULL);
            """.trimIndent()
        )

        // 8. AppSettings:
        db.execScript(
            """
            DELETE FROM AppSettings;
            INSERT INTO AppSettings (key, long_value) VALUES
            ('sync_last_sync_timestamp', 50000),
            ('first_run_timestamp', 1000);
            """.trimIndent()
        )
    }
}
