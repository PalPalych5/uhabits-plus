# UHabit Next — canonical live handoff

> Этот файл является первым документом, который должен читать агент перед работой над UHabit Next.  
> Если фактическое состояние Git противоречит этому файлу, агент обязан проверить Git/код и обновить handoff, а не слепо доверять устаревшему статусу.

Last verified: **2026-10-03, Asia/Novosibirsk**. Maintainer: текущая работающая сессия Codex / Antigravity. Это живой статус, а не замена ТЗ, исследованиям или Foundation design.

**Canonical location:** `C:/Users/Pavel/source/repos/uhabits-plus/docs/UHABIT_NEXT_HANDOFF.md`. На этом компьютере все агенты, включая работающих в другом worktree, читают и обновляют именно этот файл. Канонические документы (UHABIT_NEXT_HANDOFF.md, UHabit_Next_Spec.md, uhabit-next-foundation-plan.md) зафиксированы в Git через исключения в .gitignore (!docs/...) и синхронизируются через commit/checkout/pull. Этот файл не меняет AGENTS.md или автоматический bootstrap harness: чтение должно быть включено в инструкции запуска агента.

## 1. Product direction

UHabit Next развивается из habit tracker в личную систему планирования и фактической деятельности:

- **Container** организует контекст.
- **Task** представляет конкретный результат.
- **Habit** представляет регулярную/количественную норму.
- **FocusSession** представляет зарегистрированную фактическую работу.
- **Contribution** связывает факт с нормой, сохраняя источник и предотвращая двойной учёт.
- **Today** представляет текущие действия и реальные обязательства.
- **Statistics** объясняет деятельность и прогресс.

> Powerful underneath, simple on the surface.

> Один реальный факт вводится один раз.

Дерево добровольно: пользователь простых Habit не обязан создавать проекты, задачи или правила. Container, Task, Habit и факт работы имеют разные жизненные циклы.

## 2. Canonical documents

| Документ | Роль |
|---|---|
| [UHabit_Next_Spec.md](UHabit_Next_Spec.md) | Продуктовая и архитектурная спецификация |
| [container-redesign-research-2026-10-03.md](container-redesign-research-2026-10-03.md) | Основное исследование идеи, конкурентов и ограничений |
| [container-code-audit-2026-10-03.md](container-code-audit-2026-10-03.md) | Аудит legacy кода и migration/sync risks на зафиксированном срезе |
| [container-commercial-analogues-2026-10-03.md](container-commercial-analogues-2026-10-03.md) | Коммерческие аналоги и границы доказательств |
| [container-open-source-analogues-2026-10-03.md](container-open-source-analogues-2026-10-03.md) | OSS/исторические аналоги |
| [uhabit-next-foundation-plan.md](uhabit-next-foundation-plan.md) | Конкретные domain/storage contracts, migration design, три IA и PR1–PR7. Канонический документ перенесён в `docs/` и версионируется в Git |
| [codex-project-context.md](codex-project-context.md), [architecture-uhabits.md](architecture-uhabits.md), [AGENTS.md](../AGENTS.md) | Контекст существующего приложения, карта кода и правила работы |

Приоритет при противоречиях: **(1) фактический Git/код → (2) явно принятые решения этого handoff → (3) UHabit_Next_Spec → (4) Foundation plan → (5) исследования**. Код определяет, что реализовано; accepted decisions определяют согласованную цель. Наличие старой реализации не отменяет будущий дизайн автоматически. Требования пользователя текущей задачи и применимые инструкции имеют приоритет. Исследования — контекст, не обязательный контракт; изменение согласованного решения фиксировать здесь с причиной.

Foundation plan перенесён в `docs/uhabit-next-foundation-plan.md` и добавлен в Git для равного доступа всех агентов и worktrees.

## 3. Accepted decisions

Приняты пользователем для текущего направления; повторно обсуждать только при новых ограничениях/доказательствах:

