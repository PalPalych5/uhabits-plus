# UHabit Next — PR20 Production Readiness and Cutover Audit

Audit date: 2026-10-07, Asia/Novosibirsk. Source baseline: `dev`, PR19 `1cda48931` (verified Git HEAD before this documentation change). Status: **AUDIT COMPLETE / PRODUCTION CUTOVER NO-GO**. This document designs subsequent work; it does not authorize or implement migration, runtime activation, a sync protocol, promotion, recurrence or polish.

Current follow-up (2026-10-08): PR21 and protected-staging PR22/PR22.1 are DONE and accepted; actual baseline `e24dc13eb`. Allocated schema is v30; canonical opener remains v29 with LEGACY authority. The original PR20 findings below remain a historical baseline, not current claims that PR21/22 are missing. PR23 was authorized and audited, then stopped at its mandatory section-30 split checkpoint; see [effective read authority inventory and concrete PR23A/PR23B proposal](UHABIT_EFFECTIVE_READ_AUTHORITY.md). No reader code is integrated yet. Runtime/release NO-GO remains; both proposed reader halves must complete before activation. PR24 backup/device/accessibility and PR25 authority switch remain separate, unstarted stages.

## Decision and evidence boundary

| Question | Answer today |
|---|---|
| Production schema cutover ready? | No. Experimental DDL and conversion are substantial foundations, but production transaction composition, recovery, validation and metadata gates are missing. |
| Production runtime activation ready? | No. Exclusive timer authority and contribution-aware Habit/report readers are blocking. |
| Safe with current sync? | No for Next production authority. Keep legacy sync only on legacy data. A separately gated local-only release is possible after other blockers; cloud release requires a new protocol. |
| Experimental dataset promotion safe now? | No supported end-to-end workflow. Current Android restore explicitly rejects it. Preserve and export the experiment. |
| Production + experiment merge supported? | No. Existing legacy Habit import is not a graph/history/fact merge. |
| Exact next implementation stage | PR21: preflight, coherent recovery bundle, diagnostics and startup recovery contract, while retaining production v29 and disabling Next production activation. |

Human PR19 decision: implementation DONE; evaluation ACCEPTED; permanent fourth Today workspace REJECTED; secondary **Day Overview / Обзор дня** ACCEPTED DIRECTION. Global phone navigation **Habits | Tasks | Statistics** is FROZEN. `Tasks → Today / Сегодня` remains task-only. No PR20 UI rename or redesign is needed. Visual polish was not independently reviewed here.

The previous 794 aggregate is withdrawn: recursively discovering XML in the repository does not prove that those tests ran in PR19. The source currently contains **6** `TodayEvaluationServiceTest` methods and **3** `TodayPresentationTest` methods, despite the old 5-core claim. Historical PR19 regression/build/emulator reports are retained as historical evidence, not PR20 execution. Fresh selected test results are recorded in section 15. No production DB, emulator fixture or live Supabase was opened or changed by this audit.

Sources below use `C/` = `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/`, `A/` = `uhabits-android/src/main/java/org/isoron/uhabits/`, and `B/` = `C/containers/backup/`. Anchors identify inspected symbols/line ranges at the baseline; implementation must recheck them. Findings are code observations; future policies are explicitly proposals.

## 1. Current production architecture

- `C/Constants.kt:23`: `DATABASE_VERSION = 29`. `A/HabitsDatabaseOpener.kt:35–72` uses SQLiteOpenHelper, starts creation at v8, loads ordered numbered SQL assets and rejects downgrade. `platform/io/Database.kt:113–121` updates `user_version` after each script; it does not itself own a transaction. Android helper lifecycle supplies transaction ownership; standalone callers need their own.
- Production assets through `29.sql` own Habits, Repetitions, HabitExtensions, HabitBlocks, HabitGoals, AppSettings, SyncQueue and EntryOps. V29 adds stable identities, update/delete metadata and legacy synchronization structures. No numbered migration installs the complete Next schema.
- `DatasetSessionFactory` currently has two branches: legacy production uses `uhabits.db`, default preferences, LEGACY organization authority and enabled production capabilities; experiment uses a separate file/UUID/preferences, CONTAINER_LOCAL authority, disabled sync and other capability gates.
- Legacy timer writes numerical credit via `AddNumericalEntryOpCommand`; Repetitions remain the historical/manual quantity authority. Global Habit readers recompute from these entries.
- Version 29 alone does **not** prove the physical schema contains no Next tables. `A/inject/HabitsApplicationComponent.kt:297–299` installs `UserTaskSchema` unconditionally if its provider is resolved. UI entry is gated, but the provider lacks the gate. This is a static gate gap, not an observed production leak. Preflight must inspect `sqlite_master` and classify unexpected/nonempty tables rather than assume a pristine version number.

## 2. Complete experimental delta inventory

