# UHabit Next — canonical live handoff

> Этот файл является первым документом, который должен читать агент перед работой над UHabit Next.  
> Если фактическое состояние Git противоречит этому файлу, агент обязан проверить Git/код и обновить handoff, а не слепо доверять устаревшему статусу.

Last verified: **2026-10-05, Asia/Novosibirsk**. Maintainer: текущая работающая сессия Codex / Antigravity. Это живой статус, а не замена ТЗ, исследованиям или Foundation design.

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
| [UHABIT_TASKS_IA.md](UHABIT_TASKS_IA.md) | Принятая Tasks IA: domains, adaptive shell, tree, representations, dates and scope; canonical for Codex/Antigravity |
| [container-redesign-research-2026-10-03.md](container-redesign-research-2026-10-03.md) | Основное исследование идеи, конкурентов и ограничений |
| [container-code-audit-2026-10-03.md](container-code-audit-2026-10-03.md) | Аудит legacy кода и migration/sync risks на зафиксированном срезе |
| [container-commercial-analogues-2026-10-03.md](container-commercial-analogues-2026-10-03.md) | Коммерческие аналоги и границы доказательств |
| [container-open-source-analogues-2026-10-03.md](container-open-source-analogues-2026-10-03.md) | OSS/исторические аналоги |
| [uhabit-next-foundation-plan.md](uhabit-next-foundation-plan.md) | Конкретные domain/storage contracts, migration design, три IA и PR1–PR7. Канонический документ перенесён в `docs/` и версионируется в Git |
| [codex-project-context.md](codex-project-context.md), [architecture-uhabits.md](architecture-uhabits.md), [AGENTS.md](../AGENTS.md) | Контекст существующего приложения, карта кода и правила работы |

Приоритет при противоречиях: **(1) фактический Git/код → (2) явно принятые решения этого handoff и UHABIT_TASKS_IA → (3) UHabit_Next_Spec → (4) Foundation plan → (5) исследования**. Код определяет, что реализовано; accepted decisions определяют согласованную цель. Наличие старой реализации не отменяет будущий дизайн автоматически. Требования пользователя текущей задачи и применимые инструкции имеют приоритет. Исследования — контекст, не обязательный контракт; изменение согласованного решения фиксировать здесь с причиной.

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
- Форматы Entry, UNKNOWN/SKIP/explicit zero, numerical x1000, HabitGoal history/UUID и формулы не меняются в Foundation.

### Accepted product direction (Product Pivot & Workspaces):
Эти исторические shell решения уточнены PR10 ниже: Settings остаётся secondary entry, а не четвёртой постоянной bottom destination.
- **Разделение рабочих пространств (Workspaces)**:
  - `Workspace 1: Habits` (Привычки)
  - `Workspace 2: Tasks` (Задачи)
  - `Workspace 3: Statistics` (Статистика)
  - `Workspace 4: Settings` (Настройки)
- **Habits сохраняют существующий UHabit visual/interaction model**:
  - `HabitCardListView`, `HabitCardView`, календарь, отметки/числовой ввод, фильтры, сортировка и детальные экраны остаются привычными и чистыми;
  - Основной экран Habit — не дерево; никаких принудительных Containers между привычками.
- **Tasks получают отдельный task-manager UX**:
  - Чистый, быстрый мобильный таск-менеджер (уровня TickTick / Todoist);
  - Smart Views: `Сегодня`, `Входящие`, `Предстоящие`, `Все задачи`, `Завершённые`;
  - Задачи группируются по датам и контекстам без тяжелых рамочных карточек.
- **Роль Containers**:
  - Containers используются прежде всего для организации Tasks и будущих сквозных отчётов;
  - Habit также может иметь Container placement на уровне модели, но основной Habit screen не обязан показывать иерархию;
  - Смешанный вид Habits + Tasks не является главным экраном;
  - Общий Container Overview / cross-domain аналитика возможна позже как отдельная поверхность.
- **Критический семантический инвариант**:
  - **`Planned date != Deadline`**:
    - `plannedDate`: когда планируется заняться задачей (прошедшая дата при невыполнении = пропущенный план / требует перепланирования, но НЕ hard overdue);
    - `deadline`: момент объективной просрочки (просроченный дедлайн = hard overdue);
  - **`Task completion != work time`**: завершение задачи ставит `completedAt`, но НЕ создаёт FocusSession и НЕ изменяет Habit автоматически.

### PR9.1 accepted UX decisions (2026-10-04)
- Habits и Tasks остаются раздельными рабочими инструментами. Container — контекст/фильтр Tasks; общий обзор позже.
- Inbox = незавершённые root Tasks с `containerId == null`. Задача своего раздела без дат — незапланированная, а не входящая.
- Smart Views выбираются через компактный dropdown рядом с фильтром разделов.
- Task Detail — live editor: свойства сразу, текст с debounce/flush при закрытии, Delete в overflow.
- Quick Add раскрывает план/раздел/дедлайн. Recurrence, FocusSession и новый Container redesign вне PR9.1.

### Tasks visual reset requested (2026-10-04)
Исторический design-only scope завершён и заменён разрешением native PR10 от 2026-10-05; запрет Android изменений из этого этапа больше не действует.
- PR9/PR9.1 domain/storage and mechanics remain; current Tasks presentation is a temporary prototype, not an approved final design.
- Next scope is design only: five-app mobile benchmark, real UHabit token audit, three high-fidelity concepts, comparison/recommendation, then STOP for user visual review.
- No further production Kotlin/XML changes, Task features, recurrence, FocusSession, dashboard or incremental polish in this scope.

### Rejected as primary UX:
- `UnifiedContainerHomeView` как основной Habit screen;
- Корневой экран «Разделы» вместо привычного списка привычек;
- Containers inline между Habit rows;
- Обязательная навигация через Living Context для ежедневного чекина привычек.
*(Код `UnifiedContainerHomeView` и наработки PR8 переносятся во внутренние developer/experimental настройки и не удаляются).*

Изменение решения: указать старое/новое правило, причину, последствия для compatibility/migration/tests и подтверждённое основание согласования. Не переписывать Completed work log задним числом.

## 4. Roadmap

### PR10 accepted IA and native authorization (2026-10-05)

- `IA_APPROVED / VISUAL_LANGUAGE_NOT_FINAL / NATIVE_IMPLEMENTATION_ALLOWED`. Полный контракт: [UHABIT_TASKS_IA.md](UHABIT_TASKS_IA.md).
- Phone: Habits / Tasks / Statistics bottom workspaces; Settings secondary; Tasks modal drawer со smart views и Containers.
- Wide: persistent sidebar; тот же navigation state. Habits screen сохраняется; Containers не возвращаются в Habit list.
- Drawer ограничивает visual depth тремя уровнями, затем local tree context с возвратом к parent.
- List и Calendar — representations выбранного smart view / Container. Inbox = incomplete root Task с `containerId == null`; plan и deadline независимы.
- Эти решения заменяют старые dropdown/Browse shell варианты. Новые HTML concepts не нужны; визуал уточняется на Android после review. PR11 не разрешён автоматически.

Статусы: `PLANNED`, `READY`, `IN_PROGRESS`, `BLOCKED`, `DONE`, `SUPERSEDED`, `VISUAL_REVIEW_PENDING`. Текущий согласованный и разрешённый пользователем scope — PR10 Native Adaptive Tasks Shell; дальнейший этап требует отдельного запроса.

