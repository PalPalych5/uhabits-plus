# UHabit Next — продуктовое и архитектурное ТЗ

Статус: архитектурное направление и основа последующей разработки.

Это не задача «реализовать всё сразу». Документ определяет направление продукта, доменные границы, UX-принципы, инварианты данных и последовательность развития.

Перед любыми крупными изменениями изучить текущий репозиторий и предыдущие исследования:

- `container-redesign-research-2026-10-03.md`
- `container-code-audit-2026-10-03.md`
- `container-commercial-analogues-2026-10-03.md`
- `container-open-source-analogues-2026-10-03.md`
- предыдущие исследования Tasks / Super Productivity, если доступны.

Не считать решения из этих документов абсолютными. Если код или реальные ограничения противоречат предложению, зафиксировать это и предложить более безопасный вариант.

---

## 1. Что мы строим

UHabit больше не должен развиваться как:

> habit tracker, в который постепенно добавляются задачи, Pomodoro и другие функции.

Целевая модель:

> **личная система планирования и фактической деятельности, в которой проекты/контексты, задачи, привычки и реально выполненная работа существуют в единой организационной структуре и связаны между собой без двойного ввода данных.**

Приложение должно отвечать на несколько разных вопросов:

```text
Где это относится?          → Container
Что нужно закончить?        → Task
Что нужно делать регулярно? → Habit
Что я реально делал?        → FocusSession / WorkFact
Как это влияет на норму?    → Contribution / SourceRule
Что делать сегодня?         → DayPlan / Today
Что происходило?            → Statistics / History
```

Эти понятия связаны, но НЕ должны становиться одной универсальной сущностью.

---

## 2. Главная продуктовая философия

### 2.1. Одно реальное действие регистрируется один раз

Пользователь не должен:

1. запустить таймер;
2. затем отдельно отметить задачу;
3. затем вручную записать 42 минуты в привычку;
4. затем вручную обновить прогресс проекта.

Пример:

```text
Учёба
└── Базы данных
    └── ЛР №2
        └── Task: Сделать запросы
```

Пользователь 42 минуты работает над Task.

Создаётся:

```text
FocusSession = 42 min
Task work = 42 min
```

Поскольку Task находится внутри:

```text
Учёба → Базы данных → ЛР №2
```

эта работа должна быть доступна статистике всех соответствующих Container.

Если существует явно настроенная Habit:

```text
Учёба ≥ 600 минут / неделя
```

и её source rule включает работу внутри `Учёба`, эта же FocusSession может дать:

```text
Contribution = +42 min
source = FocusSession UUID
```

Нельзя создавать второй независимый факт «42 минуты», если источником является та же FocusSession.

---

## 3. Не копировать конкурентов, а улучшать связанный сценарий

Наличие следующих функций НЕ является самостоятельным преимуществом:

```text
Tasks
Habits
Pomodoro
Projects
Subprojects
Planned date
Deadline
Recurring tasks
Time tracking
Statistics
Weekly/monthly goals
```

Эти механики уже существуют у Amazing Marvin, TickTick, Super Productivity, Todoist, OpenHabitTracker, Task Coach и других продуктов.

Они являются table stakes.

Ценность UHabit должна появляться в качестве их взаимодействия.

Приоритетные отличия:

```text
один факт → несколько корректных представлений
source-specific undo
исторически стабильная статистика
история изменения нормы
отдельные plan и deadline
богатые numerical habits
понятное происхождение каждого автоматического значения
простота Android UX несмотря на мощную внутреннюю модель
local-first / полноценный backup
```

Не утверждать уникальность функции без отдельного доказательства.

---

## 4. Container — новая организационная основа

Ввести отдельный домен `Container`.

Рабочее внутреннее название:

```text
Container
```

Пользовательское название пока НЕ фиксируется окончательно.

В интерфейсе допустимо использовать:

```text
Проект
Раздел
Пространство
```

или другое понятное слово.

### 4.1. Главное свойство

Container может содержать другие Container.

```text
Учёба
├── 4 курс
│   ├── Базы данных
│   │   ├── Лабораторные
│   │   │   └── ЛР №2
│   │   └── Теория
│   └── МУСы
└── ВКР
```