All rows below are currently unsynced by the Next domain; legacy shared Habit/Entry sync does not provide their coverage. Current full experimental backup copies the durable stores listed, with deliberately excluded active runtime. Initial production migration must install complete versioned DDL and retain old data; rollback after new writes means restoring the pre-cutover bundle, not dropping tables.

| Capability / owner | Dependencies and current activation | Backup / restore coverage and gaps | Production requirement / rollback |
|---|---|---|---|
| OrganizationState | Singleton dataset UUID, foundation/mode/cutover/source SHA/current revision; mode CHECK only STAGED/CONTAINER_LOCAL. OrganizationSchema and container-authority facade. | Copied; UUID/mode routing, basic structure validated. | Explicit production metadata/mode contract; DB marker authoritative across preference crashes. Restore matching identity. |
| OrganizationChanges | Revision PK, unique operation UUID, recorded time/type/origin/payload; current/history rows reference it. | Copied; no full manifest content hash or exact revision/current-history parity validator. | Baseline + atomic command/history updates; local revision is not distributed ordering. Preserve history in rollback/export. |
| Containers + ContainerHistory | Local integer keys, stable UUID, parent RESTRICT FK, archive/tombstone/order/color/icon, revision; historical versions keyed container/revision. | Both copied; parents/cycles/counts checked, full order/history parity incomplete. | PR3 root conversion; preserve identities/tombstones; no destructive block replacement. |
| HabitPlacements + HabitPlacementHistory | Habit local FK, optional Container local FK, separate order/origin/revision; one placement per Habit. | Copied; cardinality/history lower bounds/FK checks; complete parity incomplete. | One organization authority; no dual-write of block_id. Never cascade history away. |
| LegacyBlockMap | One-to-one legacy block local ID/UUID → Container local ID, RESTRICT FKs. | Copied with original blocks. | Preserve mapping/provenance; retain legacy tables while map references them. |
| HabitVisualIdentities | Habit UUID → VECTOR/EMOJI/value, no SQL Habit FK. Container facade store. | Copied; orphan Habit rejected; no full content hash. | Versioned schema and semantic references; sync revisions/tombstones later. |
| UserTasks / subtasks | Text stable ID, container/parent text references without SQL FK; title/description/status/planned date/deadline/completion/order/times. Provider gate caveat above. | Copied; parent existence/cycles checked; container reference validation missing. Physical delete, no tombstone/revision. | Version DDL, validate graph, define deletion/version contract before sync. No Task completion → Habit/fact inference. |
| FocusSessions | Stable text ID, immutable revision 1, checked start/end/active duration, nullable historical Task/Habit/Container targets, source/device/primary target/frozen organization context. No cascading FKs. | V2+ copies finalized facts and hashes; old experimental schema additions inferred by schema adapter, not from Repetitions. | Empty new production facts initially; preserve frozen/stale historical refs under explicit policy; no fabricated old facts. |
| FocusCaptureJournal | Singleton timer snapshot, captured target/context and immutable segment identities; SQLite capture store. | Intentionally omitted/cleared on backup restore. Native process recovery uses journal + namespaced timer prefs. | Production exclusive authority, epoch and crash recovery; recovery bundle distinct from portable backup. |
| Contributions | Stable ID, Habit UUID, source type/ID, value/effective time/update/removal, optional SourceRule ID; unique source/type/target key. No SQL FKs. | V3+ copies active/tombstoned rows; V4 hash includes rule ID. Restore validates target Habit, not full source/rule consistency. | Empty initially; idempotency and explicit corrections; never materialize credit into manual Repetitions. |
| SourceRules / interval history | Retained rule definitions with explicit interval-closing tombstones in SourceRules, source Container/target Habit, include-descendants/mapping/enabled/effective times. Partial unique active scope. | V4 copies/hashes; target Habit/source Container checked. No separate SourceRuleHistory table. | Install final DDL, preserve disabled historical intervals; edits use replacement IDs, removal updates removed_at/updated_at; do not revoke past credit on disable. |
| EffectiveHabitValue projection | No durable table. Original entries + HabitGoal history + active Contributions; rule/fact context determines attribution. Opt-in readers. | Input stores backed up; projection reconstructed; unit/calendar/full reader parity unproved. | Integrate all production readers, invalidation and conservative coverage semantics before activation. |
| BackupManifest | Portable metadata, format 1–4 independent of SQLite version. | Recovery metadata excludes secrets and timer prefs; embedded preferences not fully reapplied by Android restore. | New explicit production format with complete durable inventory and tested preference recovery; preserve old adapters. |
| Dataset/session preferences | Selected mode/file/UUID/foundation/source metadata in DatasetSessionManager; experimental namespace and `_timer`; critical selection uses commit before restart. | Nonsecret subset included; selector/runtime/credentials not a portable full recovery image. | DB-backed authority epoch plus restart protocol; retain callback qualification. Do not select new authority before DB commit. |