| Этап | Цель / входит | Специально не входит | Prerequisites | Status |
|---|---|---|---|---|
| Architecture preparation | Code audit, Container/storage/migration design, 3 IA, PR decomposition | Implementation, runtime proof | Spec + исследования + фактический код | DONE |
| PR1 — Container domain + memory foundation | Новый core.containers, contracts/service/memory, tree/placement/history, tests | SQL, Android, DI, sync, production wiring | Accepted decisions + PR1 contract; scope claim | DONE |
| PR2 — SQLite adapter on isolated DB | SQL adapter, transaction/schema/contract tests в изолированной БД | Production migration/version change | PR1 завершён, review/tests приняты; отдельное разрешение начать | DONE |
| PR3 — Legacy migration planner + staging migration | Raw inventory, map/placement baseline, validation, crash/retry | Production DB replacement, invented history | PR2; policy для обнаруженных legacy anomalies | DONE |
| PR4 — Experimental backup/restore | Full snapshot/manifest/roundtrip; version-aware import routing | Silent Container merge, production behavior change | PR3; recovery prefs/import policy | DONE |
| PR5 — Authority switch + compatibility facade | Placement authority, legacy writer guards, editor/group/filter/SKIP seams | Full redesign, dual-write | PR4 recovery path; inventory readers/writers | DONE |
| PR6 — Isolated DatasetSession + sync/jobs isolation | Отдельная копия, DI/session lifecycle, hard sync/job gates | Remote protocol, production cutover | PR5; timer/prefs policy; обязательные integration checks | DONE |
| PR7 — Minimal Browse prototype | Roots/children/Habits, create/move/archive, breadcrumb/search | Tasks/Focus/new statistics, full shell redesign | PR6 Foundation validated; Android visual QA | DONE |
| PR8 — Unified Hierarchical Home + Visual Identity | Единый экран в ListHabitsFragment, inline-раскрытие + drill-down, 196 Lucide иконок, HabitVisualIdentity | Изменение flat-списка в LEGACY_PRODUCTION, Tasks | PR7 функционально принят; visual UX переосмыслен | DONE |
| PR8·Spatial — Living Context Native Slice | Открытая вёрстка (Zero cards), 2dp rail у раскрытого, fallback без иконки, Subtle Morph ~240ms, Zero-card Focused | Tasks, Today, Overview, new stats, schema v29 | Завершён как эксперимент; основной UX отклонён в пользу воркспейсов | DONE |
| PR9.1 — Tasks UX polish & visual reset | Strict Inbox, dropdown views, autosave editor, zero-card 60dp rows, plain metadata text, docked Quick Add, live Task Detail sheet, empty states | Recurrence, FocusSession, Container redesign, production migration/sync | PR9 + user feedback | DONE |
| PR10 — Native Adaptive Tasks Shell | Phone 3 workspaces + Tasks drawer; wide sidebar; bounded tree; List/Calendar shared context; existing detail/Quick Add; native QA | New domain/backend/schema/sync, Habit/Statistics redesign, PR11 | IA approved; native implementation and QA complete; user visual review required | VISUAL_REVIEW_PENDING |
| FocusSession | Durable standalone work fact и безопасная финализация | Выдуманные legacy sessions, произвольные rules | Task MVP; timer/attribution/recovery design | PLANNED |
| Contribution / provenance | Один явный source rule, breakdown/dedupe/corrections/undo | Пользовательский rules language, hidden double-write | Durable facts; manual/automatic projection contract | PLANNED |
| Container statistics | Отдельные time/outcomes/Habit metrics; historical/current mode | Universal productivity score | Проверенные facts/provenance/placement | PLANNED |
| Today-first UX evaluation | Сравнить A/B/C на реальных task/day сценариях | Автоматическая замена home без оценки | Task MVP и достаточно содержимого Today | PLANNED |
| Production migration / new sync protocol | Отдельный release design, compatibility gate, conflict/recovery checks | Скрытая активация внутри Foundation | Validated local stages; protocol/dataset/server gate; отдельное согласование | PLANNED |

Последние этапы отражают направление, а не разрешение реализовать весь roadmap одним запросом. Today-first evaluation может быть отдельно согласована раньше Container statistics после Task MVP.

## 5. Current state

```text
Architecture preparation: DONE
Implementation: PR1 DONE (commit 19087fd6), PR2 DONE (commit 4cbd6942fa21adab95d7f3138033da9c5ca06139), PR3 DONE (commit 58fac669414d232be804e412e0971b0399c146f6), PR4 DONE (commit f924d958d26bf3e297935a059383181b00d5eb97), PR5 DONE (commit 51dd5bac1f2c9fa08766206b6e5ec9e73518c636), PR6 DONE (commit f4073570776b25aa17dfa17bc671b569bf8d40a2), PR7 DONE (commit 620212ec), PR8 DONE (commit c5d44788), PR8 Verification DONE (commit dc3f0acf), PR8·Spatial Native Slice DONE (commit d847b434f), PR9 DONE (commit 68538527e), PR9.2 Tasks Visual Reset DONE
Product Pivot: Habits/Tasks separate workspaces, Habit UI restored, Tasks MVP fully validated
Current branch: dev; verified code/evidence HEAD 629b3be13 (PR10); documentation-only closure may follow
Canonical checkout: C:/Users/Pavel/source/repos/uhabits-plus
Tasks IA: APPROVED
Adaptive App Shell: APPROVED
Visual language: ITERATING
Native Android implementation: COMPLETE_FOR_REVIEW / PR10 VISUAL_REVIEW_PENDING
Active implementation: none; STOP for user native visual review
Documentation/IA commit: 5c89f0a2e
PR10 implementation/evidence commit: 629b3be13
Checks: APK build + 21 focused tests + emulator three widths/themes + process/recreation + isolation/restoration PASS
Production DB schema: v29 unchanged (repository DATABASE_VERSION)
Production UI: unchanged (mode default is LEGACY)
Production sync: unchanged (disabled in CONTAINER_LOCAL mode)
```

PR10 реализован и зафиксирован локально. Experimental phone: `Привычки | Задачи | Статистика`, Settings secondary; Tasks drawer; от 600dp persistent sidebar. List/Calendar используют один navigation context. Habits сохраняет классический UI. [Native QA report](../pr10_artifacts/REPORT.md), [gallery/video](../pr10_artifacts/index.html), [evidence](../pr10_artifacts/evidence.json). Calendar MVP и wide detail sheet требуют visual review; окончательный visual language не утверждён. No push / GitHub PR publication. Pre-existing root A/B spike, pr91/pr92 files and local QA backups preserved; backups/preferences excluded from Git.

## 6. Active work
 
| Agent | Work item | Branch/worktree | Status | Started | Expected touched areas | Last commit/result |
|---|---|---|---|---|---|---|
| none | none | dev / primary | IDLE | 2026-10-05 | none | PR10 code/evidence committed 629b3be13; native QA passed; STOP for user visual review. |

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

1. STOP: пользователь просматривает реальные Android [screenshots/video](../pr10_artifacts/index.html) и [QA report](../pr10_artifacts/REPORT.md). Не начинать PR11 и не делать новый HTML concept.
2. Следующий агент читает этот handoff и docs/UHABIT_TASKS_IA.md; работает только по конкретному результату user native review. IA не пересматривается без нового явного решения пользователя.
3. После нового согласования можно уточнять native visual language. Calendar month-cell titles, richer phone agenda и wide detail pane остаются visual/future scope, не утверждённой новой задачей.

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
- A/B/C shell superseded: адаптивная IA принята в UHABIT_TASKS_IA.md; phone bottom workspaces + Tasks drawer, wide persistent sidebar.
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

### 2026-10-03 — PR6 Isolated DatasetSession + runtime isolation

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- Разработана модель активной сессии данных `DatasetSession` с режимами `LEGACY_PRODUCTION` и `CONTAINER_LOCAL_EXPERIMENTAL`, типизированными возможностями `DatasetCapabilities` и метаданными `ExperimentalDatasetMetadata`.
- Реализована строгая физическая изоляция базы данных: экспериментальный датасет размещается в отдельном SQLite-файле `uhabits-container-experimental.db` без изменения схемы v29 production DB.
- Реализован менеджер жизненного цикла сессий `DatasetSessionManager`:
  - Безопасное создание эксперимента из snapshot production DB через конвейер PR3 миграции и PR4 валидации;
  - Защита от потери таймера: вход в эксперимент блокируется при активном production timer (`pref_timer_is_running`);
  - Верификация при старте (`quick_check`, проверка схемы, соответствие UUID/mode) с автоматическим безопасным откатом в `LEGACY_PRODUCTION` и фиксацией ошибки при обнаружении повреждений;
  - Возврат в production без автоматического слияния данных: экспериментальная копия сохраняется отдельно, production DB остаётся в неизменном виде.
