# UHabit Next: архитектурная подготовка Foundation

Дата: 3 октября 2026. Проверенный HEAD: `e06da77293375ee4105cc14339c81d2699bb2c3e`. Рабочее дерево в начале проверки чистое. Это проект решения, а не реализованная миграция. Код, production-БД, настройки приложения, Supabase и Git index не изменялись. Документ сохранён вне репозитория.

## 1. Рекомендуемое решение

Добавить отдельный организационный домен **Container** в существующий `uhabits-core`: лес узлов с одним родителем, stable UUID, отдельное текущее размещение Habit, история организационных изменений начиная с перехода. Habit остаётся доменом измерения и регулярной нормы. Его цели, записи, вычисления, таймер и идентичность сохраняются.

Новые таблицы — `Containers`, `HabitPlacements`, `LegacyBlockMap`, две типизированные таблицы истории и небольшой журнал организационных транзакций. Старые `HabitBlocks` и поле `HabitExtensions.block_id` сохраняются как исходный migration snapshot. После cutover они больше не являются редактируемым источником размещения.

Foundation сначала реализуется и проверяется **на изолированной копии**, с отдельной DI-сессией и полностью заблокированным remote sync. `DATABASE_VERSION=29`, production opener и production-БД сохраняются. Новую production-версию схемы включать отдельным будущим решением после проверок. Feature flag сам по себе не изолирует данные.

Первый Android-прототип — **C, Evolutionary**: сохранить главный экран Habits, добавить экспериментальный Browse с локальными страницами Container. Today-first остаётся кандидатом для следующего этапа, когда существуют пользовательские Tasks и реальный Today.

### Чем это отличается от буквального следования исследованиям

1. Не запускать существующий `repairDefaultBlockUuids()` ради миграции: сохранить UUID фактически прочитанного блока. При локальном эксперименте remote reconciliation не требуется. Известные альтернативные старые идентификаторы сохранять отдельно, не менять первичный UUID Container.
2. Использовать лес с `parent=null` и виртуальные «Без раздела»/Inbox. Не создавать обязательный системный root, AREA/PROJECT discriminator или постоянные семь системных сфер.
3. Не вводить универсальную polymorphic Placement-таблицу под ещё отсутствующие Tasks: `HabitPlacements` имеет настоящую FK к `Habits`. Позже добавится отдельная Task-placement, если она нужна задачному домену.
4. Начать с adjacency list и итеративных обходов. Closure/materialized path — только после измерения. Нынешний Android prepared-statement adapter распознаёт query по `SELECT`/`PRAGMA`; произвольный `WITH RECURSIVE ... SELECT` потребует отдельной адаптации.
5. Delete непустого Container отклонять. Не начинать с автоматического переноса всей ветви при удалении.
6. Заложить историю parent edges **и** Habit placement, но не объявлять её происхождением старых Entry. История организации и история совершения действий — разные данные.
7. Не делать старые таблицы двусторонней compatibility projection. Старые читатели переключаются через явный bridge; старые записи размещения прекращаются.

## 2. Что реально существует

ТЗ `docs/UHabit_Next_Spec.md` прочитано целиком; рассмотрены все четыре указанных исследования. Их внешние выводы используются как предоставленный исследовательский контекст, без новой проверки конкурентов в интернете. Архитектурные факты ниже сверены с текущими исходниками. Graph-инструмент использован для поиска связей; окончательная проверка выполнена по файлам, поскольку отдельные размеры/связи индекса не совпадают с текущим текстом.

| Наблюдение в коде | Практическое следствие |
|---|---|
| `HabitBlock` содержит id/name/color/icon/position/isArchived, но не UUID и не parent | Миграция должна читать storage rows, а не список UI-моделей |
| `HabitBlockData` содержит UUID/updatedAt/deletedAt; `findAll()` скрывает tombstones | Для миграции нужна отдельная raw-выборка всех строк |
| Migration 27 создала семь блоков и первоначально назначала Habits по цвету | Новая миграция использует уже сохранённый block_id; повторная классификация по цвету недопустима |
| `HabitExtensions` хранит один текущий block_id и заменяется целиком через INSERT OR REPLACE | Новое placement нельзя добавлять только в эту legacy строку или извлекать из каждого обычного update Habit |
| `SQLiteHabitList.remove()` ставит Habit tombstone и удаляет extension | У ряда удалённых Habit текущая сфера уже невосстановима |
| `SQLiteHabitList.update()` пишет extension и вызывает replaceAll для goals | Migration не должна переносить Habit через обычное edit/update API: оно может переписать metadata/history |
| `removeAll()` физически удаляет Habit/Entry/Goal, но не является полным сбросом всех fork-таблиц | Этот путь требует отдельного ограничения в Container dataset |
| `ManageBlocksActivity` пишет repository напрямую; id 1–7 защищены от удаления | Нужны адаптация точки записи и снятие особой семантики id только в новом режиме |
| `EditHabitActivity` начинает с blockId=7; old edit command копирует Habit целиком | Новый контекст не должен теряться при обычной правке имени/цели |
| BY_SPHERE зависит от blockId в memory list и HabitCardListView; SkipDayDialog также группирует по blockId | Bridge нужен не только для editor, но и для сортировки и массового SKIP |
| Reports отбирают по текущему blockId и группируют прошлые результаты по нему | Это нынешняя классификация, а не достоверная историческая атрибуция |
| `StatisticsOverview.focusMinutes` берёт численные минутные AT_LEAST Entry | Это может быть ручное количество; нельзя назвать его будущим measured FocusSession time |
| `SyncCoordinator` заменяет UUID default blocks, допускает legacy adoption; unknown entity -> true | Старый sync не допускается к новому dataset |
| SyncManager capture pause не открывает transaction | Atomicity организационных команд требуется реализовать явно |
| `Database.migrateTo()` самостоятельно не начинает transaction | Staging runner должен явно обеспечивать atomicity, включая user_version |
| Backup validator знает таблицы до v29; restore мигрирует staging и заменяет файл | Новая схема требует version-aware semantic validator и собственного экспериментального restore-route |
| LoopDBImporter сопоставляет blocks также по имени/цвету/позиции; raw Habit/Goal/Entry выборки не везде фильтруют tombstones | Merge-import нельзя использовать как lossless full restore или перенос Container dataset |
| В просмотренном opener нет явного включения foreign_keys | Объявленная FK не доказывает её runtime enforcement; проверить и включить для экспериментального connection после inventory |

Есть расхождение основной архитектурной документации с кодом: она упоминает `StatisticsStabilityReportBuilder`, но текущий `StatisticsFragment` в файле `StatisticsActivity.kt` вызывает `StatisticsReportStateBuilder`. В плане используется реально существующий builder. В рамках read-only задачи документы репозитория не исправлялись.

### Основные проверенные точки

Все пути относительно `C:/Users/Pavel/source/repos/uhabits-plus/`:

- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/HabitBlock.kt:21`.
- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/database/HabitBlockRepository.kt:29`.
- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/database/HabitExtensionRepository.kt:5`.
- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/sqlite/SQLiteHabitList.kt:198`, `:265`, `:290`.
- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/io/LoopDBImporter.kt:83`, `:157`, `:207`, `:231`.
- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/ui/screens/statistics/StatisticsReportStateBuilder.kt:164`, `:473`.
- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/ui/screens/statistics/StatisticsOverview.kt:211`.
- `uhabits-android/src/main/java/org/isoron/uhabits/sync/SyncCoordinator.kt:1231`, `:1323`, `:1370`.
- `uhabits-android/src/main/java/org/isoron/uhabits/utils/DatabaseUtils.kt:68`, `:114`, `:196`.
- `uhabits-android/src/main/java/org/isoron/uhabits/database/AndroidDatabase.kt:33`.
- `uhabits-android/src/main/java/org/isoron/uhabits/inject/HabitsApplicationComponent.kt:97`, `:141`.

## 3. Domain model и слои

### 3.1. Минимальные классы

Ниже — проект контрактов, не добавленный Kotlin-код. Миллисекунды — UTC instants; дата habitual дня остаётся существующим `LocalDate`, её формат не меняется.

```kotlin
@JvmInline value class ContainerId(val value: String)
@JvmInline value class HabitRef(val uuid: String)
@JvmInline value class OrganizationRevision(val value: Long)