Actual DDL: `C/containers/sqlite/OrganizationSchema.kt:31–132`; `C/userTasks/sqlite/UserTaskSchema.kt:28–47`; `C/focus/sqlite/FocusSessionSchema.kt:33–62`; `C/focus/sqlite/SQLiteFocusCaptureStore.kt:42–53`; `C/contribution/sqlite/ContributionSchema.kt:34–63`; `C/rules/sqlite/SourceRuleSchema.kt:39–63`. DI: `A/inject/HabitsApplicationComponent.kt:131–170,255–299,338–379`; `A/focus/FocusCreditController.kt:49–51`. Some schemas lazily adapt older experimental tables; that is not a production migration ledger.

Explicit auxiliary indexes: `idx_containers_tree`, `idx_containers_active`, `idx_habit_placements_contents`, `idx_container_history_lookup`, `idx_habit_placement_history_lookup`, `idx_org_changes_recorded`; `idx_user_tasks_container/parent/status/planned/order`; `idx_focus_sessions_task/started/ended`; `idx_contributions_habit/source/rule`; `idx_source_rules_active_scope` (partial UNIQUE), `idx_source_rules_target/container/active`, plus PK/UNIQUE implicit indexes. Preflight inventories actual SQL/index definitions, not only names. There is no separate durable Today or ContainerStatistics table.

## 3. Production schema migration design

**Proposal: one atomic v29 → next unused production schema version.** V30 is a candidate only if still unallocated when implemented. Experimental PR numbers/backup versions are not shipped SQLite schema versions; do not create one production version per PR. Small implementation PRs and separate activation milestones do not require partially committed production schemas.

1. Before ordinary DI/open-triggered upgrade: inventory version/schema, quiesce writers/sync/services, resolve legacy active timer, create and verify complete recovery bundle.
2. Plan from a coherent v29 raw snapshot; confirm fingerprint unchanged. Older supported DBs normalize on staging using existing migrations; retain the normalized identity baseline for stable retries.
3. Outer production transaction installs organization/Task DDL, PR3-equivalent baseline and unchanged-legacy validation, then final Focus/Contribution/SourceRule/journal DDL/indexes and explicit production dataset/cutover metadata. New fact/rule stores begin empty. No Repetitions/goal/queue rewrite.
4. Check integrity, actual enabled FKs, all references/history/order/identity and old-table parity before committing complete state + schema version. Migration failure leaves v29 intact.
5. After DB commit, reconcile preferences/session/epoch idempotently from the DB marker, reopen and verify; create post-migration backup before activation. New-schema staging alone must not enable Next runtime.

Composition blockers: `LegacyContainerMigrationExecutor.execute` owns BEGIN IMMEDIATE/COMMIT (`:38–60,232`) and cannot be called unchanged inside SQLiteOpenHelper's transaction. Enable/check FK before the outer transaction (e.g. explicit onConfigure hook), not via PRAGMA inside BEGIN. `LegacyContainerMigrationValidator` permits organization/UserTasks/manifest but lacks Focus/Contribution/Rules in its allowlist: run legacy parity phase before later tables or deliberately extend it. The raw `migrateTo` helper is not independently crash-atomic. These require adapter/transaction tests, not copying PR3 code into an asset.

## 4. Legacy organization and numerical data conversion

Reuse `LegacyContainerMigrationPlanner` semantics: every existing block, including archived/tombstoned, becomes one root Container with its literal stable UUID and metadata. Dense root order derives from position/local-ID tie-break; createdAt stays unknown when not stored. LegacyBlockMap preserves local/portable identities. Each Habit gets exactly one placement; per-context order derives from Habit.position/local-ID without changing global position.

| Input | Required behavior |
|---|---|
| Extension exists, block_id null | Known unassigned, MIGRATION_SNAPSHOT. |
| Missing extension | Null placement, UNKNOWN_LEGACY; do not guess a default block. |
| Valid referenced block | Exact mapped Container, preserve identity. |
| Tombstoned Habit referencing tombstoned block | Retain historical reference where valid. |
| Active Habit in tombstoned block / dangling reference | Block conversion and surface diagnostic; separately approved repair with preserved original provenance. |
| Missing/blank/duplicate UUID | Block; never match by name/color or generate replacement silently. |
| Unsupported malformed identity | Validate against repository's opaque identity contract; do not require RFC UUID syntax blindly. Diagnose unusable/colliding values. |
| Orphan extension/goal/Repetition/EntryOp or duplicate legacy row | Explicit raw preflight queries; PR3's associateBy/iteration does not diagnose every orphan. Block unsafe anomalies. |

Baseline organization revision is 1 at the actual conversion timestamp. Before it organization is UnknownBeforeCutover. Do not date it to first historical Entry. `RawLegacyInventoryReader` and PR3 validator compare UUIDs, IDs, values/notes, goals, flags, order, extensions, pending EntryOps/SyncQueue and settings. Avoid `habitList.update()`/editor/importer in migration because they may rewrite extensions/goals.

