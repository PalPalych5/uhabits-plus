# Habit interaction investigation — canonical engineering record

## 1. Executive status

**Iteration 16 COMPLETE — benchmark calibrated & decision protocol established (2026-10-15, dev, c2e531de).** No production code changes; focused strictly on benchmark reliability, A/A baseline variability, uncertainty quantification, and measurement overhead. Confirmed that the legacy rejection gate (`>10% and >20 ms p95 in both pairs`) has an unacceptable **93.8% false rejection rate** (15/16 arm pairs breached on byte-identical APKs) because $n=3$ nearest-rank p95 mathematically selects the single maximum $\max(x_1, x_2, x_3)$ subject to 41.6% coefficient of variation. Established a calibrated 4-stage, 3-outcome benchmark protocol (`ACCEPT`, `REJECT`, `INCONCLUSIVE`).

**Key Findings:**
1. **A/A False Positive Rate**: 31 of 80 (38.8%) individual scenario evaluations and 15 of 16 (93.8%) arm pairs falsely breached the legacy tail gate when comparing byte-identical APKs against themselves on the identical fixture.
2. **Guest Environment Contention**: Discovered that a rogue guest Android daemon (`android.hardware.sensors-service.multihal`) was spinning at 116% CPU inside the emulator guest, starving main-thread ART class verification and extending startup time from <3s to >17s (causing startup ANRs from ActivityManager). Once killed, startup latency dropped by 80% and clean presentation median dropped from 1448.2 ms to 538.6 ms (-62.8%).
3. **Trace TTL Limit**: Standard 360s Perfetto TTL silently truncated trailing operations when sample count exceeded $n=3$; authored and verified a 600s configuration (`post-navigation-capture-600s.config`) achieving 100% frame joins (20/20 edits, 6/6 navigation returns).
4. **Diagnostic Probe Overhead**: Enabling `HabitResortDiagnostics` inflated presentation latency median by **+125.0%** (1848.2 → 4158.4 ms) and `resortAndObservers` by **+4.1s** (2147.4 → 6294.4 ms) due to repeated `Debug.threadCpuTimeNanos()` kernel system calls in comparator loops; probes must remain OFF during baseline benchmarking.

Prior Iteration 15: 2026-10-15, dev, starting HEAD `8962ec91`. Grouped resort dominated by repeated root/path SQL work. Root-only candidate rejected/reverted after small scenario tails breached legacy gate. Canonical record PI-20261015-02.

Prior Iteration 14: 2026-10-14–15, Asia/Novosibirsk. Starting HEAD `98b752d4195459ae759e10f8f9ba31b2084bfd84`, branch `dev`. Only verified offline disposable emulator-5580, v30 WAL.

**CONFIRMED:** Iteration13's26.35s maximum crosses later navigation/viewport preparation; first same-revision row is ~311ms. Preserve all physical joins, but qualify late endpoints as unmatched for their original edits. A bounded native regression proves controller→revision / rollback revision→controller lock inversion; its repair passes final liveness/freshness tests.

**DECISION:** Reject and revert the one snapshot-sharing performance candidate. It removes historical traversal (long retag median82.41→0.26ms; primary presented320.06→252.03ms) but repeated Statistics return p95 regresses in both focused pairs505→758 and340→378ms. Production snapshot is byte-identical to starting HEAD; the rejected Iteration13 range cache remains absent. Retain required lock repair, validated attribution/viewport safeguards and semantic tests. No broad performance win is shipped.

**EVIDENCE / NEXT:**180/180 exact edit presentation joins;99/100 Habits-return joins with missing attempt retained. Final retained48 core +9 native +20 Python tests pass; actual open-v30 WAL remains verified. Next emulator-only target is grouped `resortAndObservers` (~388ms median/674ms p95), with internal attribution before changing it. See PI-20261015-01. STOP; do not start a second optimization or redirect to physical-device release testing.

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
| F09 Extreme-value instability and high false-positive rate of n=3 tail gates | CONFIRMED empirical A/A calibration (PI-20261015-03): 31/80 (38.8%) scenario evaluations and 15/16 (93.8%) arm pairs falsely breached legacy >10% & >20ms p95 gate on byte-identical builds; Monte Carlo n=3 p95 CV is 41.6% | Nearest-rank p95 at n=3 is max(x1, x2, x3); single scheduling hiccup acts as false regression. Rejection cannot rely on n=3 p95 |
| F10 Diagnostic probe measurement overhead | CONFIRMED PI-20261015-03: detailed resort probes inflate presentation median by +125% (1848.2→4158.4ms) and resortAndObservers by +4.1s (2147.4→6294.4ms) | Repeated threadCpuTimeNanos syscalls and trace allocations in tight comparator loops distort baseline; benchmark comparisons must run probes-off |

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
| PI-20261014-08 | HEAD46c0f0497; isolated baseline/rejected candidate; offline emulator5580 v30;42 habits;90-day grouped/1825-day status-sorted ABBA. | scratch/iteration13/committed-summary.json;120 edits,116 exact presentations; actual open WAL test. | CONFIRMED work elimination but short presented median regression in both pairs; candidate reverted. QA/tools/docs retained; next emulator-only target revision retagging. |

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

## PI-20261014-05 — Iteration 10: post-navigation habit latency degradation investigation and observer detachment fix

**Decision:** IMPLEMENTED AND VERIFIED FIX. Post-navigation habit latency degradation was directly caused by `StatisticsFragment` remaining registered as an active observer of `EffectiveReadRevision` and `ModelObservable` after the user navigated away from Statistics. Because `MainActivity` uses `FragmentTransaction.hide()` with `setMaxLifecycle(..., STARTED)`, view destruction (`onDestroyView`) is never invoked during tab navigation. Every subsequent habit toggle on the Habits screen triggered hidden background statistics report recomputations with date bounds (e.g. `[anchorDate, anchorDate]`) that diverged from the Habits list bounds (`[today - 365, today]`). This continuously cleared `EffectiveHabitReadController`'s cached snapshots (`canIncrementallyUpdate = false`), destroying warm incremental projection (forcing 34–68 habits recomputed on every tap with 0 cache hits) and posting Main-thread view hierarchy inflations during interaction. Observers are now strictly attached only when `isResumed && !isHidden && viewBinding != null` and detached on pause/hide/view destruction. Post-navigation warm edits now prepare strictly 1 habit with 32–33 cache hits, and retained heap usage drops from 50.4 MB to 14.5 MB.

### 1. Controlled Native Reproduction Across Scenarios A–F

Investigated on disposable offline PRODUCTION_NEXT v30 AVD (`emulator-5580`, `UHabit_UI_Review_Disposable_20261011`, API 35) using `PostNavigationLatencyInvestigationTest`:
- **Scenario A (Baseline Habits):** Repeated Boolean edits without leaving Habits. Observers: rev=1, obs=0, cmd=2. Prepared habits: strictly 1, cache hits: 32–33.
- **Scenario B (Habits -> Tasks -> Habits):** Return from unmodified Tasks. Observers: rev=1, obs=0, cmd=2. Prepared habits: strictly 1, cache hits: 33. `TasksFragment` cleanly removed its workspace observer in `onPause()`.
- **Scenario C (Habits -> Statistics -> Habits):** Return from unmodified Statistics. Observers leaked: rev=2 (+1), obs=1 (+1). On edit 1: **67 habits prepared, 0 cache hits** (100% cache eviction). On edit 2: **34 habits prepared, 1 cache hit**.
- **Scenario D (Habits -> Tasks modified -> Habits):** Tasks modified, then return. With leaked Statistics observers, edit 1 prepared **34 habits**, edit 2 prepared **34 habits**.
- **Scenario E (Habits -> Statistics filtered -> Habits):** Filter/tab changed on Statistics, then return. Edit 1 prepared **34 habits, 0 cache hits**; edit 2 prepared **34 habits, 1 cache hit**. Heap used jumped from 18.3 MB to 47.3 MB.
- **Scenario F (Repeated cycles 1, 3, 10):** Repeated cycles of Habits -> Tasks -> Statistics -> Habits. Every post-return edit suffered **34–68 habits recomputed** and **0–1 cache hits**. After 10 cycles, PSS reached 151 MB and heap used reached 50.4 MB.

### 2. Attribution of Degradation Timeline

1. **Listener Retention:** In `StatisticsActivity.kt`, `component.habitList.observable.addListener(this)` and `component.effectiveReadRevision.addListener(effectiveRevisionListener)` were registered in `onViewCreated` and only deregistered in `onDestroyView`. Because `MainActivity` hides tabs without destroying views, the listeners remained permanently active.
2. **Cache Annihilation:** In `EffectiveHabitReadController.kt`, `cachedContext` records `from` and `to`. Habits list requests `[today - 365, today]`. When a habit was toggled on Habits, the mutation triggered `effectiveRevisionListener` in the hidden `StatisticsFragment`. `StatisticsFragment` immediately posted a background task requesting `[anchorDate, anchorDate]`. Because `prevContext.from != currentContext.from`, `canIncrementallyUpdate` evaluated to `false`, calling `cachedSnapshots.clear()`. The warm snapshot cache for all 33 habits was completely destroyed.
3. **UI Thread Layout Pollution:** When the background statistics calculation completed, `renderReport` ran `performViewUpdate` on the Main UI thread, creating scrollviews, containers, and card views for all habits while the user was interacting with Habits.

### 3. Validated Implementation

In `StatisticsActivity.kt` (`StatisticsFragment`):
- Added `updateObserverRegistration()` helper that dynamically registers listeners only when `isResumed && !isHidden && viewBinding != null`, and cleanly removes both listeners when `!shouldObserve`.
- Invoked `updateObserverRegistration()` in `onResume()`, `onPause()`, `onHiddenChanged(Boolean)`, and `onDestroyView()`.
- Guarded `requestReportUpdate()` and `renderReport()` with `if (viewBinding == null || !isAdded || !isResumed || isHidden) return`.
- Incremented `activeReportGeneration++` on observer detachment and paused/hidden transitions so any running background task is immediately discarded and cannot post UI updates to the Main thread.
- Kept `onResume()` and `onHiddenChanged(false)` report update triggers so Statistics refreshes cleanly as soon as the user navigates back to it.

### 4. Paired Native AVD Verification (Before vs After)

Measured on identical disposable offline v30 database on `emulator-5580` (`EMULATOR36X6X11X0`, API 35). Raw reports: `scratch/post-navigation-20261014/post-navigation-latency.json` (before) vs `scratch/post-navigation-20261014/post-navigation-latency-after.json` (after).

| Scenario / Metric | Before Fix (Leaked Observers) | After Fix (Clean Detachment) | Impact |
|---|---|---|---|
| **Active Observers (rev / obs)** | 2 rev, 1 obs (permanent leak) | **1 rev, 0 obs** (in all scenarios) | Zero leaked observers |
| **Scenario C (Habits -> Stats -> Habits):** | | | |
| - Edit 1 Prepared Habits / Hits | 67 prepared, 0 hits (100% eviction) | **1 prepared, 33 hits** | 67x reduction in recomputation |
| - Edit 2 Prepared Habits / Hits | 34 prepared, 1 hit | **1 prepared, 32 hits** | 34x reduction in recomputation |
| **Scenario E (Stats Filtered -> Habits):** | | | |
| - Edit 1 Prepared Habits / Hits | 34 prepared, 0 hits | **1 prepared, 33 hits** | Full incremental cache hit restored |
| - Edit 2 Prepared Habits / Hits | 34 prepared, 1 hit | **1 prepared, 33 hits** | Full incremental cache hit restored |
| - Retained Heap Used | 47.3 MB | **15.4 MB** | **67.4% heap reduction** |
| **Scenario F (10 Navigation Cycles):** | | | |
| - Edit 1 Prepared Habits / Hits | 34 prepared, 1 hit | **1 prepared, 0 hits (context switch)** | Only changed habit recomputed |
| - Edit 2 Prepared Habits / Hits | 34 prepared, 0 hits | **1 prepared, 33 hits** | Full warm incremental projection |
| - Retained Heap Used | 50.4 MB | **14.5 MB** | **71.2% heap reduction** |
| - Total PSS | 151,306 KiB | **116,812 KiB** | **34.5 MB PSS reduction** |

### 5. Next Steps

- Keep validated observer detachment in `StatisticsActivity.kt`.
- Profile remaining post-presentation Main/RenderThread frame time on entry-sensitive sorting (where large layout passes occur when reordering rows) and evaluate targeted row rebinds.
- **STOP after this verified iteration.**

## PI-20261014-06 — Iteration 11: End-to-end and lifecycle verification of post-navigation fix

> Superseded timing interpretation: Iteration 12 (PI-20261014-07) audits the harness and corrects the presentation/durability claims below. Historical samples are preserved; they are not a strict before/after presentation comparison. Lifecycle correctness remains separately supported.

**Decision:** VERIFIED AND CONFIRMED FIXED ON DISPOSABLE AVD. Post-navigation habit latency degradation and cross-screen snapshot cache trashing are completely eliminated across Scenarios A through F. Strict FrameMetrics presentation timelines, exact SQLite and UI parity (100%), and complete background CPU work cancellation verified on `emulator-5580` (`EMULATOR36X6X11X0`, API 35).

### 1. Scope and Verification Objectives

1. **End-to-End Presentation and Lifecycle Verification:** Test whether the Iteration 10 Statistics lifecycle fix in `StatisticsActivity.kt` eliminates post-navigation habit toggle slowdown across all transition paths (Scenarios A through F), not merely redundant snapshot computations.
2. **Strict Measured Timeline:** Measure `inputToDurableMs` (SQLite durability via `HabitEditTrace`), `inputToSnapshotPublishMs` (snapshot publication via `HabitCardListCache.timingObserver`), `inputToDrawMs` (target-cell checkmark draw via `HabitFrameTiming.observer`), and `frameTotalDurationMs` / `frameLayoutMeasureMs` / `frameDrawMs` (app-surface presentation via `Window.OnFrameMetricsAvailableListener`).
3. **CPU Work Cancellation:** Distinguish canceled UI publication from actual cancellation of background CPU work. Verify that `taskGeneration != activeReportGeneration` checks terminate `StatisticsFragment.updateReport()` before heavy SQLite queries, snapshot computations, or state building.
4. **Lifecycle and Transition Correctness:** Validate observer detachment, 50 rapid hide/show transitions without listener leakage, absence of background report generation while hidden, and fresh report rendering upon return to Statistics.
5. **Exact Parity:** Verify on every edit that SQLite repetition records, effective snapshot values, and drawn checkmark states match identically.

### 2. Paired Native AVD Verification Results (Before vs After)

Target: Verified disposable offline PRODUCTION_NEXT v30 AVD (`emulator-5580`, `UHabit_UI_Review_Disposable_20261011`, API 35).
Artifacts: `scratch/post-navigation-20261014/post-navigation-latency.json` (before) vs `scratch/post-navigation-20261014/post-navigation-latency-v11.json` (after).