data class Container(
    val id: ContainerId,
    val parentId: ContainerId?,
    val name: String,
    val color: PaletteColor?,
    val icon: String?,
    val siblingOrder: Int,
    val isArchived: Boolean,
    val createdAt: Long?,       // для legacy дата создания неизвестна
    val updatedAt: Long,
    val deletedAt: Long?,
    val revision: OrganizationRevision,
)

data class HabitPlacement(
    val habit: HabitRef,
    val containerId: ContainerId?, // null = без назначения
    val localOrder: Int,
    val origin: PlacementOrigin,
    val revision: OrganizationRevision,
)

enum class PlacementOrigin {
    MIGRATION_SNAPSHOT, USER_CHANGE, IMPORT_SNAPSHOT, UNKNOWN_LEGACY
}

data class ContainerPath(
    val nodes: List<ContainerId>,  // root -> leaf, без повторов
    val revision: OrganizationRevision,
)

sealed interface HistoricalLocation {
    data class Known(val path: ContainerPath) : HistoricalLocation
    data object Unassigned : HistoricalLocation
    data object UnknownBeforeCutover : HistoricalLocation
    data object UnknownLegacyAssignment : HistoricalLocation
}
```

В commonMain использовать существующие multiplatform-паттерны проекта; `@JvmInline` здесь обозначает intent типизированного value class, а не требование JVM-only реализации. Stored UUID — непрозрачная строковая идентичность. Не вводить строгий RFC parser для всех legacy Habit/Goal/Entry identifiers: в проекте уже существуют составные IDs и fixtures с непротокольными строками. Для новых Container UUID генерировать один раз тем же hex-форматом, что использует текущий Kotlin UUID generator.

`Container` не владеет Habit objects и не содержит рекурсивный mutable `children` list. Дети и содержимое читаются запросами. У него нет target, deadline, completionPercent, DayTier и Habit score.

### 3.2. Контракты

```kotlin
interface ContainerQueries {
    fun find(id: ContainerId, includeDeleted: Boolean = false): Container?
    fun roots(includeArchived: Boolean = false): List<Container>
    fun children(parent: ContainerId, includeArchived: Boolean = false): List<Container>
    fun path(id: ContainerId): ContainerPath
    fun subtreeIds(id: ContainerId): Set<ContainerId>
    fun search(query: String): List<ContainerSearchHit> // title + полный путь
}

interface HabitPlacementQueries {
    fun current(habit: HabitRef): HabitPlacement?
    fun directHabits(container: ContainerId?): List<HabitRef>
    fun subtreeHabits(container: ContainerId): Set<HabitRef>
    fun locationAtRevision(habit: HabitRef, at: OrganizationRevision): HistoricalLocation
}

