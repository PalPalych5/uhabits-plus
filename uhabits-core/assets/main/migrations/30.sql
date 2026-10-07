-- Migration 30: Complete UHabit Next durable schema

CREATE TABLE IF NOT EXISTS OrganizationState (
    id INTEGER PRIMARY KEY CHECK(id = 1),
    dataset_uuid TEXT NOT NULL UNIQUE,
    foundation_version INTEGER NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('STAGED', 'CONTAINER_LOCAL', 'PRODUCTION_NEXT')),
    cutover_revision INTEGER NOT NULL DEFAULT 1,
    current_revision INTEGER NOT NULL DEFAULT 1,
    cutover_at INTEGER NOT NULL,
    source_schema_version INTEGER NOT NULL,
    source_snapshot_sha256 TEXT NOT NULL,
    source_file_sha256 TEXT,
    source_schema_sha256 TEXT,
    runtime_authority TEXT NOT NULL DEFAULT 'LEGACY' CHECK(runtime_authority IN ('LEGACY', 'NEXT')),
    authority_epoch INTEGER NOT NULL DEFAULT 0,
    conversion_op_uuid TEXT
);

CREATE TABLE IF NOT EXISTS OrganizationChanges (
    revision INTEGER PRIMARY KEY,
    op_uuid TEXT NOT NULL UNIQUE,
    recorded_at INTEGER NOT NULL,
    operation_type TEXT NOT NULL,
    origin TEXT NOT NULL,
    command_payload TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_org_changes_recorded ON OrganizationChanges(recorded_at, revision);

CREATE TABLE IF NOT EXISTS Containers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    parent_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
    name TEXT NOT NULL CHECK(trim(name) <> ''),
    color INTEGER NULL,
    icon TEXT NULL,
    sibling_order INTEGER NOT NULL CHECK(sibling_order >= 0),
    is_archived INTEGER NOT NULL CHECK(is_archived IN (0, 1)),
    created_at INTEGER NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER NULL,
    revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision),
    CHECK(parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX IF NOT EXISTS idx_containers_tree ON Containers(parent_id, deleted_at, is_archived, sibling_order, uuid);
CREATE INDEX IF NOT EXISTS idx_containers_active ON Containers(deleted_at, is_archived, uuid);

CREATE TABLE IF NOT EXISTS HabitPlacements (
    habit_id INTEGER PRIMARY KEY REFERENCES Habits(id) ON DELETE RESTRICT,
    container_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
    local_order INTEGER NOT NULL CHECK(local_order >= 0),
    placement_origin TEXT NOT NULL CHECK(placement_origin IN ('MIGRATION_SNAPSHOT', 'USER_CHANGE', 'IMPORT_SNAPSHOT', 'UNKNOWN_LEGACY')),
    revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision)
);

CREATE INDEX IF NOT EXISTS idx_habit_placements_contents ON HabitPlacements(container_id, local_order, habit_id);

CREATE TABLE IF NOT EXISTS ContainerHistory (
    container_id INTEGER NOT NULL REFERENCES Containers(id) ON DELETE RESTRICT,
    revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision),
    parent_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
    name TEXT NOT NULL,
    color INTEGER NULL,
    icon TEXT NULL,
    sibling_order INTEGER NOT NULL,
    is_archived INTEGER NOT NULL,
    created_at INTEGER NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER NULL,
    PRIMARY KEY(container_id, revision)
);

CREATE INDEX IF NOT EXISTS idx_container_history_lookup ON ContainerHistory(container_id, revision DESC);

CREATE TABLE IF NOT EXISTS HabitPlacementHistory (
    habit_id INTEGER NOT NULL REFERENCES Habits(id) ON DELETE RESTRICT,
    revision INTEGER NOT NULL REFERENCES OrganizationChanges(revision),
    container_id INTEGER NULL REFERENCES Containers(id) ON DELETE RESTRICT,
    local_order INTEGER NOT NULL,
    placement_origin TEXT NOT NULL CHECK(placement_origin IN ('MIGRATION_SNAPSHOT', 'USER_CHANGE', 'IMPORT_SNAPSHOT', 'UNKNOWN_LEGACY')),
    PRIMARY KEY(habit_id, revision)
);

CREATE INDEX IF NOT EXISTS idx_habit_placement_history_lookup ON HabitPlacementHistory(habit_id, revision DESC);

