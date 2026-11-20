import os
import shutil
import subprocess
from pathlib import Path

lock_dir = Path(r"D:\DevData\Android\avd\UHabit_Next_Dev_API35.avd")
pid_file = lock_dir / "hardware-qemu.ini.lock" / "pid"
if pid_file.exists():
    pid_str = pid_file.read_text().strip()
    if pid_str.isdigit():
        pid = int(pid_str)
        # Check if process is alive via tasklist
        res = subprocess.run(["tasklist", "/FI", f"PID eq {pid}"], capture_output=True, text=True)
        if str(pid) not in res.stdout:
            print(f"PID {pid} is dead. Cleaning stale locks...")
            shutil.rmtree(lock_dir / "hardware-qemu.ini.lock", ignore_errors=True)
            (lock_dir / "multiinstance.lock").unlink(missing_ok=True)
            print("Stale locks successfully cleaned.")
        else:
            print(f"PID {pid} is still alive!")
else:
    (lock_dir / "multiinstance.lock").unlink(missing_ok=True)
    print("Multiinstance lock checked.")
