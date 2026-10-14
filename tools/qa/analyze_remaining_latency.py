"""Summarize bounded end-to-end latency evidence without guessing presentation.

Instrumentation timestamps are kept separate from the Perfetto clock. Optional
presentation JSON must come from analyze-operation-trace.py's exact token join.
Counts describe recorded events; repeated events are never silently deduplicated.
"""
import argparse
from collections import Counter, defaultdict
import csv
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys


spec = importlib.util.spec_from_file_location(
    "operation_trace", Path(__file__).with_name("analyze-operation-trace.py"))
operation_trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operation_trace)


def distribution(values):
    return operation_trace.distribution([v for v in values if v is not None])


def durations(rows):
    result = defaultdict(list)
    for row in rows:
        if "durationNanos" in row:
            result[row["stage"]].append(row["durationNanos"] / 1e6)
    return {stage: {**distribution(values), "sumMs": sum(values)}
            for stage, values in sorted(result.items())}


def request_summary(requests):
    fields = ("totalMs", "monitorWaitMs", "monitorHeldMs", "latchMs", "prepareMs",
              "validatedPublicationMs")
    return {
        "requests": len(requests),
        "durations": {field: distribution([r.get(field) for r in requests]) for field in fields},
        "monitorPlusLatchWait": distribution([
            r["monitorWaitMs"] + r["latchMs"] for r in requests
            if "monitorWaitMs" in r and "latchMs" in r]),
        "counts": {field: sum(r.get(field, 0) for r in requests) for field in
                   ("prepareCount", "latchCount", "retries", "joinedHabits", "createdBatches")},
        "maxConcurrentPreparations": max((r.get("maxConcurrentPreparations", 0) for r in requests), default=0),
        "mainRequests": [r for r in requests if r.get("onMain")],
        "outcomes": dict(Counter(r.get("outcome", "unknown") for r in requests)),
        "additivity": "Monitor hold may include provider preparation in the previous controller; do not sum these stages as exclusive durations.",
    }


def batch_counts(events):
    """One cache span is broadcast to every eligible edit trace; count once."""
    stages = defaultdict(set)
    for event in events:
        if event.get("batch") is not None and event["stage"].startswith("cache."):
            stages[event["stage"]].add((event["batch"], event.get("generation")))
    return {stage: len(identities) for stage, identities in sorted(stages.items())}


def correlate_events(source, scenario=None):
    """Convert the harness envelope to the existing presentation parser input."""
    result = {"pid": source["pid"], "operations": []}
    for op in source["operations"]:
        if "operation" not in op:
            continue
        if scenario and op["scenario"] != scenario:
            continue
        ordinal = op["operation"]
        result["operations"].append({"ordinal": ordinal, "scenario": op["scenario"],
            "events": [e for e in source["events"] if e.get("operation") == ordinal],
            "sqliteParity": op.get("sqliteSnapshotParity"),
            "drawDisposition": "required"})
    return result


