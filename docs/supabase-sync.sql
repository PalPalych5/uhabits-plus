-- Phase 3 Prompt 2 remote schema for uhabits-plus.
-- Clients push immutable sync_log rows. A trigger projects those rows into
-- canonical tables. Clients pull from sync_log by log_id and keep SQLite as the
-- UI source of truth.

create extension if not exists pgcrypto;

create table if not exists public.devices (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id text not null,
    last_seen_at bigint not null default 0,
    created_at timestamptz not null default now(),
    primary key (user_id, device_id)
);

create table if not exists public.habit_blocks (
    id bigserial primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    uuid text not null,
    name text not null,
    color integer not null,
    icon text,
    position integer not null default 0,
    is_archived boolean not null default false,
    updated_at bigint not null,
    deleted_at bigint,
    unique (user_id, uuid)
);

create table if not exists public.habits (
    id bigserial primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    uuid text not null,
    name text not null,
    description text not null default '',
    question text not null default '',
    freq_num integer not null default 1,
    freq_den integer not null default 1,
    color integer not null default 0,
    position integer not null default 0,
    reminder_hour integer,
    reminder_min integer,
    reminder_days integer not null default 0,
    archived boolean not null default false,
    type integer not null default 0,
    target_type integer not null default 0,
    target_value double precision not null default 0,
    unit text not null default '',
    day_tier text not null default 'NORMAL',
    timer_enabled boolean not null default false,
    block_uuid text,
    statistics_start bigint,
    updated_at bigint not null,
    deleted_at bigint,
    unique (user_id, uuid)
);

create table if not exists public.habit_goals (
    id bigserial primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    uuid text not null,
    habit_uuid text not null,
    effective_timestamp bigint not null,
    freq_num integer not null,
    freq_den integer not null,
    target_type integer not null,
    target_value double precision not null,
    unit text not null default '',
    updated_at bigint not null,
    deleted_at bigint,
    unique (user_id, uuid)
);

create table if not exists public.entries (
    id bigserial primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    uuid text not null,
    habit_uuid text not null,
    entry_date bigint not null,
    value integer not null,
    notes text not null default '',
    updated_at bigint not null,
    deleted_at bigint,
    unique (user_id, uuid),
    unique (user_id, habit_uuid, entry_date)
);

create table if not exists public.entry_ops (
    id bigserial primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    op_uuid text not null,
    habit_uuid text not null,
    entry_date bigint not null,
    delta_value integer not null,
    op_type text not null,
    notes text,
    device_id text not null,
    created_at bigint not null,
    deleted_at bigint,
    unique (user_id, op_uuid)
);

create table if not exists public.app_settings_sync (
    id bigserial primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    setting_key text not null,
    long_value bigint,
    updated_at bigint not null,
    deleted_at bigint,
    unique (user_id, setting_key)
);

create table if not exists public.sync_log (
    log_id bigserial primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id text not null,
    op_uuid text not null,
    entity_type text not null,
    entity_uuid text not null,
    operation_type text not null,
    payload_json jsonb not null default '{}'::jsonb,
    created_at bigint not null,
    server_created_at timestamptz not null default now(),
    unique (user_id, op_uuid)
);

create index if not exists idx_sync_log_user_log_id on public.sync_log(user_id, log_id);

alter table public.devices enable row level security;
alter table public.habit_blocks enable row level security;
alter table public.habits enable row level security;
alter table public.habit_goals enable row level security;
alter table public.entries enable row level security;
alter table public.entry_ops enable row level security;
alter table public.app_settings_sync enable row level security;
alter table public.sync_log enable row level security;

drop policy if exists "own rows" on public.devices;
create policy "own rows" on public.devices for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "own rows" on public.habit_blocks;
create policy "own rows" on public.habit_blocks for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "own rows" on public.habits;
create policy "own rows" on public.habits for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "own rows" on public.habit_goals;
create policy "own rows" on public.habit_goals for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "own rows" on public.entries;
create policy "own rows" on public.entries for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "own rows" on public.entry_ops;
create policy "own rows" on public.entry_ops for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "own rows" on public.app_settings_sync;
create policy "own rows" on public.app_settings_sync for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "own rows" on public.sync_log;
create policy "own rows" on public.sync_log for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create or replace function public.apply_sync_log_entry()
returns trigger
language plpgsql
as $$
declare
    payload jsonb := new.payload_json;
