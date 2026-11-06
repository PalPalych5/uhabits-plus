# PR21 — Production Preflight & Recovery Gate

Status: **DONE**, 2026-10-07. Parent roadmap: [UHABIT_PRODUCTION_CUTOVER_PLAN.md](UHABIT_PRODUCTION_CUTOVER_PLAN.md). No publication or PR22 execution.

## Boundary and entry

PR21 prepares evidence only. `DATABASE_VERSION = 29`, `LEGACY_PRODUCTION`, direct legacy numerical timer credit and existing cloud authority remain unchanged. There is no migration, production Next schema installer, authority switch, bundle restore, promotion, merge or PR22 action. `MIGRATION_READY` means a verified preparation source at that instant; it does not authorize or execute migration.

The internal entry is `HabitsApplication.requestProductionRecoveryPreparation()`. It durably requests preparation, schedules a normal process restart and terminates the old process. It does not dispose/cancel/reset/complete its timer. The cold process calls `ProductionPreparationStartup.runBeforeDependencyInjection()` immediately after `Application.onCreate`/context assignment, before session resolution, `DatabaseUtils.initializeDatabase`, component construction, timer or sync. No migration UI is added. Ordinary startup with neither request nor preparation metadata skips the gate.

Any persisted preparation state is reconciled on subsequent starts. A missing/empty canonical DB, unreadable source, unsupported version, failed integrity or unresolved WAL/hot journal stops this explicit path before the ordinary opener can create, upgrade or replace data. A healthy v29 source with active timer, anomalies, stale approval or missing/corrupt bundle retains ordinary legacy startup; migration readiness remains blocked. Source inspection is independent of bundle classification so a rejected bundle cannot trap healthy legacy startup.

## Read-only preflight contract

`ProductionPreflightService` owns no opener. Its caller supplies a closed/quiescent source and a read-only connection. Every statement is SELECT or a read-only PRAGMA; there are no schema installers, repairs or write PRAGMAs. Android uses `OPEN_READONLY | NO_LOCALIZED_COLLATORS`, not `SQLiteOpenHelper`.

`ProductionPreflightReport` includes path, user version, exact file SHA-256, physical schema SQL/type/name/table, table columns/counts, semantic/schema hashes, identity markers, integrity and foreign-key results, connection foreign-key flag, journal mode, timer readiness, typed blocking issues and warnings. `sourceEligible` excludes only recovery-evidence issues; full `migrationEligible` requires no blockers. An unbundled clean report explicitly has `RecoveryBundleUnavailable` and is not migration-ready.

Inventory reads `sqlite_master`, `table_info`, `user_version`, `foreign_keys`, `integrity_check`, `foreign_key_check`, and actual row counts. Required domain tables: HabitBlocks, Habits, HabitExtensions, HabitGoals, Repetitions, EntryOps, SyncQueue, AppSettings. Historical Events and Android metadata are allowed auxiliary tables; SQLite internal tables are inventoried. Unknown tables, views/triggers and all known Next tables block. Both empty and nonempty Next tables are distinguished and retained unchanged; empty leakage is not silently accepted.

Issue taxonomy covers unsupported version, integrity/FK failure, missing legacy table, unexpected Next table/nonempty Next table/schema object, duplicate/blank portable ID, dangling block, active Habit in deleted block, orphan extension/goal/Repetition/EntryOp, malformed legacy row, timer state, unavailable/mismatched recovery evidence and inspection failure. Physical schema is not inferred from `user_version=29`.

Raw inventory reuses the PR3 reader, then diagnoses references without collapsing duplicates through `associateBy`. Missing extension rows/unassigned habits are not repaired. Raw row/type/natural-key validation preserves null versus empty, rejects malformed numeric/frequency/identity fields, duplicate local IDs/settings keys, out-of-Int-range values and non-0/1 booleans. A malformed FK definition reports a blocker without suppressing the remaining raw inventory. No name/color-based UUID repair or fabricated history is performed.

## Provider leakage

PR20's risk existed: `userTaskRepository()` unconditionally called `UserTaskSchema.createSchema`. PR21 checks `session.isExperimental` before resolving the lazy DB or installing anything. Legacy resolution now throws an explicit disabled-capability exception. Other existing Next providers retain their experimental gates. The instrumentation test resolves the generated component's legacy Task provider and verifies source bytes and absence of UserTasks; no numbered production migration is installed.

## Source fingerprint

Approval binds exact database bytes/SHA-256, user version, physical schema hash, complete raw legacy/allowed auxiliary semantic inventory hash, row counts and dataset identity. Semantic encoding sorts rows, preserves duplicate rows and extra columns, length-frames values and uses SQLite `typeof` plus hex BLOB representations to avoid delimiter/NUL collisions. AppSettings is included in full. The manifest also binds canonical source path and selected preference checksum. Timestamp alone is never approval.