- Продолжаем существующий fork; полного rewrite, массового rename и смены package/application id нет. GPL/copyright сохраняются.
- Habit остаётся отдельным сильным доменом. Container — отдельный организационный домен; giant `Node` не создаётся. Tasks/Habits не объединяются в один тип.
- Организация — forest/tree: один parent, без системного root. Unassigned допустим; Inbox/«Без раздела» — view.
- Stable UUID сохраняются при rename/move/archive. Legacy local IDs, имена и цвета не являются переносимой identity.
- HabitBlocks не переименовываются напрямую. Migration additive: Containers + LegacyBlockMap + отдельный Habit placement; старые таблицы сохраняются до безопасного завершения перехода.
- Один placement source of truth после cutover; бесконечного dual-write нет. Legacy block_id не подменяется Container local ID.
- История Container parent/metadata и Habit placement начинается с перехода. Прошлое до cutover неизвестно; сессии/пути не выдумываются из старых Entry.
- Archive Container скрывает организационную ветвь, не архивирует Habit и не отменяет её reminders. Delete первого этапа — только empty soft delete; hard purge отсутствует.
- Section-specific Container/Habit order; global Habit.position сохраняется. Один общий cross-type rank пока не нужен.
- Planned date != deadline. FocusSession в будущем — самостоятельный факт; Contribution требует provenance и дедупликации.
- Graph/multiple parents и universal productivity score не нужны сейчас.
- Preferred первый UX prototype — **Evolutionary**: Habit-first сохраняется, Browse добавляется постепенно. Today-first — вероятное следующее направление после Tasks, ещё не финальное решение shell.
- Production schema/sync пока не менять. Foundation сначала проверяется изолированно на memory/test DB/staging copy; экспериментальный dataset не подключается к legacy sync.
- Форматы Entry, UNKNOWN/SKIP/explicit zero, numerical x1000, HabitGoal history/UUID и формулы не меняются в Foundation.

Изменение решения: указать старое/новое правило, причину, последствия для compatibility/migration/tests и подтверждённое основание согласования. Не переписывать Completed work log задним числом.

## 4. Roadmap

Статусы: `PLANNED`, `READY`, `IN_PROGRESS`, `BLOCKED`, `DONE`, `SUPERSEDED`. `READY` не означает, что агент уже получил разрешение начать; запуск должен соответствовать текущему запросу и Active work. Следующий implementation scope — PR3.

| Этап | Цель / входит | Специально не входит | Prerequisites | Status |
|---|---|---|---|---|
| Architecture preparation | Code audit, Container/storage/migration design, 3 IA, PR decomposition | Implementation, runtime proof | Spec + исследования + фактический код | DONE |
| PR1 — Container domain + memory foundation | Новый core.containers, contracts/service/memory, tree/placement/history, tests | SQL, Android, DI, sync, production wiring | Accepted decisions + PR1 contract; scope claim | DONE |
| PR2 — SQLite adapter on isolated DB | SQL adapter, transaction/schema/contract tests в изолированной БД | Production migration/version change | PR1 завершён, review/tests приняты; отдельное разрешение начать | DONE |
| PR3 — Legacy migration planner + staging migration | Raw inventory, map/placement baseline, validation, crash/retry | Production DB replacement, invented history | PR2; policy для обнаруженных legacy anomalies | DONE |
| PR4 — Experimental backup/restore | Full snapshot/manifest/roundtrip; version-aware import routing | Silent Container merge, production behavior change | PR3; recovery prefs/import policy | DONE |
| PR5 — Authority switch + compatibility facade | Placement authority, legacy writer guards, editor/group/filter/SKIP seams | Full redesign, dual-write | PR4 recovery path; inventory readers/writers | READY |
| PR6 — Isolated DatasetSession + sync/jobs isolation | Отдельная копия, DI/session lifecycle, hard sync/job gates | Remote protocol, production cutover | PR5; timer/prefs policy; обязательные integration checks | PLANNED |
| PR7 — Minimal Browse prototype | Roots/children/Habits, create/move/archive, breadcrumb/search | Tasks/Focus/new statistics, full shell redesign | PR6 Foundation validated; Android visual QA | PLANNED |
| Task MVP | Отдельный Task domain, title capture/Inbox, plan vs deadline, complete/undo | Recurrence engine, Focus/Contribution | Stable Container/Browse; отдельный Task contract | PLANNED |
| FocusSession | Durable standalone work fact и безопасная финализация | Выдуманные legacy sessions, произвольные rules | Task MVP; timer/attribution/recovery design | PLANNED |
| Contribution / provenance | Один явный source rule, breakdown/dedupe/corrections/undo | Пользовательский rules language, hidden double-write | Durable facts; manual/automatic projection contract | PLANNED |
| Container statistics | Отдельные time/outcomes/Habit metrics; historical/current mode | Universal productivity score | Проверенные facts/provenance/placement | PLANNED |
| Today-first UX evaluation | Сравнить A/B/C на реальных task/day сценариях | Автоматическая замена home без оценки | Task MVP и достаточно содержимого Today | PLANNED |
| Production migration / new sync protocol | Отдельный release design, compatibility gate, conflict/recovery checks | Скрытая активация внутри Foundation | Validated local stages; protocol/dataset/server gate; отдельное согласование | PLANNED |