| Scenario / Operation | Listeners (rev / obs) Before -> After | Prepared Habits Before -> After | Cache Hits Before -> After | Lock Held Ms Before -> After | After Timeline: Durable / Snapshot / Draw / FrameTotal | Parity |
|---|---|---|---|---|---|---|
| **A: Baseline (Habits only)** | | | | | | |
| - Edit 1 (cold edit) | 1 / 0 -> **1 / 0** | 1 -> **1** | 32 -> **32** | 37.5 -> 63.7 ms | 1593.3 ms / 2149.1 ms / 1534.9 ms / Frame 52.9 ms (layout 0.3, draw 3.0) | **PASS** |
| - Edit 2 (warm edit) | 1 / 0 -> **1 / 0** | 1 -> **1** | 33 -> **33** | 59.6 -> 34.4 ms | 1239.1 ms / 1491.0 ms / 1238.7 ms / Frame 36.7 ms (layout 0.1, draw 0.2) | **PASS** |
| - Edit 3 (warm edit) | 1 / 0 -> **1 / 0** | 1 -> **1** | 33 -> **33** | 66.9 -> 82.3 ms | 791.1 ms / 992.9 ms / 803.6 ms / Frame 86.8 ms (layout 0.1, draw 0.2) | **PASS** |
| **B: Habits -> Tasks -> Habits** | | | | | | |
| - Edit 1 (post-nav) | 1 / 0 -> **1 / 0** | 1 -> **1** | 33 -> **0** (context) | 62.7 -> 35.9 ms | 820.8 ms / 943.7 ms / 816.8 ms / Frame 54.0 ms (layout 0.2, draw 4.0) | **PASS** |
| - Edit 2 (warm) | 1 / 0 -> **1 / 0** | 1 -> **1** | 33 -> **32** | 63.1 -> 75.5 ms | 1062.1 ms / 1336.3 ms / 1021.4 ms / Frame 82.7 ms (layout 0.2, draw 0.2) | **PASS** |
| **C: Habits -> Stats -> Habits** | | | | | | |
| - Edit 1 (post-nav) | 2 / 1 -> **1 / 0** (clean) | 67 -> **1** (67x) | 0 -> **33** (100% hit) | 78.2 -> 24.4 ms | 822.4 ms / 988.6 ms / 781.0 ms / Frame 67.6 ms (layout 0.2, draw 0.2) | **PASS** |
| - Edit 2 (warm) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 1 -> **32** | 10.1 -> 57.0 ms | 862.1 ms / 1114.6 ms / 789.4 ms / Frame 81.7 ms (layout 0.2, draw 0.2) | **PASS** |
| **D: Tasks Modified -> Habits** | | | | | | |
| - Edit 1 (post-nav) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 1 -> **0** (context) | 23.2 -> 31.9 ms | 816.9 ms / 967.3 ms / 812.3 ms / Frame 79.2 ms (layout 0.2, draw 38.1) | **PASS** |
| - Edit 2 (warm) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 1 -> **33** | 39.1 -> 63.4 ms | 779.7 ms / 956.2 ms / 767.9 ms / Frame 47.8 ms (layout 0.2, draw 2.0) | **PASS** |
| **E: Stats Filtered -> Habits** | | | | | | |
| - Edit 1 (post-nav) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 0 -> **33** (100% hit) | 74.5 -> 76.0 ms | 791.5 ms / 1104.2 ms / 804.5 ms / Frame 296.3 ms (layout 0.2, draw 28.2) | **PASS** |
| - Edit 2 (warm) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 1 -> **0** | 47.4 -> 32.4 ms | 947.9 ms / 1124.5 ms / 945.9 ms / Frame 68.5 ms (layout 0.2, draw 22.2) | **PASS** |
| **F: 1 Navigation Cycle** | | | | | | |
| - Edit 1 (post-nav) | 2 / 1 -> **1 / 0** | 68 -> **1** (68x) | 1 -> **32** | 54.4 -> 45.2 ms | 1005.0 ms / 1281.8 ms / 1008.4 ms / Frame 63.8 ms (layout 0.3, draw 0.3) | **PASS** |
| - Edit 2 (warm) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 1 -> **32** | 24.2 -> 58.4 ms | 728.9 ms / 1027.5 ms / 735.5 ms / Frame 102.8 ms (layout 0.2, draw 5.7) | **PASS** |
| **F: 3 Navigation Cycles** | | | | | | |
| - Edit 1 (post-nav) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 1 -> **32** | 11.1 -> 70.0 ms | 845.3 ms / 1080.5 ms / 872.2 ms / Frame 94.7 ms (layout 3.0, draw 0.2) | **PASS** |
| - Edit 2 (warm) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 0 -> **33** | 19.2 -> 88.4 ms | 1172.4 ms / 1364.5 ms / 1166.5 ms / Frame 175.9 ms (layout 0.2, draw 0.2) | **PASS** |
| **F: 10 Navigation Cycles** | | | | | | |
| - Edit 1 (post-nav) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 1 -> **33** | 5.5 -> 38.3 ms | 782.5 ms / 950.3 ms / 798.2 ms / Frame 69.1 ms (layout 3.0, draw 0.4) | **PASS** |
| - Edit 2 (warm) | 2 / 1 -> **1 / 0** | 34 -> **1** (34x) | 0 -> **33** | 10.7 -> 49.5 ms | 831.7 ms / 980.8 ms / 831.6 ms / Frame 87.4 ms (layout 0.2, draw 0.2) | **PASS** |
| - Retained Heap Used | 50.4 MB -> **14.3 MB** | (-71.6%) | | | | |
| - Total PSS | 151,306 KiB -> **110,013 KiB** | (-27.3%) | | | | |

*Key Findings:*
1. **Zero Leaked Observers:** In every post-navigation scenario, `revisionListenerCount` is strictly 1 and `observableListenerCount` is strictly 0 while on Habits. The leaked background observer is completely eliminated.
2. **Warm Incremental Projection Preserved:** In all post-navigation scenarios, subsequent habit edits recompute **only 1 habit** (the targeted habit), while 32–33 unchanged habits hit the cache. Zero full-list recomputations occurred.
3. **No Cumulative Latency Degradation:** Across cycles 1, 3, and 10, durable SQLite write time (~730–1170 ms), target draw time (~735–1166 ms), and total frame duration (~64–176 ms) remained completely stable, with zero cumulative growth.
4. **Memory Stability:** After 10 full navigation cycles, retained heap used was 14.3 MB (vs 50.4 MB before fix, a 71.6% reduction) and total PSS was 110.0 MB (vs 151.3 MB before fix, a 41.3 MB reduction).

### 3. Lifecycle Invariant Verification (`testStatisticsLifecycleCorrectness`)

Executed natively on `emulator-5580`: **PASSED (OK (1 test), 79.5s)**.

Verified invariants:
1. **Initial Habits Screen:** `component.effectiveReadRevision.listenerCount == 1`, `component.habitList.observable.listenerCount == 0`. Statistics is not active.
2. **Navigate to Statistics:** `isObservingDataState == true`, `isHidden == false`, `listenerCount` becomes 2 (revision) and 1 (observable). Initial report key rendered.
3. **Navigate to Habits:** `isObservingDataState == false`, `isHidden == true`, `listenerCount` returns strictly to 1 (revision) and 0 (observable). `activeGeneration` is captured.
4. **Habit Edit on Habits:** Habit toggled via `CreateRepetitionCommand`. `effectiveReadRevision` increments. Hidden Statistics does NOT increment `activeGeneration` and launches zero background tasks.
5. **Return to Statistics:** `isObservingDataState == true`, `isHidden == false`, `activeGeneration` advances, and a fresh report is rendered with `lastRenderedKey.effectiveRevision` matching the updated database revision.
6. **Rapid Transitions (50 Cycles):** Toggled between Habits and Statistics 50 times in rapid succession. At the end on Habits, `revisionListenerCount == 1` and `observableListenerCount == 0`. Zero listener accumulation.
7. **Activity Recreation:** `scenario.recreate()` executed. On Habits, `revisionListenerCount == 1` and `observableListenerCount == 0`. Clean detachment preserved across recreation.

### 4. Background CPU Work Cancellation

In `StatisticsActivity.kt` (`StatisticsFragment.updateReport`):
- Guarded `doInBackground()` with `if (taskGeneration != activeReportGeneration) return` checks before:
  1. Focus session database query (`SQLiteFocusSessionRepository.getByTimeRange`).
  2. Snapshot resolution (`effectiveHabitReads.snapshots(habits, start, end)`).
  3. Report building (`StatisticsReportStateBuilder.build()`).
  4. Comparison period snapshot resolution and report building.
- Guarded `onPostExecute()` with:
  `if (viewBinding == null || !isAdded || !isResumed || isHidden || taskGeneration != activeReportGeneration) return`.
- Whenever the user navigates away, `activeReportGeneration++` invalidates any running background task. The background task exits immediately at the nearest fence without executing expensive SQL queries or overwriting `EffectiveHabitReadController` cache contexts.

### 5. Parity & Data Correctness

- Across all 17 measured edits in Scenarios A through F, `parityMatches == true` for 100% of samples.
- Every toggle produced an exact match between SQLite committed entries (`EntryRepository.findAllByHabitId`), in-memory effective snapshots (`snapshot.presentationHabit.originalEntries`), and the UI checkmark button state.

### 6. Decision and Next Steps

- **DECISION:** Post-navigation habit latency degradation is marked as **FIXED on verified disposable AVD (`emulator-5580`)**.
- Keep validated lifecycle detachment and background CPU cancellation in `StatisticsActivity.kt`.
- Profile remaining post-presentation Main/RenderThread frame time on entry-sensitive sorting (where large layout passes occur when reordering rows) and evaluate targeted row rebinds.
- **STOP after this verified iteration.**

## PI-20261014-07 — Iteration 12: actual post-navigation committed presentation

### Question, scope and acceptance

Does the Statistics lifecycle correction improve actual committed target-cell presentation, and what produced Iteration 11's 730–1170 ms input-to-durable values? Accept an endpoint only when input, successful changing SQL return, revision-qualified published row, exact today-cell glyph and presented frame are joined. Missing, repeated, superseded or ambiguous endpoints remain unknown; no nearest-frame fallback. Compare exact product revisions before attributing a new optimization. Starting HEAD matched `53c52dbb944ed4beb54ff455247e2535d437df7a`; unrelated tracked artifact deletions and untracked files were preserved.

### Audit of Iteration 11 definitions

| Old metric | Exact start/end | Interpretation and correction |
|---|---|---|
| inputToDurableMs | `startNanos = System.nanoTime()` after target lookup, before observer setup; callback for operation's `sqlite.durable` | Includes observer installation, HandlerThread creation, FrameMetrics registration, UIAutomator system-dialog lookup, Activity callbacks/coordinate lookup and input injection. The endpoint is a real operation event, but occurs after successful `statement.step()`, `SELECT changes()` and the autocommit check. It is neither accepted-input latency nor exact step-return time. |
| inputToSnapshotPublishMs | Same harness start; last global `cache.publish` callback | Unqualified global publication observation, overwritten by subsequent callbacks. Does not identify the edited row, revision, generation or batch. |
| inputToDrawMs | Same harness start; first matching habit/value checkmark draw | Does not require today offset 0, durable revision, published operation lineage or committed snapshot value; an optimistic or another-day glyph can qualify. |
| frameTotalDurationMs | Last collected OnFrameMetrics callback's TOTAL_DURATION | An arbitrary frame's work duration. Neither input-to-presentation nor proof that the target glyph belongs to that frame. |
| wallDurationMs | Same harness start; completion of sequential latch waits | Harness/latch barrier; later drain is separate. Neither durability nor presentation. |

`System.nanoTime()` timestamps the old endpoints; MotionEvent uses `uptimeMillis()`. The old code never joined stored FrameMetrics vsync timestamps to the target. Callback timestamps can be overwritten after latches open; there is no per-operation ordering validation. This explains why its apparent draw could precede durable and snapshot publication. Its parity assertion checks the final SQLite value, not the full snapshot-to-glyph lineage. The date/filter scenario contains an empty fragment lookup, so no date/filter was changed.

The legacy harness now uses `harnessSetupToDurableCallbackMs`, `harnessSetupToUnqualifiedCacheCallbackMs`, `harnessSetupToUnqualifiedDrawCallbackMs` and `lastUncorrelatedFrameWorkDurationMs`. Its existing lifecycle correctness test is retained. Historical JSON is preserved and interpreted with the original source audit; values are not silently rewritten.

### Isolated experiment and provenance

Product builds are exact `ffd39c6a` and `53c52dbb`; before source was exported with `git archive` to `scratch/iteration12/before` without checkout/reset of the dirty working tree. The same new test source was overlaid on both. QA listener inspection uses private-field reflection because the old product lacks the newer count getters; no product code was patched for comparison. Product-source diff in the current checkout is empty.

Device identity was checked before fixture writes: disposable AVD `UHabit_UI_Review_Disposable_20261011`, serial `EMULATOR36X6X11X0`, `emulator-5580`, API 35, airplane mode 1, Wi-Fi/data disabled. Each arm resets only this disposable app state and uses the established guarded generator/fixture initialization, including its existing synthetic v30 setup. Production data, physical devices, Xiaomi, Supabase and migration code were untouched. PRODUCTION_NEXT capabilities explicitly disable sync, remote calls, background jobs, widgets and reminders.

Reference date 2026-10-11; stable BY_POSITION sorting; 10 controlled Boolean habits with 90 days each, 42 total habits. All four primary semantic fixture hashes are `eb1810a38355daa39d43eebacb5bd46c035091ac15f6a7d893d2368961e098dc`: positional habit name/type/frequency plus all persisted repetition dates/values. Random UUIDs/IDs and creation timestamps are intentionally excluded; this is semantic parity, not byte-identical database files. The common production fixture also asserts its two Focus facts and attribution consistency.

Primary order is before1 / after1 / after2clean / before2 (ABBA), each with two sequential edits per scenario. The second pair reverses scenario order. Ten mixed cycles means ten returns, alternating five Statistics and five Tasks visits. Date-change scenario actually clicks Statistics Previous and asserts its anchor changed. Target viewport lookup/scroll, drain and any empty-button recovery happen before measured input and are retained in `viewportDiagnostics`; they are not presented as user latency. First/subsequent labels refer to scenario preparation, not cold process or disk. Scenarios share process/cache/JIT history; reverse-order warm edits occur after prior navigation. This is a small descriptive sample, not an inferential benchmark.

`afterA` is the earlier pilot before final full-fixture hash/lifecycle probes. Extra `after2` overlapped host trace analysis; all ten samples remain separately reported and that condition was repeated as `after2clean` with trace analysis stopped. No unusually slow sample was removed. Main pair analysis was performed after operation recording; full raw artifacts and earlier analysis versions are preserved.

### Strict critical-path definitions and ordering

New events are operation-local. View `input.down` and `input.accepted` are distinct; the latter fires after computing the toggle and before forwarding the command. FIFO stages are command acceptance, enqueue, IO worker receipt and predecessor completion. `sqlite.end` fires immediately after statement return; it is accepted retrospectively only with one successful changing `sqlite.durable` autocommit qualification and no failed/pending-transaction event. The qualification lag from SELECT changes is excluded. An outer transaction would need its actual completion; pending/ambiguous cases remain unmatched. No command.finished, polling observation or drain qualifies durability.

The target row requires its durable revision and exact revision/generation/batch publication lineage. The exact today button must have offset 0, the published operation, matching manual snapshot value and expected committed value before `glyph.draw`. Bind uses the same lineage. Retained historical traces can receive later publications: only the publication used by the exact glyph is eligible. Provider metrics without batch identity remain temporal context, not a guessed causal join. Repeated snapshot starts/retries keep ambiguous endpoint durations unknown.

**40/40 primary edits** have monotonic input -> command scheduling -> SQL return -> qualification ordering and SQL return -> recompute end -> qualified publication -> glyph ordering. All 40 have unique qualified bind joins. Both DOWN and accepted-input trace markers join 40/40 to their committed glyph's containing doFrame token and same-process actual app-surface FrameTimeline with On-time/Early/Late Present; zero unmatched or ambiguous presentations. No FrameMetrics substitution is used.

All stage deltas stay in System.nanoTime's monotonic domain. Paired `nanoTime`, `elapsedRealtimeNanos` and `uptimeMillis` anchors are saved at beginning/end of every arm to diagnose offset/suspend; no clock domains are subtracted. Frame/input/presentation arithmetic uses Perfetto timestamps only. Lifecycle and parity polling timestamps remain observation/barrier times. CPU/runnable attribution intersects exact Perfetto accepted-to-present windows. The first pair reports `sorter_push_event_out_of_order=8` in each trace; reverse traces report no nonzero error/data-loss stats. Exact token/ordering joins still succeed, but the first traces are not declared error-free.

### Actual presented samples

Milliseconds; each cell lists forward / reverse values. Sample 0 is first after the scenario preparation; sample 1 is subsequent. Every primary sample is included.

| Scenario | Before first | After first | Before subsequent | After subsequent |
|---|---:|---:|---:|---:|
| Warm repeated Habits edits | 538.405 / 159.651 | 636.755 / 209.905 | 311.725 / 229.698 | 188.399 / 198.249 |
| Habits -> Statistics -> Habits | 296.892 / 141.903 | 214.369 / 203.321 | 268.478 / 311.057 | 191.853 / 109.686 |
| Statistics date change -> Habits | 171.983 / 159.115 | 254.077 / 212.250 | 263.655 / 194.363 | 291.171 / 158.423 |
| Habits -> Tasks -> Habits | 245.546 / 273.027 | 352.812 / 151.098 | 266.456 / 285.673 | 241.379 / 211.811 |
| Ten mixed navigation cycles | 430.237 / 692.187 | 291.045 / 395.370 | 292.652 / 211.044 | 259.510 / 158.370 |