**Existing production Repetitions remain canonical legacy/manual quantity forever.** They may include old timer credit; it cannot be disentangled reliably. No historical FocusSession fabrication, subtraction or heuristic deduplication. After runtime cutover, new work creates FocusSession → SourceRule → Contribution → EffectiveHabitValue, with zero legacy credit writes. Store schema completion and runtime-authority activation separately if gated release stages need different dates: organization baseline reflects schema conversion; exact runtime boundary records UTC epoch ms, dataset UUID/authority epoch and pre-cutover recovery identity. Both are durable and immutable; no ambiguous preference-only timestamp.

## 5. Timer cutover policy

Current controller/capture wiring is experimental-only; production passes null captureStore. `TimerSessionManager.saveElapsed:533–555` returns for `isExperimental`, otherwise writes AddNumericalEntryOpCommand. Adding capture to a nonexperimental production session would permit both pipelines. **Exclusive runtime authority is blocking**, independent of schema readiness.

Upgrade must detect legacy timer state before opening/upgrading through normal DI or handling completion callbacks: running, paused, accumulated stopwatch, focus/break Pomodoro, overtime/completion-pending all count. Block conversion until the user resumes/finishes/cancels through the legacy engine. Finish keeps old Repetition credit; cancellation is explicit. Never carry elapsed legacy work into fabricated Focus facts. Quiesce services, alarms, notification actions and sync; commit inactive legacy state synchronously and verify it before transition.

Then persist a DB authority epoch and resolve preference/notification cleanup with restart-safe states. `datasetId=production` alone is insufficient: old unqualified production callbacks currently remain accepted (`TimerSessionManager:182–183`). Invalidate old PendingIntents and tag future callbacks by authority epoch as well as dataset. First post-cutover session starts with a fresh stable capture identity.

Reuse existing Habit timer, Task launcher, Stopwatch/Pomodoro UI and notification infrastructure. Pauses/breaks yield no work; reset/cancel discards the active segment. Pomodoro base/overtime and cycles may produce multiple legitimate immutable segments; exactly-once means one fact per segment, not one fact per entire run. Capture commits journal/facts atomically; attribution follows commit and retries in 50-fact pages on reopen. Journal is process-recovery authority; portable restore discards it. Stale callbacks cannot write after disposal. Verify Habit/Task launch, alarms/actions, pause/break/overtime, process death, backward clock, attribution failure and permissions on the real production authority seam.

## 6. Habit projection readiness

`Habit.recompute:196–220` uses originalEntries → computedEntries → scores/streaks; the PR15 service does not write these caches. Current effective readers are current TargetCard, experimental Today and Container statistics. This narrow integration cannot be activated as a consistent production Habit experience.

| Surface / inspected seam | Classification before new production credit | Required work |
|---|---|---|
| List day cells/current quantity/score (`HabitCardListCache:324–327`) | BLOCKING CUTOVER | Effective read seam + refresh on insert/correction/tombstone; manual input stays manual. |
| Completion filters/sorting (`HabitMatcher:40–41`, Habit.isCompletedToday, MemoryHabitList:167,181) | BLOCKING CUTOVER | Consistent effective completion/score semantics; no hiding a credited Habit as incomplete. |
| Score/streak/overview (`ScoreList`, `StreakList`, ScoreCard/StreakCart/OverviewCard) | BLOCKING CUTOVER | Define conservative numerical coverage/calendar/AT_MOST behavior; preserve old no-credit behavior. |
| History/quantity/frequency (`HistoryCard:94–164`, BarCard, FrequencyCard) | BLOCKING CUTOVER | Combined results and provenance; discover contribution-only date ranges. |
| Current TargetCard | Partially integrated | Shared calendar/preferences, errors, invalidation and parity with other readers still required. |
| Widgets and notification suppression | BLOCKING if enabled | Checkmark/Target/Score/Streak/History/Frequency and NotificationTray use old readers. Explicitly disabling an unsupported surface can be a gated limitation; leaving misleading values cannot. |
| Manual history editor | SAFE DEFERRED only as explicit manual input | Distinguish manual value from total/provenance; do not let effective total overwrite manual history. |
| CSV | BLOCKING if represented as full Habit results | Current computed checkmarks/scores ignore credit. May defer combined export only with explicit manual-only semantics and lossless full backup. |
| New Focus timeline, recurrence, secondary Overview polish | SAFE DEFERRED LIMITATION | Does not excuse inconsistency in existing readers. |

Shared requirements: UNKNOWN + credit remains a lower bound; SKIP preserves source facts but excludes goal quantity; explicit zero differs from unknown; preserve Boolean behavior and AT_LEAST/AT_MOST, day/week/month and historical goals. Never materialize Contributions into Repetitions as a shortcut. Source correction must update all consumers without corrupting manual input. Projection unit integrity errors must be surfaced rather than silently presenting plausible totals.

## 7. Statistics readiness

Global `StatisticsOverview:152`, `StatisticsReportStateBuilder:496,534,667,848`, and `Habit.statisticsEntries:185` use computedEntries/scores or derive first dates from originalEntries. Goal percentages, trends, calendar, week rhythm, sphere balance/stability and insights omit automatic credit. **BLOCKING runtime activation**: contribute-aware day/range/completion/score/date-domain inputs and invalidation, respecting statistics cutoffs. Keep report UI/design; fix reader authority.