Последние этапы отражают направление, а не разрешение реализовать весь roadmap одним запросом. Today-first evaluation может быть отдельно согласована раньше Container statistics после Task MVP.

## 5. Current state

```text
Architecture preparation: DONE
Implementation: PR1 DONE (commit 19087fd6), PR2 DONE (commit 4cbd6942fa21adab95d7f3138033da9c5ca06139), PR3 DONE (commit 58fac669414d232be804e412e0971b0399c146f6), PR4 DONE (commit f924d958d26bf3e297935a059383181b00d5eb97)
Coordination handoff: DONE
Current repository HEAD: f924d958d26bf3e297935a059383181b00d5eb97
Current branch: dev
Canonical checkout: C:/Users/Pavel/source/repos/uhabits-plus
Next implementation: PR5 — Authority switch + compatibility facade (READY)
Production DB schema: v29 unchanged (repository DATABASE_VERSION)
Production UI: unchanged
Production sync: unchanged
```

Git status основного checkout чистый, зафиксированы коммиты PR1 (`19087fd6`), PR2 (`4cbd6942`), PR3 (`58fac669`) и PR4 (`f924d958`). Реализованы: домен и память `core.containers`, SQLite адаптер с реальным FK enforcement, legacy migration planner + staging executor + validator, а также экспериментальный backup / restore / import routing с манифестом, SHA-256 чексуммами, staging restore и строгой изоляцией от production runtime. Покрыто 80 targeted tests в commonTest. Второй worktree `C:/Users/Pavel/.codex/worktrees/38f8/uhabits-plus` при проверке idle.

Runtime/device DB и live Supabase не проверялись: `v29 unchanged` подтверждает кодовый schema contract и отсутствие изменений этой сессии, а не инспекцию устройства/сервера. Нет выполненной Container migration, Foundation tests или нового UI.

## 6. Active work

| Agent | Work item | Branch/worktree | Status | Started | Expected touched areas | Last commit/result |
|---|---|---|---|---|---|---|
| none | none | none | IDLE | 2026-10-03 11:40 (Asia/Novosibirsk) | none | PR4 committed (f924d958), 80 tests passing |

Других зарегистрированных implementation работ нет. Доступный Codex research chat в worktree 38f8 при проверке idle. Состояние независимых Antigravity sessions автоматически не установлено: отсутствие записи не доказывает отсутствие работающего процесса.

Перед существенной работой агент обязан:

1. Прочитать Active work и проверить Git status/branch/worktree/recent commits.
2. Убедиться, что scope не занят; сверить доступные agent sessions и изменения в ожидаемых областях.
3. Записать здесь свою работу как `IN_PROGRESS`: agent/session identity, work item, branch/absolute worktree, start time, expected touched areas.
4. Непосредственно перед изменениями перечитать канонический файл и проверить, что claim сохранился и не появился конфликт.
5. Если scope пересекается — **не начинать изменения молча**: выбрать независимый scope или явно сообщить конфликт и согласовать разделение.

Handoff — coordination register, не атомарный mutex: одновременное чтение `none` не является эксклюзивной блокировкой. При одновременных claims остановить пересекающиеся изменения до согласования. Перед любой правкой handoff читать актуальный файл, сохранять чужие строки/решения и применять узкий patch; не перезаписывать целиком старой копией. Общие файлы (DI/build/migrations/handoff) тоже входят в scope.

Не удалять чужой claim только из-за старого timestamp; проверить owner/status. При паузе/блокере сохранить owner и причину; `BLOCKED` не освобождает scope автоматически. Независимый агент не начинает PR1 повторно и не открывает dependent PR2 до readiness/review/разрешения.

## 7. Next actions

