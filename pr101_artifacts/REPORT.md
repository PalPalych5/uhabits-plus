# PR10.1 — Native Tasks visual refinement

Date: 2026-10-06, Asia/Novosibirsk. Status: **NATIVE_VISUAL_REVIEW_PENDING**. Implementation, scoped checks and emulator QA completed; STOP for human visual review.

## Scope and decision

The user reviewed three actual Android Today variants and selected **Compact**: [Compact](variant-compact.png), [Balanced](variant-balanced.png), [Editorial](variant-editorial.png). The temporary internal capture selector and alternative profile code are removed. Final Compact metadata gaps follow the native 4dp rhythm.

IA remains fixed: phone bottom workspaces with Tasks drawer; expanded width persistent sidebar; List / Calendar share the current Tasks context. Habits and Statistics retain their existing native screens. No task domain, database schema, sync, backup format, or new feature changes. Experimental inactive bottom navigation uses the existing secondary palette at full opacity for readable labels; the legacy palette/opacity branch is preserved.

## Refinements

- Compact task rows establish title, subdued container/subtask context, and independent planned-date/deadline metadata. Missed plan and hard overdue retain separate wording and restrained warning colors.
- Drawer uses consistent vectors, aligned counts, a calm selected row, section dividers, and bounded indentation/local context. A clean completed-task vector replaces the malformed imported path.
- Calendar improves month/day hierarchy, selected-day treatment, and agenda rows. Phone cells use distinct plan/deadline markers; wider cells expose task titles. Same-month updates preserve scroll position, and cell heights accommodate larger text.
- Detail reduces unnecessary spacing, keeps native property pickers and autosave, and softens subtask trash controls. Title, notes, date clearing, completion, and subtasks retain their existing roles.
- Quick Add uses matching vector actions and retains draft text/properties through recreation. Working content is bounded on wide screens rather than stretching across the full window; relative padding improves RTL handling.

## Evidence and observed checks

Actual native captures include [phone Today](01-phone-today.png), [drawer](02-phone-drawer.png), [Container list](03-phone-container.png), [calendar](04-phone-calendar.png), [detail](05-phone-detail.png), [Quick Add](06-phone-quick-add.png), [landscape draft](09-landscape-draft.png), [tablet sidebar/list](10-tablet-sidebar-list.png), [tablet calendar](11-tablet-calendar.png), [tablet agenda](12-tablet-agenda.png), and [tablet detail](13-tablet-detail.png). Main captures were refreshed after the icon/contrast fixes. The last resource-only Quick Add gap adjustment affects the recaptured composer; other unchanged surfaces reuse valid captures. [Gallery/video](index.html).

- Final `assembleDebug` PASS; Android SmartView 6 + CalendarEvents 3 + ContainerScope 4 tests PASS. Last resource-only attachment-gap refinement rebuilt successfully; unaffected test results reused. Core/schema/backup code is unchanged; PR10 core verification remains applicable, not falsely reported as a fresh PR10.1 run.
- Phone (~411×914dp): Light/Dark/AMOLED; landscape (~914×411dp): Light; tablet (1200×800dp): Light/Dark/AMOLED. Matrix is explicitly scoped, not a claim that every theme/width permutation was checked.
- List/Calendar preserve Container context; smart views/counts, empty List, Completed/List, local depth through Вопросы and parent Back, detail on phone/landscape/tablet (landscape checklist/add reachable by scrolling), Quick Add and subtasks exercised. Completed tasks without a Container say «Без раздела», not Inbox; unavailable Container detail no longer exposes a UUID.
- [RTL tablet calendar](qa-tablet-calendar-rtl.png) and [150% font tablet calendar](qa-tablet-calendar-font150.png) checked; overflow «+3 ещё» remains visible. This is not comprehensive RTL/font-scale coverage of every flow.
- Independent date clears confirmed in disposable experimental DB snapshots: plan cleared with deadline retained; deadline cleared with plan retained. Original notes are preserved. The earlier architectural sentence was QA fixture content, replaced with an ordinary task description in the new fixture.
- Draft survives portrait/landscape/tablet recreation and confirmed background process death: PID 21256 → no process → 21500, `ProcessDraft` unchanged. [Process proof](process-proof.json).
- Native [81-second recording](pr101-native-flow.mp4) completes Habits → Tasks → drawer → Учёба → Базы данных → Calendar → List → Detail → Back; recorder PID still alive at completed flow. QA pauses are not animation benchmarks.
- Final crash log empty. Transient missing UI roots during API37 startup and an initial launcher capture were retried. Two draft-check attempts were rejected as proof (PID unchanged / hidden draft), and a duplicate-text selector was tightened; only the confirmed run is evidence.
- Production DB byte-identical before/after; original experimental DB, session/preferences byte-identically restored. Native size/density, rotation, font scale and app locale restored. Private backups/snapshots/preferences stay local and are excluded from Git. [Evidence](evidence.json).

## Reference limits and remaining weaknesses

The inspected TickTick references are annotated iOS help illustrations in `pr92_design/sources/`: drawer, editor, and composer. They support hierarchy and presentation observations, not Android dp measurements, current-version parity, or a direct comparison with the user's unavailable original screenshots.

Remaining visual limits are explicit: the phone calendar uses markers rather than in-cell titles, with readable task content in the agenda; empty wide calendar cells remain sparse; wide detail remains a sheet rather than a dedicated pane; inactive bottom navigation contrast was corrected in the experimental shell. Remaining hierarchy question: Today still dedicates separate secondary lines to organization and dates, so the richest rows are taller than simple tasks. These are subjective review points, not evidence of lost task functionality.

Physical-device behavior, comprehensive TalkBack/RTL/large-text coverage, and measured animation/frame timing are unverified. Technical checks are complete within the recorded emulator scope. Final visual language approval belongs to the user. STOP; no PR11.

Source reference attribution: [TickTick Add Tasks](https://help.ticktick.com/articles/7055782422935240704), [Task Details](https://help.ticktick.com/articles/7055782408586526720). Existing local reference illustrations were inspected; no live TickTick Android comparison is claimed.
