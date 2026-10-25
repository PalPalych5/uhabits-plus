ALTER TABLE UserTasks ADD COLUMN linked_habit_uuid TEXT;
ALTER TABLE UserTasks ADD COLUMN habit_time_accrual_enabled INTEGER NOT NULL DEFAULT 0 CHECK(habit_time_accrual_enabled IN (0, 1));
ALTER TABLE UserTasks ADD COLUMN habit_link_effective_from INTEGER;
CREATE INDEX IF NOT EXISTS idx_user_tasks_linked_habit ON UserTasks(linked_habit_uuid);
