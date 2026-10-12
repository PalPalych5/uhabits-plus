import json
import math
import sys
import os

path = sys.argv[1] if len(sys.argv) > 1 else "scratch/habit-interaction-lag/benchmark-baseline.json"
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)

variant = data.get("variant", "unknown")
habit_count = data.get("habitCount", 0)
total_interactions = data.get("totalInteractions", 0)
interactions = data.get("interactions", [])
frames = data.get("choreographerFrames", [])
events = data.get("events", [])

print(f"==================================================")
print(f"BENCHMARK ANALYSIS: {variant}")
print(f"Habits: {habit_count}, Recorded Interactions: {total_interactions}")
print(f"Choreographer Frames: {len(frames)}, Event Samples: {len(events)}")
print(f"==================================================")

def stats(vals):
    if not vals:
        return {"count": 0, "median": 0, "p95": 0, "p99": 0, "max": 0, "stalls_100": 0, "stalls_500": 0, "stalls_1000": 0}
    vals_s = sorted(vals)
    n = len(vals_s)
    p50 = vals_s[n // 2]
    p95 = vals_s[int(n * 0.95)] if n >= 20 else vals_s[-1]
    p99 = vals_s[int(n * 0.99)] if n >= 100 else vals_s[-1]
    max_v = vals_s[-1]
    return {
        "count": n,
        "median": p50,
        "p95": p95,
        "p99": p99,
        "max": max_v,
        "stalls_100": sum(1 for v in vals if v >= 100.0),
        "stalls_500": sum(1 for v in vals if v >= 500.0),
        "stalls_1000": sum(1 for v in vals if v >= 1000.0)
    }

# 1. Main Thread Heartbeat Stalls
heartbeats = [e["ms"] for e in events if e.get("stage") == "main.heartbeatDelay"]
hb_stats = stats(heartbeats)
print("\n--- Main-Thread Heartbeat Delay (Stall Indicator) ---")
print(f"Count: {hb_stats['count']}, Median: {hb_stats['median']:.2f} ms, p95: {hb_stats['p95']:.2f} ms, p99: {hb_stats['p99']:.2f} ms, Max: {hb_stats['max']:.2f} ms")
print(f"Stalls >= 100ms: {hb_stats['stalls_100']} ({hb_stats['stalls_100']*100/max(1, hb_stats['count']):.1f}%)")
print(f"Stalls >= 500ms: {hb_stats['stalls_500']} ({hb_stats['stalls_500']*100/max(1, hb_stats['count']):.1f}%)")
print(f"Stalls >= 1000ms: {hb_stats['stalls_1000']} ({hb_stats['stalls_1000']*100/max(1, hb_stats['count']):.1f}%)")

# 2. Choreographer Frames
cf_stats = stats(frames)
dropped_16 = sum(1 for f in frames if f > 20.0)
print("\n--- Choreographer Frames ---")
print(f"Count: {cf_stats['count']}, Median: {cf_stats['median']:.2f} ms, p95: {cf_stats['p95']:.2f} ms, p99: {cf_stats['p99']:.2f} ms, Max: {cf_stats['max']:.2f} ms")
print(f"Dropped Frames (>20ms): {dropped_16} ({dropped_16*100/max(1, cf_stats['count']):.1f}%)")
print(f"Severe Frame Drops (>=100ms): {cf_stats['stalls_100']}")
print(f"Severe Frame Drops (>=500ms): {cf_stats['stalls_500']}")
print(f"Severe Frame Drops (>=1000ms): {cf_stats['stalls_1000']}")

# 3. Interactions by Scenario
scenarios = {}
for i in interactions:
    s = i.get("scenario", "unknown")
    if s not in scenarios:
        scenarios[s] = {"tapToDraw": [], "handler": []}
    if i.get("tapToDrawMs", -1) >= 0:
        scenarios[s]["tapToDraw"].append(i["tapToDrawMs"])
    scenarios[s]["handler"].append(i.get("handlerMs", 0))

print("\n--- Tap Latencies by Scenario ---")
for s, d in sorted(scenarios.items()):
    td = stats(d["tapToDraw"])
    h = stats(d["handler"])
    print(f"Scenario [{s}]: (n={td['count']})")
    print(f"  Tap-to-Draw: Median={td['median']:.2f} ms, p95={td['p95']:.2f} ms, p99={td['p99']:.2f} ms, Max={td['max']:.2f} ms, >=100ms: {td['stalls_100']}, >=500ms: {td['stalls_500']}, >=1s: {td['stalls_1000']}")
    print(f"  Handler:     Median={h['median']:.2f} ms, p95={h['p95']:.2f} ms, Max={h['max']:.2f} ms")

# 4. Correlated Event Stages
stages = {}
for e in events:
    stg = e.get("stage", "other")
    ms = e.get("ms", 0.0)
    stages.setdefault(stg, []).append(ms)

print("\n--- Correlated Pipeline Stages (sorted by Max ms) ---")
for stg, vals in sorted(stages.items(), key=lambda x: max(x[1]), reverse=True)[:25]:
    st = stats(vals)
    print(f"Stage [{stg}]: count={st['count']}, median={st['median']:.2f} ms, p95={st['p95']:.2f} ms, max={st['max']:.2f} ms, >=100ms: {st['stalls_100']}, >=500ms: {st['stalls_500']}")

