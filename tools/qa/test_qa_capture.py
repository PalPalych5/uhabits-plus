import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import qa_capture
from qa_capture import _is_focused_window_owned

PACKAGE = "org.isoron.uhabits.plus"
ACTIVITY = f"{PACKAGE}/.activities.main.MainActivity"


class FocusedWindowOwnershipTest(unittest.TestCase):
    """Unit tests for _is_focused_window_owned without any device access."""

    def _focus(self, window_token):
        return f"  mCurrentFocus=Window{{abcd1234 {window_token}}}\n"

    def test_exact_activity_accepted(self):
        focus = self._focus(f"{PACKAGE}/.activities.main.MainActivity")
        self.assertTrue(_is_focused_window_owned(focus, ACTIVITY))

    def test_bottom_sheet_dialog_accepted(self):
        # App-owned BottomSheetDialog floating over the activity.
        focus = self._focus(f"{PACKAGE}/com.google.android.material.bottomsheet.BottomSheetDialog")
        self.assertTrue(_is_focused_window_owned(focus, ACTIVITY))

    def test_alert_dialog_accepted(self):
        focus = self._focus(f"{PACKAGE}/android.app.AlertDialog")
        self.assertTrue(_is_focused_window_owned(focus, ACTIVITY))

    def test_anonymous_inner_dialog_accepted(self):
        # DialogFragment hosted inside the activity creates anonymous inner class windows.
        focus = self._focus(f"{PACKAGE}/.activities.containers.ContainersActivity$3")
        self.assertTrue(_is_focused_window_owned(focus, ACTIVITY))

    def test_popup_window_accepted(self):
        focus = self._focus(f"{PACKAGE}/android.widget.PopupWindow")
        self.assertTrue(_is_focused_window_owned(focus, ACTIVITY))

    def test_real_popup_window_hash_token_accepted(self):
        focus = f"  mCurrentFocus=Window{{4d006f u0 PopupWindow:16a4fe2}}\n  mFocusedApp=AppWindowToken{{1234 {ACTIVITY}}}\n"
        self.assertTrue(_is_focused_window_owned(focus, ACTIVITY))

    def test_other_package_rejected(self):
        focus = self._focus("com.android.systemui/.recents.RecentsActivity")
        self.assertFalse(_is_focused_window_owned(focus, ACTIVITY))

    def test_other_package_dialog_rejected(self):
        # A BottomSheetDialog from another package must be rejected.
        focus = self._focus("com.another.app/com.google.android.material.bottomsheet.BottomSheetDialog")
        self.assertFalse(_is_focused_window_owned(focus, ACTIVITY))

    def test_empty_focus_output_rejected(self):
        self.assertFalse(_is_focused_window_owned("", ACTIVITY))

    def test_no_mCurrentFocus_line_rejected(self):
        self.assertFalse(_is_focused_window_owned("some other window dump line\n", ACTIVITY))


