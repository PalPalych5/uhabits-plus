-- Persist the unresolved legacy-plan decision separately from historical raw data.
ALTER TABLE UserTasks ADD COLUMN legacy_plan_review_pending INTEGER NOT NULL DEFAULT 0 CHECK(legacy_plan_review_pending IN (0, 1));
UPDATE UserTasks SET legacy_plan_review_pending = 1 WHERE due_kind = 'NONE' AND planned_date IS NOT NULL;
