"""Coverage for the live TUI half of ``scripts/monitor.py``.

This file drives the curses loop through a stub window and a fake curses module:
no terminal, no network and no real ``gh`` calls.  Only the curses part of the
module (palette setup, the refresh loop, loading screen, keys, alarm, autostart
and the beep) is in scope; the data and layout halves have their own test files.
"""
import importlib.util
import io
import sys
import threading
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]


class StopTui(Exception):
    """The stub window raises this when its scripted keys are exhausted."""


class PauseRefresh(Exception):
    """The stub fetch raises this to leave a refresh visibly half-finished."""


class FakeCurses(types.ModuleType):
    """A curses stand-in that records the calls the TUI makes."""

    error = type("error", (Exception,), {})
    KEY_RESIZE = 410
    A_BOLD = 1
    A_REVERSE = 2
    COLOR_BLACK, COLOR_RED, COLOR_GREEN, COLOR_YELLOW = 0, 1, 2, 3
    COLOR_BLUE, COLOR_MAGENTA, COLOR_CYAN, COLOR_WHITE = 4, 5, 6, 7
    COLORS = 256

    def __init__(self):
        super().__init__("curses")
        self.wrapper_fn = None
        self.beep_count = 0
        self.init_pairs = []
        self.cursors = []

    def wrapper(self, fn):
        self.wrapper_fn = fn

    def curs_set(self, value):
        self.cursors.append(value)

    def use_default_colors(self):
        pass

    def init_pair(self, number, fg, bg):
        self.init_pairs.append((number, fg, bg))

    def color_pair(self, number):
        return number << 8

    def beep(self):
        self.beep_count += 1


_MISSING = object()


