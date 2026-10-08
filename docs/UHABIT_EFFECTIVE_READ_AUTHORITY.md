# PR23 effective Habit read authority — audit and PR23A implementation

Date: 2026-10-08, Asia/Novosibirsk. Inspected baseline: `dev`, PR22.1 `e24dc13eb04970559d7a177eabab7787bfb3be5e`; PR22 `c1dba8521bc3ac446ddd70248563e16d9c03ce2b`. No tracked source changes at audit start. Existing untracked scripts, screenshots and private QA databases were preserved.

**Status: PR23A DONE; PR23B IN_PROGRESS (Global Statistics checkpoint completed and verified; Compare, Widgets, Notifications, CSV remaining). PR23 overall NOT DONE.** Production activation remains BLOCKED. PR24/PR25 BLOCKED.

## Evidence and inventory conventions

`C/` means `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/`; `A/` means `uhabits-android/src/main/java/org/isoron/uhabits/`. Sources below identify actual classes/functions at the inspected baseline, not assumptions from the old architecture document. In particular, `StatisticsFragment` lives in `A/activities/statistics/StatisticsActivity.kt`; the live report calls `StatisticsReportStateBuilder`, not the stale documented `StatisticsStabilityReportBuilder`.

O = originalEntries; E = computedEntries; S = ScoreList; T = StreakList; H = Habit.statisticsEntries. The inventory below describes the inspected audit baseline, before PR23A. Converted surfaces and current contracts are recorded in the implementation section. Calendar conversion uses current local timezone; several legacy helpers use locale weekday while views receive live preferences.

## Production-facing reader inventory