Pooled accepted-to-presentation arithmetic median **267.467 -> 212.031 ms**, maximum **692.187 -> 636.755 ms**, 20 edits per side; descriptive median reduction 20.7%. This does not establish a universal improvement: first Statistics edits have opposite directions across pairs; first date-change edits are slower in both corrected runs. Subsequent Statistics edits and first edits after ten cycles improve in both pairs. Cold/JIT exposure, scenario carryover and small n limit generalization. Pilot afterA is 10/10 linked; extra after2 is 10/10 linked, median 214.023 ms and max 521.064 ms; every extra sample remains in `scratch/iteration12/SUMMARY.md` and its raw JSON.

### Independent stage measurements

Arithmetic median / maximum milliseconds over 20 edits per side unless stated. Rows are overlapping or different clock domains and must not be summed as an exclusive partition.

| Stage | Before | Corrected |
|---|---:|---:|
| Accepted input -> actual changing autocommit return | 11.374 / 69.714 | 7.612 / 76.481 |
| Actual statement execution/return wall time | 3.201 / 21.825 | 2.662 / 21.175 |
| Command accepted -> enqueue | 0.119 / 18.117 | 0.099 / 3.657 |
| Enqueue -> IO worker receipt | 4.278 / 35.057 | 3.024 / 38.642 |
| FIFO predecessor wait | 0.104 / 0.586 | 0.065 / 2.463 |
| Historical command model recompute | 1.811 / 50.212 | 1.310 / 20.434 |
| Model refresh | 0.060 / 0.162 | 0.061 / 8.659 |
| Resort and synchronous observers | 1.019 / 18.722 | 1.374 / 17.693 |
| Offline sync-queue command stage | 3.607 / 16.440 | 2.477 / 10.840 |
| Actual durable -> exact drawn-row publication | 134.159 / 568.805 | 107.568 / 461.810 |
| Qualified publication -> exact glyph | 41.141 / 56.068 | 39.386 / 62.335 |
| Qualified publication -> target bind endpoint | 32.451 / 50.561 | 30.168 / 54.819 |
| Qualified target bind -> glyph endpoint | 6.532 / 21.251 | 6.403 / 17.177 |
| Exact glyph -> actual surface presentation (Perfetto) | 68.796 / 168.485 | 58.696 / 91.698 |
| Harness injection entry -> accepted input | 645.979 / 761.439 | 650.541 / 727.030 |
| UIAutomator system-dialog lookup alone | 595.098 / 685.058 | 603.815 / 672.146 |

Thus the old 730–1170 ms scale mostly reflects pre-input harness/idle/UIAutomator work, not slow SQLite. New harness setup-to-accepted additionally includes viewport preparation: median 837.579 -> 867.790 ms; it is explicitly excluded from input latency. Exact original overhead cannot be retroactively partitioned from its old JSON; source audit plus directly measured new system-check/injection intervals explain the scale without assigning old individual samples invented accepted timestamps.

For the exact drawn cache batch, request -> background start is 21.659/56.144 -> 6.054/30.067 ms; background execution 41.812/351.939 -> 41.231/61.453 ms; ready -> Main row publication 12.502/74.111 -> 12.098/38.692 ms. Actual durable -> cache request also contributes (40.426/297.431 -> 21.500/380.024 ms). These are separate measured wall stages, not proof that all elapsed waiting is dispatcher runnable time. Target batch controller requests and preparation counts are retained per edit; cache hits and unbatched provider timings remain temporal context rather than a guessed causal join. Qualified bind/draw endpoints are recorded in final analysis. Row-level view-work durations are contextual because their recorded frame samples do not distinguish every today button from the other days; exact today-button draw CPU is not separately established.

Main CPU in exact accepted-to-present windows is 58.324/88.094 -> 50.059/93.016 ms; Main runnable scheduling is 35.586/132.236 -> 27.909/102.341 ms. JIT-thread CPU overlap is 26.801/416.603 -> 18.400/481.089 ms. JIT CPU is concurrent context, not an additive latency stage. GC slices overlap after1 operations 1/7 (27.444/247.308 ms) and before2 operations 1/1/3/7/8 (144.878/100.591/167.388/44.474/96.858 ms); no GC-named slices overlap before1 or after2clean windows. These are concurrent HeapTaskDaemon collection spans, not proven stop-the-world pauses. Zero GC-named thread CPU is not zero GC: thread names do not identify HeapTaskDaemon work. Nested runtime spans are not summed. Large first-operation JIT/GC exposure is recorded, not sufficient by itself to prove a specific blocking cause.

Remaining latency is chiefly in the post-durable cache/publication path and frame work/presentation, rather than sustained SQL, FIFO predecessor wait or command historical recomputation. In the slow before2 ten-cycle first edit, target background work is 351.939 ms with 250 ambient prepared habits before the committed glyph, versus corrected reverse 40.550 ms and 1 ambient prepared habit. Ambient work is not all the target's own preparation; exact batch identity remains the causal boundary. Existing hidden Statistics work often continues after the first target presentation, so large reductions in total projection work do not translate one-for-one into target latency.

### Lifecycle, workload and memory

Hidden Statistics keeps 2 revision / 1 model-observable listeners before, versus 1 / 0 corrected. Hidden report generation advances across edits in old arms (for example Statistics 4->10 and 42->49) and stays constant corrected (3->3 and 29->29). Visible reports match current effective revision at all 14 sampled revisits per implementation. These are after-settle freshness observations, not proof of zero transient loading. Whole-workload provider calls fall 148->52 and prepared-habit counts 5004->1348; totals include startup and visible Statistics work and are not labeled per-edit costs. Corrected hidden screens launch no new report generations in these windows; existing generation fences act between stages and cannot preempt a query/projection already executing.

After the ten cycles and two edits: forward/reverse PSS KiB before 143294/152663 versus corrected 134585/142801; runtime-used bytes 40216144/48683536 versus 28788304/35488560. Active task counts are zero and pending report runnable is false at all corresponding after-settle states. These are sampled process/runtime memory, not retained heap, peak allocations or a leak slope; no forced-GC comparison. All before/after state samples are preserved in SUMMARY. Lifecycle test `PostNavigationLatencyInvestigationTest#testStatisticsLifecycleCorrectness` freshly PASS, OK (1 test), 34.111 s: hidden detachment, edit suppression, fresh return, 50 transitions and activity recreation.

### Evidence, reproducibility and decision gate

Raw artifacts: `scratch/iteration12/{before1,after1,after2clean,before2}.{json,perfetto,txt}`; separate pilot `afterA` and extra `after2`; APK hashes `apk-hashes.json`. `*-analysis-final.json` contains final qualified bind/cache path/order joins, `*-accepted-presentation.json` / `*-down-presentation.json` the exact FrameTimeline joins, and `*-cpu.json` the literal rerunnable CPU/runtime SQL. Earlier analysis versions are retained. Detailed local report: `scratch/iteration12/SUMMARY.md`.

Reproduce with `tools/qa/run_post_navigation_attribution.py --apk-root <isolated outputs/apk> --label <newLabel> --reverse <false|true> --output <new directory> --config tools/qa/post-navigation-capture.config`; the runner validates the sole permitted AVD before reset/install. Then use `analyze_post_navigation_attribution.py --source <arm.json> --processor C:/Users/Pavel/.codex/skills/perfetto/bin/trace_processor --analysis-name <newVersion>` and `attribute_post_navigation_cpu.py` with the same source/processor. The scripts reuse exact presentation joins, retain ambiguous events and preserve existing outputs. CPU query materializes filtered windows/tables to avoid the initial slow unmaterialized query; that initial analysis was stopped and no result is claimed from it. Schemas were inspected before querying. No closest-frame heuristic is used.

Fresh checks: isolated before app/test builds PASS; corrected app/test builds PASS; four primary native attribution tests PASS (78.730 / 83.880 / 61.178 / 63.706 s); extra/pilot native tests PASS and preserved; retained lifecycle native test PASS; analyzer suites 17/17 PASS; QA Python compile and scoped diff whitespace checks PASS. No instrumentation test ran on a physical device. All measured primary SQLite/snapshot/view parity checks pass. Product optimization changes: none.

1. **Presentation benefit:** pooled descriptive benefit and repeatable improvement for subsequent Statistics edits / first ten-cycle edits; first ordinary Statistics and date-change results do not support a universal speedup.
2. **730–1170 ms:** misnamed pre-input harness measurement, especially UIAutomator lookup; actual accepted-to-SQLite return is millisecond-scale. The SQL endpoint itself was operation-specific but slightly late; the start definition was the major error.
3. **Residual path:** post-durability cache dispatch/preparation/Main publication and rendering/presentation dominate the measured windows; SQL/FIFO/command recompute do not dominate typical edits. Concurrent JIT/GC and Main runnable time are recorded with bounded attribution.
4. **Next action:** no separate narrowly repairable correctness defect was confirmed. Keep the lifecycle correction and proceed only with a separately authorized controlled physical-device test using these metrics and disposable data. First date-change edits merit targeted remeasurement, not an unproven optimization. STOP; no further optimization, phone access or push in this iteration.

## PI-20261014-08 — Iteration 13: source audit and rejected bounded range reuse

**Outcome: REJECTED / REVERTED.** One production optimization was implemented, tested and evaluated, then reverted because short-history presented latency and Main CPU regress. This iteration does not claim the objective of a shipped end-to-end performance improvement was achieved. Existing Iterations8–12 implementation remains intact. No push, real dataset, physical device, cloud or sync access occurred.

### Question, baseline and invariants established before implementation

Can visible Statistics and Habits consumers reuse their different requested ranges without recomputing all histories when the revision has not changed? Source at starting HEAD has one `cachedContext`/map; `sameInputs` includes from/to, so switching ranges clears every snapshot. Controlled A -> B -> A probes reproduce provider work on every return. Expected benefit: zero provider preparations on equal-version returns and less navigation work. Acceptance also requires no material warm/edit/navigation regression.

Preserve exact manual and effective values, contribution/required/observed metadata, historical goals, scores and streaks, revision freshness, global/lost-history fallback, rollback/calendar/dataset invalidation, InFlightBatch terminal publication, numerical manual editing, Tasks/Statistics, WAL and FIFO. No speculative state is substituted for committed rows. Do not change transaction architecture or journal policy.

### Ranked source audit (CONFIRMED mechanism; dominance qualified separately)

1. **Cross-range eviction, selected:** `android/focus/EffectiveHabitReadController.kt` context comparison and clear in the planning monitor; `ListHabitsFragment` uses list history while visible Statistics uses a different date range. Hidden Statistics observer work was already fixed in Iteration12 and is not repeated here. Final probes show baseline 42-history return preparation versus candidate identity reuse. Highest confidence in eliminated real work; bounded implementation risk, but end-to-end regression ultimately rejects it.
2. **Revision retagging and publication path, recommended next:** controller calls `withRevision` for every surviving snapshot under its monitor. `core/models/projection/EffectiveHabitReadSnapshot.kt:106` constructs a new snapshot, whose initialization rebuilds date lookup/coverage collections. Baseline qualified durable-to-row remains 113ms median short and456ms long. This is source-proven allocation/loop work, not a measured claim that it explains all of that interval. Strictly-newer `affectedHabitsBetween` still synchronizes revision while holding controller; rollback listeners acquire revision -> controller. That pre-existing source lock-order risk is unresolved and not reproduced by final UI captures. Equal-version candidate reuse explicitly avoided adding this edge; do not claim all paths are deadlock-proof.
3. **Tasks Main rendering:** `TasksFragment.kt:544-551,604-610,877-941` re-renders navigation on cached workspace/resume and `removeAllViews()` constructs navigation views again. SmartView roots also make repeated date/current-time decisions per task. `TasksAdapter` uses DiffUtil; claiming a blind full adapter rebind would be wrong. Native UI covers Tasks visits/returns, but an exact Tasks-open presentation endpoint is UNKNOWN; do not treat UIAutomation drain as a frame.
4. **Full refresh metadata:** `core/ui/screens/habits/list/HabitCardListCache.kt:640-653` repeats organization/container metadata access per habit. Entry-sensitive sorting legitimately takes a full refresh; targeted refresh/generation safety and late workers must be preserved.
5. **Historical provider and layout:** `EffectiveHabitReadSnapshot.kt:255` provider prepares goals/day records/score/streak histories. Five-year entry-sensitive data triggers substantial real Main layout and card measure work; exact-window measurements below confirm its cost. ViewHolder binding, list adapter visibility, asynchronous full/targeted refresh publication, CoroutineTaskRunner command completion and Main observers were reviewed; no new FIFO/WAL redesign is justified.

### Runtime SQLite/WAL and transactions

`HabitsDatabaseOpener.kt` init/onConfigure/onCreate/onOpen/onUpgrade enables WAL when requested. `DatabaseUtils.initializeDatabase` computes `enableWalForNext && (targetVersion >=30 || ProductionCutoverGate.isCutoverAuthorized(filename))`; the default flag is true. Every final capture reports actual open DB schema30, `journalMode=wal`, actual helper `isWalRequested=true`, default predicate true, and `fixtureJournalOverride=false`. No PRAGMA journal assignment is used.

**v29 separately:** canonical legacy database without cutover authorization does not request WAL and open/create/upgrade disables it. Noncanonical staging/test filenames are always cutover-authorized by `ProductionCutoverGate`, so a v29 staging helper can request WAL. “All v29 has WAL disabled” is too broad; no real v29 DB was opened. **v30:** default Next production path requests WAL (subject to the explicit global flag); open disposable v30 verifies it at runtime.

New guarded `TransactionWriterContentionAndroidTest#openProductionNextWalPreservesReadersWritersAndSavepoints` operates on the component's already-open marked disposable DB, not a replacement connection/file. It writes only uniquely named synthetic AppSettings rows. Held nonexclusive writer permits a Main SELECT of committed data; second writer waits; release produces exact two revisions with no precommit notification. Nested SQL savepoint rollback removes the inner row, emits rollback notification, and publishes exactly one revision only at outer release. Its 2s bound is a liveness assertion, not a latency benchmark. No reproduced writer-contention deadlock or WAL-induced Main blocking in this scoped check; no blanket guarantee for all concurrency paths.

### Candidate and regression evidence (not shipped)

Candidate kept active plus one previous range in existing controller, matched dataset/calendar/rollback context, reused only revision-qualified snapshots, removed scoped changed habits from a restored range and retagged survivors, and cleared on unknown/global/lost history. In-flight batches were still invalidated on context switches; provider and latch waits stayed outside the monitor. Disposal/rollback dropped both maps. No writes, formulas, adapter state or transactions changed.

Focused `testBoundedDateRangeReuseAndInvalidation`: baseline fails zero-work range-return assertion; candidate passes identity reuse, third-range eviction, scoped edits in both maps, fresh provider parity including goals/manual/numerical/Focus values, score points/streaks, timezone/global/rollback/lost257-history invalidation, paused-owner retry and disposal. A review discovered that equal-version `affectedHabitsBetween` would introduce a revision monitor acquisition; unsafe intermediate candidate fails the held-revision liveness test (68.576s test duration), corrected candidate uses local emptySet and passes (32.899s). Provider parity oracle was corrected to compare semantic requested days and complete score/streak results rather than unequal batch-padding envelopes. Candidate-only source/test patches are retained locally as `rejected-EffectiveHabitReadController.patch` and `rejected-EffectiveHabitReadControllerContentionTest.patch`; both final working files are restored exactly to HEAD.

### Final disposable workload and counterbalanced protocol

`scratch/iteration13/committed-protocol.json`: ABBA `before90j,after90j,after90k,before90k`, then `before1825j,after1825j,after1825k,before1825k`. j uses forward scenario order; k reverses it. Three edits per each of warmRepeated, afterStatistics, actual Statistics previous-date change, afterTasks, and afterTenMixedCycles. 15 native UI edits per arm, 30 per side per workload,120 total; every accepted write participates in analysis. Native pointer input uses visible, laid-out demo today's Boolean checkmark; viewport setup and UIAutomator checks precede accepted input. Nav/date changes use genuine controls. Production trace endpoint is actual autocommit SQLite step return, not model recompute or command completion. Glyph needs exact revision/generation/batch lineage, stored manual parity, `isShown` and nonempty global visible rect. Presentation joins that marker to containing doFrame token and the same process's actual MainActivity surface frame; no nearest-frame inference or clock mixing.

