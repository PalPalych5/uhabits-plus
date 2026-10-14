"""Presentation joins must reject plausible but unproven frames."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("operation_trace", Path(__file__).with_name("analyze-operation-trace.py"))
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)


class PresentationJoinTest(unittest.TestCase):
    def test_durability_uses_qualified_step_return_not_later_callback(self):
        events = [dict(stage="input.accepted", timestampNanos=10),
                  dict(stage="sqlite.end", timestampNanos=30),
                  dict(stage="sqlite.durable", timestampNanos=50),
                  dict(stage="command.finished", timestampNanos=1000)]
        result = trace.stage_metrics(events)
        self.assertEqual(result["actualDurability"]["timestampNanos"], 30)
        self.assertEqual(result["inputToActualDurableMs"], 20e-6)
        for extra in (dict(stage="sqlite.failed", timestampNanos=29),
                      dict(stage="sqlite.pendingTransaction", timestampNanos=31),
                      dict(stage="sqlite.end", timestampNanos=20)):
            result = trace.stage_metrics(events + [extra])
            self.assertEqual(result["actualDurability"]["status"], "unmatched")
            self.assertNotIn("inputToActualDurableMs", result)

    def setUp(self):
        self.row = dict(input_ns="100", draw_id="1", draw_ns="120", dur="20", present_ns="150",
                        present_type="On-time Present", marker_upid="1", frame_upid="1",
                        depth="2", frame_id="4", token="9", jank_type="None")

    def test_link_requires_actual_same_process_present(self):
        self.assertEqual(trace.classify([self.row])["status"], "linked")
        for change in (dict(frame_upid="2"), dict(present_type="Dropped Frame"), dict(dur="-1"),
                       dict(present_ns=""), dict(present_ns="110")):
            self.assertEqual(trace.classify([dict(self.row, **change)])["status"], "unmatched")

    def test_missing_tokens_have_no_nearest_frame_fallback(self):
        self.assertEqual(trace.classify([])["status"], "unmatched")
        self.assertEqual(trace.classify([dict(self.row, present_ns="", frame_id="")])["status"], "unmatched")

    def test_ambiguous_markers_or_frames_are_rejected(self):
        for change in (dict(draw_id="2"), dict(frame_id="5")):
            self.assertEqual(trace.classify([self.row, dict(self.row, **change)])["status"], "ambiguous")
        self.assertEqual(trace.classify([self.row, dict(self.row, input_ns="101")])["status"], "unmatched")

    def test_deepest_valid_resynced_ancestor_with_outer_fallback(self):
        outer = dict(self.row, depth="1", token="8", frame_id="3")
        self.assertEqual(trace.classify([self.row, outer])["surfaceFrameToken"], 9)
        missing_inner = dict(self.row, present_ns="", frame_id="")
        self.assertEqual(trace.classify([missing_inner, outer])["surfaceFrameToken"], 8)

    def test_generation_spans_are_not_paired_across_batches(self):
        events = [dict(stage="snapshot.begin", timestampNanos=1, generation=1),
                  dict(stage="snapshot.begin", timestampNanos=2, generation=2),
                  dict(stage="snapshot.end", timestampNanos=5, generation=1),
                  dict(stage="snapshot.end", timestampNanos=9, generation=2)]
        result = trace.stage_metrics(events)
        self.assertEqual(result["spans"]["snapshot"], [dict(batch=None, generation=1, durationMs=4e-6, endStage="snapshot.end"), dict(batch=None, generation=2, durationMs=7e-6, endStage="snapshot.end")])
        self.assertEqual(result["unclosedSpans"], [])

    def test_colliding_generations_require_batch_identity(self):
        events = [dict(stage="snapshot.begin", timestampNanos=1, generation=1, batch=1),
                  dict(stage="snapshot.begin", timestampNanos=2, generation=1, batch=2),
                  dict(stage="snapshot.end", timestampNanos=5, generation=1, batch=1),
                  dict(stage="snapshot.end", timestampNanos=9, generation=1, batch=2)]
        self.assertEqual([s["durationMs"] for s in trace.stage_metrics(events)["spans"]["snapshot"]], [4e-6, 7e-6])

    def test_draw_join_requires_matching_publication_batch_not_nearest_revision(self):
        events = [dict(stage="sqlite.durable", timestampNanos=1, revision=3),
                  dict(stage="cache.row.published", timestampNanos=4, revision=3, generation=1, batch=1),
                  dict(stage="cache.row.published", timestampNanos=5, revision=3, generation=1, batch=2),
                  dict(stage="glyph.draw", timestampNanos=9, revision=3, generation=1, batch=1)]
        self.assertEqual(trace.stage_metrics(events)["publicationToGlyphDraws"][0]["durationMs"], 5e-6)
        events[-1]["batch"] = None
        self.assertEqual(trace.stage_metrics(events)["publicationToGlyphDraws"], [])


if __name__ == "__main__":
    unittest.main()
