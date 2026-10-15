"""Iteration 19 arm runner with automated pre-flight health assertions and host monitoring."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import re
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from host_monitor import HostMonitor

ROOT = Path(__file__).resolve().parents[2]
PROCESSOR = Path("C:/Users/Pavel/.codex/skills/perfetto/bin/trace_processor")

def check_guest_health(output, label):
    output = output / 'health'
    output.mkdir(exist_ok=True)
    """Asserts that emulator guest is responsive, idle, and sensor daemon is calm."""
    cmd = ["adb", "-s", "emulator-5580", "shell", "-n", "-T", "getprop ro.boot.qemu.avd_name && getprop ro.serialno && settings get global airplane_mode_on"]
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    lines = [line.strip() for line in res.stdout.strip().splitlines() if line.strip()]
    assert len(lines) == 3, f"Unexpected props: {lines}"
    assert lines[0] == "UHabit_UI_Review_Disposable_20261011", f"Wrong AVD: {lines[0]}"
    assert lines[1] == "EMULATOR36X6X11X0", f"Wrong serial: {lines[1]}"
    assert lines[2] == "1", f"Airplane mode not on: {lines[2]}"

    # Check top for runaway processes and overall idle
    top_cmd = ["adb", "-s", "emulator-5580", "shell", "-n", "-T", "top -n 2 -d 2 -b -m 8"]
    top_res = subprocess.run(top_cmd, capture_output=True, text=True, check=True)
    top_out = top_res.stdout
    (output / (label + '.guest-health.txt')).write_text(top_out)
    last_sample = top_out.rsplit('Tasks:', 1)[-1]
    if "sensors-service" in last_sample:
        for line in last_sample.splitlines():
            if "sensors-service" in line:
                parts = line.split()
                try:
                    cpu_idx = 8
                    cpu_val = float(parts[cpu_idx].replace('%', ''))
                    if cpu_val > 5.0:
                        raise RuntimeError(f"Sensor daemon spinning at {cpu_val}% CPU: {line}")
                except (ValueError, IndexError):
                    pass
    cpu_lines = [line for line in top_out.splitlines() if '%cpu' in line]
    idle = re.search(r'([\d.]+)%idle', cpu_lines[-1])
    if idle is None or float(idle.group(1)) < 375:
        raise RuntimeError('Guest not idle enough: ' + cpu_lines[-1])
    print("Pre-flight guest health check PASSED.")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--arm-type', choices=['baseline', 'candidate'], required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--history-days', choices=['90', '1825'], default='90')
    parser.add_argument('--sort', choices=['grouped', 'entrySensitive'], default='grouped')
    parser.add_argument('--reverse', choices=['true', 'false'], default='false')
    parser.add_argument('--samples', type=int, default=4)
    parser.add_argument('--mixed-cycles', type=int, default=3)
    parser.add_argument('--workload', choices=['full', 'warmOnly'], default='full')
    parser.add_argument('--resort-diagnostics-enabled', choices=['true', 'false'], default='false')
    parser.add_argument('--coarse-resort-spans-enabled', choices=['true', 'false'], default='false')
    parser.add_argument('--output', type=Path, default=Path("scratch/iteration19/runs"))
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    apk_root = ROOT / f"scratch/iteration19/{args.arm_type}"
    config = ROOT / "scratch/iteration19/post-navigation-capture-600s.config"
    if not config.exists():
        config = ROOT / "tools/qa/post-navigation-capture.config"

    print(f"=== Starting Iteration 19 Arm: {args.label} ({args.arm_type}) ===")
    print(f"History: {args.history_days}d, Sort: {args.sort}, Reverse: {args.reverse}, Samples: {args.samples}, Diagnostics: {args.resort_diagnostics_enabled}")

    check_guest_health(args.output, args.label)

    monitor = HostMonitor(interval_seconds=1.0)
    monitor.start()
    start_time = time.time()

    cmd = [
        sys.executable, str(ROOT / "tools/qa/run_post_navigation_attribution.py"),
        "--apk-root", str(apk_root),
        "--label", args.label,
        "--reverse", args.reverse,
        "--output", str(args.output),
        "--config", str(config),
        "--history-days", args.history_days,
        "--sort", args.sort,
        "--samples", str(args.samples),
        "--mixed-cycles", str(args.mixed_cycles),
        "--workload", args.workload,
        "--resort-diagnostics-enabled", args.resort_diagnostics_enabled,
        "--coarse-resort-spans-enabled", args.coarse_resort_spans_enabled,
        "--range-probe-cycles", "0"
    ]

    try:
        run_res = subprocess.run(cmd, capture_output=True, text=True, check=True)
        print(run_res.stdout)
    except subprocess.CalledProcessError as e:
        print("ERROR running arm:")
        print(e.stdout)
        print(e.stderr)
        raise
    finally:
        monitor.stop()
        elapsed = time.time() - start_time
        host_summary = monitor.summary()
        host_summary['armElapsedSeconds'] = round(elapsed, 2)
        host_summary['label'] = args.label
        host_summary['armType'] = args.arm_type
        (args.output / f"{args.label}.host-env.json").write_text(json.dumps(host_summary, indent=2), encoding='utf-8')
        print(f"Host monitor: {host_summary['sampleCount']} samples, CPU mean: {host_summary['cpuPercent']['mean']}%, Max: {host_summary['cpuPercent']['max']}%, MemLoad: {host_summary['memoryLoadPercent']['mean']}%")

    # Run trace analysis
    print(f"Running trace analysis for {args.label}...")
    source_json = args.output / f"{args.label}.json"
    analyze_cmd = [
        sys.executable, str(ROOT / "tools/qa/analyze_post_navigation_attribution.py"),
        "--source", str(source_json),
        "--processor", str(PROCESSOR),
        "--skip-context-trace",
        "--analysis-name", "final"
    ]
    analysis_res = subprocess.run(analyze_cmd, capture_output=True, text=True, check=True)
    print(analysis_res.stdout)
    print(f"=== Completed Arm: {args.label} in {elapsed:.1f}s ===")

if __name__ == '__main__':
    main()