PR18 Container statistics has effective Habit values and immutable historical Focus paths; Task outcomes intentionally use current placement. It does not fix global Statistics. Existing defect: DI constructs `EffectiveHabitValueService(contribRepo)` with defaults and ContainerStatisticsService without live midnight delay/firstWeekday (`A/inject/HabitsApplicationComponent:144–154`; service defaults Monday/0 at `:58–59`). Detail/Today pass live preferences; reports may disagree at day/week boundaries. Fix later in the shared calendar/projection stage, not PR20. Historical Entry-to-Container attribution remains unknown before baseline; do not invent it.

## 8. Backup, restore and rollback

Current experimental v4 copies known legacy + organization/history/map/visual/Task/fact/ledger/rule tables. It excludes FocusCaptureJournal and timer prefs deliberately. Manifest hashes legacy inventory and finalized Focus/Contribution/Rule inventories; it does **not** hash complete organization/Task/visual/history content. Structurally valid edits can escape those checks. Embedded nonsecret preferences are sanitized; current Android restore does not establish full preference reapplication. Source copy is not shown under one coherent source read transaction; destination transaction alone cannot prove source consistency.

Restore semantic checks validate Container cycles/parents, placement count, history minimum counts, visual Habit refs, Task parent/cycles, Contribution Habit and SourceRule Habit/Container refs. Missing production checks include dense order/current-history equality/revision continuity, Task Container refs, full provenance/source-rule/fact consistency and projection eligibility. Historical stale target references can be legitimate; validator must distinguish documented historical references from broken live dependencies.

Mandatory release recovery protocol:

1. Quiesce writers and resolve active timer; obtain full v29 DB plus relevant preferences/authority/sync metadata without exporting auth secrets. Validate full integrity_check, foreign_key_check and semantic/raw inventory parity. CSV is not recovery.
2. Persist recoverable preparation state, snapshot identities/hashes and retain immutable original. Migration transaction leaves all-old or complete new DB.
3. Validate replacement before any normal runtime jobs; preserve DB-backed schema/authority markers. Build post-migration full backup with a new explicit production format covering every durable store and tested preferences.
4. Reconcile crash states at startup **before** normal DB opening/DI. Missing canonical file, old+rollback, new+rollback, unactivated committed schema and preference mismatch need deterministic recovery, not silent empty DB creation.
5. Inject failure before/after DB commit, file moves, prefs commit, activation, post-backup and first restart. Verify unchanged old data and retry-stable identity. Keep recovery snapshot until validated restart and accessible rollback.

`DatabaseUtils.restoreDatabaseFromBackup:248–342` uses two individually atomic moves (canonical→rollback, temp→canonical) and exception restoration. Process death bypasses catch; rollback reconciliation currently runs inside restore, not a complete startup state machine. That is not proof of crash-safe production cutover. `ContainerRestoreService.restoreLegacyBackup` defaults absent cutover timestamp to 1000000L: production must supply real durable boundary metadata.

Old APK downgrade-open is unsafe and `onDowngrade` throws for newer version. Rollback means **restore verified v29 bundle + corresponding legacy authority preferences**, invalidate new callbacks/alarms and keep new data exported separately. It loses post-cutover changes from the restored canonical view; no lossless reverse conversion is promised. A local restore does not undo remote publication; cloud rollback needs epoch policy before sync activation.

## 9. Dataset promotion, merge and retirement

| Source policy | Current support / recommendation |
|---|---|
| A. Production v29 → migrated canonical production | Safest initial release source, after blockers. Preserve original data; retain experiment separately. |
| B. Experimental → canonical replacement | Not ready. Android DatabaseUtils rejects experimental replacing production and requires same experimental UUID. Needs separate explicit whole-dataset promotion, backup of both stores, accounting for production edits since fork, full validation, preserved IDs/history/preferences, resolved journal, authority/account/cloud epoch transition and tested rollback. |
| C. Production + experimental merge | Unsupported. LegacyHabitMergeImporter rejects Container datasets. Shared ancestor UUIDs do not resolve divergent histories, facts/rules/tree and manual edits. No silent merge/remap/LWW. |

Initial release should migrate selected production only, preserve existing experiment unchanged and exportable, and clearly state its separate status. Promotion is deferred as a bounded opt-in replacement stage; full merge remains excluded until an independent reconciliation design. This policy preserves experimental work rather than discarding it.

Retirement stages: retain DatasetSession identity/callback validation/capability gates; add explicit Next production authority and replace `isExperimental` product assumptions with appropriate capabilities. Keep experiment creation/switch/export as developer/staging infrastructure while promotion is unsupported. Ordinary release activation eventually stops creating parallel databases. Preserve old experiment recovery/export before retiring its UI. Remove legacy authority paths only after supported upgrades/backups no longer need them. Remove nothing in PR20. Current startup experiment validation (`DatasetSessionManager:319–369`) checks quick_check, a small organization table set and UUID/mode; it is not production release validation.

