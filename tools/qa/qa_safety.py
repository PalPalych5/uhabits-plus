"""Boundaries for future QA runners; never reset or repair application data."""
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "org.isoron.uhabits.plus"


def _owner_alive(pid, started):
    """Check both PID and creation time to reject dead or recycled owners."""
    if os.name != "nt" or not isinstance(pid, int) or not isinstance(started, str):
        return False
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    try:
        creation, exit_time, kernel_time, user_time = (wintypes.FILETIME() for _ in range(4))
        if not kernel.GetProcessTimes(handle, ctypes.byref(creation), ctypes.byref(exit_time),
                                      ctypes.byref(kernel_time), ctypes.byref(user_time)):
            return False
        if exit_time.dwLowDateTime or exit_time.dwHighDateTime:
            return False
        ticks = (creation.dwHighDateTime << 32) | creation.dwLowDateTime
        actual = (ticks - 116444736000000000) / 10_000_000
        expected = datetime.fromisoformat(started.replace("Z", "+00:00")).astimezone(timezone.utc).timestamp()
        return abs(actual - expected) < 0.001
    except (ValueError, OverflowError):
        return False
    finally:
        kernel.CloseHandle(handle)


def legacy_runner_disabled(name):
    raise SystemExit(f"{Path(name).name} is quarantined: replace its unsafe fixture and device handling before use.")


def _owner_is_ancestor(owner_pid):
    """Require this runner to descend from the live lifecycle owner on Windows."""
    if os.name != "nt" or not isinstance(owner_pid, int):
        return False
    import ctypes
    from ctypes import wintypes

    class ProcessEntry(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("usage", wintypes.DWORD),
                    ("pid", wintypes.DWORD), ("heap", ctypes.c_size_t),
                    ("module", wintypes.DWORD), ("threads", wintypes.DWORD),
                    ("parent", wintypes.DWORD), ("priority", ctypes.c_long),
                    ("flags", wintypes.DWORD), ("name", wintypes.WCHAR * 260)]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
    kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    kernel.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessEntry)]
    kernel.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessEntry)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.CreateToolhelp32Snapshot(2, 0)
    if handle == ctypes.c_void_p(-1).value:
        return False
    parents = {}
    try:
        entry = ProcessEntry()
        entry.size = ctypes.sizeof(entry)
        available = kernel.Process32FirstW(handle, ctypes.byref(entry))
        while available:
            parents[entry.pid] = entry.parent
            available = kernel.Process32NextW(handle, ctypes.byref(entry))
    finally:
        kernel.CloseHandle(handle)
    current = os.getpid()
    visited = set()
    while current in parents and current not in visited:
        if current == owner_pid:
            return True
        visited.add(current)
        current = parents[current]
    return current == owner_pid


def verify_disposable(adb, serial, package=PACKAGE):
    """Verify the current with-avd temporary lease and a booted, exact guest.

    This is an accident-prevention boundary, not a security boundary against a
    malicious local process. The caller must recheck before sensitive actions.
    """
    if not re.fullmatch(r"emulator-[0-9]+", serial or "") or package != PACKAGE:
        raise RuntimeError("Explicit emulator serial and exact QA package are required")
    run_id = os.environ.get("UHABIT_QA_RUN_ID", "")
    manifest_path = os.environ.get("UHABIT_QA_MANIFEST", "")
    if not re.fullmatch(r"[0-9a-f]{32}", run_id) or not manifest_path:
        raise RuntimeError("Missing owned temporary AVD lease")
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8-sig"))
    if (manifest.get("runId") != run_id or manifest.get("temporary") is not True
            or manifest.get("status") != "running"
            or manifest.get("avd") != "UHabit_Temp_" + run_id
            or serial != f"emulator-{manifest.get('port')}"
            or not _owner_alive(manifest.get("ownerPid"), manifest.get("ownerStartedUtc"))
            or not _owner_is_ancestor(manifest.get("ownerPid"))):
        raise RuntimeError("Temporary AVD lease does not match target")

    def call(*args):
        clean_args = ["shell", "-n", "-T"] + list(args[1:]) if args and args[0] == "shell" else list(args)
        return subprocess.run([str(adb), "-s", serial, *clean_args], check=True,
                              capture_output=True, text=True, timeout=15).stdout.strip()

    if call("get-state") != "device":
        raise RuntimeError("Device is not online")
    if call("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Target is not an emulator")
    if call("shell", "getprop", "sys.boot_completed") != "1":
        raise RuntimeError("Emulator is not fully booted")
    if call("shell", "service", "check", "activity") != "Service activity: found":
        raise RuntimeError("Android activity service is not ready")
    if call("emu", "avd", "name").splitlines()[0].strip() != manifest["avd"]:
        raise RuntimeError("Actual AVD identity does not match lease")
    installed = False
    for _ in range(10):
        try:
            res = subprocess.run([str(adb), "-s", serial, "shell", "-n", "-T", "pm", "path", package],
                                 capture_output=True, text=True, timeout=10)
            if res.returncode == 0 and res.stdout.strip().startswith("package:"):
                installed = True
                break
        except Exception:
            pass
        import time; time.sleep(1)
    if not installed:
        raise RuntimeError("Expected application package is not installed")
    return manifest["avd"]