def load_monitor():
    """Import monitor.py with a fake curses and harmless argv (ONCE=0)."""
    fake = FakeCurses()
    saved_curses = sys.modules.get("curses", _MISSING)
    saved_argv = sys.argv
    sys.modules["curses"] = fake
    sys.argv = ["monitor.py", "30", "50", "0", "0"]
    try:
        spec = importlib.util.spec_from_file_location(
            "monitor_tui_subject", ROOT / "scripts" / "monitor.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    finally:
        sys.argv = saved_argv
        if saved_curses is _MISSING:
            sys.modules.pop("curses", None)
        else:
            sys.modules["curses"] = saved_curses
    return module, fake


class FakeThread:
    """A thread that is created but never runs by itself, so data arrival is scriptable."""

    created = []

    def __init__(self, target=None, args=(), daemon=None, **kwargs):
        self.target, self.args, self.daemon = target, args, daemon
        FakeThread.created.append(self)

    def start(self):
        pass

    def join(self):
        pass


class Screen:
    """A stub curses window: scripted keys, a fixed size and a log of addstr calls."""

    def __init__(self, steps, size, error_type, error_on=()):
        self.steps = list(steps)
        self.size = size
        self.error_type = error_type
        self.error_on = set(error_on)
        self.addstr_calls = []
        self.refreshes = 0
        self.erases = 0
        self.timeouts = []

    def timeout(self, milliseconds):
        self.timeouts.append(milliseconds)

    def getch(self):
        if not self.steps:
            raise StopTui()
        hook, key = self.steps.pop(0)
        if hook:
            hook()
        return key

    def getmaxyx(self):
        return self.size

    def erase(self):
        self.erases += 1

    def addstr(self, y, x, text, attr=0):
        self.addstr_calls.append((y, x, text, attr))
        if (y, x) in self.error_on:
            raise self.error_type()

    def refresh(self):
        self.refreshes += 1

    def text(self):
        return "\n".join(call[2] for call in self.addstr_calls)


class TuiCase(unittest.TestCase):
    """Shared fixtures: patched clock, fetch, compose, threads and beep."""

    @classmethod
    def setUpClass(cls):
        cls.mod, cls.curses = load_monitor()

    def setUp(self):
        self.m = self.mod
        self.real_beep = self.m.beep
        FakeThread.created.clear()
        self.curses.init_pairs.clear()
        self.curses.cursors.clear()
        self.curses.beep_count = 0
        self.curses.COLORS = 256
        self.m.INTERVAL = 30
        self.m.RECENT = 5
        self.m.AUTOSTART = 0
        self.now = 1000.0
        self.payloads = []
        self.pause = False
        self.fetch_calls = []
        self.compose_calls = []
        self.compose_result = ([[("title", "bar")]], [], [], 30)
        self.beep_mock = mock.MagicMock()
        shim = types.SimpleNamespace(Lock=threading.Lock, Thread=FakeThread)
        self.patches = [
            mock.patch.object(self.m, "time", types.SimpleNamespace(time=self.clock)),
            mock.patch.object(self.m, "threading", shim),
            mock.patch.object(self.m, "fetch", self.fetch_stub),
            mock.patch.object(self.m, "compose", self.compose_stub),
            mock.patch.object(self.m, "beep", self.beep_mock),
        ]
        for patch in self.patches:
            patch.start()
        self.addCleanup(self.stop_patches)
        self.screen = None

    def stop_patches(self):
        for patch in reversed(self.patches):
            patch.stop()

    def clock(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds

    def fetch_stub(self, emit=None, full=True):
        self.fetch_calls.append(emit)
        payload = self.payloads.pop(0) if self.payloads else {}
        if emit:
            emit(payload)
        if self.pause:
            raise PauseRefresh()
        return payload

    def compose_stub(self, data, width, height, ids=frozenset(), busy=False, loading=frozenset()):
        self.compose_calls.append((data, width, height, ids, busy, loading))
        return self.compose_result

    def payload(self, **overrides):
        base = {"repo": "o/r", "issues": [], "prs": [], "merged": [], "closed": [],
                "total": "123", "archive": "", "todo": "", "commits": [],
                "jobs": {}, "churn": {}, "runs": [], "runs_live": [], "runs_more": []}
        base.update(overrides)
        return base

    def failed(self, number):
        return {"databaseId": number, "status": "completed", "conclusion": "failure",
                "workflowName": "Delivery", "headBranch": "main",
                "createdAt": "2026-11-21T11:00:00Z", "startedAt": "2026-11-21T11:00:00Z",
                "updatedAt": "2026-11-21T12:00:00Z"}

    def arrive(self, payload, complete=True):
        """Run the newest kick's refresh: emit `payload`, optionally leave it unfinished."""
        self.payloads.append(payload)
        self.pause = not complete
        thread = FakeThread.created[-1]
        try:
            thread.target(*thread.args)
        except PauseRefresh:
            pass
        finally:
            self.pause = False

    def run_tui(self, steps, size=(24, 100), error_on=()):
        self.screen = Screen(steps, size, self.curses.error, error_on)
        try:
            self.m.tui(self.screen)
        except StopTui:
            pass
        return self.screen


class PaletteAndOnceTests(TuiCase):
    def test_ansi_codes_cover_named_bold_unknown_and_special_styles(self):
        m = self.m
        self.assertIn("38;5;252", m.ansi_code(""))
        self.assertIn("38;5;245", m.ansi_code("dim"))
        self.assertIn("48;5;66", m.ansi_code("bar"))
        self.assertTrue(m.ansi_code("fail").startswith("1;38;5;174"))
        self.assertTrue(m.ansi_code("green").startswith("38;5;108"))
        self.assertTrue(m.ansi_code("b_green").startswith("1;38;5;108"))
        self.assertIn("38;5;252", m.ansi_code("bogus"))
        self.assertIn("38;5;252", m.ansi_code("b_bogus"))
        self.assertEqual(m.ANSI.get("bogus"), m.ansi_code("bogus"))

    def test_once_replaces_missing_sections_and_pads_both_columns(self):
        m = self.m
        frames = [
            ([[("A", "bar")]], [[("left", "")]], [], 8),
            ([], [], [[("right", "")]], 8),
            ([], [], [], 8),
        ]
        for top, left, right, cw in frames:
            self.payloads.append({"repo": "o/r", "issues": None, "runs": []})
            self.compose_result = (top, left, right, cw)
            with mock.patch.object(m.shutil, "get_terminal_size", lambda: types.SimpleNamespace(columns=140)), \
                    redirect_stdout(io.StringIO()) as out:
                m.once()
            text = out.getvalue()
            for line in top + left + right:
                for segment, _style in line:
                    self.assertIn(segment, text)
                    self.assertIn("\033[", text)
        self.assertEqual(self.compose_calls[-1][1], 140)
        self.assertIsNone(self.compose_calls[-1][2])

    def test_once_clamps_terminal_width_to_the_supported_range(self):
        m = self.m
        for columns, expected in ((40, 100), (120, 120), (240, 160)):
            self.payloads.append(self.payload())
            self.compose_result = ([], [], [], 8)
            with mock.patch.object(m.shutil, "get_terminal_size", lambda: types.SimpleNamespace(columns=columns)), \
                    redirect_stdout(io.StringIO()):
                m.once()
            self.assertEqual(self.compose_calls[-1][1], expected)


class CommandTests(TuiCase):
    def test_swarm_idle_distinguishes_live_waves_and_open_tasks(self):
        m = self.m
        self.assertIsNone(m.swarm_idle({"runs": None, "todo": ""}))
        self.assertIsNone(m.swarm_idle({"runs": [], "todo": None}))
        live = [{"workflowName": "Agent swarm", "status": "in_progress"}]
        self.assertFalse(m.swarm_idle({"runs": live, "todo": "- [ ] X-1 · work"}))
        other = [{"workflowName": "Delivery", "status": "completed"}]
        self.assertTrue(m.swarm_idle({"runs": other, "todo": "- [ ] X-1 · work"}))
        self.assertFalse(m.swarm_idle({"runs": other, "todo": "- [x] X-1 · work"}))
        idle_wave = [{"workflowName": "Agent swarm", "status": "completed"}]
        self.assertTrue(m.swarm_idle({"runs": idle_wave, "todo": "- [ ] X-1 · work"}))

    def test_start_swarm_returns_the_last_error_line_or_a_fallback(self):
        m = self.m
        with mock.patch.object(m.subprocess, "run", return_value=mock.Mock(returncode=0, stderr="")):
            self.assertEqual(m.start_swarm(), "")
        with mock.patch.object(m.subprocess, "run",
                               return_value=mock.Mock(returncode=1, stderr="first\nlast\n")):
            self.assertEqual(m.start_swarm(), "last")
        with mock.patch.object(m.subprocess, "run",
                               return_value=mock.Mock(returncode=1, stderr="  \n")):
            self.assertEqual(m.start_swarm(), "gh failed")

    def test_beep_uses_a_local_player_when_present_and_falls_back_to_curses(self):
        popen = mock.MagicMock()
        with mock.patch.object(self.m.shutil, "which", side_effect=["/usr/bin/afplay"]), \
                mock.patch.object(self.m.subprocess, "Popen", popen):
            self.real_beep()
        self.assertEqual(popen.call_count, 1)
        self.assertEqual(self.curses.beep_count, 0)
        popen.reset_mock()
        with mock.patch.object(self.m.shutil, "which", side_effect=[None, "/usr/bin/paplay"]), \
                mock.patch.object(self.m.subprocess, "Popen", popen):
            self.real_beep()
        self.assertEqual(popen.call_count, 1)
        with mock.patch.object(self.m.shutil, "which", side_effect=[None, None]), \
                mock.patch.object(self.m.subprocess, "Popen", popen):
            self.real_beep()
        self.assertEqual(self.curses.beep_count, 1)


class InitTests(TuiCase):
    def test_palette_initialisation_handles_rich_and_plain_terminals(self):
        for colors in (256, 16, 8):
            with self.subTest(colors=colors):
                self.curses.COLORS = colors
                self.curses.init_pairs.clear()
                screen = self.run_tui([], size=(24, 100))
                self.assertEqual(self.curses.cursors[-1], 0)
                self.assertEqual(screen.timeouts, [200])
                numbers = [entry[0] for entry in self.curses.init_pairs]
                self.assertEqual(len(numbers), 10)
                self.assertIn(40, numbers)

    def test_module_entry_wrapped_tui_without_running_it(self):
        self.assertIs(self.curses.wrapper_fn, self.m.tui)




class LoadingAndKeysTests(TuiCase):
    def test_loading_screen_waits_for_three_quarters_of_the_sections(self):
        steps = [
            (lambda: self.arrive({"repo": "o/r"}, complete=False), -1),
            (None, -1),  # identical signature: redraw is skipped
            (lambda: self.arrive(self.payload()), ord("q")),
        ]
        screen = self.run_tui(steps)
        self.assertEqual([c[2] for c in screen.addstr_calls].count("Loading..."), 1)
        self.assertEqual(screen.erases, 1)
        self.assertEqual(self.compose_calls, [])

    def test_quit_keys_return_without_consuming_later_steps(self):
        for key in (ord("q"), ord("Q"), 27):
            with self.subTest(key=key):
                screen = self.run_tui([(None, key), (None, ord("q"))])
                self.assertEqual(len(screen.steps), 1)

    def test_refresh_keys_kick_only_when_idle(self):
        self.run_tui([(None, ord("r")), (None, ord("R"))])
        self.assertEqual(len(FakeThread.created), 1)  # busy from the initial kick
        steps = [(lambda: self.arrive(self.payload()), ord("r")), (None, ord("q"))]
        self.run_tui(steps)
        self.assertEqual(len(FakeThread.created), 3)  # one initial kick per call, plus the refresh key

    def test_resize_no_key_and_unrelated_keys_do_not_quit(self):
        screen = self.run_tui([(None, -1), (None, self.curses.KEY_RESIZE),
                               (None, ord("x")), (None, ord("q"))])
        self.assertEqual(len(screen.steps), 0)

    def test_the_timer_kicks_only_after_the_configured_interval(self):
        self.run_tui([(None, -1)])
        self.assertEqual(len(FakeThread.created), 1)  # at is still zero
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (lambda: self.advance(31), -1),
            (None, ord("q")),
        ]
        self.run_tui(steps)
        self.assertEqual(len(FakeThread.created), 3)  # initial kick plus the interval kick


class AlarmTests(TuiCase):
    def test_a_scan_with_no_fresh_failures_keeps_the_screen_quiet(self):
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (lambda: (self.advance(10), self.arrive(self.payload())), -1),
            (None, ord("q")),
        ]
        screen = self.run_tui(steps)
        self.assertNotIn("NEW FAILED RUN", screen.text())
        self.assertEqual(self.beep_mock.call_count, 0)

    def test_a_new_failure_raises_an_alarm_and_any_key_silences_it(self):
        steps = [
            (lambda: self.arrive(self.payload()), -1),  # first look: seen is None
            (lambda: (self.advance(10), self.arrive(self.payload(runs=[self.failed(1)]))), -1),
            (None, -1),  # any key silences the alarm
            (None, ord("q")),
        ]
        screen = self.run_tui(steps)
        self.assertEqual(screen.text().count("NEW FAILED RUN"), 1)
        self.assertEqual(self.beep_mock.call_count, 1)

    def test_m_mutes_the_beep_but_not_the_alarm_and_unmutes_again(self):
        fail = lambda n: (lambda: (self.advance(10), self.arrive(self.payload(runs=[self.failed(n)]))), -1)
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (None, ord("m")),  # muted before any failure
            fail(1),
            (None, ord("x")),  # any key silences the alarm
            (None, ord("M")),  # unmuted again
            fail(2),
            (None, ord("q")),
        ]
        screen = self.run_tui(steps)
        self.assertIn("NEW FAILED RUN", screen.text())
        self.assertIn("m mute", screen.text())
        self.assertEqual(self.beep_mock.call_count, 1)  # only the second failure beeped

    def test_the_footer_and_alarm_say_when_muted(self):
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (None, ord("m")),
            (None, -1),
            (None, ord("q")),
        ]
        self.run_tui(steps)
        self.assertIn("m unmute (MUTED)", self.screen.text())
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (None, ord("m")),
            (lambda: (self.advance(10), self.arrive(self.payload(runs=[self.failed(1)]))), -1),
            (None, ord("q")),
        ]
        self.run_tui(steps)
        self.assertIn("(muted)", self.screen.text())
        self.assertEqual(self.beep_mock.call_count, 0)

    def test_multiple_fresh_failures_are_grouped_and_beeped_every_five_seconds(self):
        runs = [self.failed(1), self.failed(2)]
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (lambda: (self.advance(10), self.arrive(self.payload(runs=runs))), -1),
            (lambda: self.advance(2), -1),  # before the next beep
            (lambda: self.advance(4), -1),  # at the next beep
            (None, ord("q")),
        ]
        self.run_tui(steps)
        self.assertIn("(+1 more)", self.screen.text())
        self.assertEqual(self.beep_mock.call_count, 2)

    def test_a_later_failure_joins_the_already_active_alarm(self):
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (lambda: (self.advance(10), self.arrive(self.payload(runs=[self.failed(1)]))), -1),
            (lambda: (self.advance(10),
                      self.arrive(self.payload(runs=[self.failed(1), self.failed(2)]))), -1),
            (None, ord("q")),
        ]
        self.run_tui(steps)
        self.assertTrue(any(call[3] >= {1, 2} for call in self.compose_calls))

    def test_fail_rows_flicker_only_on_the_alternate_phase(self):
        self.compose_result = ([[("boom", "fail"), ("plain", "unknown")]], [], [], 30)
        steps = [
            (lambda: self.arrive(self.payload()), -1),
            (lambda: (self.advance(1), self.arrive(self.payload(runs=[self.failed(1)]))), -1),
            (lambda: self.advance(1), -1),  # phase back to zero while the alarm is up
            (None, ord("q")),
        ]
        self.run_tui(steps)
        self.assertIn("boom", self.screen.text())
        reversed_attrs = [call[3] for call in self.screen.addstr_calls if call[2] == "boom"]
        self.assertTrue(any(attr & self.curses.A_REVERSE for attr in reversed_attrs))
        self.assertTrue(any(not attr & self.curses.A_REVERSE for attr in reversed_attrs))


