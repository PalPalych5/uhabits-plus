"""Join operation glyph markers to presented app frames; never guess nearest frames.

Use the Perfetto skill's pinned trace_processor wrapper via --processor.
All latency arithmetic here stays inside Perfetto's trace clock domain.
"""
import argparse
import csv
import io
import json
import math
import re
import subprocess
import sys
from pathlib import Path


def distribution(values):
    values = sorted(values)
    if not values:
        return {"n": 0}
    return {"n": len(values), "p50Ms": values[math.ceil(len(values) * .5) - 1],
            "p95Ms": values[math.ceil(len(values) * .95) - 1], "maxMs": values[-1]}


def classify(rows):
    """One operation may have nested resynced doFrames; prefer deepest valid token."""
    inputs = {int(r["input_ns"]) for r in rows if r["input_ns"]}
    draws = {int(r["draw_id"]) for r in rows if r["draw_id"]}
    if len(inputs) != 1:
        return {"status": "unmatched", "reason": "missing_or_duplicate_input_marker"}
    if not draws:
        return {"status": "unmatched", "reason": "no_committed_target_draw_marker"}
    if len(draws) != 1:
        return {"status": "ambiguous", "reason": "multiple_committed_target_draw_markers"}
    candidates = [r for r in rows if r["present_ns"] and r["dur"] and int(r["dur"]) >= 0
                  and r["present_type"] in ("On-time Present", "Early Present", "Late Present")
                  and r["frame_upid"] == r["marker_upid"]]
    if not candidates:
        return {"status": "unmatched", "reason": "no_presented_frame_for_containing_doFrame"}
    deepest = max(int(r["depth"]) for r in candidates)
    candidates = [r for r in candidates if int(r["depth"]) == deepest]
    identities = {(r["frame_id"], r["token"]) for r in candidates}
    if len(identities) != 1:
        return {"status": "ambiguous", "reason": "multiple_presented_frames_for_token"}
    r = candidates[0]
    if int(r["present_ns"]) < int(r["draw_ns"]):
        return {"status": "unmatched", "reason": "presentation_precedes_target_draw"}
    return {"status": "linked", "surfaceFrameToken": int(r["token"]),
            "presentType": r["present_type"], "jankType": r["jank_type"],
            "inputToCommittedPresentationMs": (int(r["present_ns"]) - next(iter(inputs))) / 1e6,
            "committedDrawToPresentationMs": (int(r["present_ns"]) - int(r["draw_ns"])) / 1e6}


