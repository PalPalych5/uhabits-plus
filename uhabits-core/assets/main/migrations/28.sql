create table HabitGoals (
    id integer primary key autoincrement,
    habit_id integer not null references Habits(id) on delete cascade,
    effective_timestamp integer not null,
    freq_num integer not null,
    freq_den integer not null,
    target_type integer not null,
    target_value real not null,
    unit text not null default ''
);

create unique index idx_habit_goals_unique_effective
    on HabitGoals(habit_id, effective_timestamp);

create index idx_habit_goals_habit_effective
    on HabitGoals(habit_id, effective_timestamp desc);

alter table HabitExtensions add column stats_start_timestamp integer;

create table AppSettings (
    key text primary key,
    long_value integer
);

insert into HabitGoals (
    habit_id,
    effective_timestamp,
    freq_num,
    freq_den,
    target_type,
    target_value,
    unit
)
select
    id,
    0,
    freq_num,
    freq_den,
    target_type,
    target_value,
    coalesce(unit, '')
from Habits;
