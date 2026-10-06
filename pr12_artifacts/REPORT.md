# PR12 — Runtime capture bridge QA

Status: DONE within the experimental runtime/backup scope, 2026-10-06.

## Delivered contract

- The existing stopwatch/Pomodoro engine is decorated by FocusCapturingTimer. Experimental Android DI persists a singleton runtime journal and finalized FocusSession facts atomically. Production has no Focus schema/store activation and retains numerical Habit accrual.
- Exactly one primary Task/Habit/Container is captured with a frozen organization path/revision. Existing Habit timer controls are wired; startTask/startContainer reuse the same manager/service APIs. New Task/Container launcher UI is not included.
- Pauses/breaks are excluded. Reset cancels the active interval. Duplicate completion/retry is idempotent. Non-auto Pomodoro records the base focus and later overtime separately.
- Journal recovery resumes running wall time, preserves paused elapsed time and stable IDs. A backwards clock cancels an unsafe active segment rather than inventing a fact. No facts are fabricated from legacy timer preferences.
- Experimental full backup v2 preserves finalized facts and validates their checksum. Runtime journal is omitted; restore clears experimental timer preferences. Fact-free v1/raw experimental snapshots are supported. Partial/future Focus schemas, unmanifested facts and tampered checksums are rejected. Restore requires the same experimental dataset identity; production does not accept experimental backups.
- No Contribution, FocusSession-to-Habit projection, Task completion coupling, recurrence, statistics UI or production migration/sync change.

## Automated evidence

90 selected JVM tests passed, 0 failures/errors: Focus domain/repository/runtime/durability, TimerSessionEngine, dataset isolation, backup/restore/merge and Task regressions. See core-results.json for class counts.

Debug APK and Android test APK built successfully. The final debug APK was rebuilt after the dataset restart/selection fixes; unaffected core results were reused.

AndroidJUnitRunner: FocusRuntimeBridgeAndroidTest passed (1 test, 20.308 s). It exercises the real TimerSessionManager for a numerical Habit, Task and Container, pause exclusion, duplicate finish, cancellation, no Habit credit/Task mutation, historical target after Habit deletion, callback dataset guards, and actual DatabaseUtils snapshot/validation/restore. Four finalized facts survive restore; the active journal is excluded. See android-integration.txt.

The existing Application test mode clears its selected DB before instrumentation starts. This test requires a disposable experimental selection and an external focusFixture argument. Do not run it against unsaved user data. QA preserved original files before instrumentation and restored them afterwards. The unrelated destructive BaseAndroidTest suite was not run; its new partial-schema test was compiled. Partial/future rejection is covered by scoped core tests.

## Native walkthrough

Pixel_6 emulator, phone portrait 1080x2400 / density 420, existing Habit timer card, disposable numerical-minute fixture.

1. Start stopwatch, pause: elapsed 16,368 ms; no finalized fact.
2. Force-stop and reopen through Habits: same paused time and stable capture ID.
3. Finish: exactly one TIMER/HABIT fact, duration still 16,368 ms although wall interval is 50,092 ms. See process-recovery.json and screenshots 01/02/03.
4. Existing Pomodoro UI with one-minute focus/break: completion records exactly 60,000 ms and enters stopped break. Start, pause and finish break: fact count remains 2. See pomodoro-runtime.json and screenshot 05.
5. Settings experimental-to-production switch: old PID 5447 exits; persisted mode is LEGACY_PRODUCTION; explicit reopen creates PID 5614 and displays the legacy shell. This found/fixed the lost async preference write. Alarm-driven automatic relaunch timing is not asserted.

## Defects found and fixed

- Existing migration whitelist rejected the already-created UserTasks additive table.
- Same-target pause/resume could lose its frozen organization metadata; next focus now refreshes metadata while resumption retains it.
- Ticker callbacks wrote SQLite every 500 ms; unchanged heartbeats now avoid disk writes.
- Existing overtime could be discarded when AutoSwitch was enabled later.
- A deleted Habit prevented notification Pause/Finish/Reset; experimental controls fall back to the captured target.
- Recovery used stale AutoSwitch policy before applying current preferences.
- Restore failure and dataset switch could relaunch an Activity with closed DB handles; these paths now terminate the process before reopening.
- Async dataset selection apply could be lost during termination; critical selection writes now commit synchronously.
- Android raw backup omitted the new contract and included active runtime; experimental snapshot/restore now uses the full versioned fact contract.

## Restoration and limits

Both original databases and all three baseline preference files restored byte-for-byte (SHA256), integrity ok, user_version 29, original Focus tables absent. Original unrelated files retained; disposable remote fixtures/test APK removed. Debug app remains installed and force-stopped. Private DBs/preferences are local untracked recovery artifacts, not committed. See restoration.json.

Not claimed: physical-device/Doze/exact-alarm timing, automatic alarm relaunch latency, wall-clock rollback on a device, or injected filesystem swap failure. These failure/recovery semantics are modeled in core tests and inspected in native code; actual file-based journal recovery and Android full restore are tested. This is not a new visual pass: existing Light/Dark/AMOLED visual V1 evidence remains unchanged. PR13 is not started.