1. PR1 (Container domain + memory foundation), PR2 (SQLite adapter on isolated DB), PR3 (Legacy migration planner + staging executor + semantic validator) и PR4 (Experimental backup / restore / import routing) успешно завершены и закоммичены (`f924d958`). Пройдено 80 targeted tests без сбоев.
2. Реализован и проверен полный recovery path: standalone backup с манифестом и SHA-256 чексуммами, staged restore с валидацией целостности, foreign keys и семантики, безопасная миграция legacy v29 backup на staging без модификации исходного файла in-place.
3. Доказано строгое разграничение: Container dataset merge-import явно отвергается как неподдерживаемый, а merge-import legacy привычек выполняется с явным указанием контейнера/unassigned и типизированными политиками разрешения коллизий UUID.
4. По следующему явному запросу пользователя взять в работу **PR5 — Authority switch + compatibility facade**, предварительно зарегистрировав scope в Active work.
5. Сохранять инварианты: production schema version v29 неизменна, production DB opener и migrations не трогать, production UI и production sync не менять.

## 8. PR1 contract

**Allowed scope:** новый `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/` и соответствующий commonTest пакет. Минимальные модели `Container`, `HabitPlacement`, typed IDs; OrganizationService; query/store/UnitOfWork contracts; injected clock/id generation; memory implementation. Существование Habit проверяется через interface/test stub, не переделку Habit domain.

Входит: forest/tree invariants; create/edit/move/reorder; cycle protection; archive/unarchive; deleteEmpty; Habit placement/local order; organization revision/history и baseline provenance; idempotent operation IDs и stale-revision rejection; rollback tests.

Правила:

- UUID неизменен; имена не уникальны; parent=null/root и unassigned допустимы.
- Move проверяется в mutation boundary; self/ancestor cycles, absent/deleted/effectively archived target отклоняются.
- Порядок dense integer отдельно по section; global Habit.position не меняется.
- Archive ancestor скрывает Browse branch, не переписывает descendant archive/Habit flags. Unarchive не оживляет отдельно archived children.
- Empty soft delete; active или archived содержимое препятствует delete. Identity/history сохраняются.
- История parent и placement хранится на одной revision; до cutover unknown. UNKNOWN_LEGACY assignment отличается от известного unassigned.
- Организационная история не является attribution старых Entry или Focus facts.

**PR1 НЕ меняет:** Habit; HabitBlock; SQL; migrations; DATABASE_VERSION; Android UI; DI; sync; backup runtime; Supabase; timer; Tasks; FocusSession. Кроме нового пакета/тестов допускается только обязательное обновление этого handoff; иные изменения требуют расширения согласованного scope.

**Definition of done:** targeted tests покрывают depth >=6, cycles/moves, stable UUID/duplicate names, sibling/local ordering, archive/delete, placement + ancestor history, unknown baseline, operation retry/revision conflict и rollback. Proposed test classes: `ContainerFoundationTest`, `HabitPlacementFoundationTest`. Команда после появления тестов:

```powershell
.\gradlew.bat :uhabits-core:jvmTest --tests "*ContainerFoundationTest" --tests "*HabitPlacementFoundationTest" --console=plain --quiet
```

Проверить git diff/stat/name-only и релевантный diff; пройти Session close protocol. Сборка/компиляция без этих тестов не означает DONE. Сейчас эти тесты не существуют и не запускались.

## 9. Open decisions

### Not blocking current work

- Финальное пользовательское название Container (prototype default: «Раздел»).
- Окончательный A/B/C shell и permanent Browse tab; первый prototype выбран Evolutionary.
- Future tags/secondary links, Task recurrence model.
- Focus fact context snapshots, attribution/day boundaries, historical label display.
- Distributed tree conflict algorithm и новый sync protocol/server gate.

### Blocking next stage

**Для PR1 blockers нет.** Ниже решения для последующих PR3–PR6, а не повод остановить текущий memory scope:

- PR3: политика обнаруженных orphan/deleted-block assignments и неоднозначных legacy identities. Safe default — диагностика/блок activation, без догадок и name-based merge.
- PR4: точный набор recovery preferences и безопасный manifest/credentials contract; merge-import placement overwrite policy. Default — explicit preview/choice; unsupported Container merge отказ.
- PR6: experimental timer/preferences/jobs behavior и согласие с независимостью dataset copy. Default — таймер/jobs копии blocked до dataset-aware интеграции; обратного автоматического merge в production нет.