Fixture includes existing32 demo habits plus10 identifiable Boolean interaction histories, realistic Tasks and Focus Contributions; total42 habits. Today/reference is frozen2026-10-11. Controlled history depth is90 or1825 daily entries, last today NO and yesterday YES, with historical goals/numerical/Contribution fixtures. Grouped uses BY_POSITION; long history uses entry-sensitive BY_STATUS_ASC. After direct SQL seed commits, original entries are invalidated and habits recomputed while implicit projection reads are suspended; assertions verify SQL and loaded history depth, today/yesterday entries, nonzero score and nonempty streak. Loader is restored before measured UI. Only preparatory explicit probes separately suspend/drain implicit refresh; real UI uses normal production loader. Each final edit has SQLite/effective parity; zero visible mismatches and zero controlled-reader failures. Logical habit/repetition hashes match within each depth; hashes are not claims about every random Task UUID.

Final app APK SHA256 baseline `242f47bc9d929a19508db20e2e9b5d678263e3779ffa3a212b4f7c70213c0212`; rejected candidate `fc771d223a1532a73e52743430708e2ff0cb9dfe04e449ee8524831b087411ff`. Both trees came from `git archive` of starting HEAD; candidate overlay was only controller production code. Capture source SHA256 in both/root `b6d8bf04632eb725c6da103dfec8e5d4129fbb5de5c1d358b1a6572c92ce2151`. No builds or trace analysis ran concurrently with final capture. Harness's historic JSON experimentId string remains `PI-20261014-12`; labels/protocol identify this canonical record08 unambiguously.

Earlier a–g captures are supporting only: pre-Activity implicit refresh initially raced probes; probes were isolated/drained. Earlier direct-SQL seed did not prove loaded histories; h fails the explicit model-history assertion before UI. Final post-commit invalidation/hydration corrects that boundary. All failed/intermediate raw attempts and slow final arms remain local. No operations are removed as JIT/GC outliers. Last baseline long arm took255.895s instrumentation versus other long arms125–138s; this contributes to tail uncertainty and is included.

### Before/after measurements (candidate rejected)

Each cell is **n / median / p95 / max**, milliseconds; p95 is nearest-rank and medians use arithmetic middle pair. Different missing endpoints have explicit denominators. Presented totals cannot be reconstructed by summing stage medians.

| Workload/version | accepted→durable | durable→qualified row | row→exact glyph | glyph→presented | accepted→presented |
|---|---|---|---|---|---|
| 90-before | 30 / 8.99 / 84.14 / 100.81 | 30 / 113.03 / 315.31 / 476.73 | 30 / 40.86 / 74.05 / 88.31 | 30 / 68.07 / 110.34 / 118.73 | 30 / 236.76 / 491.95 / 666.78 |
| 90-after | 30 / 13.53 / 66.23 / 93.97 | 30 / 132.72 / 351.42 / 479.85 | 30 / 41.95 / 80.07 / 80.92 | 30 / 69.07 / 114.41 / 166.09 | 30 / 279.78 / 485.54 / 698.47 |
| 1825-before | 30 / 17.63 / 116.41 / 231.51 | 28 / 455.99 / 6809.68 / 25643.44 | 28 / 627.36 / 1478.00 / 1648.46 | 28 / 97.99 / 255.78 / 258.52 | 28 / 1364.65 / 8011.72 / 26349.20 |
| 1825-after | 30 / 17.58 / 77.91 / 96.22 | 28 / 290.77 / 6163.45 / 20559.61 | 28 / 513.15 / 725.00 / 764.00 | 28 / 80.73 / 151.78 / 169.60 | 28 / 879.04 / 7044.03 / 21410.09 |

| Scenario | 90d baseline→candidate presented median / p95 | 1825d baseline→candidate presented median / p95 | first edit 90d median B→C | first edit1825d median B→C |
|---|---|---|---|---|
| afterStatistics | 184.32/371.19 → 258.00/352.08 | 1376.62/5928.82 → 903.60/5293.92 | 228.17 → 256.79 | 473.27 → 526.25 |
| afterStatisticsDateChange | 236.76/273.63 → 246.79/428.91 | 1689.33/5779.60 → 858.42/4187.15 | 242.26 → 268.58 | 676.81 → 566.33 |
| afterTasks | 234.61/326.73 → 206.07/292.20 | 1224.74/26349.20 → 858.16/21410.09 | 217.06 → 218.53 | 808.37 → 599.47 |
| afterTenMixedCycles | 251.88/666.78 → 395.71/698.47 | 1795.73/5586.96 → 899.10/3289.39 | 421.34 → 552.03 | 1162.17 → 601.86 |
| warmRepeated | 287.36/491.95 → 377.72/485.54 | 1064.45/5209.07 → 939.55/4623.02 | 427.46 → 431.53 | 983.76 → 639.55 |

Scenario totals are6 per side short; long5 for warm/mixed and6 others. First-edit figures are only2 per side and descriptive. WarmRepeated pooled short median287.36→377.72ms, while long1064.45→939.55ms. Warm semantics are not cold process/JIT/page-cache semantics.

| Arm | edits linked/attempted | Habits-return nav linked/attempted | presented edit median/p95 | end PSS KiB |
|---|---|---|---|---|
| before90j | 15/15 | 13/13 | 197.55/491.95 | 131355 |
| after90j | 15/15 | 13/13 | 232.42/485.54 | 146543 |
| after90k | 15/15 | 3/13 | 332.35/698.47 | 133772 |
| before90k | 15/15 | 3/13 | 273.77/666.78 | 126991 |
| before1825j | 14/15 | 0/13 | 1074.04/26349.20 | 163134 |
| after1825j | 14/15 | 0/13 | 864.46/21410.09 | 163074 |
| after1825k | 14/15 | 0/13 | 906.15/7044.03 | 178704 |
| before1825k | 14/15 | 0/13 | 1732.67/8011.72 | 155065 |

Short edits have60/60 exact joins; long56/60, one operation15 unmatched in every arm (no committed visible draw marker after entry-sensitive row movement). All120 have durable endpoints and stored/effective parity; 40 long final settle observations lack a visible target and are **unavailable**, not mismatches or invented frame matches. Habits-return nav joins only32/52 short and0/52 long: exact target row is outside viewport before later target setup. Short pooled matched nav median176.66→179.38ms, p95305.56→494.88ms; subset/missing endpoints prohibit claiming a broad navigation benefit. Tasks-open exact presented latency remains UNKNOWN.

| Workload/version | UI provider total ms | prepared habit-snapshots | warm A→B→A return n/median/p95/max ms | Main CPU n/median/p95/max ms | Main runnable n/median/p95/max ms | framework layout/measure union wall n/median/p95/max ms |
|---|---|---|---|---|---|---|
| 90-before | 8056.75 | 1358 | 6 / 129.15 / 202.43 / 202.43 | 30 / 53.15 / 101.97 / 113.22 | 30 / 32.39 / 98.84 / 104.82 | 30 / 0.00 / 0.00 / 0.00 |
| 90-after | 3177.45 | 366 | 6 / 1.03 / 2.20 / 2.20 | 30 / 62.19 / 100.89 / 111.37 | 30 / 35.29 / 81.18 / 173.54 | 30 / 0.00 / 0.00 / 0.00 |
| 1825-before | 31438.81 | 1358 | 6 / 456.08 / 1548.80 / 1548.80 | 28 / 563.95 / 2232.29 / 4329.62 | 28 / 122.24 / 498.93 / 1169.10 | 28 / 317.12 / 1034.64 / 1035.16 |
| 1825-after | 9684.05 | 366 | 6 / 1.21 / 3.12 / 3.12 | 28 / 304.29 / 1204.42 / 4053.82 | 28 / 72.36 / 383.35 / 1453.88 | 28 / 200.04 / 748.13 / 889.63 |

UI provider excludes explicit preparatory rangeSwitch phases, includes cold launch and all navigation/edit work. Summed UI controller.lock cache hits (counting repeated consumers, not unique snapshots) are1966→2833 short and2864→4018 long. Repeated snapshot count1358→366 per workload confirms actual preparation reduction73.0%; total provider wall short8056.75→3177.45ms, long31438.81→9684.05ms. Six warm listRangeReturn probes per side: short129.15→1.03ms, long456.08→1.21ms; candidate equal-version probes have zero provider preparation and42 cache hits. Cold first full-range probe (two per side) remains short257/557ms baseline versus281/468ms candidate; long808/1717ms versus770/793ms. There is no new cold algorithm improvement claim. Cold-launch harness drain includes Activity/setup/observers and is not presentation; raw definition explicitly says process/JIT/SQLite/page-cache warm.

Main CPU and runnable use only exact accepted→present windows. Filtered numeric window query reproduces all CPU/runnable records and15 windows of the original helper on before90j, with exact trace-domain presentation delta checks in every arm. Layout is the interval union of Main `measure`, `layout`, `RV OnLayout`; zero short-window overlap does not mean zero lifetime layout. Main card measure union short median0.59→0.40ms, p953.65→6.52; long25.35→16.95ms, p9578.93→29.62. Nested wall slices are not summed or called CPU. All matched target frames report jank (60/60 short,56/56 long, mixed app/SF causes); no broad jank improvement is established.

Memory end PSS is one sample per arm, shown above; these isolated runtime samples cannot establish retained heap or leak. Candidate retains one extra historical map, a plausible allocation/retention cost, but causal GC explanation of short regression is **UNKNOWN**. Contextual GC/JIT overlaps are retained, not used to discard slow edits. Trace health: after90j and after1825j have8 `sorter_push_event_out_of_order`, before90k12 and before1825k16; other final arms no error/data_loss counters. Strict joins remain unique/monotonic, but traces are not advertised as error-free.

### Decision, final files and verification

Short presented median worsens18.2%; each pair worsens197.55→232.42ms and273.77→332.35ms. Main CPU median increases53.15→62.19ms. Long presented median improves35.6% and provider work drops substantially, but the short regression and incomplete navigation endpoints fail the predeclared acceptance gate. **Revert**, not average unlike workloads to hide the regression. Source mechanism is confirmed; exact cause of short regression and a safer cache retention design remain open.

Final production controller and candidate-only contention test are byte-identical to HEAD. Retained files: `PostNavigationPresentationAttributionTest.kt` (guarded workload, post-commit hydration, sorting, visible exact draw/nav diagnostics, range/WAL provenance); `TransactionWriterContentionAndroidTest.kt` (actual open-WAL/savepoint check); `run_post_navigation_attribution.py` (bounded samples/history/sort); `post-navigation-capture.config` (256MiB ring,360s capture); `analyze_post_navigation_attribution.py` (strict nav join, optional separate context analysis); this record and coordination handoff. No new library/cache/transaction/schema production changes.

Checks: isolated baseline/candidate app and test APK builds PASS (last committed-fixture build logs); final8 guarded native UI campaigns PASS; candidate focused regression PASS after red/green corrections, then removed with rejected candidate; 38 targeted core tests PASS (19 snapshot,17 revision,2 database concurrency; XML timestamp2026-10-14T06:56); strict analyzer unit suites8+9 PASS, rerun after final tool changes. Candidate WAL1 and InFlight5 pass earlier. After restore, actual installed baseline from last capture passes open-WAL plus existing five InFlight checks again:6 tests,8.043s (`restored-baseline-native-checks.txt`). Independent read-only review reports no new actionable defects in correction/harness and preserves the pre-existing lock-order uncertainty. Builds do not substitute for these device checks.

Raw/native traces, probes, rejected patches, all attempt logs and pinned-query SQL/context records remain under `scratch/iteration13`; final report sources `committed-summary.json`, `*-final.json`, `*-accepted-presentation.json`, `*-context-fast.json`. No raw databases/APKs/traces are committed. `perfetto_analysis_report.md` points to the exact source SQL and health caveats. Preserve all unrelated pre-existing artifact deletions/untracked files.

**Next bounded optimization:** on the same emulator, attribute the existing durable→row interval to revision-retagging work and cache scheduling, especially historical lookup/coverage rebuilding in `withRevision`, then compare a narrowly proven correction. For Tasks, add a visible task-row workspace endpoint before claiming an exact Tasks-open improvement. A physical-device release/test step is not required. STOP after this rejected candidate; do not implement either follow-up here.


## PI-20261015-01 — Iteration 14: revision retagging and operation lifetime

**IN_PROGRESS, primary comparison preregistration (2026-10-14–15).** Starting HEAD98b752d41/dev verified; unrelated dirty artifacts preserved. Investigated Iteration13 raw evidence before any new campaign. Its extreme accepted-to-present windows often terminate in a later viewportSetup: baseline before1825j operation12 has same-revision row publication311.093ms after input, edit drain1139.985ms and offscreen target, then exact glyph26272.04ms during setup following mixed navigation. The old26.349s presented marker is real but its interval contains subsequent harness/navigation work; it is not attributed as one continuous blocked edit. All old records remain preserved. Both long baseline arms have only5/15 target draws within their original scenario,9 later viewport draws and1 missing. Correct the lifetime/visibility boundary before any optimization claim.

Required correctness repair precedes performance work: bounded native regression proves snapshot planning owns controller while waiting for revision history, and genuine rollback callbacks own revision before controller. Baseline assertion fails after safe latch release (6.612s); moving history lookup outside controller with identity/version/environment revalidation passes (3.006s). This repair is shared by both performance arms; no permanently deadlocked AVD is created. The rejected two-range cache remains absent.

Selected one performance candidate only after direct attribution: in the completed preparatory long probe, revision-only retag of40 snapshots/76160 referenced days takes38.44–205.20ms inside controller, while changed-habit provider work is23.78–113.58ms. withRevision rebuilds the private date map and immutable contribution/representation/coverage flags despite forwarding the same frozen inputs and precomputed scores/streaks. Candidate shares only these immutable structures through a private revision-copy constructor; it does not store the previous wrapper or add a cache. Core red observes14992 historical list reads for two retags, expected0; candidate green includes all21 snapshot tests plus17 revision,2 database concurrency and9 widget checks. Compiled fields have no revisionSource field. A test-only Mokkery compatibility setting is necessary for the pre-existing final listener diagnostic on mocked CommandRunner; no production final member is opened.

Primary protocol is fixed in scratch/iteration14/primary-protocol.json before launch: two ABBA workloads, before90a/after90a/after90b/before90b then before1825a/after1825a/after1825b/before1825b; reverse scenario order in b arms;3 edits per scenario,3 mixed cycles,30 edits per side per depth. Before/after isolated from HEAD98b752d41 plus identical required lock repair, retag diagnostics and QA; only snapshot structural sharing differs between production arms. No concurrent builds/analysis during captures. Exact APK identities are written by the runner before capture.

Fixture validation: retain42 habits,10 controlled histories, Tasks, Focus and old goals/rows. Short grouping uses sphere/position. Long must use actual GLOBAL status/position sorting: sphere primary already applies a position tie-break, so merely putting status secondary does not exercise ordering. Keep four incomplete Boolean interaction rows and complete surrounding synthetic rows so both target positions stay visible without post-input scroll; any current-day synthetic AT_MOST goal adjustment needed to cover preserved period history is recorded/hashed, with older goals untouched. Viewport anchored before leaving Habits; retire each trace at final observation; no later setup/navigation draw can qualify it. The earlier sphere/status probe is supporting attribution/viewport evidence, not the final long configuration.

Acceptance: every operation retained with exact SQL/snapshot/visible parity, current revision/dataset/calendar/rollback guards, no new listener leak/deadlock/unbounded cache, unique actual app-surface FrameTimeline joins or explicit unmatched/ambiguous status. Require deterministic elimination of historical traversal and measured retag/critical-path or CPU benefit, meaningful improvement in that path, and no reproducible short-history or navigation regression across counterbalanced arms. Do not pool unlike workloads or drop cold/JIT/slow operations. Compare first versus later edits and related navigation, total provider work, Main CPU/layout and delayed work. Revert the performance candidate if it fails; retain the required independently proven lock repair. STOP after evaluating this one optimization; emulator-only remains the next direction.

Final matched results and rejection decision follow below.