- Изоляция DI графа и компонентов: `HabitsApplication.rebuildComponent` полностью утилизирует старый граф (`SyncCoordinator.dispose()`, `TimerSessionManager.dispose()`, закрытие соединения с SQLite) и инициализирует чистый граф для активной сессии.
- Изоляция настроек: `SharedPreferencesStorage` переключается на namespaced storage `uhabits_prefs_experimental_<datasetId>` для экспериментальной сессии, оставляя дефолтные preferences незатронутыми.
- Hard barrier сетевой синхронизации (0 remote calls):
  - `DisabledSyncBackend` выбрасывает `IllegalStateException` при любых попытках удалённого обращения (`signIn`, `signOut`, `refreshSession`, `pushChanges`, `pullChanges`);
  - `SyncCoordinator` блокирует все точки входа (`runSync` возвращает Failure/Skipped `sync_disabled_in_session`, отключает auto-sync слушатели, блокирует фоновые воркеры и восстановление block UUIDs).
- Изоляция таймеров, напоминаний и виджетов:
  - `TimerSessionManager` блокирует запуск и модификацию таймера в экспериментальном режиме;
  - `ReminderReceiver` и `WidgetReceiver` проверяют capabilities активной сессии и отбрасывают интенты при отключении возможностей.
- Внутренний feature gate: в секцию Developer настроек добавлен переключатель режима с диалогами подтверждения, разъясняющими изоляцию, отсутствие облачной синхронизации и отдельное хранение базы.
- Написаны тесты:
  - 6 комплексных тестов в `DatasetSessionTest` (контракты сессий, резолвер, независимость баз с проверкой SHA-256 хеша production DB до и после экспериментальных мутаций, запрет dual-write, циклы переключения);
  - 2 интеграционных теста в `SyncCoordinatorTest` (полная блокировка sync в experimental сессии, исключения в `DisabledSyncBackend`).
- Пройдены все 104 теста PR1–PR6 в `:uhabits-core:jvmTest` (479 тестов core всего), 12 тестов `SyncCoordinatorTest` в `:uhabits-android:testDebugUnitTest`, компиляция `:uhabits-android:compileDebugKotlin` и полная сборка `:uhabits-android:assembleDebug`.
- Production `DATABASE_VERSION` (29) не изменялся, production default mode остаётся `LEGACY`.