Также:

```text
Тело
├── Силовые
├── Бег
│   └── Полумарафон
└── Осанка
```

`Учёба`, `Базы данных`, `ЛР №2`, `Тело` и `Полумарафон` на уровне хранения могут быть одинаковыми Container.

Не вводить обязательные системные типы:

```text
AREA
PROJECT
FOLDER
LIST
```

только ради названия уровня.

Пользователь сам задаёт смысл узла.

---

## 5. Tree, а не Graph

Для основной организации использовать:

```text
Container.parentId
```

У одного Container один основной parent.

Task и Habit также имеют один primary Container.

Полноценный DAG / multiple parents сейчас НЕ реализовывать.

Разделять три разных понятия:

```text
1. Primary location
   Где объект находится.

2. Secondary relation
   С какими темами/объектами он связан.

3. Contribution
   Куда засчитывается фактический результат.
```

Пример:

```text
Task:
Сделать ML-модель для ВКР

Primary:
Учёба → ВКР

Secondary:
ML

Contribution:
Учёба ≥ 600 мин/week
```

Не превращать secondary relation во второй parent.

Позже при доказанной необходимости можно добавить:

```text
Tags
Secondary links
Aliases / shortcuts
```

Но primary tree должен оставаться однозначным.

---

## 6. Container не является универсальным Node

НЕ создавать огромную сущность:

```text
Node {
    type = TASK | HABIT | CONTAINER | ...
    dozens_of_nullable_fields
}
```

Предпочтительная логическая модель:

```text
Container
Task
Habit
FocusSession
HabitGoal
Contribution
SourceRule
DayPlan
```

каждая со своим жизненным циклом.

Container отвечает на вопрос:

> где это организовано?

Он не обязан иметь:

```text
completionPercent
deadline
habitTarget
streak
taskStatus
```

---

## 7. Habit необходимо сохранить как отдельный сильный домен

Не превращать Habit в recurring Task.

Сохранить сильные свойства текущего uhabits-plus:

```text
boolean habits
numerical habits
UNKNOWN
SKIP
explicit zero
AT_LEAST
AT_MOST
units
weekly/monthly quantities
HabitGoal history
effectiveDate
score/streak
manual entries
notes
statistics
```

Пример:

```text
Учёба ≥ 600 минут / неделя
```

означает именно количественную недельную цель:

```text
100 + 180 + 320 = 600
```

а не:

```text
30 минут минимум 5 дней
```

Это разные модели.

---

## 8. Task — отдельный новый домен

Task отвечает на вопрос:

> какой конкретный результат нужно получить?

Минимальная модель должна предусматривать:

```text
stable UUID
title
notes
status
primary container
planned date/time
deadline
createdAt
updatedAt
completedAt
cancelledAt
archived/deleted semantics
```

Позже:

```text
checklist
subtasks
recurrence
TaskSeries
TaskOccurrence
reminders
estimates
```

Не использовать существующий технический `core.tasks.Task` как пользовательскую Task.

Рабочее имя новой сущности может быть `TaskItem`, если необходимо избежать конфликта.

---

## 9. Planned date НЕ равен Deadline

Это обязательный продуктовый invariant.

Пример:

```text
Сделать отчёт

Planned:
Tuesday

Deadline:
Friday 18:00
```

Если Tuesday закончился:

```text
План требует пересмотра.
```

Но Task ещё НЕ overdue.

Только после Friday 18:00:

```text
Deadline overdue.
```

Перенос planned date НЕ переносит deadline.

Day mode / MINIMUM также не должен скрывать настоящий deadline.

---

## 10. FocusSession — самостоятельный факт

Текущий таймер не должен в долгосрочной архитектуре просто писать минуты напрямую в сегодняшнюю Habit Entry.

Ввести самостоятельный факт работы.

Концептуально:

```text
FocusSession
id
startedAt
endedAt
duration
taskId?
habitId?
containerId?
source/device metadata
createdAt
updatedAt
revision/version
```

FocusSession означает:

> пользователь реально зарегистрировал этот интервал работы.

FocusSession сама по себе НЕ означает:

```text
Task completed
Habit completed
Goal completed
```

Это отдельный факт.

