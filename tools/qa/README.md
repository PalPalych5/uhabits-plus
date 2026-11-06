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
  - Targeted compilation and APK assembly (`assembleDebug`, `assembleDebugAndroidTest`).
  - Single standard smoke run on an isolated AVD (`light_standard_100`), running only the specific journey covering the modified feature.
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

---

## Execution Discipline

- Evaluate the risk profile and select the appropriate QA level (Level 1, 2, or 3).
- Do not launch full QA matrices for minor fixes, documentation updates, or routine development.
- Avoid duplicate concurrent AVD runs for the same verification scope.
- Always cleanly stop temporary AVDs and clean up test resources after test sessions.