interface OrganizationService {
    fun create(request: CreateContainer): OrganizationResult<Container>
    fun edit(request: EditContainer): OrganizationResult<Container>
    fun move(request: MoveContainer): OrganizationResult<Unit>
    fun reorder(request: ReorderContainers): OrganizationResult<Unit>
    fun placeHabit(request: PlaceHabit): OrganizationResult<Unit>
    fun reorderHabits(request: ReorderPlacedHabits): OrganizationResult<Unit>
    fun archive(id: ContainerId, expectedRevision: OrganizationRevision): OrganizationResult<Unit>
    fun unarchive(id: ContainerId, expectedRevision: OrganizationRevision): OrganizationResult<Unit>
    fun deleteEmpty(id: ContainerId, expectedRevision: OrganizationRevision): OrganizationResult<Unit>
}
```

В запросах move/place/reorder передаются operation ID и ожидаемая organization revision. Operation ID делает повтор команды идемпотентным. Revision предотвращает запись по устаревшему UI snapshot. Повтор ранее успешной операции возвращает её прежний результат; повтор op ID с другим запросом отклоняется. Внутри `OrganizationChanges` сохраняется канонический command payload для такой проверки; это локальный audit/идемпотентность, не новый sync protocol.

### 3.3. Ответственность слоёв

| Слой | Новые компоненты | Ответственность |
|---|---|---|
| Domain/commonMain | Container, ids, Placement, errors, TreePolicy | Формулировка инвариантов без Android/SQL |
| Application/commonMain | OrganizationService, MigrationPlanner, HabitOrganizationFacade | Команды, цикл/жизненный цикл/порядок, граница старой и новой модели |
| Ports | ContainerQueries, HabitPlacementQueries, OrganizationStore, UnitOfWork, Clock, IdGenerator, HabitIdentityLookup | Тестируемые зависимости; существование Habit сверяется со старым доменом |
| Memory adapter | MemoryOrganizationStore, memory UnitOfWork | Полностью проверяемое поведение без production DB |
| SQLite adapter | SQLiteOrganizationStore, typed history repositories | Prepared statements, transactions, FK, conversion local id <-> UUID |
| Android integration | OrganizationMode/DatasetSession, DI providers, safe experimental opener | Выбор dataset до построения component, изоляция sync/preferences/services |
| Read models/UI | ContainerContentsBuilder, RootContainerOptions, breadcrumbs | Children + direct Habits; позже отдельные task/focus providers |

`OrganizationStore` — внутренний transactional storage API, а не публичный CRUD с произвольным `updateParent`. Весь mutation-path проходит через OrganizationService. Repository сам по себе не считается защитой дерева, если UI может записать parent в обход use case.

### 3.4. Дерево, порядок и цикл

- Лес: `parentId=null` означает root Container. Несколько root допустимы. «Без раздела» — view, не особый Container.
- Имена не уникальны. Два «ЛР2» различаются UUID и путём. UUID не меняется при rename/move/archive/delete.
- Минимум шесть уровней должен проходить тест. Бизнес-ограничения глубины в шесть уровней нет. Обходы итеративны, с visited set и ресурсной защитой от повреждения.
- Проверка MoveContainer выполняется **в той же write transaction**, что запись: parent существует, не tombstoned, target node не tombstoned, parent не сам node и не его descendant. В первый срез размещение в effectively archived ветви запрещено; сначала восстановить ветвь.
- Проверять путь надо с повторным чтением в transaction; проверка только на экране неверна. Для SQLite использовать write transaction, исключающую параллельное изменение дерева до commit; при standalone connection — BEGIN IMMEDIATE. Внутри уже начатой transaction использовать согласованный UnitOfWork/savepoint, не вложенный BEGIN.
- Root — обычный узел, его можно переместить под другой узел. Защищать системный root не требуется, поскольку системного root нет.
- Порядок Container — dense `Int` среди sibling Containers. Вставка/move/reorder перенумеровывает затронутых siblings атомарно. Для чтения при повреждённых/дублирующихся позициях tie-break UUID; validator диагностирует дубли, а не оставляет случайный порядок.
- Habit получает отдельный dense `localOrder` среди непосредственных Habit данного Container. При миграции он выводится из существующего глобального Habit.position с deterministic tie-break local id. Сам Habit.position не меняется.
- Порядок в global Habits остаётся старым. Ручная сортировка в Container не меняет global порядок, и наоборот.
- Mixed UI использует **секции**, поэтому общего cross-type rank для child Container + Task + Habit сейчас нет. Если позднее нужен действительно смешанный drag-and-drop, это отдельный контракт; не закладывать polymorphic NodeOrder наугад.

Локальная transaction/cycle protection не решает distributed concurrent A->B/B->A. Sync нового дерева не включается в Foundation.

### 3.5. Archive/delete

**Archive:** меняет собственный `isArchived`. В обычном Browse ветвь скрыта, если архивирован сам узел или любой его предок. Дочерние архивные флаги и Habit.isArchived не переписываются. Это позволяет unarchive родителя, не оживляя отдельно архивированного ребёнка.

Global Habits, индивидуальные напоминания и Habit statistics сохраняют прежнюю политику active/archive Habit. Archive Container не отключает Habit и её reminder. UI сообщает об этом; пользователь может отдельно архивировать Habit. Позже тот же принцип сохраняет видимость настоящих Task deadlines.

**Delete:** только soft delete пустого узла. Наличие не удалённых детей либо размещённых не удалённых Habit, включая архивные, отклоняет операцию. Сначала явно перенести/отсоединить содержимое. Current placement tombstoned Habit может оставаться исторической ссылкой и не требует удаления истории. Новые назначения к tombstoned Container запрещены.

История, identity и metadata удалённого Container остаются. Hard purge и cascade delete не реализуются. Перенос всей ветви «при удалении», массовый archive Habit, автоматическое завершение Container также не реализуются. При migration возможны legacy tombstoned blocks, на которые всё ещё указывает active Habit: это отдельная диагностируемая аномалия, а не обычная новая delete-policy.

### 3.6. Historical placement уже в Foundation

Если разрешить перемещения и не сохранять историю с первой операции, следующий этап уже не сможет восстановить первые изменения. Поэтому заложить **две типизированные истории**:

1. Версии Container: parent, title, order, archive/delete metadata.
2. Версии Habit placement: Container/null, local order, provenance.

Каждая организационная transaction имеет возрастающую локальную revision и UTC recordedAt. Исторический путь строится по parent versions на **одной и той же revision**. История только Habit placement недостаточна: Container-предок также может переехать.

Revision, а не wall-clock timestamp, определяет порядок; две команды в одну миллисекунду остаются различимыми. История фиксирует состояние, наблюдавшееся после принятой операции, без retroactive effective-date редактора и без distributed causal semantics.

Migration создаёт baseline на revision=1 и cutoverAt. До неё возвращается `UnknownBeforeCutover`, а не сегодняшний путь. Nullable placement с `UNKNOWN_LEGACY` у Habit без extension означает неизвестное исходное назначение; nullable placement с известным origin означает подтверждённое отсутствие назначения.

**Ограничение:** Entry сейчас имеет дату, но не время каждого исходного действия. Даже после появления истории организации нельзя точно распределить сумму за день, в середине которого Habit переместилась, либо позднее исправленную запись за прошлый день. Foundation не добавляет historical Container report по таким Entry. Для нового FocusSession этапа использовать organization revision/path snapshot на факте; его schema не создаётся сейчас. Старый отчёт по сферам остаётся явно классификацией по текущему размещению.

## 4. Конкретная схема хранения

Это проект полей/ограничений, а не SQL migration. Новый schema number условно N=30, **только для тестовой/экспериментальной копии**; при начале реализации номер сверить заново. Production `DATABASE_VERSION` не повышается в Foundation.

### 4.1. Таблицы

| Таблица | Поля и ограничения |
|---|---|
| `OrganizationState` | `id INTEGER PK CHECK(id=1)`; `dataset_uuid TEXT NOT NULL UNIQUE`; `foundation_version INTEGER NOT NULL`; `mode TEXT NOT NULL CHECK(mode IN ('STAGED','CONTAINER_LOCAL'))`; `cutover_at INTEGER NOT NULL`; `current_revision INTEGER NOT NULL`; `source_schema_version INTEGER NOT NULL`; `source_snapshot_sha256 TEXT NOT NULL` |
| `OrganizationChanges` | `revision INTEGER PK`; `op_uuid TEXT NOT NULL UNIQUE`; `recorded_at INTEGER NOT NULL`; `operation_type TEXT NOT NULL`; `origin TEXT NOT NULL`; `command_payload TEXT NOT NULL`. Operation payload проверяется по versioned локальному формату, не по сетевому протоколу |
| `Containers` | `id INTEGER PK AUTOINCREMENT`; `uuid TEXT NOT NULL UNIQUE`; `parent_id INTEGER NULL FK Containers(id) ON DELETE RESTRICT`; `name TEXT NOT NULL CHECK(trim(name)<>'')`; `color INTEGER NULL`; `icon TEXT NULL`; `sibling_order INTEGER NOT NULL CHECK(sibling_order>=0)`; `is_archived INTEGER NOT NULL CHECK(IN(0,1))`; `created_at INTEGER NULL`; `updated_at INTEGER NOT NULL`; `deleted_at INTEGER NULL`; `revision INTEGER NOT NULL FK OrganizationChanges(revision)`; `CHECK(parent_id IS NULL OR parent_id<>id)` |
| `HabitPlacements` | `habit_id INTEGER PK FK Habits(id) ON DELETE RESTRICT`; `container_id INTEGER NULL FK Containers(id) ON DELETE RESTRICT`; `local_order INTEGER NOT NULL CHECK(local_order>=0)`; `placement_origin TEXT NOT NULL CHECK(IN('MIGRATION_SNAPSHOT','USER_CHANGE','IMPORT_SNAPSHOT','UNKNOWN_LEGACY'))`; `revision INTEGER NOT NULL FK OrganizationChanges(revision)`. PK гарантирует максимум одно primary placement |
| `ContainerHistory` | `container_id INTEGER NOT NULL FK Containers(id) ON DELETE RESTRICT`; `revision INTEGER NOT NULL FK OrganizationChanges(revision)`; `parent_id INTEGER NULL FK Containers(id) ON DELETE RESTRICT`; snapshot `name/color/icon/sibling_order/is_archived/created_at/updated_at/deleted_at` с теми же базовыми типами; `PRIMARY KEY(container_id,revision)` |
| `HabitPlacementHistory` | `habit_id INTEGER NOT NULL FK Habits(id) ON DELETE RESTRICT`; `revision INTEGER NOT NULL FK OrganizationChanges(revision)`; `container_id INTEGER NULL FK Containers(id) ON DELETE RESTRICT`; `local_order INTEGER NOT NULL`; `placement_origin TEXT NOT NULL CHECK(IN('MIGRATION_SNAPSHOT','USER_CHANGE','IMPORT_SNAPSHOT','UNKNOWN_LEGACY'))`; `PRIMARY KEY(habit_id,revision)` |
| `LegacyBlockMap` | `legacy_block_id INTEGER PK FK HabitBlocks(id) ON DELETE RESTRICT`; `legacy_block_uuid TEXT NOT NULL UNIQUE`; `container_id INTEGER NOT NULL UNIQUE FK Containers(id) ON DELETE RESTRICT`. Строка immutable после activation; UUID — сохранённый исходный identity, а не lookup по имени |

`OrganizationState.current_revision` валидируется против максимума `OrganizationChanges`; можно дополнительно объявить FK к revision. В одном DB-файле ровно один dataset. Cross-dataset namespace хранится в manifest/import plan; повторять dataset_uuid в каждой domain row сейчас не требуется.

FK по локальному Habit.id не делает его переносимой identity. Наружу placement API работает с Habit.uuid; SQLite adapter разрешает UUID в локальный id. Full restore сохраняет local IDs; merge-import обязан remap FK. Не добавлять дублирующий habit_uuid с возможностью расхождения с Habits.uuid в каждую placement row.

Прямая ссылка HabitPlacements на Habits, а не на HabitExtensions, позволяет сохранить историческую организацию после удаления extension. `ON DELETE RESTRICT` сознательно блокирует старый hard reset/removeAll в новом dataset. Если он понадобится, реализовать отдельный явный dataset reset, не удалять history по CASCADE.

**Известные прежние UUID:** если inventory доказал несколько UUID одного legacy блока, recovery manifest сохраняет их и основания сопоставления. До необходимости автоматического legacy identity resolution отдельная alias-таблица не обязательна. Если она потребуется в следующем migration PR, использовать маленькую `LegacyBlockIdentities(legacy_uuid PK, legacy_block_id FK LegacyBlockMap)`; это compatibility identity registry, не пользовательские aliases/shortcuts. Нельзя угадывать исчезнувшие UUID или связывать их только по совпадающему имени.

### 4.2. Индексы

| Индекс | Назначение |
|---|---|
| UNIQUE Containers(uuid) | Stable lookup |
| Containers(parent_id, deleted_at, is_archived, sibling_order, uuid) | Roots/children/ordered Browse |
| Containers(deleted_at, is_archived, uuid) | Active/archive выборки |
| HabitPlacements(container_id, local_order, habit_id) | Direct contents и порядок |
| ContainerHistory(container_id, revision DESC) | Версия узла на revision; PK уже даёт базовый доступ |
| ContainerHistory(parent_id, revision, container_id) | Диагностика/исторические обходы при необходимости |
| HabitPlacementHistory(habit_id, revision DESC) | Историческое размещение |
| HabitPlacementHistory(container_id, revision, habit_id) | Исторические selection queries при необходимости |
| UNIQUE OrganizationChanges(op_uuid) | Идемпотентная команда |
| OrganizationChanges(recorded_at, revision) | Выбор revision по observed time с явной ограниченной семантикой |
| UNIQUE LegacyBlockMap(legacy_block_uuid), UNIQUE LegacyBlockMap(container_id) | Mapping один к одному |

Не создавать UNIQUE(parent_id,sibling_order): nullable root и переиндексация позиций потребуют лишней сложности. Уникальность/плотность порядка гарантирует transactional service и validator. Дополнительные history-индексы, помеченные «при необходимости», включать после проверки query plan; обязательный минимум — PK и основные current queries.

### 4.3. Источник истины и предотвращение dual-write

| Фаза | Дерево/размещение | Legacy rows |
|---|---|---|
| Production v29 | HabitBlocks + HabitExtensions.block_id | Работают как сейчас |
| STAGED copy | Новые таблицы заполняются из immutable снимка | Snapshot для проверки, пользовательских команд нет |
| CONTAINER_LOCAL copy | Containers + HabitPlacements; transactional history фиксирует каждую смену | HabitBlocks и старый block_id заморожены |

Current rows — authoritative текущее состояние. History — обязательный журнал принятых состояний; максимальная history version должна совпадать с current row. Обе записи обновляются одним commit. Нет независимого редактирования history и current, нет background bidirectional reconciler.

LegacyBlockMap нужен: UI `HabitBlock` теряет UUID/tombstone metadata, local block ID не обязан совпадать с Container ID, а повторные staging/restore должны давать одно и то же mapping. Простого сравнения названий недостаточно, даже если при migration UUID совпадает.

Каждый существующий блок переносится один к одному в root Container, включая архивные и tombstoned строки. Сохраняются прочитанные имя, цвет, иконка, позиционный порядок, archive/delete metadata и UUID. Нет автоматического переименования в семёрку из ТЗ, повторного seed или защиты id<=7. Создание новых root/child не создаёт HabitBlock.

`HabitExtensions` остаётся местом хранения dayTier/timerEnabled/statisticsStartDate. Experimental extension writer обновляет эти поля с сохранением legacy block_id snapshot. Он не принимает старое поле Habit.blockId за команду placement.

## 5. ER/ASCII-схема

```text
                         OrganizationState (one dataset, mode, cutover, revision)
                                      |
                          OrganizationChanges (revision, op UUID)
                             /                  \
                    ContainerHistory       HabitPlacementHistory
                           |                        |
                           v                        v
