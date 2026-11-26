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

    def test_max_task_mode_defaults_to_false(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("SWARM_MAX_TASK", None)
            self.assertFalse(swarm.max_task_mode())
            self.assertEqual(swarm.wave_workers(), 1)
            self.assertEqual(swarm.concurrent_wave_limit(), 1)

    def test_max_task_mode_true_values(self):
        for value in ("1", "true", "yes", "on"):
            with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": value}):
                self.assertTrue(swarm.max_task_mode(), msg=value)

    def test_legacy_single_task_variable_is_ignored(self):
        with mock.patch.dict(os.environ, {"SWARM_SINGLE_TASK": "true"}, clear=False):
            os.environ.pop("SWARM_MAX_TASK", None)
            self.assertFalse(swarm.max_task_mode())

    def test_configured_wave_limits(self):
        env = {"SWARM_MAX_TASK": "true", "SWARM_MAX_WORKERS_PER_WAVE": "3",
               "SWARM_MAX_WAVE_COUNT": "2"}
        with mock.patch.dict(os.environ, env):
            self.assertEqual(swarm.wave_workers(), 3)
            self.assertEqual(swarm.concurrent_wave_limit(), 2)
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "true",
                                          "SWARM_MAX_WORKERS_PER_WAVE": "bad"}):
            self.assertEqual(swarm.wave_workers(), 1)

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

    def test_top_up_returns_false_in_bounded_mode(self):
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "false"}):
            self.assertFalse(swarm.top_up("CW-001"))

    def test_swarm_enabled_accepts_uppercase_and_padded_disable_values(self):
        for value in ("False", "FALSE", "  Off  "):
            with mock.patch.dict(os.environ, {"SWARM_ENABLED": value}):
                self.assertFalse(swarm.swarm_enabled(), msg=value)

    def test_max_task_mode_accepts_uppercase_and_padded_enable_values(self):
        for value in ("TRUE", " On ", "YES"):
            with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": value}):
                self.assertTrue(swarm.max_task_mode(), msg=value)

    def test_issues_enabled_accepts_uppercase_disable_values(self):
        with mock.patch.dict(os.environ, {"SWARM_ISSUES": "FALSE"}):
            self.assertFalse(swarm.issues_enabled())

    def test_protected_override_accepts_uppercase_enable_values(self):
        with mock.patch.dict(os.environ, {"SWARM_ALLOW_PROTECTED": "TRUE"}):
            self.assertTrue(swarm.protected_override())