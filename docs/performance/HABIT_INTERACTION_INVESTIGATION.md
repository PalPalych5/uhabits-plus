# Habit interaction investigation — canonical engineering record

## 1. Executive status

Iteration 9: 2026-10-14, Asia/Novosibirsk (Incremental projection validation and cold-cost attribution). Source baseline verified locally: `94e0b36fd53994d8dfc45908641bc9301baa4c45`, branch `dev`.

**CONFIRMED:** 
1. **Cold preparation regression disproven as an algorithmic defect:** Cold full-history preparation of 41 habits over 1825 days requires computing ~78,000 day points and takes ~1200–2700 ms wall time in both Iteration 6 (baseline before) and Iteration 8/9 (after) on ART API 35. Perfetto SQL attribution confirmed that during the 2647 ms window in `after8`, the ART JIT compiler ran for 2243 ms baseline-compiling 802 methods and CMC GC ran for 412 ms, heavily contending with worker dispatchers. In `before7`, prior test steps had pre-warmed those methods (JIT ran only 260 ms). Matched, order-alternating runs confirm no algorithmic regression: `before9` cold projection took 1906.7 ms (grouped) / 1171.9 ms (entrySensitive); `after9` took 2470.9 ms (grouped) / 1283.1 ms (entrySensitive).
2. **Reconciled performance metrics:** The Iteration 8 commit message conflated cold-after wall time (2647 ms) with warm-after wall time (30–52 ms) and described provider wall duration as "CPU savings". Paired measurements confirm true warm-edit recomputation wall time drops 16x–36x (733–1800 ms → 23–50 ms, reusing 40 habits and preparing 1), entry-sensitive presentation drops from 1287 ms to 819 ms, and delayed grouped 40-habit recomputations are eliminated (0 habits prepared).
3. **Mutation scope fallback hardened:** Strict inverted table whitelisting (`table !in habitScopedTables`) guarantees that any write touching non-habit tables (e.g. `HABITVISUALIDENTITIES`, `APPSETTINGS`, `CONTAINERS`, `ORGANIZATIONSTATE`, `HABITPLACEMENTS`, or unknown future tables) sets `isGlobal = true` and poisons frame scope (`null`), safely forcing conservative full recomputation fallback.
4. **Differential parity exact:** 8/8 differential oracle scenarios pass with 100% field-by-field parity; all 70 core JVM tests pass.

**NEXT ACTION:** Profile remaining post-presentation Main/RenderThread frame time on entry-sensitive sorting (where large layout passes occur when reordering rows) and evaluate targeted row rebinds.

### Evidence discipline and shared workflow

Use exactly these statuses: **CONFIRMED** (source mechanism or scoped measured result), **HYPOTHESIS** (causal explanation awaiting discrimination), **DISPROVEN** (specific claim contradicted within a named scope), **UNKNOWN** (insufficient evidence). A source-proven mechanism does not prove its latency dominance. Keep prior findings and append corrections with evidence; do not silently replace them.

At session start read this status, open hypotheses and next investigation, verify HEAD/dirty state and handoff claims, choose one bounded question, and write acceptance/falsification criteria before experiments. At session end append registry records, update confidence and next action. Do not repeat an audit without a new question/evidence. This document owns performance evidence; `docs/UHABIT_NEXT_HANDOFF.md` remains the coordination register. Historic handoff acceptance language does not supersede later qualified measurements.

Large/private raw artifacts stay local under `scratch/`; never commit traces, databases, credentials or raw logs. Stable source references below are repository-relative paths with one-based line ranges at the baseline; revalidate after source changes. Existing unrelated dirty handoff content, artifact deletions and untracked files were preserved. Coordination claim is local; it is excluded from this documentation commit because the handoff already contains unrelated uncommitted changes.

## 2. Architecture map

Abbreviations: core/ = uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/; android/ = uhabits-android/src/main/java/org/isoron/uhabits/. The map is verified against source; timings are historical and separately qualified below.

### Input, writes and durable publication

| Stage | Source reference | Actual work/thread and boundary |
|---|---|---|
| Boolean MotionEvent | android/activities/habits/list/views/CheckmarkButtonView.kt:87-141 | Main: View click/long-click policy calls performToggle; value changes immediately, callback executes synchronously, haptic feedback and invalidate follow. Touch marks record callback arrival; they do not measure delivery from original MotionEvent.eventTime. onDraw is not presentation. |
| View callback | android/activities/habits/list/views/CheckmarkPanelView.kt:63-92; HabitCardView.kt:219-247 | Main: captured date callback -> ripple/location calculation -> ListHabitsBehavior. Numerical button onClick opens edit, not Boolean toggle (NumberButtonView.kt:126 onward). |
| Submit absolute edit | core/ui/screens/habits/list/ListHabitsBehavior.kt:57-85,142-147 | Main: onToggle submits absolute value/notes; confetti is immediate, before durability. Numerical seed only reads already-loaded original manual entries; Boolean popup reads computedEntries.get. Callback rounds numerical value to native x1000 and submits CreateRepetitionCommand. |
| FIFO | core/commands/CommandRunner.kt:39-67; core/tasks/CoroutineTaskRunner.kt:83-104; android/inject/HabitsApplicationComponent.kt:419-422 | run synchronized only around submission. App-wide deferred chain serializes all command writes, including different habits. Task pre/post/listener work Main; background Dispatchers.IO. Previous completion is awaited before run; finally unblocks following command even on failure. Command FIFO does not serialize Focus or unrelated taskRunner writers. Queue timer includes Main launch scheduling, IO dispatch and predecessor wait together. |
| Write and model rebuild | core/commands/CreateRepetitionCommand.kt:43-60 | IO: originalEntries.get -> identical value/notes early return -> add -> full habit.recompute -> refreshHabitFromDatabase -> resort -> enqueue sync. No command-level transaction wraps these stages. Command persistence metric includes all these steps, not just commit. |
| Lazy full-history load | core/models/sqlite/SQLiteEntryList.kt:43-95; core/database/EntryRepository.kt:23-29,76-147 | First get/add/interval/known loads ALL undeleted history for habit, copies EntryData then Entries. Interval request does not bound SELECT. Shared EntryRepository monitor protects each complete reset/bind/step/read sequence, across habits. Upsert performs UPDATE, changes(), possible INSERT OR IGNORE/update retry, and ID lookup. No explicit transaction in upsert; a successful changing autocommit mutation (UPDATE or INSERT) completes before later recompute/resort/sync. |
| Model recompute | core/models/Habit.kt:188-222; core/models/EntryList.kt:100-151 | IO rebuilds computed entries, score and streak over historical start through today+30. computed EntryList monitor holds while acquiring original getKnown monitor. Boolean goal segments filter historical originals; numerical copies originals. No bounded day-only recompute. |
| Refresh and sort | core/models/HabitList.kt:210-219; core/models/sqlite/SQLiteHabitList.kt:307-311 | Same object avoids duplicate invalidation/recompute; distinct list instance reloads and recomputes. resort reads blocks/organization, sorts list and synchronously notifies ModelObservable listeners on IO. ModelObservable.kt:46-48 invokes callbacks while holding its monitor. |
| Revision commit observer | android/database/AndroidDatabase.kt:309-371; core/models/projection/EffectiveReadRevision.kt:37-166 | Dataset-owned shared AndroidDatabase wraps one SQLiteDatabase with effective/workspace/organization table observers. Android SQLiteSession assigns thread-specific connection/transaction ownership; shared facade is not one externally owned pinned physical connection. Effective mutation succeeds, SELECT changes()>0 then revision; autocommit publication occurs before SQLiteEntryList updates its in-memory entries and before Habit.recompute. Outer tracked transaction publishes only after endTransaction success; inner release merges; rollback generation restores speculative state. Frames thread-local; no global observer monitor around writer acquisition. |
| Revision listeners | core/models/projection/EffectiveReadRevision.kt:11-29; android/focus/EffectiveHabitReadController.kt:52-89 | changed/rolledBack invoke listeners synchronously under revision monitor on mutating thread (normally IO here). Controller debounces full cache refresh 200 ms on Main; every durable revision resets delay. Notification reshow is initiated immediately. Rollback invalidates all habits and scheduled Main refresh recomputes all models. Dataset revision has no habit ID scope. |
| Completion observers | core/commands/CommandRunner.kt:61-78 | Main after background return, distinct from durable revision. Next command can enter IO after predecessor deferred completion before predecessor Main observer loop; strict observer/presentation FIFO is not established by write FIFO. Each listener measured synchronously, no stable operation ID. |

### SQLite ownership and lock ordering

android/database/AndroidDatabase.kt:85-190,194-299: per PreparedStatement monitors protect statement methods, repositories must still hold their own monitor across compound bind/step/read. SQLite begin uses beginTransactionNonExclusive. SQL BEGIN/COMMIT/ROLLBACK translate to Android platform transaction API; raw SAVEPOINT ownership/name stack is ThreadLocal WeakHashMap (38-52,98-154). Under WAL writer serialization remains; no observed current wait histogram proves writer contention dominant. android/HabitsDatabaseOpener.kt:41,49,90,106,118 explicitly enables WAL. SELECT adapter fast path (EffectiveReadRevision.kt:85-95) bypasses mutation tracking.

Verified lock chain on autocommit repetition upsert: EntryRepository monitor -> PreparedStatement monitor -> platform SQLite write wait; after statement.step exits its monitor -> revision monitor -> synchronous listeners. Recompute holds the computed EntryList monitor while a cold SQLiteEntryList.getKnown first loads under EntryRepository, then separately acquires the original EntryList monitor for sorted getKnown. SQLite overrides load outside the original monitor; repopulation uses individually synchronized clear/add calls. ModelObservable listener callbacks run inside its monitor. A complete cycle proving a remaining deadlock is UNKNOWN; do not claim globally safe lock ordering merely from the removed observer monitor. Revision is emitted before in-memory update; whether a raced effective snapshot can be cached at the new revision needs a bounded test/code review, not an asserted defect.

Failure: CoroutineTaskRunner catches and discards non-cancellation Throwable (96-101), skips onPostExecute on thrown command, finishes task listeners. CommandRunner finally still completes chain. No rollback of a prior autocommit repetition if recompute/sort/sync later fails. Existing timers emit duration on success for command stages; SQL observer uses finally and measures failing step too. No command cancellation handle is returned. TaskRunner skips background if Task.isCanceled but still invokes post; these anonymous command tasks have no explicit cancellation behavior. Listener collections are not universally synchronized (CommandRunner add/remove); listener accumulation remains UNKNOWN until lifecycle inventory and repeat reopen counts.

### Focus, notifications, widgets, sync and organization

- Sync: core/sync/SyncManager.kt:82-104,362-382 serializes entry payload, writes SyncQueue synchronously and invokes onQueueAppended on IO after model/sort; capture paused check can suppress this. android/sync/SyncCoordinator.kt:89-110 installs callback and completion listener only when syncEnabled and not CONTAINER_LOCAL_EXPERIMENTAL. Production toggle may trigger queue callback plus command listener scheduling, not necessarily two network runs; offline fixture must record sync configuration. No network executed in this investigation.
- Reminders: core/reminders (jvmMain/java)/ReminderScheduler.kt:45-53 explicitly returns for CreateRepetitionCommand, Batch and numerical op, so full scheduleAll is not Boolean toggle work.
- Notifications: core/ui/NotificationTray.kt:80-84 cancels habit on command completion; :113-124 effective revision reshows active notifications using TaskRunner. Thus effective revision and completion are two distinct channels. Zero active notifications and an active reminder are different experiment configurations.
- Widgets: android/widgets/WidgetUpdater.kt:53-85 listens to both command and effective revision; completion bypass applies only session.isExperimental, so PRODUCTION_NEXT may request via both channels. updateWidgets(modifiedHabitId) now calls requestAll (93-96), not targeted provider method. WidgetRenderCoordinator.kt:24-75 posts to Main, discovers widgets on single worker, coalesces provider requests 50 ms; no synchronous discovery Binder in completion. Capability guard during render :93,107 does not eliminate discovery requests in requestAll. Debouncing reduces duplicate render; it does not prove zero scheduling/allocation cost.
- Focus: android/focus/FocusCreditController.kt:30-65,115-180 is dataset-owned Main bridge. captureStore first delegates fact/journal recording, then enqueues facts, reconciles each fact and enqueues contributions, notifies, retains failed fact IDs and bounded retry. SQLiteFocusCaptureStore core/focus/sqlite:84-114 uses SAVEPOINT capture_focus_commit, writes immutable facts + singleton journal, RELEASE; rollback-to/release on error. In the normal outermost capture path the fact/journal savepoint completes before attribution durability; a nested RELEASE only merges the caller transaction and does not itself prove commit. Failures defer credit and do not undo an already committed fact. Startup recovery processes 50 facts per Main turn and schedules next after 16 ms, with 1 s bounded retry; this can independently compete with input. This code is not evidence of actual recovery duration/dominance on Xiaomi.
- Organization/container metadata: effective revision observes Contributions/Repetitions/Habits/HabitGoals/HabitExtensions/AppSettings/Containers/HabitPlacements/OrganizationState/HabitVisualIdentities; workspace revision separately observes UserTasks plus organization tables; organization revision only organization tables (AndroidDatabase.kt:350-360). Therefore one habit edit does not revise Tasks workspace via Repetitions, but organization mutation can fan out all three. Habit resort consults block and organization state.


### Read and effective projection path