Task может иметь несколько FocusSession.

---

## 11. Contribution и provenance

Автоматический прогресс должен быть объяснимым.

Каждый автоматически полученный вклад должен иметь источник.

Пример:

```text
Contribution
habitId = Study
value = 42 min
sourceType = FocusSession
sourceId = UUID-123
ruleId = UUID-ABC
```

Главный invariant:

> один исходный факт не должен случайно учитываться дважды в одной и той же цели.

Например, если FocusSession одновременно:

```text
явно связана с Habit
```

и

```text
попала под subtree SourceRule
```

система должна дедуплицировать вклад согласно определённой политике.

`Contribution` желательно рассматривать как внутренний/derived слой.

В MVP НЕ создавать для пользователя универсальный редактор автоматизаций вроде:

```text
IF Task.name contains X
AND project ...
THEN +Y ...
```

---

## 12. SourceRule

Для первой версии нужен очень небольшой набор явных правил.

Главный сценарий:

```text
Habit:
Учёба ≥ 600 min/week

SourceRule:
include all valid FocusSessions
inside subtree "Учёба"
```

Дополнительно возможно:

```text
include explicit Task
exclude Task
exclude FocusSession
```

Но MVP должен проверять прежде всего один понятный сценарий.

Пользователь должен иметь возможность открыть прогресс:

```text
420 / 600 мин
```

и увидеть:

```text
БД                    170
МУСы                  110
ВКР                   140
--------------------------
Total                 420
```

а затем провалиться до исходных сессий.

Никакой скрытой магии без возможности объяснения.

---

## 13. Исправление факта и Undo

Undo должен работать на уровне источника.

Если было:

```text
FocusSession = 42 min
```

и пользователь исправил:

```text
42 → 30
```

система должна получить:

```text
Task work      42 → 30
Container time 42 → 30
Habit progress 42 → 30
```

Ручная Habit Entry:

```text
+20 min
```

не должна измениться.

Не создавать независимые конкурирующие истины:

```text
FocusSession = 30
HabitContribution = editable 42
Entry = editable 60
```

Нужен понятный source of truth и derived projections.

---

## 14. Historical attribution

Перемещение объекта сегодня не должно незаметно переписывать историю прошлого.

Пример:

1 сентября:

```text
Task находится:
Учёба → БД
```

FocusSession:

```text
42 min
```

1 октября Task переместили:

```text
Личное → Архив
```

Исторический отчёт за сентябрь не должен автоматически говорить:

```text
Личное +42
```

только потому, что сегодня объект находится там.

Необходимо спроектировать различие:

```text
current organization
historical attribution
```

Точное техническое решение выбрать после анализа:

```text
placement history
effective-dated relation
fact context snapshot
stable path attribution
```

Но invariant обязателен.

---

## 15. Статистика Container

Container должен иметь возможность показать статистику собственного поддерева.

Пример:

```text
Учёба
├── БД     7h20
├── МУСы   5h40
└── ВКР    8h10

TOTAL     21h10
```

Допустимые независимые метрики:

```text
actual focus time
number of open tasks
completed tasks
overdue deadlines
Habit goal status
Habit contribution totals
recent activity
```

Не создавать универсальный показатель:

```text
Productivity = 83%
```

путём смешивания:

```text
minutes
checkboxes
task counts
habit percentages
streaks
```

без строгой математической модели.

---

## 16. DayTier / MINIMUM / NORMAL / IDEAL

Существующую систему не удалять.

Но не превращать Tier в уровень Container.

Концептуально это:

```text
PlanTier
```

или другая модель добровольной нагрузки.

Пример:

```text
Сегодня MINIMUM

Anki      10 min
МУСы      20 min
Разминка   5 min
```

При этом:

```text
Сдать отчёт сегодня 18:00
```

остаётся обязательством независимо от MINIMUM.

Также DayTier не должен автоматически менять:

```text
HabitGoal 600 min/week
```

Можно показать одновременно:

```text
Минимальный план на сегодня выполнен.
Недельная цель: 420 / 600 мин.
```

---

## 17. UX: нельзя просто добавить ещё одну вкладку Tasks

До реализации крупного UI провести отдельное проектирование Information Architecture.

