"""CLI and entry-point coverage for scripts/monitor.py (OPS-06).

The monitor reads its configuration from ``sys.argv`` at import time and runs either
``once()`` or ``curses.wrapper(tui)`` in the module body. These tests load an isolated
copy of the script with controlled arguments and mocked ``gh``/``curses`` side effects so
the argument parsing, main dispatch, auto-start launch and exit paths run without a
terminal, without the network and without touching the shared ``monitor`` module used by
the other monitor coverage suites.
"""

import importlib.util
import os
import subprocess
import sys
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
MONITOR_PATH = str(SCRIPTS / "monitor.py")


class FakeGhProcess:
    """A minimal ``gh`` process: every section returns an empty JSON payload."""

    def __init__(self, *args, **kwargs):
        pass

    def communicate(self, timeout=None):
        return "[]", ""

    def kill(self):
        pass


class MonitorLoadMixin:
    """Load a fresh module instance of monitor.py with controlled argv and mocked I/O."""

    _load_counter = 0

    @classmethod
    def load_monitor(cls, argv, *, popen=FakeGhProcess):
        MonitorLoadMixin._load_counter += 1
        name = f"monitor_cli_case_{MonitorLoadMixin._load_counter}"
        spec = importlib.util.spec_from_file_location(name, MONITOR_PATH)
        module = importlib.util.module_from_spec(spec)
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(sys, "argv", list(argv)))
            wrapper = stack.enter_context(mock.patch("curses.wrapper"))
            printer = stack.enter_context(mock.patch("builtins.print"))
            stack.enter_context(mock.patch("subprocess.Popen", popen))
            stack.enter_context(mock.patch("shutil.get_terminal_size",
                                           return_value=os.terminal_size((120, 40))))
            spec.loader.exec_module(module)
        return module, wrapper, printer


class ArgumentParsingTest(MonitorLoadMixin, unittest.TestCase):
    """The module reads INTERVAL, RECENT, ONCE and the optional AUTOSTART from argv."""

    def test_parses_interval_recent_and_once_from_positional_arguments(self):
        module, wrapper, _ = self.load_monitor(["monitor.py", "7", "13", "0"])

        self.assertEqual(module.INTERVAL, 7)
        self.assertEqual(module.RECENT, 13)
        self.assertIs(module.ONCE, False)
        wrapper.assert_called_once_with(module.tui)

    def test_once_flag_enables_the_static_frame(self):
        module, wrapper, _ = self.load_monitor(["monitor.py", "30", "20", "1"])

        self.assertIs(module.ONCE, True)
        wrapper.assert_not_called()

    def test_autostart_defaults_to_zero_without_fifth_argument(self):
        module, _, _ = self.load_monitor(["monitor.py", "30", "20", "0"])

        self.assertEqual(module.AUTOSTART, 0)

    def test_autostart_is_read_from_the_optional_fifth_argument(self):
        module, _, _ = self.load_monitor(["monitor.py", "30", "20", "0", "15"])

        self.assertEqual(module.AUTOSTART, 15)

    def test_non_integer_interval_is_rejected(self):
        with self.assertRaises(ValueError):
            self.load_monitor(["monitor.py", "not-a-number", "20", "0"])

    def test_non_integer_autostart_is_rejected(self):
        with self.assertRaises(ValueError):
            self.load_monitor(["monitor.py", "30", "20", "0", "soon"])


class MainDispatchTest(MonitorLoadMixin, unittest.TestCase):
    """The module body dispatches to either the static frame or the interactive TUI."""

    def test_non_once_mode_starts_the_interactive_tui(self):
        _, wrapper, printer = self.load_monitor(["monitor.py", "30", "20", "0"])

        wrapper.assert_called_once()
        self.assertTrue(callable(wrapper.call_args.args[0]))
        self.assertEqual(wrapper.call_args.args[0].__name__, "tui")
        printer.assert_not_called()

    def test_once_mode_prints_a_static_frame_and_exits(self):
        _, wrapper, printer = self.load_monitor(["monitor.py", "30", "20", "1"])

        wrapper.assert_not_called()
        printed = "".join(str(call.args[0]) for call in printer.call_args_list if call.args)
        self.assertIn("swarm monitor", printed)
        self.assertIn("Recent Runs", printed)

    def test_once_frame_uses_blank_values_for_failed_sections(self):
        module, _, _ = self.load_monitor(["monitor.py", "30", "20", "1"])
        partial = {key: module.blank(key) for key in [*module.CALLS, "jobs", "churn", "commits"]}
        partial["runs"] = None

        with mock.patch.object(module, "fetch", return_value=partial), \
                mock.patch("builtins.print") as printer:
            module.once()

        printed = "".join(str(call.args[0]) for call in printer.call_args_list if call.args)
        self.assertIn("swarm monitor", printed)
        self.assertEqual(partial["runs"], [])  # a failed section is replaced with its blank value


class AutoStartLaunchTest(MonitorLoadMixin, unittest.TestCase):
    """start_swarm() dispatches one Agent swarm wave and reports failures."""

    def setUp(self):
        self.module, _, _ = self.load_monitor(["monitor.py", "30", "20", "0"])

    def run_swarm(self, returncode, stderr):
        completed = subprocess.CompletedProcess(args=[], returncode=returncode, stdout="", stderr=stderr)
        with mock.patch.object(subprocess, "run", return_value=completed) as run:
            result = self.module.start_swarm()
        return result, run

    def test_success_returns_empty_string(self):
        result, run = self.run_swarm(0, "ignored on success")

        self.assertEqual(result, "")
        run.assert_called_once_with(["gh", "workflow", "run", "swarm.yml", "-f", "now=true"],
                                    capture_output=True, text=True, env=mock.ANY)
        self.assertEqual(run.call_args.kwargs["env"]["GIT_CONFIG_COUNT"], "3")

    def test_failure_returns_last_stderr_line(self):
        result, _ = self.run_swarm(1, "first line\nsecond line\nthird line\n")

        self.assertEqual(result, "third line")

    def test_failure_without_stderr_returns_generic_message(self):
        result, _ = self.run_swarm(1, "  \n\t\n")

        self.assertEqual(result, "gh failed")


if __name__ == "__main__":
    unittest.main()