CREATE TABLE IF NOT EXISTS LegacyBlockMap (
    legacy_block_id INTEGER PRIMARY KEY REFERENCES HabitBlocks(id) ON DELETE RESTRICT,
    legacy_block_uuid TEXT NOT NULL UNIQUE,
    container_id INTEGER NOT NULL UNIQUE REFERENCES Containers(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS HabitVisualIdentities (
    habit_uuid TEXT PRIMARY KEY,
    icon_type TEXT NOT NULL CHECK(icon_type IN ('VECTOR', 'EMOJI')),
    icon_value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS UserTasks (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL CHECK(trim(title) <> ''),
    description TEXT NOT NULL DEFAULT '',
    container_id TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'COMPLETED')),
    planned_date TEXT,
    deadline INTEGER,
    completed_at INTEGER,
    parent_task_id TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_tasks_container ON UserTasks(container_id);
CREATE INDEX IF NOT EXISTS idx_user_tasks_parent ON UserTasks(parent_task_id);
CREATE INDEX IF NOT EXISTS idx_user_tasks_status ON UserTasks(status);
CREATE INDEX IF NOT EXISTS idx_user_tasks_planned ON UserTasks(planned_date);
CREATE INDEX IF NOT EXISTS idx_user_tasks_order ON UserTasks(parent_task_id, sort_order);

CREATE TABLE IF NOT EXISTS FocusSessions (
    id TEXT PRIMARY KEY NOT NULL CHECK(trim(id) <> ''),
    started_at INTEGER NOT NULL CHECK(started_at >= 0),
    ended_at INTEGER NOT NULL CHECK(ended_at > started_at),
    duration_ms INTEGER NOT NULL CHECK(duration_ms > 0 AND duration_ms <= ended_at - started_at),
    task_id TEXT CHECK(task_id IS NULL OR trim(task_id) <> ''),
    habit_uuid TEXT CHECK(habit_uuid IS NULL OR trim(habit_uuid) <> ''),
    container_id TEXT CHECK(container_id IS NULL OR trim(container_id) <> ''),
    source TEXT NOT NULL CHECK(source IN ('MANUAL', 'TIMER')),
    device_id TEXT CHECK(device_id IS NULL OR trim(device_id) <> ''),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
    revision INTEGER NOT NULL CHECK(revision = 1),
    primary_target_type TEXT CHECK(primary_target_type IS NULL OR primary_target_type IN ('TASK', 'HABIT', 'CONTAINER')),
    organization_context TEXT
);

CREATE INDEX IF NOT EXISTS idx_focus_sessions_task ON FocusSessions(task_id, started_at, id);
CREATE INDEX IF NOT EXISTS idx_focus_sessions_started ON FocusSessions(started_at, id);
CREATE INDEX IF NOT EXISTS idx_focus_sessions_ended ON FocusSessions(ended_at, id);

CREATE TABLE IF NOT EXISTS FocusCaptureJournal (
    slot INTEGER PRIMARY KEY CHECK(slot = 1),
    timer_id INTEGER,
    timer_name TEXT NOT NULL,
    mode TEXT NOT NULL,
    phase TEXT NOT NULL,
    running INTEGER NOT NULL,
    elapsed INTEGER NOT NULL,
    display INTEGER NOT NULL,
    focus_duration INTEGER NOT NULL,
    break_duration INTEGER NOT NULL,
    started_at INTEGER NOT NULL,
    accumulated INTEGER NOT NULL,
    overtime INTEGER NOT NULL,
    completion INTEGER NOT NULL,
    auto_switch INTEGER NOT NULL,
    observed_at INTEGER NOT NULL,
    target_kind TEXT,
    target_id TEXT,
    target_container_id TEXT,
    organization_context TEXT,
    active_id TEXT,
    active_started_at INTEGER,
    active_elapsed_offset INTEGER,
    active_device_id TEXT
);

CREATE TABLE IF NOT EXISTS Contributions (
    id TEXT PRIMARY KEY NOT NULL CHECK(trim(id) <> ''),
    target_habit_uuid TEXT NOT NULL CHECK(trim(target_habit_uuid) <> ''),
    source_type TEXT NOT NULL CHECK(source_type IN ('FOCUS_SESSION')),
    source_id TEXT NOT NULL CHECK(trim(source_id) <> ''),
    contributed_value INTEGER NOT NULL CHECK(contributed_value >= 0),
    effective_timestamp INTEGER NOT NULL CHECK(effective_timestamp >= 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
    removed_at INTEGER CHECK(removed_at IS NULL OR removed_at >= created_at),
    source_rule_id TEXT CHECK(source_rule_id IS NULL OR trim(source_rule_id) <> ''),
    UNIQUE (source_type, source_id, target_habit_uuid)
);

CREATE INDEX IF NOT EXISTS idx_contributions_habit ON Contributions(target_habit_uuid, effective_timestamp, id);
CREATE INDEX IF NOT EXISTS idx_contributions_source ON Contributions(source_type, source_id, id);
CREATE INDEX IF NOT EXISTS idx_contributions_rule ON Contributions(source_rule_id);

CREATE TABLE IF NOT EXISTS SourceRules (
    id TEXT PRIMARY KEY NOT NULL CHECK(trim(id) <> ''),
    source_kind TEXT NOT NULL CHECK(source_kind IN ('FOCUS_SESSION')),
    source_container_id TEXT NOT NULL CHECK(trim(source_container_id) <> ''),
    include_descendants INTEGER NOT NULL CHECK(include_descendants IN (0, 1)),
    target_habit_uuid TEXT NOT NULL CHECK(trim(target_habit_uuid) <> ''),
    value_mapping TEXT NOT NULL CHECK(value_mapping IN ('FOCUS_DURATION')),
    is_enabled INTEGER NOT NULL CHECK(is_enabled IN (0, 1)),
    effective_from INTEGER NOT NULL CHECK(effective_from >= 0),
    created_at INTEGER NOT NULL CHECK(created_at >= 0),
    updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
    removed_at INTEGER CHECK(removed_at IS NULL OR removed_at >= created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_source_rules_active_scope ON SourceRules(source_kind, source_container_id, target_habit_uuid) WHERE is_enabled = 1 AND removed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_source_rules_target ON SourceRules(target_habit_uuid, id);
CREATE INDEX IF NOT EXISTS idx_source_rules_container ON SourceRules(source_container_id, id);
CREATE INDEX IF NOT EXISTS idx_source_rules_active ON SourceRules(is_enabled, removed_at);
