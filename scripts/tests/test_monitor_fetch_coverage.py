"""Coverage for the data layer of scripts/monitor.py (the fetch part).

This file owns the fourth of the monitor script: the ``gh`` call table, ``spawn``,
``collect``, ``log_timing``, ``merge_runs``, ``fetch`` (including the nested
``put``/``timed``/``load``/``add_jobs`` helpers), the churn/compare step, caching
and error handling, plus the small pure helpers that live beside them (lines
1-316 of monitor.py). The display/TUI/CLI parts belong to the other three test
files. Everything here is synthetic: ``gh`` and subprocess calls are faked and
no network or real terminal is ever used.
"""
import datetime
import json
import os
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock

import curses

from helpers import load


# The module reads sys.argv and starts either once() or the curses TUI at import
# time. Load it with a controlled argv and a stubbed curses.wrapper so importing
# never touches a real terminal. Two argv shapes exercise both sides of the
# AUTOSTART default ternary on line 5.
def _load(argv):
    original = sys.argv
    sys.argv = argv
    try:
        with mock.patch.object(curses, "wrapper", lambda fn: None):
            return load("monitor")
    finally:
        sys.argv = original


monitor = _load(["monitor.py", "30", "50", "0", "0"])
_monitor_defaults = _load(["monitor.py", "30", "50", "0"])  # no 5th arg -> AUTOSTART = 0
_monitor_autostart = _load(["monitor.py", "30", "50", "0", "7"])  # 5th arg -> AUTOSTART = 7

UTC = datetime.timezone.utc


def stamp(minutes_ago):
    """Synthetic ISO timestamp, ``minutes_ago`` before real 'now'."""
    return (datetime.datetime.now(UTC) - datetime.timedelta(minutes=minutes_ago)).strftime(
        "%Y-%m-%dT%H:%M:%SZ")


class FakeProc:
    """Minimal subprocess.Popen stand-in with deterministic output and kill tracking."""

    def __init__(self, out="", error=None):
        self.out = out
        self.error = error
        self.killed = []

    def communicate(self, timeout=None):
        if self.error:
            raise self.error
        return self.out, ""

    def kill(self):
        self.killed.append(True)


class SyncThread:
    """Run a thread target synchronously so parallel fetch() loads are deterministic."""

    def __init__(self, target=None, args=(), daemon=None):
        self.target, self.args = target, args

    def start(self):
        self.target(*self.args)

    def join(self):
        pass


def classify(args):
    """Map a faked gh argv back to the CALLS key it came from."""
    text = " ".join(args)
    if args[:2] == ["repo", "view"]:
        return "repo"
    if args[:2] == ["issue", "list"]:
        return "issues"
    if args[:2] == ["pr", "list"]:
        return args[args.index("--state") + 1]  # open / merged / closed
    if args[:2] == ["run", "list"]:
        return "runs" if "100" in args else "runs_more"
    if args[:2] == ["run", "view"]:
        return "jobs"
    if args[:1] == ["api"]:
        if "graphql" in text:
            if "TODO-DONE.md" in text:
                return "archive"
            if 'object(oid:' in text:
                return "blobs"
            if 'HEAD:tasks"' in text:
                return "todo"
            return "total"
        if "/compare/" in text:
            return "compare"
        if "/commits?" in text:
            return "commits"
    return "unknown"


TEXT_KEYS = {"repo", "todo", "archive", "total", "commits"}


class FakeGh:
    """Canned gh responses: ``payload`` for normal output, ``raw`` for exact bytes,
    ``fail`` to raise (and exercise collect's except branch)."""

    def __init__(self, payload, raw=None, fail=()):
        self.payload = payload
        self.raw = raw or {}
        self.fail = set(fail)
        self.seen = []
        self.procs = []

    def spawn(self, args):
        self.seen.append(list(args))
        key = classify(args)
        ident = args[2] if key == "jobs" else key
        if ident in self.fail or key in self.fail:
            proc = FakeProc(error=RuntimeError("synthetic gh failure"))
        elif ident in self.raw:
            proc = FakeProc(out=self.raw[ident])
        else:
            value = self.payload.get(ident, [] if key != "jobs" else {})
            if key in TEXT_KEYS:
                proc = FakeProc(out=value if isinstance(value, str) else "")
            else:
                proc = FakeProc(out=json.dumps(value))
        self.procs.append(proc)
        return proc


