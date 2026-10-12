import os
import subprocess
import time
import sys

repo = os.path.abspath(".")
avd_home = os.path.join(repo, "scratch", "ui-review", "avd-home-api35")
sdk = os.environ.get("LOCALAPPDATA", "") + r"\Android\Sdk"
emulator_exe = os.path.join(sdk, "emulator", "emulator.exe")
adb_exe = os.path.join(sdk, "platform-tools", "adb.exe")
serial = "emulator-5580"
avd_name = "UHabit_UI_Review_Disposable_20261011"

env = os.environ.copy()
env["ANDROID_AVD_HOME"] = avd_home

# Check if emulator is already running
try:
    res = subprocess.run([adb_exe, "-s", serial, "emu", "avd", "name"], capture_output=True, text=True, timeout=5)
    if avd_name in res.stdout:
        print(f"AVD {avd_name} already running on {serial}")
        sys.exit(0)
except Exception:
    pass

print(f"Starting emulator {avd_name} on {serial}...")
cmd = [
    emulator_exe,
    "-avd", avd_name,
    "-port", "5580",
    "-no-snapshot",
    "-no-audio",
    "-no-window",
    "-gpu", "swiftshader"
]

proc = subprocess.Popen(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

# Poll for boot_completed
print("Waiting for boot completion...")
deadline = time.time() + 180
booted = False
while time.time() < deadline:
    time.sleep(3)
    try:
        res = subprocess.run([adb_exe, "-s", serial, "shell", "-n", "-T", "getprop sys.boot_completed"], capture_output=True, text=True, timeout=5)
        if res.stdout.strip() == "1":
            booted = True
            break
    except Exception:
        pass

if not booted:
    print("ERROR: Boot timed out!")
    sys.exit(1)

print(f"Emulator {avd_name} successfully booted on {serial}!")
