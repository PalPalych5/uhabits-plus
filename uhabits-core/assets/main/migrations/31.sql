-- Migration 31: Unified Task Due

ALTER TABLE UserTasks ADD COLUMN due_kind TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE UserTasks ADD COLUMN due_date TEXT;
ALTER TABLE UserTasks ADD COLUMN due_time_millis INTEGER;
ALTER TABLE UserTasks ADD COLUMN due_zone TEXT;

DROP INDEX IF EXISTS idx_user_tasks_planned;
CREATE INDEX IF NOT EXISTS idx_user_tasks_due_kind ON UserTasks(due_kind);
CREATE INDEX IF NOT EXISTS idx_user_tasks_due_date ON UserTasks(due_date);

-- Case 1 & 3: deadline is present (deadline-only or both deadline and planned_date)
-- Unified model sets due_kind = 'TIMED' and due_time_millis = deadline (deadline priority).
-- If planned_date was also present, it is preserved in the planned_date column for historical reference.
UPDATE UserTasks
SET due_kind = 'TIMED',
    due_time_millis = deadline
WHERE deadline IS NOT NULL AND deadline > 0;

-- Plan-only tasks (deadline IS NULL OR deadline <= 0):
-- Initial Due is NONE for ALL plan-only tasks (both past and future).
-- The raw planned_date column retains the historical planned date intact,
-- and the task is presented in the UI as requiring a decision on transferring/setting the due date.