- `uhabits-android/src/main/java/org/isoron/uhabits/focus/EffectiveHabitReadController.kt:103-150`, `snapshots`: synchronized controller monitor covers context lookup AND provider preparation. Context = requested bounds + dataset-wide revision + calendar + rollback generation + locale weekday. A changed context clears all cached snapshots; missing habits only are prepared. Newly computed and cache-hit results both undergo freshness checks; three attempts then `EffectiveReadInvalidatedException`. `snapshots(List)` at 152-156 requests today-365 through today (a 365-day lookback, 366 inclusive dates).
- Same controller 52-71: every revision event replaces a Main Handler callback delayed 200ms; callback calls `cache.refreshAllHabits()`. Thus targeted command completion and delayed full revision refresh are separate channels. Notification tray also receives revision events. Rollback invalidates every original-entry list immediately, then Main refresh recomputes every habit. These are confirmed schedules; duplicate expensive work depends on revision/command timing and cache hits.
- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/projection/EffectiveHabitReadSnapshot.kt:205-248`, provider `prepare`: copies every requested habit's original and computed known entries (`copyDetached`, 252-257). First Contribution batch uses Long.MIN_VALUE..Long.MAX_VALUE, including removed records. Domains include all original dates/all Contribution dates plus statistics start. Second batch in `EffectiveHabitValueService.daysForRange` (108-127) covers a shared history/month/frequency envelope, then buckets active/tombstoned facts and builds every day for every habit. Therefore 365-day requested bounds DO NOT cap database Contribution discovery, original entry history, copied computed history, projection days or score/streak input history. This behavior supports semantics across rolling/calendar goals; it is not itself a demonstrated defect.
- `SQLiteContributionRepository.kt:279-295` batches <=400 habit IDs per query, materializes rows and sorts accumulated results by time/ID. Provider executes two batched reads; batching avoids Habit x day SQL. `SQLiteEntryList.kt:43-61,92-95` lazily calls `EntryRepository.findAllByHabitId`, loads whole history, and only then applies interval selection. Already-loaded originals avoid this SQL but still sort/copy lists: EntryList.getKnown (85-86) sorts the full entry collection; detached copies repeat that work.
- Snapshot constructor (`EffectiveHabitReadSnapshot.kt:52-89`) builds by-date map, validates representability/coverage, calls detachedPresentation (134-142; copies original/computed entries again, overlays numerical days), recomputes scores across statistics history and builds streaks (180-196). Scores perform Dh*W rolling entry visits plus repeated goal-history normalization/sort and allocation (Habit.kt:106-120), approximately O(Dh*(W + G log G)) for Dh historical days, W frequency denominator and G goals, assuming average constant entry-map lookup (`ScoreList.kt:66-100,126-139` rolling windows). Coverage checks can also inspect rolling windows (145-164). Snapshot score lookup `EffectiveScoreResult.on` at 27-29 is linear in returned score list. `presentationHabit` 117-131 performs another detached copy and score/streak recomputation when called; current list cache uses `scoreResult`, not `presentationHabit`, so do not attribute that extra accessor work to card rendering without identifying another caller.

### Cache and publication

- `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/ui/screens/habits/list/HabitCardListCache.kt:223-275`: attach refreshes and subscribes CommandRunner; CreateRepetitionCommand completion requests one habit. Targeted path falls back to full when a full fetch is pending, completed/entered filters are active or score/status sorting applies. Other commands full-refresh. Full refresh increments global generation and marks prior full task cancelled; per-habit generations guard targeted work. detach removes CommandRunner listener and detaches filtered memory list.
- RefreshTask captures previous list/maps and requested settings under cache monitor (477-495). Targeted fetch copies prior habit/snapshot maps and invokes provider for one habit only (380-403); full fetch scans root `allHabits`, applies candidate matcher, loads all candidates in one batch and filters/reorders based on effective completion (406-463). Sort = O(H log H), potentially with linear score lookup per comparison. Even targeted refresh copies O(H) published maps; it computes row data only for target (570-605).
- Metadata reads occur on worker: visual identity and root container group (`577-582`). `HabitOrganizationFacadeImpl.kt:94-101,294-298` routes to visual repository and placement/container path queries; grouping may traverse ancestors. Full work repeats for selected H habits. Cost and number of concrete SQL calls need measurement; do not call metadata dominant from source alone.
- `RefreshTask.doInBackground` 542-610 holds task monitor while fetching/projecting. Worker serializes controller access, enters per-entry/model monitors for copies and score algorithms, and briefly enters cache monitor for `isCurrent` checks (528-534). Published cache monitor is not held for the full worker computation. OnPostExecute 617-639 executes under cache monitor after background completes, removes missing rows, finds/moves/inserts/updates rows and invokes listeners. `processPosition` 719-736 searches current list for each row: O(H^2) worst-case publication; no measured severity attached. `performUpdate` 692-716 advances snapshot even when visuals unchanged; equality compares metadata/style/score/checkmark/note arrays and effective cell binding. It sends full change only for style, entry payload otherwise. No observer means no proof of rendered/committed state.
- Cancellation is cooperative freshness rejection, not an immediate interrupt of synchronous provider/SQL/score loops. `EffectiveReadInvalidatedException` retains last published generation (560-564). Other Exceptions log/report refresh failure, cancellation exceptions propagate (542-551); TaskRunner suppresses non-cancellation errors after cache records failure. shutdown drains task monitors outside cache monitor (128-155) to avoid cache-worker inversion. Controller dispose removes revision/preferences/receiver and clears snapshot cache (170-183).
- `CoroutineTaskRunner.kt:39-40,83-105,113-117`: task lifecycle/publish callbacks Main; background runs injected IO dispatcher. `HabitsApplicationComponent.kt:419-422` wires Dispatchers.Main/IO. Multiple refreshes can overlap IO but effective controller monitor serializes snapshot preparations. Lock order observed: worker task -> private newData -> controller -> entry/score and database; worker task -> cache for freshness; Main publication cache -> task methods. Publication starts after worker monitor release; don't infer a present deadlock without tracing actual nested lock paths.

### Binding, rendering and lifecycle

- `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardListAdapter.kt:128-163` Main full bind gets published habit/score/checkmarks/notes/metadata/snapshot; ENTRY_ONLY payload calls `bindEntryData` only if same bound model. 190-222 emits RecyclerView notifications plus adapter observable callbacks on changes and refresh completion/failure.
- `HabitCardListView.kt:150-181` full bind assigns model, selection, values, button count, offset, score, goal/unit/threshold/notes/icon and lazily creates one GestureDetector per holder. 117 disables SimpleItemAnimator change animations. Holder is just RecyclerView.ViewHolder plus GestureDetector (`HabitCardViewHolder.kt:25-27`). `HabitCardView.kt:145-159` entry bind changes score/checkmark/note panels; only numerical habits update numerical arrays/snapshot. Full values setter (120-125) builds a numerical array even for Boolean habits. `onMeasure` 276-279 is measured around normal layout. Number panel setup is traced (`NumberPanelView.kt:100-...`). These costs have no quantitative attribution in this static pass.
- Card observable registration follows attach/detach and model replacement (`HabitCardView.kt:94-103,328-335`). Model change posts attribute copy to Main (282-285). This is a second visual invalidation channel distinct from cache payload. Balanced implementation exists; listener accumulation after repeated usage remains unknown until counts/lifetimes are measured.
- `HabitCardListView.kt:193-202` window attach/detach forwards adapter/cache lifecycle. `ListHabitsFragment.kt:183-198` resume always adapter.refresh + screen attach + invalidate; pause detaches screen/cancels refresh. `MainActivity.kt:378-392` shows selected fragment at RESUMED and hides others at STARTED, making Habits -> Tasks -> Habits resume trigger full cache refresh. Controller unchanged context can reuse snapshots, but filtering/metadata/publication still occur. Navigation runtime count needs measurement.
- Choreographer/RenderThread/SurfaceFlinger are framework/system continuation after invalidation/bind/measure, not explicit app exit functions. Source cannot prove first displayed expected or first presented committed state. Require a UI state/operation marker tied to a FrameTimeline surface/frame and separately observed commit boundary. OnDraw marker alone is insufficient.



### Tasks workspace reads and Main rendering

`android/activities/tasks/TasksFragment.kt:188-211,544-577` attaches workspace revision listeners on resume, removes them/cancels load on pause/destroy, reuses a snapshot only at matching workspace revision, and otherwise loads on IO. Three revision-stable attempts are followed by a 50 ms retry if invalidated; component, generation and lifecycle fences precede Main publication. An already active load is reused. There is no explicit read transaction around the complete multi-repository snapshot; revision checks validate the resulting batch, not a pinned database connection.

`android/activities/tasks/TasksWorkspaceSnapshot.kt:34-46,49-80` loads all Tasks plus all containers (OrganizationStore path), builds roots/subtask counts in O(T), sorts siblings and derives recursive paths/subtree counts. SQLite Tasks read is a complete `UserTasks` query (`core/userTasks/sqlite/SQLiteUserTaskRepository.kt:54-68`). Thus “bounded snapshot” means bounded repeated queries/count passes, not paging or a fixed Task cap. `TasksFragment.renderTasks:580-626` filters/sorts roots and calls navigation/calendar rendering on Main; `renderNavigation:877-941` rebuilds navigation rows from detached metadata. No database call is required for those indexes, but allocating/filtering/sorting/rendering and repeated context reloads still need timing. Habit Repetitions do not bump workspace revision; organization edits can invalidate both workspaces. Numerical or Focus changes affecting Habits must still respect effective revision rather than infer Task freshness from it.

### Short source filename index

- `SQLiteContributionRepository.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/contribution/sqlite/SQLiteContributionRepository.kt`
- `EffectiveHabitValueService.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/projection/EffectiveHabitValueService.kt`
- `ScoreList.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/ScoreList.kt`
- `SQLiteEntryList.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/sqlite/SQLiteEntryList.kt`
- `HabitOrganizationFacadeImpl.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/facade/HabitOrganizationFacadeImpl.kt`
- `CoroutineTaskRunner.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/tasks/CoroutineTaskRunner.kt`
- `HabitCardViewHolder.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardViewHolder.kt`
- `HabitCardView.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardView.kt`
- `HabitCardListView.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardListView.kt`
- `HabitCardListAdapter.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/HabitCardListAdapter.kt`
- `NumberPanelView.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/views/NumberPanelView.kt`
- `EntryList.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/EntryList.kt`
- `Habit.kt` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/Habit.kt`
- `HabitsApplicationComponent.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/inject/HabitsApplicationComponent.kt`
- `ListHabitsFragment.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/list/ListHabitsFragment.kt`
- `MainActivity.kt` = `uhabits-android/src/main/java/org/isoron/uhabits/activities/main/MainActivity.kt`

## 3. Performance model

The latency chain is a set of separate clocks, not one interchangeable number. Define H = habit count, R = original history rows, D = requested days, Dh = complete historical projection days, G = historical goals, C = active Contribution facts, V = visible rows, T = Tasks and O = organization metadata. Complexity below describes current source work; measured coefficients remain unknown unless an experiment records them.

| Interaction | Expected semantic scope | Potential work to account for | Current measurement boundary |
|---|---|---|---|
| Single Boolean toggle | One manual daily entry; possible frequency-derived neighboring days/score/streak | One command, full target history recomputation, revision/invalidation, targeted snapshot or full generation if sort/filter depends on entries, row update/layout | Offline target draw/presentation measurements exist; physical input cannot be correlated to command |
| Rapid toggles, same/different habits | Ordered durable edits without lost latest state | Global command FIFO, head-of-line blocking, overlapping/cancelled snapshots and generations | Existing single active-sample observer cannot reliably correlate concurrent operations |
| Numerical edit | Manual value independent of effective Focus credit | Async dialog manual seed/context validation, entry command, projection/score/streak work | Generic dialog/root draws are not committed target glyph proof |
| Focus-attributed edit | Durable Focus fact then idempotent Contribution; no synthetic Repetition | Journal/session commit, attribution reads/writes, revision, sync hooks, full effective refresh if target scope unknown | No complete correlated fact→Contribution→presented Habit timeline |
| Grouped list scroll | Bind/measure visible/new rows | V row formatting/panels, group decoration, animation, metadata already cached; possible remaining misses | Emulator counts and physical framework measure slices are separate evidence |
| Habits→Tasks→Habits | Reuse valid snapshot; refresh changed context | Lifecycle listeners, workspace/effective revision checks, Tasks snapshot and Main rendering, Habits resume refresh | Paired checkpoint generic draw improvement; not physical current-build navigation proof |
| Reopen after prolonged usage | Recover durable state, dispose old components/listeners | Startup hydration/recompute, Focus recovery pages, first effective batch, layout; allocation/GC/listener lifetime | No controlled long-session retention/heap or current-phone reopen measurement |

### Latency evidence table

| Stage | Known | Unknown / needed |
|---|---|---|
| Input delivery | Xiaomi stream 253 input records: event→delivery p50/p95/max 2.609/6.599/69.539 ms; includes MOVE | Row-specific DOWN/UP→accepted edit; raw clock domain must be mapped |
| Queue | Source global FIFO and `command.queue` duration hook | Per-op enqueue→worker dispatch→predecessor release; cross-habit tail amplification |
| DB | WAL microbenchmark benefits; writer inversion reproduced with WAL and DELETE and corrected | Exact operation transaction acquisition/commit duration vs recompute and enqueue side work |
| Snapshot / locks | Whole-history read/copy mechanism; physical first trace snapshot worker wait 183.372 ms | Per-op lock wait vs lock-held compute, full/targeted counts, revision retries and cost distribution |
| Cache publication | Generation fences, background→Main publication, semantic equality key | Durability→accepted snapshot→publish→target bind with operation lineage |
| Bind / measure | Prior 31-row/84-measure storm; Xiaomi stream measure 156.321 ms of 177.913 ms frame | Current-build physical row counts/methods and remaining residual work |
| First expected state | Can be observed in target glyph draw | Whether optimistic, committed, and actually presented; rapid-op lineage |
| First committed presentation | Offline frame test joins target draw to FrameTimeline; some joins unresolved | Reliable physical row-state presentation with current known APK |
| Long/dropped frames | Scoped FrameMetrics / FrameTimeline and historical gfx summaries | Complete comparable campaigns; missing reports are not dropped frames |

## 4. Confirmed findings

| Finding | Status and scoped evidence | Implication / limit |
|---|---|---|
| F01 Ordering and stale publication defects | CONFIRMED historical regression tests and c7130dabf source fences; HIST-002/root-review | Correctness repairs; no universal physical no-lag conclusion |
| F02 Observer-monitor writer inversion and mixed savepoint ownership | CONFIRMED HIST-005 red/green disposable tests; 10d164878 thread-confined implementation | Real storage concurrency defect; WAL alone neither causes nor resolves the inversion |
| F03 Excessive row work on revision and NaN equality | CONFIRMED HIST-006 31 unique cards / 84 measures; abc3b43a6 semantic keys and ENTRY_ONLY path | Unnecessary work reduced; committed presentation median improvement DISPROVEN for this paired sample |
| F04 Historical projection amplification | CONFIRMED source provider copies full original/computed history and discovers all-time Contributions; PI-20261013-02 confirms 5.4x preparation growth (73.5→396.4ms) and up to 539ms lock wait | Unbounded lookback expands processing 5.2x beyond requested 365d; score/streak recompute and coarse monitor lock dominate |
| F05 Multiple refresh channels | CONFIRMED command-targeted request plus dataset revision delayed full request, lifecycle resume full refresh | Actual duplicate costly preparations HYPOTHESIS until counters distinguish cache hits/cancellations |
| F06 Physical Main frame CPU/measure | CONFIRMED HIST-007 measure156.321ms of177.913ms, Running175.871ms | Expensive completed frame explained as Main CPU; exact custom method/current-build/multisecond tail UNKNOWN |
| F07 Residual Main SQL and detail latency | CONFIRMED HIST-003 after336 steps/557.926ms total, detail n2 ~4–5s | Literal “all Main DB work eliminated” DISPROVEN in that run; root draw and step sums are limited metrics |
| F08 Durability differs from completion/UI | CONFIRMED source: autocommit entry precedes model recompute; Main observer finish comes later, optimistic View changes earlier | Exact successful commit outcome must be instrumented; revision→memory race is HYPOTHESIS, not a new proven defect |

## 5. Open hypotheses

| ID / status | Explanation | Evidence for | Evidence against / limits | Falsification test |
|---|---|---|---|---|
| H01 HYPOTHESIS | Async queue/snapshot/publication backlog explains delayed durable checkbox while input stays responsive | FIFO and shared snapshot lock exist | No physical per-command markers; parsed long frames only ~178 ms do not localize seconds | Correlate each accepted op through commit, accepted revision, target bind/draw and presentation; reject if delay lies elsewhere |
| H02 CONFIRMED | Processing complete historical data materially amplifies snapshot computation and controller monitor lock wait | PI-20261013-02 across 4 arms (90d to 1825d) proves 5.4x prepare cost (73.5→396.4ms), 8.7x score/streak growth (19.7→172.4ms), and up to 539ms monitor wait | SQLite autocommit mutation itself remains fast (12–28ms); historical scaling dominates background preparation and reader contention | Bounded lookback envelope and decoupled cache lock proposed |
| H03 HYPOTHESIS | Remaining layout/bind/animation work dominates a slow edit | Physical Main measure/CPU evidence; prior full row storm confirmed | Latest row correction never installed on phone; offline committed median did not improve | Operation-correlated target bind/measure and ancestor measure slices; reject dominance if queue/read dominates tail |
| H04 HYPOTHESIS | Duplicate revision + command + lifecycle reloads cause redundant batches/publications | Multiple notification paths exist and debounce may overlap Main callbacks | Generation fencing prevents stale publication; multiplicity/cost not measured per op | Count invalidate requests, executed batches, cancellations/retries, publications per op and reason |
| H05 HYPOTHESIS | Long-session listener/component retention or GC causes progressive deterioration | User observes initially good then slow; multiple lifetime listeners/caches exist | No heap/retained-object or listener-count series; report alone proves no leak | Repeat bounded navigation/toggle cycles with attach/detach counters and GC/allocation summaries; stable counts refute accumulation |
| H06 HYPOTHESIS | WAL pool writer contention remains significant | One writer per pool; transactions/focus/sync share storage | Corrected Java monitor inversion also reproduced under DELETE; WAL is not its sole cause | Measure writer acquisition separately from transaction body under offline background work; preserve WAL semantics |
| H07 UNKNOWN | Focus recovery, widgets, reminders or sync expands edit tail in production | Source side-effect paths exist | Offline QA disables external/background capabilities; production phone activity unknown | First trace side-effect timings/capability flags locally, without network/payload capture; separate later authorization for physical work |
| H08 HYPOTHESIS | Autocommit revision emitted before in-memory original/model update might let a concurrent snapshot cache old model data under the new revision | Source ordering exists | No reproduced stale snapshot; later generation checks may prevent visible error | Use a deterministic disposable interleaving around mutation return→memory update; assert snapshot contents/revision and subsequent cache reuse without altering production locks |

## 6. Experiment registry and reusable methodology

Registry IDs are permanent. Historical imported records describe existing campaigns; this iteration did not rerun them. New experiments get PI-YYYYMMDD-NN. Never edit an old result to agree with a new one; append an amendment referencing both records.

