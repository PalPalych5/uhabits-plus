# Iteration 19 source dependencies and observable contracts

Read-only source audit of current candidate, 2026-10-15. This document is source evidence and a correctness map, not an acceptance decision or presentation benchmark. Line references are repository-relative, captured after addition of `onEntriesChanged()`.

## Bounded correction and call path

`uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/commands/CreateRepetitionCommand.kt:73` invokes `habitList.onEntriesChanged()` only after the existing persisted entry write, habit recompute and list-instance refresh. Its no-op guard and sync queue ordering remain unchanged.

`uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/HabitList.kt:223` defaults the new operation to general `resort()`, so other implementations keep conservative behavior. `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/sqlite/SQLiteHabitList.kt:315` delegates entry change to its memory list and preserves wrapper observable notification. `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/memory/MemoryHabitList.kt:385` skips only root sorting when both active keys are entry-independent; a direct call on a filtered child remains conservative and sorts. No broad cache, write transaction or optimistic UI change is introduced.

The ordinary SQLite resort at `SQLiteHabitList.kt:320` still reloads blocks, forwards the facade, invokes memory resort, and notifies the wrapper. The ordinary memory resort at `MemoryHabitList.kt:399` still clears sphere caches, sorts, and notifies. Metadata, organization and external recovery callers must retain that general operation. Equal setting values alone never establish that comparator input data is unchanged.

## Dependency matrix

| Event | Parent order | Membership | Independent child order | Observable/UI contract |
| --- | --- | --- | --- | --- |
| Daily or historical entry edit, stable primary AND secondary | Entry mutation does not alter stable keys; root order can be preserved | Recheck completion and entered state | Existing rebuild sorts every child whose order context differs | Deliver memory, descendant and SQLite-wrapper listeners; command completion refreshes affected card |
| Entry edit, score/status primary OR secondary | Sort; ties under a stable primary can expose sensitive secondary | Recheck | Keep existing child sorting | Same notification and card-refresh delivery |
| Numerical value edit without completion change | Status comparator may still change by quantity | Entered membership may change | Keep independent sorting | Must refresh actual value/score/notes |
| Historical entry edit | Today's score may change despite today's raw entry being unchanged | Recomputed completion semantics still apply | Keep independent sorting | Full refresh fallback for sensitive ordering |
| Habit metadata/name/color/position/tier change | General resort; comparator inputs may change | Search/archive/reminder predicates may change | Preserve independent keys and tie order | Existing update notifications |
| Block/root/container placement or hierarchy mutation | General grouped resort and sphere cache invalidation | Current HabitMatcher contains no container predicate | Group-aware child can change even with same facade identity | Preserve organization and list refresh paths |
| Reload after rollback or authoritative replacement | Rehydrate authoritative habits, then retain recovery/general ordering semantics | Rebuild against fresh identities | Do not reuse previous dataset state | Existing reload and dataset lifecycle remain necessary |

## Comparators, filters, children and reentrancy

All comparator code is `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/memory/MemoryHabitList.kt`. Composed primary/secondary application is at `:232`. Score comparators at `:259` read today's computed scores; status comparators at `:268` read completion, numerical type and today's computed quantity. Both primary and secondary must be checked. Existing score direction naming is retained as implemented, including its apparent inverse numerical ordering.

Stable entry keys are position, name, color, day tier and sphere/group. Sphere at `:90` resolves root placement/order and then habit position/name; day-tier ordering also contains position/name tie-breaks. Entry-only mutation changes none of those inputs. This is a scoped source inference, not a guarantee about arbitrary concurrent metadata or organization mutation.

`uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/HabitMatcher.kt:33` and `:34` exclude completed or entered habits when requested. Other predicates concern archive, reminders and searchable metadata. Therefore preserving order cannot mean suppressing parent notifications or retaining stale child membership.

`MemoryHabitList.kt:158` subscribes each filtered child to its parent observable. `:367` rebuilds membership on every notification. `:358` checks matching primary/secondary/facade identity/day-tier context; matching children inherit the freshly filtered parent's ordering, and differing children retain their own resort. Nested children repeat this propagation. An independent entry-sensitive child still sorts when its stable parent skips sorting. Equal comparator keys retain the existing stable-sort tie behavior; the candidate does not invent a replacement tiebreaker.

`uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/models/ModelObservable.kt:47` delivers callbacks synchronously under its monitor (`:50` iteration). Memory notifications remain under the same list synchronization. Reentrant callbacks can update properties, change sort settings or invoke ordinary resort; no suppression, deferred delivery, listener snapshot or new locking contract is introduced. Detachment at `MemoryHabitList.kt:177` still removes the parent listener outside the child monitor, preserving the earlier lock-order safeguard.

## View models and effective reads

`uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/ui/screens/habits/list/HabitCardListCache.kt:244` listens to command completion separately from list notifications and requests affected-habit refresh. At `:276` a targeted refresh falls back to full refresh for in-flight tasks, completion/entered filtering, or score/status in either order. Existing effective snapshot freshness, generation fences and publication behavior remain unchanged. Thus skipping stable root comparison does not eliminate committed card-state refresh. SQLite/effective/UI parity still requires fresh runtime verification; source alone cannot establish it.

## Test evidence scope and limits

The historical Iteration 18 `uhabits-core/src/jvmTest/java/org/isoron/uhabits/core/models/MemoryHabitListResortTest.kt:21` fixture uses 42 habits and checks one parent resort, 42 sorted visits/root-cache misses, and preserved child notifications. This is deterministic test work accounting, not runtime duration and not a fresh Iteration 19 run.

The candidate's focused `HabitListEntryChangeTest.kt:34` checks zero memory resort calls and zero composed comparisons while preserving parent/child delivery. That fixture has two habits and no real container facade, so its asserted zero comparisons must not be described as a measured 42-habit SQL reduction. Source implies no root lookup from the skipped root comparator; actual database work reduction still requires instrumented or trace evidence. Other tests in the same file cover grouped ties, same-facade root reassignment, sensitive secondary keys, nested completion/entered filters, independent child ordering, reentrancy, numerical status quantities, historical score updates and detached identity isolation. Obtain passing counts from the root agent's actual test outputs, not this audit.

`SQLiteHabitListEntryChangeTest.kt:17` compares command listener delivery and fresh SQLite entry/score/order reads. `:41` uses an actual SQLite rollback followed by explicit existing `reload()` and verifies fresh identities/membership. This does NOT test automatic cache repair merely from `onEntriesChanged()`. `:71` uses separate SQLite databases with the same habit UUID and checks isolated listeners and notes; it does NOT exercise actual DatasetSessionManager cutover, Android lifecycle or rollback listeners throughout the effective-read controller. Those boundaries remain limitations until separately observed.

## Accepted safeguards retained

Iteration 17's root-only parent traversal remains at `uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/sqlite/SQLiteOrganizationStore.kt:523`; the candidate does not alter traversal, missing ancestors, cycle protection or placement authority. Iteration 18's property equality guards, one general parent sort, matching-child order inheritance and independent-child resort remain intact. General resort still clears caches for changed organization data even when facade identity and sort settings are unchanged.

The existing probes-off CPU/scheduling attribution is recorded separately in `attribution/perfetto_analysis_report.md`. It establishes approximately 102–111 ms median scheduled CPU inside historical grouped resort envelopes, plus runnable delay; it does not isolate SQL/comparator/observer CPU. No ACCEPT/REJECT/INCONCLUSIVE optimization verdict is made here.
