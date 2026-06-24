alter table Habits add column updated_at integer not null default 0;
alter table Habits add column deleted_at integer;

update Habits
set uuid = lower(hex(randomblob(16)))
where uuid is null or trim(uuid) = '';

update Habits
set updated_at = strftime('%s', 'now') * 1000
where updated_at = 0;

create unique index idx_habits_uuid on Habits(uuid);

alter table Repetitions add column uuid text;
alter table Repetitions add column updated_at integer not null default 0;
alter table Repetitions add column deleted_at integer;

update Repetitions
set uuid = (
    select Habits.uuid || ':' || Repetitions.timestamp
    from Habits
    where Habits.id = Repetitions.habit
)
where uuid is null or trim(uuid) = '';

update Repetitions
set updated_at = timestamp
where updated_at = 0;

create unique index idx_repetitions_uuid on Repetitions(uuid);
create index idx_repetitions_habit_deleted on Repetitions(habit, deleted_at, timestamp desc);

alter table HabitGoals add column uuid text;
alter table HabitGoals add column updated_at integer not null default 0;
alter table HabitGoals add column deleted_at integer;

update HabitGoals
set uuid = (
    select Habits.uuid || ':goal:' || HabitGoals.effective_timestamp
    from Habits
    where Habits.id = HabitGoals.habit_id
)
where uuid is null or trim(uuid) = '';

update HabitGoals
set updated_at = effective_timestamp
where updated_at = 0;

create unique index idx_habit_goals_uuid on HabitGoals(uuid);
create index idx_habit_goals_active on HabitGoals(habit_id, deleted_at, effective_timestamp desc);

alter table HabitBlocks add column uuid text;
alter table HabitBlocks add column updated_at integer not null default 0;
alter table HabitBlocks add column deleted_at integer;

update HabitBlocks
set uuid = lower(hex(randomblob(16)))
where uuid is null or trim(uuid) = '';

update HabitBlocks
set updated_at = strftime('%s', 'now') * 1000
where updated_at = 0;

create unique index idx_habit_blocks_uuid on HabitBlocks(uuid);
create index idx_habit_blocks_active on HabitBlocks(deleted_at, position);

create table SyncQueue (
    queue_id integer primary key autoincrement,
    op_uuid text not null unique,
    entity_type text not null,
    entity_uuid text not null,
    operation_type text not null,
    payload_json text not null,
    created_at integer not null,
    device_id text not null,
    pushed_at integer,
    failed_at integer,
    failure_reason text
);

create index idx_sync_queue_pending on SyncQueue(pushed_at, queue_id);

create table EntryOps (
    op_uuid text primary key,
    habit_uuid text not null,
    entry_timestamp integer not null,
    delta_value integer not null,
    op_type text not null,
    notes text,
    device_id text not null,
    created_at integer not null,
    deleted_at integer
);

create index idx_entry_ops_habit_timestamp on EntryOps(habit_uuid, entry_timestamp, deleted_at, created_at);