## 10. Sync/Supabase gap analysis

Repository SQL `docs/supabase-sync.sql:8–125` and `SupabaseSyncBackend:55–124` carry user/device/op/entity/payload/client time and server log cursor. Known entities are habit_block, habit, habit_goal, entry, entry_op, app_setting. No dataset epoch/protocol handshake/minimum client capability exists. Deployed SQL/RLS was **not** inspected live.

Containers/history/organization changes/map/placements, Tasks/subtasks, FocusSessions, Contributions/corrections, SourceRules/intervals and visual identity are entirely unsynced. Legacy sync may preserve old shared Habit rows while omitting their new meaning. `SyncCoordinator.applyRemoteEvents:1263–1290` acknowledges unknown types (`else → true`), and successful pull advances cursor. New events must not enter this log for old clients. Legacy block repair/bootstrap/queue capture cannot remain on Next authority.

Proposal for new protocol:

- Explicit user + dataset UUID + authority epoch + protocol/payload version + op UUID/device + server sequence. Server rejects old clients on every old read/write route before publishing Next data; handshake and retained cursor on unknown/dependency failures. UI hiding is insufficient.
- Verified complete baseline upload with resumable checkpoint/dependencies; stable portable UUIDs, local numeric keys remapped, old outbox/cursors isolated. Preserve LegacyBlockMap as provenance; do not replay old assignments as current placements.
- Organization commands use operation identity/base revision and server sequencing or explicit conflict/rebase. Apply grouped moves/reorders with cycle/dependency/order checks atomically. Frozen context revision mapping survives replay. No blanket parent-field LWW.
- Tasks need revisions and deletion tombstones/events (current repository physically deletes); define concurrent parent/delete/complete conflicts. Validate subtask cycles.
- Focus facts are immutable idempotent exact-ID records; conflicting payload rejected. Journal remains local. Contributions synchronize provenance keys and explicit correction tombstones; no summed duplicates or projected Repetition writes. SourceRule definitions and interval-closing correction events retain auditable history; local removal updates tombstone fields, and disable never erases old credit. Define deterministic attribution authority across devices.
- Visuals require Habit identity + version/tombstone/conflict contract. Sync only declared settings; exclude secrets/caches/runtime.
- Test offline concurrent structural changes, dependency order, tombstone replay/resurrection, restore/bootstrap/account change, interrupted upload/pagination, clock skew and old-client refusal; verify actual server deployment/RLS before release.

A **local-only** release does not inherently require implementing this protocol first, but requires tested production gates disabling remote calls **and** legacy capture/bootstrap/repair, explicit account/cloud behavior and full local backup. Existing cloud data remains isolated; do not replay it later without epoch adoption. A cloud-enabled release requires new protocol before activation. Current enabled legacy sync is not a safe hybrid.

## 11. Backup compatibility matrix

| Source | Current legacy destination | Current experimental destination | Future Next production policy |
|---|---|---|---|
| Supported raw legacy SQLite ≤29 | Validated, older supported versions migrate to29 | Core PR3 conversion; Android route has identity restrictions | Explicit normalize-on-staging adapter, then new production migration. |
| Raw pre-Focus experimental, no manifest | Rejected | Android synthesizes v1 compatibility manifest only for supported fact-free structure | Preserve explicit adapter/provenance, never pretend current v4. |
| Experimental v1 | Rejected | Fact-free accepted; Focus present rejected | Preserve reader; missing facts remain missing. |
| v2 | Rejected | Finalized Focus + checksum; later ledger/rules rejected | Preserve exact v2 semantics on staging. |
| v3 | Rejected | Focus + Contribution hash without rule ID; SourceRules rejected | Preserve exact historical checksum formula. |
| v4 | Rejected | Known schema/foundation + fact/ledger/rule hashes, same active dataset UUID | New production format/routing and separate promotion policy required. |
| SQLite >29 / manifest >4 / foundation >1 | Rejected by current validation/router | Rejected | Future app must explicitly support; no silent reinterpretation. |
| Future production backup → old APK | Unsupported | Unsupported | Reject. Restore retained v29 backup with compatible app instead. |

SQLite schema version, backup format, foundation version and runtime authority epoch are distinct axes. New production backup must identify its own mode/epoch and complete durable schema; do not mutate the meaning of experimental v4. Portable backup discards active runtime; migration recovery bundle preserves safe resolution state under an explicit local recovery contract.

## 12. Release blocker and evidence matrix

These are release gates, not reasons to reject completed experimental PRs. Schema-only staging, runtime/local release and cloud release have different gates.