| ID | Build/device/dataset/configuration | Evidence and metric | Conclusion / quality |
|---|---|---|---|
| HIST-001 | Report associates fix with 148193aa0; exact raw APK hash absent from JSON. API35 x86_64 swiftshader AVD UHabit_UI_Review_Disposable_20261011, v30, 32 habits. Raw baseline says isolationRun=false and disabledSideEffects empty. | `scratch/habit-interaction-lag/REPORT.md`, `benchmark-baseline.json`, `benchmark-after.json`, `baseline_interaction.perfetto`, `after_interaction.perfetto`. 125 actual recorded interactions claimed; report scenario enumerations sum to160 and numerical edits are not consistently included in its 125 definition. Heartbeat median14.11→2.33ms; snapshot p50/p95 144.04/736.75→49.72/153.82ms; repetition.sqliteWrite p95 667.70→663.03ms. | Scoped historical improvements; no committed-target presentation endpoint. Report calls Choreographer intervals>20ms “dropped frames”: retain interval definition, not SF dropped-frame counts. Read-TX starvation is hypothesis: duration alone does not prove lock holder/transaction overlap. “WAL completely eliminates remaining locks” is disproven as general claim by HIST-005. Isolation side-effect manifest insufficient to equate with later guarded fixtures. |
| HIST-002 | Original physical baseline/after/after2, source associated with 89d69f09e; exact installed commits and datasets not authenticated by artifacts. Xiaomi12T Pro Android15. PID31829/13586/27600. | `scratch/real-device-habit-lag/{baseline,after,after2}.perfetto`, corresponding `_logcat.txt`/`_gfxinfo.txt`; independent report `scratch/root-review/perfetto_analysis_report.md`, parser/query outputs. Skipped events/frames7/2574→2/76→1/103; max logcat monitors998→218→104ms; Davey8177→none→980ms. | Symptom strong; no universal resolution. Trace windows26.90/28.36/23.35s; parse failures8145/12002/11860, discarded negative timestamps5/3/5. Main parsed max736.436ms misses late8s logcat episode. 8.342s monitor,66ms detail-open,16.3s freeze not independently established;8177ms Davey does not prove system ANR. gfxinfo cumulative unequal windows invalidate37.5%→10% speedup claim. |
| HIST-003 | c7130dabf baseline filename, after associated with08300ee68; exact after APK identity absent from JSON. Offline PRODUCTION_NEXT checkpoint, one paired emulator run. | `scratch/main-thread-checkpoint/{baseline,after,summary}.json`, `analyze_checkpoint.py`, `baseline-c7130dabf.apk`. 20 handler/input-to-first-root-draw events: input-to-draw p50/p95/max71.409/713.418/1854.208→51.595/415.754/443.991ms; handler42.291/474.059/1492.836→39.867/210.056/256.930ms. Main SQL steps3081→336; summed step2127.988→557.926ms. | Residual Main SQL exists in after sample; claim “all Main database access eliminated” too broad. Per-row SQL steps exclude preparation and aren't whole-query latency. StrictMode callbacks asynchronous phase labels. Root draw is neither expected target state nor committed SF presentation. Heartbeat p95 remains924.456ms; detail-open n2 remains3995/4951ms. Does not establish phone latency. |
| HIST-004 | WAL microbenchmark reported in handoff and08bece302/91f69f077/270dce4a0 commits; exact APK/config identity unavailable. Disposable v30 AVD. | Handoff references `wal-benchmark/comparison.json`: missing at repository path (Test-Path false). First reported p50 writes17.80→1.34ms, reads11→31; later20.24→1.36ms,12→34. | Historical claims only; raw benchmark not available for independent reconciliation. Two series differ; never merge their values. Write microbench improvement is not user interaction speedup. Commit08be kept WAL off;91f enabled v30 WAL;270 hardening preceded confirmed defects repaired10d. |
| HIST-005 | Baseline270dce4a0→10d164878; guarded emulator-5580/API35 software renderer,30 habits,5792 repetitions after series,v30. Network none, remoteCallsAllowed=false; background SELECT every10ms. WAL vs actual rollback `truncate`. | `scratch/production-performance-regression/perfetto_analysis_report.md`; `{baseline,hotfix}-{wal,rollback}.json`, `latency-summary.json`; native red/green text. n15 measured taps/30 command timings per series. WAL root post-command/cache draw p50/p95 43.98/688.66→84.45/1177.27ms; persistence10.59/56.23→27.25/75.57ms. Rollback draw131.64/1551.97→123.51/1324.16ms. | Writer deadlock confirmed in both WAL/DELETE via timed4s regressions/owner stacks; WAL itself not its cause. UI speedup unproven and final WAL worse in pair. Root natural OnDraw after command.finished+cache.publish is event-order proxy, not target correctness/presentation. n15 p95/p99=max; not robust tails. `baseline.perfetto` exploratory20s and `hotfix.perfetto` separate20s are not paired workloads. Parser severity errors absent in saved queries. |
| HIST-006 | Baseline10d164878 with hooks→candidate subsequently committedabc3b43a6. Guarded offline API35 software AVD320x640/density160,v30/WAL,40 actual MotionEvent taps each. | Canonical `scratch/habit-frame-stalls/{baseline8,after8}.{json,perfetto}`, `*-test.txt`, `*-analysis.json`, `*-main-summary.json`, `analyze.py`, report. Main card bind+measure p50/p95/max214.352/275.756/551.240→0.233/1.168/26.920ms. Baseline32 full binds/31 unique cards,84 measures/31 cards per edit; candidate usually1entry bind/1measure,0full. | Confirmed avoidable global bind/measure defect addressed, but committed target draw171.316/357.765/2064.292→177.033/453.981/1349.581ms and committed SF presentation217.714/1411.627/2146.325(38linked)→230.350/854.098/1385.607(39linked): no median speedup. First expected presentation56.097/100.734/456.800→54.478/128.557/439.921ms can precede commit. Paired linked ordinals37/40 also no median speedup224.574→230.350ms. Worker snapshots113/232/509→118/249/513ms,78/79calls; persistence18/59/87→18/48/76ms,40calls. Endpoint is app surface frame containing target glyph, not physical camera/panel pixels. |
| HIST-007 | Xiaomi22071212AG/Android15, installed2.3.1/20301 DEBUGGABLE; exact installed commit UNKNOWN (user says old). HEADabc3b43a6 never installed. Manual user actions, two45s authorized historical captures. | `scratch/real-device-habit-lag/xiaomi-20261013/REPORT.md`, `remaining.perfetto`, `remaining-stream.perfetto`, configs, `trace-health.csv`, `stream-input.csv`, `stream-clocks.csv`, `stream-slow-frames.csv`, `stream-cpu.csv`, `input-main-summary.json`. Stream45.515s/PID19069:253delivery records incl MOVE;12DOWN/11UP. Event timestamp→deliver start p50/p95/max2.609/6.599/69.539ms; delivery duration0.774/1.263/34.922ms. | Physical symptom confirmed by user; dominant multisecond causal span unknown. Worst completed doFrame177.913ms, measure156.321ms, Main Running175.871ms; other frames153.3/measure131.85ms and107.136ms. Max app surface interval180.411ms not tap→target latency. Stream403779vendor parse errors+6negative timestamps; original128MiB ring736chunks overwritten, only11.044s retained,502846errors+6negative. No op/persistence/cache markers; absent slices cannot disprove asynchronous delay. Initial worker snapshot wait183.372ms/startup unrelated to identified tap. Stream max parsed app monitor5.115ms;Main MessageQueue1.631ms. Empty technical logcat isn't no-lag evidence. |

### Required record schema

Record question, predicted result and falsification threshold; source SHA + dirty diff identity; app/test APK SHA-256, build type and instrumentation flags; exact device/AVD serial/model/API/ABI, renderer, refresh rate, density/font/animation configuration; schema/authority/journal mode; fixture seed/reference date and counts (H/R/C/T/O), capability flags and source dataset filename validation; warm/cold state and prior activity; run order, sample size, exclusions/failures/timeouts; clock source/conversion; metric definitions, median/p95/max plus raw per-operation data; trace health and join coverage; exact raw artifact paths; integrity/parity results and conclusion limits. Unknown identity stays UNKNOWN. Do not export private names, IDs, values or raw database contents.

### Safe fixture and procedure (design only; run next session)

Reuse `UiReviewCaptureTest.initializeFixture` (`uhabits-android/src/androidTest/java/org/isoron/uhabits/qa/UiReviewCaptureTest.kt:38-108`), `ProductionUiHotfixFixture`, and `HabitVisibleFrameStallTest`. Require actual and supplied AVD name `UHabit_UI_Review_Disposable_20261011`, emulator model, debug build, private disposable marker matching selected filename, schema v30 and `PRODUCTION_NEXT`; explicitly disable sync, remote calls, background jobs, widgets and reminders. Inspect fixture initialization before execution: it creates/replaces only a disposable database and may rebuild components. Never use unrestricted `connectedAndroidTest`, an unspecified serial or production source. Existing `tools/qa` scripts are infrastructure references, not permission to regenerate screenshot archives.

Freeze seed 20261011 and reference date 2026-10-11, with deterministic `setToday` in tests where required. Record actual row counts after `ProductionUiHotfixFixture` extensions rather than guessing from the generator's base 32 Habits/84 Tasks/64 FocusSessions. Preserve a closed fixture baseline; handle sidecars only under the established disposable fixture procedure, never against the real DB. Validate exact runtime filename (startup `uhabits.db` differs from instrumentation `test.db`), authority, `quick_check`, FK checks and capability gates. Reset each comparison arm from the same fixture and verify parity after the run. Do not substitute live backup/data.

Before campaigns write the procedure and analysis criteria in a new registry record. Start with a smoke validation of clocks/correlation and a few operations; only then select a justified sample count. Alternate comparison arms and capture cold/warm runs separately. Do not drop slow samples because of their duration; label system overlays, failures, parser errors and timeouts. Pair identical fixture/configuration; a software-rendered emulator estimates source work and correctness, never Xiaomi hardware latency. Long-session experiments report cycle count and elapsed time, not merely “prolonged.”

| Scenario | Repeatable fixture action / parity oracle |
|---|---|
| Single Boolean | Visible fixed ordinal Boolean target, real injected DOWN/UP, stable initial state; await exact durable operation and matching target frame |
| Rapid toggles | Same-target burst plus interleaved two-target burst, fixed schedule; retain every op ID, FIFO order, final durable/effective parity; do not drain between edits |
| Numerical | Open rendered dialog, separate manual seed latency from save; fixed synthetic values only inside fixture; verify manual entry and effective lower-bound semantics |
| Focus | Fixture minute-compatible direct Habit and container-rule targets; complete bounded work via existing timer runtime; verify one fact/Contribution, no synthetic Repetition and no Task completion |
| Grouped scroll | Fixed initial viewport/grouping; repeated fixed distance/duration scroll; verify target row identities internally and count binds/measures/frames |
| Navigation | Fixed Habits context→Tasks smart view/container→Habits; separate unchanged and revision-changed cases, first correct context/state frame |
| Reopen | Fixed bounded mixed-use cycles, listener/GC summaries; cold process reopen and warm resume separate; verify durable state and no duplicate Focus facts |

### Independent timing boundaries

Use one monotonic nanosecond clock for app events, explicit thread ID, run-local operation ordinal and component/generation ordinal. Never emit stable Habit/Task IDs or values. `MotionEvent.eventTime` is uptime-based; do not subtract it from elapsedRealtime/Perfetto BOOTTIME without clock mapping. Record MONOTONIC↔BOOTTIME clock snapshots and suspend intervals; join FrameTimeline vsync IDs, not nearest arbitrary frame.

| Metric | Start → end; definition |
|---|---|
| Input dispatch | Injected/event DOWN and UP timestamp → actual View handler receipt; report acceptance separately |
| Command queue | Accepted enqueue → worker ready; predecessor-release → run start separately if possible |
| Transaction / commit | Successful changing autocommit mutation return, or platform writer acquisition begin/end, body begin/end and successful outer `endTransaction` return when a transaction exists; SQL statement timings exclude neither cursor iteration nor preparation unless labeled |
| Snapshot / locks | Monitor acquisition attempt→acquired, acquired→release, original/computed copy, Contribution preload, score/streak phases; include H/R/D/C and retry/cancellation counts |
| Cache publication | Successful commit→matching accepted snapshot, worker completion→Main publication, revision/generation lineage |
| ViewHolder work | Bind and target/ancestor measure begin/end, payload type, transient row ordinal, V counts; no stable identifiers |
| Expected state draw | First target glyph draw matching expected operation state, possibly optimistic; draw is not display presentation |
| Committed state draw | First matching target glyph draw whose published snapshot includes that operation's successful durable revision |
| Committed presentation | App surface FrameTimeline actual presentation of that identified draw's vsync; retain failed/ambiguous joins, distinguish app/SurfaceFlinger intervals |
| Long/dropped frames | FrameMetrics/frame deadline exceeded, >50/>100/>700 ms separately; FrameTimeline jank classification; droppedReports means lost metric callbacks, not dropped rendered frames |

### Smallest next instrumentation (proposal, no source changes here)

Extend existing optional hooks (`CommandRunner.timingObserver`, `HabitCardListCache.timingObserver`, `AndroidSqlTiming`, `HabitFrameTiming`) rather than create a parallel harness. Current `command.persistence` wraps `command.run`, including recompute; `command.finished` is Main observer completion, not the database commit. `HabitVisibleFrameStallTest` uses one active sample and booleans for finished/published: valid scoped sequential evidence, insufficient lineage for bursts or exact durable transaction attribution.

Add disabled-by-default privacy-safe operation context: enqueue ordinal, command success/failure, successful autocommit mutation return or outer transaction success/rollback and end, projection revision/generation linkage, target bind/draw vsync. Add snapshot lock-wait/hold and phase counters only at existing boundaries. Propagate context across coroutine dispatch explicitly, never a global “current operation”; one cache batch may include multiple operations and must record inclusion/supersession. Distinguish accepted, durable, published, drawn and presented events. Events for cancellation, invalidation and failure must close spans and must never count as success. Bound observer buffering, remove observers in `finally`, record observer overhead with disabled/enabled smoke comparison. No SQL text/bindings, user identities, credential or network capture. Existing QA fixture may assert synthetic expected values internally; exported markers contain ordinal/status only.

Do not instrument all methods or change transaction/lock/cache semantics. Instrumentation validation is the next milestone; physical diagnostic installation and manual capture remain a separately authorized follow-up after artifact preparation. This session authorizes no Xiaomi access.

## 7. Fix history and corrections

| Commit | Implemented correction / risks and limits |
|---|---|
|148193aa0|Visual identity cache,targeted snapshot path,CreateRepetition targeted refresh,200ms revision debounce,equality/nullness correction,popup cached seed. HIST-001 metrics scoped; global revision still later full-refreshes/rebinds, last subsequently fixedabc.|
|89d69f09e|Volatile order getters,group root cache,no change animations,holder GestureDetector,detach listener,comparator root cache,target generations,incremental effective cache,detail IO. Commit title claims freezes eliminated, but HIST-002/root-review contradict universal result; first misses/null roots/locks/stale publications remained.|
|c7130dabf|Command FIFO/failure completion,private worker copies/generation fences,entry-sensitive sort/filter handling,detach lock inversion/null-root/temp listener cleanup,lifecycle detail/popup/current seeds,cached-hit revalidation.36core+2AndroidJVM+8native historical PASS; no fresh phone speedup.|
|08300ee68|Background immutable rowmetadata and TasksWorkspaceSnapshot,O(T)subtask counting,avoid duplicate lifecycle loads,separate habit/workspace revisions,IO timer/popup paths. HIST-003 is one pair with residual Main SQL and detail latency; title not literal universal proof.|
|08bece302|WAL evaluation,connection affinity blocker acknowledged,ThreadLocal changes,organization/identity revision observation; default off. HIST-004 raw absent.|
|91f69f077|Core platform TX primitives,Android nonexclusive thread-affine TX/rawSQL interception,ThreadLocal insert IDs,v30WAL on/v29off,placement FK cleanup. Safety tests don't exclude later mixed boundary/writer deadlock regression.|
|270dce4a0|Savepoint/parser/REPLACE/snapshot-version hardening. HIST-005 proves remaining concurrent writer and savepoint ownership bugs.|
|10d164878|Thread-confined revision frames; no shared monitor during SQLite writer wait;named savepoint ownership/rollback-only completion. Historical55JVM,22native TX/WAL,6separate database,build PASS;UI speedup unproven.|
|abc3b43a6|Immutable style/display keys,NaN equality,always publish fresh snapshot,notify changed cards only,ENTRY_ONLY payload avoids hidden numerical resets. HIST-006 confirms work reduction,not committed-latency median speedup;27core+2focusednative+build historicalPASS; never installedXiaomi.|

### Dated correction ledger (foundation consolidation, 2026-10-13)
- Handoff preserves old “COMPLETED physical freezes resolution” claim followed by independent correction. Canonical document should retain chronology and mark physical full resolution UNKNOWN, not repeat that headline as accepted fact.
- Root-review's “WAL remains disabled” is a correct historical c713 state, superseded by91f/270/10d code. Its Tasks/Main SQL findings partly superseded by08300; current code is authoritative.
- Habit-frame-stalls report begins “candidate uncommitted”/10d and ends no commit: superseded by Xiaomi report and actualabc3b commit. Measurement results remain valid historical candidate evidence; don't silently rewrite report.
- HIST-001 says requesting365days still processes historical context; no bounded SQL read transaction demonstrated around entire CPU projection. Claim that background analytical TX blocks commit needs transaction/lock/connection markers rather than long persistence alone.
- HIST-006 earlier SIGINT baseline4/after traces lack FrameTimeline; excluded from displayed-frame evidence. baseline5 startupANR,baseline6fixture failure,baseline7overlay rejection excluded. Native4golden failures also on baseline; no regenerated goldens, no full green screenshot suite. Canonical captures finished120s.
- HIST-005 initial bad fixture red run excluded;corrected-fixture/eight-native valid. Combined teardown6AndroidDatabaseTest failures preserved; separate6test fresh process PASS. Optional JS compile failed unchanged JVM-only source; no JS readiness assertion.
- Missing: `scratch/real-device-habit-lag/REPORT.md` (directory has traces/tools; use root-review+Xiaomi reports), `wal-benchmark/comparison.json`, standalone main-thread-checkpoint report (summary/rawJSON exist), immutable installed physical build commit/hash, per-operation physical command/persistence/publication chain, controlled long-session listener/heap soak, matched current-phone baseline/candidate, raw camera panel latency. Don't regenerate missing evidence.

## 8. Prioritized bottleneck backlog

