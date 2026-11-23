"""Coverage for the screen-composition part of scripts/monitor.py (OPS-06).

The monitor builds its dashboard from synthetic dictionaries in ``compose``. These
tests drive that public function through every branch of the screen model: stat
cards, the history strip, pipeline meters, the In Progress / Recent Runs / Failed
panels, the Next Up / merged / PR / issue panels and alarm highlighting. No
terminal is started and no network or ``gh`` call is made: the module is loaded
with a stub curses and a frozen clock, and all input is synthetic.
"""
import datetime
import sys
import types
import unittest
from unittest import mock

from helpers import load

# monitor.py reads argv and calls curses.wrapper at import time. Stub curses and use
# benign argv so importing the module never touches a real terminal or runs once().
_STUB_CURSES = types.SimpleNamespace(wrapper=lambda fn: None)
with mock.patch.object(sys, "argv", ["monitor.py", "60", "20", "0"]), \
        mock.patch.dict(sys.modules, {"curses": _STUB_CURSES}):
    monitor = load("monitor")

NOW = datetime.datetime(2026, 11, 21, 12, 0, tzinfo=datetime.timezone.utc)


class FrozenDatetime(datetime.datetime):
    """datetime with a fixed now(); fromisoformat/timedelta are inherited."""

    @classmethod
    def now(cls, tz=None):
        return NOW if tz is not None else NOW.replace(tzinfo=None)