Главный UX-вопрос:

> как естественно показать Container, внутри которого одновременно находятся другие Container, Tasks, Habits и фактическая работа?

Не считать правильным заранее ни один вариант.

Нужно спроектировать минимум 3 варианта и сравнить их.

### Вариант-кандидат A — Today first

Предпочтительное исходное направление:

```text
Today
Browse
Habits / Progress
```

или близкая структура.

Главный принцип:

> повседневное использование не требует путешествовать по дереву.

Today показывает глобально:

```text
реальные deadlines
выбранные Task
Habit / quotas
активный Focus
```

Quick Add Task:

```text
title → save
```

Container/date необязательны.

Если Container не указан:

```text
Inbox
```

---

## 18. Browse / Container screen

Container должен открываться как рабочий контекст, а не как обычная пустая папка.

Пример:

```text
Учёба / Базы данных

[Overview] [Tasks] [Habits] [Stats]

Подразделы
────────────────
Лабораторные
Теория

Задачи
────────────────
□ Сделать ЛР2
□ Подготовиться к защите

Привычки / нормы
────────────────
Учёба       420 / 600 min
БД          2 / 3 sessions

Недавняя работа
────────────────
ЛР2                    42 min
Теория                 25 min
```

Это только направление прототипа.

Не считать tabs обязательными.

Проверить альтернативы:

```text
mixed feed
sections
segmented views
tabs
context dashboard
```

Task и Habit должны быть визуально различимы.

Не использовать один одинаковый checkbox так, чтобы пользователь перестал понимать разницу между:

```text
одноразовым результатом
```

и

```text
регулярной метрикой
```

---

## 19. Навигация глубокой структуры

Модель данных должна переживать как минимум 6 уровней.

Но UX не должен заставлять пользователя ежедневно работать на такой глубине.

При глубоком пути использовать:

```text
Учёба / … / БД / ЛР2
```

Breadcrumb должен позволять открыть всех предков.

Нужны:

```text
search
favorites
recent containers
quick move
quick capture
```

Не делать всё дерево постоянно раскрытым с огромным indentation.

Предпочтительно:

> локальная страница текущего Container + его дети.

Полный outline — вторичный режим.

---

## 20. Global Tasks и Global Habits

Container не должен уничтожить глобальные представления.

Пользователь должен иметь возможность увидеть:

```text
все задачи Today
все Upcoming
Inbox
все overdue
completed
```

без обхода дерева.

Аналогично существующий быстрый global Habits workflow должен сохраниться хотя бы на переходном этапе.

Tree — способ организации.

Global views — способ действия.

---

## 21. Миграция от HabitBlock

НЕ переименовывать существующую таблицу HabitBlock в Container напрямую.

Предпочтительное направление:

```text
new Containers
+
LegacyBlockMap
+
new placement layer
```

Старые сферы становятся начальными root-level Container.

Например:

```text
Учёба
Речь
Тело
Уход
Режим
Самоконтроль
Прочее
```

Пользователь затем может:

```text
переименовать
переместить
создать вложенность
архивировать
```

Не считать эти семь узлов системными навсегда.

Во время перехода сохранить старые HabitBlock/HabitExtensions до безопасного завершения миграции.

Не делать бесконечный dual-write между двумя моделями.

Нужно определить один future source of truth и явный migration boundary.

---

## 22. Sync

НЕ добавлять новые entity types в существующий sync без protocol/version gate.

Старый клиент не должен:

```text
получить неизвестный event
проигнорировать его
продвинуть cursor
потерять данные
```

До проектирования новой версии sync:

```text
Container
Task
FocusSession
Contribution
```

работают в local experimental / feature-flagged режиме.

Не пытаться протащить новые факты через старую EntryOp модель так, чтобы возникал двойной учёт.

---

## 23. Backup

Перед миграцией существующей production DB:

```text
создать полный recovery snapshot
```

Не считать CSV полным backup.

Нужно сохранить:

```text
database
UUID
HabitGoal history
UNKNOWN/SKIP/0
notes
archived/tombstones
relevant preferences
active timer state
sync metadata
```

Миграционный тест проводится на копии.

Исходная база не должна повреждаться.