class AutostartTests(TuiCase):
    def idle_payload(self):
        return self.payload(runs=[{"workflowName": "Delivery", "status": "completed",
                                   "conclusion": "success"}],
                            todo="- [ ] X-1 · work")

    def test_s_disables_autostart_before_the_first_refresh(self):
        self.m.AUTOSTART = 7
        with mock.patch.object(self.m, "start_swarm") as start:
            self.run_tui([(None, ord("s")), (lambda: self.arrive(self.idle_payload()), -1),
                          (None, ord("q"))])
        start.assert_not_called()
        self.assertIn("s auto-start off", self.screen.text())

    def test_s_enables_autostart_with_the_default_wait_when_started_off(self):
        with mock.patch.object(self.m, "start_swarm", return_value="") as start:
            self.run_tui([(None, ord("S")), (lambda: self.arrive(self.idle_payload()), -1),
                          (None, ord("q"))], size=(24, 150))
        start.assert_called_once_with()
        self.assertIn("s auto-start on (10m)", self.screen.text())

    def test_toggling_preserves_the_wait_and_last_launch_time(self):
        self.m.AUTOSTART = 7
        with mock.patch.object(self.m, "start_swarm", return_value="") as start:
            self.run_tui([(lambda: self.arrive(self.idle_payload()), -1), (None, ord("s")),
                          (None, ord("s")), (lambda: self.advance(30), -1),
                          (lambda: self.arrive(self.idle_payload()), -1), (None, ord("q"))],
                         size=(24, 150))
        start.assert_called_once_with()
        self.assertIn("s auto-start on (7m)", self.screen.text())

    def test_a_wave_starts_when_swarm_is_idle_and_the_wait_has_elapsed(self):
        self.m.AUTOSTART = 10
        with mock.patch.object(self.m, "start_swarm", return_value="") as start:
            self.run_tui([(lambda: self.arrive(self.idle_payload()), -1), (None, ord("q"))])
        self.assertEqual(start.call_count, 1)
        self.assertIn("swarm was idle", self.screen.text())

    def test_a_failed_launch_is_reported_in_the_footer(self):
        self.m.AUTOSTART = 10
        with mock.patch.object(self.m, "start_swarm", return_value="boom"):
            self.run_tui([(lambda: self.arrive(self.idle_payload()), -1), (None, ord("q"))])
        self.assertIn("swarm start failed: boom", self.screen.text())

    def test_a_live_wave_or_a_short_wait_prevents_autostart(self):
        self.m.AUTOSTART = 10
        live = self.payload(runs=[{"workflowName": "Agent swarm", "status": "in_progress"}],
                            todo="- [ ] X-1 · work")
        with mock.patch.object(self.m, "start_swarm") as start:
            self.run_tui([(lambda: self.arrive(live), -1), (None, ord("q"))])
        self.assertEqual(start.call_count, 0)
        self.assertIn("auto-start on (10m)", self.screen.text())
        self.now = 1.0  # less than the ten-minute wait since started_at = 0
        with mock.patch.object(self.m, "start_swarm") as start:
            self.run_tui([(lambda: self.arrive(self.idle_payload()), -1), (None, ord("q"))])
        self.assertEqual(start.call_count, 0)