def summarize(source, presentation=None, navigation_presentation=None):
    events = source["events"]
    frames = source.get("frames", [])
    requests = [e for e in events if e["stage"] == "controller.request"]
    presentations = {o["ordinal"]: {k: v for k, v in o.items() if k != "stageMetrics"}
                     for collection in (presentation, navigation_presentation)
                     if collection for o in collection["operations"]
                     if o["status"] != "unmatched" or o.get("reason") != "missing_or_duplicate_input_marker"}
    operations = []
    for op in source["operations"]:
        ordinal = op.get("operation", 0)
        # phase labels group ambient asynchronous work, not proof of causal ownership.
        lower = op.get("startNanos")
        subsequent = [candidate["startNanos"] for candidate in source["operations"]
                      if lower is not None and candidate.get("startNanos", 0) > lower]
        upper = min(subsequent) if subsequent else None
        def in_window(event):
            if lower is None:
                return event.get("phase") == op["scenario"]
            timestamp = event["timestampNanos"]
            return timestamp >= lower and (upper is None or timestamp < upper)
        correlated = [e for e in events if e.get("operation", 0) == ordinal]
        relevant = [e for e in correlated if in_window(e)]
        row_frames = [f for f in frames if f.get("operation", 0) == ordinal]
        metrics = operation_trace.stage_metrics(relevant)
        durable = [e for e in relevant if e["stage"] == "sqlite.durable"]
        drawn = [e for e in relevant if e["stage"] == "glyph.draw"]
        if len(durable) == len(drawn) == 1 and drawn[0].get("batch") is not None:
            draw = drawn[0]
            lineage = tuple(draw.get(k) for k in ("batch", "generation", "revision"))
            publications = [e for e in relevant if e["stage"] == "cache.row.published"
                and tuple(e.get(k) for k in ("batch", "generation", "revision")) == lineage
                and durable[0]["timestampNanos"] <= e["timestampNanos"] <= draw["timestampNanos"]]
            if (len(publications) == 1 and durable[0].get("revision") is not None
                    and draw.get("revision") is not None and draw["revision"] >= durable[0]["revision"]):
                metrics["durableToDrawnRowPublicationMs"] = (
                    publications[0]["timestampNanos"] - durable[0]["timestampNanos"]) / 1e6
                metrics["drawnPublicationLineage"] = dict(zip(("batch", "generation", "revision"), lineage))
        input_events = [e for e in relevant if e["stage"] == "input.down"]
        draw_events = [e for e in relevant if e["stage"] == "glyph.draw"]
        interaction_frames = None
        if len(input_events) == len(draw_events) == 1:
            lo, hi = input_events[0]["timestampNanos"], draw_events[0]["timestampNanos"]
            interaction_frames = [f for f in row_frames if lo <= f["timestampNanos"] <= hi]
        operations.append({**op, "harnessToDrainMs": op["durationToDrainNanos"] / 1e6,
            "stageMetrics": metrics,
            "observedEventCounts": dict(sorted(Counter(e["stage"] for e in relevant).items())),
            "laterCorrelatedEventCounts": dict(sorted(Counter(e["stage"] for e in correlated if not in_window(e)).items())),
            "controller": request_summary([e for e in relevant if e["stage"] == "controller.request"]),
            "viewTiming": durations(row_frames),
            "targetViewTiming": durations([f for f in row_frames if f.get("targetRow")]),
            "inputToGlyphViewTiming": durations(interaction_frames) if interaction_frames is not None else None,
            "inputToGlyphTargetViewTiming": durations([f for f in interaction_frames if f.get("targetRow")])
                if interaction_frames is not None else None,
            "presentation": presentations.get(ordinal, {"status": "unmatched", "reason": "no_exact_presentation_analysis"})})
    phase_summaries = {}
    for phase in sorted({e.get("phase", "unknown") for e in events} | {f.get("phase", "unknown") for f in frames}):
        phase_events = [e for e in events if e.get("phase") == phase]
        phase_summaries[phase] = {
            "observedEventCounts": dict(sorted(Counter(e["stage"] for e in phase_events).items())),
            "uniqueTracedBatchCounts": batch_counts(phase_events),
            "controller": request_summary([e for e in phase_events if e["stage"] == "controller.request"]),
            "controlledReaders": request_summary([e for e in phase_events
                if e["stage"] == "controller.request" and e.get("thread", "").startswith("qa-latency-reader-")]),
            "viewTiming": durations([f for f in frames if f.get("phase") == phase]),
        }
    return {"buildLabel": source.get("buildLabel"), "historyDays": source.get("historyDays"),
        "tracingEnabled": source.get("tracingEnabled"), "coldDefinition": source.get("coldDefinition"),
        "controller": request_summary(requests), "phases": phase_summaries,
        "uniqueTracedBatchCounts": batch_counts(events),
        "observerCallback": {**distribution([x / 1e6 for x in source.get("observerCallbackNanos", [])]),
                             "sumMs": sum(source.get("observerCallbackNanos", [])) / 1e6},
        "operations": operations,
        "limitations": ["Harness-to-drain includes instrumentation and idle barriers; it is not input-to-screen latency.",
                         "Ambient operation and phase labels are observation windows; batch and generation identify causal cache work.",
                         "Nested view timing spans overlap; their sums must not be added as Main exclusive time.",
                         "Missing recorded stage is unknown, not zero; actual app-surface presentation requires exact FrameTimeline token join."]}