begin
    if new.entity_type = 'habit_block' then
        insert into public.habit_blocks (
            user_id, uuid, name, color, position, is_archived, updated_at, deleted_at
        )
        values (
            new.user_id,
            new.entity_uuid,
            coalesce(payload->>'name', ''),
            coalesce((payload->>'color')::integer, 0),
            coalesce((payload->>'position')::integer, 0),
            coalesce((payload->>'is_archived')::boolean, false),
            new.created_at,
            case when new.operation_type = 'delete' then new.created_at else null end
        )
        on conflict (user_id, uuid) do update
        set name = excluded.name,
            color = excluded.color,
            position = excluded.position,
            is_archived = excluded.is_archived,
            updated_at = greatest(public.habit_blocks.updated_at, excluded.updated_at),
            deleted_at = case
                when excluded.deleted_at is not null then excluded.deleted_at
                when public.habit_blocks.deleted_at is not null and public.habit_blocks.deleted_at > excluded.updated_at then public.habit_blocks.deleted_at
                else null
            end;
    elsif new.entity_type = 'habit' then
        insert into public.habits (
            user_id, uuid, name, description, question, freq_num, freq_den, color, archived, type,
            target_type, target_value, unit, day_tier, timer_enabled, block_uuid, statistics_start,
            reminder_hour, reminder_min, reminder_days, updated_at, deleted_at
        )
        values (
            new.user_id,
            new.entity_uuid,
            coalesce(payload->>'name', ''),
            coalesce(payload->>'description', ''),
            coalesce(payload->>'question', ''),
            coalesce((payload->>'freq_num')::integer, 1),
            coalesce((payload->>'freq_den')::integer, 1),
            coalesce((payload->>'color')::integer, 0),
            coalesce((payload->>'archived')::boolean, false),
            coalesce((payload->>'type')::integer, 0),
            coalesce((payload->>'target_type')::integer, 0),
            coalesce((payload->>'target_value')::double precision, 0),
            coalesce(payload->>'unit', ''),
            coalesce(payload->>'day_tier', 'NORMAL'),
            coalesce((payload->>'timer_enabled')::boolean, false),
            nullif(payload->>'block_uuid', ''),
            nullif(payload->>'statistics_start', '')::bigint,
            nullif(payload->>'reminder_hour', '')::integer,
            nullif(payload->>'reminder_min', '')::integer,
            coalesce((payload->>'reminder_days')::integer, 0),
            new.created_at,
            case when new.operation_type = 'delete' then new.created_at else null end
        )
        on conflict (user_id, uuid) do update
        set name = excluded.name,
            description = excluded.description,
            question = excluded.question,
            freq_num = excluded.freq_num,
            freq_den = excluded.freq_den,
            color = excluded.color,
            archived = excluded.archived,
            type = excluded.type,
            target_type = excluded.target_type,
            target_value = excluded.target_value,
            unit = excluded.unit,
            day_tier = excluded.day_tier,
            timer_enabled = excluded.timer_enabled,
            block_uuid = excluded.block_uuid,
            statistics_start = excluded.statistics_start,
            reminder_hour = excluded.reminder_hour,
            reminder_min = excluded.reminder_min,
            reminder_days = excluded.reminder_days,
            updated_at = greatest(public.habits.updated_at, excluded.updated_at),
            deleted_at = case
                when excluded.deleted_at is not null then excluded.deleted_at
                when public.habits.deleted_at is not null and public.habits.deleted_at > excluded.updated_at then public.habits.deleted_at
                else null
            end;
    elsif new.entity_type = 'habit_goal' then
        insert into public.habit_goals (
            user_id, uuid, habit_uuid, effective_timestamp, freq_num, freq_den,
            target_type, target_value, unit, updated_at, deleted_at
        )
        values (
            new.user_id,
            new.entity_uuid,
            payload->>'habit_uuid',
            (payload->>'effective_timestamp')::bigint,
            (payload->>'freq_num')::integer,
            (payload->>'freq_den')::integer,
            (payload->>'target_type')::integer,
            (payload->>'target_value')::double precision,
            coalesce(payload->>'unit', ''),
            new.created_at,
            case when new.operation_type = 'delete' then new.created_at else null end
        )
        on conflict (user_id, uuid) do update
        set freq_num = excluded.freq_num,
            freq_den = excluded.freq_den,
            target_type = excluded.target_type,
            target_value = excluded.target_value,
            unit = excluded.unit,
            updated_at = greatest(public.habit_goals.updated_at, excluded.updated_at),
            deleted_at = case
                when excluded.deleted_at is not null then excluded.deleted_at
                when public.habit_goals.deleted_at is not null and public.habit_goals.deleted_at > excluded.updated_at then public.habit_goals.deleted_at
                else null
            end;
    elsif new.entity_type = 'entry' then
        insert into public.entries (
            user_id, uuid, habit_uuid, entry_date, value, notes, updated_at, deleted_at
        )
        values (
            new.user_id,
            new.entity_uuid,
            payload->>'habit_uuid',
            (payload->>'entry_date')::bigint,
            case when new.operation_type = 'delete' then -1 else coalesce((payload->>'value')::integer, -1) end,
            coalesce(payload->>'notes', ''),
            new.created_at,
            case when new.operation_type = 'delete' then new.created_at else null end
        )
        on conflict (user_id, uuid) do update
        set value = excluded.value,
            notes = excluded.notes,
            updated_at = greatest(public.entries.updated_at, excluded.updated_at),
            deleted_at = case
                when excluded.deleted_at is not null then excluded.deleted_at
                when public.entries.deleted_at is not null and public.entries.deleted_at > excluded.updated_at then public.entries.deleted_at
                else null
            end;
    elsif new.entity_type = 'entry_op' then
        insert into public.entry_ops (
            user_id, op_uuid, habit_uuid, entry_date, delta_value, op_type, notes, device_id, created_at, deleted_at
        )
        values (
            new.user_id,
            new.entity_uuid,
            payload->>'habit_uuid',
            (payload->>'entry_date')::bigint,
            (payload->>'delta_value')::integer,
            payload->>'op_type',
            nullif(payload->>'notes', ''),
            new.device_id,
            new.created_at,
            null
        )
        on conflict (user_id, op_uuid) do nothing;
    elsif new.entity_type = 'app_setting' then
        insert into public.app_settings_sync (
            user_id, setting_key, long_value, updated_at, deleted_at
        )
        values (
            new.user_id,
            payload->>'key',
            nullif(payload->>'long_value', '')::bigint,
            new.created_at,
            case when new.operation_type = 'delete' then new.created_at else null end
        )
        on conflict (user_id, setting_key) do update
        set long_value = excluded.long_value,
            updated_at = greatest(public.app_settings_sync.updated_at, excluded.updated_at),
            deleted_at = excluded.deleted_at;
    end if;
    return new;
end
$$;

drop trigger if exists trg_apply_sync_log_entry on public.sync_log;
create trigger trg_apply_sync_log_entry
after insert on public.sync_log
for each row execute function public.apply_sync_log_entry();