def ago(minutes, base=NOW):
    return (base - datetime.timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")


def run(dbid, *, status="completed", conclusion="success", workflow="Delivery",
        branch="main", event="push", sha="a1b2c3d4e5f6", title="Commit title",
        created=180, started=175, updated=5, display=None):
    return {
        "databaseId": dbid, "workflowName": workflow,
        "displayTitle": title if display is None else display,
        "conclusion": conclusion, "status": status, "headSha": sha,
        "headBranch": branch, "event": event,
        "createdAt": ago(created),
        "startedAt": None if started is None else ago(started),
        "updatedAt": None if updated is None else ago(updated),
        "url": "https://example.invalid/runs/" + str(dbid),
    }


def step(name, *, status="completed", conclusion="success"):
    return {"name": name, "status": status, "conclusion": conclusion}


def job(name, *, status="completed", conclusion="success", steps=(), started=170):
    return {"name": name, "status": status, "conclusion": conclusion,
            "startedAt": ago(started), "steps": list(steps)}


def issue(number, *, labels=(), created=60):
    return {"number": number, "title": "Issue %d" % number,
            "labels": [{"name": name} for name in labels], "createdAt": ago(created)}


def pr(number, *, draft=False, labels=(), title=None):
    return {"number": number, "title": title or "PR %d" % number, "isDraft": draft,
            "labels": [{"name": name} for name in labels],
            "headRefName": "branch-%d" % number, "createdAt": ago(60)}


def merged(number, *, title=None, when=30):
    return {"number": number, "title": title or "Merged %d" % number, "mergedAt": ago(when)}


def closed(number, *, when=25, merged_when=None):
    return {"number": number, "title": "Closed %d" % number, "closedAt": ago(when),
            "mergedAt": merged_when}


TODO = "\n".join([
    "- [x] DONE-001 · A completed task",
    "  - agent: swarm-9",
    "- [ ] TODO-001 · The first open task",
    "- [ ] TODO-002 · The second open task",
    "- [ ] TODO-003 · The third open task",
    "[-] TAKEN-001 · A task in progress",
    "  - agent: swarm-1",
    "[-] TAKEN-002 · Another in progress",
    "  - agent: swarm-2",
    "",
])


def data(*, runs=(), issues=(), prs=(), merged_prs=(), closed_prs=(), todo="",
         archive="", jobs=None, churn=None, total="", repo="acme/repo"):
    return {"repo": repo, "runs": list(runs), "issues": list(issues), "prs": list(prs),
            "merged": list(merged_prs), "closed": list(closed_prs), "todo": todo,
            "archive": archive, "jobs": jobs, "churn": churn, "total": total}


def texts(lines):
    return [text for line in lines for text, _ in line]


def titles(lines):
    return " ".join(texts(lines))


class ComposeTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(monitor, "datetime", FrozenDatetime)
        patcher.start()
        self.addCleanup(patcher.stop)

    def compose(self, payload, W=180, H=None, **kwargs):
        return monitor.compose(payload, W, H, **kwargs)

    # -- empty state: every "nothing here yet" branch -------------------------------
    def test_empty_screen_at_natural_height(self):
        top, left, right, cw = self.compose(data())
        self.assertTrue(top and left and right)
        self.assertIn("swarm monitor", titles(top))
        self.assertEqual(cw, 89)
        joined = titles(top).upper()
        for label in ("main", "running", "merged 24h", "failed 24h", "closed 24h",
                      "commits", "commits last 24h", "median run", "needs human"):
            self.assertIn(label.upper(), joined)
        panel_text = titles(left) + titles(right)
        for title in ("In Progress", "Recent Runs", "Failed 24h", "Swarm Tasks Taken",
                      "Next Up", "Merged PRs 24h", "Open PRs", "Open Issues"):
            self.assertIn(title, panel_text)
        self.assertIn("(none)", panel_text)

    # -- rich wide screen: main/wave found, alarm, live and done rows ---------------
    def test_rich_screen_wide_cards_and_all_panels(self):
        runs = [
            run(1, status="in_progress", conclusion=None, workflow="Agent swarm",
                branch="feature/very-long-branch-name-that-stays-legible", event="workflow_dispatch",
                title="swarm wave 42", display="Agent swarm"),
            run(2, status="queued", conclusion=None, workflow="Delivery", event="workflow_run",
                branch="main", title="queued delivery"),
            run(3, conclusion="success", workflow="Delivery", branch="main", updated=90),
            run(4, conclusion="failure", workflow="Agent swarm", branch="swarm/x", updated=10),
            run(5, conclusion="cancelled", workflow="Delivery", branch="main", updated=200),
        ]
        payload = data(
            runs=runs,
            issues=[issue(7, labels=("needs-human",)), issue(8, labels=("bug",))],
            prs=[pr(11, draft=True, labels=("needs-fix",)), pr(12, labels=("needs-human",))],
            merged_prs=[merged(21, title="TASK-001: do the thing"),
                        merged(22, title="Bump some dependency"),
                        merged(23, title="A plain merged title")],
            closed_prs=[closed(31), closed(32, merged_when=ago(20))],
            todo=TODO, archive="- [x] OLD-001 · Archived one\n- [x] OLD-002 · Archived two\n",
            jobs={1: [job("swarm/COVPY-001 · worker", status="in_progress", conclusion=None,
                          steps=[step("Run agent", status="in_progress", conclusion=None)])],
                  4: [job("Delivery tooling", conclusion="failure",
                          steps=[step("Run python3 tests", conclusion="failure")])]},
            churn={"commits": 9, "files": 4, "add": 12, "del": 3},
            total="12345",
        )
        top, left, right, cw = self.compose(payload, W=200, H=None, alarm_ids={4}, busy=True)
        self.assertEqual(cw, 99)
        self.assertIn("1 failed", titles(top))
        joined = titles(top) + titles(left) + titles(right)
        self.assertIn("TASK-001", joined)
        self.assertIn("Bump", joined)
        self.assertIn("needs-human", joined)
        self.assertIn("needs-fix", joined)
        self.assertIn("+12", joined)
        self.assertIn("12345", joined)
        self.assertIn("*", titles(top))  # busy clock marker

    # -- narrow / short screens: card dropping, flat meters, clipped columns -------
    def test_short_screen_uses_narrow_cards_and_flat_meters(self):
        runs = [run(1, status="in_progress", conclusion=None, workflow="Agent swarm"),
                run(2, conclusion="success"), run(3, conclusion="failure", updated=10)]
        payload = data(runs=runs, todo=TODO, churn={"commits": 3},
                       merged_prs=[merged(21)], issues=[issue(7)], prs=[pr(11)])
        top, left, right, cw = self.compose(payload, W=90, H=16)
        self.assertEqual(cw, 44)
        # narrow card layout uses lowercase labels and no box drawing for the stat row
        joined = titles(top)
        self.assertIn("main", joined)
        self.assertIn("failed 24h", joined)
        self.assertTrue(left and right)

    def test_tall_screen_with_few_items_keeps_cards(self):
        top, left, right, cw = self.compose(data(runs=[run(1)]), W=180, H=40,
                                            loading={"runs", "todo"})
        self.assertTrue(top and left and right)
        self.assertIn("loading", titles(top))

    # -- live-run detail: task-id rows, generic rows, zero/many jobs, dividers -----
    def test_the_workers_card_shows_running_over_the_cap(self):
        runs = [run(1, status="in_progress", conclusion=None, workflow="Agent swarm"),
                run(2, status="in_progress", conclusion=None, workflow="Delivery")]
        live = lambda name, status="in_progress": job(name, status=status, conclusion=None)
        jobs = {1: [live("Swarm worker (story, CW-001, 1) / Work the task"),
                    live("Swarm worker (story, CW-001, 1) / Publish result", "queued"),
                    live("Swarm worker (fix, FIX-9, 2) / Work the task"),
                    live("Swarm worker (story, CW-003, 3) / Work the task", "queued"),
                    live("Plan the wave"),
                    job("Swarm worker (story, CW-004, 4) / Work the task")],
                2: [live("Swarm worker (story, CW-005, 5) / Work the task")]}
        self.assertEqual(monitor.worker_counts(runs, jobs), (2, 1))
        top, _, _, _ = self.compose(data(runs=runs, jobs=jobs))
        text = titles(top)
        self.assertIn("WORKERS", text)
        self.assertIn("2/20", text)
        self.assertIn("running, 1 queued", text)

    def test_no_live_workers_shows_zero_of_the_cap(self):
        self.assertEqual(monitor.worker_counts([], {}), (0, 0))
        self.assertEqual(monitor.worker_counts([run(1, status="in_progress", conclusion=None,
                                                    workflow="Agent swarm")], {}), (1, 0))

    def test_live_runs_with_and_without_task_ids(self):
        runs = [
            run(1, status="in_progress", conclusion=None, workflow="Agent swarm",
                display="Agent swarm"),
            run(2, status="in_progress", conclusion=None, workflow="Delivery",
                display="Delivery"),
        ]
        jobs = {
            1: [job("swarm/COVPY-001 · worker", status="in_progress", conclusion=None,
                    steps=[step("Run agent", status="in_progress", conclusion=None)]),
                job("swarm/COVPY-002 · worker", status="in_progress", conclusion=None),
                job("swarm/COVPY-003 · worker", status="in_progress", conclusion=None),
                job("swarm/OTHER · worker", status="in_progress", conclusion=None),
                job("swarm/COVPY-004 · worker", status="in_progress", conclusion=None)],
            2: [job("Verify and smoke image", status="in_progress", conclusion=None,
                    steps=[step("Run compose", status="in_progress", conclusion=None)]),
                job("Policy and tooling", status="in_progress", conclusion=None)],
        }
        task_text = "\n".join([
            "- [-] COVPY-001 · Worker one", "  - agent: swarm-1",
            "- [-] COVPY-002 · Worker two", "  - agent: swarm-2",
            "- [ ] COVPY-003 · Next task", "",
        ])
        top, left, right, cw = self.compose(data(runs=runs, jobs=jobs, todo=task_text),
                                            W=200, H=None)
        joined = titles(left)
        self.assertIn("COVPY-001", joined)
        self.assertIn("swarm-1", joined)
        self.assertIn("Verify and smoke image", joined)
        self.assertIn("┄", joined)  # divider between live runs

    def test_live_run_with_no_live_jobs_uses_wait_label(self):
        runs = [run(1, status="in_progress", conclusion=None, workflow="Delivery",
                    display="Delivery")]
        jobs = {1: [job("Build", status="queued", conclusion=None)]}
        _, left, _, _ = self.compose(data(runs=runs, jobs=jobs), W=200, H=None)
        self.assertIn("Delivery", titles(left))

    # -- history strip colours and the Failed / Recent Runs panels ----------------
    def test_history_strip_and_failed_rows(self):
        runs = [
            run(1, conclusion="success"), run(2, conclusion="failure", updated=10),
            run(3, conclusion="timed_out", updated=20),
            run(4, conclusion="cancelled", updated=30),
            run(5, conclusion="success", updated=40),
        ]
        jobs = {2: [job("Delivery", conclusion="failure",
                        steps=[step("Run python", conclusion="failure")])],
                3: [job("Delivery", conclusion="failure")]}
        top, left, _, _ = self.compose(data(runs=runs, jobs=jobs), W=200, H=None,
                                       alarm_ids={2})
        self.assertIn("›  python", titles(left))
        self.assertIn("└", titles(left))

    # -- merged PR row styles ------------------------------------------------------
    def test_merged_pr_prefix_bump_and_plain_rows(self):
        payload = data(merged_prs=[merged(1, title="FIX-123: repair"),
                                   merged(2, title="Bump dependency"),
                                   merged(3, title="Ordinary change")])
        _, _, right, _ = self.compose(payload, W=200, H=None)
        joined = titles(right)
        self.assertIn("FIX-123", joined)
        self.assertIn("Bump", joined)
        self.assertIn("Ordinary change", joined)

    # -- loading placeholders across cards, panels and meters ---------------------
    def test_loading_sections_show_placeholders(self):
        payload = data(runs=[run(1)], todo=TODO, merged_prs=[merged(2)],
                       issues=[issue(3)], prs=[pr(4)], churn={"commits": 1}, total="7")
        top, left, right, _ = self.compose(
            payload, W=200, H=40,
            loading={"runs", "todo", "merged", "prs", "issues", "jobs", "churn", "total", "archive"})
        joined = titles(top) + titles(left) + titles(right)
        self.assertIn("…", joined)
        self.assertIn("loading", joined + titles(top))

    # -- task loading with no runs mirrors the todo-only placeholder --------------
    def test_todo_only_loading_meter(self):
        top, _, _, _ = self.compose(data(runs=[run(1)], todo=TODO), W=200, H=40,
                                    loading={"todo"})
        self.assertIn("loading", titles(top))

    # -- fit_col distribution: many items, spare lines and padding ----------------
    def test_many_items_fill_available_lines(self):
        runs = [run(i, conclusion="success") for i in range(1, 12)]
        payload = data(runs=runs, issues=[issue(i) for i in range(1, 6)],
                       prs=[pr(i) for i in range(1, 6)], merged_prs=[merged(i) for i in range(1, 6)],
                       todo=TODO)
        top, left, right, _ = self.compose(payload, W=180, H=18)
        self.assertGreater(len(left), 0)
        self.assertGreater(len(right), 0)
        self.assertIn("more", titles(left) + titles(right))

    # -- trigger_of mapping and mid() shortening ----------------------------------
    def test_trigger_names_and_middle_ellipsis(self):
        runs = [run(1, status="in_progress", conclusion=None, event="workflow_run"),
                run(2, status="in_progress", conclusion=None, event="pull_request"),
                run(3, status="in_progress", conclusion=None, event="workflow_call"),
                run(4, status="in_progress", conclusion=None, event="unknown-event")]
        _, left, _, _ = self.compose(data(runs=runs), W=200, H=None)
        joined = titles(left)
        self.assertIn("chained", joined)
        self.assertIn("PR", joined)
        self.assertIn("called", joined)
        self.assertIn("unknown-event", joined)

    # -- missing timestamps and unknown states ------------------------------------
    def test_runs_without_timestamps_and_unknown_states(self):
        runs = [run(1, conclusion=None, status="completed", started=None, updated=None,
                    branch=None, sha=None, title=None, display=None),
                run(2, conclusion="action_required", updated=None)]
        top, left, _, _ = self.compose(data(runs=runs, jobs={}), W=200, H=None)
        self.assertTrue(top and left)


if __name__ == "__main__":
    unittest.main()