def run_fetch(gh, emit=None, timing_path=None, full=True, keep_cache=False, task_cache=None):
    """Call monitor.fetch with synchronous threads, a faked gh and a scratch timing log."""
    if not keep_cache:
        monitor.CACHE["jobs"].clear()
        monitor.CACHE["slow_at"] = 0.0
        monitor.CACHE.pop("repo", None)
    tmp = tempfile.mkdtemp()
    log_path = timing_path or os.path.join(tmp, "timing.log")
    fake_threading = types.SimpleNamespace(Thread=SyncThread, Lock=threading.Lock)
    with mock.patch.object(monitor, "threading", fake_threading), \
            mock.patch.object(monitor, "spawn", gh.spawn), \
            mock.patch.object(monitor, "TASK_CACHE", task_cache or os.path.join(tmp, "tasks.json")), \
            mock.patch.object(monitor, "TIMING_LOG", log_path):
        return monitor.fetch(emit, full=full), log_path


class TaskLoadingTests(unittest.TestCase):
    """The task files are listed by oid; only files not in the local cache are read."""

    def setUp(self):
        monitor.CACHE.pop("repo", None)
        self.cache = os.path.join(tempfile.mkdtemp(), "tasks.json")

    def run_todo(self, gh, listing):
        fake_threading = types.SimpleNamespace(Thread=SyncThread, Lock=threading.Lock)
        with mock.patch.object(monitor, "threading", fake_threading), mock.patch.object(monitor, "spawn", gh.spawn), \
                mock.patch.object(monitor, "TASK_CACHE", self.cache):
            return monitor.todo_text(listing)

    def blobs(self, **texts):
        return {"data": {"repository": {k: {"text": v} for k, v in texts.items()}}}

    def test_reads_new_files_once_and_joins_them_in_name_order(self):
        gh = FakeGh({"repo": "acme/clinic", "blobs": self.blobs(a0="- [ ] B-1 · b\n", a1="- [x] A-1 · a\n")})
        text = self.run_todo(gh, "00000200-B-1.md o2\n00000100-A-1.md o1\n")
        self.assertEqual(text, "- [x] A-1 · a\n- [ ] B-1 · b\n")
        gh2 = FakeGh({"repo": "acme/clinic"})
        again = self.run_todo(gh2, "00000200-B-1.md o2\n00000100-A-1.md o1\n")
        self.assertEqual(again, text)
        self.assertFalse(any(classify(a) == "blobs" for a in gh2.seen))  # all from the cache

    def test_a_failed_batch_gives_none_and_a_bad_listing_line_is_skipped(self):
        gh = FakeGh({"repo": "acme/clinic"}, fail={"blobs"})
        self.assertIsNone(self.run_todo(gh, "00000100-A-1.md o1\nnot a listing line here\n"))

    def test_an_unknown_repo_name_gives_none(self):
        gh = FakeGh({"repo": ""})
        self.assertIsNone(self.run_todo(gh, "00000100-A-1.md o1\n"))
        self.assertNotIn("repo", monitor.CACHE)

    def test_an_unwritable_cache_still_gives_the_tasks(self):
        self.cache = os.path.join(tempfile.mkdtemp(), "missing-folder", "tasks.json")
        gh = FakeGh({"repo": "acme/clinic", "blobs": self.blobs(a0="- [ ] A-1 · a\n")})
        self.assertEqual(self.run_todo(gh, "00000100-A-1.md o1\n"), "- [ ] A-1 · a\n")

    def test_an_unreadable_cache_file_is_ignored_and_rewritten(self):
        with open(self.cache, "w") as f:
            f.write("{not json")
        gh = FakeGh({"repo": "acme/clinic", "blobs": self.blobs(a0="- [ ] A-1 · a\n")})
        self.assertEqual(self.run_todo(gh, "00000100-A-1.md o1\n"), "- [ ] A-1 · a\n")
        with open(self.cache) as f:
            self.assertEqual(json.load(f), {"o1": "- [ ] A-1 · a\n"})


