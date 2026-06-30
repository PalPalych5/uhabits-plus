# AGENTS.md

## Project

- `uhabits-plus` is a fork of Loop Habit Tracker / uhabits.
- Current product work covers Today, Habits, Statistics, Settings, Pomodoro/timer, and sphere/block colors.
- Do not turn this app into TickTick, Obsidian, a calendar, or a general task manager.

## Always read first

Before any task, read:

1. `docs/codex-project-context.md`
2. `docs/architecture-uhabits.md` if the task touches architecture, models, database, UI, entries, numerical habits, statistics, build, or MVP planning.

## Hard rules

- Do not change Gradle/AGP/Kotlin/dependencies/SDK unless explicitly asked.
- Do not run AGP Upgrade Assistant.
- Do not do broad refactoring.
- Do not change database schema or migrations unless explicitly asked.
- Do not modify score/streak/statistics formulas unless explicitly asked.
- Do not change `Entry.value` semantics.
- Do not change timer/Pomodoro or habit business logic unless explicitly asked.
- Prefer minimal, reversible changes.
- Separate facts from assumptions.
- If unsure, inspect files and report uncertainty.

## Current MVP direction

- MVP 0: repo builds/runs, architecture understood.
- MVP 1: Today screen / daily summary using existing `HabitList`/`EntryList`.
- Today Screen is a read-only dashboard / summary screen (no quick action buttons). Tapping on a habit navigates to the existing Loop detail screen.
- Use existing numerical habits for minutes and sets.
- No new database table for MVP 1.
- Keep Timer/Pomodoro changes task-scoped and use existing storage semantics unless explicitly asked otherwise.

## Important technical facts

- Modules: `uhabits-core`, `uhabits-android`.
- JDK/toolchain: 17.
- Android module: `uhabits-android`.
- Main list screen: `ListHabitsActivity`.
- Habit model: `Habit`.
- Entry model: `Entry`.
- Numerical entries use x1000 scaling: `45 min = 45000`, `3 sets = 3000`.
- `targetValue` is an ordinary `Double`: `45.0`, `3.0`.
- Existing `Repetitions` table stores one entry per habit/date, not multiple sessions.

## Workflow

- Before coding, explain the plan.
- Prefer small commits.
- For documentation files, because `*.md` is ignored, remind to use `git add -f`.
- After changes, summarize touched files, risks, and what was not changed.

## Communication

- Be concise by default. Do not write long walkthroughs unless the user asks for detail.
- Keep normal responses and final reports within 8-12 lines when practical.
- Final reports use: `Changed / Checks / Screenshots / Blockers / Next`.

## Token and command discipline

- Do not scan the whole repository. Use `rg` to locate symbols/files before reading them.
- Use `git diff --stat` and `git diff --name-only` to confirm scope.
- Do not paste large Gradle logs, diffs, or search output; report the decisive result.
- Use RTK for noisy read-only commands when available; otherwise use the raw command and summarize.
- Do not use RTK for `.env*`, `git checkout`, `git merge`, `git rebase`, conflict resolution, or detached-HEAD investigation.
- Use `magick` and `repomix` only when available. Repomix is optional and intended for large context packaging, not small UI fixes.
- Codex core tools are ready: RTK, `rg`, `npm`, `npx`, repomix, Android SDK `adb`, `magick`, Java, and `gradlew.bat`.
- Codex defaults to RTK + `rg` + `git diff` + targeted Gradle, with repomix only when needed.
- Aider and Headroom are installed and work outside the Codex sandbox, including Antigravity. Their direct `uv` launchers are blocked by Codex sandbox path canonicalization, so Codex must not rely on or try to repair them.
- Do not require Aider or Headroom for small bugfixes or UI corrective passes.

## Android scope

- Keep changes scoped to the requested feature or screen.
- Do not change Today, Habits, Statistics, or Settings unless the user explicitly puts that screen in scope.
- Do not change habit business logic unless explicitly requested.

## Icon policy

- Use Tabler-derived vectors for Settings row icons, bottom navigation icons, and any new toolbar icons.
- New app icons use Tabler as the single source.
- Do not mix packs or Material, hand-drawn, and Tabler glyphs within the same navigation, toolbar cluster, or UI surface.
- Prefer simple Tabler glyphs that stay legible at 24dp and tint correctly from theme attributes.
- No hand-drawn vector paths.
- If an existing production icon is not being migrated in the current task, leave it alone instead of introducing a mixed stopgap.

## UI tasks and visual QA

- A successful compile does not complete a UI task.
- Run screenshot QA only after a successful build.
- Do not run screenshot/MCP loops; use at most 5 screenshots per UI task.
- If screenshot or device/MCP access is unavailable, report it as a blocker and do not claim the UI is ready.
- If MCP quota is exhausted, report the blocker and stop UI QA.
- Do not present "manual verification was not performed" as a completed UI result.

## Color picker rules

- For color picker tasks, work only in color picker files unless another dependency is necessary and explained.
- Support Light, Dark, and AMOLED/Pure Black themes.
- Do not add debug labels, color indexes, extra words, or artificial demo labels.
- Center the selected checkmark inside its swatch.
- RGB/HSV sliders are continuous, without tick marks or dots.
- `Применить` / `Готово` buttons are neutral/outline, semibold, with `letterSpacing=0`.
- Fullscreen picker and custom color bottom sheet action rows must show both left cancel and right apply/done buttons in Light, Dark, and AMOLED.
- Resolve color picker theme attrs from the inflated picker view/dialog themed context, not from the host activity `requireContext()`, otherwise fullscreen/bottom-sheet action text or backgrounds can disappear.
- Keep left/right action controls on the same rendering path where practical; do not mix MaterialButton/AppCompatButton/TextView or XML tint/runtime ripple paths without proving both buttons render in all themes.
- AMOLED cancel/neutral action buttons need a visible non-black surface and border; pure black is for the screen background, not for tappable neutral controls.
- AMOLED uses pure black where intended.
- Reset/default returns the current sphere/block primary color, never hardcoded green.
- A saved individual habit color must not be overwritten by the sphere color.
- Initial picker color priority: individual habit color, then sphere primary color, then app default.

## Build and check policy

- Run at most 2 Gradle attempts per task and use targeted tasks only.
- Do not run full or instrumentation tests unless explicitly requested.
- UI/resource: `.\gradlew.bat :uhabits-android:assembleDebug --console=plain --quiet`.
- Android Kotlin-only: `.\gradlew.bat :uhabits-android:compileDebugKotlin --console=plain --quiet`.
- Core test: `.\gradlew.bat :uhabits-core:jvmTest --tests "<TestClass>" --console=plain --quiet`.