def trace_attribution(trace, processor, pid):
    """Use validated public schemas and stdlib CPU/runnable joins.

    A single batched query avoids reparsing; sandboxed Windows session sockets
    can be unavailable. CPU time comes from stdlib, not manual span arithmetic.
    Descendant spans retain their hierarchy and cannot be summed as exclusive.
    """
    sql = f"""
    INCLUDE PERFETTO MODULE slices.cpu_time;
    INCLUDE PERFETTO MODULE android.frames.timeline;
    INCLUDE PERFETTO MODULE sched.runnable;
    WITH app AS (SELECT p.upid FROM process p WHERE p.pid={int(pid)}),
    main_frames AS (
      SELECT s.id,s.ts,s.dur,s.name,c.cpu_time,f.frame_id
      FROM android_frames_choreographer_do_frame f
      JOIN slice s ON s.id=f.id
      LEFT JOIN thread_slice_cpu_time c ON c.id=s.id
      WHERE f.upid IN (SELECT a.upid FROM app a) AND s.dur>=0
    ), slow_frames AS (SELECT m.* FROM main_frames m WHERE m.dur>=16000000),
    main_slices AS (
      SELECT s.id,s.ts,s.dur,s.name,s.depth,s.parent_id,c.cpu_time
      FROM slice s JOIN thread_track tt ON tt.id=s.track_id
      JOIN thread t ON t.utid=tt.utid
      LEFT JOIN thread_slice_cpu_time c ON c.id=s.id
      WHERE t.upid IN (SELECT a.upid FROM app a) AND t.is_main_thread=1
    )
    SELECT 'mainFrame' kind,json_object('id',m.id,'ts',m.ts,'durationMs',m.dur/1e6,
      'cpuMs',m.cpu_time/1e6,'token',m.frame_id,'name',m.name) payload
      FROM main_frames m
    UNION ALL SELECT 'slowFrameDescendant',json_object('frameId',f.id,'id',d.id,
      'parentId',d.parent_id,'depth',d.depth,'ts',d.ts,'durationMs',d.dur/1e6,
      'cpuMs',c.cpu_time/1e6,'name',d.name)
      FROM slow_frames f JOIN descendant_slice(f.id) d
      LEFT JOIN thread_slice_cpu_time c ON c.id=d.id
      WHERE d.dur>=0 AND (d.name GLOB '*measure*' OR d.name GLOB '*layout*'
        OR d.name GLOB '*Layout*' OR d.name GLOB 'habit*' OR d.dur>=10000000)
    UNION ALL SELECT 'mainExpensiveSlice',json_object('id',s.id,'ts',s.ts,
      'durationMs',s.dur/1e6,'cpuMs',s.cpu_time/1e6,'name',s.name,
      'depth',s.depth,'parentId',s.parent_id)
      FROM main_slices s WHERE s.dur>=16000000
    UNION ALL SELECT 'workerRunnable',json_object('thread',t.name,'runningTs',r.ts,
      'runningMs',r.dur/1e6,'runnableTs',q.ts,'runnableMs',q.dur/1e6,
      'wakerThread',w.name)
      FROM sched_previous_runnable_on_thread prev
      JOIN thread_state r ON r.id=prev.id
      JOIN thread_state q ON q.id=prev.prev_runnable_id
      JOIN thread t ON t.utid=r.utid
      LEFT JOIN thread w ON w.utid=q.waker_utid
      WHERE t.upid IN (SELECT a.upid FROM app a) AND t.is_main_thread=0
        AND t.name GLOB 'DefaultDispatch*' AND q.dur>=1000000
    UNION ALL SELECT 'traceHealth',json_object('name',st.name,'idx',st.idx,
      'severity',st.severity,'value',st.value) FROM stats st WHERE st.value!=0
      AND (st.severity='error' OR st.severity='data_loss')
    UNION ALL SELECT 'marker',json_object('ts',s.ts,'name',s.name,'id',s.id,
      'thread',t.name) FROM slice s JOIN thread_track tt ON tt.id=s.track_id
      JOIN thread t ON t.utid=tt.utid WHERE t.upid IN (SELECT a.upid FROM app a)
      AND (s.name GLOB 'habit.op.*' OR s.name GLOB 'habit.nav.*')
    """
    # trace_processor's CSV printer does not escape inner JSON quotes. Hex is
    # lossless and contains neither commas nor quotes, so standard CSV is safe.
    includes, query = sql.split("WITH app AS", 1)
    sql = includes + "SELECT q.kind,hex(q.payload) payload FROM (WITH app AS" + query + ") q"
    result = subprocess.run([sys.executable, str(processor), "query", str(trace), sql],
                            capture_output=True, text=True, check=True)
    grouped = defaultdict(list)
    for row in csv.DictReader(io.StringIO(result.stdout)):
        grouped[row["kind"]].append(json.loads(bytes.fromhex(row["payload"]).decode("utf-8")))
    return {"trace": str(trace.resolve()), "sql": sql, "records": dict(grouped),
        "frameDuration": distribution([r["durationMs"] for r in grouped["mainFrame"]]),
        "frameCpu": distribution([r.get("cpuMs") for r in grouped["mainFrame"]]),
        "workerRunnable": distribution([r["runnableMs"] for r in grouped["workerRunnable"]]),
        "scope": "Entire app process trace capture; do not assign unrelated startup frames to an input operation.",
        "limitations": ["Descendant and expensive-slice durations overlap; retain parent IDs.",
                         "DefaultDispatcher runnable intervals >=1ms report scheduling delay, not all sleeping/latch waiting; other runtime threads excluded.",
                         "Frame wall and CPU time require recorded scheduling; absence stays unknown."]}