class CommandTableAndSpawnTests(unittest.TestCase):
    def test_call_table_has_every_section_and_both_run_pages(self):
        self.assertEqual(
            set(monitor.CALLS),
            {"repo", "issues", "prs", "merged", "closed", "runs", "runs_live", "runs_more",
             "total", "todo", "archive"})
        self.assertEqual(monitor.CALLS["repo"][:2], ["repo", "view"])
        self.assertEqual(monitor.CALLS["runs"][3], "100")
        self.assertEqual(monitor.CALLS["runs_more"][3], "300")
        self.assertIn("{since}", " ".join(monitor.CALLS["merged"]))
        self.assertEqual(monitor.ONCE, 0)
        self.assertEqual(monitor.AUTOSTART, 0)
        self.assertEqual(_monitor_defaults.AUTOSTART, 0)
        self.assertEqual(_monitor_autostart.AUTOSTART, 7)

    def test_spawn_prepends_gh_and_forwards_output_settings(self):
        with mock.patch.object(monitor.subprocess, "Popen", return_value="proc") as popen:
            self.assertEqual(monitor.spawn(["repo", "view"]), "proc")
        popen.assert_called_once_with(
            ["gh", "repo", "view"], stdout=monitor.subprocess.PIPE,
            stderr=monitor.subprocess.DEVNULL, text=True)


class CollectAndTimingTests(unittest.TestCase):
    def test_collect_parses_json_and_keeps_text_keys_verbatim(self):
        procs = {
            "issues": FakeProc(json.dumps([{"number": 1}])),
            "repo": FakeProc("  acme/clinic  "),
            "empty": FakeProc(""),
        }
        data = monitor.collect(procs, ("repo", "empty"))
        self.assertEqual(data, {"issues": [{"number": 1}], "repo": "  acme/clinic  ", "empty": ""})
        self.assertEqual(monitor.collect({"blank": FakeProc("")}), {"blank": []})

    def test_collect_kills_a_timed_out_process_and_keeps_its_slot(self):
        proc = FakeProc(error=TimeoutError("synthetic timeout"))
        data = monitor.collect({"runs": proc})
        self.assertEqual(data, {"runs": None})
        self.assertEqual(proc.killed, [True])

    def test_log_timing_writes_slowest_first_and_handles_an_unwritable_log(self):
        path = os.path.join(tempfile.mkdtemp(), "timing.log")
        with mock.patch.object(monitor, "TIMING_LOG", path):
            monitor.log_timing([("issues", 1.0, "9 items"), ("runs", 2.5, "FAILED or timed out")], 3.5)
            monitor.log_timing([], 0.0)
        with open(path) as handle:
            text = handle.read()
        self.assertIn("refresh took 3.5s", text)
        self.assertLess(text.index("runs"), text.index("issues"))
        self.assertIn("9 items", text)
        bad = os.path.join(tempfile.mkdtemp(), "missing", "timing.log")
        with mock.patch.object(monitor, "TIMING_LOG", bad):
            self.assertIsNone(monitor.log_timing([("x", 1.0, "note")], 1.0))  # OSError swallowed


class MergeRunsTests(unittest.TestCase):
    def test_merge_runs_deduplicates_by_id_and_sorts_newest_first(self):
        a = [{"databaseId": 1, "createdAt": "2026-11-21T10:00:00Z"}, {"databaseId": 2}]
        b = [{"databaseId": 1, "createdAt": "2026-11-21T11:00:00Z"},
             {"databaseId": 3, "createdAt": "2026-11-20T09:00:00Z"}]
        merged = monitor.merge_runs(a, b)
        self.assertEqual([r["databaseId"] for r in merged], [1, 3, 2])
        self.assertEqual(merged[0]["createdAt"], "2026-11-21T10:00:00Z")  # new a wins over old b
        self.assertEqual(monitor.merge_runs([], []), [])


