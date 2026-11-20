import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import qa_safety


class QaSafetyTest(unittest.TestCase):
    def test_rejects_missing_lease_and_wrong_target_without_adb(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(qa_safety.subprocess, "run") as run:
            for serial, package in [(None, qa_safety.PACKAGE), ("physical", qa_safety.PACKAGE),
                                    ("emulator-5580", "other.package"), ("emulator-5580", qa_safety.PACKAGE)]:
                with self.assertRaises(RuntimeError):
                    qa_safety.verify_disposable("adb", serial, package)
            run.assert_not_called()

    def test_checks_lease_identity_boot_and_package(self):
        run_id = "a" * 32
        record = {"runId": run_id, "temporary": True, "status": "running", "port": 5580,
                  "avd": "UHabit_Temp_" + run_id, "ownerPid": 123, "ownerStartedUtc": "2026-01-01T00:00:00Z"}
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "lease.json"
            manifest.write_text(json.dumps(record), encoding="utf-8")
            env = {"UHABIT_QA_RUN_ID": run_id, "UHABIT_QA_MANIFEST": str(manifest)}
            responses = ["device", "1", "1", "Service activity: found", record["avd"] + "\nOK", "package:/data/app/base.apk"]
            with patch.dict(os.environ, env, clear=True), patch.object(qa_safety, "_owner_alive", return_value=False), patch.object(qa_safety.subprocess, "run") as run:
                with self.assertRaises(RuntimeError):
                    qa_safety.verify_disposable("adb", "emulator-5580")
                run.assert_not_called()
            with patch.dict(os.environ, env, clear=True), patch.object(qa_safety, "_owner_alive", return_value=True), patch.object(qa_safety, "_owner_is_ancestor", return_value=False), patch.object(qa_safety.subprocess, "run") as run:
                with self.assertRaises(RuntimeError):
                    qa_safety.verify_disposable("adb", "emulator-5580")
                run.assert_not_called()
            with patch.dict(os.environ, env, clear=True), patch.object(qa_safety, "_owner_alive", return_value=True), patch.object(qa_safety, "_owner_is_ancestor", return_value=True), patch.object(qa_safety.subprocess, "run") as run:
                run.side_effect = [unittest.mock.Mock(stdout=value) for value in responses]
                self.assertEqual(record["avd"], qa_safety.verify_disposable("adb", "emulator-5580"))
                self.assertEqual(6, run.call_count)
                run.reset_mock()
                run.side_effect = [unittest.mock.Mock(stdout=value) for value in ["device", "1", "0"]]
                with self.assertRaises(RuntimeError):
                    qa_safety.verify_disposable("adb", "emulator-5580")
                run.side_effect = [unittest.mock.Mock(stdout=value) for value in ["device", "1", "1", "Service activity: found", "wrong-avd"]]
                with self.assertRaises(RuntimeError):
                    qa_safety.verify_disposable("adb", "emulator-5580")
                run.side_effect = [unittest.mock.Mock(stdout=value) for value in ["device", "1", "1", "Service activity: not found"]]
                with self.assertRaises(RuntimeError):
                    qa_safety.verify_disposable("adb", "emulator-5580")
                run.side_effect = [unittest.mock.Mock(stdout=value) for value in ["device", "1", "1", "Service activity: found", record["avd"], ""]]
                with self.assertRaisesRegex(RuntimeError, "package"):
                    qa_safety.verify_disposable("adb", "emulator-5580")


if __name__ == "__main__":
    unittest.main()
