import subprocess
import time
import sys

adb_exe = "adb"
serial = "emulator-5580"
deadline = time.time() + 180

while time.time() < deadline:
    time.sleep(3)
    try:
        res = subprocess.run([adb_exe, "-s", serial, "shell", "-n", "-T", "getprop sys.boot_completed"],
                             capture_output=True, text=True, timeout=5)
        if res.stdout.strip() == "1":
            print("Boot completed!")
            sys.exit(0)
    except Exception:
        pass

print("Boot timed out!")
sys.exit(1)
