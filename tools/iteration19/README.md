# Iteration 19 — remaining resort cost

Decision: **INCONCLUSIVE**. This is an investigation archive, not an applied optimization.
Starting branch/HEAD: `dev`, `69eaef49efc5e677d64546bb3c5c04f732c41440`.
The production and native harness files were restored byte-for-byte to this HEAD.
Iterations 17–18 remain intact. No push, physical device, private database or cloud action.

## Finding and candidate

The daily-entry command unconditionally invokes full parent sorting. Position,
name, color, day tier and container/group comparator inputs are entry-independent.
Both primary and secondary score/status keys require reordering. Completion and
entered filters must rebuild membership; independently ordered children must sort;
both memory and SQLite wrapper listeners must retain synchronous delivery.
See [the event/dependency audit](source-audit.md), captured against the archived candidate.

`candidate.patch` adds a conservative `HabitList.onEntriesChanged()` default,
uses it only in `CreateRepetitionCommand`, skips the stable root sort and retains
the existing child rebuild/independent-sort/notification chain. General resort,
organization invalidation, transactions, effective reads and committed UI remain unchanged.
It also includes optional coarse tracing and its native runner flag, both off by default.
The three new test sources are in `candidate-tests/`; they are deliberately outside
Gradle source sets after restoration.

## Scoped correctness and build evidence

42 tests passed, zero failures/errors, in the final archived candidate state:

| Suite | Tests |
|---|---:|
| HabitListEntryChangeTest | 11 |
| SQLiteHabitListEntryChangeTest | 3 |
| HabitResortCoarseDiagnosticsTest | 1 |
| MemoryHabitListResortTest | 4 |
| SQLiteRootGroupingTest | 4 |
| OrganizationAuthorityIntegrationTest | 11 |
| DatasetSessionTest | 6 |
| CreateRepetitionCommandTest | 2 |

Tests preceded the correction: first missing-API RED, then 11 correctness tests
passed using the conservative default resort, then the zero-sort test failed
before the candidate passed. The initial memory tests omitted the shared date
setup; extending the existing BaseUnitTest corrected the tests, not production.

Coverage includes stable grouped/position ties, primary and secondary score/status,
numerical quantity changes without completion changes, historical score changes,
nested completion/entered children, independent child ordering, reentrant mutations,
SQLite listener order/persistence, actual rollback followed by explicit reload,
separate database identities and detach, and exception-balanced coarse spans.
The explicit reload test does not establish automatic rollback cache recovery;
separate SQLite datasets do not substitute for a native DatasetSessionManager cutover.
Existing organization suites supply actual traversal evidence beyond the mocked root test.

`:uhabits-android:assembleDebug` and `:uhabits-android:assembleDebugAndroidTest` passed.
Baseline/candidate instrumentation APK hashes match; application hashes differ.
Exact hashes are in `evidence/`. APKs, successful XMLs and quiet build logs remain
in `scratch/iteration19/`. The independent source review found no actionable defect;
it did not run tests or establish device/performance readiness.

The committed failed-trace and host-environment JSON files are compact summaries
with paths and SHA-256 references to their complete raw results in scratch.

## Attribution and failed environment attempt

Historical Iteration 18 probes-OFF traces, 20 exact resort stages each:

| Existing candidate run | Wall median | Scheduled CPU median | Runnable median | Sleep median |
|---|---:|---:|---:|---:|
| 90g-c1 | 161.212 ms | 102.370 ms | 63.087 ms | 0 ms |
| 1825g-c1 | 156.368 ms | 111.421 ms | 52.761 ms | 0 ms |

These independent medians are not additive. Nested JIT code-cache contention is
visible, but its inclusive duration overlaps scheduler accounting. Neither model
lock waiting nor SQLite waiting follows from wall-minus-CPU. SQL/comparator/observer
substage costs remain UNKNOWN in those traces. Both loaders report eight out-of-order
events. Details: `evidence/perfetto_analysis_report.md`, `states.sql`, `nested.sql`,
`summary.json`; full raw CSVs and original traces remain in scratch.

Fresh chronology, all on `emulator-5580`, named
`UHabit_UI_Review_Disposable_20261011`, API35, serial `EMULATOR36X6X11X0`, airplane=1:

1. After cold boot, sensor daemon consumed 23% CPU; host available memory was
   approximately 0.9 GiB even after owned Gradle/Kotlin daemons were stopped.