HabitBlocks --1:1--> LegacyBlockMap --1:1--> Containers <--N:1-- HabitPlacements
    |                                          ^                   |
    |                                        parent                | 1:1
    |                                          |                   v
    +---- legacy HabitExtensions.block_id      +-- Containers     Habits
                  |                                               |  \
                  +---------------- habit_id ----------------------+   +--> HabitGoals
                                                                  +------> Repetitions

HabitExtensions.dayTier/timerEnabled/statisticsStartTimestamp остаются.
EntryOps, SyncQueue и AppSettings сохраняются без переиздания старых операций.
Task/FocusSession/Contribution/SourceRule tables здесь отсутствуют.
```

## 6. Migration plan

### M0. Остановить записи и сделать recovery snapshot

Для Foundation пользователь явно создаёт экспериментальную копию; production не подменяется. Snapshot получают согласованно: остановить sync/commands/writer jobs на момент снимка, сериализовать таймер/prefs и DB состояние, выполнить SQLite snapshot API с checkpoint; не копировать работающую SQLite базу вслепую. Текущий fallback file-copy допустим только под блокировкой writer и после validation.

Recovery bundle содержит DB, schema/manifest, SHA-256, нужные preferences, active timer snapshot, sync cursor/bootstrap/review flags и device-context metadata. Auth secrets не экспортировать в переносимый открытый manifest. Аккаунт и credentials не активировать в экспериментальном dataset.

Для первой activation экспериментального dataset выбрать момент **без активного legacy таймера**. Можно дождаться его штатного окончания/паузы, но не завершать автоматически. Исходный timer snapshot всё равно сохраняется в recovery bundle. Таймерная подсистема до отдельной dataset-aware адаптации не запускается на experimental copy: её preferences и callbacks сейчас не разделены по dataset.

### M1. Inventory до построения SQLModelFactory

Прочитать raw rows всех таблиц, включая tombstones, и зафиксировать:

- local IDs/UUID Habit, Block, Goal, Entry; полный values/notes набор;
- active/archive/delete flags и timestamps;
- точные HabitExtensions и текущие assignments;
- цели с effective_timestamp, frequency, target_type/value/unit;
- EntryOps и SyncQueue побайтно/по каноническому порядку, включая pending/failure metadata;
- stats cutoffs, порядок Habit, AppSettings;
- missing/blank/duplicate UUID, orphan refs, block UUID repair candidates.

Ошибки не чинить через UI/repository с auto-generated UUID. Если v29 имеет пустой/дублированный UUID, activation блокируется с диагностикой. Для старой базы выполнить existing migrations до v29 **только на staging copy**, зафиксировать generated UUID в сохраняемом нормализованном snapshot; следующие retry идут от него. Ранее отсутствовавший UUID не «восстанавливается», а впервые назначается этой migration с provenance.

### M2. Подготовить план без изменений исходной БД

`LegacyContainerMigrationPlanner` возвращает mapping plan, placement plan и issues. Стабильность плана определяется checksum исходного snapshot, dataset ID и сохранёнными выбранными identity; имя/цвет не являются identity.

Для каждой Habit с extension: `block_id=null` -> известное unassigned; существующий block -> его Container. Для tombstoned Habit без extension -> current null и baseline UNKNOWN_LEGACY. Не назначать её автоматически в «Прочее».

Dangling block_id или active Habit внутри tombstoned block сохраняются в исходном snapshot и диагностике. По умолчанию activation отклоняется до явного решения; допустимые repair decisions — сохранить видимый recovery-context либо явно перенести в unassigned, с сохранением исходного ref и причины. Такие исправления не маскируются обычным migration success. Нормальный fixture должен мигрировать без repair decisions.

### M3. Добавочная migration в transaction на копии

Создать новые таблицы/indexes, baseline OrganizationChanges и root Containers. Заполнить map и placements. Записать ContainerHistory и HabitPlacementHistory на cutover revision, origin=MIGRATION_SNAPSHOT либо UNKNOWN_LEGACY. Ничего не датировать прошлой датой первой Entry.

Не писать Habits/Repetitions/HabitGoals через `habitList.update()`, `EditHabitCommand` или importer. Не менять original data, normalized goal history, UUID, EntryOps, queue и Habit.position. Не создавать FocusSession, Contribution, LegacyAggregate-копии старых чисел или новое EntryOp на прежнее значение.

Atomicity включает schema, seed, map, history, metadata и экспериментальный user_version. При ошибке rollback всей migration. Наличие таблицы без complete manifest не считается успешным cutover. Повтор готового плана — no-op; частичный transaction не оставляет новый mapping. Snapshot с впервые generated UUID сохраняется отдельно, чтобы crash/retry старой нормализации не менял identity.

### M4. Семантическая проверка

Проверить SQLite integrity и FK, уникальность UUID, mapping cardinality, отсутствие циклов, родительские ссылки, порядок, один placement на Habit, согласованность current/history. Проверка global FK нужна до включения enforcement: legacy anomalies могут существовать даже при валидном quick_check.

Сверить исходные таблицы/колонки до/после: UUID/local IDs, raw Entry values/notes, tombstones, goals, extension поля и pending ops. New tables — единственная добавочная область данных. Для older-than-v29 fixture сравнивать old-domain данные с нормализованным v29 staging baseline, поскольку существующие migrations сами добавляют sync metadata.

Отдельные semantic fixtures: boolean YES_MANUAL/YES_AUTO/NO/UNKNOWN/SKIP; numerical explicit zero/UNKNOWN/SKIP и масштаб x1000; AT_LEAST/AT_MOST; day/week/month; цель 300->420 в середине недели; local/global stats cutoff; archive/deleted Habit; notes; score/streak. Не улучшать formula одновременно с migration.

### M5. Активировать только experimental dataset

Сначала готовность backup/restore, sync gate и adapters, затем `mode=CONTAINER_LOCAL`. Построить отдельную DI-сессию на validated copy. Mode/persisted dataset metadata проверяются до запуска SyncCoordinator, workers, bootstrap/repair, reminders/widgets и timer manager. Не ограничиваться выключением кнопки sync.

Исходная production DB остаётся прежней и не получает новый schema number. Изменения экспериментальной копии не объединяются назад автоматически. Выход из эксперимента сохраняет копию; открытие production возвращает старый dataset. Экспериментальные изменения пользователю обозначены как локальные и независимые.

### M6. Backup/import/restore

Full experimental backup включает все новые таблицы/history/map и legacy таблицы/UUID/goals/Entry special values/notes/tombstones/queue. Отдельный manifest хранит dataset mode, foundation version, recovery preferences и происхождение source snapshot. Validator обязан обнаружить missing Container tables, invalid FKs, cycles и placement/history divergence.

**Restore** заменяет coherent experimental snapshot через staging и перестроение DI/cache. Old full DB backup -> normalize on copy -> Foundation migration -> validation -> experimental activation. Prod restore-route не должен принимать экспериментальную N-схему случайно.

**Merge import** не равен restore. В Foundation: реализовать отдельно проверенный legacy Habit import в новый dataset с размещением на дату импорта и сохранением UUID либо явно выбранным remap конфликтов. Не переносить name/color/position matching в Container import. Existing Habit metadata import не меняет primary placement без explicit preview/choice. Архивные/удалённые rows обрабатываются по явной политике, не воскресают незаметно. Container dataset merge-import до собственного tree/history mapping **отклоняется**, full restore такого backup поддерживается.

Не заявлять lossless Container merge-import готовым на основании принятия файла `canHandle()`.

### M7. Rollback и будущая production migration

Для эксперимента rollback — закрыть экспериментальную сессию и открыть неизменённую production DB; новая копия и backup сохраняются. Это самый простой recovery path Foundation.

Будущая production migration потребует отдельного release gate: tested in-place/staged migration, writer quiescence, protocol/dataset compatibility, recovery до automatic DB open и restart восстановления после crash. UI feature flag не downgrades SQLite schema. Старый APK отказывает `onDowngrade`.

После новых данных возврат exact v29 snapshot означает возврат к прежнему моменту, а не lossless merge. После remote publish локальный restore не откатывает Supabase. Новое дерево не публикуется в рамках Foundation, поэтому distributed rollback пока не возникает.

## 7. Supabase и старые версии

Новых entity types, endpoint, protocol, reducers и server SQL в Foundation нет. Legacy dataset продолжает старый sync без изменения поведения. Experimental dataset полностью local; никакого push, pull, bootstrap, default UUID repair или advancement legacy cursor.

Обычные Habit/Entry команды на экспериментальной копии также не должны отправлять изменения в старый аккаунт. Legacy outbox сохраняется как замороженный исходный набор; новые команды экспериментального режима не дописывают в него новые remote операции. Организационный локальный audit хранится в OrganizationChanges и не считается sync outbox. Future sync adoption потребует snapshot/epoch или отдельно спроектированного new outbox, а не публикации этого локального журнала как готового протокола.

Для будущего synced Container dataset нужны server-side отказ старым клиентам на всех прежних прямых REST read/write путях, отдельный capability/epoch contract, durable handling unknown/dependency events и общий conflict algorithm дерева. Минимальный safe contract — старые клиенты не синхронизируют несовместимый dataset. LWW отдельных parent fields недостаточен.

Это будущие release prerequisites, а не работы первого Foundation. Live Supabase в этой задаче не проверялся; SQL в docs не доказывает deployed schema.

## 8. Как встроить рядом со старым ядром

| Компонент | Действие Foundation | Граница |
|---|---|---|
| Habit | Сохранить identity, measurements, goals, original/computed entries, score/streak. Placement доступен через HabitOrganizationFacade | Не делать recurring Task, не внедрять children/graph в Habit |
| HabitGoal / goal repository | Оставить модель/формулы. В migration raw-copy без update | Не менять effective-date semantics и current goal normalization |
| Entry/EntryList/SQLiteEntryList, ScoreList/StreakList | Сохранить data contract и вычисления | Не превращать YES_AUTO или minute totals в FocusSession |
| HabitBlock | Сохранить legacy DTO/таблицу и старый production UX | Постепенно deprecated только как организационная authority в новом режиме |
| HabitBlockRepository | Production путь неизменен. Для migration raw reader; в experimental writable legacy block access запрещён | Не обернуть только один UI: все прямые writers должны быть закрыты |
| HabitExtensionData/Repository | DayTier/timer/stats-start остаются; experimental writer сохраняет старый block_id snapshot | Не использовать whole-row upsert для обновления нового placement |
| SQLiteHabitList | Добавить mode-aware seam для placement lifecycle/create/delete и старых extension writes | Обычная правка name/goal не меняет placement; physical removeAll в новом dataset запрещён |
| Create/Edit/DeleteHabitCommand | Команды сохраняются. Explicit create/place соединяется transactional wrapper; tombstone сохраняет placement history | Нельзя считать любое копирование blockId новым пользовательским move |
| HabitList/MemoryHabitList | Старый global порядок сохраняется; organization grouping в experimental режиме питается отдельным selector/read model | Не переименовывать всю HabitList в универсальный ItemList |
| ManageBlocksActivity / EditHabitActivity | Сохранить classes/layouts; mode-aware facade/chooser используют Container UUID, root options и path | default blockId=7 и id<=7 delete rule не переносятся на Container |
| HabitCardListView / SkipDayDialogController | Переключить organization selection/group key на new read model там, где включён эксперимент | Массовый SKIP не затрагивает другую ветвь из-за stale blockId |
| StatisticsReportStateBuilder/Overview/per-habit cards/Compare | Формулы сохранить. Container selector передаёт выбранный Habit set; scope current/direct/subtree явный | Не добавлять Task как Habit; historical Container aggregate и новый ContainerReport отложены |
| StatisticsActivity/Fragment filters/cache | Legacy production filter unchanged; experimental ContainerSelection и org revision в cache key | Не выдавать old sphere projection за historical path |
| BackupManager/DatabaseUtils/restore tasks | Обернуть schema-specific validator/opener и dataset-aware routing, расширить recovery bundle | Не использовать default production restore для experimental database |
| LoopDBImporter/GenericImporter | Существующий production import сохранить. Новый dispatch для safe legacy->Container import; unsupported new merge отказ | Не сливать одноимённые Containers |
| SyncCoordinator/SyncManager | Legacy behavior сохраняется; hard gate для experimental session до repair/bootstrap/network | `isSyncReviewRequired` недостаточно: manual override не должен обходить dataset gate |
| SQLModelFactory/MemoryModelFactory | Новые stores предоставлять рядом отдельным OrganizationModule/provider | Не заставлять каждую upstream factory строить пользовательские Tasks |
| HabitsApplicationComponent | DatasetSession определяет DB/mode до component; новые providers scoped к этой сессии | После restore/переключения пересоздать component и отменить старые jobs/listeners |
| MainActivity/MainDestination | Foundation не меняет home/navigation. Browse добавится отдельным следующим PR | Существующие Habit URI/deep links/package/application id сохранить |
| TimerSessionEngine/Manager, widgets/reminders | Production поведения не менять. Experimental запуск blocked до dataset-aware интеграции | Нельзя смешивать общий prefs timer и разные DB; новый FocusSession не реализуется |

### Конкретный compatibility bridge

Не заполнять `Habit.blockId` поддельным Container local ID: это разные namespaces, а старый editor/sync воспримет его как ссылку HabitBlocks. Не создавать фиктивные блоки для новых roots и не заводить вечный dual-write.

Добавить `HabitOrganizationFacade` с режимами Legacy и ContainerLocal. В новом режиме он даёт `RootContainerOption(uuid,title,color)`/`ContainerSelection`, direct/subtree set Habit UUID и полный путь. Небольшие organization-specific consumers используют эти ответы; остальной Habit UI продолжает старые данные и вычисления.

`Habit.blockId` в experimental session — legacy field, не текущее положение. UI после cutover не показывает его как актуальную сферу. Старый editor location section переключается на новый chooser. Old management screen использует new service, сохраняя компоновку; полный Browse остаётся следующим этапом. Старые сохранённые sphere filters преобразуются через LegacyBlockMap в Container UUID; если узел теперь nested, selector показывает его полный путь, а не переименовывает его в другой root.

BY_SPHERE в эксперименте означает new root grouping: group key UUID текущего корня; direct placement доступен через breadcrumb. Это небольшая явная адаптация сортировочного read model, не подмена blockId. Для generic statistics выбранный Container определяет current direct/subtree Habit set до расчёта. Formula остаётся прежней; новый historical location mode ещё не обещается.

До готовности всех этих seams cutover выключен. Mode нельзя включить после адаптации только edit screen, оставив old manage/import/sync writers.

## 9. Android Information Architecture

Макеты ниже включают **целевые последующие** Tasks/Focus/Stats, чтобы оценить IA. Их присутствие в wireframe не расширяет Foundation scope. В работающем Foundation не показывать пустые Tasks/Focus tabs, фиктивные totals или неработающие кнопки. Общее правило: Habit — отметка/численная норма, Task — разовый результат, child Container — переход. Их controls визуально и семантически различаются.

### A. Today-first

Главный экран — действие на сегодня. Browse — организация. Глобальный Tasks виден в Browse как быстрый entry; global Habits — самостоятельная вкладка. Reports доступен в Today toolbar и Container; Settings — toolbar/menu.

```text
TODAY                             [Поиск] [Отчёты] [Настройки]
Сегодня, сб 3 октября                    Режим: NORMAL