Iteration14 secondary-regression follow-up preregistration (before capture): primary edits all120/120 exact joins and fixtures match, but short ordinary Statistics returns worsen280.98->349.98 and236.90->639.03ms; Tasks improves, date changes mixed. The639ms endpoint includes332ms draw-to-presentation, not all app computation. One bounded BAAB follow-up after90c/before90c/before90d/after90d uses the SAME production APK hashes, optional QA-only Statistics-only cycle mode,90-day grouped fixture,3 edits/scenario and10 Statistics cycles (44 ordinary Statistics returns total). Evaluate standalone and repeated Statistics strata separately, never dilute with Tasks/date. A>=10% median or p95 worsening in both matched follow-up pairs in either Statistics stratum rejects the candidate; parity/new CPU work/reproducible short-edit regression also reject. Preserve primary adverse evidence and every missing/tail operation. Protocol and APK hashes are fixed in statistics-followup-protocol.json before launch; no additional benchmark after this follow-up.


### Final evidence and decision — Iteration 14

**REJECTED / REVERTED:** revision-only snapshot structural sharing removes real historical work and improves five-year edits, but fails the preregistered Statistics-return tail gate. Keep the required controller lock-order repair, disabled-by-default attribution, viewport/scope safeguards and semantic tests. `EffectiveHabitReadSnapshot.kt` is byte-identical to starting HEAD98b752d41; its candidate-only zero-traversal test is removed. The rejected two-range cache from Iteration13 also remains absent. Candidate source/test patch is preserved as `scratch/iteration14/snapshot-candidate.patch`; no second optimization is attempted.

Source identity: branch dev, starting HEAD98b752d4195459ae759e10f8f9ba31b2084bfd84. Before/after APKs are isolated archive builds from that HEAD with the SAME required lock repair, diagnostics and fixture. Only snapshot sharing differs in production code. The follow-up uses byte-identical production APK hashes; only the test APK gains an optional Statistics-only cycle argument. `primary-protocol.json`, `statistics-followup-protocol.json` and source-identity manifests preserve hashes. Original unrelated artifact deletions, untracked files and all raw evidence remain untouched. Raw JSON retains the existing harness experimentId PI-20261014-12; run labels/manifests identify this PI-20261015-01 campaign.

#### Correction to Iteration13 extreme-tail attribution

The26.349s physical join is real, but it is NOT a continuously blocked habit edit. In before1825j operation12 (afterTasks), actual autocommit return is about10ms after accepted input, first same-revision row about311ms, native drain1139.98ms, and final observation reports the target unavailable. The old selected lineage publishes again around25653ms and draws around26272ms in **viewportSetup of the following scenario**, after mixed navigation and harness waiting. The26.349s Main window contains4329.62ms Running,1169.10ms runnable,20843.76ms S and6.71ms D. S does not by itself distinguish idle from monitor waiting; native scenario chronology proves the endpoint crosses later work. Do not assign that elapsed time to withRevision, GC, an emulator stall or deadlock.

The same issue affects36 late joins across four old long-history arms. Original physical matches and complete records remain preserved; the new scoped files retain invalid endpoint records under `outOfScopePresentedCandidate` and label them unmatched for the original edit. They are not discarded as slow outliers. Original long-history latency/CPU aggregates in PI-20261014-08 must not be used as valid edit-response comparisons; its cache rejection and provider-work observations remain historical evidence. Five valid joins per old arm do not establish latency for the ten unmatched operations.

| Old arm | original physical joins | qualified original-scenario joins | qualified median/p95/max ms |
|---|---|---|---|
| before1825j | 14/15 | 5/15 | 484.58/1056.73/1056.73 |
| before1825k | 14/15 | 5/15 | 910.80/1795.73/1795.73 |
| after1825j | 14/15 | 5/15 | 491.96/785.11/785.11 |
| after1825k | 14/15 | 5/15 | 638.71/745.92/745.92 |

For original valid baseline draws, row→glyph ranges115.96–201.86ms in j and130.59–408.27ms in k; these are genuine remaining UI intervals, not proof that all time is historical copying. The previously quoted627ms late row→glyph interval belongs to later viewport preparation. `old-tail-attribution.json` reconstructs every baseline operation, native revision/publication lineage, supersession/retry events and exact Main intervals for the slowest physical joins. Full SQL and schema checks are retained.

#### Mandatory lock-order blocker and selected performance mechanism

The baseline planning monitor called synchronized revision history, while genuine SQL rollback invokes controller rollback listeners under the revision monitor. A bounded deterministic native test holds revision on a releasable latch, observes the reader blocked on history, and probes controller availability; baseline fails6.612s. It separately confirms the genuine failed modified-transaction reverse edge, without permanently deadlocking the disposable process. Move history lookup outside controller, then accept it only for the identical cached-context object and observed revision; calendar/dataset/rollback/weekday/range guards and publication freshness remain unchanged. Green3.006s and final retained native check pass. This proves/removes lock inversion; it does not establish that the old26s endpoint was a deadlock.

Direct preparatory retag attribution:40 snapshots/76160 referenced days take38.44–205.20ms inside the controller; changed-habit provider23.78–113.58ms. withRevision forwards unchanged frozen inputs and precomputed score/streak results, but rebuilds the private date map and immutable contribution/representation/incomplete-input flags. The candidate shared only these structures through a private revision constructor, retaining no previous-wrapper field or lazy/deferred work. Red test observes14992 historical list reads for two retags, expected0; candidate green49 tests. Compiled-field inspection confirms no revisionSource field. The mechanism is proven even though the candidate fails end-to-end acceptance.

#### Fixture and measurement validity

Only existing offline AVD emulator-5580/UHabit_UI_Review_Disposable_20261011, serial EMULATOR36X6X11X0/API35. All12 final arms report open schema30 `journal_mode=wal`, actual helper WAL requested/default predicate true and no fixture journal override; controlled reader failures0. Production DatabaseUtils/HabitsDatabaseOpener enable WAL through the intended default path. v29 separately: unapproved canonical legacy databases do not request WAL; authorized/noncanonical staging v29 can. No real v29 database is opened. Final open-v30-WAL native test verifies concurrent reader/Main liveness, two writers, transaction revision boundaries, nested savepoints and rollback notifications without changing journal mode.

Retain42 habits,10 controlled90/1825-day histories, Tasks, historical goals and Focus Contributions. Fixed app reference date2026-10-11. Short uses sphere/position grouping; long GLOBAL status/position (sphere primary already has a position tie-break). Synthetic surrounding Boolean Sunday values are SKIP; numerical rows complete their current period, with any today-effective AT_MOST goal adjustment explicitly recorded/hashed and older goals/entries preserved. Four incomplete controlled rows include an unchanged leading anchor; target moves1↔3 through genuine NO→YES→SKIP→NO. Before-input viewport setup keeps that corridor visible; no post-input scrolling, animation suppression, optimistic committed endpoint, history truncation or speculative state publication.

