create table HabitExtensions (
    habit_id integer primary key,
    day_tier text not null default 'NORMAL'
        check (day_tier in ('MINIMUM', 'NORMAL', 'IDEAL', 'OPTIONAL')),
    timer_enabled integer not null default 0
        check (timer_enabled in (0, 1)),
    foreign key (habit_id) references Habits(id) on delete cascade
);

insert into HabitExtensions (habit_id, day_tier, timer_enabled)
select id,
       'NORMAL',
       case
           when type = 1 and lower(trim(unit)) in
               ('min', 'mins', 'minute', 'minutes', 'мин', 'минута', 'минуты', 'минут')
           then 1
           else 0
       end
from Habits;