---

## 24. Лицензия и происхождение

Проект остаётся производным от Loop Habit Tracker.

Не удалять copyright notices и GPL licensing information.

Новые изменения должны оставаться совместимыми с действующей лицензией проекта.

Не проводить массовый rename namespaces/package только ради ощущения «нового приложения», если это не имеет технической причины.

Брендинг продукта может развиваться отдельно.

---

## 25. Что НЕ делать сейчас

Не реализовывать на первом этапе:

```text
full graph / DAG
multiple primary parents
team collaboration
Jira/GitLab integrations
Gantt
desktop client
web client
AI planning
plugin marketplace
custom rules language
universal productivity score
Habit→Habit arbitrary automation
calendar sync
полный recurrence engine
massive UI rewrite
полный rewrite проекта
fork Super Productivity
```

Это не означает «никогда».

Это означает:

> эти возможности не нужны для проверки главной гипотезы.

---

## 26. Архитектурный принцип разработки

Не переписывать существующий Habit-domain без необходимости.

Развивать новые возможности рядом:

```text
existing Habit domain
        │
        ├── new Container domain
        ├── new Task domain
        ├── new FocusSession domain
        └── new attribution/reporting layer
```

Старый код постепенно перестаёт быть центром приложения, но не уничтожается одним rewrite.

---

## 27. Первый этап разработки — Foundation

Первый инженерный этап НЕ должен пытаться сделать полноценный новый UHabit.

Нужно реализовать только foundation.

До изменения production-кода подготовить:

```text
1. Architecture Decision Record
2. окончательную схему сущностей первого этапа
3. migration design
4. UI wireframe / text mockup минимум 3 вариантов
5. список затрагиваемых существующих компонентов
6. перечень рисков
```

После этого реализовать только безопасный Container foundation.

Минимальный scope:

```text
Container model
Container repository
SQLite tables/indexes
stable UUID
parent relation
cycle protection
ordering
archive semantics
tree queries
LegacyBlockMap
Habit → Container placement
migration from current blocks
unit tests
backup/restore awareness
```

UI первого этапа может быть минимальным и feature-flagged.

Не добавлять полноценные Tasks одновременно, если foundation ещё не стабилен.

---

## 28. Второй этап — Browse prototype

После Container foundation создать минимальный новый Browse.

Он должен позволять:

```text
видеть root containers
создать child
переместить container
открыть container
видеть children
видеть находящиеся здесь Habits
видеть полный breadcrumb
искать container
```

Не требуется красивый финальный UI.

Главная цель:

> проверить модель навигации и вложенности.

Также подготовить 2–3 альтернативных макета Container Detail, где позже вместе будут Tasks и Habits.

Не начинать большую UI-полировку до выбора модели.

---

## 29. Третий этап — Task MVP

После стабилизации Container добавить пользовательский Task-domain.

Минимальный vertical slice:

```text
Inbox
Create Task
Task title
Task notes
primary Container
planned date
deadline
complete
undo complete
cancel
history
Today
Upcoming
```

Без полноценного recurrence.

Task должна существовать независимо от Habit.

---

## 30. Четвёртый этап — FocusSession

Перевести таймер на самостоятельные work facts.

Первый обязательный сценарий:

```text
Учёба
└── БД
    └── ЛР1
        └── Task "Сделать ЛР1"
```

Habit:

```text
Учёба ≥ 300 min/week
```

Focus:

```text
42 min on Task
```

Ожидаемый результат:

```text
Task actual work    42
ЛР1 subtree         42
БД subtree          42
Учёба subtree       42
Habit Study         +42
```

но хранится один исходный факт работы.

---

## 31. Пятый этап — provenance / Contribution

Добавить один понятный SourceRule:

```text
all valid FocusSessions
inside subtree X
→ Habit Y
```

Показать breakdown.

Проверить:

```text
manual Habit Entry + automatic contribution
undo
edit 42→30
moving Task
moving Container
exclude source
rebuild projections
```

Ни один сценарий не должен удваивать данные.

---

## 32. Шестой этап — Container statistics

После появления реальных Task и Focus данных создать отдельный ContainerReport.

