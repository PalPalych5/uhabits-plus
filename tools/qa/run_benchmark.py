import subprocess
import os
import sys
import time

adb = "adb"
serial = "emulator-5580"
avd_token = "UHabit_UI_Review_Disposable_20261011"
variant = sys.argv[1] if len(sys.argv) > 1 else "baseline"
disabled_side_effects = sys.argv[2] if len(sys.argv) > 2 else ""

def adb_cmd(args, timeout=300):
    return subprocess.run([adb, "-s", serial] + args, capture_output=True, text=True, timeout=timeout)

print(f"=== Running Benchmark: variant={variant}, disabled={disabled_side_effects} ===")

# 1. Clear app data & grant permissions
print("Resetting app state...")
adb_cmd(["shell", "-n", "-T", "pm clear org.isoron.uhabits.plus"])
adb_cmd(["shell", "-n", "-T", "pm grant org.isoron.uhabits.plus android.permission.POST_NOTIFICATIONS"])
adb_cmd(["shell", "-n", "-T", "input keyevent KEYCODE_WAKEUP"])
adb_cmd(["shell", "-n", "-T", "wm dismiss-keyguard"])

# 2. Clean previous trace if any
adb_cmd(["shell", "-n", "-T", "rm -f /data/misc/perfetto-traces/interaction.perfetto"])

# 3. Start Perfetto trace in background
print("Starting Perfetto background trace...")
perfetto_res = adb_cmd(["shell", "-n", "-T",
    "perfetto -d -t 90s -s 64mb -o /data/misc/perfetto-traces/interaction.perfetto sched freq am wm gfx view binder_driver"
])
print("Perfetto start output:", perfetto_res.stdout.strip()[:100])

# 4. Run instrumentation
instrument_args = [
    "shell", "-n", "-T",
    f"am instrument -w -e class org.isoron.uhabits.qa.HabitInteractionLagBenchmarkTest#runInteractionLagBenchmark -e disposableAvd {avd_token} -e benchmarkVariant {variant}"
]
if disabled_side_effects:
    instrument_args[-1] += f" -e isolation true -e disableSideEffects {disabled_side_effects}"
instrument_args[-1] += " org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner"

print("Starting instrumentation...")
start_time = time.time()
inst_res = adb_cmd(instrument_args, timeout=300)
elapsed = time.time() - start_time
print(f"Instrumentation finished in {elapsed:.1f}s")
print("Instrumentation stdout:")
print(inst_res.stdout)
if inst_res.stderr:
    print("Instrumentation stderr:", inst_res.stderr)

# 5. Pull benchmark JSON and Perfetto trace
os.makedirs("scratch/habit-interaction-lag", exist_ok=True)
json_device_path = f"/sdcard/Android/data/org.isoron.uhabits.plus/files/interaction-lag/benchmark-{variant}.json"
local_json_path = f"scratch/habit-interaction-lag/benchmark-{variant}.json"
pull_json = adb_cmd(["pull", json_device_path, local_json_path])
print(f"Pulled benchmark JSON: {pull_json.stdout.strip()}")

trace_device_path = "/data/misc/perfetto-traces/interaction.perfetto"
local_trace_path = f"scratch/habit-interaction-lag/{variant}_interaction.perfetto"
pull_trace = adb_cmd(["pull", trace_device_path, local_trace_path])
print(f"Pulled Perfetto trace: {pull_trace.stdout.strip()}")

print("Done!")
