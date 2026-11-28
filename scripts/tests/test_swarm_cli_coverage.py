"""CLI and delivery-helper coverage for scripts/swarm.py (OPS-06).

This file closes the uncovered lines of the swarm script's command-line slice:
the shared Delivery listing, the failed-run repair helpers and every uncovered
subcommand of ``main``. Every test goes through a public function or
``swarm.main([...])``, replaces the ``gh`` boundary and each dispatched callee,
and asserts an observable result (a return value, a printed line or an emitted
output). All data is synthetic; no test reaches the network, a real repository,
``TODO.md``, ``.agent/`` or a real credential.
"""

import contextlib
import io
import json
import os
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")

# Small synthetic slot table, matching the shape of .opencode/config/slots.json.
SLOTS = [{"slot": n} for n in range(1, 4)]


class FakeGh:
    """Record gh calls and answer the run-view queries made by the repair helper."""

    def __init__(self, run=None, log=""):
        self.calls = []
        self.run = run or {}
        self.log = log

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("run", "view"):
            if "--log-failed" in args:
                return self.log
            return json.dumps(self.run)
        return ""

    def said(self, *needle):
        return any(all(n in " ".join(map(str, call)) for n in needle) for call in self.calls)


def failed_run(jobs):
    """A synthetic failed 'Agent swarm' run on main with the supplied job list."""
    return {"name": "Agent swarm", "headBranch": "main", "headSha": "a" * 40,
            "conclusion": "failure", "url": "https://example.invalid/runs/9", "jobs": jobs}


class CliTestCase(unittest.TestCase):
    """Give every test a scratch directory, a temp GITHUB_OUTPUT and captured streams.

    ``emit`` appends to ``GITHUB_OUTPUT``; pointing it at a fresh file keeps emitted
    outputs inspectable without touching the real GitHub output or the repository.
    ``helpers.load`` scrubs ambient GitHub/CI variables on import, so the variable is
    installed explicitly here.
    """

    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.dir = Path(scratch.name)
        self.output = self.dir / "github-output"
        env = mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(self.output)})
        env.start()
        self.addCleanup(env.stop)
        out = mock.patch("sys.stdout", new_callable=io.StringIO)
        err = mock.patch("sys.stderr", new_callable=io.StringIO)
        self.stdout = out.start()
        self.addCleanup(out.stop)
        self.stderr = err.start()
        self.addCleanup(err.stop)

    def emitted(self):
        """Parse the name=value lines written by ``emit`` into a dictionary."""
        if not self.output.exists():
            return {}
        result = {}
        for line in self.output.read_text().splitlines():
            if "=" in line:
                name, value = line.split("=", 1)
                result[name] = value
        return result


class DeliveryListingCoverageTests(CliTestCase):
    """The shared Delivery listing always returns a well-formed list."""

    def test_a_list_answer_is_returned_with_the_shared_query(self):
        listing = [{"headSha": "a", "conclusion": "success", "event": "pull_request",
                    "workflowDatabaseId": 123}]
        with mock.patch.object(swarm, "gh_json", side_effect=[{"id": 123}, listing]) as gh_json:
            result = swarm.delivery_listing()
        self.assertEqual(result.runs, listing)
        self.assertEqual(result.workflow_id, 123)
        self.assertFalse(result.full)
        gh_json.assert_any_call(
            "run", "list", "--workflow", "123", "--limit", str(swarm.DELIVERY_LISTING_LIMIT),
            "--json", "databaseId,headSha,status,conclusion,event,workflowDatabaseId")

    def test_full_window_is_flagged_and_valid_runs_do_not_fallback(self):
        head = "a" * 40
        valid = {"databaseId": 7, "headSha": head, "event": "pull_request",
                 "workflowDatabaseId": 123, "status": "completed", "conclusion": "success"}
        raw = [valid] + [{"databaseId": n + 100, "headSha": head, "event": "push",
                          "workflowDatabaseId": 123, "status": "completed",
                          "conclusion": "success"} for n in range(99)]
        with mock.patch.object(swarm, "gh_json", side_effect=[{"id": 123}, raw]):
            listing = swarm.delivery_listing()
        self.assertTrue(listing.full)
        with mock.patch.object(swarm, "validated_delivery_runs", return_value=[valid]), \
                mock.patch.object(swarm, "delivery_runs") as fallback:
            self.assertEqual(swarm.delivery_runs_from(listing, head, 7), [valid])
        fallback.assert_not_called()

    def test_a_non_list_answer_raises(self):
        with mock.patch.object(swarm, "gh_json", side_effect=[{"id": 123}, {"message": "rate limited"}]):
            with self.assertRaises(ValueError):
                swarm.delivery_listing()


class RepairTaskCoverageTests(CliTestCase):
    """Repair-task generation distinguishes missing, routine and real failures."""

    def test_repair_task_ignores_a_missing_run(self):
        error = subprocess.CalledProcessError(1, "gh", output="", stderr="404 Not Found")
        with mock.patch.object(swarm, "gh_json", side_effect=error):
            self.assertIsNone(swarm.repair_task("9"))
        self.assertIn("run 9 no longer exists", self.stdout.getvalue())

    def test_repair_task_reraises_other_gh_failures(self):
        error = subprocess.CalledProcessError(1, "gh", output="boom", stderr="")
        with mock.patch.object(swarm, "gh_json", side_effect=error):
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.repair_task("9")

    def test_repair_task_ignores_a_non_failure_run(self):
        # Both halves of the guard matter: a green run and a run on another branch
        # are equally uninteresting to the repair planner.
        run = failed_run([{"name": "Plan the wave", "conclusion": "failure"}])
        run["headBranch"] = "feature"
        with mock.patch.object(swarm, "gh_json", return_value=run):
            self.assertIsNone(swarm.repair_task("9"))
        run["headBranch"] = "main"
        run["conclusion"] = "success"
        with mock.patch.object(swarm, "gh_json", return_value=run):
            self.assertIsNone(swarm.repair_task("9"))

    def test_repair_task_skips_a_worker_only_failure(self):
        # A timed-out worker is retried by the planner; main is not broken and a
        # repair would block the whole swarm, so no failed-log call is made.
        run = failed_run([
            {"name": "Plan the wave", "conclusion": "success"},
            {"name": "Swarm worker (story, CW-001, 0, Title) / Work the task", "conclusion": "failure"},
        ])
        fake = FakeGh(run=run)
        with mock.patch.object(swarm, "gh_json", return_value=run), \
                mock.patch.object(swarm, "gh", fake):
            self.assertIsNone(swarm.repair_task("9"))
        self.assertFalse(fake.said("--log-failed"))

    def test_repair_task_builds_a_story_task(self):
        run = failed_run([{"name": "Java and database verification", "conclusion": "failure"}])
        fake = FakeGh(run=run, log="line one\n```\nline two")
        with mock.patch.object(swarm, "gh_json", return_value=run), \
                mock.patch.object(swarm, "gh", fake):
            task = swarm.repair_task("9")
        self.assertEqual(task["id"], "FIX-9")
        self.assertEqual(task["kind"], "story")
        self.assertEqual(task["title"], "Repair main: Agent swarm failed")
        self.assertIn("line two", task["body"])
        self.assertIn("```text", task["body"])
        self.assertIn("'''", task["body"])  # log fences are neutralised, not the template's
        self.assertTrue(fake.said("--log-failed"))


