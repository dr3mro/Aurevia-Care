import os
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")


class KillSwitchTests(unittest.TestCase):
    def test_swarm_enabled_defaults_to_true(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("SWARM_ENABLED", None)
            self.assertTrue(swarm.swarm_enabled())

    def test_swarm_enabled_false_values(self):
        for value in ("0", "false", "False", "no", "off", "  Off  "):
            with mock.patch.dict(os.environ, {"SWARM_ENABLED": value}):
                self.assertFalse(swarm.swarm_enabled(), msg=value)

    def test_swarm_enabled_true_values(self):
        for value in ("", "1", "true", "yes", "on"):
            with mock.patch.dict(os.environ, {"SWARM_ENABLED": value}):
                if value == "":
                    os.environ.pop("SWARM_ENABLED", None)
                self.assertTrue(swarm.swarm_enabled(), msg=value)

    def test_single_task_mode_defaults_to_false(self):
        os.environ.pop("SWARM_SINGLE_TASK", None)
        self.assertFalse(swarm.single_task_mode())

    def test_single_task_mode_true_values(self):
        for value in ("1", "true", "yes", "on"):
            with mock.patch.dict(os.environ, {"SWARM_SINGLE_TASK": value}):
                self.assertTrue(swarm.single_task_mode(), msg=value)

    def test_plan_workers_returns_empty_when_disabled(self):
        with mock.patch.dict(os.environ, {"SWARM_ENABLED": "false"}):
            self.assertEqual(
                swarm.plan_workers(2, [{"slot": 1}, {"slot": 2}], "text", []),
                [],
            )

    def test_continue_swarm_returns_false_when_disabled(self):
        with mock.patch.dict(os.environ, {"SWARM_ENABLED": "false"}):
            self.assertFalse(swarm.continue_swarm("text", []))

    def test_top_up_returns_false_when_disabled(self):
        with mock.patch.dict(os.environ, {"SWARM_ENABLED": "false"}):
            self.assertFalse(swarm.top_up("CW-001"))

    def test_top_up_returns_false_in_single_mode(self):
        with mock.patch.dict(os.environ, {"SWARM_SINGLE_TASK": "true"}):
            self.assertFalse(swarm.top_up("CW-001"))

    def test_swarm_enabled_accepts_uppercase_and_padded_disable_values(self):
        for value in ("False", "FALSE", "  Off  "):
            with mock.patch.dict(os.environ, {"SWARM_ENABLED": value}):
                self.assertFalse(swarm.swarm_enabled(), msg=value)

    def test_single_task_mode_accepts_uppercase_and_padded_enable_values(self):
        for value in ("TRUE", " On ", "YES"):
            with mock.patch.dict(os.environ, {"SWARM_SINGLE_TASK": value}):
                self.assertTrue(swarm.single_task_mode(), msg=value)

    def test_issues_enabled_accepts_uppercase_disable_values(self):
        with mock.patch.dict(os.environ, {"SWARM_ISSUES": "FALSE"}):
            self.assertFalse(swarm.issues_enabled())

    def test_protected_override_accepts_uppercase_enable_values(self):
        with mock.patch.dict(os.environ, {"SWARM_ALLOW_PROTECTED": "TRUE"}):
            self.assertTrue(swarm.protected_override())