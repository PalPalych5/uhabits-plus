import copy
import unittest
from analyze_post_navigation_attribution import qualify_operation_scope


class OperationScopeTest(unittest.TestCase):
    def fixture(self, phase='afterTasks', timestamp=90):
        return ({'operations': [{'operation': 1, 'scenario': 'afterTasks', 'finishedNanos': 100}],
                 'events': [{'operation': 1, 'stage': 'glyph.draw', 'phase': phase,
                             'timestampNanos': timestamp}]},
                {'operations': [{'ordinal': 1, 'status': 'linked',
                                 'inputToCommittedPresentationMs': 26349.2,
                                 'committedDrawToPresentationMs': 77.1}]})

    def test_scoped_join_is_retained_without_mutating_physical_evidence(self):
        source, physical = self.fixture()
        original = copy.deepcopy(physical)
        result = qualify_operation_scope(source, physical)
        self.assertEqual(result['coverage']['linked'], 1)
        self.assertEqual(result['operations'][0]['operationScope'], 'finishedBoundary')
        self.assertEqual(physical, original)

    def test_later_viewport_join_is_preserved_but_not_counted_as_edit_latency(self):
        source, physical = self.fixture('viewportSetup', 200)
        result = qualify_operation_scope(source, physical)
        op = result['operations'][0]
        self.assertEqual(result['coverage']['unmatched'], 1)
        self.assertNotIn('inputToCommittedPresentationMs', op)
        self.assertEqual(op['outOfScopePresentedCandidate'], physical['operations'][0])
        self.assertEqual(result['inputToCommittedPresentation'], {'n': 0})

    def test_same_phase_after_retirement_is_unmatched(self):
        source, physical = self.fixture(timestamp=101)
        self.assertEqual(qualify_operation_scope(source, physical)['coverage']['unmatched'], 1)


if __name__ == '__main__':
    unittest.main()
