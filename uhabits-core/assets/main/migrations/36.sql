ALTER TABLE HabitExtensions ADD COLUMN missing_data_policy INTEGER;
CREATE TABLE IF NOT EXISTS HabitDayAdjustments (
    habit_uuid TEXT NOT NULL,
    date INTEGER NOT NULL,
    adjustment_millis INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (habit_uuid, date)
);
CREATE INDEX IF NOT EXISTS idx_habit_day_adjustments_habit_date ON HabitDayAdjustments(habit_uuid, date);