class FetchTests(unittest.TestCase):
    def setUp(self):
        self.runs = [
            {"databaseId": 1, "status": "in_progress", "workflowName": "Agent swarm",
             "createdAt": stamp(5), "updatedAt": stamp(1), "displayTitle": "wave 1"},
            {"databaseId": 2, "status": "completed", "conclusion": "failure",
             "workflowName": "Delivery", "createdAt": stamp(60), "updatedAt": stamp(10)},
            {"databaseId": 6, "status": "in_progress", "workflowName": "Delivery",
             "createdAt": stamp(4), "updatedAt": stamp(2)},
            {"databaseId": 3, "status": "completed", "conclusion": "success",
             "workflowName": "Delivery", "createdAt": stamp(90), "updatedAt": stamp(80)},
            {"databaseId": 4, "status": "completed", "conclusion": "timed_out",
             "workflowName": "Delivery", "createdAt": stamp(5000), "updatedAt": stamp(4000)},
            {"databaseId": 5, "status": "completed", "conclusion": "failure",
             "workflowName": "Delivery", "createdAt": stamp(30), "updatedAt": None},
        ]
        self.payload = {
            "repo": "  acme/clinic  \n",
            "issues": [{"number": 7, "title": "Open issue", "labels": [{"name": "needs-human"}]}],
            "open": [{"number": 8, "title": "Open PR", "isDraft": False, "labels": []}],
            "merged": [{"number": 9, "title": "X-1 · merged", "mergedAt": stamp(30)}],
            "closed": [{"number": 10, "title": "Closed PR", "closedAt": stamp(40), "mergedAt": None}],
            "total": "12345",
            "archive": "- [x] OLD-9 · done\n",
            "todo": "00000100-NEW-8.md abc123\n",
            "blobs": {"data": {"repository": {"a0": {"text": "- [ ] NEW-8 · Work\n"}}}},
            "commits": "aaa111 bbb000\nccc222 \n",  # last line has no parent -> no compare call
            "runs": self.runs[:4],
            "runs_more": self.runs[3:] + [{"databaseId": 99, "status": "completed",
                                           "conclusion": "success", "createdAt": stamp(100)}],
        }
        self.raw = {
            "1": json.dumps({"jobs": [{"status": "in_progress", "name": "Worker 1", "steps": []}]}),
            "2": "null",  # collect -> None, included as []
            "6": "[]",  # collect -> list, excluded by the isinstance filter
        }

    def test_fetch_loads_every_section_and_merges_runs(self):
        gh = FakeGh(self.payload, self.raw)
        out, path = run_fetch(gh)
        self.assertEqual(out["repo"], "acme/clinic")
        self.assertEqual(out["prs"], self.payload["open"])
        self.assertEqual(out["issues"], self.payload["issues"])
        self.assertEqual(out["merged"], self.payload["merged"])
        self.assertEqual(out["closed"], self.payload["closed"])
        self.assertEqual(out["total"], "12345")
        self.assertEqual(out["todo"], "- [ ] NEW-8 · Work\n")
        self.assertEqual(out["archive"], self.payload["archive"])
        self.assertEqual(out["churn"], {"commits": 2, "files": 0, "add": 0, "del": 0})
        self.assertIn("runs", out)
        self.assertEqual(set(out["jobs"]), {1, 2})
        self.assertEqual(out["jobs"][2], [])
        self.assertFalse(any("compare" in " ".join(call) for call in gh.seen))
        with open(path) as handle:
            self.assertIn("refresh took", handle.read())

    def test_fetch_emits_each_part_as_it_arrives(self):
        parts = []
        gh = FakeGh(self.payload, self.raw)
        out, _ = run_fetch(gh, emit=parts.append)
        merged = {}
        for part in parts:
            merged.update(part)
        self.assertTrue(parts)
        self.assertEqual(merged, out)

    def test_fetch_compares_the_day_when_the_oldest_commit_has_a_parent(self):
        payload = dict(self.payload, commits="aaa111 bbb000\n")
        payload["compare"] = {"files": 3, "add": 10, "del": 4}
        gh = FakeGh(payload, self.raw)
        out, _ = run_fetch(gh)
        self.assertEqual(out["churn"], {"files": 3, "add": 10, "del": 4, "commits": 1})
        self.assertTrue(any("/compare/" in " ".join(call) for call in gh.seen))

    def test_fetch_keeps_no_churn_when_compare_returns_a_non_object(self):
        payload = dict(self.payload, commits="aaa111 bbb000\n")
        gh = FakeGh(payload, dict(self.raw, compare="[]"))
        out, _ = run_fetch(gh)
        self.assertIsNone(out["churn"])

    def test_fetch_with_no_commit_lines_still_reports_zero_churn(self):
        gh = FakeGh(self.payload, dict(self.raw, commits=""))
        out, _ = run_fetch(gh)
        self.assertEqual(out["churn"], {"commits": 0, "files": 0, "add": 0, "del": 0})

    def test_fetch_with_only_finished_runs_publishes_an_empty_jobs_map(self):
        finished = [
            {"databaseId": i, "status": "completed", "conclusion": "success",
             "workflowName": "Delivery", "createdAt": stamp(90), "updatedAt": stamp(80)}
            for i in (11, 12)
        ]
        gh = FakeGh(dict(self.payload, runs=finished, runs_more=finished), self.raw)
        out, _ = run_fetch(gh)
        self.assertEqual(out["jobs"], {})
        self.assertFalse(any(c[:2] == ["run", "view"] for c in gh.seen))

    def test_a_quick_refresh_skips_the_slow_sections(self):
        run_fetch(FakeGh(self.payload, self.raw))  # a full load starts the slow clock
        gh = FakeGh(self.payload, self.raw)
        out, _ = run_fetch(gh, full=False, keep_cache=True)
        asked = {classify(c) for c in gh.seen}
        for key in ("total", "archive", "closed", "merged", "commits", "runs_more"):
            self.assertNotIn(key, asked)
            self.assertNotIn(key, out)
        for key in ("issues", "open", "todo", "runs"):
            self.assertIn(key, asked)

    def test_the_slow_sections_come_back_after_their_interval(self):
        run_fetch(FakeGh(self.payload, self.raw))
        with mock.patch.object(monitor, "SLOW_EVERY", 0):
            gh = FakeGh(self.payload, self.raw)
            run_fetch(gh, full=False, keep_cache=True)
        self.assertIn("merged", {classify(c) for c in gh.seen})

    def test_jobs_of_finished_runs_are_looked_up_once(self):
        runs = [{"databaseId": 21, "status": "completed", "conclusion": "failure", "workflowName": "Delivery",
                 "createdAt": stamp(30), "updatedAt": stamp(20)}]
        payload = dict(self.payload, runs=runs, runs_more=runs)
        raw = {"21": json.dumps({"jobs": [{"name": "Build", "conclusion": "failure", "steps": []}]})}
        first = FakeGh(payload, raw)
        out, _ = run_fetch(first)
        self.assertEqual(sum(c[:2] == ["run", "view"] for c in first.seen), 1)
        second = FakeGh(payload, raw)
        again, _ = run_fetch(second, keep_cache=True)
        self.assertFalse(any(c[:2] == ["run", "view"] for c in second.seen))
        self.assertEqual(again["jobs"], out["jobs"])

    def test_fetch_keeps_previous_values_and_drops_unavailable_sections(self):
        gh = FakeGh(self.payload, self.raw, fail={"repo", "runs", "commits", "runs_more"})
        out, _ = run_fetch(gh)
        self.assertIsNone(out["repo"])
        self.assertNotIn("runs", out)
        self.assertIsNone(out["runs_more"])
        self.assertNotIn("churn", out)
        self.assertTrue(any(proc.killed for proc in gh.procs))