The gate compares source before/after snapshot, independently reopened copy, and restart source. Any changed DB, schema, counts, identity or captured preferences invalidates readiness. Normal legacy work after preparation may immediately make approval stale; future PR22 must revalidate under the same writer exclusion immediately before using it. This stage cannot provide perpetual readiness while ordinary writes continue.

## Legacy timer readiness

The classifier reads actual `pref_timer_*` persistence: selected Habit, STOPWATCH/POMODORO, FOCUS/BREAK, running, elapsed/start time, durations and completion/display flags. READY means no active work or pending completion. Merely retaining a configured Habit with zero work is allowed. Running stopwatch/focus/overtime, paused accumulated work and break block as `BLOCKED_ACTIVE_WORK`; completion/display pending blocks as `BLOCKED_PENDING_COMPLETION`; invalid types/enums, negative values or contradictory running/start/target state yield `INCONSISTENT_STATE`. Overtime is represented by existing FOCUS elapsed state, not an invented enum/preference.

Resolve the existing timer through legacy controls before retrying. The gate does not clear timer prefs, cancel alarm/notification, fabricate FocusSession or credit quantities. Alarm revision/completion event IDs are preserved as metadata. Android's process cannot execute receiver/service callbacks before its Application initialization returns; the gate owns that window. Existing alarms are not canceled.

## Coherence and WAL strategy

The quiesce protocol is a cold pre-DI process boundary, not a live UI mutex or sync scheduling flag. The old process is terminated; the new process has not opened the DB or constructed timer/sync/repositories. The inspected manifest has no app writer in another Android process. Provider review found FileProvider and AndroidX startup initializers for emoji/process lifecycle/profile installation; no database writer/WorkManager initializer. This is an architectural assumption future writer/provider/multiprocess changes must revisit.

The bounded `LegacySnapshotLease` contract excludes every writer for the complete inspection/copy/preferences/verification interval. Capture allowlisted preferences before copying, after copying, and before ready commit; require equality. Timer and SyncQueue/AppSettings writes cannot run in that cold-start window. After it returns, normal legacy DI and sync resume unchanged. No pause API, timer dispose or sync protocol modification is introduced.

The legacy opener disables WAL, but PR21 trusts actual files/mode. Android rejects WAL header bytes and nonempty `-wal`/`-journal` before opening SQLite, avoiding even read-only WAL shared-memory creation. It accepts only delete/truncate/persist mode with no active sidecar, then copies the exact closed main file. It performs no checkpoint or sidecar deletion. WAL/hot-journal sources require separate resolution and cannot create a misleading main-only bundle. `-shm` alone is not committed data. Core and Android tests include actual committed uncheckpointed WAL.

## Local recovery bundle

Owned location: `noBackupFilesDir/production-recovery/<UUID>/`. Files: exact `legacy-v29.db` and checked `manifest`; root has checked `preparation.state`. This local safety artifact is distinct from user portable backup v1–v4. It is not encrypted and remains app-private, outside automatic backup; it contains the user's original DB.

Manifest v1 includes bundle ID/time/source path, exact source fingerprint/schema/counts/identity, `LEGACY_PRODUCTION`/database filename, timer readiness and selected preference hash. Preference allowlist contains nonsecret `sync_device_id` writer identity (DeviceIdentityManager stores it outside SQLite), sync enabled/cursor/review/bootstrap markers; midnight/first-weekday; Pomodoro configuration; timer target/mode/phase/running/elapsed/start/durations/completion/alarm revision/event ID. Absent writer identity on a never-initialized install is preserved as absence, without generating one during inspection; existing IDs are validated and included in Android fingerprint identity markers. All required fields are validated. Auth preference stores, passwords, API/sync keys, account email/tokens, arbitrary preferences and caches are not captured. The exact DB remains exact: PR21 does not redact or rewrite original user rows.

Verification requires source and copy integrity, v29, source/copy SHA equality, semantic/schema/count/identity equality, independent read-only reopen, ready timer and complete valid preferences, manifest checksum and strict manifest key allowlist. A copied file alone never grants readiness. This metadata is locally checksummed for corruption detection, not authenticated against a malicious actor with app-file access.

## Persisted recovery state

`LEGACY_STABLE → PREFLIGHT_PASSED → RECOVERY_BUNDLE_CREATING → RECOVERY_BUNDLE_VERIFIED → MIGRATION_READY`. Any rejected evidence returns `RECOVERY_REQUIRED`; a fresh preparation is required. States bind source fingerprint and bundle ID. There is no MIGRATION_IN_PROGRESS behavior in PR21.

Metadata uses deterministic Base64 fields/checksum, fsynced temporary file and atomic rename. Partial `.tmp` metadata is not approval. Parent directory fsync is best effort: the verified contract is process-death recovery and fail-closed corruption handling, not an unmeasured hardware power-loss guarantee. Production source is never renamed, replaced, opened for writes or restored by this gate.