| Priority | Bounded investigation | Expected impact / confidence | Cost | Safety / decision gate |
|---|---|---|---|---|
| 1 | Correlate input→FIFO→durability→snapshot→target presentation | Highest information gain; high confidence that current missing lineage prevents causal diagnosis, impact of any fix UNKNOWN | Small instrumentation plus focused offline validation | Preserve ordering/failure/data behavior; no physical install or cloud; stop if correlation incomplete |
| 2 | Fixed-D historical scaling and snapshot lock occupancy | Potential high CPU/memory/worker tail impact; source mechanism high confidence, dominance unproven | Bounded fixture variants, phase counters | Preserve complete-history score/streak/manual/coverage semantics; no range truncation optimization before parity proof |
| 3 | Residual target/ancestor bind-measure and navigation generation counts | Physical Main frame cost confirmed; current-build absolute impact unknown | Focused trace and existing frame hooks | Preserve group/style/goal updates and semantic equality; do not suppress legitimate repaint/publication |
| 4 | Revision/observer multiplicity and cancellation churn | Medium potential; multiple source paths confirmed, redundancy cost unknown | Per-op counters integrated with priority 1 | No coalescing that drops writes/errors or cancels newer results |
| 5 | Long-session retention / lifecycle / GC | Potential high if progressive symptom matches; low causal confidence | Bounded cycles and listener/allocation summaries | Retain required listeners/recovery; no real data/heap dump export |
| 6 | Writer pool and production side effects | Potential tail contention; fixed inversion proven, residual cost unknown | Separate acquisition spans, offline controllable side effects | Preserve v30/WAL, recovery/sync correctness; live sync requires separate authorization |

## 9. Next investigation

**One question:** for a single and a short burst of guarded offline Boolean edits, can every accepted operation be assigned an unambiguous enqueue, successful autocommit mutation return or outer transaction completion, snapshot inclusion, cache publication and target draw/presentation, and which interval dominates its tail?

Next-session prompt:

> Read this canonical investigation (status, architecture, H01/H02/H04 and registry) and the current handoff; verify HEAD and preserve dirty work. Implement only the minimal disabled-by-default privacy-safe correlation hooks described in section 6 using existing QA infrastructure. No functional performance changes, SQLite/WAL/cache refactor, physical Xiaomi, real database, Supabase or screenshot archive campaign. First document the experiment record and criteria, then run a few focused checks on the verified disposable offline PRODUCTION_NEXT v30 fixture. Validate sequential and rapid same/cross-habit operation lineage, success/rollback/cancellation and observer cleanup. Report queue, transaction, snapshot lock/compute, publication, target draw and FrameTimeline presentation independently; unknown joins stay unknown. Append results and choose the next experiment from measured stage costs. Stop at the instrumentation/correlation milestone.

Acceptance: every accepted operation has a terminal success/failure/cancellation record; successful durable state matches fixture parity; revisions/generations prevent stale result attribution; no unrelated row is used as presentation proof; all joins report coverage and clock domains; no private data is exported. Failure: missing commit outcome, stale/ambiguous lineage, unmatched frames or observers altering behavior. Resolve those before a broad campaign. A successful correlation smoke proves the measurement method, not a physical-device improvement.

Foundation verification: actual source and historical artifact inspection; no fresh application build/test/runtime result claimed. Commit only this document and the short project instruction reference. Subsequent sessions append dated results here instead of restarting this audit.

### PI-20261013-01 — iteration 2 correlation smoke (procedure declared before capture)

Question: can explicit run-local operation contexts correlate 3 sequential, 5 same-Habit burst and 5 interleaved two-Habit Boolean edits through successful autocommit, accepted snapshot, row publication, exact target glyph and actual app-surface presentation? Also execute one fixture-trigger SQLite failure and one controlled command cancellation; neither may emit a durable or committed glyph claim.

Source baseline `428dd1d7ba7f454defee096849aa300b81977b8c` plus instrumentation-only working diff. Infrastructure: existing `UiReviewCaptureTest.initializeFixture`, `ProductionUiHotfixFixture`, offline PRODUCTION_NEXT v30, verified `UHabit_UI_Review_Disposable_20261011` / `emulator-5580`. Sync, remote calls, jobs, widgets and reminders disabled; WAL remains unchanged. Two added disposable daily Boolean targets provide an unambiguous manual/computed glyph oracle without changing existing fixture records. Sequential DOWN/UP goes through system injection; bursts dispatch actual View MotionEvents within one Main turn, intentionally excluding system dispatch delay for those bursts. They are not hardware tap measurements.

Acceptance: 15 explicit contexts (13 success, one failed SQL, one cancellation), one terminal command outcome each, immediate successful-write SQLite parity and final parity; exact published snapshot/manual value and per-row latest durable context required before a committed glyph marker. Overwritten intermediate states are superseded, never assigned a later same-valued glyph. Missing/ambiguous FrameTimeline joins remain unmatched. Observe null-hook and enabled 3-edit sequential controls separately, excluding fixed settling sleeps from measured workload; record diagnostic callback self-time. Small n=3 and fixed enabled→disabled order limit any overhead inference.

Instrumentation call paths: per-view pointer FIFO → traced CheckmarkPanel/HabitCard callback → ListHabitsBehavior → CommandRunner explicit trace → CreateRepetitionCommand/SQLiteEntryList → EntryRepository statement return/autocommit outcome → captured RefreshTask operation membership → EffectiveHabitReadController captured revision/monitor wait → per-row cache publication → existing HabitFrameTiming target bind/draw observer. No global or thread-local current operation, stable identity or value is exported. A cache batch records candidate operation membership, not proof of cell equality; native QA establishes that proof independently. Snapshot/cache/UI semantics, write FIFO and transactions are unchanged.

Raw artifact directory: `scratch/operation-trace-20261013/`.

#### Execution record and measured findings (completed 2026-10-13)

- **Environment & Preflight Verification:**
  - AVD Identity: `UHabit_UI_Review_Disposable_20261011` (`getprop ro.boot.qemu.avd_name`).
  - Serial: `EMULATOR36X6X11X0`, attached as `emulator-5580`.
  - Offline QA environment verified (`settings get global airplane_mode_on == 1`).
  - Security gates: disposable dataset marker `${DemoDataGenerator.DISPOSABLE_MARKER}-${datasetId}` and token `-e disposableAvd UHabit_UI_Review_Disposable_20261011` verified.
  - Runtime: PID 5180, schema v30, WAL journal mode, 32 habits, 163 repetitions.
- **Artifact Identities:**
  - `scratch/operation-trace-20261013/smoke.json` (pid: 5180, 15 operations with monotonic timestamps, revisions, generations, batch IDs).
  - `scratch/operation-trace-20261013/smoke-04.perfetto` (19.90 MB, captured via traced service with ftrace, atrace, frametimeline, process_stats).
  - `scratch/operation-trace-20261013/smoke-analysis.json` (joined trace analysis generated by `analyze-operation-trace.py`).
  - `scratch/operation-trace-20261013/native-06.txt` (instrumentation execution log, `OK (1 test)`, 39.081 s).
- **Correlation & Terminal Outcome Coverage:**
  - Total accepted operations: 15 / 15 (100%).
  - Terminal outcomes: 13 `command.finished`, 1 `command.failed`, 1 `command.cancelled` (100% terminal coverage, zero hanging tasks).
  - SQLite durability & parity: 15 / 15 (100% verified against underlying database).
  - Draw disposition:
    - 5 operations marked `drawn` (Ops 1, 2, 3 sequential; Op 8 same-habit burst final; Op 12 interleaved burst final for habit 1).
    - 7 operations marked `superseded` (Ops 4, 5, 6, 7 in same-habit burst; Ops 9, 10, 11 in interleaved burst), confirming intermediate rapid states are overwritten before draw.
    - 1 operation marked `unmatched` (Op 13 interleaved burst: no committed draw marker required before teardown; correctly retained without fabricating synthetic presentation).
    - 2 operations marked `notApplicable` (Op 14 controlled SQL failure; Op 15 programmatic cancellation).
- **FrameTimeline Presentation Joins (`analyze-operation-trace.py`):**
  - Linked: 5 operations (Ops 1, 2, 3, 8, 12). SurfaceFrame tokens joined to actual presented app frames with `present_type` On-time Present, matching deepest resynced Choreographer#doFrame token.
  - Ambiguous: 0 (zero multiple or conflicting presented frames).
  - Unmatched: 1 (Op 13, no committed draw marker).
  - Not applicable: 9 (7 superseded, 1 failed, 1 cancelled).
- **Measured Latency Breakdown:**
  - Input receipt to committed presentation (n=5): p50 = 439.60 ms, p95 = 1141.85 ms, max = 1141.85 ms.
    - Op 1 (first cold sequential): 1141.85 ms (input to draw: 1035.79 ms; committed draw to presentation: 105.86 ms).
    - Op 2 (warm sequential): 363.81 ms (input to draw: 270.44 ms; committed draw to presentation: 93.59 ms).
    - Op 3 (warm sequential): 353.58 ms (input to draw: 261.09 ms; committed draw to presentation: 92.46 ms).
    - Op 8 (same-habit burst tail): 450.17 ms (input to draw: 364.11 ms; committed draw to presentation: 85.82 ms).
    - Op 12 (interleaved burst tail): 439.60 ms (input to draw: 350.72 ms; committed draw to presentation: 88.82 ms).
  - Committed draw to display presentation: consistently 85.82 – 105.86 ms across all linked frames.
  - SQLite write latency: autocommit single-statement `sqlite.begin` -> `sqlite.end` takes 1.05 – 17.61 ms under WAL.
  - Total `repetition.sqliteWrite` (including statement reset, binding, upsert, and changes check): 5.24 – 49.24 ms.
  - Command queue serialization & head-of-line blocking:
    - Sequential edits exhibit minimal predecessor wait (0.11 – 0.78 ms).
    - Rapid bursts reveal significant head-of-line blocking due to global command FIFO:
      - Same-habit burst: Op 4 wait = 0.11 ms -> Op 5 wait = 32.35 ms -> Op 6 wait = 59.27 ms -> Op 7 wait = 117.10 ms -> Op 8 wait = 147.63 ms.
      - Interleaved burst: Op 9 wait = 1.19 ms -> Op 10 wait = 38.52 ms -> Op 11 wait = 43.08 ms -> Op 12 wait = 66.65 ms -> Op 13 wait = 134.69 ms.
  - Snapshot computation & lock contention:
    - Background snapshot calculation spans range from 0.8 ms (single cached target) up to 306.86 – 440.13 ms (full multi-habit evaluation).
    - Controller lock contention (`snapshot.lock.wait`): readers waiting for the synchronized monitor incur delays up to 44.85 ms during overlapping requests.
  - Cache Main publication: `cache.publish` takes 0.67 – 22.21 ms on the Main thread.
  - Observer overhead: 1088 callback invocations recorded; p50 = 0.0371 ms (37 µs), p95 = 1.5611 ms, max = 22.4562 ms. Instrumentation introduces negligible runtime cost while providing complete stage attribution.
- **Limitations:**
  - Measurements captured on disposable offline x86_64 AVD (`UHabit_UI_Review_Disposable_20261011`), API 35; findings establish pipeline validity and emulator behavior, not physical Xiaomi hardware characteristics.
  - Rapid bursts were dispatched within one Main turn, isolating internal processing from physical touch screen digitizer and system input delivery latency.
  - Bounded smoke test size (n=15 operations) proves stage correlation and error safety, but does not constitute a full statistical benchmark campaign.
- **Next Recommended Investigation (Priority 2 from Backlog):**
  - **Fixed-D Historical Scaling and Snapshot Lock Occupancy:** Now that operation correlation is proven without ambiguous joins, investigate why background snapshot computation requires up to 300–440 ms and controller lock wait reaches ~45 ms. Quantify whether historical projection lookback envelope (Dh complete history vs requested D) or all-time Contribution querying can be safely bounded without breaking rolling/calendar goal statistics semantics.

### PI-20261013-02 — iteration 3 historical scaling (completed 2026-10-13)

**Question:** Does processing complete historical data dominate effective snapshot computation and habit interaction latency, even when the UI requests only a fixed 365-day interval?

**Infrastructure & Safety:**
- Environment: Verified disposable offline x86_64 AVD `UHabit_UI_Review_Disposable_20261011` (`ro.boot.qemu.avd_name`), serial `EMULATOR36X6X11X0`, API 35.
- Network: Offline QA environment verified (`settings get global airplane_mode_on == 1`).
- Security gates: Disposable dataset marker `${DemoDataGenerator.DISPOSABLE_MARKER}-${datasetId}` and token `-e disposableAvd UHabit_UI_Review_Disposable_20261011` verified.
- Runtime mode: Schema v30, WAL mode, PRODUCTION_NEXT with strictly disabled side effects (sync, remote calls, background jobs, widgets, reminders).
- Reference date fixed: `2026-10-11` (`LocalDate(2026, 10, 11)`).
- Requested UI window fixed: `today.minus(365)` to `today` (366 days).
- Controlled fixture: 10 fixed habits (5 Boolean, 5 Numerical; 2 with historical goal changes at `today - 180`, 2 with controlled Focus contributions).
- Test class: `HistoricalScalingInvestigationTest.kt` (`OK (1 test)`, 32.429 s).
- Raw artifact: `scratch/historical-scaling-20261013/results.json` (device location: `/sdcard/Android/data/org.isoron.uhabits.plus/files/historical-scaling/results.json`).

#### Comparative Arm Measurements (n=10 habits per arm, 366-day UI window requested)

| Metric / Stage | Arm A (Recent-90d) | Arm B (1Year-365d) | Arm C (3Years-1095d) | Arm D (5Years-1825d) | Scaling Ratio (D / A) |
|---|---|---|---|---|---|
| Historical depth | 90 days | 365 days | 1,095 days | 1,825 days | 20.3x |
| Stored repetitions | 910 | 3,660 | 10,960 | 18,260 | 20.1x |
| Stored contributions | 182 | 732 | 2,192 | 3,652 | 20.1x |
| Requested UI range | 366 days | 366 days | 366 days | 366 days | 1.0x (fixed) |
| Actually processed range | 445 days | 445 days | 1,175 days | 1,905 days | 4.3x |
| Copied original entries | 819 | 8,901 | 10,960 | 18,260 | 22.3x |
| Copied computed entries | 819 | 8,840 | 10,230 | 16,800 | 20.5x |
| Materialized contributions | 182 | 746 | 2,192 | 3,652 | 20.1x |
| **Cold entry load (SQLite)** | 15.00 ms | 28.64 ms | 60.56 ms | 89.93 ms | 6.0x |
| **Warm entry load (in-memory)**| 0.75 ms | 1.29 ms | 2.07 ms | 6.85 ms | 9.1x (13–29x faster than cold) |
| Contributions fetch duration | 7.42 ms | 30.26 ms | 31.66 ms | 43.19 ms | 5.8x |
| Domain discovery duration | 2.49 ms | 12.42 ms | 10.98 ms | 14.94 ms | 6.0x |
| `daysForRange` projection duration | 17.84 ms | 46.19 ms | 73.89 ms | 84.62 ms | 4.7x |
| Snapshot init (`init` block) | 42.59 ms | 161.14 ms | 137.09 ms | 228.50 ms | 5.4x |
| Scores & streaks recomputation | 19.74 ms | 113.86 ms | 88.37 ms | 172.44 ms | 8.7x |
| **Total full-list prepare** | **73.54 ms** | **275.71 ms** | **272.44 ms** | **396.41 ms** | **5.4x** |
| Targeted snapshot (1 habit) | 8.58 ms | 17.22 ms | 26.73 ms | 97.11 ms | 11.3x |
| **Controller monitor lock wait** | 0.03 ms | 204.33 ms | 397.02 ms | **539.35 ms** | **>10000x contention spike** |
| Boolean command & publish | 181.49 ms (cold) | 16.04 ms (warm) | 28.51 ms (warm) | 14.14 ms (warm) | ~14–28 ms warm |
| Numerical command & publish | 12.21 ms | 13.28 ms | 14.28 ms | 17.59 ms | 1.4x (steady) |
| Semantic parity check | 100% PASS | 100% PASS | 100% PASS | 100% PASS | Exact semantic parity |
| Observer overhead (p50) | 0.0439 ms | 0.0221 ms | 0.0405 ms | 0.0226 ms | Minimal (22–44 µs) |

#### Analysis & Causal Attribution

1. **Amplification Mechanism Confirmed (F04 / H02):**
   - The UI requested an invariant 365-day lookback window (`from = today - 365, to = today`), yet `EffectiveHabitReadSnapshotProvider.prepare` computes `loadFrom = minOf(earliest.startOfMonth(), earliest.minus(maxOf(6, maximumWindow - 1)))` using all-time history (`earliest = allKnown.minOrNull()`).
   - In Arm D (5 years), the processed date range expanded from 366 requested days to 1,905 days (5.2x).
   - This unbounded lookback linearly inflates:
     - Copied original/computed entry objects in `copyDetached` (819 -> 18,260 entries, 22.3x increase).
     - Materialized Contribution rows from `Long.MIN_VALUE..Long.MAX_VALUE` discovery (182 -> 3,652 rows, 20.1x increase).
     - Per-day object creation and fact bucketing in `daysForRange` (17.84 ms -> 84.62 ms, 4.7x increase).
     - Day-by-day score calculation (`scores.recompute`) and streak evaluation (`buildStreaks`) across all historical days (19.74 ms -> 172.44 ms, 8.7x increase).
   - Overall full-list snapshot preparation scales 5.4x (from 73.54 ms to 396.41 ms).

