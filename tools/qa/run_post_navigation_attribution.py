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
    args = parser.parse_args()
    if not 2 <= args.samples <= 8:
        parser.error('Use 2..8 samples per scenario')
    if not 1 <= args.mixed_cycles <= 10:
        parser.error('Use 1..10 mixed navigation cycles')
    if not args.label.replace('-', '').isalnum():
        parser.error('Use an alphanumeric arm label')
    args.output.mkdir(parents=True, exist_ok=True)
    stem = args.output / args.label
    if stem.with_suffix('.txt').exists():
        parser.error('Preserve prior arm evidence')
    def adb(*command, timeout=600):
        result = subprocess.run(['adb', '-s', 'emulator-5580', *command],
            capture_output=True, text=True, timeout=timeout, check=True)
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
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'disposableAvd',
        'UHabit_UI_Review_Disposable_20261011', '-e', 'buildLabel', args.label,
        '-e', 'reverseOrder', args.reverse, '-e', 'historyDays', args.history_days,
        '-e', 'sortConfiguration', args.sort, '-e', 'sampleCount', str(args.samples),
        '-e', 'mixedCycleCount', str(args.mixed_cycles),
        '-e', 'statisticsOnlyCycles', args.statistics_only_cycles, '-e', 'class',
        'org.isoron.uhabits.qa.PostNavigationPresentationAttributionTest',
        'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner')
    stem.with_suffix('.txt').write_text(result, encoding='utf-8')
    print(result, flush=True)
    folder = '/sdcard/Android/data/org.isoron.uhabits.plus/files/remaining-latency/'
    suffix = '-' + args.history_days + '-true' + ('.json' if 'OK (1 test)' in result else '-failure.json')
    print(adb('pull', folder + args.label + suffix, str(stem.with_suffix('.json'))), flush=True)
    # Stop only this capture on the guarded disposable device once input work ends.
    pid = adb('shell', 'pidof perfetto').strip() if capture.poll() is None else ''
    if pid:
        if not pid.isdecimal():
            raise RuntimeError('Multiple Perfetto processes; cannot identify this capture')
        adb('shell', 'kill', '-INT', pid)
    capture.wait(timeout=240)
    print(adb('pull', device_trace, str(stem.with_suffix('.perfetto'))), flush=True)
    if 'OK (1 test)' not in result:
        raise RuntimeError('Arm failed; evidence retained')

if __name__ == '__main__':
    main()