| Gap | Gate | Completion evidence |
|---|---|---|
| Coherent preflight/backup + restart recovery | BLOCKING schema cutover | Full inventory, anomalies, copy/parity, failure injection and restart recovery. |
| Production metadata/version/transaction composition/FK + complete validator | BLOCKING schema cutover | Android + file SQLite atomicity, no legacy changes, retry-stable UUID, downgrade rejection. |
| Unconditional Task schema provider | BLOCKING safe gate contract | Version-owned DDL, lazy provider cannot mutate legacy production, physical schema classification. |
| Active legacy timer + exclusive runtime epoch | BLOCKING schema transition/runtime | Pre-DI guard, safe legacy resolution, stale action rejection, zero dual credit. |
| All Habit/global Statistics readers and calendar context | BLOCKING runtime | Cross-surface fixtures/manual-vs-effective parity and invalidation. |
| Complete production backup/routing/preferences recovery | BLOCKING release | New format + old adapters, roundtrip, tamper/concurrency/crash cases. |
| Legacy sync isolation / explicit local-only contract | BLOCKING local runtime release | Zero remote/bootstrap/repair/outbox side effects on Next dataset. |
| New protocol/server gates | BLOCKING cloud release | Multi-client/server/RLS/unknown-event/epoch/dependency checks. |
| Promotion workflow | BLOCKING only if offered | Explicit replacement preview, both backups, identity/history/runtime/account recovery. May defer preserving experiment. |
| Real-device upgrade/restore/background timer/Doze/permissions | BLOCKING enabled production timer/recovery | Representative physical-device functional lifecycle and alarm interruption tests. |
| TalkBack + comprehensive large font/RTL key flows | BLOCKING release functional QA | Complete task/focus/rule/backup/error/recovery traversal; no inaccessible critical action. |
| Measured frame pacing/additional tablet breadth/detail pane | Recommended post-cutover QA / deferred | Measure large-history startup before performance claims; any actual ANR/unusable flow becomes blocker. |
| Recurrence / secondary Overview polish | SAFE DEFERRED | No dependency on cutover. |

Historical coverage is narrow: PR10/10.1 checked phone/landscape/expanded emulator and Calendar RTL/font150; PR17 expanded density is not physical tablet. PR12 lacked actual Doze/exact-alarm latency, clock rollback device run and injected swap failure. PR16–19 emulator screenshots do not close accumulated TalkBack, physical device, comprehensive RTL/font or frame pacing gaps. PR20 collected them and did not fix or newly verify them. Tablet detail pane, calendar labels and IME polish remain accepted deferred work.

## 13. Ordered bounded implementation milestones — proposals only

No stage below starts automatically. Ordering allows preparation while keeping production inactive; schema migration may be validated on copies before reader work, but real activation waits for all relevant gates.

| Stage | Allowed scope | Excluded scope | Prerequisites | Rollback strategy | Required checks |
|---|---|---|---|---|---|
| **PR21 Preflight & Recovery Gate** | Complete raw schema/domain inventory, anomaly diagnostics, coherent recovery bundle, legacy timer pre-DI readiness checks, restart/recovery state contract and implementation | Version bump, production conversion/activation, sync protocol, promotion, UI polish/recurrence | PR20 accepted; preserve current v29 and dirty data | Original DB/prefs retained; no Next writes; recovery resumes legacy authority | Existing migration/backup/session tests, source concurrency, active/paused/break timer diagnostics, snapshot parity, filesystem/process failure cases on disposable copies |
| PR22 Versioned schema conversion on protected staging | Final additive DDL/metadata, transaction composition, PR3-equivalent conversion and complete semantic validator; production migration hook behind recovery gate | Automatic runtime/cloud activation, manual history rewriting, full merge | PR21 recovery gate; exact version allocation; v29 anomaly policy | Transaction rollback + verified original; no silently activated new DB | Older upgrade chains, all entry/goal/tombstone fixtures, Android FK/transaction, mid-write crashes/reopen, all indexes and legacy parity |
| PR23 Contribution-aware Habit/report readers | Shared effective read/calendar/cache seam for list/detail/score/streak/history/global Statistics/widgets/notifications/export contract | New dashboard/report redesign, migration activation, recurrence | Semantic reader contract and PR15/16 data fixtures; no production capture | Keep production credit disabled; revert reader capability without changing manual/fact stores | Cross-surface no-credit regression + credit/UNKNOWN/SKIP/zero/AT_MOST/calendar/DST/goal/correction/batch tests; functional native parity |
| PR24 Production backup/release hardening | Complete production backup manifest/restore/preferences adapters, startup crash reconciliation, accessibility/device QA; completed before PR25 activation | Cloud sync, merge, feature growth | PR21–23 foundations; must complete before PR25 release activation | Tested v29 recovery and validated new-backup restore | Full compatibility matrix, complete hashes/semantic/tamper/concurrent snapshot cases, swap failures, TalkBack/font/RTL and physical device upgrade/restore |
| PR25 Production runtime authority & local isolation | Explicit production Next session/capabilities, exclusive capture, epoch-qualified callbacks, Task/Habit/Pomodoro recovery, local-only sync gate | Cloud publication, promotion/merge, new timer UI/features | PR21/22/23/24 checks; production backup format/routing ready; explicit local-only release policy | Restore v29 bundle before cloud publication; export post-cutover data separately | Exactly-once segment/credit + zero old EntryOps, active upgrade block, stale notifications, process/restore/retry and real device background checks |
| PR26 Versioned cloud protocol | New dataset epoch/server enforcement, complete outbox/apply/dependencies/conflicts, multi-device bootstrap | Blanket LWW, old-log mixing, unrelated features | Local readiness + server release design; client/server migration authorization | No publish before gate; after publish explicit epoch-aware recovery, not local restore alone | Old-client rejection on all routes, offline races/cycles/deletes, unknown cursor retention, idempotency, interrupted baseline, deployed RLS |
| Separate experimental promotion (optional) | Explicit whole-dataset replacement with preview, both backups and account/epoch transition | Automatic merge or identity remap | All production recovery/runtime gates and explicit human choice | Retain both DBs/prefs, test replacement and epoch restoration | Full graph/history/fact parity + live runtime stopped + crash/restore cases |