class SmallHelperTests(unittest.TestCase):
    def test_blank_returns_the_neutral_value_for_each_section_kind(self):
        self.assertEqual(monitor.blank("repo"), "")
        self.assertEqual(monitor.blank("todo"), "")
        self.assertEqual(monitor.blank("jobs"), {})
        self.assertEqual(monitor.blank("churn"), {})
        self.assertEqual(monitor.blank("runs"), [])
        self.assertEqual(monitor.blank("issues"), [])

    def test_ts_normalises_zulu_and_accepts_missing_values(self):
        self.assertEqual(monitor.ts("2026-11-21T10:00:00Z").tzinfo, UTC)
        self.assertEqual(monitor.ts("2026-11-21T10:00:00+00:00").tzinfo, UTC)
        self.assertIsNone(monitor.ts(None))
        self.assertIsNone(monitor.ts(""))

    def test_span_formats_days_hours_and_minutes_and_clamps_negatives(self):
        self.assertEqual(monitor.span(-5), "0m00s")
        self.assertEqual(monitor.span(90), "1m30s")
        self.assertEqual(monitor.span(3661), "1h01m")
        self.assertEqual(monitor.span(90000), "1d01h")

    def test_clip_shortens_long_text_and_stringifies_values(self):
        self.assertEqual(monitor.clip("hello", 10), "hello")
        self.assertEqual(monitor.clip("hello world", 8), "hello...")
        self.assertEqual(monitor.clip(12345, 8), "12345")
        self.assertEqual(monitor.clip("abcdefgh", 2), "...")
        self.assertEqual(monitor.clip("x", 0), "...")

    def test_mid_keeps_both_ends_and_stringifies_values(self):
        self.assertEqual(monitor.mid("abcdef", 10), "abcdef")
        self.assertEqual(monitor.mid(12345, 10), "12345")
        self.assertEqual(monitor.mid("abcdefghij", 6), "abc…ij")
        self.assertEqual(monitor.mid("abcd", 2), "a…d")

    def test_failure_parts_reports_the_failing_job_and_first_failed_step(self):
        jobs = [
            {"conclusion": "success", "name": "ok"},
            {"conclusion": "failure", "name": "Build",
             "steps": [{"name": "Run python tests", "conclusion": "success"},
                       {"name": "Run lint", "conclusion": "failure"}]},
        ]
        self.assertEqual(monitor.failure_parts(jobs), ("Build", "lint"))
        self.assertEqual(monitor.failure_parts([{"conclusion": "timed_out", "name": "Slow",
                                                 "steps": [{"name": "deploy", "conclusion": "timed_out"}]}]),
                         ("Slow", "deploy"))
        self.assertEqual(monitor.failure_parts([{"conclusion": "failure", "name": "NoSteps"}]),
                         ("NoSteps", ""))
        self.assertEqual(monitor.failure_parts([{"conclusion": "success"}]), ("", ""))
        self.assertEqual(monitor.failure_parts([]), ("", ""))

    def test_state_prefers_conclusion_then_status_then_blank(self):
        self.assertEqual(monitor.state({"conclusion": "failure", "status": "completed"}), "failure")
        self.assertEqual(monitor.state({"status": "in_progress"}), "in_progress")
        self.assertEqual(monitor.state({}), "")


