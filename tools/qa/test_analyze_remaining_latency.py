"""Keep attribution evidence distinct from idle timing and nested Main spans."""
import unittest
from analyze_remaining_latency import batch_counts, correlate_events, summarize


class AttributionTest(unittest.TestCase):
    def source(self):
        return {"pid": 1, "events": [], "frames": [], "operations": [
            {"operation": 1, "scenario": "warmEdit", "durationToDrainNanos": 1000000},
            {"operation": 2, "scenario": "warmNavigation", "durationToDrainNanos": 2000000}]}

    def test_unmatched_presentation_is_not_replaced_with_drain(self):
        operation = summarize(self.source())["operations"][0]
        self.assertEqual(operation["harnessToDrainMs"], 1)
        self.assertEqual(operation["presentation"]["status"], "unmatched")
        self.assertNotIn("inputToCommittedPresentationMs", operation["presentation"])

    def test_repeated_request_counts_and_main_latch_are_retained(self):
        source = self.source()
        row = {"stage": "controller.request", "operation": 1, "phase": "warmEdit", "timestampNanos": 1,
               "onMain": True, "totalMs": 20, "monitorWaitMs": 1, "monitorHeldMs": 2,
               "latchMs": 17, "latchCount": 1, "prepareCount": 0}
        source["events"] = [row, dict(row)]
        result = summarize(source)["controller"]
        self.assertEqual(result["requests"], 2)
        self.assertEqual(result["counts"]["latchCount"], 2)
        self.assertEqual(len(result["mainRequests"]), 2)
        self.assertEqual(result["durations"]["prepareMs"], {"n": 0})

    def test_nested_spans_not_turned_into_exclusive_main_time(self):
        source = self.source()
        source["frames"] = [{"operation": 1, "phase": "warmEdit", "stage": stage,
                             "durationNanos": dur, "targetRow": True}
                            for stage, dur in [("habit.card.payload.bind", 1000000),
                                               ("habit.card.entry.bind", 500000)]]
        result = summarize(source)["operations"][0]["viewTiming"]
        self.assertEqual(result["habit.card.payload.bind"]["sumMs"], 1)
        self.assertEqual(result["habit.card.entry.bind"]["sumMs"], .5)
        self.assertNotIn("exclusiveMainMs", result)

    def test_navigation_correlation_is_separate(self):
        source = self.source()
        source["events"] = [{"operation": 2, "stage": "navigation.habits.request", "timestampNanos": 42}]
        result = correlate_events(source, "warmNavigation")
        self.assertEqual([r["ordinal"] for r in result["operations"]], [2])
        self.assertEqual(result["operations"][0]["events"], source["events"])

    def test_cache_broadcasts_are_counted_once_per_batch(self):
        events = [{"stage": "cache.request", "batch": b, "generation": 7, "operation": op}
                  for b, op in [(1, 1), (1, 2), (2, 1)]]
        events.append({"stage": "cache.request", "generation": 7, "operation": 1})
        self.assertEqual(batch_counts(events), {"cache.request": 2})

    def test_later_broadcast_not_charged_to_previous_operation_window(self):
        source = self.source()
        source["operations"][0]["startNanos"] = 10
        source["operations"][1]["startNanos"] = 30
        source["events"] = [{"stage": "cache.request", "operation": 1, "timestampNanos": t,
                             "phase": "warmEdit", "batch": t} for t in (20, 40)]
        op = summarize(source)["operations"][0]
        self.assertEqual(op["observedEventCounts"]["cache.request"], 1)
        self.assertEqual(op["laterCorrelatedEventCounts"]["cache.request"], 1)

    def test_next_viewport_setup_excluded_from_input_to_glyph_measurements(self):
        source = self.source()
        source["events"] = [{"stage": stage, "timestampNanos": time, "operation": 1, "phase": "warmEdit"}
                            for stage, time in [("input.down", 10), ("glyph.draw", 20)]]
        source["frames"] = [{"stage": "habit.card.bind", "timestampNanos": time,
                             "durationNanos": 1000000, "operation": 1, "phase": "warmEdit"}
                            for time in (15, 25)]
        operation = summarize(source)["operations"][0]
        self.assertEqual(operation["viewTiming"]["habit.card.bind"]["n"], 2)
        self.assertEqual(operation["inputToGlyphViewTiming"]["habit.card.bind"]["n"], 1)

    def test_drawn_publication_uses_batch_not_later_full_refresh(self):
        source = self.source()
        source["events"] = [dict(stage="sqlite.durable", timestampNanos=10, revision=3),
            dict(stage="cache.row.published", timestampNanos=20, revision=3, generation=1, batch=1),
            dict(stage="glyph.draw", timestampNanos=30, revision=3, generation=1, batch=1),
            dict(stage="cache.row.published", timestampNanos=40, revision=3, generation=1, batch=2)]
        for event in source["events"]:
            event.update(operation=1, phase="warmEdit")
        metrics = summarize(source)["operations"][0]["stageMetrics"]
        self.assertEqual(metrics["durableToDrawnRowPublicationMs"], 10e-6)
        self.assertEqual(metrics["drawnPublicationLineage"]["batch"], 1)

    def test_nav_missing_edit_marker_cannot_overwrite_valid_edit_join(self):
        source = self.source()
        edit = {"operations": [{"ordinal": 1, "status": "linked", "surfaceFrameToken": 3}]}
        nav = {"operations": [{"ordinal": 1, "status": "unmatched", "reason": "missing_or_duplicate_input_marker"}]}
        self.assertEqual(summarize(source, edit, nav)["operations"][0]["presentation"]["status"], "linked")


if __name__ == "__main__":
    unittest.main()