2. A temporary stop of `vendor.sensors-hal-multihal` produced 393% guest idle.
   This intervention made the environment unsuitable as a normal benchmark baseline.
3. `iter19-coarse-b` never installed an APK: health/log files collided with the
   native runner's preserve-evidence check. Wrapper logs/health were moved to subdirectories.
4. The only executed baseline, `iter19-coarse2-b`, used detailed probes OFF and
   coarse spans ON. It failed the window-focus assertion before valid edit input,
   then reported `INSTRUMENTATION_ABORTED: System has crashed`. Native JSON pulls
   failed with I/O error. The 21,306,172-byte trace was retained; capture returncode=0.
5. Host monitor: 102 samples, CPU mean 52.32% / max100%, memory mean94.76% / max96%,
   minimum available608.3 MiB. These are concurrent observations, not a proven crash cause.
   Guest storage had 4.1 GiB available and host disk roughly49.5 GiB available.
6. Sensor HAL was restored (`running`) and the guest rebooted. Boot completion
   was observed, but guest remained heavily loaded; healthy recovery was not established.
   The disposable emulator was shut down normally. No candidate arm was run.

The failed trace analyzer finds **zero resort operation windows**; coarse startup
slices are unassigned and cannot be used as edit-cost evidence. No successful native
fixture fingerprint/parity result exists for this attempt. The crash cause is
undetermined; no attribution to the candidate, host memory or sensor intervention is made.

## Decision gates and stop boundary

| Gate | Result |
|---|---|
| Scoped candidate correctness/build | PASS within the listed tests/builds |
| Redundant parent work | Demonstrated by source dependencies and deterministic zero-sort test; fresh SQLite reduction unmeasured |
| Reproducible committed presentation benefit | NOT EVALUATED |
| Tail and entry-sensitive non-regression | NOT EVALUATED |

The planned 18 presentation arms (90g,1825g,90s; B-C-C-B-B-C per cell) did not start.
There are no paired presentation medians, unmatched-endpoint coverage, scenario tails,
run-level variation, or warm/Statistics/Tasks comparisons for this candidate.
Low-overhead substage attribution was attempted but remains incomplete.
Therefore no ACCEPT or performance REJECT is justified. **INCONCLUSIVE**; candidate
archived and removed from production. This task stops after this one candidate evaluation.

## Cleanup

Initial `scratch/` logical file size:25.740 GiB /65,972 files. Git expanded status
contained21,128 untracked files and132 pre-existing tracked deletions.
Deleted only reproducible build/.gradle/.kotlin/__pycache__ directories inside validated
scratch paths:1,728,260,568 bytes /44,634 files; exact manifest in `evidence/`.
Raw measurements, source snapshots, databases and APK evidence were retained.

Both inactive AVD homes were moved intact from `scratch/ui-review/` to
`C:/Users/Pavel/.android/uhabit-disposable-qa/{avd-home,avd-home-api35}` (16.25 GiB),
with top-level `.ini` paths updated. This reduces repository size, not total disk usage.
No snapshot or virtual disk was deleted. The API35 AVD booted successfully from its new location.
`.gitignore` now excludes `/scratch/` and `/tools/qa/__pycache__/`.
The132 tracked deletions and unrelated IA files/v29 fixture were preserved.
Final scratch logical size is8.212GiB /21,250 files, including retained raw evidence
and this iteration's APKs/attempts. The two newly built debug APK outputs were
removed from the normal build output directory after hash-verified archival;
an unaccepted candidate is not left there as the current installable output.

## Reproduction boundary

The archive does not launch anything automatically. `git apply --check
tools/iteration19/candidate.patch` validates against the restored baseline.
To recreate the candidate in a separately authorized future run, apply that patch
and copy `candidate-tests/*.kt` into the existing JVM models test directory.
The scripts use `scratch/iteration19` for evidence/APKs. Choose fresh arm labels;
never overwrite failed attempts. `run_arm.py` fails closed on sensor CPU and guest idle,
and defaults both detailed and coarse probes OFF. The frozen campaign file records
the planned counterbalancing; it is not a record of completed runs.

`analyze_coarse.py --trace TRACE --processor C:/Users/Pavel/.codex/skills/perfetto/bin/trace_processor
--output UNUSED.json` preserves SQL/CSV/stderr/JSON/Markdown and loads one trace.
`compare.py` requires completed native/final results and validates schema/WAL,
parity and fixture equality. No comparison JSON was produced because the campaign did not run.