Настоящие сроки
□ Сдать отчёт               сегодня 18:00    Учёба / ВКР

План на сегодня
□ Запросы для ЛР2                    [Начать]
  БД / Лабораторные / ЛР2

Привычки и нормы
● Разминка                         [Отметить]
# Учёба                   420 / 600 мин за неделю   [+]

Активный Focus: ЛР2   12:35       [Пауза] [Закончить]
                                      [+ Добавить]
[Today]              [Habits]              [Browse]
```

```text
BROWSE                                     [Поиск] [+ Раздел]
[Все задачи] [Inbox] [Upcoming] [Архив]
Избранное: БД, Полумарафон
Недавние: ЛР2, ВКР
Разделы: Учёба >   Тело >   Личное >
Без раздела >

CONTAINER: Базы данных                         [Поиск] [+]
Учёба / … / Базы данных                       [Все предки]
[Обзор] [Задачи] [Привычки] [Статистика]
Содержимое: [Только здесь v]

Подразделы       Лабораторные >      Теория >
Задачи           □ Подготовиться к защите       [Начать]
Привычки         # БД: 2 / 3 занятия за неделю
Недавняя работа  ЛР2 — 42 мин; Теория — 25 мин

GLOBAL TASKS: [Inbox] [Сегодня] [Upcoming] [Все] [Готово]
GLOBAL HABITS: старый быстрый список/сетка + container-path chip
```

Quick Add: title -> сохранить Task в virtual Inbox; место/plan/deadline optional. В Container default target — текущий Container, chooser доступен. Habit create всегда отдельный тип с нормой; one-title task не маскируется Habit.

Плюс: ежедневная работа не требует обхода дерева; важное из разных ветвей собрано вместе. Минус для первого прототипа: сейчас нет Task/DayPlan providers, настоящий Today потребует нескольких доменов и пересмотра shell. Today из одних Habit сейчас плохо проверит эту IA.

### B. Context-first

Home — рабочие контексты. Постоянный global Today и индикатор сроков защищают от пропуска другой ветви. Global Habits/Tasks доступны через home header, не через пять уровней меню.

```text
CONTEXTS                              [Поиск] [+ Раздел] [Настройки]
[Сегодня: 1 срок]     [Все задачи]     [Все привычки]

