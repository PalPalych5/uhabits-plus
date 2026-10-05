# Adaptive App Shell + Tasks visual system spike

Open `index.html` directly in a modern browser. All assets are local; there are no packages, external fonts, network requests, backend calls or storage writes. An optional local preview server is running at http://127.0.0.1:8766 for this session.

## Accepted architecture and scope

One adaptive architecture replaces the A/B decision:

- Below 680 CSS px of app content width: three global bottom workspaces (Habits / Tasks / Statistics), Tasks-only modal drawer, secondary Settings entry.
- At least 680 CSS px: persistent global sidebar with Tasks smart views and container tree; no global bottom navigation.
- List / Calendar remain representations of the same Tasks context.
- Habits and Tasks remain separate domains; shared Containers provide organization. This prototype changes no model or native implementation.
- Habits and Statistics are destination placeholders, deliberately outside visual evaluation.

The presets are 412×824 portrait, 844×480 landscape / foldable, and 1120×780 tablet. The middle preset demonstrates a wide foldable or landscape layout with sidebar. A narrower landscape window would retain the phone shell: available width, not orientation, determines navigation. Breakpoints are design proposals, not measured Android WindowSizeClass decisions. The preview scales to fit its surrounding browser panel; that scale does not alter the preset's logical app width.

## Visual language

- Single built-in geometric SVG language: 24×24 viewBox, stroke 2, round caps and joins. No emoji or text-symbol system icons. Paths are declared in `app.js`; no icon CDN.
- White reading surface, slate secondary navigation, restrained blue selection; corresponding Dark and AMOLED tokens in `styles.css`.
- Mobile header 29px, task titles 16px, metadata approximately 13px; primary completion and toolbar actions have 48px targets. Curated icon use and thin separators replace per-task cards.
- Tree indentation stops at three visible levels. The last level offers a local-tree drill control; a parent action returns outward. A seven-level fixture demonstrates this without expanding the indentation budget.
- Calendar cells show abbreviated task titles plus outlined circle for plan / filled diamond for deadline. Overflow is indicated with a count. The selected day drives the agenda while retaining context. A task can have two distinct events, including two events on the same day.
- Task detail is a phone bottom sheet, a compact-wide side dialog, and a right detail pane on tablet. Existing fields only: completion, title, notes, container, planned date, deadline with time, subtasks.
- Docked Quick Add expands for existing date/container fields. Main and subtask drafts survive size/theme rerenders. Explicitly clearing a plan or choosing Inbox does not silently restore the current context default.
- Short drawer/sheet/content transitions and reduced-motion support; no gesture implementation.

## Content and interactions

Try Today → Tasks drawer → Study → University tasks → Databases → Calendar → select October 7 → open BD → edit plan / deadline separately → complete a subtask → close detail → Quick Add. Change size while composing a task or viewing a context. Drill Databases → Laboratories → Lab 4 → preparation → questions to inspect local navigation, then return to the parent.

Task titles/subtask text were taken from the supplied TickTick analysis, not directly from original screenshots. Dates, notes, extra tasks, and the deep hierarchy are illustrative. Demo clock is fixed at October 5, 2026, 09:41. A missed plan remains a planning event, not a hard overdue deadline. Today is a saved smart filter: switching that context to Calendar shows the filtered tasks' separate plan/deadline events. There is no Matrix, Board, recurrence, deletion, sync, focus-time synthesis or new native feature.

## Verification and outstanding work

Performed October 5, 2026:

- Node syntax check of `app.js` passed.
- Source-level checks passed for ancestor/descendant counts, seven-level local tree, retained context, separate calendar events, plan/deadline independence, explicit empty plan/Inbox selection, draft templates and the three layout predicates.
- Independent read-only review checked current Android resources/fields and the new source. The identified draft, tree-toggle and calendar-cell overflow defects were corrected.
- `git diff --check` passed. The old A/B spike remains unchanged. Android/backend/Gradle code was not changed; an Android build would not verify this browser deliverable.

After user-authorized retry, the browser opened successfully. **VISUAL_QA_COMPLETE_FOR_REVIEW — STOP for user review.** See [screenshot gallery](qa/index.html), [live QA report](qa/REPORT.md), and [DOM observations](qa/evidence.json).

Live rendering covered List and Calendar across all three sizes and Light/Dark/AMOLED, requested container/deep-tree flows, independent date edits, subtasks, draft retention, completed/empty states, detail at all three sizes, overflow and Tab/Escape. Real visual/focus defects were corrected and rechecked; details and remaining concerns are in the report. Selected contrast tokens were measured; this is not a complete accessibility audit.

Runtime reduced-motion preference could not be enabled with the available browser capabilities and remains unverified; ordinary-motion rendering was checked. Native Android rendering, IME/insets, gestures, TalkBack, WindowSizeClass breakpoints and device behavior remain unverified. Habits/Statistics are navigation placeholders. The old A/B screenshots do not count as evidence for this adaptive version. No native changes, commit or publication were made.