class TodoAndJobSummaryTests(unittest.TestCase):
    def test_priority_order_and_markers_follow_swarm_rules(self):
        tasks = monitor.parse_tasks("- [ ] A-1 · a\n- [ ] QAD-1 · daily\n- [ ] B-1 · b (HIGH PRIORITY: red)\n"
                                    "- [ ] C-1 · c\n  - priority: low\n- [ ] D-1 · d\n  - priority: high\n")
        got = {t["id"]: monitor.priority_of(t) for t in tasks}
        self.assertEqual(got, {"A-1": "normal", "QAD-1": "low", "B-1": "high", "C-1": "low", "D-1": "high"})

    def test_parse_tasks_reads_status_title_and_agent(self):
        text = ("# TODO\n\n"
                "- [ ] OPEN-1 · First task\n"
                "  - agent: swarm-1\n"
                "- [-] TAKEN-2 · Second\n"
                "  - scope: apps/api\n"
                "- [x] DONE-3 · Third\n\n"
                "not a task line\n"
                "  - indented noise\n")
        tasks = monitor.parse_tasks(text)
        self.assertEqual([t["id"] for t in tasks], ["OPEN-1", "TAKEN-2", "DONE-3"])
        self.assertEqual(tasks[0], {"status": "todo", "id": "OPEN-1", "title": "First task",
                                    "agent": "swarm-1", "priority": ""})
        self.assertEqual(tasks[1]["status"], "taken")
        self.assertEqual(tasks[2]["status"], "done")
        self.assertEqual(monitor.parse_tasks(None), [])

    def test_job_summary_counts_running_queued_finished_and_failed(self):
        jobs = [
            {"status": "in_progress", "name": "A", "startedAt": stamp(2)},
            {"status": "in_progress", "name": "B"},
            {"status": "queued"}, {"status": "waiting"},
            {"status": "completed", "conclusion": "failure"},
            {"status": "in_progress", "name": "C", "startedAt": stamp(1)},
            {"status": "in_progress", "name": "D", "startedAt": stamp(1)},
            {"status": "in_progress", "name": "E", "startedAt": stamp(1)},
        ]
        text = monitor.job_summary(jobs, datetime.datetime.now(UTC))
        self.assertIn("+2 running", text)
        self.assertIn("2 queued", text)
        self.assertIn("1/8 done", text)
        self.assertIn("1 failed", text)
        empty = monitor.job_summary([], datetime.datetime.now(UTC))
        self.assertEqual(empty, "0/0 done")

    def test_failure_detail_reports_a_job_with_and_without_steps(self):
        self.assertEqual(monitor.failure_detail([
            {"status": "completed"},
            {"conclusion": "failure", "name": "Delivery",
             "steps": [{"name": "Run checks", "conclusion": "failure"}]}]), "Delivery / Run checks")
        self.assertEqual(monitor.failure_detail([{"conclusion": "failure", "name": "NoSteps"}]), "NoSteps")
        self.assertEqual(monitor.failure_detail([]), "")