Artifacts: `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/session/**`, `uhabits-android/src/main/java/org/isoron/uhabits/session/**`, `uhabits-android/src/main/java/org/isoron/uhabits/inject/**`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/session/**`, `uhabits-android/src/test/java/org/isoron/uhabits/sync/SyncCoordinatorTest.kt`.

Commit: `f4073570776b25aa17dfa17bc671b569bf8d40a2`.

### 2026-10-03 — PR5 Authority switch + compatibility facade

Agent: Antigravity; session `8ab1214b-5966-4978-b72a-158ed05dfa6d`.

Result:
- Разработан фасад `HabitOrganizationFacade` с режимами `OrganizationAuthorityMode.LEGACY` (по умолчанию) и `CONTAINER_LOCAL`.
- Обеспечен строгий принцип Single Authority без dual-write: в режиме `CONTAINER_LOCAL` авторитетным источником организации являются исключительно `Containers` и `HabitPlacements`, в то время как `HabitExtensions.block_id` замораживается как legacy snapshot.
- Реализованы гарды от несанкционированной записи: `HabitBlockRepository` выбрасывает `IllegalStateException` при попытке модификации блоков в `CONTAINER_LOCAL`; `HabitExtensionRepository.upsert()` сохраняет существующий `block_id` без перезаписи; `SQLiteHabitList.removeAll()` запрещён.
- Адаптирован жизненный цикл привычек: `CreateHabitCommand` и `EditHabitCommand` поддерживают `initialContainerId`, `targetContainerId` и явный `changeContainer`; создание и редактирование атрибутов привычки (название, цель, напоминания и т.д.) не сбрасывают placement; удаление привычки через `DeleteHabitsCommand` сохраняет placement history и tombstone без hard purge.
- UI-интеграция: `HabitCardListView` использует `facade.getRootGroupKey()` для группировки `BY_SPHERE` через ancestor traversal дерева контейнеров без нарушения глобального порядка; `EditHabitActivity` адаптирует выбор сферы под древовидный выбор контейнера с путями и Unassigned; диалог `SkipDayDialogController` изолирует выбор root контейнера; статистика `StatisticsReportStateBuilder` фильтрует привычки через `allowedHabitUuids`.
- Изоляция sync: `SyncCoordinator` блокирует синхронизацию (`syncReadyReason = SYNC_DISABLED`, пропуск починки block UUIDs) в режиме `CONTAINER_LOCAL`; `SyncManager` подавляет постановку событий блоков в очередь.
- Написаны тесты: `HabitOrganizationFacadeTest` (7 тестов) и `OrganizationAuthorityIntegrationTest` (11 комплексных тестов на SQLite с проверкой сохранения истории, глубины >= 4, блокировок dual-write и изоляции bulk/статистики) — суммарно 18 тестов.
- Все 18 тестов PR5 и все 80 тестов PR1–PR4 (суммарно 98 тестов) успешно пройдены в `:uhabits-core:jvmTest`.
- Проверена компиляция Kotlin JVM, Android Kotlin (`compileDebugKotlin`) и успешная сборка debug APK (`assembleDebug`).
- Production `DATABASE_VERSION` (29) не изменялся, production default mode остаётся `LEGACY`.

Artifacts: `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/facade/**`, `uhabits-android/src/main/java/org/isoron/uhabits/**`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/**`.

Commit: `51dd5bac1f2c9fa08766206b6e5ec9e73518c636`.

### 2026-10-03 — PR6 Android runtime smoke-test and session lifecycle fixes

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- Проведён всесторонний runtime smoke-test PR6 на Android-эмуляторе (Pixel 6, Android 17 / API 37, debug build `org.isoron.uhabits.plus`):
  1. **Production baseline**: приложение успешно запускается в `LEGACY_PRODUCTION`, привычки отображаются, отметка чеклиста работает, редактирование привычки доступно, Reports открываются, экран Settings функционирует, sphere UI в норме, таймер доступен, sync/widgets/reminders не заблокированы.
  2. **Timer preflight guard**: попытка перехода в эксперимент при работающем секундомере штатно блокируется Toast-сообщением («Нельзя перейти в эксперимент при работающем таймере»), таймер продолжает работу без сброса и потерь.
  3. **Создание эксперимента**: диалог разработчика с предупреждениями (отдельная копия, 0 сетевых вызовов, отключение таймера/виджетов, отсутствие авто-слияния) успешно создаёт `uhabits-container-experimental.db` (1.9 MB) через PR3 migration + PR4 validation. В эксперименте 27 привычек, 17565 записей, 8 контейнеров, 27 размещений.
  4. **Исправление IntroActivity & Developer Mode**: в `initializeExperimentalPreferences` явно установлен `pref_first_run = false` (устранено появление визарда первого запуска при переходе в эксперимент) и добавлен `pref_developer` в список переносимых настроек.
  5. **Изоляция изменений**:
     - В эксперименте выполнены мутации: переименование привычки (`Drink WaterDrink Pure Water`), добавление записи (`Take Vitamins`, 17566), смена сферы на `Саморазвитие` (контейнер 8), создание новой привычки `Experimental Habit 99` (28 привычек).
     - Выполнен возврат в production (`LEGACY_PRODUCTION`): подтверждено, что production DB `uhabits.db` осталась побайтово неизменной (SHA-256 `d36a3f9777c892c4572ee1f1cbbdb24faff4e207c8f9f828ea4b9c68959d9ee2`), количество привычек = 27, количество записей = 17565, сфера привычки `Drink Water` = `Тело`, новой привычки нет.
  6. **Повторный вход (Re-entry)**:
     - Обнаружено и исправлено: ранее при повторном входе база создавалась заново из snapshot. Добавлены методы `DatasetSessionManager.hasExistingExperimentalDataset()` и `activateExistingExperiment()`, обновлён диалог в `SettingsFragment`.
     - Проверен повторный вход: открывается существующий датасет с сохранением всех сделанных в нём мутаций (UUID `dataset-e74bfd8d3c31936b`, 28 привычек, `Experimental Habit 99`, сфера `Саморазвитие`) без дублирования копий.
  7. **Process death / restart**:
     - При активном production: force stop -> старт открывает production DB.
     - При активном эксперименте: force stop -> старт открывает валидированную экспериментальную DB.
  8. **Sync isolation**: подтверждено 0 сетевых вызовов при ручном вызове Sync, смене настроек, bootstrap и фоновых воркерах (`BLOCKED_EXPERIMENTAL`).
  9. **Timer / Reminder / Widget guards**: в эксперименте таймер и виджеты/напоминания полностью подавляют мутации; после возврата в production секундомер работает штатно (`00:10` -> reset).
  10. **Corrupted experiment fallback**: при искусственном повреждении экспериментальной БД (удаление `OrganizationState`) приложение при старте безопасно откатывается в production DB `uhabits.db` (SHA-256 неизменен) и отображает пользователю понятный диалог об ошибке валидации через `consumeStartupError()`.
- Все 104 целевых теста PR1–PR6 в `:uhabits-core:jvmTest` и тесты в `:uhabits-android:testDebugUnitTest` пройдены успешно (exit code 0). Сборка `assembleDebug` успешна.

Artifacts: `uhabits-android/src/main/java/org/isoron/uhabits/session/DatasetSessionManager.kt`, `uhabits-android/src/main/java/org/isoron/uhabits/activities/settings/SettingsFragment.kt`, `uhabits-android/src/main/java/org/isoron/uhabits/activities/main/MainActivity.kt`.

### 2026-10-03 — PR7 Minimal Browse prototype for Containers UI

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- Реализован минимальный UX-прототип Browse для работы с Containers в режиме `CONTAINER_LOCAL_EXPERIMENTAL`:
  - Никаких Tasks, Focus, Contributions и дополнительных вкладок (Tasks/Focus/Stats); строгий принцип «Powerful underneath, simple on the surface».
  - Строгий нейминг: повсеместно используются термины «Раздел» / «Подраздел» (без «Папка» / Folder).
  - Архитектурный фасад в `uhabits-core`: открыты read queries (`containerQueries`, `habitPlacementQueries`) и сервис мутаций `organizationService` в `HabitOrganizationFacade`.
  - Core navigation tests: реализован `ContainersBrowseNavigationTest` (8 тестов, покрывающих BrowseRoot, ContainerDetail, иерархические breadcrumbs, Ancestors bottom sheet, переключатель скоупа `HERE` vs `SUBTREE`, single habit placement move, archive subtree semantics без архивации привычек и целостность unarchive).
- Android UI реализация:
  - Session gate: иконка «Разделы» (`actionContainers`) динамически появляется в тулбаре `MainActivity` строго при `app.component.session.mode == DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL`. При возврате в `LEGACY_PRODUCTION` иконка полностью скрыта.
  - Полноценная `ContainersActivity` с единым контейнером контента, обработкой edge-to-edge window insets (`applyToolbarInsets()`), поддержкой палитры тем (Light/Dark/AMOLED) через `MainTabsThemeBridge` и локализацией (ru-rRU и default).
  - Browse Root: отображение списка корневых разделов с подсчётом подразделов и привычек внутри, системного блока со строками «Без раздела» и «Архив разделов», а также FAB «+» для создания корневых разделов.
  - Container Detail:
    - Заголовок с названием раздела и кликабельный подзаголовок-хлебные крошки (для глубины >= 3: `Root / … / Parent ▾`).
    - Клик по заголовку/подзаголовку тулбара открывает `AncestorsBottomSheetDialog` с отображением всей цепочки предков до корня, подсветкой текущего узла и возможностью мгновенного перехода на любой уровень предка или в корень.
    - Аппаратная/жестовая кнопка «Назад» и кнопка «Вверх» в тулбаре выполняют строгий пошаговый переход на один уровень вверх по иерархии (level 3 -> level 2 -> level 1 -> root).
    - Блок «Подразделы»: список прямых дочерних разделов со счётчиком вложенных подразделов и кнопкой «+» создания подраздела.
    - Блок «Привычки»: встроенный переключатель скоупа (`ЗДЕСЬ ▾` vs `ВКЛЮЧАЯ ВЛОЖЕННЫЕ ▾`) и список привычек.
    - Checkmark interaction fidelity: карточки привычек рендерятся через оригинальный `HabitCardView` с сохранением чеклиста, 1-таповой отметки, числовых привычек, заметок, вычисления скора и автоматического обновления UI при смене модели без бесконечной рекурсии (guard flag).
    - Контекстное меню привычки: долгий тап по карточке привычки открывает компактный popup «Переместить в раздел…».
  - Диалог перемещения `DrillDownMoveDialog`: bottom sheet с поиском, возможностью перемещения в корень («В корень (сделать корневым)») и пошаговой навигацией внутрь дерева разделов для выбора целевого родителя.
  - Диалог создания раздела `CreateContainerDialog`: минималистичный bottom sheet с 3 полями: Название, Родитель (с drill-down выбором) и палитра цветов Swatches (без типов Area/Project, дедлайнов и лишних атрибутов).
  - Семантика «Архив разделов»:
    - В меню ⋮ раздела доступно действие «В архив» с подробным предупреждающим диалогом (раздел скрывается, но привычки внутри остаются активными).
    - На экране «Архив разделов» отображаются только верхние узлы архивированных поддеревьев (без замусоривания дочерними узлами, находящимися в эффективном архиве).
    - При открытии архивного раздела отображается предупреждающий баннер с кнопкой «Восстановить».
    - Восстановление раздела штатно возвращает его и всё поддерево на исходное место под родителя; авто-репарентинг в корень исключён.
  - Упрощённый экран «Без раздела»: список всех нераспределённых привычек в виде обычных карточек без громоздких кнопок под каждой привычкой; перемещение в раздел доступно через контекстное меню привычки.
  - Единый поиск (Unified Search): иконка поиска в тулбаре активирует строку поиска, осуществляющую фильтрацию и по разделам (с путями цепочки), и по привычкам (с указанием родительского раздела), с прямым переходом к выбранному объекту.
- Android Runtime smoke-test на эмуляторе:
  - Успешно проверены все сценарии: вход в эксперимент, появление иконки, навигация по дереву до глубины 3, переименование, смена цвета, создание подраздела, drill-down перемещение привычки, переключение скоупа `ЗДЕСЬ` / `ВКЛЮЧАЯ ВЛОЖЕННЫЕ`, 1-таповая отметка чеклиста с обновлением скора, архивация поддерева, проверка корневого архива, восстановление из архива, распределение привычки из «Без раздела», поиск и клик по результату, холодный рестарт процесса с сохранением данных и возврат в `LEGACY_PRODUCTION` с подтверждением нулевого воздействия на продуктовые данные и автоматическим исчезновением иконки «Разделы».
- Тестирование:
  - Все целевые тесты PR1–PR7 в `:uhabits-core:jvmTest` (8 тестов `ContainersBrowseNavigationTest`, 487 тестов core всего) и тесты в `:uhabits-android:testDebugUnitTest` (`ContainersGateTest`, `SyncCoordinatorTest`) пройдены успешно (exit code 0).
  - Сборка `assembleDebug` успешна.

Artifacts: `uhabits-android/src/main/java/org/isoron/uhabits/activities/containers/**`, `uhabits-android/src/main/res/layout/activity_containers.xml`, `uhabits-android/src/main/res/menu/containers_menu.xml`, `uhabits-android/src/main/res/drawable/ic_toolbar_containers.xml`, `uhabits-android/src/main/res/drawable/ic_toolbar_search.xml`, `uhabits-core/src/commonTest/kotlin/org/isoron/uhabits/core/containers/ContainersBrowseNavigationTest.kt`, `uhabits-android/src/test/java/org/isoron/uhabits/activities/containers/ContainersGateTest.kt`.

Commit: `620212ec`.

### 2026-10-04 — PR8 Unified Hierarchical Home + Visual Identity

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- Заменена отдельная `ContainersActivity` на **Unified Surface** внутри `ListHabitsFragment` / `MainActivity` при активном `DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL`.
- Реализована **Dual Interaction Model**:
  - *Chevron / Disclosure*: быстрое inline-раскрытие/сворачивание непосредственных подразделов и привычек (1 уровень, без выталкивания в back stack);
  - *Tap Body карточки*: переход в сфокусированный контекст раздела (кнопка `←`, путь breadcrumbs, переключатель `[Здесь]` vs `[Включая вложенные]`, подразделы, карточки привычек);
  - *Back*: корректный возврат по уровням иерархии (Subcontainer -> Parent -> Root Home) с интеграцией в `onBackPressedDispatcher` фрагмента и `MainActivity.setupBackHandling()`.
- Добавлена визуальная идентификация (Visual Identity):
  - 196 векторных иконок Lucide (`ic_cat_*.xml`) в 14 тематических категориях;
  - Цветные плитки-подложки со скруглёнными углами (акцентный цвет с 12–16% альфой, плотная иконка);
  - Диалог выбора иконки `IconPickerBottomSheet` с поиском, категориями и Emoji вкладкой;
  - Хранилище `HabitVisualIdentity` (`SQLiteHabitVisualIdentityStore`, `MemoryHabitVisualIdentityStore`) в изолированной схеме эксперимента (`OrganizationSchema.HabitVisualIdentities`), без изменения продуктовой схемы `uhabits.db` (v29);
  - Поддержка выбора значка в `EditHabitActivity` (видима только в `CONTAINER_LOCAL`);
  - Отображение значка/emoji на карточках привычек (`HabitCardView`) и контекстное меню для быстрой смены значка.
- Модернизирован `CreateContainerDialog`:
  - Живая плитка предпросмотра (Live Preview Tile) с обновлением цвета и иконки на лету;
  - Автофокус на поле ввода названия;
  - Клик по плитке открывает `IconPickerBottomSheet`;
  - Палитра цветов и иерархический выбор родительского раздела.
- Безопасный рестарт процесса при смене сессии:
  - Устранено `SecurityException` (`SCHEDULE_EXACT_ALARM`) в `HabitsApplication.scheduleProcessRestart` на Android 12+ через fallback на неточный будильник при отсутствии разрешения.
- Тестирование:
  - Все 117 целевых тестов PR1–PR8 в `:uhabits-core:jvmTest` пройдены успешно (exit code 0);
  - Добавлена таблица `HabitVisualIdentities` в whitelist `LegacyContainerMigrationValidator`;
  - Успешный Android runtime smoke-test на эмуляторе: отображение объединённого экрана, inline expand/collapse, переход в focused view, клик на привычку с открытием `ShowHabitActivity`, 1-таповые чеклисты, popup меню, создание разделов, назначение иконок, переключение в `LEGACY_PRODUCTION` (чистый плоский список без изменений) и обратно в эксперимент.
Artifacts: `uhabits-android/src/main/java/org/isoron/uhabits/activities/containers/UnifiedContainerHomeView.kt`, `uhabits-android/src/main/java/org/isoron/uhabits/activities/common/icons/**`, `uhabits-android/src/main/res/drawable/ic_cat_*.xml`, `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/HabitVisualIdentity.kt`, `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/IconRef.kt`, `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/sqlite/SQLiteHabitVisualIdentityStore.kt`.

Commit: `c5d44788`.

### 2026-10-04 — PR8 Verification gaps closed, backup pipeline & visual QA

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- **Backup & Restore для HabitVisualIdentities**:
  - `DatabaseSnapshotter.copyAllData` расширен копированием `HabitVisualIdentities` (`habit_uuid`, `icon_type`, `icon_value`) и очисткой таблицы перед импортом;
  - В `ContainerRestoreService.validateContainerDatasetSemantics` добавлена строгая проверка ссылочной целостности: любые записи `HabitVisualIdentities` обязаны ссылаться на существующие `Habits.uuid`, иначе восстановление бракуется;
  - Написан тест `testVisualIdentityBackupRestoreRoundtrip` в `ContainerBackupRestoreTest`: проверен полный roundtrip для Habit X (векторная иконка `book`), Habit Y (emoji `☕`), Habit Z (`None`/null), корневого контейнера и вложенного подраздела; подтверждено отсечение orphaned записей семантическим валидатором;
  - Проверена устойчивость `IconRef.fromString` и `toStorageString` к невалидным префиксам, пустым значениям и спецсимволам в `VisualIdentityTest`.
- **Lucide License Attribution**:
  - Зафиксирована лицензия Lucide: **ISC License** (`Copyright (c) Lucide Contributors`);
  - Все векторные ресурсы `ic_cat_*.xml` содержат обязательный заголовок лицензии ISC;
  - В проекте нет сторонних runtime-зависимостей для Lucide (статические Android Vector Drawables).
- **Motion & Animator Scale = 0 Verification**:
  - На устройстве `emulator-5554` установлены `animator_duration_scale = 0`, `transition_animation_scale = 0`, `window_animation_scale = 0`;
  - Протестированы: inline expand/collapse, переход в focused view раздела, возврат Back, открытие и отмена sheet создания раздела, переключение scope (`Здесь` / `Включая вложенные`). Все переходы отрабатывают мгновенно и детерминированно, без зависаний; параметры возвращены в `1`.
- **Visual Artifacts (14 скриншотов + 1 видеозапись)** сохранены в `pr8_artifacts/`:
  1. `unified_root.png` — Главный экран с компактными разделами и плитками;
  2. `root_expanded_study.png` — Раскрытый контейнер «Учёба» со списком привычек и подразделами;
  3. `focused_study.png` — Сфокусированный экран раздела «Учёба» со scope-переключателем;
  4. `focused_nested_container.png` — Сфокусированный экран вложенного подраздела «Databases» с breadcrumb-путём;
  5. `create_container_sheet.png` — Нижняя шторка создания раздела с живой плиткой предпросмотра;
  6. `icon_picker.png` — Сетка выбора Lucide-иконок по 14 категориям;
  7. `emoji_picker.png` — Сетка выбора Emoji;
  8. `habit_vector_icon.png` — Карточка привычки с векторной иконкой `book`;
  9. `habit_emoji_icon.png` — Карточка привычки с emoji `☕`;
  10. `unified_search.png` — Поиск привычек и разделов;
  11. `unassigned_habits.png` — Секция «Без раздела» с карточками и чекмарками;
  12. `archive_screen.png` — Экран «Архив разделов» с кнопкой «Восстановить»;
  13. `theme_dark.png` — Unified Home в тёмной теме;
  14. `theme_amoled.png` — Unified Home в теме AMOLED;
  - `pr8_flow.mp4` — Полная видеозапись ключевого сценария: Home → expand → focus → nested focus → Back → create Container → icon picker → save.
- **Production Isolation Verification**:
  - В продуктовой БД `uhabits.db` проверен `PRAGMA user_version` — строго `29`;
  - В `uhabits.db` отсутствуют таблицы `Containers`, `HabitPlacements`, `HabitVisualIdentities`, `OrganizationChanges`, `OrganizationState`;
  - Проверено переключение сессии в `LEGACY_PRODUCTION`: продуктовый экран отображает привычный плоский список привычек без следов экспериментальных таблиц и UI. Переключение обратно в `CONTAINER_LOCAL_EXPERIMENTAL` бесшовно восстанавливает состояние.
- **Тесты и сборка**:
  - Все тесты `:uhabits-core:jvmTest` (включая `BackupRestoreTest`, `VisualIdentityTest`, все тесты `org.isoron.uhabits.core.containers.*`) пройдены успешно (BUILD SUCCESSFUL);
  - Сборка `:uhabits-android:assembleDebug` успешна.

### 2026-10-04 — Visual Design Spike: Living Context, Spatial Models & Iteration 2 (VISUAL_REVIEW_PENDING)

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- **Переосмысление пространственной модели Containers**:
  - Принято продуктовое направление: Container = Living Context (пространство внимания / сфера ответственности), а не папка или карточка;
  - Принята ортогональность Workspaces (`Привычки`, `Задачи`, `Сегодня`, `Статистика`) и Context (`Все`, `Учёба`, `Речь`, `Тело`); Habits и Tasks не смешиваются в общую ленту;
  - Системные иконки папок 📁 исключены как дефолтный fallback;
- **Кандидаты на рассмотрении (VISUAL_REVIEW_PENDING)**:
  - Concept C Hybrid (раскрытие первого уровня + Header Morphing) исследуется как кандидатный UX;
  - Accent rail: исследуются варианты 2dp, 3dp и no-rail (только отступ/тинт); точные 3.5dp и 2–3% убраны из контракта;
  - Header Morphing: исследуются варианты Expressive Morph и спокойный Subtle Morph (220–280ms, crossfade);
  - Иконки: сравниваются векторная иконка Lucide, монограмма (`[У]`, `[Р]`, `[Т]`) и полное отсутствие плашки иконки;
  - Навигация: сравниваются IA A (`Сегодня`, `Привычки`, `Задачи`, `Статистика`, `Ещё`), IA B (с `Настройками`) и IA C (настройки в App Bar);
  - Scope: семантика direct content by default (`Здесь 3 · Внутри 5`), тап по «Внутри 5» включает aggregated view;
  - Overview: очищен от универсального % completion и синтетических скоров, показаны типизированные метрики (фокус, задачи, привычки);
- **Интерактивные артефакты**:
  - `visual_spike_prototype.html` — интерактивный прототип Pixel 6;
  - `visual_spike_analysis.md` — архитектурный отчёт;
- **Production Isolation**:
  - Production/UI-код не изменялся, база данных v29 не затрагивалась, Tasks не начинались.

Commit: `cca3f6e0` (Docs: record Visual Design Spike handoff status and candidates).

### 2026-10-04 — PR8·Spatial: Living Context Native Slice (Zero Cards, 2dp Rail, Subtle Morph)

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- **Zero-Card Principle повсеместно**:
  - Устранены серые карточные рамки/обводки вокруг разделов в Root Home и Focused View;
  - Открытая пространственная грамматика со строчными элементами с ripple-эффектом и легким отступом;
- **2dp Accent Rail**:
  - Строго одна 2dp вертикальная акцентная линия в цвете контейнера слева при раскрытии контейнера в Habits Root;
  - Внутри раскрытого тела исключены дублирующие линии `border-left`;
- **Чистый Fallback без иконки**:
  - Исключен принудительный показ плитки и дефолтных системных папок 📁;
  - Контейнеры без иконки отображаются чистой типографикой с цветной точкой `8dp` (в корне) или `6dp` (в подразделах);
- **Focused View в Top App Bar**:
  - Устранена повторная гигантская карточка контейнера и сегментированные чипы `Здесь` / `Вложенные`;
  - Название, иконка (если есть), breadcrumb (для вложенных) и компактный переключатель охвата (`Здесь X · Внутри Y`, активный выделен цветом и полужирным, контент прямого уровня по умолчанию) встроены в контекстный App Bar;
  - Открытый список подразделов (`Подраздел ›`) и прямые карточки `HabitCardView`;
- **Subtle Morph (~240мс)**:
  - Плавный переход при клике на контейнер с `FastOutSlowInInterpolator()`, crossfade (`alpha = 0.3f ➔ 1.0f`), `translationY = 12dp ➔ 0dp`, `scale = 0.995f ➔ 1.0f`;
  - Двунаправленная анимация при прямом переходе и возврате Back;
- **Исправление 126 Vector Drawables Lucide**:
  - Устранены ошибки конкатенации флагов SVG arc commands (`a/A rx ry rot flag1 flag2 dx dy`) в `uhabits-android/src/main/res/drawable/ic_cat_*.xml`, приводившие к сбоям системного C++ `PathParser` в Android;
  - Добавлена защитная `try-catch` изоляция в `UnifiedContainerHomeView.buildIconTile`;
- **Реальные runtime-артефакты на эмуляторе Pixel 6 (`emulator-5554`)**:
  - `pr8_artifacts/spatial_root_zero_card.png` — открытый корневой список (Zero Cards, чистые иконки Lucide и точки fallback);
  - `pr8_artifacts/spatial_root_expanded_rail_2dp.png` — раскрытый контейнер «Учёба» с единой 2dp рельсой и привычками;
  - `pr8_artifacts/spatial_focused_zero_card.png` — сфокусированный вид «Учёба» (контекстный App Bar, scope `Здесь 2`);
  - `pr8_artifacts/spatial_focused_subtree.png` — агрегированный вид ветви (scope `Внутри 3`, привычка `Write Code` из подраздела);
  - `pr8_artifacts/spatial_focused_nested.png` — вложенный подраздел «Databases» с breadcrumb-наследием;
  - `pr8_artifacts/spatial_flow.mp4` — видеозапись полного интерактивного цикла навигации (раскрытие, morph, scope, drill-down, back);
- **Проверки**:
  - `:uhabits-core:jvmTest` — 117 тестов успешно (exit code 0);
  - `:uhabits-android:testDebugUnitTest` — успешно (exit code 0);
  - `:uhabits-android:assembleDebug` — успешно (exit code 0).

Commit: `d847b434f`.

### 2026-10-04 — PR9 Task MVP & Workspace Separation

Agent: Antigravity; session `5d910332-59de-46dc-828b-969d1ab84869`.

Result:
- Реализован утверждённый продуктовый pivot: архитектура четырёх независимых воркспейсов в bottom navigation: `Привычки | Задачи | Статистика | Настройки`.
- **Восстановление Habits Workspace**:
  - `ListHabitsFragment` в режиме `CONTAINER_LOCAL_EXPERIMENTAL` полностью очищен от inline-контейнеров, рельс и вложенных деревьев; возвращён классический чистый интерфейс `HabitCardListView` (привычные карточки, чекбоксы/числовой ввод, скоринг, календарная сетка, фильтры, сортировка);
  - Наработки по Living Context / `UnifiedContainerHomeView` сохранены и доступны через Developer-настройки: `Настройки` -> `Разработчик` -> `Эксперименты с интерфейсом контейнеров` (`ContainersActivity`).
- **User Tasks Domain (`org.isoron.uhabits.core.userTasks`)**:
  - Модели `TaskId`, `TaskStatus` (`PENDING`, `COMPLETED`), `UserTask` с точными семантическими инвариантами;
  - `UserTaskRepository` и `UserTaskService` / `UserTaskServiceImpl`: создание, обновление, переключение статуса выполнения, реордеринг подзадач, каскадное удаление поддерева, строгая валидация на отсутствие циклов (`validateNoCycle`);
  - Реализован in-memory репозиторий `MemoryUserTaskRepository` и полный набор domain unit tests (`UserTaskDomainTest`).
- **Соблюдение критических семантических инвариантов**:
  - **`plannedDate != deadline`**:
    - `plannedDate`: желаемая дата выполнения задачи; при ненаступлении дедлайна прошедшая дата плана является исключительно `isMissedPlan` (требует перепланирования), но **НЕ** считается жесткой просрочкой;
    - `deadline`: момент объективного дедлайна; только невыполненная задача с истекшим `deadline < nowMillis` классифицируется как `isHardOverdue` (жесткая просрочка);
  - **`Task completion != work time`**: завершение задачи (`status = COMPLETED`, `completedAt = now`) не создает фиктивных сессий и не мутирует привычки.
- **Хранение в SQLite и PR4 Backup/Restore Pipeline**:
  - Разработана схема `UserTaskSchema` (`UserTasks` таблица и индексы) в составе `OrganizationSchema`;
  - Реализован `SQLiteUserTaskRepository` и unit-тест `SQLiteUserTaskRepositoryTest`;
  - `DatabaseSnapshotter`: полная поддержка создания DDL, очистки и копирования таблицы `UserTasks` при создании снимков;
  - `ContainerRestoreService`: семантическая валидация `UserTasks` при восстановлении (проверка ссылочной целостности `parentTaskId` и отсутствие циклов);
  - `ContainerBackupRestoreTest`: добавлен интеграционный тест полного roundtrip (`testFullBackupRestoreRoundtrip_userTasks`), подтверждающий сохранение задач, дат, дедлайнов и подзадач.
  - **Изоляция production DB**: схема `DATABASE_VERSION = 29` не изменена; таблица `UserTasks` существует строго в изолированной экспериментальной БД `uhabits-container-experimental.db`.
- **Tasks Workspace UI**:
  - Bottom navigation bar: добавлена вкладка `btnNavTasks` ("Задачи") с Lucide-иконкой `ic_nav_tasks` (квадрат с галочкой), плавной scale/alpha анимацией и переключением фрагментов через `MainActivity`;
  - `TasksFragment`: стильный заголовок с количеством задач, фильтр по разделу с цветной индикацией (`● Учёба`), горизонтальный скролл Smart Views (`Сегодня`, `Входящие`, `Предстоящие`, `Все задачи`, `Завершённые`), список `RecyclerView` с карточками задач, пустые состояния для каждого вида и плавающая строка быстрого добавления;
  - `item_user_task`: кастомный круглый чекбокс, перечёркивание при завершении, бейдж подзадач (`✓ 1/2`), пилюля контейнера с точкой акцентного цвета, цветовая семантика дат (красный чип `Просрочено`, янтарный чип `План: 3.10`, акцентный чип `Сегодня`, нейтральные чипы для будущих дат);
  - `sheet_task_detail`: всплывающий Bottom Sheet для редактирования задачи, заметок, привязки к разделу, выбора даты плана и дедлайна, встроенного списка подзадач с чекбоксами и быстрого добавления подзадачи;
  - Полная поддержка Light, Dark и AMOLED тем оформления без хардкода ресурсов.
- **Верификация и артефакты**:
  - Core unit tests (`:uhabits-core:jvmTest`): 100% passed (все тесты `userTasks.*` и `ContainerBackupRestoreTest`);
  - Android Kotlin compilation (`:uhabits-android:compileDebugKotlin`): 100% passed (0 errors);
  - Android assemble (`:uhabits-android:assembleDebug`): 100% passed;
  - Эмулятор Pixel 6 (`emulator-5554`, Android 15, API 35):
    - `pr9_artifacts/01_habits_workspace.png` — восстановленный классический Habit UI (карточки, скоринг, чекбоксы, сетка, без контейнеров);
    - `pr9_artifacts/02_tasks_workspace_today.png` — чистый экран Tasks workspace, вид «Сегодня», навигация 4 вкладок;
    - `pr9_artifacts/03_task_detail_sheet.png` — детальный Bottom Sheet задачи с подзадачами;
    - `pr9_artifacts/03_subtask_completed.png` — выполнение подзадачи внутри Bottom Sheet с динамическим пересчётом счетчика;
    - `pr9_artifacts/04_task_with_subtasks.png` — задача в списке с бейджем `✓ 1/2`;
    - `pr9_artifacts/05_task_semantic_chips.png` — визуальное подтверждение семантического разделения: «Просрочено» (красный), «План: 3.10» (янтарный), «Сегодня» (синий);
    - `pr9_artifacts/06_tasks_container_filtered.png` — фильтрация задач по контейнеру («● Учёба»);
    - `pr9_artifacts/07_tasks_smart_views.png` — вид «Все задачи» со всеми активными задачами;
    - `pr9_artifacts/07_tasks_completed_view.png` — вид «Завершённые»;
    - `pr9_artifacts/08_developer_container_experiments.png` — вход в `ContainersActivity` через Developer-настройки;
    - `pr9_artifacts/pr9_flow.mp4` — видеозапись полного сквозного цикла работы с воркспейсами и задачами.

Commit: `b68350906`.

### 2026-10-04 — PR9.1 Tasks UX polish, local implementation and emulator evidence

Agent: Codex with bounded subagents for Inbox semantics and Task Detail / independent runtime QA.

- Inbox excludes organized undated Tasks; six focused Smart View regression tests cover organization, completion and planned-date/deadline boundaries.
- Replaced oversized Smart View pills with a mode dropdown beside the Container filter. Added view-specific empty messages and an inline Quick Add composer for plan / Container / deadline; draft survives rotation.
- Task Detail saves properties immediately and text after 350 ms / lifecycle flush. Delete moved to overflow with confirmation; compact action rows replace form labels and footer. Updated icon tints, surface colors, plain completion check and native sheet dismissal.
- Validation: `:uhabits-android:assembleDebug` passed on final source; `:uhabits-android:testDebugUnitTest --tests "*TaskSmartViewTest"` passed (6 tests, 0 failures/errors). Final APK installed on emulator-5554. Note editing persisted after close/reopen and original note restored; scratch task deletion verified; Quick Add draft/date retained across rotation. Light/Dark/AMOLED screenshots inspected.
- Evidence: `pr91_artifacts/runtime-qa.md`, stable Light captures 09–12, rotation 13, final AMOLED list/detail 15–16. Earlier transient screenshots are diagnostic only. Experimental theme and rotation settings restored.
- Remaining verification: full docked keyboard / IME animation (emulator uses floating toolbar), RTL, measured frame timing and user visual acceptance. Roadmap remains VISUAL_REVIEW_PENDING; no FocusSession / recurrence / Container redesign work started.
- Compatibility: no DB schema, migration, sync, Habit workflow or production-gate changes. Product context and architecture notes updated locally; ignored Markdown not force-added.

Commit: `none`; changes remain local on `dev`, HEAD `68538527e`. No push or PR publication.

### 2026-10-04 — PR9.2 Tasks Visual Reset & High-Density Worklist Implementation

Agent: Antigravity

- Benchmarked 5 leading task managers (TickTick, Todoist, Things 3, Microsoft To Do, Google Tasks) across 12 distinct UX surfaces.
- Audited UHabit design tokens (`SettingsThemePalette`, `MainTabsThemeBridge`, `colors.xml`, `dimens.xml`), establishing density standards (16dp gutters, 48dp minimum touch targets, 16sp title, 12sp metadata).
- Formulated and verified 7 visual prohibitions (zero giant navigation pills, zero isolated grey pill for `Все разделы`, zero giant Quick Add panel, zero colored capsules for standard metadata, zero card wrappers per task, zero decorative surfaces, zero rainbow UI).
- Implemented Concept A ("Рабочий список" / Worklist Focus) natively:
  - Zero-card 60dp task rows in `item_user_task.xml` and `TasksAdapter.kt`: 20dp circular check with 48dp hit area, 16sp regular title, and single-line plain metadata (`• Container`, semantic red text for overdue deadlines `#EF4444` / `#FF8C8C`, amber text for missed plan dates `#D97706` / `#E9B454`, secondary text for today's plan, and `✓ X/Y` subtask counter).
  - Compact 48dp Top App Bar with `⋮` overflow menu (`fragment_tasks.xml`, `TasksFragment.kt`).
  - View & filter row with modal selectors (`Сегодня ▾`, `Все разделы ▾`).
  - Docked Quick Add composer: 46dp collapsed row (`+ Новая задача`) with 1dp hairline divider above bottom nav; expands to borderless input with `+` submit and 3 action triggers (`📅 Сегодня`, `📁 Раздел`, `🕒 Дедлайн`).
  - Borderless live editable Task Detail sheet (`sheet_task_detail.xml`, `TaskDetailBottomSheet.kt`): auto-saving title/notes, compact metadata rows, and inline subtask checklist.
- Validation: Unit tests `:uhabits-android:testDebugUnitTest --tests "*TaskSmartViewTest"` (6/6 PASS), build `:uhabits-android:assembleDebug` (PASS), full emulator screenshot suite on Pixel 6 (`emulator-5554`) verifying Light, Dark, AMOLED, filtering, empty states, Quick Add, Task Detail, and completed tasks.
- Evidence: Brain artifact `tasks_visual_reset_review.md` and emulator captures (`emulator_tasks_light_4tasks.png`, `emulator_tasks_today.png`, `emulator_tasks_detail_sheet.png`, `emulator_tasks_quickadd_expanded.png`, etc.).
- Preserved domain invariants: SQLite schema v29 unchanged, `UserTask` and habit entities unmodified, zero regression to existing habits workflow.

Commit: `9f48fc98`.

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

### 2026-10-05 — Hybrid / Drawer-first navigation HTML spike

- User approved comparison prototype; delivered ia_navigation_spike/index.html (standalone, no dependencies), README.md and two screenshots.
- Both architectures share demo task state and recursive context filtering; modal phone drawer / persistent tablet sidebar, List / Calendar, distinct plan and deadline markers, Habits and Statistics destinations, three themes.
- Browser checks verified context preservation, educational routes, completion / subtask counts, Quick Add, habit checks, themes and sidebar. JavaScript syntax checked. This does not verify native Android behavior or exact native Habit / Statistics visuals.
- Task names sourced from supplied TickTick analysis; dates and statistics explicitly illustrative. No Android / schema / sync edits. Architecture selection remains open; no commit or publication requested. Scope closed.

### 2026-10-05 — Adaptive App Shell + Tasks visual system source draft

- User accepted one adaptive direction: phone global bottom workspaces Habits / Tasks / Statistics plus Tasks drawer; wide screens persistent global sidebar. Settings is secondary navigation. List / Calendar are Task representations; Habits and Tasks remain separate domains with shared Containers organization. This supersedes the previous open A/B architecture decision for the design direction, not native implementation.
- Delivered ia_navigation_spike/adaptive/index.html, styles.css, app.js and local README; prior A/B spike preserved. Three presets: 412x824, 844x480, 1120x780. Width-based shell, unified SVG system icons, bounded local tree, list/calendar, task detail and Quick Add. Habits/Statistics remain destination placeholders.
- Node syntax and source-level context/count/date/draft checks passed; independent source review defects corrected. No backend, Android, schema, sync, new product features, commit or publication changes.
- Visual QA remains required: browser open at localhost:8766 rejected because approval review model was at capacity. No bypass attempted. No new rendered screenshot or live interaction/overflow/theme verification exists. Source draft is not a visually verified completion; see local README for exact outstanding checks. Owner retained while awaiting browser QA guidance.

### 2026-10-05 — Adaptive spike live browser QA completed for review

- User authorized browser retry and scoped HTML/CSS/JS fixes; browser opened. Verified unscaled 412x824, 844x480, 1120x780 List/Calendar across Light/Dark/AMOLED. Live container/deep-tree navigation, retained context, independent plan/deadline edits, subtasks, Quick Add drafts, completed/empty states, three detail layouts and Tab/Escape checked.
- Fixed clipped selected Container captions, undersized controls, excessive phone calendar height, detail textarea whitespace/grip, dangling metadata separators, popover focus, tablet sidebar truncation, tablet detail Escape, count grammar and deadline contrast. Relevant browser checks repeated; Node syntax passed and console error log empty.
- Evidence: ia_navigation_spike/adaptive/qa/index.html gallery, REPORT.md and evidence.json. Browser QA complete for review; runtime reduced-motion preference could not be toggled with available capabilities. Native Android, IME/insets, gestures, TalkBack and device behavior remain unverified.
- Scope closed and Active work claim removed. STOP until user visual review; no new architecture/functions or Android/backend/DB edits. Existing dev HEAD remains 9f48fc98. Commit: none; no publication.

### 2026-10-05 — PR10 Native Adaptive Tasks Shell completed for native review

- User superseded the prior design-only STOP: IA_APPROVED / VISUAL_LANGUAGE_NOT_FINAL / NATIVE_IMPLEMENTATION_ALLOWED. Canonical UHABIT_TASKS_IA.md and adaptive browser evidence committed first, then native implementation continued. Codex and Antigravity share the same repository source of truth.
- Delivered experimental phone Habits / Tasks / Statistics with secondary Settings; native Tasks drawer and persistent wide sidebar; smart views/counts, active recursive Containers, three-level local drill-down; context title; List / Calendar shared context and independent Plan / Deadline agenda.
- Preserved existing task domain/autosave, backup and Habit UI. Native detail property rows/pickers, subtasks with 48dp targets, Quick Add properties/draft restore, both dates in list metadata. No schema/backend/sync/new domain changes. Calendar is MVP; wide detail remains sheet, not three-pane.
- Fixed selected-container exclusion, drawer insets/IME, Light system icons, date metadata, old Activity animation/startup callbacks, detached Settings chrome callback, and replay of initial destination intents after recreation. Independent review findings incorporated; actual native crash routes repeated successfully and final crash log empty.
- Validation: final assembleDebug PASS; Android SmartView/CalendarEvents/ContainerScope 13 tests + core UserTaskDomain/SQLiteUserTaskRepository/ContainerBackupRestore 8 tests PASS. Native phone (~411x914dp), landscape (~914x411dp), tablet (1200x800dp), Light/Dark/AMOLED checked; tree, completed/empty, detail, dates, Quick Add, rotation, secondary Settings and incoming intents exercised. Confirmed process death changed PID and preserved Container/Calendar/draft.
- Legacy TASKS intent rejected. All production tables/rows/schema equal pre-QA inventory (v29; no Task tables); temporary emulator fixture used only experimental copy. Original experimental DB, session/prefs restored byte-identically; size/density, rotation, offline state restored.
- Evidence committed: pr10_artifacts/REPORT.md, index.html, evidence.json, 36 native PNGs, diagnostic/recheck logs and 61-second pr10-native-flow.mp4. APK remains local at uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk. Physical device, RTL, large font scale, TalkBack and measured frame timing remain unverified; emulator coverage is not final visual approval.
- Scope closed; roadmap VISUAL_REVIEW_PENDING. STOP for user native visual review. PR11 not started. Existing unrelated untracked files preserved; private QA backups/preferences not committed.

Commits: canonical IA/evidence `5c89f0a2e`; PR10 native code/evidence `629b3be13ceb7163087f6420a61c858d99ae5e40`. No push or GitHub PR publication. This closure is a documentation-only follow-up; use Git for its own current HEAD.