def attach_trace_operations(operations, attribution):
    """Use trace markers to choose operation windows, with no JSON-clock joins."""
    records = attribution["records"]
    marker_times = defaultdict(list)
    for marker in records.get("marker", []):
        marker_times[marker["name"]].append(marker["ts"])
    frames = records.get("mainFrame", [])
    descendants = records.get("slowFrameDescendant", [])
    for operation in operations:
        ordinal = operation.get("operation")
        navigation = operation["scenario"] == "warmNavigation"
        first = ("habit.nav.request." if navigation else "habit.op.input.") + str(ordinal)
        last = ("habit.nav.habitsDraw." if navigation else "habit.op.committedDraw.") + str(ordinal)
        start, end = marker_times[first], marker_times[last]
        if len(start) != 1 or len(end) != 1:
            operation["traceMain"] = {"status": "unmatched", "reason": "missing_or_duplicate_window_markers"}
            continue
        selected = [f for f in frames if start[0] <= f["ts"] <= end[0]]
        token = operation["presentation"].get("surfaceFrameToken")
        target = [f for f in frames if f["token"] == token]
        selected_ids = {f["id"] for f in selected}
        operation["traceMain"] = {
            "status": "windowed", "boundary": "doFrames starting between exact input/request and target draw trace markers",
            "frames": selected, "frameDuration": distribution([f["durationMs"] for f in selected]),
            "frameCpu": distribution([f.get("cpuMs") for f in selected]),
            "slowFrameDescendants": [d for d in descendants if d["frameId"] in selected_ids],
            "exactPresentedTokenFrames": target,
            "clockDomain": "Perfetto only; stage JSON timestamps not used for this selection"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--presentation", type=Path)
    parser.add_argument("--navigation-presentation", type=Path)
    parser.add_argument("--correlation-events", type=Path)
    parser.add_argument("--correlation-scenario", help="Export just this scenario for the exact presentation parser")
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--processor", type=Path)
    args = parser.parse_args()
    if args.output.exists() or (args.correlation_events and args.correlation_events.exists()):
        parser.error("Choose new output paths; preserve prior evidence")
    read = lambda path: json.loads(path.read_text(encoding="utf-8-sig")) if path else None
    source = read(args.events)
    output = summarize(source, read(args.presentation), read(args.navigation_presentation))
    if args.trace:
        if not args.processor:
            parser.error("--trace requires --processor")
        output["traceAttribution"] = trace_attribution(args.trace, args.processor, source["pid"])
        attach_trace_operations(output["operations"], output["traceAttribution"])
    output["source"] = str(args.events.resolve())
    args.output.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    if args.correlation_events:
        args.correlation_events.write_text(json.dumps(correlate_events(source, args.correlation_scenario), indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "controller": output["controller"]}, indent=2))


if __name__ == "__main__":
    main()