| Surface / source | Authority today | Calendar and date domain | Cache / invalidation today | Provenance required / intended PR23 authority |
|---|---|---|---|---|
| Habit list: C/ui/screens/habits/list/HabitCardListCache | E visible cells/current numerical quantity; S today | getToday, visible interval | Async generation-protected cache; command/preference refresh | Yes for numerical editing; snapshot days + canonical completion + effective score |
| Filtering/search completion: C/models/HabitMatcher | Habit.isCompletedToday -> E | today and statistics cutoff | Habit list model/resort | Typed effective completion; search text itself unchanged |
| Sorting: C/models/memory/MemoryHabitList | S, Habit completion, E today's quantity | today | resort after legacy mutations | Same snapshot revision as cells/filter |
| Detail host: A/activities/habits/show/ShowHabitView | Builds core card states; PR16 Focus observer refreshes effective Target | live prefs/today | Habit model + Focus controller observer | One immutable detail snapshot supplied to every card |
| Target: C/ui/screens/habits/show/views/TargetCard | H grouped numerical totals; experimental Android current-period row separately reads PR15 | computed oldest/cutoff, passed first weekday | detail refresh | PR15 goal/value/coverage + manual/Focus breakdown; no independent sum |
| History: C/ui/screens/habits/show/views/HistoryCard | E intervals and daily target logic; Habit.periodActualOn for AT_MOST periods | computed oldest/cutoff; passed weekday but period helper uses locale | detail/chart state | Effective day quantity/state, typed coverage, source breakdown; editor uses O only |
| Score: C/ui/screens/habits/show/views/ScoreCard | S | computed oldest/cutoff, chart first weekday | Habit.recompute | Shared effective score result, with exactness/coverage separate |
| Streak: C/ui/screens/habits/show/views/StreakCart | T | computed interval | Habit.recompute | Canonical completion decisions; preserve Boolean/legacy parity |
| Bar: C/ui/screens/habits/show/views/BarCard | E grouped sums | computed oldest/cutoff; passed weekday | detail refresh | Effective numerical quantity, not invented completion/time conversion |
| Frequency: C/ui/screens/habits/show/views/FrequencyCard | O.computeWeekdayFrequency | cutoff, display first weekday | detail refresh | Numerical quantity by weekday; Boolean manual check frequency preserved |
| Overview: C/ui/screens/habits/show/views/OverviewCard | S; O YES_MANUAL count | today/month/year/cutoff | recomputed model | Effective score; explicitly manual Boolean count remains manual |
| Global overview: C/ui/screens/statistics/StatisticsOverview | E; H; own StatisticsCompletionPolicy | daily AT_LEAST, separate calendar AT_MOST; cutoff | built report state | Typed snapshot day/period evaluations, preserving report metric meaning |
| Main global report: C/ui/screens/statistics/StatisticsReportStateBuilder | E in evaluatePeriod/evaluateHabitSlice; S for stability | historical goal segments, calendar periods, first date from O | StatisticsFragment ReportKey/dbVersion/generation cache | Snapshot quantities + coverage + canonical completion; preserve weighting/filter/group structure |
| Calendar/activity/closed days | report daily evaluations | daily goals only; first-date eligibility | report cache | Same daily effective state; weekly/monthly goals must not become daily completion |
| Trends/history/current-versus-previous comparisons | report range/slice evaluations | current, previous and bucket windows | report cache; repeated builds | One preloaded union range, effective quantities and coverage |
| Weekly rhythm | report dailyProgress aggregation | matching weekdays/observation threshold | report cache | Inherits shared daily semantics; no new metric |
| Sphere/Container-derived global sections | report Habit results + scope/group filters | existing placement/block grouping | report cache | Effective Habit inputs, existing grouping semantics retained |
| Insights: report + A/activities/statistics/views/StatisticsHabitInsightsView | report slices; direct E today-minus-i loops | observation counts/ranges/today | report/view rebuild | Snapshot observations/completion/score, with incomplete coverage retained |
| Compare Habits: A/activities/statistics/CompareHabitsActivity and views/CompareCalendarView | O known counts, E cells, S, shared card builders | O/report-builder start discovery; passed weekday in some sections | activity rebuilding | Same snapshot/date-domain; include this route in cross-surface proof |
| Checkmark widget: A/widgets/CheckmarkWidget | E value/state; S; Habit.isCompletedOn | today | WidgetUpdater listens to CommandRunner | Same day/completion/score snapshot; no effective-to-manual edit seed |
| Target/History/Score widgets | corresponding core card builders | widget preferences/calendar | WidgetUpdater | Converted presenters or explicit Next capability gate |
| Streak widget: A/widgets/StreakWidget | T | cached model | WidgetUpdater | Shared effective streak or explicit Next gate |
| Frequency widget: A/widgets/FrequencyWidget | O weekday quantity | first weekday; currently no card-style cutoff argument | WidgetUpdater | Shared numerical quantity and cutoff or explicit Next gate |
| Notifications: C/ui/NotificationTray + Android tray | Habit.isCompletedToday | completion today; reminder date schedules notification | command/preferences listeners | Canonical effective completion; preserve existing AT_MOST exception and scheduling |
| Reminders: ReminderScheduler/ReminderController/ReminderReceiver | schedule plus tray completion suppression | existing day/time rules | legacy commands/preferences; experiment capability disabled | Completion decision changes only; scheduler timing unchanged |
| CSV: C/io/HabitsCSVExporter | computed Checkmarks and S; combined timeframe from O | per-Habit computed start; combined O min/max | snapshot export execution | Computed/full outputs effective with provenance; raw manual output separately labelled |
| Sync export: A/sync/SyncCoordinator | direct O known rows | legacy row dates | existing legacy sync | Intentionally raw/manual; never sync projected observations |
| Internal Container browsers: A/activities/containers/ContainersActivity, UnifiedContainerHomeView | E day values and numerical edit seeds | selected date | model callbacks | Shared display snapshot, O edit seed; no UI redesign |

## Existing partial effective readers and storage paths

PR15 `C/models/projection/EffectiveHabitValueService` already owns Contribution summing, active/tombstone filtering, native x1000 units, integrity errors, historical goal lookup, UNKNOWN/SKIP exclusion, calendar goal evaluation and DST/day-offset bucketing. Reuse it. `valuesForRange` batches all requested Habit refs; SQLite repository chunks <=400 IDs. No per-day queries are necessary.

PR16 `A/focus/FocusCreditController.goalProgress` creates PR15 with live midnight delay/first weekday; its observers refresh detail/experimental views. This is not a repository-wide invalidation contract. `ContributionRepository.record/remove/removeById` have no common effective revision event. Direct insert/correction therefore need not refresh Habit list, Statistics, widgets or tray.

`A/inject/HabitsApplicationComponent.containerStatisticsService` creates PR15 with default calendar values, while ContainerStatisticsService defaults Monday/0. The service batches range values then calls goalProgressOn per Habit, causing additional range reads; it catches projection errors. Today passes live preferences but also needs to share the future preloaded goal evaluator. Neither service substitutes for global Statistics integration.