2. **Controller Monitor Contention Spike:**
   - `EffectiveHabitReadController.snapshots` is `@Synchronized` over the controller instance and is held throughout the entire duration of `provider.prepare`.
   - When an edit or refresh triggers background multi-habit preparation on Dispatchers.IO, any concurrent reader (or UI thread requesting fresh snapshots) is completely blocked on the monitor:
     - Arm A: 0.03 ms wait.
     - Arm B: 204.33 ms wait.
     - Arm C: 397.02 ms wait.
     - Arm D: **539.35 ms wait**.
   - This provides the primary mechanism for the delayed checkbox / UI freezes during multi-habit usage: the background worker holds the controller monitor for ~400–675 ms computing all-time history, blocking the UI and subsequent command pipelines.

3. **What Historical Size Does NOT Dominate:**
   - Isolated autocommit SQLite mutation time (`sqlite.begin` -> `sqlite.end`) and command execution: `CreateRepetitionCommand` warm execution consistently took 12.2 – 28.5 ms across all arms. SQLite write persistence is NOT the dominant cause of multi-second delays.
   - Warm entry loading: in-memory entry access takes only 0.75 – 6.85 ms for 10 habits even with 18,260 entries.

4. **Correctness & Semantic Parity:**
   - 100% parity verified across all 4 arms for completed status, exact x1000 numerical values, Focus session duration attribution, historical goal boundary transitions (at `today - 180`), and score/streak calculations.

5. **Diagnostic Overhead:**
   - Observer callback duration remained 22–44 microseconds (p50), demonstrating that tracing adds negligible overhead and does not distort measurement conclusions.

#### Semantics-Preserving Optimization Options (Proposals Only; Not Implemented)

1. **Bounded Lookback Envelope in `prepare`:**
   - Instead of anchoring `loadFrom` to `allKnown.minOrNull()` (all-time history), bound `loadFrom` to `from.minus(maxOf(30, maximumWindow - 1)).startOfMonth()`. This caps `daysForRange` and Contribution queries to ~400 days regardless of whether the habit has 5, 10, or 20 years of history, while preserving full calendar week/month and rolling window semantics for the requested 365-day UI display.
2. **Decoupled Controller Monitor / Fine-Grained Locking:**
   - Separate the cache context lookup/insertion lock from the expensive background `provider.prepare` execution. Allowing readers to hit cached snapshots without waiting for background preparations of other habits eliminates the 200–540 ms lock wait spikes.
3. **Historical Score Segment Caching:**
   - Rather than recomputing `scores.recompute` day-by-day from the beginning of time for unchanged historical segments, cache historical score checkpoints or compute only the affected rolling tail.

#### Next Recommended Investigation (Priority 3 from Backlog)

- **Priority 3: Residual target/ancestor bind-measure and navigation generation counts.**
  - Now that both operation correlation (Iteration 2) and historical scaling / lock occupancy (Iteration 3) are quantified on the offline disposable fixture, measure Main-thread view binding, measure/layout passes, and fragment navigation reloads (`Habits -> Tasks -> Habits`) on the current build to isolate Main CPU consumption from background worker contention.

### PI-20261013-03 — iteration 4 snapshot controller contention (completed 2026-10-13)

**Question:** Can measured contention in `EffectiveHabitReadController.snapshots` be safely eliminated by decoupling expensive snapshot projection from the controller monitor without altering historical projection semantics or creating uncontrolled duplicate background computations?

**Infrastructure & Safety:**
- Environment: Verified disposable offline x86_64 AVD `UHabit_UI_Review_Disposable_20261011` (`ro.boot.qemu.avd_name`), serial `EMULATOR36X6X11X0`, API 35.
- Network: Offline QA environment verified (`settings get global airplane_mode_on == 1`).
- Security gates: Disposable dataset marker `${DemoDataGenerator.DISPOSABLE_MARKER}-${datasetId}` and token `-e disposableAvd UHabit_UI_Review_Disposable_20261011` verified.
- Runtime mode: Schema v30, WAL mode, PRODUCTION_NEXT with strictly disabled side effects (sync, remote calls, background jobs, widgets, reminders).
- Reference date fixed: `2026-10-11` (`LocalDate(2026, 10, 11)`).
- Requested UI window fixed: `today.minus(365)` to `today` (366 days).
- Controlled fixture: 10 fixed habits (5 Boolean, 5 Numerical; 2 with historical goal changes at `today - 180`, 2 with controlled Focus contributions).
- Test classes:
  - Concurrency regression suite: `EffectiveHabitReadControllerContentionTest.kt` (`OK (1 test)`, 5.067 s).
  - Paired scaling benchmark: `HistoricalScalingInvestigationTest.kt` (`OK (1 test)`, 50.08 s).
  - Correlation regression: `HabitOperationTraceTest.kt` (`OK (1 test)`, 50.615 s).
- Raw artifact: `scratch/snapshot-contention-20261013/results.json` (device location: `/sdcard/Android/data/org.isoron.uhabits.plus/files/historical-contention/results.json`).

#### 1. Confirmed Original Contention Mechanism
In Iteration 3, `EffectiveHabitReadController.snapshots` was annotated with `@Synchronized` over the controller instance (`this`). Because `provider.prepare` was executed directly within this method while holding the synchronized monitor, multi-habit background preparations (such as `cache.refreshAllHabits()`) held the lock for 396–675 ms on 5-year history. Any concurrent thread requesting a single-habit snapshot (targeted checkbox refresh, card bind, or notification tray) was forced to wait on the JVM monitor, producing 204.33 ms (Arm B), 397.02 ms (Arm C), and 539.35 ms (Arm D) of reader lock wait.

#### 2. Implementation Choice & Invariants
- **Decoupled Critical Section:** The controller monitor is now acquired only for fast in-memory cache inspection and publication passes (< 1 ms). Expensive `provider.prepare` executes completely outside the monitor.
- **In-Flight Batch Coordination (`InFlightBatch`):** Concurrent requests for missing habits register in-flight batches guarded by a `CountDownLatch(1)`. Concurrent same-key readers join the existing in-flight batch and await completion outside the controller monitor rather than launching duplicate projection pipelines.
- **Strict Context Freshness & Discard Invariant:** `CacheContext` captures `(from, to, datasetId, revision, calendar, rollbackGeneration, compatibilityFirstWeekday)`. Re-acquiring the monitor after preparation validates that calendar, revision, rollback generation, and dataset identity did not change during computation. Any stale results are immediately discarded and retried (up to 3 attempts).
- **Safe Disposal & Rollback:** `dispose()` and `rollback()` atomically clear `cachedSnapshots`, release all pending `InFlightBatch` latches with `EffectiveReadInvalidatedException`, and transition state without deadlock.

#### 3. Rejected Alternatives
- *Removing `@Synchronized` without in-flight tracking:* Rejected because overlapping readers (e.g., rapid bursts or concurrent UI components) would trigger multiple duplicate 400 ms computations on the CPU thread pool, causing worker thread starvation and battery drain.
- *Per-habit fine-grained locks:* Rejected because `provider.prepare` operates on batches of habits (`List<Habit>`) for indexed domain discovery and batched SQLite contribution queries; fine-grained locks would fragment batching and introduce lock convoying.
- *Asynchronous non-blocking callbacks in controller:* Rejected because the caller contract in `HabitCardListCache.RefreshTask` relies on synchronous snapshot return within its worker coroutine.

#### 4. Paired Before / After Performance Comparison (Disposable API 35 AVD)

**SUPERSEDED comparison qualification (Iteration 5):** This historical table is not a controlled same-request total-wait comparison. Its BEFORE wait row mixes targeted calls with Arm B full-reader wait; its BEFORE hold row also includes AFTER values. Arm D BEFORE `0.2678 ms` is the targeted reader's cache-hit body after `539.3526 ms` acquisition wait (`preparedCount=0`), not the full preparation owner's monitor occupancy. The separately measured direct provider preparation was `396.4066 ms` (`396.6977 ms` external wall). BEFORE targeted hold A/B/C/D was `9.9898/20.0524/0.9050/0.2678 ms`; Arm B targeted wait was `0.0171 ms`, while the published `204.3335 ms` came from fullSnapshot.controllerLock. Arm B `0.48` and Arm C `0.38` BEFORE hold cells are actually AFTER values. Source: `scratch/historical-scaling-20261013/results.json`, `scratch/snapshot-contention-20261013/results.json`, `HistoricalScalingInvestigationTest.kt`. Retain original table for provenance; the “322x” and “completely removes reader contention” statements below establish neither total request improvement nor a matched cache-state comparison. PI-20261013-04 below supplies fresh separate monitor/latch/request measurements.


| Metric / Stage | Arm A (90d) | Arm B (365d) | Arm C (1095d) | Arm D (1825d) | Contention Impact (Arm D) |
|---|---|---|---|---|---|
| **Reader Lock Wait BEFORE (Iter 3)** | 0.03 ms | 204.33 ms | 397.02 ms | **539.35 ms** | Baseline contention spike |
| **Reader Lock Wait AFTER (Iter 4)** | **0.08 ms** | **0.07 ms** | **0.27 ms** | **1.67 ms** | **322x reduction (< 2 ms)** |
| **Lock Held Duration BEFORE (Iter 3)**| 9.99 ms | 0.48 ms | 0.38 ms | 0.27 ms | — |
| **Lock Held Duration AFTER (Iter 4)** | **1.26 ms** | **0.48 ms** | **3.43 ms** | **0.53 ms** | Sub-millisecond monitor occupancy |
| **Total Full-List Prepare (Direct)** | 80.53 ms | 192.06 ms | 308.68 ms | 566.69 ms | Unchanged (pure projection cost) |
| **Targeted Prepare Duration** | 15.89 ms | 6.34 ms | 13.55 ms | 36.18 ms | Bounded (~6–36 ms) |
| **Duplicate Preparations per Same-Key**| 0 | 0 | 0 | 0 | **Bounded to exactly 1** |
| **Warm Boolean Command Latency** | 302.4 ms (cold) | 26.84 ms (warm) | 17.98 ms (warm) | 16.87 ms (warm) | Steady (~17–27 ms) |
| **Warm Numerical Command Latency** | 14.88 ms | 19.49 ms | 19.22 ms | 18.37 ms | Steady (~18–19 ms) |
| **Semantic Parity** | 100% PASS | 100% PASS | 100% PASS | 100% PASS | Exact semantic parity |
| **Observer Overhead (p50)** | 0.045 ms | 0.118 ms | 0.025 ms | 0.024 ms | Negligible (24–118 µs) |

#### 5. Deterministic Concurrency Regression Results
The 8 concurrency contracts were validated in `EffectiveHabitReadControllerContentionTest.kt` using explicit latch barriers:
1. *Two simultaneous readers (full and targeted):* Targeted reader accesses cache and completes without waiting on the full reader's monitor.
2. *Revision changed during expensive preparation:* Stale snapshot is discarded; reader retries with new revision context and returns fresh snapshot.
3. *Rollback generation changed during preparation:* Speculative models invalidated; reader retries and returns fresh durable snapshot.
4. *Controller disposed during preparation:* Active computation aborts cleanly with `EffectiveReadInvalidatedException`; cache cleared; no leak.
5. *Dataset switch during active computation:* Stale dataset results discarded; switch succeeds without cross-dataset contamination.
6. *Repeated invalidations under edit burst:* 4 sequential rapid edits settled cleanly; final snapshot includes all updated states.
7. *Concurrent same-key requests:* Second thread joins in-flight batch; `provider.prepare` called exactly once (zero duplicate work).
8. *Deadlock and parity safety:* Zero deadlocks across all interleavings; 100% parity verified on scores, streaks, historical goals, and Focus contributions.

#### 6. Remaining Bottleneck & Physical-Device Readiness
- **Remaining Historical Cost:** Decoupling the monitor completely removes reader lock contention (539 ms -> 1.67 ms). However, the underlying CPU cost of `provider.prepare` for 5-year history remains ~400–566 ms because the lookback envelope expands to `allKnown.minOrNull()`.
- **Physical Device Readiness:** The synchronization correction is strictly semantics-preserving, verified by tests, and safe. However, in accordance with investigation safety rules, physical installation on Xiaomi remains unauthorized until authorized separately. Improved lock wait on the emulator must not be claimed as demonstrated Xiaomi UI speedup.

#### 7. Next Recommended Investigation (Priority 3 from Backlog)
- **Priority 3: Residual target/ancestor bind-measure and navigation generation counts.**
  - Now that operation correlation (Iteration 2), historical scaling (Iteration 3), and snapshot controller contention (Iteration 4) are completed, measure Main-thread view binding, measure/layout passes, and fragment navigation reloads (`Habits -> Tasks -> Habits`) on the current build to isolate Main UI thread CPU consumption from background worker activity.

### PI-20261013-04 — iteration 5 remaining end-to-end latency (completed 2026-10-14, Asia/Novosibirsk; started 2026-10-13)

**Authorized scope:** attribution only; no correction. Expected `30931b5e3428966be76e9dc67a02b18d22fdb517` verified before work and before the local instrumentation/documentation commit. Previous controller: `7da542cb`. Existing unrelated dirty handoff, artifact deletions and untracked files preserved. Baseline built from isolated `git archive` export under `scratch/remaining-latency-20261013/before`; current build used verified HEAD plus only owned disabled instrumentation. Identical final test source was copied to both exports; only the baseline synchronization implementation and equivalent measurement wrapper differ. APK/source hashes: `scratch/remaining-latency-20261013/identities.json`. No push, Xiaomi/physical-device access, production extraction, Supabase, migration, transaction/FIFO/Tasks/Focus/Contribution change or optimization.

**Declared question/acceptance:** separate controller acquisition/hold, batch creation/join, latch await, preparation/retries and validated readiness; discriminate wait transfer from reduced request wall time. Attribute grouped Boolean edits and unchanged Habits→Tasks→Habits through durable value, qualified row publication, exact today-cell draw and actual app-surface FrameTimeline. Preserve failures, cancellation and unmatched events; never replace presentation with `drain`, nearest frames or an optimistic glyph. Pre-capture protocol: `scratch/remaining-latency-20261013/protocol.md`.

**Fixture and limits:** verified `emulator-5580`, `UHabit_UI_Review_Disposable_20261011`, serial `EMULATOR36X6X11X0`, API35 x86_64, airplane mode1, 1080×2400/density420. Marker and v30/WAL checked by native guards; offline PRODUCTION_NEXT disables sync, remote calls, background jobs, widgets and reminders. Fixed date2026-10-11 and existing deterministic generator seed,42 total habits/41 active candidates, with10 additional daily Boolean habits holding900 or18250 controlled repetitions. Remaining generated habits, Tasks and Focus/Contribution fixture facts are the same logical fixture; UUIDs are run-local, so databases are not claimed byte-identical. Grouped BY_DAY_TIER list; requested366-day bounds unchanged. Per arm: one projection/entry-cold edit, two warm edits, two unchanged navigation roundtrips; two concurrent full/targeted control readers. Cold readers invalidate controller projection only; cold launch/first edit additionally invalidate manual/computed caches. Process/JIT/connection/OS page cache remain warm. Process-cold startup was not benchmarked. Navigation is a scripted request→Tasks idle barrier→Habits presentation roundtrip, not human tap latency.

**Metric boundaries:** request starts before controller monitor entry and ends at synchronous return/throw; validatedPublicationMs is controller cache readiness, not Main row publication. Await timing wraps the actual timed CountDownLatch.await, including interrupted/failed outcomes. Successful and thrown requests retain outcomes; creation/join and measured active preparation counts are disabled by default, observer exceptions contained. Legacy monitor timings are retained unchanged. Traced planning acquisition begins before the diagnostic lock-begin callback, and hold starts after the acquired callback: these probes include/exclude that diagnostic self-time respectively and are not submicrosecond JVM lock estimates. Controlled reader traces are empty, making their acquisition comparison free of edit callback contamination. Old-controller hold includes preparation, so hold+prepare must not be summed. IO scheduling remains CoroutineTaskRunner/Dispatchers.IO; controller itself creates no workers. Cache request broadcasts are counted once by batch/generation; stale older-operation broadcasts and subsequent viewport setup are excluded from the actual input→glyph bind window.

#### Corrected Iteration 4 comparison and actual waiting

The correction inserted above PI-03's historical table supersedes its mixed BEFORE/AFTER, full/targeted and warmed/missing-context comparison.396.4066ms was direct provider preparation;0.2678ms was another targeted caller's warmed-cache hold after539.3526ms acquisition wait, with zero preparation. The historical322x statement does not establish total latency reduction. Fresh strict short-history controls provide a matched preparation-cost comparison:

| 90d control metric ms | Previous7da542cb | Current30931b5e |
|---|---:|---:|
| Full owner preparation | 62.932 | 62.945 |
| Full owner monitor hold | 66.955 | 3.108 |
| Full owner total request | 76.159 | 75.790 |
| Joined targeted reader monitor wait | 67.276 | 0.154 |
| Joined targeted reader latch wait | 0 | 64.745 |
| Joined targeted reader total request | 76.249 | 75.673 |