Избранное
Учёба >        ВКР >        Полумарафон >
Недавние
БД / ЛР2 >     Личное / Документы >
Все разделы >                         Без раздела >

[Контексты]          [Today]          [Отчёты]
```

```text
CONTAINER: Базы данных                            [+]
Учёба / … / Базы данных                    [Все предки]
[Работа] [История и статистика]

Подразделы: Лабораторные >   Теория >

Ближайшая работа
□ Подготовиться к защите           plan: вт; срок: пт
□ Запросы ЛР2                               [Начать]

Регулярные нормы
# БД                  2 / 3 занятия
# Учёба               420 / 600 мин
  Контекст нормы: Учёба; показана как связанная, не второй объект

Сессия по контексту                              [Начать]
                           [Сегодня: 1 срок в другом разделе]

GLOBAL TASKS: Inbox / Today / Upcoming / All / Completed
GLOBAL HABITS: все нормы + фильтр контекста
TODAY: сроки -> plan -> нормы, как в A, вторичный экран
STATS: отдельные time / outcomes / habit evaluations
```

Для работы целиком над БД этот вариант хорошо удерживает задачи, нормы и контекст рядом. Отдельный dashboard из десятка KPI не нужен. Секция общей нормы предка только объяснимая дополнительная view, не duplicate placement.

Минус: пользователь с несколькими простыми Habit встречает организацию раньше действия. Без избранного/search/recent ежедневная навигация глубоких ветвей дорога. Home summaries и cross-context Today увеличивают объём реализации.

### C. Evolutionary — рекомендуемый первый прототип

Сохранить текущий home Habits и Reports/Settings. В эксперименте добавить вход «Разделы» в toolbar/menu, без обязательной четвёртой bottom-tab. После проверки Browse можно решить, нужна ли постоянная вкладка и как вводить Today.

```text
HABITS                             [Поиск] [Разделы] [+ Habit]
MINIMUM / NORMAL / IDEAL / OPTIONAL
Текущая привычная сетка отметок по дням
Учёба       # 420 / 600 мин за неделю
Разминка    ● [отметки]
  Необязательный контекст: Учёба / … / БД

[Habits]              [Reports]              [Settings]
```

```text
РАЗДЕЛЫ                                   [Поиск] [+ Раздел]
Избранное / Недавние                       (следующее расширение)
Учёба >    Тело >    Личное >
Без раздела >                            Архив разделов >

CONTAINER: Базы данных                              [+]
Учёба / … / Базы данных                     [Все предки]

Подразделы
Лабораторные >     Теория >

Привычки здесь
# БД               2 / 3 занятия за неделю
● Повторение       [Отметить]
[Только здесь] [Все внутри]

Задачи здесь                      (после отдельного Task MVP)
□ Подготовиться к защите
□ Запросы ЛР2                                   [Начать]

[История работы] [Статистика]      (после фактов, без пустых MVP tabs)
```

```text
ПОСЛЕ TASK MVP: entry из Разделов или toolbar
GLOBAL TASKS: [Inbox] [Today] [Upcoming] [Все] [Готово]
TODAY: реальные сроки / выбранные задачи / нормы Habit
GLOBAL HABITS: прежняя bottom-tab
REPORTS: существующая Habit statistics
CONTAINER STATS: отдельный экран позже, с явным режимом атрибуции