`Habit.recompute` currently O -> E -> S/T. Numerical E is a copy of persisted entries. `statisticsEntries` filters E by cutoff. Numerical originalEntries is stored/cached by SQLiteEntryList; Contributions are a separate immutable ledger. Neither should be overwritten by a projection.

## Why a scalar replacement is unsafe

1. **Manual write contamination.** HistoryCardPresenter.showNumberPopup and ListHabitsBehavior.onEdit initialize a manual editor from E. WidgetBehavior increments/decrements E. AddNumericalEntryOpCommand uses E as the current aggregate before writing Repetitions/EntryOps. Internal Container dialogs and legacy TimerSessionManager also read E before writes. Substituting manual+Focus into E would materialize Focus credit on the next edit/increment/timer delta. Every such path must retain original/manual seeds in the same integration stage.
2. **Score exactness.** ScoreList keeps the existing exponential recurrence `Score.compute`; numerical input is a rolling denominator window, not a calendar-week completion flag. It sums `max(0, entry.value)` using Int, so UNKNOWN becomes zero. A positive lower bound placed in scalar Entry cannot communicate incomplete coverage. Int overflow is another adapter boundary because PR15 totals are Long. Do not clamp silently or replace the scoring formula with a productivity score.
3. **Period completion conflict.** Habit.isCompletedOn AT_LEAST compares one day's quantity to the whole goal target, including weekly/monthly numerical goals. PR15 compares a calendar period. Example: three manual days 60+180+90 against >=300/week are below target individually but the PR15 period is SATISFIED. Blindly replacing the helper breaks demanded no-Contribution parity even before any credit exists. AT_MOST uses separate period handling and locale weekday. StreakList calls this helper from its date filter, so changing it propagates to streaks/filter/tray.
4. **Global report coverage/target conflict.** StatisticsOverview has a separate daily AT_LEAST policy. ReportStateBuilder.evaluatePeriod and evaluateHabitSlice separately sum entries, prorate target for SKIP and infer completed/missed/on-track with no coverage field. A partially known AT_MOST period can be shown complete. PR15 excludes SKIP quantity but does not prorate its calendar target. These report weighting/target policies must be separated from the canonical quantity/coverage authority before integration; simply injecting E would erase uncertainty or change legacy results.
5. **Dates and cache revisions.** Report ALL start/timeframe derives from O, so a contribution-only first day disappears. Main report cache increments dbVersion on HabitList observable, whereas widgets/tray use commands. A Focus-only observer cannot prove live correction convergence everywhere.

These are different domain adapters with data-loss and false-completion risks, not independent cosmetic replacements. Implementing them simultaneously before a tested contract would make parity, conservative UNKNOWN, source-only correction and manual-only editing hard to verify independently. This is the concrete basis for the section-30 split checkpoint.

## Proposed common architecture (not implemented)

`EffectiveHabitReadSnapshotProvider` builds immutable `EffectiveHabitReadSnapshot` per dataset/calendar/revision/range. The snapshot carries PR15 `EffectiveHabitDay` breakdowns, manual Entry/notes, active Contributions/source IDs, effective quantity, coverage, observed date domain, goal-period evaluations and separately typed score/streak results. A projected read-only Entry-like representation may serve compatible legacy algorithms; it must be clearly distinct from persisted facts and cannot expose write operations or serve as a manual editor seed.

Extend PR15 with a goal-evaluation overload consuming preloaded projected values. Extract/reuse its current period/coverage evaluation rather than duplicating unit checks, summing, goal-history resolution or bucketing. Batched range loading must cover the union of current/comparison/history/rolling-score windows. Goal evaluation after loading performs no Contribution SQL. Snapshot creation never writes Repetitions, EntryOps, SyncQueue, FocusSessions or rules.

Calendar context is captured once per snapshot: current timezone identity, live firstWeekday, midnight hour/minute delay. Preference/timezone changes invalidate that context. Canonical platform localDateAt/startOfLocalDay still own conversion and DST behavior. Do not add a new timezone conversion engine or hardcode Monday/0.

Date discovery needs a batched active Contribution domain query alongside manual O date bounds. Bucket the effective timestamps through PR15 calendar context; union both domains and apply statisticsStartTimestamp cutoff. Do not derive a domain from days returned only for a preselected manual range. No Habit x day SQL or unbounded getAll filtering on every UI frame.