Не пытаться расширить существующий habit StatisticsReport простым добавлением Task.

Новый read model должен отдельно возвращать:

```text
time
task outcomes
deadlines
Habit evaluations
source breakdown
activity
```

UI решает, какие 2–3 метрики релевантны конкретному экрану.

---

## 33. Критические acceptance scenarios

Перед расширением scope система должна пройти как минимум следующие сценарии:

```text
1. Container внутри Container глубиной 6.
2. Попытка создать cycle запрещена.
3. Habit переезжает в другой Container без потери UUID/history.
4. Task создаётся одним title во Inbox.
5. Planned Tuesday + Deadline Friday не становится overdue в Wednesday.
6. FocusSession 42 min survives process restart/finalization safely.
7. FocusSession 42 даёт ровно один +42 contribution.
8. Совпадение explicit rule + subtree rule не даёт +84.
9. 42 → 30 корректирует все derived totals.
10. Manual Habit +20 остаётся после undo FocusSession.
11. Move Task today не переписывает historical September attribution.
12. HabitGoal 300→420 с effectiveDate сохраняет старую оценку.
13. UNKNOWN, SKIP и zero остаются различными.
14. Archive Container не уничтожает historical facts.
15. Delete имеет явную политику для children.
16. Full backup/restore воспроизводит значения.
17. Старый sync protocol не проглатывает новые entities.
18. Пользователь может добавить Task без открытия Browse.
19. Пользователь видит hard deadline независимо от глубины дерева.
20. Обычная Habit по-прежнему отмечается не сложнее, чем раньше.
```

---

## 34. Что считать успехом

Не количество новых функций.

Успех:

> пользователь один раз фиксирует реальное действие и затем понимает, что произошло, куда оно засчиталось и почему.

Пример:

```text
Сегодня я 42 минуты делал ЛР по БД.

UHabit показывает:
- работа над Task: 42 мин;
- БД: +42 мин;
- Учёба: +42 мин;
- недельная цель Учёба: 242 / 300;
- источник каждой цифры понятен;
- я ничего не отмечал второй раз.
```

При этом пользователь, которому нужны только простые Habits, не обязан:

```text
создавать дерево
создавать Tasks
создавать rules
изучать новую систему
```

---

## 35. Основной UX-принцип

**Powerful underneath, simple on the surface.**

Модель внутри может поддерживать:

```text
6-level tree
historical placement
source ledger
effective goals
revisions
derived statistics
```

Но ежедневный пользовательский сценарий должен выглядеть примерно так:

```text
Открыл Today.
Увидел, что важно.
Создал дело одной строкой.
Запустил работу.
Закончил работу.
Получил корректный прогресс автоматически.
```

Если архитектурная возможность делает этот сценарий заметно сложнее — она не должна становиться обязательной частью UI.

---

## 36. Инструкции Codex / Antigravity перед первой реализацией

При первом запуске с этим ТЗ НЕ начинай массово изменять код.

Сначала:

1. Изучи текущую архитектуру и перечисленные исследования.
2. Сопоставь требования ТЗ с существующими классами и БД.
3. Найди противоречия и скрытые риски.
4. Предложи конкретную domain model первого этапа.
5. Предложи SQL/schema design.
6. Предложи migration plan.
7. Предложи 3 варианта Information Architecture Android UI, особенно для вопроса:
   **как внутри одного Container естественно показать Tasks + Habits + child Containers + Statistics.**
8. Выбери рекомендуемый вариант и объясни компромиссы.
9. Разбей Foundation на небольшие независимые изменения/PR.
10. Только после этого приступай к реализации согласованного первого этапа.

Не реализовывай сразу Tasks, FocusSession, Contributions, новый Today и полную статистику одним большим изменением.

Для первого coding iteration цель:

> **создать безопасный фундамент Container без разрушения текущего Habit-приложения.**

После завершения этапа представить:

```text
что изменено
какие инварианты реализованы
какие тесты добавлены
какие старые функции затронуты
какие риски остались
какой следующий минимальный этап рекомендуется
```

Не считать красивый UI доказательством правильности архитектуры.

Не считать успешную компиляцию доказательством корректной миграции.

Не терять пользовательские данные ради упрощения реализации.