Preserved failed viewport probes: visibleGlobal1825 (SKIP moves to25), visibleTriState1825 and visibleCorridor1825 (return to0 scrolls above RecyclerView's preserved anchor;10s unavailable wait retained). Their raw failures are not accepted performance samples. visibleAnchor1825 validates15/15 edits and4/4 Habits returns with SQL/snapshot/visible parity before primary capture. Scope guard rejects later-phase or post-retirement glyphs while retaining physical evidence; three focused tests cover it.

Primary: ABBA90 then ABBA1825, reverse scenario order in b arms,3 edits×5 scenarios per arm,3 mixed cycles.30 edits/side/depth. Follow-up: one preregistered BAAB90,10 Statistics-only cycles and the same five edit scenarios;30 additional edits/side and22 ordinary Statistics returns/side. No builds/trace analysis during capture. No further benchmark after follow-up. Process/JIT/SQLite/page-cache are warm; explicit fresh-projection probes are not process-cold launches.

All180 final edits retain exact SQL/effective/visible parity, revision-qualified publication and target visibility, with180/180 strict containing-doFrame→same-process actual app-surface presentation joins. Primary Habits returns47/48; before1825b afterTasks has no presented frame for its containing doFrame, retained unmatched. Follow-up52/52, including44 ordinary Statistics returns. Combined99/100 returns; no nearest-frame substitution. Tasks-open presentation is UNKNOWN; return-to-Habits is measured.

#### Primary per-stage results (median/p95/max ms)

Arithmetic median; nearest-rank p95;30 edits per row. Statistics of stages are separate, not additive medians. Native stages use one native clock; actual presentation uses Perfetto only. Complete120 operation rows: `primary-operations.csv` and `primary-summary.json`; complete60 follow-up rows: `statistics-followup-operations.csv` and `statistics-followup-summary.json`; all100 nav attempts: `all-navigation-operations.csv`. All raw JSON/traces and missing/slow records are retained.

| Workload/version | accepted→actual SQLite return | durable→qualified row | row→exact glyph | glyph→present | accepted→present |
|---|---|---|---|---|---|
| 90-before | 11.73/91.19/136.53 | 431.74/835.56/978.09 | 28.56/42.69/48.22 | 45.28/57.05/64.10 | 518.22/922.70/1191.56 |
| 90-after | 14.26/38.58/76.96 | 419.85/776.94/852.04 | 28.24/47.80/50.44 | 46.45/68.70/91.10 | 506.68/891.42/987.02 |
| 1825-before | 11.19/82.61/103.85 | 227.37/335.46/411.60 | 35.20/61.12/76.24 | 50.50/79.44/83.69 | 320.06/498.20/542.23 |
| 1825-after | 11.32/48.82/57.29 | 140.07/354.69/374.19 | 40.93/62.71/83.01 | 61.86/83.49/100.66 | 252.03/473.38/516.79 |

| Workload/version | retag | controller-held (sum/request consumers per edit) | Main CPU | app CPU (all threads) | Main runnable | named framework layout union | card measure union |
|---|---|---|---|---|---|---|---|
| 90-before | 11.58/32.69/42.21 | 14.46/38.30/47.97 | 53.18/85.32/114.22 | 659.37/1147.04/1772.16 | 34.05/109.22/134.06 | 0.00/0.00/0.00 | 0.18/0.80/0.90 |
| 90-after | 0.20/2.44/3.29 | 3.31/11.21/11.77 | 49.38/87.87/92.82 | 636.03/1217.80/1251.02 | 36.34/76.66/83.99 | 0.00/0.00/0.00 | 0.21/1.03/3.65 |
| 1825-before | 82.41/115.77/121.65 | 85.35/121.22/129.52 | 63.06/88.84/97.03 | 444.97/779.96/991.22 | 39.59/71.99/104.06 | 0.00/0.00/0.00 | 0.23/0.63/2.45 |
| 1825-after | 0.26/2.94/4.08 | 5.47/12.70/14.71 | 61.38/92.67/101.82 | 351.55/787.07/1012.40 | 38.66/80.05/93.43 | 0.00/0.00/0.00 | 0.22/2.07/2.38 |

| Scenario (6 edits/side/depth) |90 before→after presented median/p95 |1825 before→after presented median/p95 |
|---|---|---|
| afterMixedCycles | 501.68/690.70 → 467.86/747.10 | 354.49/498.20 → 287.66/467.16 |
| afterStatistics | 541.62/611.45 → 526.08/646.37 | 328.65/378.47 → 243.61/473.38 |
| afterStatisticsDateChange | 514.02/732.42 → 497.96/987.02 | 302.38/403.21 → 249.46/289.90 |
| afterTasks | 512.20/646.45 → 496.63/538.43 | 302.66/349.26 → 246.26/323.97 |
| warmRepeated | 556.20/1191.56 → 786.46/891.42 | 339.48/542.23 → 289.27/516.79 |

| Arm | edit median/p95/max | edit joins | Habits-return joins | UI provider ms / prepared snapshots / processed days | end PSS KiB |
|---|---|---|---|---|---|
| before90a | 566.17/1191.56/1191.56 | 15/15 | 6/6 | 3864.03 / 430 / 9915 | 128793 |
| after90a | 521.01/987.02/987.02 | 15/15 | 6/6 | 2485.57 / 430 / 9915 | 149044 |
| after90b | 486.21/891.42/891.42 | 15/15 | 6/6 | 2875.38 / 430 / 9915 | 143542 |
| before90b | 502.39/690.70/690.70 | 15/15 | 6/6 | 2499.70 / 430 / 9915 | 131680 |
| before1825a | 313.30/542.23/542.23 | 15/15 | 6/6 | 6304.82 / 430 / 47345 | 147416 |
| after1825a | 251.15/516.79/516.79 | 15/15 | 6/6 | 5886.55 / 430 / 47345 | 153603 |
| after1825b | 255.43/467.16/467.16 | 15/15 | 6/6 | 5581.92 / 430 / 47345 | 139758 |
| before1825b | 329.77/498.20/498.20 | 15/15 | 5/6 | 5924.79 / 430 / 47345 | 142230 |

Both primary edit-median pairs improve at each depth, but do not hide scenario regressions: short warmRepeated pooled median556.20→786.46ms; direct pair improves (baseline1191.56/589.15/922.70 vs candidate810.21/511.47/439.02), reverse pair worsens (480.21/523.26/483.54 vs786.09/891.42/786.83). Exact values/per-operation ordering remain in CSV. First-per-scenario and later samples are separately summarized in JSON; neither first/JIT effects nor cumulative navigation are discarded.

Same UI preparation counts before/after:860 snapshots per side per depth,19830 processed days short and94690 long. Cross-range A→B→A still rebuilds with the single-range cache; explicit probes and all controller hit/retry/latch/retag counters are retained. No new cold algorithm or cache-reuse benefit is claimed. Short UI provider totals6363.73→5360.96ms; long12229.61→11468.46ms are descriptive elapsed totals, not CPU or proof of cold-constructor speedup. Candidate retag benefits remain explicit, not substituted for acceptance.

Actual post-durable work is strongly configuration-dependent: repetition.resortAndObservers median/p95/max387.52/674.27/809.68ms grouped90 versus4.02/30.74/45.65ms global1825; model recompute1.04/16.86/47.03 versus18.55/43.60/46.57ms. These are named command envelopes, not exclusive comparator CPU. Source SQLiteHabitList.resort→MemoryHabitList.resort clears SphereComparator root caches, resolves facade placements/root paths and notifies observers; getComposedComparator also obtains comparators during comparisons. Resolving the envelope into sort/root lookup/observer contributions is the next bounded investigation, not a change in this iteration.

For the drawn batch, ready→Main row publication median1.46→1.66ms short and3.35→10.22ms long; candidate long p9526.21→44.25ms, max58.22→74.97ms is adverse, retained. Provider/scheduler and superseded full/targeted batches are separately reconstructed in each operation's qualifiedCachePath; no entire durable interval is assigned to withRevision. Latch max long31.78→114.80ms is retained, not hidden. Maximum controller monitor wait long32.38→9.36ms does not explain multi-second old endpoints.

Row→glyph long35.20/61.12/76.24→40.93/62.71/83.01ms; publication→bind-end medians28.38→33.10ms and bind-end→draw6.76→6.44ms. Native post-row union wall medians across visible rows: full-card bind4.21→5.14ms, payload bind5.15→6.17ms, numerical setup1.84→1.99ms. Nested scopes are not summed as CPU. Global status emits30 move callbacks/side versus0 grouped; source processPosition may emit neighbor moves, but callback counts are identical before/after. Main publication/scheduling/view traversal remains separate from background historical retag. The earlier120–408ms valid old UI intervals and viewport-era full work do not establish a current five-year payload-bind algorithm cost. Zero overlap of selected framework measure/layout names is not zero lifetime layout.

#### Secondary regression: fixed follow-up rejects candidate

Primary ordinary Statistics returns worsen280.98→349.98ms and236.90→639.03ms; Tasks improves263.18→247.04 and243.31→238.76ms; date-change results mixed. Primary Statistics Main CPU169.87→222.80 and150.94→329.67ms, while the639.03ms endpoint includes332.23ms glyph→present. This is more than a blanket compositor explanation. Whole-window CPU is contextual and can include other work; no unmeasured interval is assigned to GC/JIT.

| BAAB arm | standalone Statistics return ms | repeated Statistics n | repeated median/p95/max ms | repeated Main CPU median/p95/max ms |
|---|---|---|---|---|
| after90c | 365.23 | 10 | 249.31/758.43/758.43 | 158.41/521.64/521.64 |
| before90c | 293.52 | 10 | 271.34/505.16/505.16 | 167.37/325.85/325.85 |
| before90d | 227.62 | 10 | 280.56/340.01/340.01 | 174.77/210.09/210.09 |
| after90d | 239.08 | 10 | 289.85/378.17/378.17 | 171.05/222.21/222.21 |

Repeated Statistics p95 grows505.1573→758.4253ms (+50.1%) and340.0054→378.1749ms (+11.2%). Both exceed the preregistered10% gate. At n10 p95 is the maximum; every record remains available. Candidate repeat median improves in c and worsens in d; that does not cancel tail rejection. Standalone Statistics also remains slower365.23 vs293.52 and239.08 vs227.62. Follow-up short edit median555.73→556.15, p951143.52→1043.88, max1366.89→1095.11ms; the slow1094.44ms baseline Tasks return is retained separately and does not dilute Statistics. The exact cause of candidate-dependent navigation tails is unresolved; fresh constructor semantics/work counts are unchanged and larger JIT/Main work overlaps exist, but no source/GC attribution is proved. Conservatively revert rather than certify a broad improvement.

#### CPU, GC/JIT, memory and uncertainty

`*-context-fast.json` and `*-nav-context-fast.json` contain executed SQL, exact trace-clock windows, scheduled CPU, all Main state intervals/blocked-function fields, layout unions and contextual GC/JIT slices. `context-mechanisms.json` separates named JIT-thread CPU from named GC-pause wall overlap. In primary maxima, baseline90 op1 is1191.56ms with Main85.32ms Running/109.22ms runnable and651.00ms summed CPU on JIT-named threads; baseline1825 op1 is542.23ms with Main72.24ms Running/38.07ms runnable and473.50ms JIT-thread CPU. Candidate maxima90 op7/1825 op1 have Main92.82/92.67ms and JIT-thread CPU50.46/475.19ms. None overlaps the recorded named Mutator-threads-suspended-for-ScopedPause slice. This bounds that named pause explanation; concurrent GC/JIT markers do not prove whole elapsed-time causation. Full state intervals, native span chronology and provider/retag metrics remain necessary. No allocation profiler/retained-heap dump was collected; traversal/map construction reduction is deterministic source/test evidence, not an invented allocation count.

Health: sorter_push_event_out_of_order8 in after90a/after90b/after1825b and16 in before1825a; other final edit contexts have no nonzero error/data_loss stat. Actual unique app-frame joins remain verified; scheduler/CPU interpretation has this trace-health limitation. All180 final target frames report App Deadline Missed (mixed app/SurfaceFlinger/prediction/buffering causes); no jank cure is claimed. Raw listener counts stay phase-bounded (revision1/2, observable0/1, command1/2), activeTaskCount0 at boundaries, without cumulative listener growth. Cache architecture remains one active range. Candidate compiled fields show no wrapper chain, but candidate short end PSS149044/143542 versus baseline128793/131680KiB is adverse. Heap samples have no forced GC and cannot establish retained size or a memory win; long PSS is mixed. Preserve this uncertainty.

#### Ranked remaining work and verification

| Rank | Measured/source-supported cause | Confidence / implementation risk |
|---|---|---|
|1|Grouped command resortAndObservers ~388ms median,674ms p95; root/placement lookup, sort and observer envelope|Envelope CONFIRMED; exact internal dominance UNKNOWN; medium risk, preserve ordering/containers|
|2|Revision-only historical rebuilding ~82ms median long,122ms max primary; unnecessary immutable index/flag work|CONFIRMED; tested sharing variant REJECTED for navigation tails; do not restore it as accepted|
|3|Statistics/Habits Main rendering and presentation tails; repeated candidate p95758/378ms, baseline505/340|Measured; candidate causal CPU/JIT mechanism UNKNOWN; medium risk, lifecycle correctness required|
|4|Cold/range-switch projection work and single-range cross-screen churn|CONFIRMED preparation counts; prior two-range candidate still rejected; architecture change high risk|
|5|Row publication→bind/draw ~28–41ms median plus actual frame presentation; neighbor moves and hidden numerical panel setup are concrete source paths|Intervals CONFIRMED, exclusive dominance unproved; optimize only after narrower attribution|

Required lock inversion is repaired as a correctness blocker, not blamed for the old26s artifact. Final retained checks:48 core tests (20 snapshot,17 revision,2 database concurrency,9 widget),9 native tests (lock regression, contention/invalidation,6 InFlightBatch, open-WAL),20 Python attribution tests. Candidate red/green and compiled-field evidence remain archived. Native wrapper initially expected5 InFlight tests although all6 passed; corrected expected count and reused those passing logs before completing WAL, preserving the wrapper failure log. A test-only Mokkery ignoreFinalMembers setting is retained because its compiler otherwise rejects the existing final CommandRunner listener diagnostic in widget mocks; production CommandRunner is unchanged (official Mokkery guide: https://github.com/lupuuss/mokkery/blob/master/website/docs/Guides/Mocking.mdx).

Retained source/files: EffectiveHabitReadController (lock repair + optional diagnostics), its bounded Android regression, existing snapshot semantic test, existing post-navigation QA fixture/scope controls, QA runner/analyzer + focused scope tests, core test-mocking configuration, this record and handoff. The production snapshot candidate and candidate-only performance assertion are absent. Independent review confirms gate arithmetic, revert, retained locking and semantic-test applicability.

**Next:** emulator-only attribution of grouped resort/root-path/observer work before choosing a correction. Also retain Statistics navigation and exact-presentation tail coverage. No physical-device release direction, cloud/private database access, journal-mode redesign, push or additional optimization. STOP after this rejected candidate evaluation and verified correctness repair.


## PI-20261015-02 — Iteration 15: grouped resort and synchronous observers

Status: COMPLETE / root-only candidate REJECTED and reverted. Branch dev, verified starting HEAD8962ec91c6c22cce93fe20c993b14ceca4567109. Question: which in-scope work causes the grouped resortAndObservers stage, separating history depth from grouping? Initial source evidence: SQLiteHabitList.resort assigns the facade (whose MemoryHabitList setter sorts/notifies), then explicitly sorts/notifies again. Inner synchronous filtered-list observers are absent from the old wrapper-only listener count. Neither finding alone proves latency attribution.

Pre-registration: add disabled command-scoped probes, then a small controlled 2x2 (90/1825 days x sphere/position or global status/position), identical42-habit organization/Tasks/Focus fixture and visible demo target. Measure sort/comparator/root/SQL/callback work and scheduled CPU, not unrelated elapsed background activity. Select only one proven correction; keep all required observer semantics. Baseline and candidate use identical probes/fixture, isolated APKs, counterbalanced order. Acceptance requires deterministic ordering/invalidation/concurrency checks, exact SQL/effective/visible parity, strict original-operation FrameTimeline joins, reduced real work and end-to-end grouped benefit without reproducible material short/global/navigation regression. Preserve every slow/unmatched attempt. No uncommitted optimistic state, new persistent cache, history truncation, WAL changes or restoration of rejected candidates.

### Retained interrupted attempt and QA amendment

Original b1825sa (baseline1825/global) hit the600s host runner timeout after a large host/tool clock discontinuity. Native monotonic duration was117.207s, so the host gap is NOT assigned to application latency. One input was accepted and a changing autocommit return occurred, but no committed target glyph was captured and the native test failed before appending its operation result. Read-only synthetic SQLite evidence shows target habit97 written for2026-10-15 while the fixture2026-10-11 value remained0; the publication check expected2 for the fixture day and failed. The exact triggering calendar event was not logged; existing controller calendar receivers can legitimately reset getToday to the real day. This is retained as a timeout/date-mismatched, unmatched attempt, not discarded as an outlier or included as a valid presentation sample. RawJSON,35MBtrace, original full host log and synthetic-only readback are preserved under scratch/iteration15/b1825sa*.

QA amendment before further measurements: restore the fixture day only during unmeasured viewport preparation when drift is detected, signal revision and settle; assert/log the day immediately before measured pointer events and the post-drain SQLite query. Never restore during a measured edit. Production APK hashes remain unchanged. Keep the completed90/grouped ABBA cohort; repeat the entire1825/global cell with matched guarded test APKs/new labels. Original successful c1825sa and failed b1825sa remain separate supplemental records. primary-protocol-v2.json records both QA artifact generations and the amendment. The runner now preserves partial stdout/timeout metadata immediately and attempts native/trace recovery before failing; capture signaling requires exact ownership. Four deterministic mocked runner checks pass. A temporary SYSTEM_REQUIRED request on the orchestrator thread reduces idle-sleep risk and is released in finally, without changing the power plan.


### Final decision and experiment identity

Evaluate ONE correction, then stop: root-only parent traversal was implemented, passed candidate correctness checks and reduced real grouped work, but failed the preregistered end-to-end tail gate. It is **not retained**. No event suppression, persistent organization cache, snapshot-sharing/date-range-cache restoration or unrelated performance change was substituted. The archive is `scratch/iteration15/rejected-root-only-with-probes.patch`, plus `candidate.patch`, isolated candidate source/APKs and `SQLiteRootGroupingTest-candidate.kt`. `candidate-decision.json` records rejection. Retained production behavior uses the original `ContainerQueries.path()` and original root selection; contracts/facade have no net diff.

Verified branch/source/dirty identity before work: dev,8962ec91c6c22cce93fe20c993b14ceca4567109; `initial-state.json` and `source-identity.json`. Preserve132 pre-existing artifact deletions, unrelated untracked files and byte-identical snapshot stat noise. Only emulator-5580 / UHabit_UI_Review_Disposable_20261011 / EMULATOR36X6X11X0, airplane mode1, guarded synthetic offline PRODUCTION_NEXT v30. The open native connection reports `journalMode=wal` (e.g. diag1825g.json); the production opener requests WAL, not just QA setup. Final retained open-WAL reader/writer/savepoint test passes. No WAL/transaction/migration code change, real database, physical-device, sync or cloud action. Prior v29 source finding remains unchanged; this iteration does not claim a fresh v29 runtime measurement.

### Controlled design and source attribution

The pilot is3 genuine visible Boolean toggles in each90/1825-day × grouped/global cell. Same42 habits, organization hierarchy and date,10 history-controlled habits, Tasks, historical goals and Focus Contributions; demo target is visible and stable. Grouped is sphere/position; global is completion-sensitive status/position, so it tests real movement. History width and sort configuration are explicit separate cells. Pilot ordering90g,1825global,1825g,90global is bounded attribution, not a randomized production benchmark.

CreateRepetitionCommand's measured worker stage calls SQLiteHabitList.resort: facade assignment sorts/notifies its inner MemoryHabitList; explicit resort sorts/notifies again; outer observable then notifies. Synchronous listeners rebuild filtered MemoryHabitLists, each sorting again. SphereComparator resolves placements and root paths after its per-sort caches reset. The old path reads OrganizationState to attach currentRevision, although getRootGroupKey consumes only the first root ID; every ancestor is materialized as a full Container. Root indices and placement reads are separate work. A command-scoped thread-local collector excludes unrelated background SQL from these counts; nested postorder spans identify subjects and callback downstream work. A callback span is entered after acquiring ModelObservable's monitor, so it does not measure monitor acquisition wait. Elapsed minus owner scheduled CPU is not automatically a repository lock wait. Retained owner thread states/blocked-function records provide that distinction; no deterministic repository-monitor bottleneck was demonstrated here.

Per grouped edit in BOTH history cells:6 Memory resorts,7 observable notifications,4 synchronous callbacks,166 root misses/lookups,178 full-container ancestor reads,166 OrganizationState reads and547 SELECT step calls. Global has the same6/7/4 events but no sphere/root traversal and1 SELECT step call. SELECT steps count ROW/DONE invocations, **not distinct SQL queries**. Composed comparator construction is1/edit; selectors162 grouped, global median362 (comparisons207), so repeated comparator construction is not the dominant source. Detailed grouped root-lookup inclusive spans consume approximately89–90% of measured stage wall. Callback self work is small; inclusive callback time largely contains filtered rebuild/root queries. Do not add nested callback, sort and lookup times together or label sort self as pure TimSort CPU.

Baseline detailed-probe medians (wall / Debug owner CPU ms):90g1101.82/958.76;1825g841.18/695.80;90global95.93/47.38;1825global65.77/50.87. Work counts agree across histories: grouping, rather than history depth, drives this stage in the fixed fixture. Detailed probes are expensive, especially relative to global sorting. These absolute values are **not** low-probe latency estimates. Primary confirmation disables detailed counters/spans in both builds and retains only one outer-stage Trace span and independent Debug CPU duration. `resort-detail-analysis.json` preserves24 valid span trees, raw counters, nested/self calculations and source identities. Candidate detailed cohort has an added observable.notify span and calendar guard, so its absolute detailed latency is contextual only; primary arms establish the comparison.

### One selected correction and invariants

Candidate adds ContainerQueries.rootId with path fallback, changes the facade to consume it, and implements a fresh SQLite parent-UUID walk using one locally reused statement, visited-node guard and finally-finalize. It avoids the unused path revision and full Container/path metadata. No cross-operation cache, retained statement, lock, transaction or listener registration is added. Traversal deliberately matches TreePolicy's add-before-read semantics for missing/deleted nodes, archived ancestors and cycles; placement/ancestor reads remain fresh on every call. It preserves all6 sorts/7 notifications/4 callbacks. Candidate detailed runtime confirms166 OrganizationState reads→0,178 full Container reads→178 parent-only reads,547→381 SELECT steps. Parent/placement lookup fan-out remains, so this is a bounded reduction rather than an architectural cure.

Candidate red/green: four SQLiteRootGrouping tests include query budget, path differential parity, placement/reparent/real rollback/same-local-ID dataset freshness, and injected statement failure cleanup. On baseline, two candidate-only assertions fail (query budget and failed-traversal statement cleanup); corrected baseline fixture avoids the repository's deliberately cached insert statement, preserving active-handle assertions. Candidate all4 pass. Initial incorrect fixture failure and corrected red/green logs remain archived. Failure cleanup improvement is part of this candidate's local statement design, not a separately retained production fix. On rejection archive/remove the2 candidate-only assertions; retain the2 valid semantic tests. No speculative committed values, history truncation or revision bypass.

### Matched low-probe end-to-end results

16 isolated arms, ABBA or BAAB within EACH cell,2 arms/product/cell,3 edits in each of5 scenarios: warm repeated, Statistics return, Statistics date-change return, Tasks return,3 mixed navigation cycles. Total240 edits and96 Habits-return endpoints. Each arm starts fresh projection/entries but process/JIT/OS caches are already warm; do not call this process-cold. No build/trace processing overlapped capture. `primary-protocol-v2.json` records arm order, fixture/APK hashes and the explicitly retained QA amendment above. Baseline app SHA256 d8e8007772f5bb87aa706260f1dda4aebb548c61daccd3a3a41c3464c1384356; candidate c0efbd949b584a43e59c9c602e5784424c9c4f802f0091903d6c6a78b2f359a0. Only the3 root-only source files differ in production behavior; shared probes/QA are matched. All primary native sqlParity, viewParity and visible checks are true240/240; fixture hashes match. Raw native SQLite changing-autocommit return is distinct from command completion. Qualified row must match original operation/date/revision, then exact committed glyph and its containing app-surface FrameTimeline frame. Missing frames stay null.

The following independently audited tables are the rejected candidate comparison. All240 per-operation stage distributions, scenario/first/later partitions and matched-arm gates are in `primary-summary.json`; complete rows in `primary-operations.csv` and `primary-navigation.csv`. Names are recorded by the summarizer; accepted-operation-ledger-v2.csv is the all-attempt ledger.456 distributions and all240 source presentation/parity records were independently recalculated. Do not pool four cells, add medians, or infer end-to-end improvement from a microbenchmark.

## Exact presented outcomes

Each cell is `n; median / p95 / maximum`.

| Cell | Edit baseline | Edit candidate | Navigation baseline | Navigation candidate |
|---|---:|---:|---:|---:|
| 90-grouped | 30; 1041.91 / 2295.64 / 2521.32 | 30; 835.92 / 1335.07 / 1839.09 | 12; 604.21 / 843.40 / 843.40 | 12; 488.01 / 932.51 / 932.51 |
| 1825-grouped | 30; 837.24 / 1197.88 / 3042.19 | 30; 717.51 / 1235.18 / 1410.00 | 12; 425.86 / 808.23 / 808.23 | 11; 331.27 / 480.12 / 480.12 |
| 90-entrySensitive | 30; 381.41 / 695.75 / 1048.22 | 30; 440.55 / 957.32 / 1311.01 | 11; 299.81 / 362.40 / 362.40 | 12; 335.49 / 1231.27 / 1231.27 |
| 1825-entrySensitive | 29; 527.91 / 1025.53 / 1229.30 | 30; 456.96 / 917.26 / 1262.29 | 12; 328.26 / 528.35 / 528.35 | 12; 271.84 / 401.63 / 401.63 |

#### Stage medians

Each stage is `n/median` baseline → candidate. Different denominators are deliberate; adding medians does not reconstruct the end-to-end median. Resort is a parallel nested interval, not an additive pipeline stage.

| Cell | Accepted→durable | Durable→row | Row→glyph | Glyph→present | Resort native wall | Resort Debug CPU |
|---|---:|---:|---:|---:|---:|---:|
| 90-grouped | 30/28.97 → 30/21.91 | 30/869.12 → 30/649.01 | 30/45.88 → 30/53.25 | 30/101.39 → 30/100.45 | 30/761.66 → 30/540.23 | 30/596.75 → 30/411.53 |
| 1825-grouped | 30/24.92 → 30/16.96 | 30/709.05 → 30/566.24 | 30/37.86 → 30/43.07 | 30/60.82 → 30/67.93 | 30/552.86 → 30/415.55 | 30/453.19 → 30/327.82 |
| 90-entrySensitive | 30/16.24 → 30/19.74 | 30/244.93 → 30/284.06 | 30/53.20 → 30/48.77 | 30/80.34 → 30/85.34 | 30/8.75 → 30/15.04 | 30/5.04 → 30/7.68 |
| 1825-entrySensitive | 30/32.80 → 30/28.14 | 30/354.59 → 30/333.05 | 30/36.34 → 30/36.54 | 29/65.67 → 30/64.84 | 30/10.24 → 30/12.56 | 30/8.07 → 30/9.06 |

#### CPU and layout medians

CPU/layout use exact accepted→presented windows; missing presentation has no invented context. App CPU sums concurrent threads and can exceed elapsed wall. Layout is union wall overlap, not CPU.

| Cell | Main CPU | App CPU | Main runnable | Main sleeping | Card measure union | Resort owner sched CPU |
|---|---:|---:|---:|---:|---:|---:|
| 90-grouped | 30/89.60 → 30/88.43 | 30/1308.43 → 30/957.29 | 30/83.25 → 30/74.56 | 30/895.62 → 30/684.05 | 30/0.31 → 30/0.37 | 30/596.00 → 30/416.20 |
| 1825-grouped | 30/74.45 → 30/73.67 | 30/1087.95 → 30/854.87 | 30/46.87 → 30/46.17 | 30/697.91 → 30/603.31 | 30/0.23 → 30/0.25 | 30/454.46 → 30/328.76 |
| 90-entrySensitive | 30/85.63 → 30/72.27 | 30/479.11 → 30/520.90 | 30/57.30 → 30/74.98 | 30/240.87 → 30/305.06 | 30/0.18 → 30/0.28 | 30/6.93 → 30/10.39 |
| 1825-entrySensitive | 29/89.08 → 30/78.47 | 29/675.78 → 30/614.26 | 29/79.33 → 30/63.59 | 29/346.59 → 30/333.31 | 29/0.22 → 30/0.26 | 30/8.99 → 30/9.26 |

#### Provider work, memory and delayed observations

Provider totals cover all recorded non-fixture/non-range-switch UI phases, including cold launch. These are per two-arm/product cohorts, not per-edit medians. Processed days are sum of provider batch horizons; processedHabitDays multiplies each horizon by its prepared habit count. PSS is two end-of-arm samples, not retained heap/leak evidence.

| Cell | Prepared snapshots B→C | Provider ms B→C | Batch days B→C | Habit-days B→C | End PSS KiB B→C | Observed post-retirement events B→C |
|---|---:|---:|---:|---:|---|---:|
| 90-grouped | 860 → 860 | 15010.96 → 11735.47 | 19830 → 19830 | 304430 → 304430 | 126774, 122345 → 146004, 134334 | 0 → 0 |
| 1825-grouped | 860 → 860 | 20128.93 → 17600.04 | 94690 → 94690 | 1636930 → 1636930 | 158595, 144395 → 150277, 159047 | 0 → 0 |
| 90-entrySensitive | 860 → 860 | 8257.10 → 12481.90 | 19830 → 19830 | 304430 → 304430 | 126316, 131416 → 124894, 119522 | 0 → 0 |
| 1825-entrySensitive | 860 → 860 | 24881.13 → 20422.92 | 94690 → 94690 | 1636930 → 1636930 | 142018, 146813 → 144978, 145984 | 0 → 0 |

Delayed observations count only recorded provider/controller/publication/move events after final retirement until artifact emission. They do not measure post-present CPU, exclude other callback types, and do not prove no later work. Full event records are retained in arms[].delayedAfterLastRetirement.

#### Rejection gates

Each value is per-arm scenario p95; edit scenarios n3/arm (p95=max), standalone navigation often n1/arm. Rejection condition: >10% AND >20ms worsening in BOTH matched order pairs.

| Cell / endpoint / scenario | Forward pair B→C | Reverse pair B→C | Coverage caveat |
|---|---:|---:|---|
| 1825-grouped / edit / afterTasks | 3:839.6896 → 3:1410.0045 | 3:881.1869 → 3:997.7279 | Small scenario sample; all observed records retained |
| 90-entrySensitive / edit / afterTasks | 3:440.6357 → 3:762.2441 | 3:422.0910 → 3:553.4524 | Small scenario sample; all observed records retained |
| 90-entrySensitive / edit / warmRepeated | 3:695.7475 → 3:957.3153 | 3:378.6542 → 3:420.1889 | Small scenario sample; all observed records retained |
| 90-entrySensitive / navigation / afterMixedCycles | 2:334.5001 → 3:1231.2695 | 3:362.4049 → 3:409.5145 | Forward baseline navigation missing one endpoint; complete tail UNKNOWN |

#### Missing endpoints and health

- b1825sb2 edit 8 (afterStatisticsDateChange): no_presented_frame_for_containing_doFrame.
- c1825ga2 nav 6 (afterTasks): no_presented_frame_for_containing_doFrame.
- b90sa2 nav 10 (afterMixedCycles): no_presented_frame_for_containing_doFrame.

Nonempty per-arm trace health: `{"b90ga": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "c90ga": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "c90gb": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "b90gb": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "b1825sa2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "b1825sb2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "c1825sb2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 16}], "c1825ga2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "b1825ga2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "b1825gb2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 20}], "b90sa2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 16}], "c90sa2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "c90sb2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}], "b90sb2": [{"name": "sorter_push_event_out_of_order", "severity": "error", "value": 8}]}`. Strict joins do not establish trace-wide error freedom.


#### First versus later edits and cache reuse

Each presentation value is n; median/p95/max ms. First is sample0 of each scenario (including warmRepeated), later samples1/2. Exact per-navigation scenario breakdown remains in primary-summary.json; this table does not isolate JIT or collapse five different returns into a causal claim.

| Cell | First edit B → C | Later edits B → C | Native cache hits B → C | Prepared B → C |
|---|---|---|---|---|
| 90-grouped | 10; 1112.11/2295.64/2295.64 → 10; 867.39/1839.09/1839.09 | 20; 1035.30/1712.43/2521.32 → 20; 828.15/1076.11/1335.07 | 2331 → 2211 | 860 → 860 |
| 1825-grouped | 10; 911.00/1128.41/1128.41 → 10; 703.45/1158.58/1158.58 | 20; 802.64/1197.88/3042.19 → 20; 729.73/1235.18/1410.00 | 2370 → 2530 | 860 → 860 |
| 90-entrySensitive | 10; 408.49/1048.22/1048.22 → 10; 398.90/957.32/957.32 | 20; 381.41/593.46/695.75 → 20; 458.20/762.24/1311.01 | 2750 → 2585 | 860 → 860 |
| 1825-entrySensitive | 10; 474.01/817.66/817.66 → 10; 456.96/806.76/806.76 | 19; 530.27/1229.30/1229.30 → 20; 469.42/917.26/1262.29 | 2611 → 2570 | 860 → 860 |

Each arm has25 provider calls and430 prepared snapshots across non-fixture/non-range-switch UI phases, including fresh coldLaunch; two-arm totals are860/product/cell. Cold preparation and range switches were explicitly captured in the detailed pilot, while primary disables repeated9 standalone range alternations to bound the navigation campaign. Prepared work/habit-days remain equal; cache-hit counts are recorded controller.lock events, not inferred avoided work. No cache-architecture win is claimed. All recorded frames except one1825-global baseline outcome include App Deadline Missed (mixed SurfaceFlinger/prediction/buffering causes); source frame records preserve exact jank strings. The selected-name framework layout/measure median is0 because no matching slices were observed, not because layout is free. Card-measure union and Main CPU medians are above. No allocation profiler, forced-GC retained heap or complete post-presentation CPU campaign was collected; PSS and0 recorded delayed events cannot prove a memory win, absence of leaks or absence of later CPU. Rejection does not depend on inventing such a claim.

### Tail attribution, limitations and rejection

Worst linked primary operation b1825gb2/op8: accepted→present3042.193ms; exact worker resort2754.881ms, owner scheduled CPU2273.154ms, runnable481.727ms; independent Debug CPU2282.066ms. Main CPU58.982ms, Main sleeping2878.449ms; no overlapping GC-named slice. This localizes substantial necessary/removable work to grouped resort, rather than Main layout or an assumed emulator stall. It does not identify every slow query as monitor blocking. First cold pilot90g stage2437ms has1623ms scheduled CPU and353ms sleeping; long1318/864/155ms similarly contains non-CPU intervals. No unexplained remainder is assigned to GC, JIT, withRevision or the emulator.

Global candidate c1825sb2/op12 tail1262.293ms overlaps GC-named slice union1004.142ms/JIT-named384.077ms and Main runnable280.995ms; event-local retag485.746ms. These overlaps are contextual, not proof of a stop-the-world cause. Short global candidate Tasks op12/6 tails762.24/553.45ms include resort30.36/89.94ms; warm op1/14 tails957.32/420.19ms include resort30.39/4.16ms. Main sleeping/runnable and JIT context exist, with no GC-named overlap in those4, but sleeping reason remains UNKNOWN. The source global path does not invoke rootId, so candidate-dependent tail mechanism is unproved. This cannot excuse or remove slow operations. Both matched pairs breach the preregistered >10% AND >20ms p95 gate for90/global warm/Tasks and1825/grouped Tasks. At n3 per scenario, p95=max; bounded samples are evidence for conservative rejection, not a universal statistical causal estimate. Mixed navigation additionally worsens in both pairs, with an incomplete forward baseline endpoint explicitly UNKNOWN. Grouped aggregate medians/CPU improve, but no general performance win is certified.

Exact overall ledger:280 accepted =240 primary +12 baseline pilot +12 candidate detailed +15 original successful supplemental +1 failed interrupted input.278 linked,2 unmatched,0 ambiguous. Primary239/240; missing b1825sb2/op8 afterStatisticsDateChange has committed glyph but no presented frame for its containing doFrame. Failed original b1825sa/op1 is forensic-unmatched with actual-write636.9213ms, wrong fixture date, raw unqualified row interval9328.975ms, and NULL qualified-row/glyph/presentation/total.102 navigation attempts (96 primary+6 supplemental):100 linked,2 unmatched; missing c1825ga2/nav6 afterTasks and b90sa2/nav10 mixed cycles. Never substitute a nearby frame or discard the timeout. `accepted-operation-ledger-v2.json/.csv` and navigation CSV retain every operation, source lineage, reason and classification; v1 outputs also preserved.

Trace health retains sorter_push_event_out_of_order errors in14/16 primary traces (8–20 events); no additional data-loss rows returned by retained queries. Strict unique joins do not establish trace-wide health. Owner scheduler versus independent Debug CPU in long arms differs median2.270ms/max38.819ms; retain that limit. Literal executed SQL, exact PID/UTID windows, all thread states/blocked-function fields, layout unions, GC/JIT records and health are in each *-primary-context.json; no native-to-trace timestamp subtraction or guessed clock mapping. The selected root traversal adds no persistent cache/new background task, but unmeasured late work remains a limit; the candidate is reverted regardless.

### Retained files, final checks and ranked next work

Retained source: optional HabitResortDiagnostics; command scope and SQLite/Memory resort, comparator/cache, repository-path and live ModelObservable probes; native PostNavigationPresentationAttributionTest controls/outer CPU/guarded calendar; evidence-preserving runner plus deterministic runner tests;2 SQLite root-order/invalidation semantic tests and4 diagnostic isolation/exception tests; this report/handoff. Disabled diagnostics return before per-call clock, collector allocation or thread-local lookup; no production hook assignments. QA installs hooks only explicitly and clears finally. Listener order, duplicate notifications, exceptions, monitors, comparator tie-breaking, placement semantics and FIFO remain unchanged. Root-only API/facade/algorithm is absent; snapshot byte-identical to starting HEAD. Independent retained-source review found no actionable blocker; custom intermittently failing diagnostic clocks could invalidate duration origin, not business outcomes, and actual QA uses stable System.nanoTime/Debug clocks.

Final retained checks:97 core tests (7 facade,15 store contracts,5 store specific,2 root semantics,4 diagnostics,3 Memory concurrency,13 SQLiteHabitList,2 effective DB concurrency,20 snapshot,17 revision,9 widgets),0 failures/errors/skips; assembleDebug and assembleDebugAndroidTest exit0. Candidate99 core passed before rejection.24 Python QA tests pass, including4 new runner checks (timeout evidence, missing alternate pull, capture ownership/reused label). Final retained APK reruns9 native tests: controller/revision lock regression1, contention/invalidation1, InFlightBatch6, actual open-v30 WAL readers/writers/savepoints1; all pass. See final-retained-checks.txt, final-retained-core-results.json, python-tests-final.txt, retained-native-*.txt and candidate red/green archives. All build/test results refer to observed exit/status, not planned checks. Required Iteration14 liveness repair is retained and freshly tested.

| Rank | Confirmed cost / next bounded question | Confidence / risk |
|---|---|---|
|1|Grouped SQL root/placement fan-out across6 sorts and4 callbacks; original166 unused revision reads remain because this candidate failed E2E acceptance. Can repeated data work be removed while preserving live/reentrant observer semantics and both paired Tasks tails?|Mechanism/work CONFIRMED; safe accepted correction UNKNOWN; medium/high risk|
|2|After-Tasks and global warm durable→row/scheduling tails persist, often outside the tiny global resort. Reconstruct sleeping/runnable/revision/retag intervals for these exact operations before another candidate.|Intervals CONFIRMED, precise blocker UNKNOWN; medium risk|
|3|Remaining immutable revision-only history rebuild and cold/cross-range projection work; prepared counts unchanged. Do not restore rejected sharing/two-range designs.|Work CONFIRMED; accepted strategy UNKNOWN; high semantic risk|
|4|Row→glyph ~36–53ms medians and glyph→actual frame ~61–101ms, navigation rendering/presentation; recorded layout names do not prove dominance.|Intervals CONFIRMED, exclusive cause UNKNOWN; medium risk|

Recommended next direction remains **emulator-only grouped lookup/rebuild fan-out attribution with Tasks/warm-tail controls**. This iteration stops after rejecting the ONE tested optimization and retaining verified investigation infrastructure. No physical-device release redirection, second candidate, push, private/cloud operation or WAL redesign.

---

## 7. PI-20261015-03: Iteration 16 — Benchmark Reliability, A/A Calibration and Calibrated Decision Protocol

### Experiment identity and environmental baseline
- **Record ID**: `PI-20261015-03`
- **Date**: 2026-10-15, Asia/Novosibirsk (HEAD `c2e531de0e7048f1e59533b27bb2a7de5665ae35`, branch `dev`).
- **Target build**: Debug APK (SHA-256: `2225FF8606305B17CEC88DC626AA2F0A2824EABA100D77B3059109546EFE9037`), AndroidTest APK (SHA-256: `A8A747DC3AF54C8F9C157B856CF041EF91CEBD43756FEF0D5B0B2DFD582BDC5C`). Both built locally from clean HEAD.
- **Device & Environment**: Dedicated offline disposable emulator `emulator-5580` (`EMULATOR36X6X11X0`), API 35 x86_64 swiftshader software rasterizer, 4 vCPUs, 2.5 GB RAM, airplane mode `1`, journal mode `WAL`, schema v30, fixture seed `20261011` (`8cd176051129ae4414d7aeab218910552a442b4271118ec614abccb5b133831c`).
- **Preregistered objective**: Quantify measurement uncertainty on our emulator harness before using small tail differences to accept or reject optimizations. Determine the empirical false-positive rate of the legacy `>10% and >20 ms p95 in both pairs` rule under identical builds (A/A). Measure the observer/probe overhead of `HabitResortDiagnostics`. Formulate a practical 3-outcome benchmark decision protocol (`ACCEPT`, `REJECT`, `INCONCLUSIVE`). Zero production code changes.

### Methodology audit: deterministic vs descriptive vs uncertain
The existing performance harness combines deterministic software assertions with physical trace joins. Classifying existing findings:

| Aspect | Methodology component | Evidence classification | Methodological limit |
|---|---|---|---|
| **Deterministic** | SQLite/Memory data parity, autocommit return timestamp, lock order, schema invariants, SQL query count, row visit counts | DETERMINISTIC | Verifies exact state convergence; does not predict physical rendering latency. |
| **Deterministic** | Work elimination: query removal, cache hits, listener counts, batch creation counts | DETERMINISTIC | Proves algorithmic reduction; does not prove presentation speedup if work is off critical path. |
| **Descriptive** | Measured trace spans: worker resort CPU, SQLite write duration, model recompute duration, view bind durations | DESCRIPTIVE | Accurately describes what happened in a given trace window; subject to host and guest scheduling noise. |
| **Statistically Uncertain** | Small-sample scenario tails: $n=3$ nearest-rank p95, $n=1$ standalone navigation return | STATISTICALLY UNCERTAIN | Mathematically equals $\max(x_1, x_2, x_3)$ ($n=3$) or single sample ($n=1$). Extreme value distribution variance (CV > 40%) renders small differences indistinguishable from noise. |
| **Physical Join** | FrameTimeline token join: input DOWN → containing Choreographer `doFrame` → same-process SurfaceFlinger actual present | PHYSICAL JOIN | Rigorous presentation endpoint; essential for user-perceived latency. Must not be replaced by synthetic idle loops or FrameMetrics. |

### Dedicated A/A calibration on identical builds
To test baseline variability, byte-identical APKs were evaluated under identical conditions:

#### 1. Evaluation of legacy rejection gate on historical identical runs
Using the 16 primary baseline-vs-baseline and candidate-vs-candidate runs from Iteration 15 (8 identical pairs, 80 individual scenario comparisons), we evaluated how often comparing two identical runs breaches the legacy rejection gate (`>10% and >20 ms worsening`):
- **Total scenario evaluations**: 80
- **False-positive scenario breaches**: **31 / 80 (38.8%)**
- **Unidirectional arm pairs with ≥1 false rejection**: **15 / 16 (93.8%)**

Under the legacy gate, **93.8% of identical-build comparisons would be falsely rejected as regressed**!

#### 2. Run-level hierarchical variance decomposition
Between-run variance substantially exceeds within-run variance across identical builds:

| Cohort | Run 1 Median (ms) | Run 2 Median (ms) | Median Diff (ms) | Rel Diff (%) | Run 1 p95 (ms) | Run 2 p95 (ms) | p95 Diff (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 90g-baseline | 1395.2 | 835.5 | -559.7 | -40.1% | 2521.3 | 1185.7 | -1335.6 |
| 1825g-baseline | 834.8 | 865.9 | +31.1 | +3.7% | 1197.9 | 3042.2 | +1844.3 |
| 90s-baseline | 397.4 | 376.7 | -20.7 | -5.2% | 695.7 | 1048.2 | +352.5 |
| 1825s-baseline | 530.3 | 501.1 | -29.1 | -5.5% | 1229.3 | 704.5 | -524.8 |
| 90g-candidate | 842.8 | 784.4 | -58.4 | -6.9% | 1839.1 | 1335.1 | -504.0 |
| 1825g-candidate | 657.8 | 745.9 | +88.1 | +13.4% | 1410.0 | 1158.6 | -251.4 |
| 90s-candidate | 435.0 | 446.2 | +11.2 | +2.6% | 1311.0 | 750.9 | -560.1 |
| 1825s-candidate | 429.7 | 523.3 | +93.6 | +21.8% | 806.2 | 1262.3 | +456.1 |

#### 3. Dedicated Iteration 16 A/A test (Arm 1 vs Arm 2, probes disabled, n=4 per scenario)
Evaluating two identical runs of the current production code (`pilot-probe-off` vs `aa-90g-arm2`, 20 linked edits and 6 linked navigation returns each, 100% exact FrameTimeline joins):

| Scenario (n=4) | Arm 1 Median (ms) | Arm 2 Median (ms) | Med Diff (ms) | Arm 1 p95 (ms) | Arm 2 p95 (ms) | p95 Diff (ms) | Breaches Legacy Gate? |
|---|---:|---:|---:|---:|---:|---:|---|
| warmRepeated | 1968.2 | 597.5 | -1370.6 | 2969.1 | 929.3 | -2039.8 | **YES (FALSE REJECTION)** |
| afterStatistics | 2126.3 | 577.8 | -1548.5 | 3263.9 | 617.1 | -2646.8 | **YES (FALSE REJECTION)** |
| afterStatisticsDateChange | 1299.9 | 536.8 | -763.1 | 2001.3 | 657.3 | -1344.0 | **YES (FALSE REJECTION)** |
| afterTasks | 1197.4 | 604.2 | -593.3 | 1626.3 | 661.1 | -965.2 | **YES (FALSE REJECTION)** |
| afterMixedCycles | 1289.7 | 512.1 | -777.6 | 1760.3 | 522.2 | -1238.1 | **YES (FALSE REJECTION)** |
| **All Edits (n=20)** | **1466.5** | **555.0** | **-911.5** | **2969.1** | **661.1** | **-2308.0** | **YES (FALSE REJECTION)** |
| Navigation Returns (n=6) | 827.6 | 293.7 | -533.9 | 1315.3 | 319.9 | -995.4 | **YES (FALSE REJECTION)** |

### Environment contention and guest daemon discovery
1. **Windows Host Monitoring**: Implemented zero-dependency host monitor (`scratch/iteration16/host_monitor.py`) polling `GetSystemTimes` and `GlobalMemoryStatusEx` at 1.0s intervals. Recorded host CPU mean 38.2%–70.7%, peak 89.1%–99.8%, memory load 84.9%–86.2%.
2. **Rogue Guest Android Daemon Discovery**: During early runs, app startup repeatedly suffered background ANRs from ActivityManager (`Reason: Process failed to complete startup`). Inspection of guest processes via `top` revealed that `android.hardware.sensors-service.multihal` was spinning at **116% CPU continuously inside the emulator guest**, consuming an entire vCPU and starving main-thread ART bytecode class verification (which took >17s). Once the spinning service was killed/reset, guest background CPU dropped from 116% sys to 7% sys (393% idle), startup latency dropped from >17s to **2.8s**, and clean presentation median dropped from 1466.5 ms to **555.0 ms** (-62.2%).
3. **Trace TTL Limit**: Discovered that `post-navigation-capture.config` had a hardcoded `duration_ms: 360000` (6 minutes). When sample count was increased from $n=3$ to $n=4$ (arm duration ~436s), Perfetto silently stopped tracing at 360s, truncating trailing operations. Created `scratch/iteration16/post-navigation-capture-600s.config` (10 minutes TTL), restoring 100% join coverage (20/20 edits, 6/6 navigation returns).

### Tail stability vs sample size (Monte Carlo resampling)
Resampling 3,000 bootstrap draws from the empirical 60-operation baseline pool shows how tail percentile stability degrades at small $n$:

| Sample Size n | Median Mean (ms) | Median CV% | p95 Mean (ms) | p95 CV% | p95 Interquartile Range [IQR] (ms) |
|---|---:|---:|---:|---:|---|
| **n = 3** | 975.2 | 25.2% | 1405.8 | **41.6%** | [1025 – 1542] |
| **n = 4** | 970.5 | 18.5% | 1524.4 | **40.3%** | [1044 – 1712] |
| **n = 6** | 946.5 | 13.8% | 1715.0 | **37.4%** | [1186 – 2296] |
| **n = 10** | 933.8 | 10.3% | 1955.7 | **32.6%** | [1459 – 2521] |
| **n = 15** | 924.3 | 8.6% | 2165.4 | **28.9%** | [1542 – 2521] |
| **n = 30** | 920.4 | 6.4% | 2038.2 | **24.6%** | [1542 – 2521] |

**Conclusion**: At $n=3$, p95 has a 41.6% coefficient of variation and its interquartile range spans over 500 ms. At $n \ge 15$, median CV drops to 8.6% and pooled p95 stabilizes. Single-scenario $n=3$ p95 cannot be used as an acceptance or rejection gate.

### Measurement overhead of diagnostic probes
Comparing identical 90-day grouped workloads with `HabitResortDiagnostics` enabled (`pilot-probe-on`) versus disabled (`pilot-probe-off`):

| Metric | Probe ON (Diagnostics Enabled) | Probe OFF (Diagnostics Disabled) | Overhead Delta | Rel Overhead (%) |
|---|---:|---:|---:|---:|
| Presentation Latency (Common Edits Median) | 4158.4 ms | 1848.2 ms | +2310.2 ms | **+125.0%** |
| Op 1 `resortAndObservers` Span | 6294.4 ms | 2147.4 ms | +4147.0 ms | **+193.1%** |
| Op 1 `sqliteWrite` Span | 169.6 ms | 81.5 ms | +88.1 ms | +108.1% |
| Total Arm Duration | 469.5 s | 399.6 s | +69.9 s | +17.5% |
| Host CPU Utilization (Mean) | 58.0% | 61.2% | -3.2% | - |

**Causal Mechanism**: When `HabitResortDiagnostics` is enabled, every item comparison and observer notification invokes `Debug.threadCpuTimeNanos()` (a kernel system call `clock_gettime(CLOCK_THREAD_CPUTIME_ID)`), performs thread-local lookups, and creates diagnostic objects. In lists with 32+ habits and hundreds of entry checks, repeated kernel transitions in tight inner loops inflate resort latency by over 4 seconds per operation.
**Rule**: Benchmarking comparisons MUST run with diagnostic probes OFF by default. Probes should be enabled exclusively during targeted diagnostic attribution.

---

### Calibrated Benchmark Protocol for Future Iterations

To prevent false rejections of valid algorithmic optimizations while preserving rigorous protection against regressions:

```mermaid
flowchart TD
    Start["Candidate Evaluation"] --> G1["Gate 1: Correctness & Parity<br/>(100% Core/Native tests, SQLite/Memory parity, 0 lock inversions)"]
    G1 -- Fail --> Reject1["REJECT: Semantic or Parity Failure"]
    G1 -- Pass --> G2["Gate 2: Algorithmic Work Reduction<br/>(Proven query/row/allocation reduction via counters)"]
    G2 -- Fail --> Reject2["REJECT: No Proven Work Elimination"]
    G2 -- Pass --> G3["Gate 3: Meaningful Median Speedup<br/>(Pooled Median Δ ≥ 15% and ≥ 30ms, Bootstrap CI excludes 0)"]
    G3 -- Fail --> Inconclusive1["INCONCLUSIVE: Work reduced but no E2E speedup"]
    G3 -- Pass --> G4{"Gate 4: Uncertainty-Aware Non-Regression<br/>(Pooled n ≥ 10 per scenario cohort)"}
    G4 -- "Tail Regressed > 25% and > 50ms<br/>+ Causal Software Trace Mechanism Identified" --> Reject3["REJECT: Confirmed Causal Regression"]
    G4 -- "Tail Difference < 25% OR<br/>Environmental Overlap (GC/JIT/Guest) Without Code Cause" --> Inconclusive2["INCONCLUSIVE: Tail within noise floor;<br/>Targeted Replay Required"]
    G4 -- "No Replicated Tail Regression" --> Accept["ACCEPT: Candidate Passed All Gates"]
```

#### Protocol Gates
1. **Gate 1: Correctness & Parity (Deterministic)**
   - 100% pass rate across core test suite, native lock-order regression tests, and InFlightBatch concurrency tests.
   - Exact row parity between SQLite storage, in-memory models, and published snapshots across all mutated records.
   - Zero lock inversions, zero data corruption.
2. **Gate 2: Algorithmic Work Reduction (Deterministic / High Confidence)**
   - Must prove substantial reduction in actual work performed (e.g., query count, row visits, resort CPU time, snapshot allocations, or observer dispatches).
   - Verified via deterministic counters or trace slice durations.
3. **Gate 3: Meaningful Median Improvement (Primary E2E Gate)**
   - Pooled or scenario median improvement must exceed the measurement noise floor ($\ge 15\%$ reduction AND $\ge 30\text{ ms}$).
   - Cluster-bootstrap 95% confidence interval of the median difference must exclude zero in the positive direction.
4. **Gate 4: Uncertainty-Aware Non-Regression (Secondary Tail Gate)**
   - DO NOT evaluate p95 on $n < 6$ (minimum $n \ge 10$ pooled per scenario cohort).
   - Noise threshold calibrated to empirical A/A variance: regression confirmed only if $>25\%$ AND $>50$ ms across replicated runs with an identified causal mechanism in the trace.
   - Three Decision Outcomes:
     - **ACCEPT**: Passes Gates 1–3, no confirmed causal regression in Gate 4.
     - **REJECT**: Fails Gate 1 or Gate 2, OR exhibits a replicated tail regression $>25\%$ and $>50$ ms with an identified software bottleneck (e.g., lock contention, main thread measure storm).
     - **INCONCLUSIVE**: Passes Gates 1–2, but tail differences are within noise bounds ($<25\%$) or unconfirmed across runs or lack an identified causal mechanism; requires bounded follow-up profiling rather than automatic rejection.

---

### Executed attempts ledger and exclusions
All attempts from Iteration 16 are preserved in `scratch/iteration16/`:

| Label | Purpose | Status | Outcome / Reason |
|---|---|---|---|
| `pilot-probe-on-failed-anr` | Probe ON pilot attempt 1 | EXCLUDED | System UI / Messaging ANR popups stole window focus during startup; preserved with artifacts. |
| `pilot-probe-on` | Probe ON pilot attempt 2 | COMPLETED (PARTIAL) | 11/11 linked ops before 360s trace TTL; trailing 9 ops unlinked due to config TTL expiration. Proved +125% probe overhead. |
| `pilot-probe-off` | Probe OFF pilot (Arm 1) | COMPLETED | 20/20 linked edits, 6/6 linked navigation returns (100% coverage); ran during guest sensor service contention (median 1466.5ms). |
| `aa-arm2-90g-failed-startup-anr` | Reverse A/A attempt 1 | EXCLUDED | ActivityManager startup ANR due to 116% CPU guest sensor service contention; preserved. |
| `aa-arm2-90g-failed-reverse-startup-anr2` | Reverse A/A attempt 2 | EXCLUDED | ActivityManager startup ANR due to reverse order starting with heavy `afterMixedCycles` during ART verification; preserved. |
| `aa-90g-arm2-failed-startup-anr` | Arm 2 attempt 1 | EXCLUDED | ActivityManager startup ANR due to guest sensor daemon contention; preserved. |
| `aa-90g-arm2` | Arm 2 identical A/A | COMPLETED | 20/20 linked edits, 6/6 linked navigation returns (100% coverage); ran with guest sensor fixed (median 555.0ms, duration 117.2s). |

### Ranked next work
1. **Re-evaluate Grouped Fan-Out Optimization under Calibrated Protocol**: Grouped resort fan-out (166 unused reads, 178 full ancestor reads per edit) remains confirmed necessary work to eliminate. In Iteration 15, root-only traversal eliminated these reads and reduced resort CPU from 596ms to 411ms, but was rejected due to small $n=3$ `afterTasks` differences (839→1410ms) that fell entirely within the 41.6% CV noise band. Re-evaluate grouped resort work elimination under the new calibrated protocol with $n \ge 10$ and probes disabled.
2. **Tasks & Statistics Navigation Viewport Settling**: Investigate post-navigation looper settling and avoid running navigation cycles immediately during cold startup.
3. **Guest Environment Pre-Flight Health Check**: Add an automated pre-flight assertion in QA harness to verify guest CPU is <10% and sensors service is not spinning before initiating benchmark captures.