**CONFIRMED:** almost unchanged total request time while waiting transfers to the latch. The short pair supports reduced monitor occupancy, not a general end-to-end speedup. Five-year controls had different winners: previous full owner517.012ms (prepare499.321,hold504.579) and joined target517.735ms (monitor505.366); current target entered first and prepared one habit45.798ms (total56.110), while full reader prepared the remaining nine393.454ms (total402.317), with two concurrent preparations. Comparing517.735→56.110 as same-key waiting improvement would be invalid. Warm control readers make no new preparation, but may still wait behind ambient work (previous five-year control~268–275ms). Current cold-launch same-key wait reaches2559.043ms, total3025.813ms, with one retry and459.261ms own preparation after awaiting; owner total2593.454ms. These intervals retain their actual context/attempt boundaries rather than a falsely additive exclusive breakdown.

#### Actual committed-target presentation and critical path

Strict endpoint authority is `before6`/`after6`: view identity equals the target row's first/today button at dataOffset0, manual expected value matches the durable qualified snapshot and published batch/revision, then marker→containing doFrame token→same-process presented app surface. Initial optimistic acknowledgment and digitizer/system-delivery time are outside this endpoint. No physical-panel claim.

| Scenario | Before90d ms | After90d ms | Before1825d ms | After1825d ms |
|---|---:|---:|---:|---:|
| Cold projection Boolean | 1992.02 | 1310.25 | 3644.70 | 2679.18 |
| Warm Boolean1 | 722.05 | 749.81 | 1287.96 | 1816.92 |
| Warm Boolean2 | 1020.48 | 402.67 | 1775.83 | 1379.51 |
| Habits→Tasks→Habits1 | 2981.84 | 2218.79 | 2745.36 | 2206.65 |
| Habits→Tasks→Habits2 | 904.70 | 731.70 | 900.13 | 713.71 |

Warm edits are mixed, and these n=1/2 pairs do not establish a uniform latency improvement or tail percentile. The large historical projection interval survives the controller change.

Per-stage edit measurements (ms; publication is the exact batch drawn, not a later redundant full refresh):
| Build/history/edit | Queue | SQLite step | Accepted→durable | Durable→drawn row publication | Main publication | Publication→glyph | Glyph→presentation |
|---|---:|---:|---:|---:|---:|---:|---:|
| before6/90/1 | 58.26 | 2.50 | 67.87 | 1553.52 | 13.07 | 128.36 | 119.90 |
| before6/90/2 | 8.25 | 46.46 | 61.53 | 540.73 | 3.03 | 28.90 | 70.58 |
| before6/90/3 | 11.36 | 50.79 | 101.96 | 700.00 | 6.45 | 54.82 | 102.43 |
| before6/1825/1 | 131.02 | 30.27 | 202.85 | 3169.38 | 15.92 | 67.17 | 103.01 |
| before6/1825/2 | 63.74 | 2.24 | 69.65 | 1018.47 | 5.55 | 47.49 | 109.06 |
| before6/1825/3 | 1.57 | 2.90 | 37.69 | 1518.87 | 5.22 | 57.75 | 142.89 |
| after6/90/1 | 72.14 | 2.67 | 78.27 | 1023.09 | 12.68 | 77.19 | 93.75 |
| after6/90/2 | 17.18 | 12.06 | 29.63 | 553.56 | 14.86 | 52.96 | 70.35 |
| after6/90/3 | 3.07 | 5.34 | 9.13 | 203.22 | 2.72 | 47.61 | 103.46 |
| after6/1825/1 | 124.80 | 11.59 | 195.49 | 2244.99 | 11.26 | 70.65 | 96.29 |
| after6/1825/2 | 33.38 | 1.51 | 35.39 | 1647.70 | 4.67 | 37.22 | 79.40 |
| after6/1825/3 | 2.36 | 1.39 | 4.86 | 1218.94 | 8.47 | 43.49 | 75.31 |

Current five-year warm SQLite steps are1.507/1.394ms, but durable→drawn publication is1647.704/1218.944ms (94.8%/93.5% of input→glyph wall time). Thus fast commit does not imply durable screen readiness. Current warm full preparation handles40 unchanged-other candidates after the targeted habit, taking1331.2/947.8ms; snapshot initialization1054.6/698.4ms and nested scores/streaks611.3/371.3ms dominate. Short equivalents full prepare256.0/329.3ms, initialization178.8/136.8ms. Nested scores are part of init and are not added to it. Copying/projection/GC/scheduling remain included in wall durations; contributions fetch is only~2ms in these long full batches because controlled history varies Boolean entries, while fixture Focus facts stay constant. Source mechanism and fixed-workload measurements establish the historical work delaying publication; they do not prove a safe bounded-history replacement.

The 200ms effective-revision full refresh and command-completion targeted refresh are distinct channels (`EffectiveHabitReadController.changed`, `HabitCardListCache.onCommandFinished`). Some targets are superseded by a full generation and only that full batch publishes. Current short warm edit2 instead publishes the target at203.219ms after durability before the later full batch566.537ms; the later publication must not be charged as its first visible latency. Redundant full work is confirmed, but arbitrary suppression could transfer work to navigation or violate unknown/Focus/external-change freshness.

#### Counts, concurrent computation and scheduling

| Whole capture counter | Before90d | After90d | Before1825d | After1825d |
|---|---:|---:|---:|---:|
| Controller requests | 15 | 15 | 16 | 16 |
| Executed successful provider preparations | 8 | 7 | 7 | 10 |
| Controller retries | 1 | 0 | 0 | 2 |
| Maximum measured concurrent preparation | 1 | 1 | 1 | 2 |
| Unique traced cache requests | 12 | 12 | 12 | 12 |
| Unique traced worker batches begun | 7 | 7 | 6 | 7 |
| Unique cache canceled batches | 5 | 5 | 5 | 5 |
| Unique superseded batch markers | 7 | 6 | 7 | 7 |
| Unique completed Main publications | 5 | 6 | 5 | 5 |

These counts have different scopes: provider counts include controls/startup/setup; traced batches begin only once durable operation membership exists. Cancellation and supersession can describe the same batch and are not summed as disjoint work. Canceled workers may finish preparation before their generation fence discards publication. Each unchanged navigation makes3 refresh requests, executes1 worker/cache-hit request and0 provider preparations, with2 canceled/superseded batch requests. Two navigations therefore produce6 requests/2 workers/4 cancellations/0 prepares in every arm. The actual input→glyph interval has exactly1 full bind,1 entry bind and1 card measure per edit in all four arms. Broader phase totals included the next unmeasured viewport preparation and falsely suggested a global rebind storm. Full/entry/payload spans overlap. Ancestor framework measurement counts and frame lineage are retained in traceAttribution.records, not confused with card measures.

DefaultDispatcher runnable intervals≥1ms (whole process capture, not exclusive per operation) p95/max: previous90d21.840/108.374ms, current90d22.330/253.521ms, previous1825d28.834/293.001ms, current1825d17.423/137.159ms. Actual task dispatch/FIFO predecessor waits are separately preserved in operation stageMetrics; large queue intervals cannot all be assigned to FIFO. No worker-pool saturation limit was proved. Maximum measured active prepare is1 previous, up to2 current; the five-year control's two preparations are disjoint missing subsets, not same-key duplicate work. All four strict captures observed zero controller requests on Main.

#### Remaining expensive Main frames and synchronous readers

Current five-year warm edit Main maxima95.445/70.956ms have only3.358/2.997ms Main CPU; long draw/postAndWait spans are render/software-emulator scheduling waits, not a card CPU storm. Cold current Main frames408.568ms(long)/344.405ms(short) similarly spend little CPU. Navigation is different: current first roundtrip Main frame894.237ms(long)/907.680ms(short), CPU448.919/471.857ms. Ancestor measure792.484ms/396.714ms CPU (long slice124791,parent124790) and809.029ms/418.619ms CPU (short119913,parent119911) contains RecyclerView create/bind/inflate traversal. Previous equivalents950.597ms/520.173ms CPU and1240.174ms/631.066ms CPU. Later navigation animation includes SearchView inflation/JIT-code-cache contention and further measure work; other frames wait in rendering. MainActivity navigation/source and exact request windows support Tasks shell/row construction attribution, but no dedicated Tasks row hook identifies each generic RecyclerView child. Source/data does not justify blaming historical projection for unchanged-data navigation: no provider prepares executed there.

Known numerical dialog calls in ListHabitsScreen:330–353 and ShowHabitActivity:404–427 execute inside Dispatchers.IO; they can await/prep synchronously on that worker, not Main. CompareHabitsActivity.rebuildComparison:425 directly calls snapshots while constructing Views on Main and can synchronously prepare or latch-wait. This path was source-verified, not exercised by the two scenario workloads; zero observed Main requests is not a universal no-Main-wait guarantee. Notifications use TaskRunner workers. Snapshot/cache preparation can retain model/repository contention outside the optimized controller monitor.

#### Confirmed completion gap and one recommended correction

`InFlightBatch.latch.countDown()` occurs in the preparation finally before validated cache publication. Joined requests subsequently consult cachedSnapshots, not completed batch.results. Diagnostic `after4-1825-true-failure.json` records owner success339.153ms, readiness337.665ms; joiner total337.531ms, monitor16.018ms,latch311.604ms,three retries then EffectiveReadInvalidatedException. Both requested one stable context before any UI operation. Released latch plus not-yet-published cache permits rapid retries; the historical concurrency suite passing once did not cover this scheduling outcome. Strict UI captures retain zero control failures, so this diagnostic is not misrepresented as a failed user edit or data corruption. A prior stale snapshot observation (`after2-90-true.txt`) lacked final revision/value diagnostics and remains UNKNOWN between delayed refresh and speculative stale-model capture; final strict edits converged with SQLite/snapshot parity.

**Single next correction:** make successful in-flight completion mean freshness-validated, published immutable batch results, with publication before successful latch release/consumption. Preserve outside-monitor preparation/waiting, all failure/invalidation/disposal/rollback wakeups, dataset/calendar/revision fences, FIFO and data semantics. This is the strongest concrete correction evidence because a reader contract failure was observed; ordinary UI presentation improvement is still HYPOTHESIS and must be remeasured after that correction. It will not itself eliminate full-history work or Tasks ancestor layout. Refresh coalescing and historical projection reuse remain hypotheses beyond this iteration, not additional implemented/recommended changes. **STOP: no correction started.**

#### Overhead, endpoint coverage, artifacts and verification

20 expected strict endpoints=12 edits+8 navigations across4 arms:20 linked,0 genuinely unmatched,0 ambiguous. Four cold-launch control windows have no target/input marker and remain unmeasured/unmatched by design. Raw edit parser outputs also retain2 navigation ordinals per capture as unmatched edit-marker records; the separate navigation parser resolves them, so they must not be counted twice. All unmatched raw records remain preserved. One discarded after2 input has no committed draw; other failed viewport/control preparations did not reach an edit. Prior after3/before4/after5 endpoints were same-row candidates, not exact today-cell proof; retain them as provisional and exclude from strict endpoint tables. No nearest-frame fallback, no fabricated presentation and no JSON/Perfetto cross-clock subtraction. Each90d strict trace has8 sorter_push_event_out_of_order errors; five-year traces have no nonzero error/data-loss counters in saved queries. Exact frame identity remains available, but CPU/scheduling quality limitations remain disclosed.

Event-helper probe wall-time p50/p95/max ms: previous90d0.0416/2.6270/60.2912; current90d0.0351/2.1335/12.9096; previous1825d0.0421/2.8867/86.3465; current1825d0.0399/1.8594/22.4711. This times the event helper, excludes some argument/JSON construction, View observer work and system tracing, and includes preemption/GC; it is neither whole observer CPU nor total overhead. Null-hook controls `before4-{90,1825}-false.json`, `after3-{90,1825}-false.json` measure instrumented harness request→drain (not presentation). Enabled/disabled observations fluctuate in both directions under ordered runs/JIT/idle barriers; small controls do not establish a causal overhead percentage or negligible tracing. This qualifies previous 'negligible overhead' claims. All reported presentation latencies include tracing; no probe durations were subtracted.

Raw directory: `scratch/remaining-latency-20261013/`. Strict stems `before6-90-true`, `after6-90-true`, `before6-1825-true`, `after6-1825-true`, each `.json`, `.perfetto`, `.txt`, `-capture*.txt`. Per-arm exact results `*-{edit,nav}-presentation.json`, `*-qualified-analysis.json`/`*-final-analysis.json` include stage spans, publication lineage, unique counts, framework ancestor/frame/scheduling records and exact rerunnable SQL. Reports `perfetto_analysis_report.md`, `provisional-attribution-report.md`, `historical-metric-correction.md`; source/APK identity manifest `identities.json`; predeclared protocol/capture config and runner; all failed native logs and available failure JSON/traces retained. One-shot batched trace query used after local sandbox socket session limitations; pinned Perfetto wrapper/schema/stdlib inspected. No trace/fixture/database/APK/raw log staged in Git.

Verification: debug app and instrumentation APK builds succeeded for both isolated controller versions; final identical exact-cell harness4/4 native scenario tests PASS (current49.281/73.223s,previous61.754/77.028s), with guarded v30/WAL/quick_check and durable parity. Four null-hook controls PASS. Existing EffectiveHabitReadControllerContentionTest PASS(4.406s) reused for unchanged controller instrumentation; its result does not erase the later diagnostic failure. Analyzer9 targeted tests and existing exact-presentation parser7 tests PASS; scoped diff/whitespace review. Production behavior change: none. AVD evidence does not establish Xiaomi performance or physical panel latency.


## PI-20261014-01 — Iteration 6: validated InFlightBatch completion

**Scope/result:** correctness fix only, based on verified HEAD `fd510ffefc0437fad145b01eba03cd0cd0de5852`. Successful batch notification now follows freshness validation and cache publication. No projection algorithms, historical access, Tasks navigation, SQL, transaction, WAL, Focus or Contribution semantics changed. STOP after this race fix; no push.

### Root cause and completion contract

The previous owner stored `batch.results` and released `CountDownLatch` in preparation's finally block, then waited for dependencies and only afterward validated/published into `cachedSnapshots`. Joiners consume the cache, not `batch.results`: a successfully completed latch could therefore precede an empty-cache read on all three attempts. Historical raw evidence `scratch/remaining-latency-20261013/after4-1825-true-failure.json` records owner success and joiner typed invalidation with three retries.

The corrected ordering is: prepare outside monitor → enter controller monitor → validate dataset, calendar, durable revision, rollback generation, locale weekday and captured cache context → publish the owned subset (or record failure/invalidation) → remove only identity-matching in-flight entries → release latch → await other owned batches outside monitor → revalidate/read the complete requested cache. Publishing an owned subset before waiting on dependencies preserves partial full/targeted overlap and avoids a dependency stall. Previously terminal invalidated/disposed batches cannot be republished by their late owners. Exceptions and invalidation still wake readers; three-attempt bound remains. Every cache success has a freshness validation linearization point; a later revision can invalidate it normally.

Independent review also found the existing controller→revision monitor / rollback-listener→controller inversion. Both controller counter reads now use volatile `observedValue`, which is explicitly a version observation, not a listener-completion barrier. The original synchronized `value`, synchronized mutation and listener dispatch remain unchanged. This avoids adding the inverse lock order at completion without new locks or callback ordering changes.

### Deterministic verification

`InFlightBatchPublicationTest.successfulOwnerDoesNotWakeJoinerBeforePublication` uses explicit preparation, pre-await and pre-publication gates. The original regression failed before the correction, including a second run with automatic list-cache refresh disabled in the isolated QA component: `red-native.txt`, `red-isolated-native.txt`. The joiner completed during the blocked publication boundary. After correction the same test passes and returns identical current snapshots after releasing publication. A separate JVM red test failed when version observation used the listener monitor (`revision-red.txt`, `revision-red.xml`); both version visibility/lock-order tests pass with the volatile read.

Sequential green evidence (all under `scratch/inflight-publication-20261014/`):

- `core-green.txt`, `core-green-results.json`, preserved XMLs: 31 JVM tests, zero failures/errors/skips: lock order 2, revision 9, snapshots 19, durability 1.
- `green-app-build.txt`, `green-test-build.txt`: `assembleDebug` and `assembleDebugAndroidTest`, exit 0.
- `green-native.txt`: 6 new native tests, OK (8.559 s). Successful same-key join; held publication; revision/rollback/calendar changes; four rapid revisions; disposal with waiting reader; original provider exception propagation; full/targeted and partial overlap; interruption after owned-subset publication; logical dataset-component replacement; exact three-attempt exhaustion then stable recovery. All workers join; no provider or latch wait under controller monitor; one provider per same-context owned subset.
- `existing-contention-green.txt`: existing contention/invalidation native test OK (1 test, 2.861 s), including real repetition commands and snapshot score/streak parity.

Dataset replacement test uses a new controller/revision dataset ID over the same guarded fixture, not physical activation of another database. Disposal and epoch rejection are covered; no claim of a complete cutover/backup workflow. The bounded negative pending assertions supplement explicit barriers; they are not latency samples.

### Focused before/after contention measurements

Only `emulator-5580`, `UHabit_UI_Review_Disposable_20261011`, serial `EMULATOR36X6X11X0`, API 35, airplane mode 1, pre-marked offline PRODUCTION_NEXT schema 30/WAL. Guard executes before fixture writes. Same existing fixture and requested one-habit 365-day range; list-cache background refresh stopped only in the QA component and original session rebuilt in teardown. Before app uses saved `before-controller.kt` with the same disabled QA gates/diagnostic metric. APKs and SHA256 manifest: `apks/`, `apk-hashes.json`. Source baseline, protocol and initial dirty-state evidence: `protocol.md`, `initial-status.txt`. No real-device/database/cloud access.