class CaptureTest(unittest.TestCase):
    def test_writes_only_after_fresh_expected_state(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(qa_capture, "verify_disposable") as verify, \
             patch.object(qa_capture.subprocess, "run") as run:
            png = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR"
                   b"\x00\x00\x00\x01\x00\x00\x00\x01" + b"x" * 100
                   + b"\x00\x00\x00\x00IEND\xaeB`\x82")
            run.side_effect = [Mock(stdout=b"mCurrentFocus=Window{ org.isoron.uhabits.plus/.MainActivity}"),
                               Mock(stdout=b"UI hierchary dumped to: /sdcard/fresh.xml"),
                               Mock(stdout=b'<hierarchy><node package="org.isoron.uhabits.plus" text="Create" bounds="[0,0][10,10]"/></hierarchy>'),
                               Mock(stdout=png)]
            path = Path(tmp) / "capture.png"
            qa_capture.capture_asserted("adb", "emulator-5580", path, "org.isoron.uhabits.plus/.MainActivity", ["Create"])
            self.assertEqual(png, path.read_bytes())
            self.assertEqual(2, verify.call_count)
            with self.assertRaises(FileExistsError):
                qa_capture.capture_asserted("adb", "emulator-5580", path, "org.isoron.uhabits.plus/.MainActivity", ["Create"])

    def test_bottom_sheet_over_activity_accepted(self):
        """A BottomSheetDialog from the same package must pass the focus check."""
        with tempfile.TemporaryDirectory() as tmp, patch.object(qa_capture, "verify_disposable"), \
             patch.object(qa_capture.subprocess, "run") as run:
            png = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR"
                   b"\x00\x00\x00\x01\x00\x00\x00\x01" + b"x" * 100
                   + b"\x00\x00\x00\x00IEND\xaeB`\x82")
            bs_focus = (b"mCurrentFocus=Window{abc123 org.isoron.uhabits.plus/"
                        b"com.google.android.material.bottomsheet.BottomSheetDialog}")
            run.side_effect = [Mock(stdout=bs_focus),
                               Mock(stdout=b"UI hierchary dumped to: /sdcard/fresh.xml"),
                               Mock(stdout='<hierarchy><node package="org.isoron.uhabits.plus" text="\u0421\u043e\u0437\u0434\u0430\u0442\u044c" bounds="[0,0][10,10]"/></hierarchy>'.encode("utf-8")),
                               Mock(stdout=png)]
            path = Path(tmp) / "bs_capture.png"
            qa_capture.capture_asserted(
                "adb", "emulator-5580", path,
                "org.isoron.uhabits.plus/.activities.main.MainActivity",
                ["Создать"]
            )
            self.assertTrue(path.exists())

    def test_rejects_null_root_despite_success_exit(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(qa_capture, "verify_disposable"), \
             patch.object(qa_capture.subprocess, "run") as run:
            run.side_effect = [Mock(stdout=b"mCurrentFocus=Window{ org.isoron.uhabits.plus/.MainActivity}"),
                               Mock(stdout=b"ERROR: null root node returned by UiTestAutomationBridge.")]
            path = Path(tmp) / "capture.png"
            with self.assertRaisesRegex(RuntimeError, "hierarchy dump failed"):
                qa_capture.capture_asserted("adb", "emulator-5580", path, "org.isoron.uhabits.plus/.MainActivity", ["Create"])
            self.assertFalse(path.exists())

    def test_rejects_wrong_window_and_missing_label(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(qa_capture, "verify_disposable"), \
             patch.object(qa_capture.subprocess, "run") as run:
            path = Path(tmp) / "capture.png"
            run.return_value = Mock(stdout=b"mCurrentFocus=Window{ other.package/.MainActivity}")
            with self.assertRaisesRegex(RuntimeError, "not focused"):
                qa_capture.capture_asserted("adb", "emulator-5580", path, "org.isoron.uhabits.plus/.MainActivity", ["Create"])
            run.side_effect = [Mock(stdout=b"mCurrentFocus=Window{ org.isoron.uhabits.plus/.MainActivity}"),
                               Mock(stdout=b"UI hierchary dumped to: /sdcard/fresh.xml"),
                               Mock(stdout=b'<hierarchy><node package="org.isoron.uhabits.plus" text="Other" bounds="[0,0][10,10]"/></hierarchy>')]
            with self.assertRaisesRegex(AssertionError, "missing"):
                qa_capture.capture_asserted("adb", "emulator-5580", path, "org.isoron.uhabits.plus/.MainActivity", ["Create"])
            self.assertFalse(path.exists())
            run.side_effect = [Mock(stdout=b"mCurrentFocus=Window{ org.isoron.uhabits.plus/.MainActivity}"),
                               Mock(stdout=b"UI hierchary dumped to: /sdcard/fresh.xml"),
                               Mock(stdout=b'<hierarchy><node package="org.isoron.uhabits.plus" text="Create" bounds="[3,3][3,3]"/></hierarchy>')]
            with self.assertRaisesRegex(AssertionError, "missing"):
                qa_capture.capture_asserted("adb", "emulator-5580", path, "org.isoron.uhabits.plus/.MainActivity", ["Create"])

    def test_rejects_dialog_from_other_package(self):
        """A BottomSheetDialog from a different package must still be rejected."""
        with tempfile.TemporaryDirectory() as tmp, patch.object(qa_capture, "verify_disposable"), \
             patch.object(qa_capture.subprocess, "run") as run:
            path = Path(tmp) / "capture.png"
            run.return_value = Mock(
                stdout=b"mCurrentFocus=Window{abc com.android.systemui/com.google.android.material.bottomsheet.BottomSheetDialog}"
            )
            with self.assertRaisesRegex(RuntimeError, "not focused"):
                qa_capture.capture_asserted("adb", "emulator-5580", path, "org.isoron.uhabits.plus/.MainActivity", ["OK"])


if __name__ == "__main__":
    unittest.main()
