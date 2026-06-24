# Sync Conflict Model

- Local SQLite remains the UI source of truth. Remote sync exchanges entity changes and immutable entry operations; it does not transfer the whole `uhabits.db`.
- Habit metadata uses last-write-wins by `updated_at` / sync event time. A newer tombstone (`deleted_at`) beats an older update.
- Boolean and daily entry state uses the last explicit action wins rule.
- Numerical/timer accumulation uses immutable `EntryOps`; clients dedupe by `op_uuid` and materialize totals locally.
- Historical goals are append/update by stable goal UUID (`habit_uuid:goal:effective_timestamp`) and resolved by `updated_at`.
- Backup restore is destructive local restore, not sync. After restore, sync is paused until the user confirms review.