| Restart state/evidence | Result |
|---|---|
| No preparation | Ordinary legacy startup |
| PREFLIGHT_PASSED / BUNDLE_CREATING | Recognize owned incomplete files, idempotent bounded cleanup, LEGACY_STABLE; legacy remains canonical |
| VERIFIED before readiness commit | Reopen/verify bundle, exact current source and preferences, then reapprove only if all match |
| Source/prefs changed | RECOVERY_REQUIRED, fresh preflight; healthy legacy startup allowed |
| Missing/corrupt bundle or manifest | Migration blocked; healthy legacy startup allowed |
| Corrupt state metadata | Fail closed; healthy legacy startup allowed |
| Dataset mode disagreement | Invalidate readiness and block this recovery startup with a diagnostic before the ordinary resolver's legacy fallback; no automatic switch |
| Missing/unsafe canonical legacy source | Stop explicit startup path before opener; never silently create an empty DB |

Incomplete cleanup only touches fixed owned names in a validated bundle UUID directory and rejects symlinks/unrecognized files. Cleanup interrupted after deletion is retryable. Verified and older unreferenced bundles are retained; automatic retention/purge is not implemented.

## Failure evidence and checks

Seven hooks: before snapshot; during actual copy; after copy before manifest; during preference capture; before verification; after verification before readiness commit; during incomplete cleanup after real deletion. Exception-injection tests rebuild gate instances and assert unchanged original bytes, deterministic restart classification and safe retry. A separate subprocess test uses `Runtime.halt(73)` at every hook without stack unwinding/finally and verifies parent restart/source equality/retry.

Final command (exit 0):

```powershell
.\gradlew.bat :uhabits-core:jvmTest --tests '*ProductionPreflight*' --tests '*LegacyTimerReadinessClassifierTest' --tests '*ProductionRecoveryGateTest' --tests '*LegacyContainerMigration*Test' --tests '*ContainerBackup*Test' --tests '*InvalidBackupRejectionTest' --tests '*DatasetSessionTest' --tests '*TimerSessionEngineTest' --tests '*TimerNotificationStateTest' :uhabits-android:testDebugUnitTest --tests '*SyncCoordinatorTest' :uhabits-android:assembleDebug :uhabits-android:assembleDebugAndroidTest --console=plain --quiet --max-workers=1
```

Inspected exactly 13 XMLs in `uhabits-core/build/test-results/jvmTest/`, current timestamps 2026-10-07 UTC 06:08:26: ProductionPreflightService 9, durability 1, timer classifier 8, recovery 20; PR3 planner/executor/validation 7/4/5; ContainerBackupManifest/Restore/InvalidBackupRejection 2/3/4; DatasetSession 6; TimerSessionEngine/NotificationState 14/6. **89 selected core tests**, zero failures/errors/skipped. The single current `uhabits-android/build/test-results/testDebugUnitTest/TEST-org.isoron.uhabits.sync.SyncCoordinatorTest.xml` has **12 tests**, zero failures/errors/skipped, UTC 06:10:28. No repository-wide total is claimed.

Built/installed app and test APKs on emulator-5554 (API 36); command:

```text
adb shell am instrument -w -e class org.isoron.uhabits.production.ProductionPreparationAndroidTest org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner
```

Final result **OK (12 tests)**. Covers actual v29 byte/schema/fingerprint parity, provider gate, empty/nonempty Next tables, secrets, source staleness, missing/corrupt bundle, real WAL, five timer states, pre-DI lease rejection, versions 28/30 and missing canonical DB. Local ignored evidence: `pr21_artifacts/android-instrumentation-final.txt`, SHA-256 `f43cb6d61d732bb5586c89b84ad40bab10147d91de3bb05d79fbb2a654eb6edc`; emulator archive/hash comparisons remain local and are excluded from Git. Initial fixture failures (multi-row UNIQUE update, Android PRAGMA API and intentional CHECK bypass) were corrected before these final reruns.

Android fixtures use unique cache paths and the actual numbered v29 opener only to create disposable fixtures. Production preparation then uses the read-only runner. Instrumentation does not swap global components or mutate default prefs. Because existing test Application startup can recreate a selected dataset, emulator databases/preferences/files were archived before install/run and restored afterward; original database/preference hashes matched. No physical user production database was tested or changed.

## Remaining boundary

PR22 is separately authorized work: protected staging schema conversion, exact version allocation and full legacy parity, followed by later reader/runtime/backup/sync milestones. PR21 does not restore a bundle over canonical production, certify power-loss behavior, implement WAL recovery/checkpoint policy, migrate corrupted/unsupported sources, add diagnostics UI or permit runtime/cloud/promotion. A nonempty cold PERSIST journal is conservatively rejected too; it is not labeled corruption or deleted. Persisted preparation is developer-only infrastructure: a contradictory dataset selection requires explicit reconciliation, rather than silently switching; there is no user-facing reset/purge flow in this stage. Physical-device/OEM alarm timing and power-loss evidence remain release-hardening work. The existing high-level four NO-GO decisions for production conversion/runtime/current-sync/promotion remain in force until their own stages are proven.
