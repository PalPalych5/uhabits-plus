"""Run one isolated disposable offline arm; preserve all previous evidence."""
import argparse
import json
import subprocess
from pathlib import Path

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk-root', type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--reverse', choices=['true', 'false'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--history-days', choices=['90', '1825'], default='90')
    parser.add_argument('--sort', choices=['grouped', 'entrySensitive'], default='grouped')
    parser.add_argument('--samples', type=int, default=2)
    parser.add_argument('--mixed-cycles', type=int, default=10)
    parser.add_argument('--statistics-only-cycles', choices=['true', 'false'], default='false')
    parser.add_argument('--workload', choices=['full', 'warmOnly'], default='full')
    parser.add_argument('--resort-diagnostics-enabled', choices=['true', 'false'], default='true')
    parser.add_argument('--range-probe-cycles', type=int, choices=range(4), default=3)
    args = parser.parse_args()
    if not 2 <= args.samples <= 8:
        parser.error('Use 2..8 samples per scenario')
    if not 1 <= args.mixed_cycles <= 10:
        parser.error('Use 1..10 mixed navigation cycles')
    if not args.label.replace('-', '').isalnum():
        parser.error('Use an alphanumeric arm label')
    args.output.mkdir(parents=True, exist_ok=True)
    stem = args.output / args.label
    if any(args.output.glob(args.label + '.*')):
        parser.error('Preserve prior arm evidence')
    stem.with_suffix('.attempt.json').write_text(json.dumps(dict(label=args.label, status='started',
        historyDays=args.history_days, sort=args.sort, reverse=args.reverse), indent=2), encoding='utf-8')
    def adb(*command, timeout=600):
        result = subprocess.run(['adb', '-s', 'emulator-5580', *command],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout, check=True)
        return result.stdout
    assert adb('shell', 'getprop ro.boot.qemu.avd_name').strip() == 'UHabit_UI_Review_Disposable_20261011'
    assert adb('shell', 'getprop ro.serialno').strip() == 'EMULATOR36X6X11X0'
    assert adb('shell', 'settings get global airplane_mode_on').strip() == '1'
    for apk in ['debug/uhabits-android-debug.apk', 'androidTest/debug/uhabits-android-debug-androidTest.apk']:
        print(adb('install', '-r', str(args.apk_root / apk)), flush=True)
    assert 'Success' in adb('shell', 'pm clear org.isoron.uhabits.plus')
    adb('shell', 'pm grant org.isoron.uhabits.plus android.permission.POST_NOTIFICATIONS')
    adb('shell', 'input keyevent KEYCODE_WAKEUP')
    adb('shell', 'wm dismiss-keyguard')
    adb('push', str(args.config), '/data/local/tmp/iteration12.config')
    device_trace = '/data/misc/perfetto-traces/' + args.label + '.perfetto'
    capture = subprocess.Popen(['adb', '-s', 'emulator-5580', 'shell',
        'cat /data/local/tmp/iteration12.config | perfetto --txt -c - -o ' + device_trace],
        stdout=stem.with_suffix('.capture.txt').open('w'), stderr=stem.with_suffix('.capture-error.txt').open('w'))
    result = ''
    instrumentation_error = None
    evidence = dict(label=args.label, instrumentation={}, nativePulls=[], cleanup=[], tracePulled=False)
    def exception_info(error):
        def decoded(value):
            return value.decode('utf-8', errors='replace') if isinstance(value, bytes) else value
        return dict(type=type(error).__name__, message=str(error),
                    timeoutSeconds=getattr(error, 'timeout', None),
                    returncode=getattr(error, 'returncode', None),
                    stdout=decoded(getattr(error, 'stdout', None)),
                    stderr=decoded(getattr(error, 'stderr', None)))
    try:
        result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'disposableAvd',
            'UHabit_UI_Review_Disposable_20261011', '-e', 'buildLabel', args.label,
            '-e', 'reverseOrder', args.reverse, '-e', 'historyDays', args.history_days,
            '-e', 'sortConfiguration', args.sort, '-e', 'sampleCount', str(args.samples),
            '-e', 'mixedCycleCount', str(args.mixed_cycles),
            '-e', 'statisticsOnlyCycles', args.statistics_only_cycles,
            '-e', 'workload', args.workload,
            '-e', 'resortDiagnosticsEnabled', args.resort_diagnostics_enabled,
            '-e', 'rangeProbeCycles', str(args.range_probe_cycles), '-e', 'class',
            'org.isoron.uhabits.qa.PostNavigationPresentationAttributionTest',
            'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner')
    except Exception as error:
        instrumentation_error = error
        evidence['instrumentation'] = exception_info(error)
        result = evidence['instrumentation'].get('stdout') or ''
    finally:
        # TimeoutExpired may contain bytes even when subprocess text=True.
        stem.with_suffix('.txt').write_text(result, encoding='utf-8')
        print(result, flush=True)
        passed = instrumentation_error is None and 'OK (1 test)' in result
        evidence['instrumentation']['passed'] = passed
        evidence['status'] = 'collecting-evidence'
        stem.with_suffix('.attempt.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
        folder = '/sdcard/Android/data/org.isoron.uhabits.plus/files/remaining-latency/'
        preferred = '.json' if passed else '-failure.json'
        suffixes = [preferred, '-failure.json' if passed else '.json']
        native_pulled = False
        # Both known outcomes are attempted; absence of the alternative is expected.
        for suffix in suffixes:
            remote = folder + args.label + '-' + args.history_days + '-true' + suffix
            local = stem.with_suffix('.native-alternate.json' if native_pulled else '.json')
            record = dict(remote=remote, local=str(local))
            try:
                record['output'] = adb('pull', remote, str(local), timeout=60)
                record['pulled'] = True
                native_pulled = True
            except Exception as error:
                record.update(pulled=False, error=exception_info(error))
                if local.exists():
                    partial = stem.with_suffix('.native-partial-' + str(len(evidence['nativePulls'])) + '.json')
                    local.rename(partial)
                    record['partialLocal'] = str(partial)
            evidence['nativePulls'].append(record)
        # Never let an artifact-pull failure bypass capture shutdown and trace pull.
        try:
            pid = adb('shell', 'pidof perfetto', timeout=30).strip() if capture.poll() is None else ''
            if pid:
                if not pid.isdecimal():
                    raise RuntimeError('Multiple Perfetto processes; cannot identify this capture')
                commandline = adb('shell', 'cat', '/proc/' + pid + '/cmdline', timeout=30)
                if device_trace not in commandline.split('\x00'):
                    raise RuntimeError('Perfetto command line does not identify this attempt output; refusing signal')
                evidence['cleanup'].append(dict(pid=pid, ownership='exact output-path argument',
                    output=adb('shell', 'kill', '-INT', pid, timeout=30)))
        except Exception as error:
            evidence['cleanup'].append(dict(error=exception_info(error)))
        try:
            evidence['captureReturncode'] = capture.wait(timeout=240)
        except Exception as error:
            evidence['cleanup'].append(dict(error=exception_info(error)))
        try:
            evidence['tracePullOutput'] = adb('pull', device_trace, str(stem.with_suffix('.perfetto')), timeout=120)
            evidence['tracePulled'] = True
        except Exception as error:
            evidence['tracePullError'] = exception_info(error)
        evidence['nativePulled'] = native_pulled
        evidence['status'] = 'passed' if passed and native_pulled and evidence['tracePulled'] and not any('error' in x for x in evidence['cleanup']) else 'failed'
        stem.with_suffix('.attempt.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    if evidence['status'] != 'passed':
        raise RuntimeError('Arm failed; partial stdout, timeout/errors and available artifacts retained') from instrumentation_error

if __name__ == '__main__':
    main()