class LayoutHelperTests(unittest.TestCase):
    def test_cells_builds_a_fixed_width_row_with_flex_and_alignment(self):
        row = monitor.cells(10, ("hi", "a", 3), ("there", "b", None), ("x", "c", 2, "r"))
        self.assertEqual(row, [("hi ", "a"), (" ", ""), ("t...", "b"), (" ", ""), (" x", "c")])

    def test_clipline_stops_at_the_requested_width(self):
        line = [("abcdef", "a"), ("ghij", "b")]
        self.assertEqual(monitor.clipline(line, 7), ([("abcdef", "a"), ("g", "b")], 7))
        self.assertEqual(monitor.clipline([("abc", "a"), ("def", "b")], 3), ([("abc", "a")], 3))

    def test_box_draws_a_rounded_card_and_pads_missing_rows(self):
        lines = monitor.box("Title", "badge", "cyan", "gray", [[("hello", "x")]], 10, 5)
        self.assertEqual(len(lines), 5)
        self.assertEqual(lines[0][0], ("╭─", "line"))  # gray border mapped to line
        self.assertEqual(lines[-1][0][0][0], "╰")
        plain = monitor.box("T", "", "red", "red", [], 2, 2)
        self.assertEqual(len(plain), 2)
        self.assertNotIn(("╭─", "line"), plain[0])

    def test_fit_col_handles_natural_height_loading_empty_and_overflow(self):
        panel = lambda items, prio=1, **kw: dict(
            title="T", total=len(items), col="cyan", border="gray", prio=prio, items=items, **kw)
        items = [[("a", "x")], [("b", "x")], [("c", "x")]]

        natural = monitor.fit_col([panel(items)], None, 10)
        self.assertEqual(natural, monitor.box("T", 3, "cyan", "gray", items, 10, 5))

        spare = monitor.fit_col([panel(items, prio=2), panel(items[:2], prio=1)], 100, 10)
        self.assertEqual(len(spare), 100)  # spare lines become padding, filling exactly avail
        self.assertIn(("c", "x"), spare[3])  # higher-priority panel received every item
        self.assertIn(("          ", ""), spare[4])  # an explicit padding row
        self.assertIn((" 2 ", "b_cyan"), spare[50])  # the second card follows the first

        loading = monitor.fit_col([panel([], loading=True)], 10, 10)
        self.assertTrue(any(("loading…", "dim") in row for row in loading))

        empty = monitor.fit_col([panel([])], 10, 10)
        self.assertTrue(any(("(none)", "dim") in row for row in empty))

        overflow = monitor.fit_col([panel(items + [[("d", "x")]])], 5, 12)
        self.assertTrue(any("+2 more" in text for row in overflow for text, _ in row))


if __name__ == "__main__":
    unittest.main()
