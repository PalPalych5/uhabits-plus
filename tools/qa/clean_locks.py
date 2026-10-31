"""Report emulator locks; never remove them while ownership is uncertain."""
import os
from pathlib import Path

avd_home = Path(os.environ.get("ANDROID_AVD_HOME", r"D:\DevData\Android\avd"))
lock_dir = avd_home / "UHabit_UI_Review_Disposable_20261011.avd"
for lock in lock_dir.glob("*.lock"):
    print(f"Preserved lock: {lock}")
print("No locks removed. Close the owning emulator and verify ownership before manual recovery.")