Дефолты рекомендованы, но детали этих contracts ещё должны быть подтверждены перед соответствующим этапом. Новый blocker записывать с owner, затронутым stage и требуемым решением; будущие идеи не маркировать текущими blockers.

## 10. Completed work log

Append-only: добавлять короткую запись после значимой завершённой работы; при исправлении статуса добавлять correction entry, не менять прежнюю историю.

### 2026-10-03 — Architecture preparation

Agent: Codex; session `01a0ff8e-a5be-7731-b778-11076a059e70`.

Result:
- Изучены ТЗ/исследования и фактические code/storage/backup/import/sync seams.
- Спроектированы Container domain, storage/migration и compatibility boundary.
- Сравнены три Android IA; рекомендован Evolutionary prototype.
- Foundation разбит на PR1–PR6; PR7 выделен как следующий Browse этап.

Artifacts: [Foundation plan](C:/Users/Pavel/.codex/visualizations/2026/10/03/01a0ff8e-a5be-7731-b778-11076a059e70/uhabit-next-foundation-plan.md), архитектурный ответ в этой сессии.

Commit: **none (read-only repository/code audit)**. Runtime/migration/Android/Supabase проверки не выполнялись; эта запись не означает implementation DONE.

### 2026-10-03 — Canonical coordination handoff

Agent: Codex; session `01a0ff8e-a5be-7731-b778-11076a059e70`.

Result:
- Создан этот единственный canonical handoff: accepted decisions, roadmap/status, PR1 boundaries, claims/conflict rules и session protocols.
- Проверены HEAD/branch/worktrees, отсутствие Foundation implementation, наличие canonical documents и внешний путь Foundation plan.
- Проверены 12 разделов и Git status/diff; код/production schema/UI/sync не изменены, PR1 не начат.

Artifacts: `docs/UHABIT_NEXT_HANDOFF.md`.

Commit: **none (documentation only; файл игнорируется Git, не staged)**. Active scope закрыт; next implementation — PR1 по отдельному запросу пользователя. Gradle/runtime tests для создания Markdown не требуются и не запускались.

### 2026-10-03 — PR1 Container domain + memory foundation

Agent: Antigravity; session `b17e796e-eb7e-4101-8915-37a00e9fbefe`.

Result:
- Реализован новый пакет `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/` (модели `Container`, `HabitPlacement`, value classes, контракты и порты, `TreePolicy`, `MemoryOrganizationStore`, `OrganizationServiceImpl`).
- Реализована изоляция транзакций через снапшоты состояния в `MemoryOrganizationStore` с чистым rollback.
- Реализованы инварианты дерева (глубина >= 6, защита от циклов, archive/unarchive visibility, soft-delete emptiness checks, плотный порядок siblings и habit placements).
- Поддержаны идемпотентность по `opUuid` с валидацией payload и отказ при устаревшей ревизии (`expectedRevision`).
- Написаны и пройдены целевые тесты `ContainerFoundationTest` (11 тестов) и `HabitPlacementFoundationTest` (5 тестов) — всего 16 тестов.
- Проверена компиляция Kotlin JVM и Kotlin JS. Production схема, UI, Habit domain и sync не затронуты.

