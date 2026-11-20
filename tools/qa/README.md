# UHabits QA Strategy and Verification Policy

## Core Principle: Risk-Based Verification

> **Full QA Matrix is a final acceptance gate, not the default development loop.**

Before running tests, determine:
1. **What changed?** (Domain logic, single UI component, layout geometry, strings, database schema, or documentation only?)
2. **Which invariants could be affected?** (Layout bounds, text truncation, touch targets, accessibility, data calculation, persistence?)
3. **What existing tests already verify these invariants?** (Core JVM unit tests, Android unit tests, existing focused instrumentation?)
4. **What is the minimal sufficient verification?** (Quick Smoke vs. Targeted QA vs. Full Matrix?)
5. **Is there any justification to re-run already verified configurations?** (Never repeat full matrices for minor fixes, documentation, or unchanged components).

A small set of carefully inspected artifacts (e.g. 10–15 high-contrast frames covering critical edge cases) is far more informative and reliable than hundreds of uninspected automated captures.

---

## The 3 Tiers of QA

### Level 1 — Quick Smoke (Default Development Loop)
Apply after routine development, local bug fixes, chart adjustments, string/label updates, or minor refactorings:
- **Scope**:
  - Relevant JVM and Android unit tests (`:uhabits-core:jvmTest`, `:uhabits-android:testDebugUnitTest`).
  - Opt-in [Compose screenshot verification](../../docs/BUILD.md#compose-screenshot-tests) for affected components with goldens.
  - Targeted compilation and APK assembly (`assembleDebug`, `assembleDebugAndroidTest`).
  - One smoke run on an isolated AVD (`light_standard_100`) when the change needs device or UI integration evidence. Exercise the affected journey.
- **Rule**: If the change does not touch Android runtime, UI rendering, or resources, **skip instrumentation entirely** — fast unit/integration tests are sufficient.

### Level 2 — Targeted QA (Risk-Based Verification)
Apply after substantial modifications to specific UI components, complex interactions, custom views, or layout hierarchies:
- **Scope**:
  - Run only the 3–4 configurations that directly test the risk dimensions:
    1. `light_standard_100` (Baseline reference)
    2. `dark_standard_100` (Dark theme contrast & colors)
    3. `light_narrow320_200` (Extreme space constraint + maximum text expansion)
    4. `amoled_narrow320_200` (Pure black contrast + small viewport + large font)
  - Execute **only the relevant journey** (e.g., only `habitOverviewJourney` if Habit statistics were modified), avoiding redundant executions of unaffected journeys.
- **Focus**: Verify component bounds, padding, text wrapping, touch targets (≥ 48dp), TalkBack descriptions, and DPAD focus.

### Level 3 — Full QA Matrix (Final Acceptance Gate)
The full 12-configuration matrix:
$$\text{Light, Dark, AMOLED} \times \text{Standard, 320dp} \times \text{100\%, 200\% font scale} = 12 \text{ configurations}$$
is reserved exclusively for:
- Final acceptance and sign-off of a major PR or integrated multi-phase feature milestone.
- Foundational architectural changes to the app shell, global design system, base themes, or navigation structure.
- Explicit user request for release validation.

**Never run the Full Matrix:**
- As a routine debugging loop during iterative coding.
- After minor layout tweaks, bug fixes, or test adjustments that follow an already-passed full matrix.
- After edits to Markdown documentation, reports, or scripts.

---

## Critical Data Safety Invariants

- **Synthetic Isolation**: All Android instrumentation QA must run on isolated temporary AVDs with synthetic datasets.
- **Zero Real Data Touch**: Never execute `uninstall`, `pm clear`, or wipe data on physical devices or non-QA environments.
- **Persistence Verification**: Database schema migrations, backup/restore fidelity, and dataset switching are verified via dedicated contract and roundtrip tests, not visual instrumentation passes.

## Active vs. Quarantined Runners

- **Active Safe Boundary**:
  - `tools/qa/with-avd.ps1 -Temporary` — owns temporary AVD lifecycle, `-WipeData` is restricted to `-Temporary`.
  - `tools/qa/launch_avd.py` — thin Python wrapper delegating to `with-avd.ps1` (pass `--temporary` for isolated runs; without it, defaults to persistent `UHabit_Next_Dev_API35`).
  - `tools/qa/qa_safety.py` (`verify_disposable`) — verifies explicit serial, active temporary manifest, live owner process ancestry, boot state, AVD identity, and installed package.
  - `tools/qa/qa_capture.py` / `tools/qa/capture_asserted.py` — strict foreground-activity, fresh-hierarchy, and visible-label screenshot capture.
- **Historical PR-Specific PowerShell Runners (Unapproved for General QA)**:
  - `pr10-run-isolated.ps1`, `pr11-run-isolated.ps1`, `pr11-run-targeted.ps1`, `pr12-run-isolated.ps1`, `pr12-run-targeted.ps1`, `pr13-final-gaps.ps1`, `pr14-run-isolated.ps1`, `pr14-upgrade-test.ps1`, `run-gap6-qa.ps1`, `run-habit-link-visual.ps1`, `run-sections-qa.ps1`, `run-targeted-qa-api35.ps1` — archived single-milestone scripts with hardcoded emulator/ADB assumptions. Retained for historical reference only; do not treat as approved or safe general-purpose QA runners.
- **Quarantined Legacy Runners (Do Not Execute)**:
  - `tools/qa/verify_container_dialogs.py` is a fail-closed stub (original unsafe implementation retained only in Git history).
  - Legacy mutating Python runners (`capture_habit_details_baseline.py`, `capture_habit_details_matrix.py`, `habit_details_test_fixture.py`, `verify_consolidated_habit_details.py`, `verify_habit_details_interactions.py`, `verify_shared_components.py`, `run_appearance_verification.py`, `run_consistency_and_classic_verification.py`, `verify_details_composition.py`, `run_benchmark.py`, `run_post_navigation_attribution.py`, `run_qa_avd.py`, `install_apks.py`) are quarantined via `qa_safety.legacy_runner_disabled`.
  - `tools/qa/run-ui-review.ps1` and `tools/qa/measure-habits-startup.ps1` target persistent named AVDs and mutate device/app state; do not run against valuable data.

### Android CLI in an owned QA run

In Antigravity, check `agy plugin list` for the official `android-cli-plugin` before installing tooling. Inside the active `with-avd.ps1 -Temporary` action, keep the guest offline and pass its lease-provided `$serial` explicitly to Android CLI. Use `android run --device=$serial $apk` to deploy and launch a prebuilt debug APK in one call, or `android install --device=$serial $apk` for installation alone. Passing the APK path avoids a second Gradle build.

For a visual spot check, use `android screen capture --device=$serial --output=$png` and inspect the image. Use `qa_capture.py` for asserted QA evidence. Keep the existing ADB hierarchy workflow for scripted selection. Validate the CLI's `layout --flat` output on the target before relying on CLI selectors. In Android CLI 1.1, `layout --diff` is a no-op.

---

## Agent and Orchestration Discipline (Codex / Orca / AGY)

- The **Coordinator** is responsible for evaluating the risk profile and selecting the appropriate QA level (Level 1, 2, or 3).
- **Subagents / Workers** must never launch independent full QA matrices or spin up uncoordinated AVDs.
- Avoid duplicate concurrent AVD runs for the same verification scope.
- After an interruption, check Git status and the previous build result before starting again. Use `with-avd.ps1 -ListOrphans` to inspect dead-owner leases. Continue interacting with a guest only from its owning action.
- Always cleanly stop temporary AVDs and clean up orphan resources after test sessions.