class MainDispatchCoverageTests(CliTestCase):
    """Route each uncovered subcommand and assert its output, never its callee's logic."""

    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(swarm, "active_waves", return_value=(True, 0, ""))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_main_ensure_labels_routes_to_ensure_labels(self):
        with mock.patch.object(swarm, "ensure_labels") as ensure:
            self.assertEqual(swarm.main(["ensure-labels"]), 0)
        ensure.assert_called_once_with()

    def test_main_reconcile_tasks_persists_and_prints_the_count(self):
        with mock.patch.object(swarm, "reconcile_tasks", return_value=3) as reconcile:
            self.assertEqual(swarm.main(["reconcile-tasks", "--dir", "tasks"]), 0)
        reconcile.assert_called_once_with("tasks", "", persist=True)
        self.assertIn("reconciled 3 task(s)", self.stdout.getvalue())

    def test_main_draft_triage_emits_the_count(self):
        with mock.patch.object(swarm, "draft_triage", return_value=2):
            self.assertEqual(swarm.main(["draft-triage"]), 0)
        self.assertEqual(self.emitted().get("drafts"), "2")

    def test_main_plan_workers_returns_an_empty_wave_when_the_tracker_push_is_blocked(self):
        include = [{"id": "CW-001", "kind": "story", "slot": 1, "title": "",
                    "body": "", "fix": ""}]
        with mock.patch.object(swarm, "gh_json", side_effect=[[], []]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", return_value=[]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value="text"), \
                mock.patch.object(swarm, "reclaim", return_value="text"), \
                mock.patch.object(swarm, "plan_workers", return_value=include), \
                mock.patch.object(swarm, "update_todo",
                                  side_effect=swarm.TrackerPushBlocked()), \
                mock.patch.object(swarm, "note_claims") as note:
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        emitted = self.emitted()
        self.assertEqual(emitted["matrix"], '{"include":[]}')
        self.assertEqual(emitted["count"], "0")
        self.assertIn("repository rules", emitted["reason"])
        note.assert_not_called()

    def test_main_plan_workers_emits_capacity_unavailable_reason(self):
        with mock.patch.object(swarm, "gh_json", side_effect=[[], []]), \
                mock.patch.object(swarm, "waiting_waves") as waves, \
                mock.patch.object(swarm, "live_workers",
                                  side_effect=swarm.CapacityUnavailable()), \
                mock.patch("builtins.print"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        waves.assert_not_called()
        self.assertEqual(self.emitted(), {"matrix": '{"include":[]}', "count": "0",
                                          "reason": "capacity_unavailable"})
        for line in self.output.read_text().splitlines():
            self.assertFalse(line.startswith(("::", "##[")))

    def test_main_plan_workers_emits_wave_waiting_reason(self):
        with mock.patch.object(swarm, "gh_json", side_effect=[[], []]), \
                mock.patch.object(swarm, "waiting_waves", return_value=1), \
                mock.patch.object(swarm, "live_workers", return_value=[]), \
                mock.patch.object(swarm, "plan_workers") as planned, \
                mock.patch("builtins.print"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        planned.assert_not_called()
        self.assertEqual(self.emitted(), {"matrix": '{"include":[]}', "count": "0",
                                          "reason": "wave_waiting"})
        for line in self.output.read_text().splitlines():
            self.assertFalse(line.startswith(("::", "##[")))

    def test_main_plan_workers_empty_plan_reason_carries_no_untrusted_text(self):
        text = (
            "# TODO\n\n"
            "- [ ] DLV-9 · Untrusted repair title\n"
            "  - scope: apps/api/a::error::injected##[error]leak\n"
        )
        with mock.patch.object(swarm, "gh_json", return_value=[]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=True), \
                mock.patch.object(swarm, "live_workers", return_value=["FIX-42"]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value=text), \
                mock.patch.object(swarm, "reclaim", return_value=text), \
                mock.patch.object(swarm, "plan_workers", return_value=[]), \
                mock.patch.object(swarm, "update_todo"), mock.patch.object(swarm, "note_claims"), \
                mock.patch("builtins.print") as shown:
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        lines = [str(call.args[0]) for call in shown.call_args_list if call.args]
        self.assertTrue(any("no workers planned" in line for line in lines))
        self.assertTrue(any("::error::injected" in line for line in lines))
        self.assertEqual(self.emitted(), {"matrix": '{"include":[]}', "count": "0",
                                          "reason": "empty_plan"})
        for line in self.output.read_text().splitlines():
            self.assertFalse(line.startswith(("::", "##[")))

    def test_main_plan_workers_empty_plan_with_blocked_push_keeps_tracker_reason_last(self):
        text = "# TODO\n\n- [ ] CW-001 · Ready\n  - scope: apps/api/a\n"
        with mock.patch.object(swarm, "gh_json", return_value=[]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", return_value=[]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value=text), \
                mock.patch.object(swarm, "reclaim", return_value=text), \
                mock.patch.object(swarm, "plan_workers", return_value=[]), \
                mock.patch.object(swarm, "update_todo",
                                  side_effect=swarm.TrackerPushBlocked()), \
                mock.patch.object(swarm, "note_claims") as note, \
                mock.patch("builtins.print"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        note.assert_not_called()
        self.assertEqual(self.emitted()["reason"], "tracker push blocked by repository rules")
        for line in self.output.read_text().splitlines():
            self.assertFalse(line.startswith(("::", "##[")))

    def test_main_plan_workers_ignore_red_main_skips_the_check(self):
        include = [{"id": "CW-001", "kind": "story", "slot": 1, "title": "",
                    "body": "", "fix": ""}]
        with mock.patch.object(swarm, "gh_json", return_value=[]), \
                mock.patch.object(swarm, "main_is_red") as red, \
                mock.patch.object(swarm, "live_workers", return_value=[]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value="text"), \
                mock.patch.object(swarm, "reclaim", return_value="text"), \
                mock.patch.object(swarm, "plan_workers", return_value=include) as planned, \
                mock.patch.object(swarm, "update_todo"), mock.patch.object(swarm, "note_claims"), \
                mock.patch.object(swarm, "emit"), \
                mock.patch("builtins.print") as shown:
            self.assertEqual(swarm.main(["plan-workers", "--ignore-red-main",
                                         "--file", str(self.dir / "TODO.md")]), 0)
        red.assert_not_called()
        self.assertIs(planned.call_args.kwargs["red"], False)
        lines = [str(c.args[0]) for c in shown.call_args_list if c.args]
        self.assertTrue(any("ignore-red-main override active" in line for line in lines))

    def test_main_plan_workers_explains_an_empty_wave(self):
        text = (
            "# TODO\n\n"
            "- [ ] CW-001 · Ready\n  - scope: apps/api/a\n  - details:\n    Do it\n\n"
        )
        with mock.patch.object(swarm, "gh_json", return_value=[]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=True), \
                mock.patch.object(swarm, "live_workers", return_value=["FIX-42"]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value=text), \
                mock.patch.object(swarm, "reclaim", return_value=text), \
                mock.patch.object(swarm, "plan_workers", return_value=[]), \
                mock.patch.object(swarm, "update_todo"), mock.patch.object(swarm, "note_claims"), \
                mock.patch.object(swarm, "emit"), \
                mock.patch("builtins.print") as shown:
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        lines = [str(c.args[0]) for c in shown.call_args_list if c.args]
        reason = next(line for line in lines if "no workers planned" in line)
        self.assertIn("main_red=True", reason)
        self.assertIn("live=1", reason)
        self.assertIn("open_prs=0", reason)

    def test_main_reconcile_tasks_reports_write_failure_as_nonzero(self):
        with mock.patch.object(swarm, "reconcile_tasks",
                               side_effect=swarm.TrackerPushBlocked()), \
                contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(swarm.main(["reconcile-tasks"]), 1)
        self.assertIn("repository rules", err.getvalue())

    def test_main_reconcile_tasks_reports_git_failure_as_nonzero(self):
        error = subprocess.CalledProcessError(1, ("git", "push"), stderr="push rejected")
        error.stderr = "push rejected"
        with mock.patch.object(swarm, "reconcile_tasks", side_effect=error), \
                contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(swarm.main(["reconcile-tasks"]), 1)
        self.assertIn("returned non-zero exit status 1", err.getvalue())

    def test_main_reconcile_tasks_reports_evidence_failure_as_nonzero(self):
        with mock.patch.object(swarm, "reconcile_tasks",
                               side_effect=swarm.ReconciliationError("bad evidence")), \
                contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(swarm.main(["reconcile-tasks"]), 1)
        self.assertIn("bad evidence", err.getvalue())

    def test_main_archive_todo_prints_the_archived_count(self):
        with mock.patch.object(swarm, "archive_todo", return_value=2):
            self.assertEqual(swarm.main(["archive-todo"]), 0)
        self.assertIn(f"archived 2 finished task(s) to {swarm.store().ROOT}/{swarm.store().DONE}",
                      self.stdout.getvalue())

    def test_main_slot_matrix_emits_the_slot_list(self):
        with mock.patch.object(swarm, "load_slots", return_value=SLOTS):
            self.assertEqual(swarm.main(["slot-matrix"]), 0)
        self.assertEqual(self.emitted().get("matrix"), '{"slot":[1,2,3]}')

    def test_main_slot_matrix_propagates_a_loader_error(self):
        # No local slots file and no try/except on this dispatch: the failure must
        # surface rather than silently emit an empty matrix.
        with mock.patch.object(swarm, "load_slots", side_effect=FileNotFoundError("no slots")):
            with self.assertRaises(FileNotFoundError):
                swarm.main(["slot-matrix"])

    def test_main_slot_config_prints_the_config(self):
        with mock.patch.object(swarm, "load_slots", return_value=SLOTS):
            self.assertEqual(swarm.main(["slot-config", "--slot", "2"]), 0)
        printed = json.loads(self.stdout.getvalue())
        self.assertNotIn("model", printed)
        self.assertIs(printed["autoupdate"], False)

    def test_main_slot_config_rejects_a_nested_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "slots.json"
            path.write_text(json.dumps({"slots": [
                {"slot": 1, "config": {"nested": {"model": "opencode/test"}}}
            ]}))
            with self.assertRaisesRegex(ValueError, "must not select a model"):
                swarm.main(["slot-config", "--slot", "1", "--slots", str(path)])

    def test_main_plan_workers_reclaims_and_claims_on_every_push_retry(self):
        # The nested `mark` callback is passed to update_todo; a fake that invokes it
        # proves the newest TODO.md is re-claimed on every push retry.
        prs = [{"number": 7, "headRefName": "swarm/story"}]
        current = ("- [-] CW-001 · Story\n  - scope: apps/story\n"
                   "- [ ] FIX-9 · Fix\n  - scope: apps/fix\n")

        def call_edit(edit, message, path):
            self.assertEqual(message, "Take 1 task(s) for the swarm")
            edit(current)
            return True

        include = [{"id": "CW-001", "kind": "story", "slot": 1, "title": "secret",
                    "body": "secret", "fix": "secret"},
                   {"id": "FIX-9", "kind": "fix", "slot": 2, "title": "secret",
                    "body": "secret", "fix": "secret"}]
        with mock.patch.object(swarm, "gh_json", side_effect=[prs, [], prs, []]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", side_effect=[[], []]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value=current), \
                mock.patch.object(swarm, "reclaim", side_effect=lambda text, *args: text) as reclaim, \
                mock.patch.object(swarm, "claim_open", return_value=("taken", {"CW-001"})) as claim, \
                mock.patch.object(swarm, "plan_workers", side_effect=[include, include]), \
                mock.patch.object(swarm, "update_todo", side_effect=call_edit) as update, \
                mock.patch.object(swarm, "note_claims") as note:
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        update.assert_called_once()
        self.assertEqual(reclaim.call_count, 2)  # once when planning, once in `mark`
        claim.assert_called_once_with(current, {"CW-001": "swarm-1"}, force=False)
        note.assert_called_once()
        emitted = self.emitted()
        self.assertEqual([t["id"] for t in json.loads(emitted["matrix"])["include"]],
                         ["CW-001", "FIX-9"])
        self.assertTrue(all(not t[field] for t in json.loads(emitted["matrix"])["include"]
                            for field in ("title", "body", "fix")))
        self.assertEqual(emitted["count"], "2")
        self.assertNotIn("reason", emitted)

    def test_main_plan_workers_drops_a_fix_that_is_incomplete_before_final_mark(self):
        include = [{"id": "STORY-1", "kind": "story", "slot": 1, "title": "", "body": "", "fix": ""},
                   {"id": "FIX-1", "kind": "fix", "slot": 2, "title": "", "body": "", "fix": ""}]
        fresh_plan = [{**include[0], "slot": 2}]
        current = ("- [ ] STORY-1 · Ready\n  - scope: apps/story\n"
                   "- [!] FIX-1 · Held fix\n  - scope: apps/fix\n"
                   "- [!] BLOCK-1 · Overlapping incomplete task\n  - scope: apps/fix/deep\n")

        def call_edit(edit, message, path):
            self.assertEqual(message, "Take 1 task(s) for the swarm")
            edit(current)
            return True

        with mock.patch.object(swarm, "gh_json", side_effect=[[], [], [], []]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", side_effect=[[], []]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value=current), \
                mock.patch.object(swarm, "reclaim", side_effect=lambda text, *args: text), \
                mock.patch.object(swarm, "plan_workers", side_effect=[include, fresh_plan]), \
                mock.patch.object(swarm, "claim_open", return_value=("taken", {"STORY-1"})) as claim, \
                mock.patch.object(swarm, "update_todo", side_effect=call_edit), \
                mock.patch.object(swarm, "note_claims"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        claim.assert_called_once_with(current, {"STORY-1": "swarm-2"}, force=False)
        emitted = self.emitted()
        planned = json.loads(emitted["matrix"])["include"]
        self.assertEqual([task["id"] for task in planned], ["STORY-1"])
        self.assertEqual(planned[0]["slot"], 2)
        self.assertEqual(emitted["count"], "1")

    def test_main_plan_workers_replans_with_fresh_capacity_slots_prs_and_live(self):
        include = [{"id": "STORY-1", "kind": "story", "slot": 1, "title": "", "body": "", "fix": ""}]
        fresh_include = [{**include[0], "slot": 9}]
        fresh_prs = [{"number": 9, "headRefName": "swarm/FRESH-1-1"}]
        fresh_everything = [{"number": 9, "headRefName": "swarm/FRESH-1-1", "state": "OPEN"}]
        fresh_live = ["BUSY-1"]
        fresh_slots = [{"slot": 9}]
        current = "- [ ] STORY-1 · Ready\n  - scope: apps/story\n"

        def call_edit(edit, message, path):
            edit(current)
            return True

        with mock.patch.object(swarm, "gh_json", side_effect=[[], [], fresh_prs, fresh_everything]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", side_effect=[[], fresh_live]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "scheduler_room", side_effect=[2, 1]) as room, \
                mock.patch.object(swarm, "worker_slots", side_effect=[SLOTS, fresh_slots]) as slots, \
                mock.patch.object(swarm, "todo_read", return_value=current), \
                mock.patch.object(swarm, "reclaim", side_effect=lambda text, *args: text), \
                mock.patch.object(swarm, "plan_workers", side_effect=[include, fresh_include]) as plan, \
                mock.patch.object(swarm, "claim_open", return_value=("taken", {"STORY-1"})) as claim, \
                mock.patch.object(swarm, "update_todo", side_effect=call_edit), \
                mock.patch.object(swarm, "note_claims"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        self.assertEqual([call.args[1] for call in room.call_args_list], [0, 1])
        self.assertEqual([call.args[1] for call in slots.call_args_list], [0, 1])
        replan = plan.call_args_list[1].args
        self.assertEqual(replan[0], 1)
        self.assertEqual(replan[1], fresh_slots)
        self.assertEqual(replan[2], current)
        self.assertEqual(replan[3], fresh_prs)
        self.assertEqual(replan[5], fresh_live)
        claim.assert_called_once_with(current, {"STORY-1": "swarm-9"}, force=False)
        self.assertEqual(json.loads(self.emitted()["matrix"])["include"][0]["slot"], 9)

    def test_main_plan_workers_emits_empty_matrix_when_fresh_wave_cap_is_reached(self):
        include = [{"id": "STORY-1", "kind": "story", "slot": 1, "title": "", "body": "", "fix": ""}]
        current = "- [ ] STORY-1 · Ready\n  - scope: apps/story\n"

        def call_edit(edit, message, path):
            edit(current)
            return True

        with mock.patch.object(swarm, "gh_json", side_effect=[[], [], [], []]), \
                mock.patch.object(swarm, "active_waves", side_effect=[
                    (True, 0, ""), (False, swarm.concurrent_wave_limit(), "wave cap")]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", side_effect=[[], []]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value=current), \
                mock.patch.object(swarm, "reclaim", side_effect=lambda text, *args: text), \
                mock.patch.object(swarm, "plan_workers", return_value=include) as plan, \
                mock.patch.object(swarm, "claim_open", return_value=(current, set())) as claim, \
                mock.patch.object(swarm, "update_todo", side_effect=call_edit), \
                mock.patch.object(swarm, "note_claims"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        self.assertEqual(plan.call_count, 1)
        claim.assert_called_once_with(current, {}, force=False)
        emitted = self.emitted()
        self.assertEqual(emitted["matrix"], '{"include":[]}')
        self.assertEqual(emitted["count"], "0")

    def test_main_plan_workers_reports_capacity_unavailable_from_final_refresh(self):
        include = [{"id": "STORY-1", "kind": "story", "slot": 1, "title": "", "body": "", "fix": ""}]
        current = "- [ ] STORY-1 · Ready\n  - scope: apps/story\n"

        def call_edit(edit, message, path):
            edit(current)
            return True

        with mock.patch.object(swarm, "gh_json", side_effect=[[], [], [], []]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", side_effect=[[], swarm.CapacityUnavailable()]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value=current), \
                mock.patch.object(swarm, "reclaim", side_effect=lambda text, *args: text), \
                mock.patch.object(swarm, "plan_workers", return_value=include) as plan, \
                mock.patch.object(swarm, "claim_open", return_value=(current, set())) as claim, \
                mock.patch.object(swarm, "update_todo", side_effect=call_edit), \
                mock.patch.object(swarm, "note_claims"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        self.assertEqual(plan.call_count, 1)
        claim.assert_called_once_with(current, {}, force=False)
        emitted = self.emitted()
        self.assertEqual(emitted["matrix"], '{"include":[]}')
        self.assertEqual(emitted["count"], "0")
        self.assertEqual(emitted["reason"], "capacity_unavailable")

    def test_main_plan_workers_reports_when_all_claims_were_lost(self):
        include = [{"id": "CW-001", "kind": "story", "slot": 1, "title": "",
                    "body": "", "fix": ""}]
        with mock.patch.object(swarm, "gh_json", side_effect=[[], []]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", return_value=[]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value="text"), \
                mock.patch.object(swarm, "reclaim", return_value="text"), \
                mock.patch.object(swarm, "plan_workers", return_value=include), \
                mock.patch.object(swarm, "claim_open", return_value=("taken", set())), \
                mock.patch.object(swarm, "update_todo"), mock.patch.object(swarm, "note_claims"), \
                mock.patch("builtins.print"):
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        self.assertEqual(self.emitted()["reason"], "claims_lost")

    def test_main_pr_flow_reports_a_gh_failure_and_returns(self):
        # Keep subprocess real so the except clause catches a genuine error.
        error = subprocess.CalledProcessError(7, "gh")
        with mock.patch.object(swarm, "pr_flow", side_effect=error):
            self.assertEqual(swarm.main(["pr-flow"]), 0)
        self.assertIn("pr-flow stopped early: gh failed (7)", self.stderr.getvalue())

    def test_main_watchdog_routes_to_watchdog(self):
        with mock.patch.object(swarm, "watchdog") as watchdog:
            self.assertEqual(swarm.main(["watchdog"]), 0)
        watchdog.assert_called_once_with()

    def test_main_run_event_prints_the_result(self):
        with mock.patch.object(swarm, "run_event", return_value="event happened") as run_event:
            self.assertEqual(swarm.main(["run-event", "--run", "9"]), 0)
        run_event.assert_called_once_with("9")
        self.assertIn("event happened", self.stdout.getvalue())

    def test_main_issue_context_prints_the_count(self):
        out = self.dir / "context"
        with mock.patch.object(swarm, "issue_context", return_value=2) as issue_context:
            self.assertEqual(swarm.main(["issue-context", "--task", "CW-1", "--out", str(out)]), 0)
        issue_context.assert_called_once_with("CW-1", str(out))
        self.assertIn("2 issue(s) written to", self.stdout.getvalue())

    def test_main_task_field_prints_the_field(self):
        with mock.patch.object(swarm, "task_field", return_value="A title") as task_field:
            self.assertEqual(swarm.main(["task-field", "--id", "CW-1", "--field", "title"]), 0)
        task_field.assert_called_once_with("CW-1", "title", "")
        self.assertIn("A title", self.stdout.getvalue())

    def run_continue(self, go, waiting, live):
        """Dispatch `continue` with controlled readiness, worker counts and gh calls."""
        state = {"text": "- [ ] T-1\n"}

        def persist(edit, message, path="TODO.md", attempts=8):
            state["text"] = edit(state["text"])
            return True

        with mock.patch.object(swarm, "gh_json", side_effect=[[], []]), \
                mock.patch.object(swarm, "waiting_waves", return_value=0), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "todo_read", side_effect=lambda path="TODO.md": state["text"]), \
                mock.patch.object(swarm, "update_todo", side_effect=persist) as update, \
                mock.patch.object(swarm, "reclaim", side_effect=lambda text, prs, live: text), \
                mock.patch.object(swarm, "live_workers", return_value=[f"task-{n}" for n in range(live)]), \
                mock.patch.object(swarm, "continue_swarm", return_value=go), \
                mock.patch.object(swarm, "waiting_waves", return_value=waiting), \
                mock.patch.object(swarm, "gh") as gh:
            self.assertEqual(swarm.main(["continue", "--file", str(self.dir / "TODO.md")]), 0)
        self.assertEqual(update.call_args.args[1], "Reclaim stale swarm claims")
        return gh

    def test_main_continue_does_not_queue_a_waiting_wave(self):
        gh = self.run_continue(go=True, waiting=1, live=0)
        gh.assert_not_called()
        self.assertIn("No free workers or a wave is already waiting.", self.stdout.getvalue())
        self.assertIn("No further wave to start now.", self.stdout.getvalue())

    def test_main_continue_does_not_queue_when_all_worker_slots_are_occupied(self):
        gh = self.run_continue(go=True, waiting=0, live=swarm.MAX_ACTIVE_WORKERS)
        gh.assert_not_called()
        self.assertIn("No free workers or a wave is already waiting.", self.stdout.getvalue())

    def test_main_continue_starts_a_wave_when_ready(self):
        gh = self.run_continue(go=True, waiting=0, live=0)
        gh.assert_called_once_with("workflow", "run", "swarm.yml", "--ref", "main", check=False)
        self.assertIn("Starting the next wave.", self.stdout.getvalue())

    def test_main_release_routes_to_release(self):
        with mock.patch.object(swarm, "release") as release:
            self.assertEqual(swarm.main(["release", "--task", '{"id": "A"}']), 0)
        release.assert_called_once_with({"id": "A"}, "failed", 0)

    def test_main_publish_routes_to_publish(self):
        with mock.patch.object(swarm, "publish") as publish:
            self.assertEqual(
                swarm.main(["publish", "--task", '{"id": "A"}', "--dir", str(self.dir)]), 0)
        publish.assert_called_once_with({"id": "A"}, str(self.dir), "")

    def test_main_monitor_select_skips_an_off_season_daily_cron(self):
        # An unrecognised SCHEDULE can never equal the New York UTC offset, so the
        # off-season guard stops this daily run without calling the selector.
        with mock.patch.dict(os.environ, {"EVENT_NAME": "schedule", "SCHEDULE": "not-a-cron"}), \
                mock.patch.object(swarm, "monitor_select") as select:
            # This guard uses a bare `return`, so the early exit has no status value.
            self.assertIsNone(swarm.main(["monitor-select", "--mode", "daily",
                                          "--out", str(self.dir)]))
        select.assert_not_called()
        self.assertEqual(self.emitted().get("act"), "false")

    def selection_file(self):
        selection = {"prs": [[7, "swarm/story"]], "nudge": [], "exhausted": [],
                     "triage": [], "reset": [], "close": []}
        path = self.dir / "selection.json"
        path.write_text(json.dumps(selection))
        return path

    def test_main_monitor_assign_prints_the_result(self):
        with mock.patch.object(swarm, "monitor_assign", return_value={"assigned": 1}) as assign:
            self.assertEqual(swarm.main(["monitor-assign", "--selection",
                                         str(self.selection_file())]), 0)
        assign.assert_called_once()
        self.assertEqual(assign.call_args.args[1], [(7, "swarm/story")])
        self.assertIn('"assigned": 1', self.stdout.getvalue())

    def test_main_monitor_assign_propagates_a_selector_error(self):
        # The failure must come from the multi-line print call at line 3919, not
        # from reading the selection file on the line before it.
        with mock.patch.object(swarm, "monitor_assign", side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                swarm.main(["monitor-assign", "--selection", str(self.selection_file())])

    def test_main_notes_collect_emits_days_and_act(self):
        with mock.patch.object(swarm, "notes_collect",
                               return_value=["2026-11-19", "2026-11-20"]) as collect:
            self.assertEqual(swarm.main(["notes-collect", "--out", str(self.dir)]), 0)
        collect.assert_called_once_with(str(self.dir))
        emitted = self.emitted()
        self.assertEqual(emitted.get("days"), "2026-11-19,2026-11-20")
        self.assertEqual(emitted.get("act"), "true")

    def test_main_notes_publish_completes_without_a_fatal_error(self):
        # No *.files in the collected directory: the reconciler has nothing to
        # complain about, so main falls through to its final return instead of exiting.
        collected = self.dir / "collected"
        collected.mkdir()
        with mock.patch.object(swarm, "notes_publish", return_value={"days": []}) as publish:
            self.assertEqual(swarm.main(["notes-publish", "--collected", str(collected),
                                         "--reconciled", str(self.dir / "reconciled")]), 0)
        publish.assert_called_once()
        self.assertIn('"days": []', self.stdout.getvalue())

    def test_main_add_audit_tasks_routes_to_add_audit_tasks(self):
        with mock.patch.object(swarm, "add_audit_tasks") as add:
            self.assertEqual(swarm.main(["add-audit-tasks", "--findings", "f.json"]), 0)
        add.assert_called_once_with("f.json", "")

    def test_main_add_repair_task_exits_without_a_run(self):
        with self.assertRaises(SystemExit) as stop:
            swarm.main(["add-repair-task"])
        self.assertIn("needs --run <id> or --latest", str(stop.exception))

    def test_main_add_repair_task_with_a_run_emits_added(self):
        todo = self.dir / "TODO.md"
        with mock.patch.object(swarm, "add_repair_task", return_value=True) as add:
            self.assertEqual(swarm.main(["add-repair-task", "--run", "9", "--file", str(todo)]), 0)
        add.assert_called_once_with("9", str(todo))
        self.assertEqual(self.emitted().get("added"), "true")

    def test_main_add_repair_task_latest_emits_added(self):
        todo = self.dir / "TODO.md"
        with mock.patch.object(swarm, "add_latest_repair_task", return_value=False) as add:
            self.assertEqual(swarm.main(["add-repair-task", "--latest", "--file", str(todo)]), 0)
        add.assert_called_once_with(str(todo))
        self.assertEqual(self.emitted().get("added"), "false")

    def test_main_repair_gate_prints_and_emits_the_verdict(self):
        with mock.patch.object(swarm, "repair_gate", return_value=(True, "start it")) as gate:
            self.assertEqual(swarm.main(["repair-gate", "--run", "9"]), 0)
        gate.assert_called_once_with("9")
        self.assertIn("start it", self.stdout.getvalue())
        self.assertEqual(self.emitted().get("start"), "true")

    def test_main_plan_integration_emits_the_queue(self):
        with mock.patch.object(swarm, "main_is_red", return_value=True), \
                mock.patch.object(swarm, "plan_integration", return_value=[7, 8]) as plan:
            self.assertEqual(swarm.main(["plan-integration"]), 0)
        plan.assert_called_once_with(True, None)
        emitted = self.emitted()
        self.assertEqual(emitted.get("queue"), "[7, 8]")
        self.assertEqual(emitted.get("count"), "2")

    def test_main_plan_integration_caps_the_batch_for_the_job_timeout(self):
        queue = list(range(1, 10))
        with mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "plan_integration", return_value=queue), \
                mock.patch.dict(os.environ, {"SWARM_INTEGRATE_BATCH": "3"}):
            self.assertEqual(swarm.main(["plan-integration"]), 0)
        emitted = self.emitted()
        self.assertEqual(emitted.get("queue"), "[1, 2, 3]")
        self.assertEqual(emitted.get("count"), "3")

    def test_recovery_pr_ids_accept_only_positive_integer_ids(self):
        self.assertIsNone(swarm.recovery_pr_ids(""))
        self.assertEqual(swarm.recovery_pr_ids("7, 9"), [7, 9])
        for bad in ("7;DROP", "-1", "abc", "7,"):
            with self.subTest(bad=bad), self.assertRaises(SystemExit):
                swarm.recovery_pr_ids(bad)

    def test_main_passes_the_allow_listed_retry_prs_to_the_planner(self):
        with mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "plan_integration", return_value=[7]) as plan, \
                mock.patch.dict(os.environ, {"SWARM_RECOVERY_PRS": "7"}):
            self.assertEqual(swarm.main(["plan-integration"]), 0)
        plan.assert_called_once_with(False, [7])

    def test_plan_integration_restricts_a_retry_to_allow_listed_prs(self):
        prs = [
            {"number": 7, "title": "A: one", "labels": [],
             "isCrossRepository": False, "isDraft": False, "headRefOid": "a" * 40},
            {"number": 8, "title": "B: two", "labels": [],
             "isCrossRepository": False, "isDraft": False, "headRefOid": "b" * 40},
        ]
        with mock.patch.object(swarm, "gh_json", return_value=prs), \
                mock.patch.object(swarm, "todo_read", return_value=""):
            queue = swarm.plan_integration(False, recovery_prs=[8])
        self.assertEqual(queue, [8])

    def test_main_caps_the_recovery_attempt(self):
        with mock.patch.object(swarm, "integrate_publish") as publish:
            with self.assertRaises(SystemExit):
                swarm.main([
                    "integrate-publish", "--manifest", "m", "--bundle", "b",
                    "--recovery-attempt", "2",
                ])
        publish.assert_not_called()
        self.assertEqual(self.emitted().get("publication_state"), "failed")

    def test_integration_exit_code_rejects_non_integer_status(self):
        with self.assertRaises(TypeError):
            swarm.integration_exit_code(object())

    def test_main_integrate_batch_maps_status_without_exiting_on_merge_count(self):
        for result, expected in ((-1, 1), (0, 0), (1, 0), (2, 0)):
            with self.subTest(result=result), \
                    mock.patch.object(swarm, "integrate_batch", return_value=result) as integrate:
                self.assertEqual(swarm.main(["integrate-batch", "--queue", "[7]",
                                             "--gate", "gate.sh"]), expected)
            integrate.assert_called_once_with([7], "gate.sh")

    def test_main_integrate_publish_maps_status_without_exiting_on_merge_count(self):
        # A bounded base-advance recovery is expected and must not fail the step;
        # terminal and unknown negative outcomes stay red.
        for result, expected in ((-3, 1), (-1, 1), (-2, 0), (0, 0), (1, 0), (2, 0)):
            with self.subTest(result=result), \
                    mock.patch.object(swarm, "integrate_publish", return_value=result) as publish:
                self.assertEqual(swarm.main(["integrate-publish", "--manifest", "m.json",
                                             "--bundle", "b.bundle", "--gate", "gate.sh"]), expected)
            publish.assert_called_once_with(
                "m.json", "b.bundle", "gate.sh", attempt=0)

    def test_publication_state_helper_covers_every_outcome(self):
        """The output is a closed enum; an unexpected negative fails closed."""
        for result, state in ((-3, "failed"), (-1, "failed"), (-2, "deferred"),
                              (0, "noop"), (1, "published"), (2, "published")):
            with self.subTest(result=result):
                self.assertEqual(swarm.publication_state(result), state)

    def test_publication_exit_code_defers_only_the_bounded_retry(self):
        for result, expected in ((-3, 1), (-1, 1), (-2, 0), (0, 0), (2, 0)):
            with self.subTest(result=result):
                self.assertEqual(swarm.publication_exit_code(result), expected)

    def test_main_integrate_publish_emits_an_explicit_state_for_every_outcome(self):
        """CI gates on the state, not on the job status, so every path emits one."""
        publish_args = ["integrate-publish", "--manifest", "m.json",
                        "--bundle", "b.bundle", "--gate", "gate.sh"]
        for result, state in ((0, "noop"), (2, "published"),
                              (swarm.INTEGRATION_RETRY, "deferred"),
                              (swarm.INTEGRATION_FAILED, "failed"), (-3, "failed")):
            with self.subTest(result=result):
                self.output.write_text("")
                with mock.patch.object(swarm, "integrate_publish", return_value=result):
                    self.assertEqual(swarm.main(publish_args),
                                     swarm.publication_exit_code(result))
                self.assertEqual(self.emitted().get("publication_state"), state)
                written = [line for line in self.output.read_text().splitlines()
                           if line.startswith("publication_state=")]
                self.assertEqual(len(written), 1)

    def test_main_integrate_publish_retry_keeps_the_allow_listed_signal(self):
        """The state output is descriptive; the validated JSON alone authorises a retry."""
        payload = json.dumps({"id": "base_advanced", "new_base": "a" * 40,
                              "prs": [7], "attempt": 0}, separators=(",", ":"))
        self.output.write_text(f"integration_retry={payload}\nrecovery_attempt=0\n")
        with mock.patch.object(swarm, "integrate_publish",
                               return_value=swarm.INTEGRATION_RETRY):
            self.assertEqual(swarm.main(["integrate-publish", "--manifest", "m.json",
                                         "--bundle", "b.bundle", "--gate", "gate.sh"]), 0)
        emitted = self.emitted()
        self.assertEqual(emitted["integration_retry"], payload)
        self.assertEqual(emitted["recovery_attempt"], "0")
        self.assertEqual(emitted["publication_state"], "deferred")

    def test_main_integrate_publish_refusal_emits_failed_before_exiting(self):
        """A manifest/tip refusal never reaches a result, but must still fail closed."""
        with mock.patch.object(swarm, "integrate_publish",
                               side_effect=SystemExit("bundle head moved")):
            with self.assertRaises(SystemExit):
                swarm.main(["integrate-publish", "--manifest", "m.json",
                            "--bundle", "b.bundle", "--gate", "gate.sh"])
        self.assertEqual(self.emitted().get("publication_state"), "failed")

    def test_main_integrate_publish_unexpected_error_emits_failed(self):
        """An unexpected publication error keeps downstream jobs from assuming success."""
        with mock.patch.object(swarm, "integrate_publish",
                               side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                swarm.main(["integrate-publish", "--manifest", "m.json",
                            "--bundle", "b.bundle", "--gate", "gate.sh"])
        self.assertEqual(self.emitted().get("publication_state"), "failed")

    def test_main_integrate_publish_rejection_emits_zero_and_skips_dispatch(self):
        manifest = self.dir / "manifest.json"
        bundle = self.dir / "main.bundle"
        manifest.write_text(json.dumps({"tip": "tip", "accepted": [
            {"pr": 7, "task_id": "CW-001", "head_sha": "abc", "merge_sha": "merge"}],
            "gates": {"7": ["gate.sh", {}]}, "rejected": [], "already": [],
            "conflict_details": []}))
        bundle.write_text("synthetic")
        error = subprocess.CalledProcessError(1, ("push",), stderr="GH013 protected branch")
        original = Path.cwd()
        os.chdir(self.dir)
        self.addCleanup(os.chdir, original)
        with mock.patch.object(swarm, "git", return_value="tip"), \
                mock.patch.object(swarm, "gh_json", return_value={"headRefOid": "abc"}), \
                mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "report_validation"), \
                mock.patch.object(swarm, "record_merges",
                                  return_value=([(7, "CW-001", "merge")], ["comment"], [])), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write"), \
                mock.patch.object(swarm, "authed_git", side_effect=error), \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch, \
                mock.patch.object(swarm, "report_pr_stuck"):
            self.assertEqual(swarm.main(["integrate-publish", "--manifest", str(manifest),
                                         "--bundle", str(bundle), "--gate", "gate.sh"]), 1)
        self.assertEqual(self.emitted().get("merged"), "0")
        self.assertEqual(self.emitted().get("publication_state"), "failed")
        dispatch.assert_not_called()

    def test_main_resolve_publish_routes_to_resolve_publish(self):
        with mock.patch.object(swarm, "resolve_publish") as resolve:
            self.assertEqual(swarm.main(["resolve-publish", "--dir", str(self.dir)]), 0)
        resolve.assert_called_once_with(str(self.dir), dispatch=True)

    def test_main_resolve_publish_can_disable_the_unbounded_dispatch(self):
        with mock.patch.object(swarm, "resolve_publish") as resolve:
            self.assertEqual(
                swarm.main(["resolve-publish", "--dir", str(self.dir), "--no-dispatch"]), 0)
        resolve.assert_called_once_with(str(self.dir), dispatch=False)

    def test_main_guard_blocks_when_needed(self):
        with mock.patch.object(swarm, "guard", return_value=True) as guard:
            self.assertEqual(swarm.main(["guard", "--pr", "7"]), 0)
        guard.assert_called_once_with(7, False)
        self.assertEqual(self.emitted().get("blocked"), "true")

    def test_main_guard_allows_when_clear(self):
        with mock.patch.object(swarm, "guard", return_value=False) as guard:
            self.assertEqual(swarm.main(["guard", "--pr", "7"]), 0)
        guard.assert_called_once_with(7, False)
        self.assertEqual(self.emitted().get("blocked"), "false")

    def test_main_merge_local_reports_a_conflict(self):
        with mock.patch.object(swarm, "merge_local", return_value=(None, "", ["docs/a.md"])), \
                mock.patch.object(swarm, "fail") as fail:
            self.assertEqual(swarm.main(["merge-local", "--pr", "7"]), 0)
        fail.assert_called_once()
        self.assertIn("docs/a.md", fail.call_args.args[1])
        emitted = self.emitted()
        self.assertEqual(emitted.get("conflict"), "true")
        self.assertEqual(emitted.get("sha"), "")
        self.assertEqual(emitted.get("task"), "")

    def test_main_merge_local_names_unknown_conflict_files(self):
        # An empty conflict list must still produce a useful reason for the worker.
        with mock.patch.object(swarm, "merge_local", return_value=(None, "", [])), \
                mock.patch.object(swarm, "fail") as fail:
            self.assertEqual(swarm.main(["merge-local", "--pr", "7"]), 0)
        self.assertIn("unknown files", fail.call_args.args[1])
        self.assertEqual(self.emitted().get("conflict"), "true")

    def test_main_merge_local_reports_the_sha(self):
        with mock.patch.object(swarm, "merge_local", return_value=("abc123", "CW-1", [])), \
                mock.patch.object(swarm, "fail") as fail:
            self.assertEqual(swarm.main(["merge-local", "--pr", "7"]), 0)
        fail.assert_not_called()
        emitted = self.emitted()
        self.assertEqual(emitted.get("conflict"), "false")
        self.assertEqual(emitted.get("sha"), "abc123")
        self.assertEqual(emitted.get("task"), "CW-1")

    def test_main_finalize_routes_to_finalize(self):
        with mock.patch.object(swarm, "finalize") as finalize:
            self.assertEqual(swarm.main(["finalize", "--pr", "7", "--task-id", "CW-1",
                                         "--sha", "abc123"]), 0)
        finalize.assert_called_once_with(7, "CW-1", "abc123")

    def test_main_fail_reads_the_log_and_routes(self):
        log = self.dir / "log"
        log.write_text("the log line")
        with mock.patch.object(swarm, "fail") as fail:
            self.assertEqual(swarm.main(["fail", "--pr", "7", "--reason", "broken",
                                         "--log", str(log)]), 0)
        fail.assert_called_once_with(7, "broken", "the log line")

    def test_main_returns_zero_for_an_unrecognised_command(self):
        # argparse normally constrains the command, but a defensive fall-through
        # must still return cleanly if an unexpected command reaches dispatch.
        unknown = swarm.argparse.Namespace(cmd="unrecognised")
        with mock.patch.object(swarm.argparse.ArgumentParser, "parse_args", return_value=unknown):
            self.assertEqual(swarm.main([]), 0)

    def test_module_main_guard_exits_with_zero(self):
        # runpy re-imports the file, so use a pure command on a temp slots file:
        # no gh, no repository and no network is reached.
        slots = self.dir / "slots.json"
        slots.write_text(json.dumps({"slots": SLOTS}))
        with mock.patch.object(sys, "argv", ["swarm.py", "slot-config", "--slot", "1",
                                            "--slots", str(slots)]):
            with self.assertRaises(SystemExit) as stop:
                runpy.run_path(swarm.__file__, run_name="__main__")
        self.assertEqual(stop.exception.code, 0)
        printed = json.loads(self.stdout.getvalue())
        self.assertNotIn("model", printed)
        self.assertIs(printed["autoupdate"], False)


class StaleRetryTests(unittest.TestCase):
    def test_stale_retry_releases_the_claim_and_resets_the_attempt(self):
        text = ("- [-] TASK-01 · work it\n  - agent: swarm-2\n  - attempts: 2\n"
                "  - details:\n    stay inside scope\n")
        with mock.patch.object(swarm, "update_todo",
                               side_effect=lambda edit, message: edit(text)) as update:
            result = swarm.stale_retry("TASK-01")
        self.assertIn("- [ ] TASK-01 · work it", result)
        self.assertNotIn("  - agent:", result)
        self.assertIn("  - attempts: 0", result)
        self.assertIn("stay inside scope", result)
        self.assertIn("Retry TASK-01", update.call_args.args[1])

    def test_stale_retry_resets_attempts_after_details(self):
        text = ("- [-] TASK-01 · work it\n  - details:\n    stay inside scope\n"
                "  - attempts: 4\n")
        with mock.patch.object(swarm, "update_todo",
                               side_effect=lambda edit, message: edit(text)):
            result = swarm.stale_retry("TASK-01")
        self.assertIn("- [ ] TASK-01 · work it", result)
        self.assertIn("  - attempts: 0", result)
        self.assertIn("stay inside scope", result)

    def test_reset_attempts_resolves_aliases_and_metadata_after_details(self):
        text = ("- [-] TASK-01 · work it\n  - aliases: TASK-ALIAS\n"
                "  - details:\n    stay inside scope\n  - attempts: 4\n")
        result = swarm.reset_attempts(text, "TASK-ALIAS")
        self.assertIn("  - attempts: 0", result)
        self.assertIn("stay inside scope", result)

    def test_stale_retry_never_reopens_or_mutates_other_states(self):
        cases = (
            "- [ ] TASK-01 · Open\n",
            "- [x] TASK-01 · Done\n",
            "- [!] TASK-01 · Incomplete\n  - details:\n    Held.\n  - attempts: 3\n",
            "- [ ] OTHER-1 · Missing target\n",
        )
        for text in cases:
            with self.subTest(text=text), \
                    mock.patch.object(swarm, "todo_read", return_value=text), \
                    mock.patch.object(swarm, "todo_write") as write, \
                    mock.patch.object(swarm, "git") as git, \
                    mock.patch.object(swarm, "authed_git") as authed:
                self.assertFalse(swarm.stale_retry("TASK-01"))
                write.assert_not_called()
                git.assert_not_called()
                authed.assert_not_called()

    def test_main_dispatches_stale_retry(self):
        with mock.patch.object(swarm, "stale_retry") as retry:
            swarm.main(["stale-retry", "--task", "TASK-09"])
        retry.assert_called_once_with("TASK-09")


if __name__ == "__main__":
    unittest.main()