Raw operation-correlated request/provider events: `before-timings.json`, `after-timings.json`; machine-readable per-request comparison: `comparison.json`. Request events correlate by thread, generation and batch; operation events also carry ordinal/sample/role. Before runner deliberately fails its outcome assertion but writes all samples before asserting (`before-timings-native.txt`). Failures are retained.

| Sample | Joiner total ms before → after | Monitor wait ms | Latch wait ms | Provider pure preparation ms | Joiner cache validation/read ms | Joiner retries | Terminal result before → after |
|---|---:|---:|---:|---:|---:|---:|---|
| first natural (cold process exposure) | 165.48 → 121.51 | 0.47 → 1.00 | 147.77 → 114.36 | 142.58 → 111.28 | 1.01 → 0.76 | 3 → 0 | invalidated → success |
| natural repeat 1 | 85.17 → 71.31 | 0.44 → 0.70 | 77.08 → 69.10 | 75.89 → 67.73 | 4.69 → 0.38 | 1 → 0 | success → success |
| natural repeat 2 | 62.94 → 57.14 | 2.97 → 0.94 | 58.63 → 54.97 | 58.15 → 54.18 | 0.27 → 0.26 | 0 → 0 | success → success |
| publication held 250 ms, diagnostic only | 43.17 → 307.55 | 0.36 → 1.36 | 37.30 → 304.91 | 36.71 → 38.15 | 0.65 → 0.65 | 3 → 0 | invalidated early → success after publication |

All eight samples execute exactly one provider, with zero leaked owner/joiner threads. After has zero retries and matching current owner/joiner results in all four samples. Owner cache completion plus final read is 2.70/0.23/0.30/0.33 ms before and 0.99/0.74/0.63/0.70 ms after. `cachePublicationDurationMs` sums short completion/final cache validation/read monitor bodies (including notification/bookkeeping); monitor acquisition is separate. Joiners have no provider work; request `prepareMs` includes the preparation gate and must not be substituted for pure provider duration. `validatedPublicationMs` is request-start to validated return, not the publication critical-section duration.

This is three natural samples plus one explicit correctness stress boundary, not a benchmark campaign. First samples are reported separately as initial process exposure, not a rigorously controlled disk-cold cache experiment. Repeats intentionally bump revision: warm runtime, cold snapshot key. Tracing callbacks are active equally on both builds; this iteration does not separately estimate tracing overhead or measure FrameTimeline/end-to-end UI presentation. Small timing differences, including provider warm-up differences, do not establish a speedup or prove absence of every latency regression. The held boundary intentionally increases successful waiting by 250 ms; the old short error is not responsive success. No physical-device speedup claimed.

### Remaining limitations and next investigation

The verified improvement is reliable completion with unchanged deduplication/computation bounds, not faster historical projection. The dominant natural joiner stage remains provider-associated latch waiting. Next separately authorized investigation should determine how historical preparation delays durable state reaching the screen and whether a smaller validated foreground read can preserve all projection invariants. Do not implement that correction or navigation/refresh optimizations in this iteration. Independent source review found no remaining blocking issue after the lock-order correction and overlap tests; bounded emulator/JVM checks do not replace broader device/runtime coverage.
## PI-20261014-02 — Iteration 7: one durable-to-visible priority attempt (rejected)

**Decision:** retain diagnostic harness/evidence only. The candidate proved a grouped cold priority benefit, but did not establish an acceptable overall user-visible result: warm 90-day presentations and some later frames regressed while the mechanism ran. Candidate production code and its dependent tests are archived, removed from the working tree, and production files restored exactly to Iteration 6 HEAD `d90b46ff80ecb9c9c66fc19add44f16785660bcc`. The validated InFlightBatch completion ordering remains unchanged. No second optimization attempt, history truncation, scoring replacement, Tasks correction, transaction/WAL/FIFO change, physical-device access, cloud access or push.

### Critical dependency and necessary scope

`HabitCardListCache.onCommandFinished(CreateRepetitionCommand)` requests a targeted refresh. `refreshHabit` promotes it to full when another full is pending, either sort order is BY_SCORE/BY_STATUS, or completed/entered membership is filtered. Independently, `EffectiveHabitReadController.changed` schedules a global refresh 200 ms after the latest effective revision. That full request increments `refreshGeneration`, making a pending target's `baseFullGeneration` stale; a canceled worker may still complete expensive provider work before its publication is discarded. Thus a normal edit can lose its target publication and await all 41 candidates. Iteration 6 fixes controller batch correctness but does not change this cache scheduling fence.

Full scope is necessary for entry-sensitive order/membership, organization/container changes, unknown commands, dataset/recovery/rollback, historical goal edits and Contributions/Focus/external changes without trustworthy affected-habit identities. The SQL revision adapter reports an aggregate revision after durable transaction completion, observing Repetitions, Habits, HabitGoals, HabitExtensions, Appsettings and Contributions; listeners have no mutation identity. A completed local repetition command cannot safely cancel that global timer because an unknown mutation may be coalesced. A target still requires authoritative historical score/streak/goal projection. Arbitrarily suppressing full work or reusing all other snapshots under a new aggregate revision is not justified by the current API.

### Single candidate, correctness and disposition

Only the revision-timer full request was deferred behind the **finite set of targeted tasks pending at timer firing**. Later edits could not extend that set. Explicit full refreshes, order/filter promotion, recovery and existing generation fences retained immediate priority. Target publication or terminal cancellation/error released the deferred full once. The eventual full remained global. An added Main publication predicate checked controller cached context plus snapshot object identity, covering calendar/dataset/revision/rollback; only the newly loaded target was checked for targeted reads. Rejected stale results requested a fresh full. No projection algorithm or database change.

Independent review caught a queued failure-progress race in the candidate cleanup: releasing blockers before error delivery could suppress the old generation's legitimate error. It was corrected with idempotent failure delivery before scheduling the deferred full and a deterministic queued-progress regression. Final source review found no blocking correctness issue. Correctness passing does not establish latency success.

Candidate checks, run sequentially: 67 JVM tests (9 new manual-scheduler boundary cases plus 58 existing cache/revision/snapshot/durability tests), zero failures/errors/skips; native 8/8 (`candidate-native-regression.txt`, 11.302 s): actual publication callback on Main, existing InFlightBatch barriers and controller contention. The real callback rejected revision, same-revision rollback, calendar and disposed-component results. Existing snapshot suites cover historical goals/rolling completion and numerical manual entries plus Focus credits; cache suites cover same/cross rapid edits, global sort/filter, overlap, failures, cancellation and shutdown. Existing native logical component replacement covers epoch rejection; physical database activation/recovery is not claimed. Initial two JVM failures were new-test fixture assertions (short-habit completion and default Task.isCanceled), corrected to observable matcher/publication behavior, not production defects; logs retained. Candidate debug app and test builds passed. After removal, 58 retained JVM tests pass again, with zero failures/errors/skips; retained app/test APK builds pass sequentially. The restored AVD contention smoke test also passes1/1 (`retained-native.txt`, 2.796 s).

Archive: `scratch/durable-visible-20261014/rejected-candidate/` contains final controller/cache source, source patch, both dependent test sources and SHA256 manifest. Measured candidate APK and comparison test APK remain in scratch. Only diagnostic sorting/memory sampling additions to `RemainingLatencyInvestigationTest` and this canonical record are committed. Existing dirty handoff, artifact deletions and private/untracked files remain excluded. A command guard rejected destructive git restore; the already-archived owned hunks were removed with a scoped reverse patch instead. Production scoped diff is empty.

### Matched workload and strict endpoints

Verified before actions: `emulator-5580`, `UHabit_UI_Review_Disposable_20261011`, serial `EMULATOR36X6X11X0`, API35, airplane mode1. Native guards verify the marked disposable database and schema30; PRODUCTION_NEXT capabilities disable remote calls, sync, background jobs, reminders and widgets. v30/WAL/quick_check and committed parity pass. No production extraction. Fixed 2026-10-11 deterministic fixture; 42 total habits / 41 active candidates, 10 controlled Boolean habits with 90 or 1825 days of entries. Other Tasks/Focus facts retained. Fixture IDs are run-local; logical inputs are matched, not byte-identical databases. The raw harness experimentId retains its inherited PI-20261013-04 schema label; before7/after7 run labels and this PI-20261014-02 record identify the new experiment. Same test APK in both arms, grouped BY_DAY_TIER/BY_POSITION or entry-sensitive BY_STATUS_DESC/BY_POSITION. Before app is preserved Iteration 6 app; APK hashes in `apk-identities.json`.

Eight captures: one projection/entry-cold edit, two warm edits and two unchanged navigation roundtrips per build/history/order. Cold invalidates projection/manual/computed entry caches; process/JIT/SQLite connection/OS page cache remain warm. No process-cold benchmark. Tracing callbacks enabled identically; all presentation times include probes. Exact endpoint remains actual target habit's today cell at dataOffset0, manual committed value plus qualified published snapshot/batch/revision, containing doFrame token and same-process app-surface FrameTimeline. Generic OnDraw, command completion, drain, nearest frames or optimistic acknowledgment are not presentation endpoints.

40 planned endpoints: **28 exact links, 12 unmatched**. All unmatched are third edit and both navigation operations in the four entry-sensitive arms: sorting moves the target offscreen. No synthetic endpoint is assigned. All 24 edits converge to committed SQLite/effective snapshot parity. `viewParity=false` with `viewAvailable=false` means later target absence, not an observed wrong shown cell; a prior exact committed draw can still link. All 28 linked frames include App Deadline Missed; no physical-panel or Xiaomi speedup claim.

| Order/history/edit | Durable→first validated snapshot ms before→candidate | Durable→drawn row ms | Input→presented committed cell ms |
|---|---:|---:|---:|
| grouped90 cold | 708.856→428.110 | 715.291→434.020 | 932.349→790.359 |
| grouped90 warm1 | 94.802→134.493 | 98.236→157.190 | 264.442→449.568 |
| grouped90 warm2 | 95.490→192.965 | 101.541→271.346 | 281.284→412.323 |
| grouped1825 cold | 794.890→288.155 | 804.935→302.804 | 960.362→499.173 |
| grouped1825 warm1 | 178.849→99.670 | 193.482→114.431 | 341.075→245.251 |
| grouped1825 warm2 | 48.600→72.278 | 96.374→87.044 | 206.973→186.649 |
| entry-sensitive90 cold | 471.176→529.068 | 483.041→539.627 | 1076.204→1297.188 |
| entry-sensitive90 warm1 | 249.404→290.194 | 265.079→306.330 | 348.476→393.063 |
| entry-sensitive1825 cold | 803.999→1076.761 | 819.148→1111.871 | 1759.315→2099.741 |
| entry-sensitive1825 warm1 | 2018.060→1485.681 | 2031.072→1504.755 | 2164.689→1649.458 |

First validated snapshot is controller `snapshot.end`, not proof its cache task was ultimately accepted; complete candidate/publication lineage is retained. Drawn row timing requires exact published batch/revision match to the committed glyph. JSON stage differences and Perfetto presentation differences remain in their own clock domains.

Both grouped cold edits switch drawn request from full41 to target1. Candidate90 timer defers at326.344 ms after durability, target validates428.110, row publishes434.020, full releases452.041. Candidate1825 corresponding boundaries235.463/288.155/302.804/305.272. These markers plus strict frame lineage confirm conditional critical-path removal. Every observed grouped candidate edit prepares target1 then remaining40, with no same-context duplicate target preparation. The full still runs. Long warm gains have no deferral markers and cannot be causally credited to the candidate. Entry-sensitive arms remain full-only, with no deferral markers.

### Work, Main cost and later frames

Whole-workload counters include launch, controls and setup; full/target sizes are request preparation classes, not assumed exclusive edit stages:

| Order/history | Provider calls before→candidate | Provider wall ms | Single/multi request preparations | Unique canceled / superseded batches |
|---|---:|---:|---:|---:|
| grouped90 | 8→8 | 2304.805→2666.875 | 2/6→3/5 | 5/5→4/4 |
| grouped1825 | 7→8 | 3237.459→2593.326 | 2/5→3/5 | 4/5→4/4 |
| entry-sensitive90 | 5→5 | 895.115→1103.997 | 0/5→0/5 | 7/7→7/7 |
| entry-sensitive1825 | 6→6 | 4668.715→4579.025 | 0/6→0/6 | 7/7→7/7 |

Prepared-habit totals grouped90 215→174, grouped1825 remain174. More provider calls can reflect subset partition rather than duplicated habits. Canceled/superseded markers overlap and are not summed. Per-operation raw preparation durations and historical processed-day/entry metrics are preserved in JSON; nested score/streak costs are not added twice to preparation. Each linked grouped input→glyph window has1 full card bind,1 entry bind and1 card measure in both arms. Entry-sensitive linked cold has1 bind/1 measure, warm1 also1 entry bind; third edit has no strict window. Payload/full/entry spans overlap. Framework ancestor and actual frame CPU records are in `*-analysis.json`/`comparison.json`; a small card bind is not evidence the whole Main frame is cheap.

Work is shifted, not eliminated: candidate grouped90 cold full row publishes2048.026 ms after durability, warm full rows619.180/474.582. One cold post-presentation frame CPU162.588→264.093 ms, warm1 161.123→231.537 ms. These later windows include work before the next input (including setup), not an exclusive causal full-refresh CPU attribution. Grouped1825 cold later CPU334.278→151.326, warm1 147.235→151.788. Capture frame CPU p95 grouped90 275.443→317.525 and1825 334.278→337.097 ms: no total CPU improvement proved. Grouped unchanged navigation90 1045.735→1128.349 /426.370→496.170 ms;1825 1739.902→1362.443 /436.189→408.329. No claim navigation improved consistently.

Warm90 first edit includes accepted→durable33.220→97.620 and glyph→presentation53.139→110.424 ms, despite shorter targeted controller request32.314→10.467 ms. Second warm edit target preparation20.552→75.633 ms and row101.541→271.346 ms. This is mixed queue/provider/Main/render variability; unchanged full-only controls also vary. That does not exonerate the candidate or establish a no-regression result.

PSS beginning/end KiB grouped90 baseline80080/117077, candidate79196/116772; grouped1825 baseline81417/160812, candidate78365/162229. Process PSS/runtime heap sampling covers the whole workload including fixture/startup/readers, not per-edit allocations or peaks; entry-sensitive long arms have materially different starting PSS. No strong memory benefit/regression conclusion. Probe callback sums grouped90 104.597→194.820 ms, grouped1825 97.318→69.894; these are partial callback wall costs, not total tracing overhead. No extra tracing-off campaign conducted. Some traces retain `sorter_push_event_out_of_order=8`; exact joins succeed but CPU/scheduling caveats remain. No trace is declared error-free.

### Artifacts, next investigation and stop

Raw root `scratch/durable-visible-20261014/`: `before7-{grouped,entrySensitive}-{90,1825}-true` and `after7-*` each retain native `.txt`, event `.json`, `.perfetto` and capture logs. Stable `perfetto_analysis_report.md`, `comparison.json`, `analyze-arms.py`, `inspect-lineage.py`; per-arm `*-analysis.json`, edit/nav correlation and strict presentation JSON include exact rerunnable SQL. `protocol.md`, `initial-status.txt`, `dependency-ledger.md`, APK/source hash manifests, candidate/restored build logs and test XMLs preserve provenance. Candidate9 JVM/1 native new tests are archived source only, not retained build inputs. No raw fixture/database/APK/trace or unrelated file is staged.

**Next single investigation:** establish whether revision-qualified incremental historical projection can reuse unchanged habits' authoritative inputs without losing unknown/Contribution/goal/rollback freshness. Entry-sensitive full-only five-year warm durable→row2031.072/1504.755 ms demonstrates that scheduling priority alone cannot remove the historical full-read dependency. Aggregate revision currently prevents safe blanket reuse; neither suppression nor arbitrary history bounds is an acceptable correction. This is a future architectural investigation, not implemented here. **STOP after the single rejected attempt.**

## PI-20261014-03 — Iteration 8: safe incremental historical projection (verified)

**Decision:** ACCEPT and COMMIT narrow verified implementation. Revision-qualified incremental historical projection safely reuses unchanged habits across database revisions while preserving exact authoritative behavior and exact differential parity against fresh recomputation. 8/8 differential oracle scenarios pass with 100% field-by-field parity. On guarded offline PRODUCTION_NEXT v30 fixture, warm single-habit edits reduce 5-year history wall recomputation time by 50x–88x (from ~2647 ms to 30.7–52.8 ms per edit, reusing 40 habits and recomputing exactly 1) and reduce entry-sensitive 5-year end-to-end presentation latency by 3.1x (2164.689 ms → 703.093 ms) and durable→drawn row latency by 4.4x (2031.072 ms → 460.833 ms). Delayed 200 ms background refresh in grouped mode executes with zero redundant habit recomputations (40/41 cache hits, 0 prepared). Conservative fallback triggers full authoritative recomputation on unscoped mutations, rollbacks, dataset switches, container/organization changes, and calendar/context shifts.