Artifacts: `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/**`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/**`.

Commit: `19087fd62fdf9a6e154c5a6d0b752d4b72f42762`.

### 2026-10-03 — PR2 SQLite adapter on isolated DB

Agent: Antigravity; session `b17e796e-eb7e-4101-8915-37a00e9fbefe`.

Result:
- Разработана изолированная DDL-схема `OrganizationSchema` (`OrganizationState`, `OrganizationChanges`, `Containers`, `HabitPlacements`, `ContainerHistory`, `HabitPlacementHistory`, `LegacyBlockMap` + индексы) с реальным foreign key enforcement (`PRAGMA foreign_keys = ON;`, `ON DELETE RESTRICT`).
- Реализован `SQLiteOrganizationStore`, поддерживающий транзакции (savepoint-based runner с `BEGIN IMMEDIATE`), разрешение UUID в локальные ID, сохранение и чтение Containers, HabitPlacements, историю ревизий и идемпотентность по `opUuid`.
- Создан параметризованный/наследуемый `OrganizationStoreContractTest`, доказавший 100% контрактный паритет между `MemoryOrganizationStore` и `SQLiteOrganizationStore` (15 тестов на каждый адаптер).
- Написан `SQLiteOrganizationStoreSpecificTest` (5 тестов), проверивший: реальный FK enforcement, защиту от каскадного удаления через `ON DELETE RESTRICT`, атомарный rollback всех таблиц (Containers, ContainerHistory, OrganizationChanges, OrganizationState) при сбое транзакции, выживание данных при закрытии и повторном открытии соединения к файловой БД, а также валидацию созданной схемы и индексов.
- В `OrganizationServiceImpl` упорядочена запись изменений (`tx.recordChange`) перед сохранением сущностей для соблюдения foreign key `revision REFERENCES OrganizationChanges(revision)`.
- Все 51 targeted tests пройдены успешно. Production `DATABASE_VERSION` (v29), production migrations, production DB opener, Habit/HabitBlock semantics, Android UI, DI, sync/Supabase и backup runtime не затронуты.

Artifacts: `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/sqlite/**`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/OrganizationStoreContractTest.kt`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/MemoryOrganizationStoreContractTest.kt`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/SQLiteOrganizationStoreContractTest.kt`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/SQLiteOrganizationStoreSpecificTest.kt`.

Commit: `4cbd6942fa21adab95d7f3138033da9c5ca06139`.

### 2026-10-03 — PR3 Legacy migration planner + staging executor + semantic validator

Agent: Antigravity; session `b17e796e-eb7e-4101-8915-37a00e9fbefe`.

Result:
- Разработан read-only `RawLegacyInventoryReader`, читающий исходные строки SQLite (HabitBlocks с tombstones/UUIDs, Habits с tombstones/UUIDs, HabitExtensions, HabitGoals history, Repetitions со всеми статусами и заметками, EntryOps, SyncQueue, AppSettings) и вычисляющий детерминированный SHA-256 хеш исходного состояния через чистый Kotlin `Sha256`.
- Реализован детерминированный планировщик `LegacyContainerMigrationPlanner`, формирующий `MigrationPlan` (1 HabitBlock -> 1 root Container с сохранением UUID, 1 Habit -> 1 HabitPlacement с dense local order, block_id = null -> MIGRATION_SNAPSHOT unassigned, missing extension -> UNKNOWN_LEGACY unassigned, baseline changes и organization state).
- Реализована типизированная иерархия блокирующих проблем `MigrationIssue` (Blank/Missing UUID, Duplicate UUID, MissingReferencedBlock, ActiveHabitReferencingTombstonedBlock, ChecksumMismatch) без молчаливого исправления аномалий.
- Реализован `LegacyContainerMigrationExecutor`, производящий миграцию строго на изолированной staging БД: проверка checksum снапшота, создание схемы `OrganizationSchema`, вставка baseline OrganizationChanges (rev 1), Containers, LegacyBlockMap, HabitPlacements, ContainerHistory, HabitPlacementHistory, OrganizationState. Поддержана полная транзакционная атомарность с откатом при сбое.
- Реализован семантический валидатор `LegacyContainerMigrationValidator`, проверяющий `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, уникальность UUID, соответствие кардинальностей, плотность порядка, а также побайтовую неизменность всех legacy таблиц (Habits, HabitBlocks, HabitExtensions, HabitGoals, Repetitions, EntryOps, SyncQueue, AppSettings) до и после миграции.
- Созданы комплексные семантические фикстуры `SemanticMigrationFixtures` и 16 тестовых сценариев в `LegacyContainerMigrationPlannerTest`, `LegacyContainerMigrationExecutorTest`, `LegacyContainerMigrationValidationTest`.
- Пройдены все 67 целевых тестов PR1 + PR2 + PR3 в `:uhabits-core:jvmTest`.
- Production `DATABASE_VERSION` (29), production migrations, production DB opener, Habit/HabitBlock semantics, Android UI, DI, sync/Supabase и backup runtime не затронуты.

Artifacts: `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/migration/**`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/migration/**`.

Commit: `58fac669414d232be804e412e0971b0399c146f6`.

### 2026-10-03 — PR4 Experimental backup / restore / import routing

Agent: Antigravity; session `b17e796e-eb7e-4101-8915-37a00e9fbefe`.

Result:
- Разработан формат полного backup экспериментального Container dataset с манифестом `ContainerBackupManifest` (формат 1, schema v29, foundation v1, dataset mode, UUID, канонический SHA-256 хеш, AppSettings, non-secret preferences).
- Реализована строгая санитизация и модель `RecoveryMetadata`, разделяющая database tables, dataset metadata, recoverable preferences и excluded sensitive secrets (пароли, auth токены, refresh токены, API ключи).
- Реализован роутер `ContainerBackupRouter` с классификацией датасетов: `ExperimentalContainer`, `LegacyProduction`, `UnsupportedNewer`, `Malformed` на основе SQLite `PRAGMA integrity_check`, структуры таблиц и манифеста (без слепого доверия `user_version`).
- Реализован `DatabaseSnapshotter` для типобезопасного создания схемы и копирования строк всех legacy и organization таблиц без искажения типов и с сохранением целостности.
- Реализован сервис полного backup `ContainerBackupService` и пошаговый сервис восстановления `ContainerRestoreService`: staging copy -> manifest/integrity/FK validation -> semantic validation (дерево без циклов, кардинальность HabitPlacements, целостность истории) -> атомарная замена целевой БД.
- Поддержан путь миграции чистого legacy v29 backup на staging copy через pipeline PR3 без модификации исходного файла in-place.
- Реализован импортер `LegacyHabitMergeImporter`: merge-import Container dataset явно отвергается как `Unsupported`, а merge-import legacy Habit выполняется с явной политикой размещения (`SpecificContainer` или `Unassigned`) и типизированной защитой от коллизий UUID (`REJECT_ON_CONFLICT` или `GENERATE_NEW_UUID`).
- Создано 5 тестовых наборов в `commonTest/kotlin/.../backup/`: `ContainerBackupManifestTest` (3 теста), `ContainerBackupRestoreTest` (1 тест), `LegacyToExperimentalRestoreTest` (1 тест), `InvalidBackupRejectionTest` (4 теста), `ContainerMergeImportTest` (5 тестов) — всего 14 тестов.
- Все 80 targeted tests PR1–PR4 в `:uhabits-core:jvmTest` пройдены успешно без сбоев.
- Production `DATABASE_VERSION` (29), production migrations, production DB opener, Habit/HabitBlock semantics, Android UI, DI, sync/Supabase и backup runtime не затронуты.

Artifacts: `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/backup/**`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/backup/**`.

Commit: `f924d958d26bf3e297935a059383181b00d5eb97`.

## 11. Session close protocol

В конце любой значимой Codex/Antigravity-сессии, до финального отчёта:

1. Проверить Git status/diff/stat/name-only в своём checkout; проверить состояние канонического handoff.
2. Обновить Current state: фактический HEAD, branch/dataset/stage и границы проверки.
3. Обновить свою строку Active work; завершённый scope снять после записи результата. Чужие claims сохранить.
4. Обновить Next actions с зависимостями и разрешённым следующим scope.
5. Если этап завершён, обновить Roadmap; при partial/blocker не ставить DONE.
6. Добавить короткую append-only запись Completed work log (включая partial результат и remaining blocker, когда сессия заканчивается незавершённой).
7. Указать фактический commit SHA или `none`; не подменять commit номером предполагаемого PR.
8. Записать новые accepted decisions с причиной/основанием либо реальные blockers.
9. Не объявлять DONE, если обязательные для этапа tests/checks не выполнены; явно отделять code review, build, migration proof, visual QA и live sync.

Это часть definition of done дальнейших UHabit Next задач. Если за время работы handoff изменил другой агент, объединить обновления точечно; conflicting accepted decisions сначала согласовать.

## 12. New-session bootstrap

```text
Before doing any work on UHabit Next:

1. Read docs/UHABIT_NEXT_HANDOFF.md completely.
2. Read the canonical documents referenced for your current task.
3. Inspect current git status, branch/worktree and recent commits.
4. Compare actual repository state with Current state / Active work.
5. If handoff is stale, update it before proceeding.
6. Do not duplicate or overlap active work owned by another agent.
7. Work only on the next agreed scope.
8. Update this handoff before ending the session.
```

On this computer, resolve the shared canonical handoff in the primary checkout before step 1 when working from another worktree. The bootstrap does not authorize work beyond the human's current request.