Coverage rules remain PR15: UNKNOWN+credit is a lower bound; explicit manual zero is complete; derived zero is observed but incomplete; SKIP retains provenance/excludes goal quantity; Boolean Contributions fail typed. AT_LEAST lower bound proves completion only at/above target; AT_MOST proves failure above target, otherwise incomplete is UNKNOWN. Historical rules disabled later do not erase active credit; explicit tombstones do.

### Score/streak compatibility checkpoint inside PR23A

Keep Score.compute and the current rolling-window percentage formula for exact observations. Preserve exact legacy outputs in the no-Contribution compatibility path, including existing UNKNOWN/SKIP and Boolean behavior. With derived incomplete observations, return typed exactness/coverage rather than falsely exposing an exact scalar; establish conservative score presentation/gating before wiring UI. Do not guess a freeze/zero policy in this audit.

For numerical streaks with credit, consume canonical PR15 period status and do not extend a streak from an unproven lower bound. Boolean/no-credit outputs retain legacy parity. The weekly/monthly no-credit-versus-canonical conflict above requires an explicit tested compatibility contract, including removing the last active Contribution: no history-dependent stale switch, no hidden persistence and no changes to manual rows. If the user intends universal calendar completion even for manual-only histories, that is a deliberate exception to exact legacy parity and must be agreed explicitly. The current prompt does not resolve that contradictory case; this proposal does not silently choose one.

### Invalidation contract

One dataset-scoped effective revision signal after durable insert/tombstone/recovery/manual/goal/cutoff changes; no signal for failed/idempotent mutations. Coalesce/batch affected Habit UUIDs. Recompute effective models, resort/filter and notify through the common model path; list/detail/Statistics generation keys, widget refresh and tray completion consume the same revision. Calendar changes invalidate every affected snapshot. Recovery page commits publish after successful writes. Disposal unregisters listeners; callbacks cannot cross datasets. For caller-owned transactions publish only after commit, not after a savepoint that can still roll back. Tests must cover those rollback and listener ordering cases.

## Proposed bounded stages

| Stage | Scope / deliverables | Boundary and proof |
|---|---|---|
| PR23A | Common snapshot/provider + PR15 preloaded evaluator + domain query + live calendar + invalidation; list/filter/sort, Target/History/Bar/Frequency/Overview, typed score/streak; all computed-to-manual seed safeguards | DONE. Verified with 109 core JVM tests, 84 Android unit tests, clean compilation, SQLite batching queries, 9-point native QA on Pixel 6 (`emulator-5554`) disposable experimental dataset, and byte-for-byte baseline restoration. |
| PR23B | Global overview/report/calendar/trends/rhythm/grouping/insights/Compare; Container/Today shared context; widgets/tray/reminder completion; computed CSV/provenance; complete performance and cross-surface proof | IN_PROGRESS (Global Statistics checkpoint completed and verified; Compare, Widgets, Notifications, CSV remaining). Production runtime authority switch remains BLOCKED until PR23B is complete. |

PR23A is complete. PR23B Global Statistics checkpoint is complete. PR23 overall remains NOT DONE until PR23B completes. Production activation remains BLOCKED. PR24 and PR25 remain BLOCKED.

## Executed verification evidence for PR23A

- **Core JVM tests (100% PASS):** 109 tests across PR23A snapshots, typed adapters, PR15 `EffectiveHabitValueService`, `HistoricalGoalsTest`, `EntrySemanticsTest`, `EffectiveHabitTimezoneTest`, `HabitDayBoundaryTest`, `EffectiveHabitValueDurabilityTest`, `ContributionDurabilityTest`, `SourceRuleDurabilityTest`, `ListHabitsBehaviorTest`, `WidgetBehaviorTest`, `AddNumericalEntryOpIsolationTest`, `EffectiveReadRevisionTest`, `EffectiveHabitCardListCacheTest`, `EffectiveShowHabitPresenterTest`, `HistoryCardManualEditTest`.
- **Android JVM tests (100% PASS):** 84 tests across 18 suites including `ListHabitsBehaviorTest`, `TaskCalendarEventsTest`, `ShowHabitPresenterTest`, `ThemeSwitcherTest`.
- **Android build:** `compileDebugKotlin` and `assembleDebug` completed with exit 0 (`uhabits-android-debug.apk` successfully built and installed on emulator).
- **Query performance / batching:** `EffectiveReadBatchSQLiteTest` verified 1-query batching across all habits and calendar periods (0 per-day SQL queries).
- **Manual write isolation:** Verified in unit tests and on-device native QA: editing today from 60 to 40 yielded effective 310 min, updated `Repetitions` to 40000, generated 0 synthetic `Repetitions` and 0 synthetic `EntryOps`.
- **Sentinel collision:** Effective numerical value 3 kept as typed `Long` in `EffectiveHabitDay` and checked via `legacyQuantitiesRepresentable` to prevent accidental conversion to legacy `Entry.SKIP`.
- **Tombstone / invalidation convergence:** Live on-device tombstoning of 90-min contribution immediately updated visible progress from 310 min to 220 min without restarting the app.
- **Native QA on Pixel 6 (`emulator-5554`):** All 9 required scenarios verified on disposable experimental dataset with artifacts and screenshots captured (`pr23a_artifacts/qa-01` through `qa-12`).
- **Baseline protection & restoration:** Emulator database and preference files restored byte-for-byte from backup; `device_snapshot.py verify` confirmed 100% SHA-256 hash match across all 7 tracked files.