QUICK ADD: [Дело] [Привычка] [Раздел]
Дело: название -> сохранить, default Inbox либо текущий контекст
```

C проверяет организационный фундамент на реальных Habit, сохраняя привычный ежедневный сценарий. Его риск — две точки входа и длительное ощущение «Habit tracker с дополнительным разделом». Поэтому это **первый прототип**, не окончательный отказ от Today-first.

### Общая deep-navigation policy

На каждом локальном экране title + `Учёба / … / БД / ЛР2`. Нажатие пути открывает список всех предков, с прямыми переходами. Android Back возвращает по navigation history; отдельная команда «Выше» идёт к parent. После прямого входа из поиска Back возвращает в поиск, а не принудительно проходит всех предков.

Search/quick move возвращают title + полный path; дубликаты имён не объединяются. Move chooser исключает node и descendants, архивные target недоступны до unarchive. Local children page вместо бесконечного indentation. Полный outline — опциональный последующий режим. Favorites/recent ускоряют 5–6 уровней; для первого Browse минимально нужны breadcrumb и search, favorites можно следующим небольшим PR.

Toggle «Только здесь / Всё внутри» меняет query scope. Overview по умолчанию показывает children и непосредственные объекты; children contents не раскрываются все сразу. Global views дедуплицируют по UUID и не требуют посещать Container.

### Сравнение вариантов

Оценки качественные проектные, usability measurements не проводились.

| Критерий | A Today-first | B Context-first | C Evolutionary |
|---|---|---|---|
| Быстрый ввод | Лучший global capture; title -> Inbox | Очень хорош в открытом контексте; global capture нужен отдельно | Habit ввод сохраняется; Task capture добавится отдельным MVP |
| Основная навигация | День -> действие; Browse вторичен | Home context -> local action; Today страховка | Global Habits + необязательный Browse |
| 5–6 уровней | Не мешают Today, paths нужны лишь при организации | Выше цена переходов; favorites/search обязательны | Не мешают привычным отметкам, проверяются отдельно |
| Tasks + Habits в одном месте | Sections в Container overview, local filters | Центральная рабочая страница, сильнейший contextual UX | Общая Container page появляется постепенно |
| Global Tasks | Прямой entry через Browse/Today | Постоянный header shortcut | Следующий Task entry; пока отсутствует |
| Global Habits | Собственная вкладка, один переход | Отдельный shortcut, менее заметен | Home без изменения workflow |
| Today | Главный и наиболее естественный | Вторичный, всегда заметные deadlines | После Task MVP; не создавать пустой новый Today сейчас |
| Statistics | Global и local, separate domains | Context history/stats + global reports | Нынешние Reports сохраняются; ContainerReport позже |
| Новый пользователь | Понятен день, но нужны ясные нормы и deadlines | Нужно понять и выбрать контекст | Очень прост для Habit, task-first пользователь может не найти задачи |
| Цена реализации поверх текущего UI | Высокая: новые providers/shell/day rules | Высокая: новая home/contents/global страховка | Минимальная для Container Foundation/Browse; adapters всё равно обязательны |

**Выбор: C для первого прототипа.** A имеет смысл проверить следующим, когда Today обладает реальным содержимым. B — контроль сценария длительной работы внутри проекта. Все три использовать на одинаковой fixture; не считать выбор доказанной оптимальностью.

## 10. Маленькие PR для Foundation

Каждый PR проверяется узкими тестами; names новых тестов ниже — предлагаемые. Полный suite не обязателен. Production schema, package/application id, старые Entry/goal semantics и legacy remote protocol остаются неизменными на всём Foundation.

### PR1 — Container domain, memory foundation и инварианты

**Меняется:** только новый `core.containers` пакет commonMain/commonTest. Typed IDs, Container, HabitPlacement, ошибки, query/service/store contracts, injected clock/UUID generator, MemoryOrganizationStore и transactional OrganizationService. Forest, cycle validation, order, archive/unarchive, deleteEmpty, placement и revision history. HabitIdentityLookup stub в тестах. Независимые старые модели не редактируются.

**Предполагаемые файлы:** `Container.kt`, `HabitPlacement.kt`, `OrganizationContracts.kt`, `OrganizationService.kt`, `TreePolicy.kt`, `memory/MemoryOrganizationStore.kt`; `ContainerFoundationTest.kt`, `HabitPlacementFoundationTest.kt`. Не нужно механически создавать отдельный файл на каждый маленький value class.

**Тесты:** глубина >=6; move root/child/subtree; self/ancestor cycle; absent/deleted/archived parent; два одинаковых имени; UUID неизменен; duplicate operation/revision conflict; stable sibling reorder; local Habit order не меняет global order; archive ancestor/unarchive с отдельно archived child; запрет delete непустого; placement history и ancestor history; before-cutover unknown; rollback без revision/history drift. Проверить root physical scope один раз по set IDs, без дубликатов.

**Не меняется:** Habit, HabitBlock, SQLModelFactory, Constants, migrations, Android, sync, backup runtime.

**Готовность:** targeted JVM tests проходят; adapters/contracts отражают все перечисленные правила; никакой production wiring; diff ограничен новым пакетом и тестами. Команды: `:uhabits-core:jvmTest --tests "*ContainerFoundationTest" --tests "*HabitPlacementFoundationTest"`.

### PR2 — SQLite adapter на тестовой/изолированной DB

**Меняется:** SQLiteOrganizationStore, transaction runner и test-only/additive schema asset, без регистрации новой production migration и без изменения DATABASE_VERSION. SQL adapter разрешает Habit UUID -> existing local ID. Итеративные tree queries работают с текущим Android SQL adapter без обязательного CTE refactor.

**Тесты:** тот же repository/service contract на JVM SQLite; реально включённые FK; unknown Habit/container; delete RESTRICT; transaction failure между current/history/meta; reopen persistence; op idempotency; ancestor query/cycles; индексы/schema validation. Android adapter parity — targeted integration позже при включении experimental opener.

**Не меняется:** production opener/schema, legacy tables/queries, remote protocol, UI.

**Готовность:** memory/SQLite дают одинаковое поведение для contract fixtures, schema создаётся только в isolated DB, rollback полный. Узкие `*SQLiteOrganizationStoreTest` и `*OrganizationStoreContractTest`.

### PR3 — Read-only migration planner и executor для staging

**Меняется:** raw legacy inventory, LegacyContainerMigrationPlanner, persisted plan/manifest, staging executor/semantic validator. Вход v29 snapshot -> verified new copy. Old snapshot остаётся immutable. UI activation отсутствует.

**Тесты:** v29 active/archive/tombstone; extension missing/orphan; nullable assignments; same names; random/repaired default UUID; pending queue old UUID; partial crash/retry; before-cutover unknown; order preservation; checksum mismatch; byte/column comparison legacy rows. Для v25–v28 chains — existing migrations на staging и Foundation comparison относительно resulting v29 baseline.

**Не меняется:** production DB/version, HabitGoal/Entry semantics, sync queue/EntryOps; не вызывается repairDefaultBlockUuids или HabitList.update.

**Готовность:** нормальные fixtures мигрируют без потерь; invalid identity/refs блокируют activation и дают конкретные issues; re-run не создаёт вторую identity. Узкие `*LegacyContainerMigrationTest`, `*ContainerMigrationValidationTest`.

### PR4 — Experimental backup/restore/import contract

**Меняется:** version-aware validator и dataset routing, recovery manifest + relevant prefs, full experimental roundtrip, old DB -> experimental staging restore. Safe legacy merge-import with explicit placement choice; new Container merge-import пока явный отказ.

**Тесты:** exact new snapshot restore с history/map/tombstones; old snapshot normalize/migrate; missing tables/cycles/refs отказ до replacement; pending ops preserved; UUID collision plan; tombstone не resurrect; unsupported merge отказ; backup source file не изменяется. Targeted BackupManager/restore integration оправданы здесь: file replacement/recovery требуют Android проверки.

**Не меняется:** production backup/import behavior; CSV не становится full backup; imported same-name nodes не сливаются.

**Готовность:** для нового dataset есть реальный recovery path до activation; backup error не открывает непроверенную DB. Узкие core import tests + необходимые Android backup/restore tests.

### PR5 — Authority switch и compatibility seams, flag выключен

**Меняется:** HabitOrganizationFacade, Legacy/ContainerLocal mode, extension writer guard, Habit lifecycle placement, editor/management/group/sphere-filter/SKIP integration через typed selection. Старое поле blockId не используется как новый container ID. Org commands -> current/history single transaction, уведомления после commit.

**Тесты:** create/edit/delete/undo Habit; goal/name edit не меняет Container; новый child move не меняет Habit UUID/Entry/Goal/position; legacy block writes отсутствуют после cutover; root/current subtree filter, BY_SPHERE grouping и SKIP isolation; restore invalidates cache. Production mode сохраняет прежние запросы/команды.

**Не меняется:** current home/bottom navigation, per-Habit formulas, production dataset authority.

**Готовность:** inventory всех old writers/readers закрыт adapter или явным запретом в new mode; отсутствует dual-write; новый режим по умолчанию недоступен. Targeted core tests, для Android/resources — assembleDebug и visual QA адаптированных chooser/management screens, если они уже отображаются в test harness.

### PR6 — Opt-in experimental DatasetSession, изоляция и интеграционный gate

**Меняется:** отдельный DB filename/opener, DI providers, explicit experiment entry, validated copy selection, mode persisted. Hard sync gate до repair/bootstrap/network, capture guard для old outbox, отдельный prefs namespace/session lifecycle. Timer/widgets/reminder jobs не запускаются в эксперименте до dataset-aware поддержки. На входе сообщается независимость копии.

**Тесты:** все automatic/manual sync entry points делают ноль network calls и не меняют legacy cursor/queue; manual allowAfterReview не обходит gate; production sync сохраняется; DB выбор до DI; процесс kill/restart; switching закрывает старые jobs/caches; source DB checksum до/после эксперимента неизменен; active timer препятствует activation; Android SQL/FK parity; backup/restore new session.

**Не меняется:** production schema v29, реальная установленная база, timers/reminders/widgets production, package/application id, Supabase schema.

**Готовность:** можно безопасно открыть/сохранить эксперимент, вернуться к production, восстановить experimental backup; обязательные Android integration checks выполнены. Без этой проверки называть Foundation готовым к пользовательской activation нельзя.

### После Foundation: PR7 — Minimal Browse prototype (отдельный этап)

**Меняется:** roots/children/direct Habits, create/move/archive, UUID navigation, breadcrumb ancestors, search и только здесь/всё внутри. Entry из текущего Habit screen. Никаких Task/Focus/Container statistics placeholders.

**Тесты:** core contents/navigation state; assembleDebug; обязательная визуальная проверка на Android depth6, duplicate titles, Back vs Up, rotation/state restoration, font scaling, archive, light/dark/AMOLED. Проверить старую быструю Habit отметку.

**Не меняется:** домашний экран, upstream Habit semantics, sync protocol; Foundation data invariants используются без UI bypass.

**Готовность:** действия tree понятны, путь не теряется, Habit из deepest Container открывается и отмечается, пользователь возвращается в global Habits. Это проверка C; после неё отдельно прототипировать A/B с Task wireframes.

### Будущий отдельный release gate

Production schema activation и совместимая синхронизация не спрятаны в PR6/PR7. Они требуют отдельного согласованного запроса и проверенного protocol/dataset plan. Зависимость Foundation: PR1 -> PR2 -> PR3 -> PR4 -> PR5 -> PR6; PR7 после Foundation. PR1 можно реализовать сразу по приведённому контракту.

## 11. Основные риски

| Риск | Ограничение/контроль |
|---|---|
| Смена UUID default blocks после migration | Frozen legacy snapshot, sync hard gate, preserve read UUID, identity reconciliation позже |
| Незаметный dual-write через старый editor/manage/import | Explicit authority mode, typed facade, тест отсутствия old placement writes, complete seam inventory |
| Пропуск tombstones через findAll/loadRecords | Raw inventory всех строк, не UI-model migration |
| Уничтожение placements старым removeAll или REPLACE/cascade | RESTRICT FK и запрет generic hard reset в new dataset; lifecycle adapter |
| Quick_check есть, semantic migration неверна | Column comparison, UUID/map/FK/tree invariants и per-Habit report fixtures |
| Ложная историческая точность | Cutover baseline, unknown legacy, no synthetic sessions; Entry date не fact instant |
| Archive неожиданно гасит Habit/reminder | Container archive только Browse; Habit archive отдельный; UI объяснение |
| Старый sync пишет неполное placement и принимает unknown | Полная network/bootstrap/capture isolation экспериментального dataset |
| Одна DB copy использует production timer/prefs/jobs | DatasetSession/prefs namespace, timers/jobs blocked в первом эксперименте |
| CTE/transaction поведение различается JVM/Android | Iterative queries first, adapter parity integration, transaction ownership tests |
| Выросшая history/storage | История только organizational changes, не Entry snapshots; indexes/query plans; никаких closure/graph tables заранее |
| Глубокое дерево усложняет простую отметку | C first, global Habits, local pages/search/breadcrumb; depth не обязательный onboarding |

## 12. Решения до реализации и открытые вопросы

### Для PR1 принять следующие defaults

Это конкретный рекомендуемый контракт, а не список вопросов, который нужно заново проектировать во время кодинга:

1. Лес; parent=null; отсутствие Habit assignment допустимо; Inbox — virtual view.
2. UUID каждого существующего блока сохраняется буквально; rename/move/archive не меняют identity.
3. Separate Habit placement с FK на Habits; никаких Tasks/универсального Node.
4. Dense section-specific integer order, global Habit order независим.
5. Archive скрывает Browse-ветвь, не архивирует Habit автоматически; delete только empty soft tombstone; purge отсутствует.
6. История Container parent/metadata и Habit placement с первой organizational transaction; до baseline unknown; нет historical report по date-only Entry.
7. PR1 без SQL/production wiring; вся Foundation сначала local isolated copy.

Запрос «Реализуй PR1 Foundation» может считаться принятием этих defaults. Он не разрешает production migration, Tasks, redesign или sync protocol.

### До PR3–PR6 потребуются решения на конкретных данных

- Как показать и разрешить существующие orphan/deleted-block assignments; default — блокировать activation, не угадывать.
- Минимальный набор relevant preferences и encryption/auth policy recovery bundle; credentials по умолчанию не экспортируются.
- Явное согласие с тем, что experimental copy независима и её edits не merge обратно. Если нужен реальный production cutover, scope меняется и требуется отдельный release plan.
- Разрешать ли legacy merge-import placement overwrite; default — только explicit choice/preview.
- Поддерживать ли старый таймер в эксперименте до FocusSession; default — блокировать экспериментальные timer/jobs, production не менять.

### До полноценного продукта, но не блокеры PR1

- Пользовательское имя Container: для прототипа «Раздел», в коде Container.
- Финальная IA A/B/C и постоянная вкладка Browse; C — начальная рекомендация, A — следующий кандидат.
- Historical name policy: current names и возможность увидеть snapshot name; baseline names уже сохраняются.
- Будущая fact-context snapshot policy, attribution/timezone/day boundaries, исправления Entry vs factual work.
- Distributed cycle/conflict algorithm, server gate и migration epoch до первого remote publish.
- Cross-type manual ordering только если sections действительно неудобны пользователям.

## 13. Граница текущей проверки

Выполнены чтение ТЗ/исследований, архитектурных документов, targeted source inspection, поиск связей, сверка схемы 26–29 и Git состояния. Новая БД, migration, Android prototype и Supabase protocol не создавались. Gradle/runtime/visual checks не запускались: implementation отсутствует. План тестов выше — будущая обязательная проверка каждого PR, а не результаты уже выполненных тестов.

Основная рекомендация для следующего запроса: **PR1 — самостоятельный Container domain + memory foundation с явно зафиксированными инвариантами, без изменения старого Habit ядра и production schema.**