def stage_metrics(events):
    """Retain repeated batch spans separately; missing endpoints stay unknown."""
    starts, spans, timestamps = {}, {}, {}
    for event in sorted(events, key=lambda e: e["timestampNanos"]):
        stage, timestamp = event["stage"], int(event["timestampNanos"])
        timestamps.setdefault(stage, []).append(timestamp)
        if stage.endswith(".begin"):
            identity = (stage[:-6], event.get("batch"), event.get("generation"))
            starts.setdefault(identity, []).append(timestamp)
        elif stage.endswith(".end") or stage in ("snapshot.lock.acquired", "snapshot.retry"):
            base = stage[:-4] if stage.endswith(".end") else ("snapshot.lock.wait" if stage == "snapshot.lock.acquired" else "snapshot")
            identity = (base, event.get("batch"), event.get("generation"))
            if starts.get(identity):
                spans.setdefault(base, []).append({"batch": identity[1], "generation": identity[2], "durationMs": (timestamp - starts[identity].pop()) / 1e6, "endStage": stage})
    result = {"spans": spans, "unclosedSpans": [{"stage": k[0], "batch": k[1], "generation": k[2], "count": len(v)} for k, v in starts.items() if v],
              "inputScope": "actual View DOWN receipt; excludes system input delivery before handler"}
    for name, first, last in (("queueWaitMs", "command.accepted", "command.fifoStart"),
                              ("acceptedToEnqueuedMs", "command.accepted", "command.enqueued"),
                              ("workerDispatchMs", "command.enqueued", "command.worker"),
                              ("fifoPredecessorWaitMs", "command.worker", "command.fifoStart"),
                              ("inputReceiptToCommandAcceptedMs", "input.down", "command.accepted"),
                              ("commandAcceptedToDurableQualificationMs", "command.accepted", "sqlite.durable"),
                              ("inputReceiptToGlyphDrawMs", "input.down", "glyph.draw")):
        if len(timestamps.get(first, [])) == len(timestamps.get(last, [])) == 1:
            result[name] = (timestamps[last][0] - timestamps[first][0]) / 1e6
    durable = [e for e in events if e["stage"] == "sqlite.durable"]
    # sqlite.durable qualifies a successful changing autocommit. Its callback
    # follows SELECT changes(); use the preceding step-return endpoint itself.
    result["actualDurability"] = {"status": "unmatched"}
    if len(durable) == 1:
        qualification = durable[0]["timestampNanos"]
        steps = [e for e in events if e["stage"] == "sqlite.end" and e["timestampNanos"] <= qualification]
        failures = [e for e in events if e["stage"] in ("sqlite.failed", "sqlite.pendingTransaction")]
        if len(steps) == 1 and not failures:
            endpoint = steps[0]["timestampNanos"]
            result["actualDurability"] = {"status": "qualifiedAutocommitStepReturn",
                "timestampNanos": endpoint, "qualificationLagMs": (qualification - endpoint) / 1e6}
            for name, stage in (("inputToActualDurableMs", "input.accepted"),
                                ("downToActualDurableMs", "input.down")):
                if len(timestamps.get(stage, [])) == 1 and timestamps[stage][0] <= endpoint:
                    result[name] = (endpoint - timestamps[stage][0]) / 1e6
    publications = [e for e in events if e["stage"] == "cache.row.published"]
    if len(durable) == 1 and durable[0].get("revision") is not None:
        committed = durable[0]
        valid = [e for e in publications if e.get("revision") is not None and e["revision"] >= committed["revision"]
                 and e["timestampNanos"] >= committed["timestampNanos"]]
        result["durableToPublications"] = [{"batch": e.get("batch"), "generation": e.get("generation"), "revision": e["revision"],
                                            "durationMs": (e["timestampNanos"] - committed["timestampNanos"]) / 1e6} for e in valid]
        joins = []
        for draw in (e for e in events if e["stage"] == "glyph.draw"):
            matches = [e for e in valid if draw.get("batch") is not None and
                       (e.get("batch"), e.get("generation"), e.get("revision")) ==
                       (draw.get("batch"), draw.get("generation"), draw.get("revision")) and
                       e["timestampNanos"] <= draw["timestampNanos"]]
            if len(matches) == 1:
                joins.append({"batch": draw["batch"], "generation": draw.get("generation"),
                              "durationMs": (draw["timestampNanos"] - matches[0]["timestampNanos"]) / 1e6})
        result["publicationToGlyphDraws"] = joins
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--processor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--events", type=Path, help="Optional JSON operations list, retaining unmatched accepted ordinals")
    parser.add_argument("--input-prefix", default="habit.op.input.")
    parser.add_argument("--draw-prefix", default="habit.op.committedDraw.")
    parser.add_argument("--layer", default="org.isoron.uhabits.plus/org.isoron.uhabits.activities.main.MainActivity")
    args = parser.parse_args()
    for value in (args.input_prefix, args.draw_prefix, args.layer):
        if not re.fullmatch(r"[A-Za-z0-9_./]+", value):
            parser.error("Marker prefixes and layer must contain only alphanumeric, underscore, dot or slash")
    if args.output.exists():
        parser.error("Choose a new output path; preserve prior evidence")
    events = json.loads(args.events.read_text(encoding="utf-8-sig")) if args.events else None
    pid_filter = f" AND t.upid IN (SELECT upid FROM process WHERE pid={int(events['pid'])})" if events else ""
    sql = f"""
    WITH inputs AS (
      SELECT id, ts, CAST(substr(name, {len(args.input_prefix) + 1}) AS INT) ordinal
      FROM slice WHERE name GLOB '{args.input_prefix}[0-9]*'
    ), draws AS (
      SELECT s.id, s.ts, CAST(substr(s.name, {len(args.draw_prefix) + 1}) AS INT) ordinal, t.upid
      FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
      WHERE s.name GLOB '{args.draw_prefix}[0-9]*' {pid_filter}
    ), ancestors AS (
      SELECT d.id draw_id, a.depth, a.name,
        CAST(substr(a.name, CASE WHEN a.name LIKE 'Choreographer#doFrame - resynced to %'
             THEN 37 ELSE 23 END) AS INT) token
      FROM draws d JOIN ancestor_slice(d.id) a WHERE a.name LIKE 'Choreographer#doFrame %'
    )
    SELECT i.ordinal, i.ts input_ns, d.id draw_id, d.ts draw_ns, d.upid marker_upid,
      a.depth, a.token, f.id frame_id, f.upid frame_upid, f.dur,
      f.ts+f.dur present_ns, f.present_type, f.jank_type
    FROM inputs i LEFT JOIN draws d ON d.ordinal=i.ordinal LEFT JOIN ancestors a ON a.draw_id=d.id
    LEFT JOIN actual_frame_timeline_slice f ON f.surface_frame_token=a.token
      AND f.layer_name LIKE '%{args.layer}%'
    ORDER BY i.ordinal, a.depth
    """
    result = subprocess.run([sys.executable, str(args.processor), "query", str(args.trace), sql],
                            capture_output=True, text=True, check=True)
    rows = [{k: ("" if v == "[NULL]" else v) for k, v in row.items()}
            for row in csv.DictReader(io.StringIO(result.stdout))]
    grouped = {}
    for row in rows:
        grouped.setdefault(int(row["ordinal"]), []).append(row)
    metadata = {}
    if args.events:
        for operation in events["operations"]:
            grouped.setdefault(int(operation["ordinal"]), [])
            metadata[int(operation["ordinal"])] = operation
    operations = [{"ordinal": ordinal, **classify(group)} for ordinal, group in sorted(grouped.items())]
    for operation in operations:
        source = metadata.get(operation["ordinal"], {})
        for key in ("scenario", "terminal", "outcome", "drawDisposition", "sqliteParity"):
            if key in source:
                operation[key] = source[key]
        operation["stageMetrics"] = stage_metrics(source.get("events", []))
        if source.get("drawDisposition") in ("superseded", "notApplicable"):
            if operation["status"] == "linked":
                operation["status"], operation["reason"] = "ambiguous", "unexpected_draw_for_superseded_or_failed_operation"
                operation.pop("inputToCommittedPresentationMs", None)
                operation.pop("committedDrawToPresentationMs", None)
            else:
                operation["status"], operation["reason"] = "notApplicable", source["drawDisposition"]
    output = {"trace": str(args.trace.resolve()), "joinMethod": "target marker -> containing doFrame token -> same-process actual app-surface present",
              "clockDomain": "Perfetto trace timestamps; no cross-clock subtraction", "operations": operations,
              "coverage": {status: sum(o["status"] == status for o in operations) for status in ("linked", "unmatched", "ambiguous", "notApplicable")},
              "inputToCommittedPresentation": distribution([o["inputToCommittedPresentationMs"] for o in operations if o["status"] == "linked"])}
    args.output.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in output.items() if k != "operations"}, indent=2))


if __name__ == "__main__":
    main()
