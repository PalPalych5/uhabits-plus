# UHabit Tasks — canonical information architecture

Accepted by the user on 2026-10-05. Applies equally to Codex and Antigravity; this repository document, not a private agent brain or chat, is the long-lived source of truth. Read [UHABIT_NEXT_HANDOFF.md](UHABIT_NEXT_HANDOFF.md) for implementation state and current ownership.

```text
IA_APPROVED
VISUAL_LANGUAGE_NOT_FINAL
NATIVE_IMPLEMENTATION_ALLOWED
```

Information architecture is frozen until an explicit new user decision. No further HTML concept stage is required before PR10. HTML is not a pixel-perfect Android specification. Existing code describes implementation status; this document describes the accepted target.

## Domains and organization

`Habits != Tasks`. Existing UHabit remains an independent Habits workspace with its current tracker UI. Do not insert Container trees, task rows, Tasks Today or inline Container navigation into Habit list.

Tasks is an independent task-management workspace using the existing UserTask domain, plannedDate, deadline, subtasks, Container assignment and backup/restore. Containers organize Tasks and are shared organizational data, not a new parallel model. No combined Habits + Tasks feed in this stage. A future separate Container/Overview may aggregate Tasks, Habits, Focus and Statistics under its own approved scope.

## Phone shell and Tasks navigation

Phone global destinations: **Привычки | Задачи | Статистика**. Settings is a secondary/global menu entry, not a permanent bottom destination. Production legacy mode stays isolated and keeps its existing navigation unless separately authorized.

Tasks header names the selected smart view or Container: `☰ Сегодня` or `☰ Базы данных`. The hamburger opens the Tasks navigation drawer. Minimum smart views: Сегодня, Входящие, Предстоящие, Все задачи, Завершённые. Below them: recursive Containers, counts, selection and expand/collapse. Container hierarchy belongs in Tasks, not Habit list.

Visual tree indentation is limited (spike demonstrates three levels). Deep branches use a local tree context with an explicit parent return; current/parent context must remain understandable. Full path is available where needed. Do not expose infinite indentation or an IDE-style file tree. UUID identities, existing ordering and archive visibility remain authoritative.

## Wide shell

Available wide/expanded width replaces modal drawer with **persistent sidebar | content**. Sidebar unifies main workspaces, Task smart views and Containers. One navigation state is presented differently across phone, landscape/foldable and tablet; orientation alone is not the state model. Use the current Android Views/Fragment stack and available-window sizing. No Compose introduction solely for this stage. Three-pane detail is optional polish when it fits without material risk.

## Navigation versus representation

Navigation means **where** the user looks. Representation means **how** those tasks are shown. Approved representations: List and Calendar. `Базы данных · List` and `Базы данных · Calendar` use the same selected Container; switching preserves the smart-view/Container context and does not create a workspace. Container contexts include their organizational descendants using existing query/domain semantics.

## Dates and Inbox

`plannedDate != deadline`. Plan means when the user intends to work; Deadline means the objective due moment. A task may have either, neither, or both. Calendar exposes separate planning/deadline events, including two events for one task. Missed plan is not a hard overdue deadline. Calendar MVP may use a native calendar/date selector plus selected-day agenda, retaining an extensible representation boundary.

**Inbox = incomplete Task with containerId == null.** An undated task assigned to Базы данных is an unscheduled task in that Container, not Inbox. No universal replacement `date` field and no schema changes are needed.

## Native presentation and scope

Use UHabit palettes, theme/insets/navigation conventions for Light/Dark/AMOLED. Visual language is **ITERATIVE**: exact spacing, typography, radius, colors, calendar cell styling and animation duration are not IA contracts. Refine these on actual Android after native visual review.

List remains a calm, compact worklist without giant pills, card-per-task or rainbow metadata chips. Detail retains existing autosave and text flush, editable title/notes, Container, plan, deadline and subtasks; property rows open native pickers, deletion stays in overflow. Quick Add stays nearly invisible collapsed and offers compact Container/plan/deadline controls when expanded. Preserve drafts and context across recreation.

PR10 excludes recurrence, Matrix, Kanban, FocusSession, Contribution, combined dashboard, Task sync, Supabase, universal productivity scores, Habit/Statistics redesign, new Container backend and new Task model. Preserve production gate, UUID/data compatibility and existing backup/persistence.

## Evidence and completion

- [Adaptive spike](../ia_navigation_spike/adaptive/index.html)
- [Live browser QA report](../ia_navigation_spike/adaptive/qa/REPORT.md)
- [Screenshots/evidence directory](../ia_navigation_spike/adaptive/qa/)
- [Native PR10 shell QA](../pr10_artifacts/REPORT.md)
- [Native PR10.1 visual refinement / selected Compact](../pr101_artifacts/REPORT.md)
- [Native PR10.1 screenshots/video](../pr101_artifacts/index.html)

**Spike is a design/evidence artifact, not production implementation.** Browser QA does not prove native behavior.

PR10 must build an APK, pass relevant tests and native runtime checks, verify portrait/landscape/expanded widths, themes, drawer/list/calendar/context/state/back, independent date cases, detail/quick capture, completed states, Habits isolation and persistence. Provide actual Android screenshots and a short navigation video. Record exact gaps rather than treating a build as visual proof. Update handoff and STOP for user native visual review; do not start PR11 automatically.