class DrawTests(TuiCase):
    def test_footer_reports_loading_refreshing_and_updated(self):
        # The new runs_live section makes 14 dashboard sections; 11 of 14 loaded
        # still clears the three-quarters "ready" threshold for the loading footer.
        partial = {k: v for k, v in self.payload().items()
                   if k not in ("issues", "prs", "merged")}
        self.run_tui([(lambda: self.arrive(partial, complete=False), -1), (None, ord("q"))])
        self.assertIn("loading 11/14...", self.screen.text())

        self.run_tui([(lambda: self.arrive(self.payload(), complete=False), -1), (None, ord("q"))])
        self.assertIn("refreshing...", self.screen.text())

        self.run_tui([(lambda: self.arrive(self.payload()), -1), (None, ord("q"))])
        self.assertIn("updated 0m00s ago", self.screen.text())

    def test_part_ignores_missing_sections_and_empty_payloads(self):
        self.run_tui([(lambda: self.arrive({"repo": None, "issues": []}, complete=False), -1),
                      (lambda: self.arrive({}, complete=False), -1),
                      (None, ord("q"))])
        self.assertIn("Loading...", self.screen.text())

    def test_draw_stops_at_the_last_row_and_handles_short_columns(self):
        self.compose_result = ([[("top", "")]], [[("L", "")]] * 10,
                               [[("R", "")]], 10)
        screen = self.run_tui([(lambda: self.arrive(self.payload()), -1), (None, ord("q"))],
                              size=(6, 40))
        self.assertEqual([c[2] for c in screen.addstr_calls].count("L"), 4)
        self.assertEqual([c[2] for c in screen.addstr_calls].count("R"), 1)

        self.compose_result = ([[("top", "")]], [], [[("R", "")]] * 4, 10)
        self.run_tui([(lambda: self.arrive(self.payload()), -1), (None, ord("q"))],
                     size=(10, 40))
        self.assertGreaterEqual([c[2] for c in self.screen.addstr_calls].count("R"), 1)

    def test_addstr_errors_from_the_terminal_are_ignored(self):
        self.compose_result = ([[("fragile", "")]], [], [], 30)
        screen = self.run_tui([(lambda: self.arrive(self.payload()), -1), (None, ord("q"))],
                              error_on={(0, 0)})
        self.assertIn("fragile", screen.text())
        self.assertGreater(screen.refreshes, 0)


if __name__ == "__main__":
    unittest.main()
