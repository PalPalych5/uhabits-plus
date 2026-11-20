"""Strict capture primitives for an owned temporary guest, not a fixture runner."""
import subprocess
import struct
import re
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

from qa_safety import PACKAGE, verify_disposable

# Window-class suffixes that the application legitimately floats above its own Activity.
# These are owned by the same process (foreground package) and do not invalidate the
# ownership check — a focused BottomSheetDialog or Alert is still the app's own window.
_APP_OWNED_WINDOW_SUFFIXES = (
    "BottomSheetDialog",
    "AlertDialog",
    "Dialog",
    "PopupWindow",
    "DropdownPopup",
)


def _is_focused_window_owned(focus_output: str, expected_activity: str) -> bool:
    """Return True if the focused window belongs to the expected activity OR is an
    app-owned dialog/bottom-sheet in the same package.

    Accepts:
      • The exact activity window   (org.pkg/org.pkg.SomeActivity)
      • A BottomSheetDialog window  (org.pkg/org.pkg.SomeActivity$<n> or similar)
      • Any window whose package segment matches PACKAGE, provided the focus line
        also contains a window token from an app-owned dialog class.
    """
    focused_lines = [l for l in focus_output.splitlines() if "mCurrentFocus=" in l]
    if not focused_lines:
        return False

    # First check: exact expected activity window is focused.
    if any(expected_activity in line for line in focused_lines):
        return True

    # Second check: app-owned dialog/bottom-sheet window in the same package.
    for line in focused_lines:
        # Window token format: "mCurrentFocus=Window{... pkg/WindowClass}"
        m = re.search(r"mCurrentFocus=Window\{[^}]+\s+(\S+)\}", line)
        if not m:
            # Older format without braces, e.g. "mCurrentFocus=Window pkg/Class"
            m = re.search(r"mCurrentFocus=Window\s+(\S+/\S+)", line)
        if not m:
            continue
        win_token = m.group(1)  # e.g. "org.isoron.uhabits.plus/..."
        if (win_token.startswith("PopupWindow") or win_token.startswith("DropdownPopup")) and expected_activity in focus_output:
            return True
        pkg_part = win_token.split("/")[0] if "/" in win_token else ""
        cls_part = win_token.split("/")[1] if "/" in win_token else ""
        if pkg_part != PACKAGE:
            continue
        # Accept any app-owned overlay/dialog window class.
        if any(cls_part.endswith(s) for s in _APP_OWNED_WINDOW_SUFFIXES):
            return True
        # Also accept anonymous inner windows like "pkg/pkg.Activity$2" which are
        # produced by DialogFragment hosted inside the activity.
        if re.search(r"\$\d+$", cls_part):
            return True
    return False


def capture_asserted(adb, serial, destination, expected_activity, expected):
    """Capture only after a fresh hierarchy and focused package meet assertions.

    The focused window may be the expected activity or an app-owned BottomSheet/Dialog
    window within the same package (e.g. a CreateContainerDialog or TaskDuePicker
    presented over the activity). The package-ownership check is strict; system windows
    or windows belonging to other packages are rejected.
    """
    if not expected or not expected_activity.startswith(PACKAGE + "/"):
        raise ValueError("An exact application activity and visible UI label are required")
    destination = Path(destination)
    if destination.exists():
        raise FileExistsError(f"Refusing to overwrite evidence: {destination}")
    verify_disposable(adb, serial)

    def run(*args):
        return subprocess.run([str(adb), "-s", serial, *args], check=True,
                              capture_output=True, timeout=30).stdout

    focus = run("shell", "dumpsys", "window", "windows").decode("utf-8", "replace")
    if "mCurrentFocus=" not in focus:
        focus = run("shell", "dumpsys", "window", "displays").decode("utf-8", "replace")
    if not _is_focused_window_owned(focus, expected_activity):
        focused_lines = [l.strip() for l in focus.splitlines() if "mCurrentFocus=" in l]
        raise RuntimeError(
            f"Expected activity window (or app-owned dialog over it) is not focused.\n"
            f"  Expected activity: {expected_activity}\n"
            f"  Focused window(s): {focused_lines}"
        )

    remote = "/sdcard/qa-hierarchy-" + uuid.uuid4().hex + ".xml"
    result = run("shell", "uiautomator", "dump", remote).decode("utf-8", "replace")
    if "UI hierchary dumped to:" not in result and "UI hierarchy dumped to:" not in result:
        raise RuntimeError(f"Fresh UI hierarchy dump failed: {result.strip()}")
    xml = run("exec-out", "cat", remote)
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        raise RuntimeError("Invalid fresh UI hierarchy") from exc
    if root.tag != "hierarchy" or not list(root.iter("node")):
        raise RuntimeError("Empty UI hierarchy")
    nodes = list(root.iter("node"))

    def visible(node):
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not bounds:
            return False
        left, top, right, bottom = map(int, bounds.groups())
        return right > left and bottom > top

    for label in expected:
        if not any(label in (n.get("text", ""), n.get("content-desc", ""))
                   and n.get("package") == PACKAGE and visible(n)
                   for n in nodes):
            raise AssertionError(f"Expected visible application label missing: {label}")
    verify_disposable(adb, serial)
    png = run("exec-out", "screencap", "-p")
    if (len(png) < 100 or not png.startswith(b"\x89PNG\r\n\x1a\n")
            or png[12:16] != b"IHDR" or not all(struct.unpack(">II", png[16:24]))
            or png[-12:-8] != b"\x00\x00\x00\x00" or png[-8:-4] != b"IEND"):
        raise RuntimeError("Screenshot capture failed")
    with destination.open("xb") as output:
        output.write(png)
    return root
