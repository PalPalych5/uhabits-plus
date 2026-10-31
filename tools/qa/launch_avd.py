"""Launch the persistent QA guest through the shared device/port lease guard."""
import os
from pathlib import Path
import subprocess
import sys

wrapper = Path(__file__).resolve().with_name("with-avd.ps1")
env = os.environ.copy()
env["ANDROID_HOME"] = env.get("ANDROID_HOME") or os.path.join(
    env["LOCALAPPDATA"], "Android", "Sdk"
)
env.setdefault("ANDROID_AVD_HOME", r"D:\DevData\Android\avd")
powershell = os.path.join(
    env["SYSTEMROOT"], "System32", "WindowsPowerShell", "v1.0", "powershell.exe"
)
# Only the local script path is interpolated; escape PowerShell single quotes.
script_path = str(wrapper).replace("'", "''")
command = (
    f"& '{script_path}' -AvdName UHabit_UI_Review_Disposable_20261011 "
    "-Port 5580 -Headless -Action { param($serial) "
    "Write-Output \"QA AVD ready on $serial\" }"
)
sys.exit(subprocess.call(
    [powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "RemoteSigned", "-Command", command], env=env
))
