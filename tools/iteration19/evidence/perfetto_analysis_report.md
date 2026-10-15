# Iteration 19: existing probes-off resort attribution

Question: what CPU and scheduling work remains inside the accepted Iteration 18 candidate's resort envelope, before testing an entry-specific order-preserving path?

Sources: `scratch/iteration18/runs/iter18-90g-c1.perfetto` (39.26 MB) and `iter18-1825g-c1.perfetto` (41.61 MB). These are historical candidate runs, not fresh Iteration 19 measurements or acceptance evidence. Detailed comparator diagnostics were not enabled. Processor: skill-pinned Perfetto v58.2-add693d8b. Analysis adds no on-device probes.

Exact intervals: positive-duration `habit.resortStage.N` slices joined through `thread_track.utid` to interval-overlapping `thread_state`. Running is scheduled guest CPU; R and R+ are runnable but not scheduled; S is interruptible sleep. Every one of the 40 stages has complete state coverage (maximum arithmetic residual below 0.000001 ms). No presentation-window or input endpoint join is involved.

| Existing run | Stages | Resort wall median/max ms | CPU median ms | Runnable median ms | Sleep median ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 90g-c1 | 20 | 161.212 / 355.178 | 102.370 | 63.087 | 0 |
| 1825g-c1 | 20 | 156.368 / 400.744 | 111.421 | 52.761 | 0 |

Across all stages, 90g totals are 3666.006 ms wall = 2058.888 CPU + 1460.506 runnable + 146.613 sleeping. For 1825g, 3534.790 ms wall = 2232.152 CPU + 1180.830 runnable + 121.809 sleeping. Medians are computed independently and must not be added. Guest CPU overlaps do not establish host physical CPU utilization or distinguish guest execution from host scheduling effects.

Exploratory warm suffix, ordinals 6–20 (not a replacement for the full run): 90g wall/CPU/runnable medians 137.001/91.101/35.976 ms; 1825g 153.529/110.520/48.687 ms. This illustrates why warm-up and run-level variation must be retained in fresh comparisons.

Direct nested slices explicitly identify **JIT code cache contention**, with inclusive totals 369.037 ms across 363 slices (90g) and 321.904 ms across 306 slices (1825g). FullSuspendCheck totals are 19.581 and 26.511 ms. These nested intervals overlap the scheduling accounting above; they are not additive exclusive lock-wait totals. In particular, no inference is made that all S or runnable time is a lock wait.

The probes-off traces do not expose named comparator, root-lookup SQLite, filtered rebuild, or observer callback spans beneath this envelope. Their separate CPU/wall costs are **UNKNOWN** from these traces. No SQLite wait or model-monitor contention is established. Existing source audit supplies dependency structure, not quantitative substage attribution. The direct child query has no SQLite/observer slices; that does not prove zero SQL or observer work.

Limitations: both loader logs report `sorter_push_event_out_of_order: 8`. Preserve this trace-health caveat. These two runs cannot establish reproducibility across independently repeated counterbalanced runs. A fresh candidate must reduce actual work and preserve correctness, then demonstrate presentation results with expensive probes off.

Re-run `states.sql` and `nested.sql` through the skill wrapper's `query -f FILE TRACE`, then `python scratch/iteration19/attribution/summarize.py`. Exact CSV outputs, loader logs, per-stage results and summary JSON are adjacent. Windows warm-session attempts failed because daemonize is unsupported and the short socket could not bind; all attempts exited. One-shot traces were processed sequentially. Initial broad nested self-join in `resort.sql` was interrupted due excessive query cost; `90g-c1.csv` is partial and **must not be used**. The validated bounded replacements are `states.sql` and `nested.sql`.
