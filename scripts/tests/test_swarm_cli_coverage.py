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

# Small synthetic slot table, matching the shape of .github/swarm/slots.json.
SLOTS = [{"slot": n, "model": f"m{n}"} for n in range(1, 4)]


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
        listing = [{"headSha": "a", "conclusion": "success"}]
        with mock.patch.object(swarm, "gh_json", return_value=listing) as gh_json:
            self.assertEqual(swarm.delivery_listing(), listing)
        gh_json.assert_called_once_with(
            "run", "list", "--workflow", "Delivery", "--limit", str(swarm.DELIVERY_LISTING_LIMIT),
            "--json", "databaseId,headSha,status,conclusion")

    def test_a_non_list_answer_becomes_an_empty_list(self):
        # gh can answer an error envelope instead of an array; a whole pr-flow pass
        # must still have an empty listing it can iterate safely.
        with mock.patch.object(swarm, "gh_json", return_value={"message": "rate limited"}):
            self.assertEqual(swarm.delivery_listing(), [])


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

    def test_main_reconcile_tasks_reports_a_blocked_push_without_crashing(self):
        with mock.patch.object(swarm, "reconcile_tasks",
                               side_effect=swarm.TrackerPushBlocked()), \
                contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(swarm.main(["reconcile-tasks"]), 0)
        self.assertIn("repository rules", err.getvalue())

    def test_main_archive_todo_prints_the_archived_count(self):
        with mock.patch.object(swarm, "archive_todo", return_value=2):
            self.assertEqual(swarm.main(["archive-todo"]), 0)
        self.assertIn(f"archived 2 finished task(s) to {swarm.ARCHIVE}", self.stdout.getvalue())

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
        self.assertEqual(printed["model"], "m2")
        self.assertIs(printed["autoupdate"], False)

    def test_main_plan_workers_reclaims_and_claims_on_every_push_retry(self):
        # The nested `mark` callback is passed to update_todo; a fake that invokes it
        # proves the newest TODO.md is re-claimed on every push retry.
        prs = [{"number": 7, "headRefName": "swarm/story"}]

        def call_edit(edit, message, path):
            self.assertEqual(message, "Take 1 task(s) for the swarm")
            edit("current TODO text")
            return True

        include = [{"id": "CW-001", "kind": "story", "slot": 1, "title": "secret",
                    "body": "secret", "fix": "secret"},
                   {"id": "FIX-9", "kind": "fix", "slot": 2, "title": "secret",
                    "body": "secret", "fix": "secret"}]
        with mock.patch.object(swarm, "gh_json", side_effect=[prs, []]), \
                mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "live_workers", return_value=[]), \
                mock.patch.object(swarm, "load_slots", return_value=SLOTS), \
                mock.patch.object(swarm, "todo_read", return_value="current TODO text"), \
                mock.patch.object(swarm, "reclaim", return_value="reclaimed") as reclaim, \
                mock.patch.object(swarm, "claim_open", return_value=("taken", {"CW-001"})) as claim, \
                mock.patch.object(swarm, "plan_workers", return_value=include), \
                mock.patch.object(swarm, "update_todo", side_effect=call_edit) as update, \
                mock.patch.object(swarm, "note_claims") as note:
            self.assertEqual(swarm.main(["plan-workers", "--file", str(self.dir / "TODO.md")]), 0)
        update.assert_called_once()
        self.assertEqual(reclaim.call_count, 2)  # once when planning, once in `mark`
        claim.assert_called_once_with("reclaimed", {"CW-001": "swarm-1"})
        note.assert_called_once()
        emitted = self.emitted()
        self.assertEqual([t["id"] for t in json.loads(emitted["matrix"])["include"]],
                         ["CW-001", "FIX-9"])
        self.assertTrue(all(not t[field] for t in json.loads(emitted["matrix"])["include"]
                            for field in ("title", "body", "fix")))
        self.assertEqual(emitted["count"], "2")

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
        task_field.assert_called_once_with("CW-1", "title", "TODO.md")
        self.assertIn("A title", self.stdout.getvalue())

    def run_continue(self, go, waiting, live):
        """Dispatch `continue` with controlled readiness, worker counts and gh calls."""
        state = {"text": "- [ ] T-1\n"}

        def persist(edit, message, path="TODO.md", attempts=8):
            state["text"] = edit(state["text"])
            return True

        with mock.patch.object(swarm, "gh_json", side_effect=[[], []]), \
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
        publish.assert_called_once_with({"id": "A"}, str(self.dir))

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
        add.assert_called_once_with("f.json", "TODO.md")

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
        plan.assert_called_once_with(True)
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

    def test_main_resolve_publish_routes_to_resolve_publish(self):
        with mock.patch.object(swarm, "resolve_publish") as resolve:
            self.assertEqual(swarm.main(["resolve-publish", "--dir", str(self.dir)]), 0)
        resolve.assert_called_once_with(str(self.dir))

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
        self.assertIn('"model":"m1"', self.stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
