create table HabitBlocks (
    id integer primary key autoincrement,
    name text not null,
    color integer not null,
    icon text,
    position integer not null,
    is_archived integer not null default 0 check (is_archived in (0, 1))
);

-- Insert default blocks
insert into HabitBlocks (id, name, color, icon, position, is_archived) values
(1, 'Интеллект / обучение', 11, 'school', 0, 0),
(2, 'Речь / мышление', 13, 'record_voice_over', 1, 0),
(3, 'Тело', 6, 'directions_run', 2, 0),
(4, 'Уход / гигиена', 8, 'face', 3, 0),
(5, 'Режим / рефлексия', 3, 'wb_sunny', 4, 0),
(6, 'Ограничения / самоконтроль', 1, 'block', 5, 0),
(7, 'Прочее', 18, 'more_horiz', 6, 0);

alter table HabitExtensions add column block_id integer references HabitBlocks(id) on delete set null;

-- Associate existing habits to blocks based on color index
update HabitExtensions
set block_id = (
    select case
        when h.color in (0, 1, 15) then 6
        when h.color in (2, 3, 4) then 5
        when h.color in (5, 6, 7) then 3
        when h.color = 8 then 4
        when h.color in (9, 10, 11, 12) then 1
        when h.color in (13, 14) then 2
        else 7
    END
    from Habits h
    where h.id = HabitExtensions.habit_id
);
