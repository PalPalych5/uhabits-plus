# PR10 — Native Adaptive Tasks Shell QA

Date: 2026-10-05. Status: native review candidate; visual language remains iterative.

## Implementation

- Experimental phone shell: Habits / Tasks / Statistics; Settings secondary through Habits menu, Statistics toolbar and Tasks menu/drawer.
- Tasks modal drawer on compact width; unified persistent sidebar from 600dp. Five smart views, counts, selected Container, collapse/expand and three-level local tree.
- List / Calendar preserve smart-view / Container context. Calendar MVP provides month navigation, selected date, independent Plan / Deadline events and daily agenda.
- Existing native autosave detail uses tappable Container / Plan / Deadline rows, native pickers, notes and subtasks. Wide detail remains a sheet; three-pane detail is deferred.
- Quick capture retains its draft and properties across recreation. Legacy production mode cannot enter Tasks even through an incoming destination intent.
- Task domain, schema, sync and backup formats are unchanged. No Compose, recurrence, Focus or combined feed introduced.

## Checks and device

Final `:uhabits-android:assembleDebug` passed. Focused Android tests: SmartView 6, CalendarEvents 3, ContainerScope 4; zero failures/errors. Core tests: UserTaskDomain 4, SQLiteUserTaskRepository 1, ContainerBackupRestore 3; zero failures/errors. Total: 21 focused tests. No full/instrumentation suite run.

Runtime: Pixel 6 emulator, Android 17 / API 37, emulator-5554. Phone 1080×2400 at 420dpi (~411×914dp), landscape 2400×1080 (~914×411dp), tablet override 1800×1200 at 240dpi (1200×800dp). These are native device sizes, not the HTML presets. List / Calendar inspected in Light, Dark and AMOLED at all three sizes. APK installed successfully.

The screenshots use a disposable experimental fixture copied from the stopped emulator database. Fixture includes nested Study containers, planned-only, deadline-only, both dates, missed plan with future deadline, genuine overdue, undated assigned task, subtasks and completed task. Fixture dates are illustrative; deadline timestamps display in the emulator timezone. Original data and preferences are backed up locally and excluded from Git.

## Live walkthrough

- Habits preserved as classic tracker; phone three workspaces, secondary Settings entry and Statistics inspected.
- Today → drawer → Study → university → databases; selected section becomes header. Direct selected-container tasks and descendant tasks are included.
- Deep tree reaches Questions, then Back returns to its parent local context. At most three indentation levels; empty section and Completed inspected.
- List ↔ Calendar preserves selected section; date selection updates agenda. Plan and deadline remain separate events.
- Native detail opened on phone, landscape and tablet. Subtask toggle changed 1/3 to 2/3. Clearing plan retained deadline; restoring plan and clearing deadline retained plan, confirmed in persisted SQLite copy. Native deadline picker changed 7→8 October without changing plan.
- Quick Add supplied Container, plan and deadline; submission persisted all three through existing task service (SQL evidence in evidence.json).
- Quick Add text retained through rotation and confirmed background process death: PID 9764 → no process → PID 10007. Container and Calendar representation also restored. See process-*.json.
- Opening drawer while composing now hides IME and preserves draft; final `mInputShown=false`, `mImeWindowVis=0`. Drawer and calendar content scroll on short landscape height.

## Defects found and corrected

1. ContainerQueries.subtreeIds excludes the selected root: direct tasks disappeared from selected Container and counts. Added shared scope helper including root and active descendants plus four regression tests; live databases now show four tasks instead of one.
2. List metadata previously chose plan over deadline. Both are now displayed independently, with distinct missed-plan and actual-overdue semantics; day/time changes invalidate rows correctly.
3. Modal drawer attached after initial insets overlapped status bar. Request fresh insets on attach; final drawer captures inspected.
4. Light status icons lacked contrast in experimental Habits. Apply palette-dependent system-bar appearance.
5. Existing subtask check/delete hit areas were 28dp. Increased to 48dp; detail inspected on final APK.
6. Composer keyboard covered lower drawer destinations. Clear input focus and hide IME while retaining draft; final native reproduction passed.
7. Rapid width recreation could execute a destroyed Activity's posted startup intent or tab-animation completion, crashing at setMaxLifecycle. Guard destroyed hosts, invalidate transition tokens and cancel animations during teardown; original Settings→Statistics→width route repeated on final APK. Both diagnostic crash-before logs are preserved; crash-after log records the recheck.
8. The same route exposed Settings' posted chrome refresh running after its Fragment detached. Run it only while attached and while the original view is still current; no Settings feature/design change.
9. A launch destination extra replayed after recreation, replacing restored Statistics with the originally launched Habits. Consume destination intents once; saved navigation state wins on recreation. Fresh onNewIntent destinations still work.

## Evidence and review limits

[Screenshot/video gallery](index.html). Required captures 01–11 plus Habits, deep tree, completed, empty, wide detail, IME correction and full theme matrix. Core captures were taken after their relevant visual fixes; later lifecycle-only changes do not invalidate those pixels. Diagnostic JSON from earlier walkthroughs can contain pre-fix counts; final-* files and required captures contain corrected scope counts.

Calendar is intentionally an MVP: marker-based month and agenda, no task titles in month cells. In landscape the month and agenda require scrolling. Drawer hierarchy works but still needs visual rhythm/icon refinement; wide task detail is a centered sheet. No claim of pixel-perfect TickTick parity or final visual approval.

Legacy `TASKS` intent was rejected: Habits / Statistics / Settings remain, Tasks absent. Every production table, row and schema definition equals the pre-QA inventory; schema remains v29 and no Task tables were created. Original experimental database, dataset preferences and session preferences were restored byte-for-byte; wm overrides, rotation and temporary offline setting were restored. See [evidence.json](evidence.json) and [legacy capture](12-legacy-isolation.png). Final crash log is empty.

The recorded native walkthrough is 61 seconds. It covers Habits → Tasks → drawer → Study → databases → Calendar → List → detail → Back.

RTL, large font scaling, TalkBack, physical foldable/device behavior and measured frame performance were not verified. Emulator QA is not physical-device proof.

STOP for user native visual review. Do not start PR11 automatically.