PR24 recovery/backup hardening must finish before PR25 can activate/release; the stages grant no automatic authorization and do not require a release between them. Recurrence stays deferred. Cloud work may be skipped for a deliberately local-only initial release; its isolation gates cannot be skipped.

## 14. Go / No-Go criteria

**GO for next audit-derived implementation:** bounded PR21 preparation with v29 and legacy authority preserved. **NO-GO for production DB conversion today; NO-GO for production automatic credit today; NO-GO for current legacy sync on Next authority; NO-GO for promotion today.**

Schema GO requires verified pre/post backups, actual version ownership, coherent raw inventory/anomaly handling, no active legacy timer, complete additive transaction/validator and crash-resumable startup marker/prefs protocol. Runtime GO additionally requires reader parity, exclusive timer pipeline, epoch-qualified jobs/actions and complete new-production restore routing. Local release GO adds tested all-entry-point legacy sync isolation and functional accessibility/physical-device recovery evidence. Cloud GO additionally requires versioned protocol, server old-client refusal and multi-device checks. Promotion GO is separate; merge has no GO contract.

An audit document/build/test aggregate cannot substitute for migration, device, cloud or promotion evidence. Each implementation stage records exact executed tasks and independently inspected results. STOP after PR20.

### PR21 implementation follow-up (2026-10-07)

Human acceptance of PR20 separately authorized PR21 only. The read-only physical/raw preflight, cold pre-DI writer exclusion, legacy Task provider gate and verified local recovery preparation are implemented in [UHABIT_PRODUCTION_PREFLIGHT_RECOVERY.md](UHABIT_PRODUCTION_PREFLIGHT_RECOVERY.md). This does not satisfy PR22 schema conversion or any production runtime/cloud/promotion GO criterion. The next bounded stage remains PR22 protected staging schema conversion, requiring fresh source/bundle revalidation immediately before conversion; no version has been allocated or migration installed in PR21. STOP after PR21 pending separate authorization.

## 15. Evidence inspected and tests actually run

Inspected canonical handoff/spec/foundation/Tasks IA plus project/architecture context; actual versioned migrations/opener, all DDL listed in section 2, raw planner/executor/validator, DatasetSession factory/manager/capabilities, DI providers, timer/capture/controller/alarm/service paths, Habit/cache/widget/notification/export/global Statistics readers, snapshot/manifest/router/restore/import adapters, backend/coordinator and repository Supabase SQL. Baseline source findings were independently integrated from bounded schema, runtime/readers and backup/sync audits; no subtask changed implementation.

Existing relevant test sources were inspected (not executed here): migration planner/executor/validation, ContainerBackupRestore/InvalidBackupRejection/LegacyToExperimentalRestore/ContainerMergeImport, FocusSessionBackup/ContributionBackup/SourceRuleBackup, SyncCoordinator, runtime and projection reader tests. Their prior counts are not fresh PR20 QA metrics.

Executed: `gradlew.bat :uhabits-core:jvmTest --tests '*TodayEvaluationServiceTest' :uhabits-android:testDebugUnitTest --tests '*TodayPresentationTest' --rerun-tasks --console=plain --quiet`, exit **0**. Initial sandbox attempt failed opening the Gradle cache lock; approved rerun completed. Exactly the two current-task XML files were inspected: `uhabits-core/build/test-results/jvmTest/TEST-org.isoron.uhabits.core.today.TodayEvaluationServiceTest.xml` (tests=6, UTC timestamp 2026-10-07T05:00:18) and `uhabits-android/build/test-results/testDebugUnitTest/TEST-org.isoron.uhabits.activities.today.TodayPresentationTest.xml` (tests=3, UTC timestamp 05:01:36). Both have failures=0, errors=0, skipped=0; fresh writes confirmed. This is **9 selected tests**, not a repository-wide test count. No other regression suite ran in PR20. No giant suite, instrumentation, migration on user data, device visual review or live Supabase validation was performed.