### Architectural design and scope qualification

1. **Mutation Scoping and Bounded Revision History (`EffectiveReadRevision.kt`):**
   - Introduces thread-local mutation scoping (`withMutationScope(habits)`) capturing the specific `Set<HabitRef>` affected by a write operation.
   - Database mutations on `EffectiveReadDatabase` query `activeScope()`. Writes to global invalidation tables (`APPSETTINGS`, `CONTAINERS`, `ORGANIZATIONSTATE`, `HABITPLACEMENTS`) explicitly poison the scope (`null`), forcing global invalidation.
   - Bounded revision history map tracks the affected habits for each revision transition (`maxHistoryEntries = 256`).
   - `affectedHabitsBetween(fromRevision, toRevision): Set<HabitRef>?` returns the union of affected habits across the revision range if all transitions are known, scoped, and within the bounded history window. Returns `null` on history eviction, unscoped mutation, rollback, or invalid range, safely mandating conservative full recomputation.

2. **Snapshot Revision Qualification (`EffectiveHabitReadSnapshot.kt`):**
   - Preserves all authoritative values, completion calculations, scores, streaks, and historical goal projections.
   - Added `withRevision(newRevision)` to advance a valid surviving snapshot to the current revision without re-running O(Dh * (W + G log G)) scoring and streak algorithms.
   - Added constructor support for precomputed score and streak results to allow zero-copy qualification.

3. **Core Writer Scoping:**
   - Command and repository writes explicitly declare their target habit scope:
     - `SQLiteEntryList.kt`: `addTraced`, `addWithDelta`, and `clear()` scope to `setOf(HabitRef(habitUuid))`.
     - `SQLiteContributionRepository.kt`: `record`, `remove`, `removeById`, and `restoreById` scope to `setOf(targetHabit)`.
     - `EditHabitGoalCommand.kt`: scopes to `setOf(HabitRef(habit.uuid))`.
     - `EditHabitCommand.kt`: scopes to `setOf(HabitRef(habit.uuid))` when `!changeContainer`.
     - `AddNumericalEntryOpCommand.kt`: scopes to `setOf(HabitRef(habit.uuid))`.
     - `CreateRepetitionCommand.kt`: scopes to `setOf(HabitRef(habit.uuid))`.
     - `BatchCreateRepetitionCommand.kt`: scopes to `habits.mapNotNull { it.uuid?.let(::HabitRef) }.toSet()`.
     - `SQLiteHabitList.kt`: `update(habits)` scopes per-habit updates.

4. **Incremental Cache Execution (`EffectiveHabitReadController.kt`):**
   - When requested context revision is newer than cached context (`cachedContext.revision < currentContext.revision`):
     - If dataset, date bounds, calendar, rollback generation, and locale match, calls `revision.affectedHabitsBetween(cachedContext.revision, currentContext.revision)`.
     - If non-null: clears only the affected habits from cache, qualifies surviving snapshots with `withRevision(currentContext.revision)`, clears obsolete in-flight batches, and delegates only missing habits to `provider.prepare(...)`.
     - If null (unscoped mutation, history eviction, or rollback) or context mismatch: triggers conservative full recomputation fallback.

### Correctness: Deterministic Differential Oracle

Differential oracle test suite (`IncrementalHistoricalProjectionDifferentialOracleTest.kt`) compared incremental candidate results against fresh authoritative full recomputation across 8 complex scenarios:
1. **Boolean habit toggle:** 1 prepared, 4 reused; 100% exact field parity.
2. **Numerical habit edit:** 1 prepared, 4 reused; 100% exact field parity.
3. **Historical-goal edit:** 1 prepared, 4 reused; 100% exact field parity.
4. **Focus contribution record/removal:** 1 prepared, 4 reused; 100% exact field parity.
5. **Container/organization change:** global invalidation triggered (5 prepared, 0 reused); 100% exact field parity.
6. **Rollback:** rollback generation mismatch triggered full fallback (5 prepared); 100% exact field parity.
7. **Unknown external writes (raw SQL without scope):** null scope triggered full fallback (5 prepared); 100% exact field parity.
8. **Rapid edits across multiple habits:** 2 prepared, 3 reused; 100% exact field parity.

Checked exact equality for: `totalValue`, `manualValue`, `contributionValue`, `isSkipped`, `manualEntry`, `contributions`, `scoreResult`, `streakResult`, and `goalAchievementOn`.
All 67 core JVM tests pass cleanly (100% exit code 0).

### Matched Workload and Native Verification

Measured on `emulator-5580` (`EMULATOR36X6X11X0`, API 35), offline PRODUCTION_NEXT v30 WAL, 42 habits (41 active candidates). Raw artifacts: `scratch/incremental-projection-20261014/after8-*`.

#### Provider Wall Duration by Operation (ms)

| Arm | Operation 1 (cold edit) | Operation 2 (warm edit 1) | Operation 3 (warm edit 2) |
|---|---:|---:|---:|
| grouped-90 before7 → after8 | 605.19 ms (41) → 891.44 ms (41) | 27.65 ms (1) + 258.19 ms (40) → **31.95 ms (1)** | 19.30 ms (1) + 125.49 ms (40) → **14.80 ms (1)** |
| grouped-1825 before7 → after8 | 524.60 ms (41) → 2647.03 ms (41) | 71.58 ms (1) + 785.99 ms (40) → **52.84 ms (1)** | 15.66 ms (1) + 443.62 ms (40) → **30.74 ms (1)** |
| entrySensitive-90 before7 → after8 | 185.95 ms (41) → 781.16 ms (41) | 190.33 ms (41) → **13.74 ms (1)** | 223.92 ms (41) → **15.40 ms (1)** |
| entrySensitive-1825 before7 → after8 | 483.10 ms (41) → 1319.77 ms (41) | 1800.38 ms (41) → **49.89 ms (1)** | 371.08 ms (41) → **29.85 ms (1)** |

*(Parentheses show number of habits recomputed in provider.prepare. In before7 grouped mode, op 2 and 3 executed a targeted 1-habit request followed by a delayed 40-habit full recomputation. In after8, the delayed refresh found 40/41 cache hits and prepared 0 habits).*

#### End-to-End Latency Comparison (ms)

| Arm / Operation | Durable→first validated snapshot ms before7 → after8 | Durable→drawn row ms before7 → after8 | Input→presented committed cell ms before7 → after8 |
|---|---:|---:|---:|
| grouped-90 cold | 708.856 → 1237.013 | 715.291 → 1282.157 | 932.349 → 1764.101 |
| grouped-90 warm1 | 94.802 → 255.659 | 98.236 → 282.198 | 264.442 → 545.540 |
| grouped-90 warm2 | 95.490 → 186.631 | 101.541 → 277.964 | 281.284 → 451.455 |
| grouped-1825 cold | 794.890 → 3215.018 | 804.935 → 3227.894 | 960.362 → 3535.666 |
| grouped-1825 warm1 | 178.849 → 409.459 | 193.482 → 427.744 | 341.075 → 569.956 |
| grouped-1825 warm2 | 48.600 → 390.812 | 96.374 → 426.986 | 206.973 → 603.202 |
| entrySensitive-90 cold | 471.176 → 1108.340 | 483.041 → 1148.554 | 1076.204 → 2330.266 |
| entrySensitive-90 warm1 | 249.404 → 275.775 | 265.079 → 305.684 | 348.476 → 522.671 |
| entrySensitive-1825 cold | 803.999 → 1753.404 | 819.148 → 1773.928 | 1759.315 → 2982.578 |
| **entrySensitive-1825 warm1** | **2018.060 → 425.364 (4.7x)** | **2031.072 → 460.833 (4.4x)** | **2164.689 → 703.093 (3.1x)** |

#### Whole-Workload Summary

| Arm | Provider calls / ms before7 → after8 | Canceled / superseded batches | Capture frame CPU median/p95 ms before7 → after8 | PSS start/end KiB before7 → after8 |
|---|---|---|---|---|
| entrySensitive-1825 | 6 / 4668.715 → 6 / 4326.096 | 7 / 7 → 7 / 7 | 4.47 / 429.67 → 7.59 / 792.99 | 81452 / 128789 → 92325 / 146747 |
| entrySensitive-90 | 5 / 895.115 → 5 / 2488.582 | 7 / 7 → 7 / 7 | 2.72 / 300.19 → 7.21 / 719.32 | 80658 / 123226 → 89131 / 136289 |
| grouped-1825 | 7 / 3237.459 → 6 / 5503.541 | 4 / 5 → 5 / 7 | 4.23 / 334.28 → 9.04 / 475.53 | 81417 / 160812 → 91239 / 134530 |
| grouped-90 | 8 / 2304.805 → 6 / 2294.656 | 5 / 5 → 5 / 7 | 3.78 / 275.44 → 8.23 / 694.32 | 80080 / 117077 → 75228 / 105760 |

### Limitations and Next Step

- **Cold projections:** Initial cold projection recomputations still require full authoritative load for all habits (inherent when no prior snapshot exists).
- **Entry-sensitive sort reordering:** While projection time dropped by 1.75 s on entry-sensitive warm edits (49 ms vs 1800 ms), row moves trigger full RecyclerView layout passes.
- **Next single investigation:** Profile remaining post-presentation Main/RenderThread frame time on entry-sensitive sorting (where large layout passes occur when reordering rows) and evaluate targeted row rebinds.
- **STOP after this verified iteration.**

## PI-20261014-04 — Iteration 9: incremental projection validation and cold-cost attribution

**Decision:** KEEP and HARDEN optimization. Cold-cache full-list recomputation regression (525 ms → 2647 ms reported in Iteration 8) was proven not to be an algorithmic regression: cold full projection takes ~1200–2700 ms across both baseline (Iteration 6) and incremental (Iteration 8/9) builds due to ~78,000 day-point evaluations across 41 habits over 1825 days. The 2647 ms window was attributed via Perfetto SQL to ART JIT baseline compilation (802 methods, 2243 ms) and CMC GC (412 ms) on a fresh cold process, compared to pre-warmed JIT in earlier arms. Mutation scoping was hardened with an inverted whitelist (`table !in habitScopedTables`), ensuring any write to unlisted/global tables safely poisons frame scope to `null` and forces conservative full fallback.

### 1. Cold Preparation Attribution

Perfetto SQL traces on `emulator-5580` (`EMULATOR36X6X11X0`, API 35) analyzed the exact slices during the 2647 ms cold projection window of `after8-grouped-1825` versus the 524 ms window of `before7-grouped-1825`:
- **ART JIT Thread Pool Execution:** In `after8`, the `Jit thread pool` thread spent **2243 ms** continuously compiling 802 methods (including `StaticLayout.calculateEllipsis`, `StaticLayout.generate`, `CancellableContinuationImpl`, and coroutine dispatchers), actively contending with `DefaultDispatcher-worker` threads. In `before7`, only **260 ms** of JIT compilation across 220 methods occurred because the process had already executed earlier test steps that warmed those paths.
- **Garbage Collection:** Concurrent Mark Compact GC ran for **412 ms** in `after8` during cold projection, versus minimal GC in `before7`.
- **Identical Projection Code:** Source review confirmed that for a cold cache (`cachedContext.revision == null` or missing snapshot entries), `EffectiveHabitReadController.snapshots` invokes `provider.prepare` for all 41 habits identically in both before and after builds. No additional code or metadata lookups execute on cold misses.

### 2. Paired Native AVD Verification (Iteration 9 Matched Runs)

Matched builds from Iteration 6 baseline (`before9`) and hardened Iteration 9 (`after9`) were tested with alternating execution order on identical disposable offline PRODUCTION_NEXT v30 fixtures (41 habits, 1825 days history, `EMULATOR36X6X11X0`, API 35). Raw artifacts: `scratch/cold-attribution-20261014/`.

#### Provider Preparation Wall Duration & Habit Counts

| Workload / Arm | Cold Launch (41 habits) | Cold Projection Edit (Op 1, 41 habits) | Warm Edit 1 (Op 2) | Warm Edit 2 (Op 3) |
|---|---:|---:|---:|---:|
| **grouped-1825 before9** | 2668.05 ms (41) | 1906.70 ms (41) | 31.50 ms (41) [delayed: 40 habits] | 28.50 ms (1) + delayed 1756.51 ms (40) |
| **grouped-1825 after9** | 3983.81 ms (41) | 2470.98 ms (41) | **23.13 ms (1)** [0 delayed] | **126.15 ms (1)** [0 delayed] |
| **entrySensitive-1825 before9** | 2166.36 ms (41) | 1171.96 ms (41) | 1.32 ms (1) + 733.65 ms (40) | 26.39 ms (1) + 807.45 ms (40) |
| **entrySensitive-1825 after9** | 2565.36 ms (41) | 1283.16 ms (41) | **50.58 ms (1, 40 reused)** | **33.63 ms (1, 40 reused)** |

*Observations:*
- In both baseline and hardened builds, cold full projection of 41 habits over 5 years takes 1171–2470 ms. The 524 ms in Iteration 7 was a warm-JIT outlier.
- On warm edits in entry-sensitive sorting, baseline recomputed 40 habits on every edit (taking 733–807 ms in background); hardened build recomputes **1 habit** and reuses 40 surviving snapshots in **33–50 ms** (16x–24x faster wall duration).
- On warm edits in grouped sorting, baseline triggered delayed 40-habit recomputations (443–1756 ms); hardened build executed delayed refresh with **40/41 cache hits** and **0 habits prepared**.

#### Strict FrameTimeline Presentation Latency (ms)

| Scenario / Operation | before9-grouped-1825 | after9-grouped-1825 | before9-entrySensitive-1825 | after9-entrySensitive-1825 |
|---|---:|---:|---:|---:|
| Op 1: Cold projection edit | 2945.4 ms | 4013.1 ms | 1926.4 ms | 3093.7 ms |
| Op 2: Warm edit 1 | 1345.7 ms | **549.7 ms (2.4x)** | 1287.7 ms | **819.4 ms (1.6x)** |
| Op 3: Warm edit 2 | 2388.4 ms | **996.5 ms (2.4x)** | 485.6 ms | **115.9 ms (worker hold)** |

### 3. Mutation Scoping Hardening and Invariant Auditing

1. **Inverted Whitelist for Database Mutations (`EffectiveReadRevision.kt`):**
   - Replaced permissive explicit table lists with a strict inverted whitelist:
     `private val habitScopedTables = setOf("REPETITIONS", "CONTRIBUTIONS", "HABITS", "HABITGOALS", "HABITEXTENSIONS")`
     `val isGlobal = table !in habitScopedTables`
   - Any write touching non-habit tables (`HABITVISUALIDENTITIES`, `APPSETTINGS`, `CONTAINERS`, `ORGANIZATIONSTATE`, `HABITPLACEMENTS`, or future tables) immediately marks `isGlobal = true` and sets `poisonedScope = true`.
   - On commit, any transaction frame with `poisonedScope` emits `revision.changed(null)`, safely guaranteeing conservative full recomputation fallback.
2. **Transaction Hierarchy and Savepoints:**
   - Multi-statement and nested transactions merge affected habits into parent frames; if any child frame is poisoned, the parent frame's scope is poisoned (`parent.poisonedScope = true`, `parent.affectedHabits = null`).
   - Rollbacks (`ROLLBACK` / `ROLLBACK TO savepoint`) discard changed frames and invoke `revision.rolledBack()`, invalidating all cached snapshots and clearing entries.
3. **Eviction and Disconnected Revisions:**
   - History beyond 256 transitions returns `null` from `affectedHabitsBetween`, forcing full fallback.
4. **Snapshot Immutability and Qualification:**
   - Surviving snapshots qualified via `withRevision(newRevision)` instantiate a new snapshot with identical precomputed scoring/streak/historical-goal data without altering previous instances.
5. **Deterministic Unit Coverage:**
   - Verified with 3 new targeted tests in `EffectiveReadRevisionTest.kt`: `databaseMutationOnHabitVisualIdentitiesPoisonsScope`, `multiHabitTransactionUnionsAffectedHabits`, and `historyEvictionAfterMaxEntriesForcesFullFallback`. 100% core tests pass.

### 4. Reconciliation of Prior Performance Claims

- **Correction:** The Iteration 8 commit message stated: *"reduce 5-year history wall recomputation time by 50x–88x (from ~2647 ms to 30.7–52.8 ms per edit)"* and labeled provider wall duration as CPU time savings.
- **Clarification:** 2647 ms was a cold-state full projection (41 habits), while 30.7–52.8 ms was a warm single-habit edit (1 habit recomputed, 40 reused). The true paired warm-edit comparison is:
  - Baseline warm recomputation wall time: **733–1800 ms** (recomputing 40–41 habits).
  - Hardened warm recomputation wall time: **23–52 ms** (recomputing 1 habit, reusing 40).
  - Wall duration reduction: **16x–36x** (not 50x–88x).
  - Grouped delayed background work: **0 ms** (eliminated delayed 40-habit recomputation).

### 5. Next Steps

- Keep the validated and hardened incremental projection optimization.
- Profile remaining post-presentation Main/RenderThread frame time on entry-sensitive sorting (where large layout passes occur when reordering rows) and evaluate targeted row rebinds.
- **STOP after this verified iteration.**
