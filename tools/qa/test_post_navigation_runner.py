import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import run_post_navigation_attribution as runner


class PostNavigationRunnerTest(unittest.TestCase):
    def exercise(self, directory, scenario):
        output = Path(directory)
        label = 'mockarm'
        calls = []
        capture = Mock()
        capture.poll.return_value = None
        capture.wait.return_value = 0

        def popen(*args, **kwargs):
            # No process owns these handles in this deterministic mock.
            kwargs['stdout'].close()
            kwargs['stderr'].close()
            return capture

        def run(command, **kwargs):
            self.assertEqual(command[:3], ['adb', '-s', 'emulator-5580'])
            self.assertEqual(kwargs['encoding'], 'utf-8')
            self.assertEqual(kwargs['errors'], 'replace')
            action = command[3:]
            calls.append(action)
            stdout = ''
            if action == ['shell', 'getprop ro.boot.qemu.avd_name']:
                stdout = 'UHabit_UI_Review_Disposable_20261011\n'
            elif action == ['shell', 'getprop ro.serialno']:
                stdout = 'EMULATOR36X6X11X0\n'
            elif action == ['shell', 'settings get global airplane_mode_on']:
                stdout = '1\n'
            elif action[0] == 'install' or action == ['shell', 'pm clear org.isoron.uhabits.plus']:
                stdout = 'Success\n'
            elif action[:3] == ['shell', 'am', 'instrument']:
                if scenario == 'timeout':
                    raise subprocess.TimeoutExpired(command, kwargs['timeout'],
                        output=b'INSTRUMENTATION_STATUS: partial\xff\n', stderr=b'partial error\xff')
                stdout = 'OK (1 test)\n'
            elif action == ['shell', 'pidof perfetto']:
                stdout = '1234\n'
            elif action[:2] == ['shell', 'cat']:
                owner = 'someone-else' if scenario == 'wrong-owner' else label
                stdout = f'perfetto\x00--txt\x00-o\x00/data/misc/perfetto-traces/{owner}.perfetto\x00'
            elif action[0] == 'pull':
                if action[1].endswith('.perfetto'):
                    Path(action[2]).write_text('trace evidence', encoding='utf-8')
                elif scenario == 'timeout' or action[1].endswith('-failure.json'):
                    raise subprocess.CalledProcessError(1, command, output='', stderr='remote object does not exist')
                else:
                    Path(action[2]).write_text('{"native": true}', encoding='utf-8')
            return subprocess.CompletedProcess(command, 0, stdout, '')

        argv = ['runner', '--apk-root', str(output / 'apk'), '--label', label,
                '--reverse', 'false', '--output', str(output), '--config', str(output / 'config')]
        with patch.object(sys, 'argv', argv), patch.object(runner.subprocess, 'run', side_effect=run), \
                patch.object(runner.subprocess, 'Popen', side_effect=popen), contextlib.redirect_stdout(io.StringIO()):
            if scenario == 'success':
                runner.main()
            else:
                with self.assertRaisesRegex(RuntimeError, 'partial stdout'):
                    runner.main()
        evidence = json.loads((output / f'{label}.attempt.json').read_text(encoding='utf-8'))
        return evidence, calls, capture

    def test_timeout_preserves_partial_bytes_metadata_and_attempts_all_evidence_cleanup(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent, prefix='runner-test-') as directory:
            evidence, calls, capture = self.exercise(directory, 'timeout')
            text = (Path(directory) / 'mockarm.txt').read_text(encoding='utf-8')
            self.assertEqual(text, 'INSTRUMENTATION_STATUS: partial\ufffd\n')
            self.assertEqual(evidence['instrumentation']['stdout'], text)
            self.assertEqual(evidence['instrumentation']['stderr'], 'partial error\ufffd')
            self.assertEqual(evidence['instrumentation']['type'], 'TimeoutExpired')
            self.assertEqual(evidence['instrumentation']['timeoutSeconds'], 600)
            self.assertFalse(evidence['instrumentation']['passed'])
            self.assertEqual(evidence['status'], 'failed')
            self.assertEqual(len(evidence['nativePulls']), 2)
            self.assertTrue(evidence['nativePulls'][0]['remote'].endswith('-failure.json'))
            self.assertTrue(evidence['nativePulls'][1]['remote'].endswith('-true.json'))
            self.assertTrue(all(not item['pulled'] for item in evidence['nativePulls']))
            self.assertIn(['shell', 'kill', '-INT', '1234'], calls)
            capture.wait.assert_called_once_with(timeout=240)
            self.assertTrue(evidence['tracePulled'])
            self.assertTrue((Path(directory) / 'mockarm.perfetto').exists())

    def test_success_tolerates_missing_alternative_native_outcome(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent, prefix='runner-test-') as directory:
            evidence, calls, capture = self.exercise(directory, 'success')
            self.assertEqual(evidence['status'], 'passed')
            self.assertTrue(evidence['instrumentation']['passed'])
            self.assertTrue(evidence['nativePulled'])
            self.assertEqual([True, False], [item['pulled'] for item in evidence['nativePulls']])
            self.assertIn('remote object does not exist', evidence['nativePulls'][1]['error']['stderr'])
            self.assertEqual(json.loads((Path(directory) / 'mockarm.json').read_text()), {'native': True})
            self.assertIn(['shell', 'kill', '-INT', '1234'], calls)
            capture.wait.assert_called_once_with(timeout=240)

    def test_wrong_capture_owner_is_never_signalled_and_trace_pull_still_attempted(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent, prefix='runner-test-') as directory:
            evidence, calls, capture = self.exercise(directory, 'wrong-owner')
            self.assertFalse(any(action[:2] == ['shell', 'kill'] for action in calls))
            self.assertEqual(evidence['status'], 'failed')
            self.assertIn('refusing signal', evidence['cleanup'][0]['error']['message'])
            self.assertTrue(evidence['tracePulled'])
            capture.wait.assert_called_once_with(timeout=240)

    def test_reused_label_fails_before_any_device_or_capture_operation(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent, prefix='runner-test-') as directory:
            output = Path(directory)
            evidence = output / 'used.txt'
            evidence.write_text('immutable prior evidence', encoding='utf-8')
            argv = ['runner', '--apk-root', directory, '--label', 'used', '--reverse', 'true',
                    '--output', directory, '--config', str(output / 'config')]
            with patch.object(sys, 'argv', argv), patch.object(runner.subprocess, 'run') as run, \
                    patch.object(runner.subprocess, 'Popen') as popen, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    runner.main()
                self.assertEqual(failure.exception.code, 2)
                run.assert_not_called()
                popen.assert_not_called()
            self.assertEqual(evidence.read_text(encoding='utf-8'), 'immutable prior evidence')
            self.assertEqual(list(output.iterdir()), [evidence])


if __name__ == '__main__':
    unittest.main()