## Executed verification evidence for PR23B Global Statistics Checkpoint

- **Core JVM tests (100% PASS, 84 tests across 6 suites):**
  - `StatisticsEffectiveReportTest`: 9 tests PASS (no-contribution parity, contribution-only date discovery, lower bound proofs, AT_MOST limits, SKIP provenance exclusion, canonical fixture 330m -> 240m convergence, historical goals, cutoffs, weekday alignment).
  - `StatisticsReportStateBuilderTest`: 36 tests PASS.
  - `StatisticsOverviewTest`: 11 tests PASS.
  - `EffectiveHabitReadSnapshotTest`: 19 tests PASS (including `manualPlusCreditAndEditNeverCopiesDerivedQuantity` manual-write isolation regression test).
  - `EffectiveReadRevisionTest`: 7 tests PASS.
  - `EffectiveReadBatchSQLiteTest`: 2 tests PASS (500 habits over multi-month range use 4 indexed queries; global statistics report uses 0 per-day or per-goal queries after preloading; verified index usage on `Contributions`).
- **Android compilation & packaging:**
  - `compileDebugKotlin`: exit 0.
  - `assembleDebug`: exit 0.
- **Manual-write isolation invariant:**
  - Verified in `EffectiveHabitReadSnapshotTest`: `detachedPresentation()` populates only `projected.computedEntries`, leaving `projected.originalEntries` pristine so manual edit seeds derive strictly from original/manual entries.
- **Native Emulator QA on Pixel 6 (`emulator-5554`) with disposable experimental dataset:**
  - Statistics Activity opens cleanly and renders effective progress.
  - Day tab: PR23 Study displays 330 / 300 min (100% reached, ON_TRACK); PR23 Limit displays 20 / 60 min (0% PENDING due to incomplete coverage); level completion displays 33% (1/3).
  - All-Time tab: Discovers contribution-only earliest date (29 Sept 2026) from 45m focus session on prior Tuesday.
  - Canonical Fixture & Tombstone Convergence: After tombstoning 90m focus session, PR23 Study immediately updates to 240 / 300 min (80% PENDING) and level completion updates to 27% (0/3).
  - Reactive invalidation: `StatisticsActivity` observes `effectiveReadRevision` and calendar context, re-rendering reports on data mutation.
  - Baseline protection & restoration: All emulator database and preference files restored byte-for-byte; `device_snapshot.py verify` confirmed 100% SHA-256 match across all 13 files.

## Production isolation and follow-up

Static checks: Constants retains LEGACY_DATABASE_VERSION29 and allocated DATABASE_VERSION30. Production opener/gate unchanged. DatasetSession still distinguishes LEGACY_PRODUCTION from CONTAINER_LOCAL_EXPERIMENTAL. FocusCreditController and timer capture are constructed only for experiment; production capture remains null and legacy timer direct AddNumericalEntryOpCommand remains unchanged. No schema, authority epoch, SourceRule execution, sync, import/export implementation or runtime switch was modified. No canonical/device/user DB opened by this audit.

Remaining requirement: PR23B remaining scope (CompareHabitsActivity, widgets, notifications/tray, CSV export) must be completed before PR23 completion or production runtime activation. STOP after Global Statistics checkpoint; PR23B remains IN_PROGRESS; do not start PR24 or PR25.

