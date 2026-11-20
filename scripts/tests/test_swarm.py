import datetime
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stderr
from unittest import mock

from helpers import ROOT, load

swarm = load("swarm")
SLOTS = [{"slot": n, "model": f"m{n}"} for n in range(1, 4)]


def labels(*names):
    return [{"name": n} for n in names]


class FakeGh:
    """Record gh calls and answer the listing queries from canned data."""

    def __init__(self, prs=None, issues=None, pr_view=None, diff=""):
        self.calls, self.prs, self.issues = [], prs or [], issues or []
        self.pr_view, self.diff = pr_view or {}, diff

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("pr", "list"):
            return json.dumps(self.prs)
        if args[:2] == ("issue", "list"):
            return json.dumps(self.issues)
        if args[:2] == ("pr", "diff"):
            return self.diff
        if args[:2] == ("pr", "view"):
            if "--jq" in args:
                return self.pr_view.get(args[args.index("--jq") + 1], "7")
            return json.dumps(self.pr_view)
        return ""

    def said(self, *needle):
        return any(all(n in call for n in needle) for call in self.calls)


TASKS = """# TODO

## Tasks

- [ ] CW-001 · Staff
  - requirements: IAM-02
  - scope: apps/api/staff, db/migrations
  - reserved: V4
  - details:
    Details for staff.
- [ ] CW-002 · Config
  - requirements: ARC-02
  - scope: apps/api/config
  - reserved: V5
  - details:
    Details for config.
- [ ] CW-003 · Overlap with staff
  - scope: apps/api/staff/deep
- [ ] CW-004 · Needs staff
  - depends: CW-001
  - scope: apps/api/other
- [x] CW-005 · Finished
  - scope: apps/api/old
  - pr: #3
- [ ] CW-006 · After finished
  - depends: CW-005
  - scope: apps/api/after
- [-] CW-007 · Taken by a loop agent
  - scope: apps/api/parked
  - agent: loop
- [ ] S-G9-01 · A story with no scope is for the loop, not the swarm
  - requirements: ARC-02
"""

ALIASED = """# TODO

## One section

- [x] S-G9-02 · Alias holder
  - aliases: CW-010

## Another section

- [ ] CW-011 · Needs the alias
  - depends: CW-010
  - scope: apps/api/alias
"""


def pr(number, branch, *names, cross=False, comments=()):
    return {"number": number, "headRefName": branch, "isCrossRepository": cross,
            "labels": labels(*names), "comments": [{"body": c} for c in comments]}


class ConcurrentWaveTests(unittest.TestCase):
    def test_combined_worker_cap_is_twenty(self):
        self.assertEqual(swarm.MAX_ACTIVE_WORKERS, 20)

    def test_slots_rotate_past_the_ones_in_use(self):
        slots = [{"slot": n} for n in range(1, 11)]
        self.assertEqual([s["slot"] for s in swarm.rotate_slots(slots, 0)][:2], [1, 2])
        self.assertEqual([s["slot"] for s in swarm.rotate_slots(slots, 10)][:2], [1, 2])
        self.assertEqual([s["slot"] for s in swarm.rotate_slots(slots, 4)][:2], [5, 6])
        self.assertEqual(swarm.rotate_slots([], 3), [])

    def test_active_workers_counts_only_worker_jobs_of_unfinished_runs(self):
        def fake(*args, check=True):
            if args[0] == "run":
                return "11\n12\n"
            return ("Plan the wave\n"
                    "Swarm worker (story, DLV-02, 0, Repair integration tests: patient... / Work the task\n"
                    "Swarm worker (story, DLV-02, 0, Repair integration tests: patient... / Publish result\n"
                    "Swarm worker (fix, CW-008, 156, swarm/CW-008-122, Staff web... / Work the task\n"
                    "Swarm integrator / Pick the queue\n")
        with mock.patch.object(swarm, "gh", fake), mock.patch.object(swarm, "repo_slug", lambda: "o/r"):
            # Two runs, two workers each; a worker's Work and Publish jobs count once.
            self.assertEqual(swarm.active_workers(), 4)
            self.assertEqual(swarm.live_workers(), ["CW-008", "DLV-02", "CW-008", "DLV-02"])

    def test_worker_job_names_carry_the_task_id(self):
        name = "Swarm worker (story, FIX-IMAGES-01, 0, Prepared-image readiness must... / Work the task"
        self.assertTrue(swarm.is_worker_job(name))
        self.assertEqual(swarm.worker_task_id(name), "FIX-IMAGES-01")
        self.assertEqual(swarm.worker_task_id("Swarm worker (fix, CW-008, 156, b, T / Publish"), "CW-008")
        self.assertEqual(swarm.worker_task_id("Swarm worker / Work the task"), "")
        self.assertFalse(swarm.is_worker_job("Swarm integrator / Pick the queue"))
        self.assertFalse(swarm.is_worker_job("Plan the wave"))


class WorkerPostgresTests(unittest.TestCase):
    """Workers must be able to run the PostgreSQL integration suites themselves."""

    def test_agent_image_carries_postgresql_17_and_a_starter(self):
        dockerfile = (ROOT / ".github/agent-image/Dockerfile").read_text()
        self.assertIn("postgresql-17", dockerfile)
        self.assertIn("apt.postgresql.org", dockerfile)
        self.assertIn("COPY start-postgres.sh /usr/local/bin/start-postgres", dockerfile)
        self.assertIn("SMARTCLINIC_PG_ADMIN=postgres", dockerfile)
        starter = (ROOT / ".github/agent-image/start-postgres.sh").read_text()
        self.assertIn("127.0.0.1", starter)
        self.assertIn("-A trust", starter)
        self.assertIn(".github/agent-image/**", (ROOT / ".github/workflows/agent-image.yml").read_text())

    def test_worker_starts_postgresql_and_runs_the_worker_check(self):
        text = (ROOT / ".github/workflows/swarm-worker.yml").read_text()
        self.assertIn("CHECK_SCRIPT: scripts/worker-check.sh", text)
        self.assertIn("SMARTCLINIC_PG_ADMIN: postgres", text)
        self.assertLess(text.index("if command -v start-postgres"), text.index("id: s1"))

    def test_worker_check_runs_integration_tests_and_database_policy(self):
        script = (ROOT / "scripts/worker-check.sh").read_text()
        self.assertIn("bash scripts/quick-check.sh", script)
        self.assertIn("-Dgroups=integration", script)
        self.assertIn("bash db/check.sh", script)
        self.assertNotIn("NVD", script)


class WorkerGateTests(unittest.TestCase):
    def setUp(self):
        import sys
        sys.path.insert(0, str(ROOT / "scripts"))
        import worker_gate
        self.gate = worker_gate

    def suite(self, tmp, name, failures=0, errors=0):
        (Path(tmp) / f"TEST-{name}.xml").write_text(
            f'<testsuite name="{name}" tests="3" failures="{failures}" errors="{errors}"/>')

    def test_failing_classes_come_from_surefire_xml(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.suite(tmp, "com.x.GoodTest")
            self.suite(tmp, "com.x.RxTransmissionFlowTest", failures=2)
            self.suite(tmp, "com.x.ChartRecordIntegrationTest", errors=1)
            self.assertEqual(self.gate.failing_classes(tmp),
                             {"RxTransmissionFlowTest", "ChartRecordIntegrationTest"})

    def test_ordinary_task_ignores_red_suites_it_did_not_touch(self):
        ok, msg = self.gate.decide({"RxTransmissionFlowTest"},
                                   ["apps/api/src/main/java/a/ChartService.java"], "S-G1-03")
        self.assertTrue(ok)
        self.assertIn("WARNING", msg)

    def test_ordinary_task_fails_on_a_related_suite(self):
        for changed in (["apps/api/src/test/java/a/RxTransmissionFlowTest.java"],
                        ["apps/api/src/main/java/a/RxTransmission.java"]):
            with self.subTest(changed=changed):
                ok, _ = self.gate.decide({"RxTransmissionFlowTest"}, changed, "S-G2-01")
                self.assertFalse(ok)

    def test_repair_task_without_named_classes_is_strict(self):
        for story in ("FIX-123", "DLV-02"):
            ok, _ = self.gate.decide({"RxTransmissionFlowTest"}, [], story, "Repair the failing build.")
            self.assertFalse(ok)

    def test_repair_task_is_judged_on_the_classes_it_names(self):
        body = "Classes: `ChartRecordIntegrationTest` and `ExchangeTaskIsolationTest` must pass."
        # Other repair tasks' classes are still red: this one is not blamed for them.
        ok, msg = self.gate.decide({"RxTransmissionFlowTest", "ConversationFlowTest"}, [], "DLV-01", body)
        self.assertTrue(ok)
        self.assertIn("other repair tasks", msg)
        # Its own class still failing fails it, whatever else is red.
        ok, msg = self.gate.decide({"ChartRecordIntegrationTest", "RxTransmissionFlowTest"}, [], "DLV-01", body)
        self.assertFalse(ok)
        self.assertIn("ChartRecordIntegrationTest", msg)
        self.assertNotIn("RxTransmissionFlowTest", msg)

    def test_named_classes_are_read_from_backticks_only(self):
        self.assertEqual(self.gate.named_classes("`FooTest`, BarTest and `lowerTest` `BazIT` `Qux`"),
                         {"FooTest", "BazIT"})

    def test_passing_run_is_ok(self):
        self.assertTrue(self.gate.decide(set(), [], "S-G1-03")[0])


class RepairFilterTests(unittest.TestCase):
    def run_of(self, name, *jobs):
        return {"name": name, "jobs": [{"name": n, "conclusion": c} for n, c in jobs]}

    def test_real_matrix_job_names_count_as_worker_jobs(self):
        run = self.run_of("Agent swarm", ("Plan the wave", "success"),
                          ("Swarm worker (story, DLV-02, 0, Repair integration tests... / Work the task", "failure"))
        self.assertTrue(swarm.only_worker_failures(run))

    def test_a_failed_worker_alone_is_not_a_repair(self):
        run = self.run_of("Agent swarm", ("Plan the wave", "success"),
                          ("Swarm worker / Work the task", "failure"))
        self.assertTrue(swarm.only_worker_failures(run))

    def test_planning_or_integration_failures_still_count(self):
        for bad in ("Plan the wave", "Swarm integrator / Integrate the ready batch"):
            run = self.run_of("Agent swarm", (bad, "failure"), ("Swarm worker / Work the task", "failure"))
            self.assertFalse(swarm.only_worker_failures(run))

    def test_other_workflows_always_count(self):
        self.assertFalse(swarm.only_worker_failures(
            self.run_of("Delivery", ("Swarm worker / x", "failure"))))


class RepairTaskTests(unittest.TestCase):
    TASK = {"id": "FIX-9", "title": "Repair main: Delivery failed", "body": "Reproduce.\n\nlog line"}

    def run_add(self, text, gate=(True, "ok"), task=TASK):
        with tempfile.TemporaryDirectory() as tmp:
            todo = Path(tmp) / "TODO.md"
            todo.write_text(text)

            def fake_update(edit, message, path="TODO.md", attempts=8):
                new = edit(Path(path).read_text())
                Path(path).write_text(new)
                return new != text
            with mock.patch.object(swarm, "repair_gate", lambda run: gate), \
                    mock.patch.object(swarm, "repair_task", lambda run: task), \
                    mock.patch.object(swarm, "update_todo", fake_update):
                added = swarm.add_repair_task("9", str(todo))
            return added, todo.read_text()

    def test_failed_run_is_appended_as_a_whole_repository_task(self):
        added, text = self.run_add("# Tasks\n\n- [x] A-1 · done\n")
        self.assertTrue(added)
        task = next(t for t in swarm.parse_tasks(text) if t["id"] == "FIX-9")
        self.assertEqual((task["status"], task["scope"], task["attempts"]), ("todo", ["*"], "0"))
        self.assertIn("log line", task["details"])
        self.assertIn(swarm.REPAIR_HEADER, text)

    def test_second_repair_waits_while_one_is_open(self):
        _, once = self.run_add("# Tasks\n")
        added, again = self.run_add(once, task={**self.TASK, "id": "FIX-10"})
        self.assertFalse(added)
        self.assertEqual(once, again)

    def test_an_open_manual_repair_task_also_blocks_a_duplicate(self):
        text = ("- [ ] DLV-01 · Repair integration tests\n  - scope: apps/api\n  - details:\n    fix\n")
        added, again = self.run_add(text)
        self.assertFalse(added)
        self.assertEqual(text, again)
        done = text.replace("[ ]", "[x]")
        self.assertTrue(self.run_add(done)[0])

    def runs(self, *items):
        return [dict(zip(("databaseId", "name", "status", "conclusion", "createdAt"), i)) for i in items]

    def test_only_the_newest_finished_run_of_each_workflow_counts(self):
        data = self.runs(
            (5, "Delivery", "completed", "failure", "2026-11-20T05:00:00Z"),
            (4, "Delivery", "completed", "success", "2026-11-20T04:00:00Z"),
            (3, "Agent swarm", "completed", "failure", "2026-11-20T03:00:00Z"),
            (2, "Agent swarm", "completed", "success", "2026-11-20T02:00:00Z"),
            (9, "Delivery", "in_progress", None, "2026-11-20T06:00:00Z"),
            (8, "Agent image", "completed", "cancelled", "2026-11-20T05:30:00Z"),
            (1, "Swarm integrator", "completed", "failure", "2026-11-20T01:00:00Z"))
        with mock.patch.object(swarm, "gh_json", lambda *a: data):
            self.assertEqual(swarm.failing_runs_on_main(), [5, 3])
        healed = self.runs((6, "Delivery", "completed", "success", "2026-11-20T06:00:00Z"),
                           (5, "Delivery", "completed", "failure", "2026-11-20T05:00:00Z"))
        with mock.patch.object(swarm, "gh_json", lambda *a: healed):
            self.assertEqual(swarm.failing_runs_on_main(), [])

    def run_latest(self, text, failing, jobs, added_for=None):
        calls = []
        with tempfile.TemporaryDirectory() as tmp:
            todo = Path(tmp) / "TODO.md"
            todo.write_text(text)

            def fake_add(run_id, path="TODO.md"):
                calls.append(run_id)
                return True
            with mock.patch.object(swarm, "failing_runs_on_main", lambda: failing), \
                    mock.patch.object(swarm, "gh_json", lambda *a: jobs[int(a[2])]), \
                    mock.patch.object(swarm, "add_repair_task", fake_add):
                added = swarm.add_latest_repair_task(str(todo))
        return added, calls

    def test_latest_repair_skips_handled_runs_and_worker_only_failures(self):
        worker_only = {"name": "Agent swarm", "jobs": [{"name": "Swarm worker / Work", "conclusion": "failure"}]}
        real = {"name": "Delivery", "jobs": [{"name": "Java and database verification", "conclusion": "failure"}]}
        text = "- [x] FIX-7 · Repair main: Delivery failed\n  - scope: *\n"
        added, calls = self.run_latest(text, [7, 6, 5], {6: worker_only, 5: real})
        self.assertEqual((added, calls), (True, [5]))
        added, calls = self.run_latest(text, [7, 6], {6: worker_only})
        self.assertEqual((added, calls), (False, []))
        self.assertEqual(self.run_latest("", [], {}), (False, []))

    def test_gate_and_non_main_runs_add_nothing(self):
        self.assertFalse(self.run_add("# Tasks\n", gate=(False, "no"))[0])
        self.assertFalse(self.run_add("# Tasks\n", task=None)[0])

    def test_repair_is_planned_before_other_tasks_and_blocks_them(self):
        text = ("- [ ] A-1 · feature\n  - scope: apps/api\n  - details:\n    do it\n"
                "- [ ] FIX-9 · Repair main\n  - scope: *\n  - details:\n    fix it\n"
                "- [ ] B-1 · other\n  - scope: docs\n  - details:\n    do it\n")
        plan = swarm.plan_workers(10, [{"slot": n, "model": "m"} for n in range(1, 4)], text, [])
        self.assertEqual([t["id"] for t in plan], ["FIX-9"])


class WaitingWaveTests(unittest.TestCase):
    def stamps(self, *minutes_ago):
        now = datetime.datetime.now(datetime.timezone.utc)
        return "\n".join((now - datetime.timedelta(minutes=m)).strftime("%Y-%m-%dT%H:%M:%SZ")
                          for m in minutes_ago) + "\n"

    def test_counts_recent_runs_in_every_not_started_state(self):
        calls = []
        recent = self.stamps(2, 10)

        def fake(*args, check=True):
            status = args[args.index("--status") + 1]
            calls.append(status)
            return recent if status == "queued" else ""
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.waiting_waves(), 2)
        self.assertEqual(calls, ["queued", "pending", "waiting", "requested"])

    def test_runs_stuck_for_hours_do_not_block_the_loop(self):
        zombies = self.stamps(300, 1200, 2600)
        with mock.patch.object(swarm, "gh", lambda *a, check=True: zombies):
            self.assertEqual(swarm.waiting_waves(), 0)


class BudgetGateCliTests(unittest.TestCase):
    def run_cli(self, public):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            with mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(out)}), \
                    mock.patch.object(swarm, "repo_is_public", lambda: public), \
                    mock.patch.object(swarm, "monthly_minutes", lambda *a: 9900.0), \
                    mock.patch.object(swarm, "gh", FakeGh()):
                swarm.main(["budget-gate"])
            return dict(line.split("=", 1) for line in out.read_text().split())

    def test_public_repository_is_never_paused_by_the_minutes_estimate(self):
        got = self.run_cli(public=True)
        self.assertEqual(got["start"], "true")
        self.assertEqual(int(got["workers"]), swarm.MAX_ACTIVE_WORKERS)

    def test_private_repository_still_pauses_near_the_cap(self):
        self.assertEqual(self.run_cli(public=False)["start"], "false")

    def test_public_check_reads_the_private_flag(self):
        with mock.patch.object(swarm, "repo_slug", lambda: "o/r"):
            for out, expected in (("false\n", True), ("true\n", False), ("", False)):
                with mock.patch.object(swarm, "gh", lambda *a, check=True, out=out: out):
                    self.assertEqual(swarm.repo_is_public(), expected)

    def test_plan_job_may_open_the_pause_issue(self):
        import yaml
        plan = yaml.safe_load((ROOT / ".github/workflows/swarm.yml").read_text())["jobs"]["plan"]
        self.assertEqual(plan["permissions"].get("issues"), "write")


class PlanTests(unittest.TestCase):
    def plan(self, prs=(), workers=10, slots=SLOTS):
        return swarm.plan_workers(workers, slots, TASKS, list(prs))

    def test_ready_tasks_respect_dependencies_scope_and_status(self):
        ids = [t["id"] for t in self.plan()]
        self.assertEqual(ids, ["CW-001", "CW-002", "CW-006"])

    def test_task_text_carries_scope_reserved_version_and_details(self):
        body = self.plan()[0]["body"]
        for needle in ("Scope (stay inside these paths): apps/api/staff, db/migrations",
                       "Reserved migration version: V4", "Details for staff."):
            self.assertIn(needle, body)

    def test_open_pull_requests_block_their_task_and_overlapping_scopes(self):
        ids = [t["id"] for t in self.plan([pr(7, "swarm/CW-001-4")])]
        self.assertEqual(ids, ["CW-002", "CW-006"])

    def test_fixes_come_first_and_blocked_or_fork_prs_are_skipped(self):
        prs = [pr(9, "swarm/CW-002-4", "swarm", "needs-fix", comments=[swarm.MARK + "\nBroke"]),
               pr(8, "swarm/CW-001-4", "swarm", "needs-fix", "needs-human"),
               pr(10, "swarm/CW-006-4", "swarm", "needs-fix", cross=True)]
        plan = self.plan(prs)
        self.assertEqual([(t["kind"], t["id"], t["slot"]) for t in plan], [("fix", "CW-002", 1)])
        self.assertIn("Broke", plan[0]["fix"])
        self.assertEqual(plan[0]["branch"], "swarm/CW-002-4")

    def test_worker_count_is_capped_by_max_and_slots(self):
        self.assertEqual(len(self.plan(workers=1)), 1)
        self.assertEqual(len(self.plan(workers=10, slots=SLOTS[:2])), 2)

    def test_marking_a_task_updates_only_its_entry(self):
        text = swarm.set_task_status(TASKS, "CW-002", "done", pr="#12", evidence="CI green")
        tasks = {t["id"]: t for t in swarm.parse_tasks(text)}
        self.assertEqual((tasks["CW-002"]["status"], tasks["CW-002"]["pr"], tasks["CW-002"]["evidence"]),
                         ("done", "#12", "CI green"))
        self.assertEqual(tasks["CW-002"]["details"], "Details for config.")
        self.assertEqual(tasks["CW-001"]["status"], "todo")
        self.assertEqual(swarm.set_task_status(TASKS, "NOPE-1", "done"), TASKS)

    def test_taking_and_releasing_a_task_sets_and_clears_the_agent(self):
        taken = swarm.set_task_status(TASKS, "CW-001", "taken", agent="swarm-3")
        self.assertIn("- [-] CW-001 · Staff\n  - agent: swarm-3\n", taken)
        task = {t["id"]: t for t in swarm.parse_tasks(taken)}["CW-001"]
        self.assertEqual((task["status"], task["agent"], task["scope"]),
                         ("taken", "swarm-3", ["apps/api/staff", "db/migrations"]))
        freed = {t["id"]: t for t in swarm.parse_tasks(swarm.set_task_status(taken, "CW-001", "todo"))}
        self.assertEqual((freed["CW-001"]["status"], freed["CW-001"]["agent"]), ("todo", ""))
        self.assertEqual(freed["CW-001"]["details"], "Details for staff.")

    def test_tasks_taken_by_other_agents_block_their_scope(self):
        text = swarm.set_task_status(TASKS, "CW-001", "taken", agent="loop")
        ids = [t["id"] for t in swarm.plan_workers(10, SLOTS, text, [])]
        self.assertNotIn("CW-001", ids)
        self.assertNotIn("CW-003", ids)

    def test_an_alias_of_a_done_story_satisfies_dependencies_across_sections(self):
        plan = swarm.plan_workers(10, SLOTS, ALIASED, [])
        self.assertEqual([t["id"] for t in plan], ["CW-011"])

    def test_each_claim_counts_an_attempt_and_the_third_stops_the_retries(self):
        text = TASKS
        for tries in (1, 2, 3):
            text = swarm.set_task_status(text, "CW-001", "taken", agent="swarm-1")
            task = {t["id"]: t for t in swarm.parse_tasks(text)}["CW-001"]
            self.assertEqual(task["attempts"], str(tries))
            text = swarm.set_task_status(text, "CW-001", "todo")
        self.assertNotIn("CW-001", [t["id"] for t in swarm.plan_workers(10, SLOTS, text, [])])
        self.assertIn("  - attempts: 3\n", text)

    def test_continue_starts_a_wave_only_when_work_is_ready_and_nothing_waits(self):
        self.assertTrue(swarm.continue_swarm(TASKS, []))
        self.assertFalse(swarm.continue_swarm(ALIASED.replace("[ ] CW-011", "[x] CW-011"), []))
        waiting = [pr(7, "swarm/CW-001-4", "swarm")]
        self.assertFalse(swarm.continue_swarm(TASKS, waiting))
        blocked = [pr(7, "swarm/CW-001-4", "swarm", "needs-human")]
        self.assertTrue(swarm.continue_swarm(TASKS, blocked))

    def test_stories_without_a_scope_are_never_dispatched(self):
        self.assertNotIn("S-G9-01", [t["id"] for t in self.plan()])

    def test_reclaim_frees_dead_claims_and_finishes_merged_ones(self):
        text = swarm.set_task_status(TASKS, "CW-001", "taken", agent="swarm-1")
        text = swarm.set_task_status(text, "CW-002", "taken", agent="swarm-2")
        text = swarm.set_task_status(text, "CW-006", "taken", agent="loop")
        prs = [{"number": 7, "headRefName": "swarm/CW-001-4", "state": "MERGED"},
               {"number": 8, "headRefName": "swarm/CW-001-5", "state": "CLOSED"}]
        got = {t["id"]: t for t in swarm.parse_tasks(swarm.reclaim(text, prs))}
        self.assertEqual((got["CW-001"]["status"], got["CW-001"]["pr"]), ("done", "#7"))
        self.assertEqual(got["CW-002"]["status"], "todo")
        self.assertEqual(got["CW-006"]["status"], "taken")
        live = [{"number": 9, "headRefName": "swarm/CW-002-1", "state": "OPEN"}]
        kept = {t["id"]: t for t in swarm.parse_tasks(swarm.reclaim(text, live))}
        self.assertEqual(kept["CW-002"]["status"], "taken")

    def test_reclaim_keeps_a_claim_whose_worker_is_still_running(self):
        # A running worker has no pull request yet. Releasing its claim let an
        # overlapping wave take the same task, so two workers did one task.
        text = swarm.set_task_status(TASKS, "CW-001", "taken", agent="swarm-1")
        kept = {t["id"]: t for t in swarm.parse_tasks(swarm.reclaim(text, [], live=["CW-001"]))}
        self.assertEqual(kept["CW-001"]["status"], "taken")
        freed = {t["id"]: t for t in swarm.parse_tasks(swarm.reclaim(text, [], live=["CW-002"]))}
        self.assertEqual(freed["CW-001"]["status"], "todo")

    def test_reclaim_frees_stale_claims_of_the_older_opencode_dispatcher(self):
        text = swarm.set_task_status(TASKS, "CW-001", "taken", agent="opencode-delivery-dispatch-4")
        got = {t["id"]: t for t in swarm.parse_tasks(swarm.reclaim(text, []))}
        self.assertEqual(got["CW-001"]["status"], "todo")

    def test_reclaim_releases_needs_human_claims(self):
        text = swarm.set_task_status(TASKS, "CW-001", "taken", agent="swarm-1")
        prs = [{"number": 9, "headRefName": "swarm/CW-001-1", "state": "OPEN",
                "labels": labels("swarm", "needs-human")}]
        got = {t["id"]: t for t in swarm.parse_tasks(swarm.reclaim(text, prs))}
        self.assertEqual(got["CW-001"]["status"], "todo")
        self.assertEqual(got["CW-001"]["agent"], "")

    def test_reclaim_keeps_active_fix_claims(self):
        text = swarm.set_task_status(TASKS, "CW-001", "taken", agent="swarm-1")
        prs = [{"number": 9, "headRefName": "swarm/CW-001-1", "state": "OPEN",
                "labels": labels("swarm", "needs-fix", "fix-1")}]
        got = {t["id"]: t for t in swarm.parse_tasks(swarm.reclaim(text, prs))}
        self.assertEqual(got["CW-001"]["status"], "taken")

    def test_release_claim_returns_task_to_open(self):
        captured = {}

        def fake(edit, message):
            captured["text"] = edit(TASKS)
            return True

        with mock.patch.object(swarm, "update_todo", fake):
            self.assertTrue(swarm.release_claim("CW-001", "worker failed"))
        got = {t["id"]: t for t in swarm.parse_tasks(captured["text"])}
        self.assertEqual(got["CW-001"]["status"], "todo")

    def test_scope_overlap_uses_path_prefixes(self):
        self.assertTrue(swarm.overlaps(["a/b/**"], ["a/b/c"]))
        self.assertFalse(swarm.overlaps(["a/b"], ["a/c"]))

    def test_label_editing_jobs_have_issues_write(self):
        import yaml
        for name, job in (("swarm-integrator.yml", "integrate"), ("fix-pr-gate.yml", "validate")):
            config = yaml.safe_load((ROOT / ".github/workflows" / name).read_text())
            perms = config["jobs"][job]["permissions"]
            self.assertEqual(perms.get("issues"), "write", f"{name} {job}")

    def test_labels_use_rest_not_graphql_pr_edit(self):
        source = (ROOT / "scripts/swarm.py").read_text()
        self.assertNotIn('"pr", "edit"', source)
        self.assertIn('"api", "--method", "POST"', source)

    def test_gh_retries_transient_api_errors(self):
        responses = [
            subprocess.CompletedProcess([], 1, "", "HTTP 502: 502 Bad Gateway"),
            subprocess.CompletedProcess([], 0, "ok", ""),
        ]
        with mock.patch.object(swarm.subprocess, "run", side_effect=responses) as run, \
             mock.patch.object(swarm.time, "sleep"):
            self.assertEqual(swarm.gh("pr", "create"), "ok")
        self.assertEqual(run.call_count, 2)

    def test_gh_does_not_retry_permanent_errors(self):
        with mock.patch.object(swarm.subprocess, "run",
                               return_value=subprocess.CompletedProcess(
                                   [], 1, "", "permission denied")) as run, \
             self.assertRaises(subprocess.CalledProcessError):
            swarm.gh("pr", "create")
        self.assertEqual(run.call_count, 1)

    def test_gh_failure_surfaces_stderr(self):
        with mock.patch.object(swarm.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, "", "permission denied")
            stream = io.StringIO()
            with redirect_stderr(stream), self.assertRaises(subprocess.CalledProcessError):
                swarm.gh("pr", "edit", "30", "--add-label", "needs-fix")
        self.assertIn("permission denied", stream.getvalue())

    def test_integration_runs_inside_the_wave_not_through_a_workflow_run_chain(self):
        import yaml
        integrator = yaml.safe_load((ROOT / ".github/workflows/swarm-integrator.yml").read_text())
        triggers = integrator[True]
        self.assertIn("workflow_call", triggers)
        self.assertNotIn("workflow_run", triggers)
        wave = yaml.safe_load((ROOT / ".github/workflows/swarm.yml").read_text())["jobs"]
        self.assertEqual(wave["integrate"]["uses"], "./.github/workflows/swarm-integrator.yml")
        self.assertEqual(wave["integrate"]["needs"], ["plan", "worker"])
        self.assertEqual(wave["loop"]["needs"], ["plan", "worker", "integrate"])
        self.assertNotIn("swarm.py continue", (ROOT / ".github/workflows/swarm-integrator.yml").read_text())
        for perm in ("contents", "pull-requests", "issues", "actions", "packages"):
            self.assertIn(perm, wave["integrate"]["permissions"])

    def test_integrator_gate_is_the_full_check(self):
        text = (ROOT / ".github/workflows/swarm-integrator.yml").read_text()
        self.assertIn("GATE: scripts/integrator-gate.sh", text)
        self.assertIn("postgres:", text)
        self.assertIn("SMARTCLINIC_PG_ADMIN: postgres", text)
        self.assertNotIn("GATE: scripts/quick-check.sh", text)

    def test_task_id_of_handles_multi_hyphen_story_ids(self):
        cases = {
            "swarm/CW-001-1": "CW-001",
            "swarm/S-G0-06-64": "S-G0-06",
            "swarm/S-G1-02-64": "S-G1-02",
            "swarm/X-01-64": "X-01",
            "swarm/FIX-444-7": "FIX-444",
        }
        for branch, expected in cases.items():
            with self.subTest(branch=branch):
                self.assertEqual(swarm.task_id_of(branch), expected)
        self.assertEqual(swarm.task_id_of("main"), "")

    def test_tracker_commits_skip_ci_waves(self):
        source = (ROOT / "scripts/swarm.py").read_text()
        self.assertIn('"commit", "-qm", f"{message} [skip ci]"', source)

    def test_open_spec_tasks_are_dispatchable(self):
        tasks = swarm.parse_tasks((ROOT / "TODO.md").read_text())
        dispatchable = [t for t in tasks
                        if t["status"] in ("todo", "taken") and t["scope"] and t["details"]
                        and swarm.attempts_of(t) < swarm.MAX_ATTEMPTS]
        # The count shrinks as the swarm finishes tasks; it only has to stay non-empty
        # while TODO.md still lists open work.
        self.assertGreaterEqual(len(dispatchable), 1)

    def test_worker_name_is_static_not_matrix_expression(self):
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertIn("name: Swarm worker", text)
        self.assertNotIn("Worker ${{ matrix.slot }}", text)

    def test_repair_watches_fix_prs_only(self):
        workflows = ROOT / ".github/workflows"
        self.assertFalse((workflows / "main-watchdog.yml").exists())
        self.assertFalse((workflows / "fix-pr-watchdog.yml").exists())
        text = (workflows / "fix-pr-gate.yml").read_text()
        self.assertIn("pull_request:", text)
        self.assertNotIn("workflow_run:", text)
        self.assertNotIn("cron:", text)
        self.assertIn("autofix", text)
        self.assertIn("scripts/check.sh", text)
        self.assertIn("swarm-integrator.yml", text)
        self.assertIn("gh pr ready", text)
        condition = text.split("    if: >-", 1)[1].split("runs-on:", 1)[0]
        self.assertIn("'autofix'", condition)
        self.assertIn("startsWith(github.event.pull_request.title, 'FIX-')", condition)
        # "contains(title, 'fix')" is case-insensitive and matched story titles
        # such as "...synthetic fixtures)", starting a full gate for each.
        self.assertNotIn("contains(github.event.pull_request.title", condition)
        self.assertNotIn("head.ref", condition)
        self.assertIn("api", text)

    def test_failed_main_runs_become_repair_tasks_inside_the_scheduler(self):
        # No separate watchdog workflow: it ran once per failure and cluttered
        # the Actions list. The scheduler's plan job queues the repair itself.
        self.assertFalse((ROOT / ".github/workflows/failed-run-repair.yml").exists())
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertIn("swarm.py add-repair-task --latest", text)
        self.assertLess(text.index("add-repair-task --latest"), text.index("swarm.py plan-workers"))
        for name in ("Delivery", "Agent swarm", "Agent image", "Swarm slot images"):
            self.assertIn(name, swarm.REPAIR_WORKFLOWS)
        self.assertNotIn("Swarm integrator", swarm.REPAIR_WORKFLOWS)

    def test_budget_workflows_include_the_fix_pr_gate(self):
        self.assertIn("fix-pr-gate.yml", swarm.BUDGET_WORKFLOWS)
        self.assertNotIn("fix-pr-watchdog.yml", swarm.BUDGET_WORKFLOWS)
        self.assertNotIn("main-watchdog.yml", swarm.BUDGET_WORKFLOWS)

    def test_worker_has_three_hour_budget_in_segments_with_merge_slack(self):
        worker = (ROOT / ".github/workflows/swarm-worker.yml").read_text()
        # 45+45+45+40 = 175 minutes of agent work, a main sync after each of the
        # first three segments, then the final merge before publishing.
        for minutes in ("45m", "45m", "45m", "40m"):
            self.assertIn(f"AGENT_TIMEOUT: {minutes}", worker)
        for n in (1, 2, 3, 4):
            self.assertIn(f"SEGMENT: '{n}'", worker)
        self.assertEqual(worker.count("SYNC_ONLY: '1'"), 3)
        self.assertIn("Fetch main (final)", worker)
        self.assertIn("timeout-minutes: 300", worker)
        self.assertIn("timeout-minutes: 35", worker)

    def test_task_prompt_states_the_deadline(self):
        text = swarm.task_text({"task": "T", "scope": ["apps/api"],
                                "details": "Do the work."})
        self.assertIn("175 minutes", text)
        self.assertIn("new worker", text)

    def test_monthly_cap_allows_long_waves(self):
        self.assertGreaterEqual(swarm.DEFAULT_MONTHLY_MINUTES, 10 * 185 * 5)

    def test_workflow_permissions_use_only_valid_scopes(self):
        """`workflows` is not a permissions key; using it invalidates the YAML."""
        import yaml
        valid = {"actions", "attestations", "checks", "contents", "deployments",
                 "discussions", "id-token", "issues", "models", "packages",
                 "pages", "pull-requests", "security-events", "statuses", "none", "write-all", "read-all"}
        for name in ("swarm.yml", "swarm-integrator.yml"):
            config = yaml.safe_load((ROOT / ".github/workflows" / name).read_text())
            blocks = [config.get("permissions", {})]
            blocks += [job.get("permissions", {}) for job in config.get("jobs", {}).values()]
            for block in blocks:
                if isinstance(block, dict):
                    self.assertFalse(set(block) - valid, f"{name}: invalid permission keys")

    def test_publish_gets_workflow_token_flag(self):
        publish = (ROOT / ".github/workflows/swarm-worker.yml").read_text().split("  publish:\n", 1)[1]
        self.assertIn("SWARM_WORKFLOW_PUSH: ${{ secrets.SWARM_TOKEN != '' }}", publish)

    def test_fallback_wave_runs_hourly(self):
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertIn("cron: '7 * * * *'", text)
        self.assertNotIn("cron: '7 */3 * * *'", text)

    def test_integrator_gate_runs_full_check_and_docs(self):
        script = (ROOT / "scripts/integrator-gate.sh").read_text()
        self.assertIn("bash scripts/check.sh", script)
        self.assertIn("bash scripts/docs.sh", script)
        workflow = (ROOT / ".github/workflows/swarm-integrator.yml").read_text()
        self.assertIn("GATE: scripts/integrator-gate.sh", workflow)
        self.assertIn("Install Doxygen", workflow)

    def test_doxygen_install_retries_apt_mirror_failures(self):
        text = (ROOT / ".github/workflows/delivery.yml").read_text()
        self.assertIn("for attempt in 1 2 3", text)
        self.assertIn("apt install attempt", text)

    def test_wave_runs_ten_workers_and_each_publishes_on_its_own(self):
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertIn("default: '10'", text)
        self.assertIn("inputs.workers || '10'", text)
        self.assertIn("max-parallel: 10", text)
        # Each worker is a reusable-workflow call, so its publish job needs only its
        # own work job and never waits for the slowest worker of the wave.
        self.assertIn("uses: ./.github/workflows/swarm-worker.yml", text)
        self.assertNotIn("\n  publish:\n", text)
        worker = (ROOT / ".github/workflows/swarm-worker.yml").read_text()
        self.assertIn("needs: work", worker)
        self.assertIn("workflow_call:", worker)

    def test_waves_overlap_but_planning_stays_serial(self):
        import yaml
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        config = yaml.safe_load(text)
        self.assertEqual(config[True]["workflow_dispatch"]["inputs"]["now"]["type"], "boolean")
        group = config["concurrency"]["group"]
        self.assertIn("github.run_id", group)
        self.assertIs(config["concurrency"]["cancel-in-progress"], False)
        plan = config["jobs"]["plan"]["concurrency"]
        self.assertEqual(plan["group"], "agent-swarm-plan")
        self.assertIs(plan["cancel-in-progress"], False)

    def test_wave_is_capped_by_the_remaining_budget(self):
        self.assertGreaterEqual(swarm.affordable_workers(0, 10000), 10)
        self.assertEqual(swarm.affordable_workers(7434, 10000), 8)
        self.assertEqual(swarm.affordable_workers(8950, 10000), 1)
        self.assertEqual(swarm.affordable_workers(20000, 10000), 1)
        self.assertIn("BUDGET_WORKERS", (ROOT / ".github/workflows/swarm.yml").read_text())

    def test_parallel_workers_are_billed_by_job_minutes(self):
        class Gh(FakeGh):
            def __call__(self, *args, check=True):
                self.calls.append(args)
                if args[0] == "api":
                    return "\n".join(["2026-11-19T10:00:00Z\t2026-11-19T12:00:00Z"] * 10)
                return ""
        with mock.patch.object(swarm, "gh", Gh()):
            self.assertEqual(swarm.run_job_minutes(7), 1200.0)

    def test_pr_size_guard_is_enforced(self):
        fake = FakeGh(diff="\n".join(f"apps/api/src/F{i}.java" for i in range(16)))
        with mock.patch.object(swarm, "gh", fake):
            self.assertTrue(swarm.guard(9))
        self.assertTrue(fake.said("labels[]=needs-human"))

    def test_committed_files_are_valid(self):
        slots = swarm.load_slots(ROOT / ".github/swarm/slots.json")
        self.assertEqual([s["slot"] for s in slots], list(range(1, 11)))
        self.assertTrue(all(s["model"] == "opencode-go/hy4-flash-free" for s in slots))
        config = swarm.slot_config(slots, 2)
        self.assertEqual((config["model"], config["autoupdate"], config["share"]),
                         (slots[1]["model"], False, "disabled"))
        self.assertNotIn("key", json.dumps(config).lower())
        tasks = swarm.parse_tasks((ROOT / "TODO.md").read_text())
        ids = [t["id"] for t in tasks]
        self.assertEqual(len(ids), len(set(ids)))
        for task in tasks:
            self.assertTrue(task["task"])
            if task["status"] == "todo" and task["scope"]:
                self.assertTrue(task["details"], task["id"])
        reserved = [t["reserved"] for t in tasks if t["reserved"]]
        self.assertEqual(len(reserved), len(set(reserved)))


class ReleaseTests(unittest.TestCase):
    fix = {"kind": "fix", "id": "CW-002", "number": 9, "slot": 1}
    story = {"kind": "story", "id": "CW-001", "number": 0, "slot": 2}

    def release(self, task, result, commits=0):
        fake = FakeGh()
        with mock.patch.object(swarm, "gh", fake):
            swarm.release(task, result, commits)
        return fake

    def test_fix_that_pushed_nothing_returns_to_the_pool_or_a_human(self):
        self.assertTrue(self.release(self.fix, "rate_limited").said("api", "--method", "POST"))
        self.assertTrue(self.release(self.fix, "failed").said("labels[]=needs-human"))

    def test_success_and_story_failures_touch_nothing(self):
        self.assertEqual(self.release(self.fix, "ok", 2).calls, [])
        self.assertEqual(self.release(self.story, "rate_limited").calls, [])


class FailTests(unittest.TestCase):
    def fail(self, existing):
        fake = FakeGh(pr_view={"labels": labels("swarm", *existing)})
        with mock.patch.object(swarm, "gh", fake):
            more = swarm.fail(9, "Integration failed", "line\n```evil\n" * 3)
        return more, fake

    def test_first_failure_asks_for_a_fix_and_dispatches_a_worker(self):
        more, fake = self.fail([])
        self.assertTrue(more)
        self.assertTrue(fake.said("labels[]=needs-fix") and fake.said("labels[]=fix-1"))
        self.assertTrue(fake.said("workflow", "run", "swarm.yml"))
        comment = [c for c in fake.calls if c[:2] == ("pr", "comment")][0][-1]
        self.assertIn(swarm.MARK, comment)
        self.assertNotIn("```evil", comment)

    def test_attempts_are_counted_and_capped(self):
        self.assertTrue(self.fail(["fix-1"])[1].said("labels[]=fix-2"))
        more, fake = self.fail(["fix-1", "fix-2", "fix-3"])
        self.assertFalse(more)
        self.assertTrue(fake.said("labels[]=needs-human"))
        self.assertFalse(fake.said("workflow"))


class IntegrationTests(unittest.TestCase):
    def test_queue_skips_blocked_and_fork_pull_requests(self):
        prs = [{"number": 3, "labels": labels("swarm"), "isCrossRepository": False},
               {"number": 2, "labels": labels("swarm", "needs-fix"), "isCrossRepository": False},
               {"number": 1, "labels": labels("swarm"), "isCrossRepository": True},
               {"number": 4, "labels": labels("swarm"), "isCrossRepository": False}]
        with mock.patch.object(swarm, "gh", FakeGh(prs=prs)):
            self.assertEqual(swarm.plan_integration(), [3, 4])

    def test_guard_stops_protected_paths_for_a_human(self):
        fake = FakeGh(diff="apps/api/src/A.java\n.github/workflows/x.yml\nscripts/swarm.py\n")
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9), [".github/workflows/x.yml", "scripts/swarm.py"])
        self.assertTrue(fake.said("labels[]=needs-human"))

    def test_repair_pr_bypasses_protected_path_guard(self):
        fake = FakeGh(diff="apps/api/src/A.java\n.github/workflows/x.yml\nscripts/swarm.py\n")
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9, repair=True), [])
        self.assertFalse(fake.said("labels[]=needs-human"))

    def test_is_repair_detects_autofix_label(self):
        fake = FakeGh(pr_view={"labels": labels("swarm", "autofix"), "title": "other"})
        with mock.patch.object(swarm, "gh", fake):
            self.assertTrue(swarm.is_repair(9))

    def test_is_repair_detects_fix_title(self):
        fake = FakeGh(pr_view={"labels": labels("swarm"), "title": "FIX-444: Repair main"})
        with mock.patch.object(swarm, "gh", fake):
            self.assertTrue(swarm.is_repair(9))

    def test_is_repair_false_for_ordinary_story(self):
        fake = FakeGh(pr_view={"labels": labels("swarm"), "title": "CW-001: Staff"})
        with mock.patch.object(swarm, "gh", fake):
            self.assertFalse(swarm.is_repair(9))

    def test_guard_sends_tracker_edits_back_to_the_worker(self):
        fake = FakeGh(diff="apps/api/A.java\nTODO.md\n", pr_view={"labels": []})
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9), ["TODO.md"])
        self.assertTrue(fake.said("labels[]=fix-1"))
        self.assertFalse(fake.said("labels[]=needs-human"))

    def test_guard_allows_ordinary_changes(self):
        fake = FakeGh(diff="apps/api/src/A.java\ndb/migrations/V4__x.sql\n")
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9), [])
        self.assertEqual(fake.calls, [("pr", "diff", "9", "--name-only")])

    def test_guard_blocks_new_module_check_scripts_for_a_human(self):
        # The merge gate itself is protected, including module check.sh files:
        # a change to any check script must be reviewed by a person.
        fake = FakeGh(diff="apps/staff-web/check.sh\napps/staff-web/src/App.tsx\n"
                           "apps/patient-mobile/check.sh\napps/patient-mobile/App.tsx\n")
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9), ["apps/staff-web/check.sh", "apps/patient-mobile/check.sh"])
        self.assertTrue(fake.said("labels[]=needs-human"))

    def test_guard_still_protects_repository_root_check_script(self):
        fake = FakeGh(diff="check.sh\n")
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9), ["check.sh"])
        self.assertTrue(fake.said("labels[]=needs-human"))


class WorkflowTests(unittest.TestCase):
    def test_workflows_only_call_real_subcommands(self):
        import re
        calls = set()
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            calls |= set(re.findall(r"scripts/swarm\.py (?:-I )?([a-z-]+)", path.read_text()))
            calls |= set(re.findall(r"swarm\.py ([a-z-]+)", path.read_text()))
        self.assertTrue({"plan-workers", "publish", "budget-gate", "integrate-batch",
                         "plan-integration", "slot-config", "slot-matrix",
                         "ensure-labels"} <= calls)
        for command in calls:
            done = subprocess.run(["python3", "-I", str(ROOT / "scripts/swarm.py"), command, "--help"],
                                  capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, command)

    def test_every_slot_has_a_secret_reference_pattern_and_an_image_layer(self):
        text = (ROOT / ".github/workflows/swarm-worker.yml").read_text()
        self.assertIn("OPENCODE_API_KEY_{0}", text)
        self.assertNotIn("OPENCODE_API_KEY:", (ROOT / ".github/agent-image/Dockerfile.slot").read_text())

    def test_swarm_slot_secret_falls_back_to_team_secret(self):
        text = (ROOT / ".github/workflows/swarm-worker.yml").read_text()
        self.assertIn("secrets[format('OPENCODE_API_KEY_{0}', inputs.slot)]", text)
        self.assertIn("secrets.OPENCODE_API_KEY", text)

    def test_worker_script_marks_checkout_safe(self):
        text = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertIn("git config --global --add safe.directory", text)


class AuditTests(unittest.TestCase):
    TODO = "- [x] S-1 · Existing finished story.\n  - scope: a\n"

    def entries(self, findings, text=None, today="20261120"):
        return swarm.audit_entries(findings, text or self.TODO, today)

    def good(self, **over):
        item = {"title": "Tenant check missing on export endpoint", "scope": ["apps/api/src/export"],
                "details": "ExportController skips the tenant check.\nSee ExportController.java."}
        return {**item, **over}

    def test_valid_finding_becomes_a_todo_entry(self):
        (entry,) = self.entries([self.good()])
        self.assertIn("- [ ] AUD-20261120-01 · Tenant check missing on export endpoint", entry)
        self.assertIn("  - attempts: 0\n  - scope: apps/api/src/export", entry)
        tasks = swarm.parse_tasks(self.TODO + entry + "\n")
        self.assertEqual(tasks[-1]["status"], "todo")
        self.assertEqual(tasks[-1]["scope"], ["apps/api/src/export"])
        self.assertIn("ExportController skips", tasks[-1]["details"])

    def test_invalid_findings_are_dropped(self):
        bad = [self.good(title="short"), self.good(scope=[]), self.good(scope=["/etc"]),
               self.good(scope=["../x"]), self.good(scope=[".github/workflows"]),
               self.good(details=""), "text", None]
        self.assertEqual(self.entries(bad), [])
        self.assertEqual(self.entries({"not": "a list"}), [])

    def test_duplicates_of_existing_titles_are_skipped(self):
        text = self.TODO + "- [ ] X-1 · Tenant check MISSING on export endpoint!\n  - scope: a\n"
        self.assertEqual(self.entries([self.good()], text), [])

    def test_at_most_five_new_tasks_per_audit(self):
        items = [self.good(title=f"Distinct finding number {n} in the code") for n in range(8)]
        self.assertEqual(len(self.entries(items)), swarm.MAX_AUDIT_NEW)

    def test_no_new_tasks_while_too_many_audit_tasks_are_open(self):
        text = self.TODO + "".join(
            f"- [ ] AUD-2026-{n:02d} · Open audit task {n} waiting.\n  - scope: a\n"
            for n in range(swarm.MAX_AUDIT_OPEN))
        self.assertEqual(self.entries([self.good()], text), [])

    def test_ids_do_not_collide_with_existing_ones(self):
        text = self.TODO + "- [x] AUD-20261120-01 · Old.\n  - scope: a\n"
        (entry,) = self.entries([self.good()], text)
        self.assertIn("AUD-20261120-02", entry)

    def test_add_audit_tasks_appends_under_one_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            todo, found = Path(tmp, "TODO.md"), Path(tmp, "f.json")
            todo.write_text(self.TODO)
            found.write_text(json.dumps([self.good()]))
            with mock.patch.object(swarm, "git"), mock.patch.object(swarm, "authed_git"):
                self.assertEqual(swarm.add_audit_tasks(str(found), str(todo), "20261120"), 1)
                found.write_text(json.dumps([self.good(title="Another distinct finding here")]))
                swarm.add_audit_tasks(str(found), str(todo), "20261121")
            text = todo.read_text()
            self.assertEqual(text.count(swarm.AUDIT_HEADER), 1)
            self.assertEqual([t["id"] for t in swarm.parse_tasks(text)][-2:],
                             ["AUD-20261120-01", "AUD-20261121-01"])

    def test_missing_or_broken_findings_change_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            todo = Path(tmp, "TODO.md")
            todo.write_text(self.TODO)
            self.assertEqual(swarm.add_audit_tasks(str(Path(tmp, "none.json")), str(todo)), 0)
            Path(tmp, "bad.json").write_text("{nope")
            self.assertEqual(swarm.add_audit_tasks(str(Path(tmp, "bad.json")), str(todo)), 0)
            self.assertEqual(todo.read_text(), self.TODO)

    def test_daily_workflow_is_read_only_for_the_worker(self):
        text = (ROOT / ".github/workflows/daily-audit.yml").read_text()
        self.assertIn("cron: '17 7 * * *'", text)
        self.assertIn("scripts/agent-run.sh audit", text)
        self.assertIn("swarm.py add-audit-tasks", text)
        audit = text.split("  audit:")[1].split("  append:")[0]
        self.assertNotIn("GH_TOKEN", audit)
        self.assertNotIn("contents: write", audit)


class ResolveConflictTests(unittest.TestCase):
    def view(self, comments=0, cross=False, branch="swarm/CW-001-1"):
        return {"headRefName": branch, "isCrossRepository": cross,
                "comments": [{"body": swarm.RESOLVE_MARK}] * comments}

    def test_conflict_is_handed_to_the_resolver_not_failed(self):
        fake = FakeGh(pr_view=self.view())
        with mock.patch.object(swarm, "gh", fake), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "is_repair", return_value=False), \
             mock.patch.object(swarm, "merge_local", return_value=(None, "CW-001", ["a.java"])), \
             mock.patch.object(swarm, "fail") as failed, \
             mock.patch.object(swarm, "emit") as emitted:
            swarm.integrate_batch([5])
        failed.assert_not_called()
        emitted.assert_any_call("conflicted", "[5]")
        queued = [c for c in fake.calls if c[:2] == ("pr", "comment")]
        self.assertTrue(any(swarm.RESOLVE_MARK in part for c in queued for part in c))

    def test_conflict_goes_back_to_the_workers_after_three_tries(self):
        fake = FakeGh(pr_view=self.view(comments=swarm.MAX_RESOLVE))
        with mock.patch.object(swarm, "gh", fake), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "is_repair", return_value=False), \
             mock.patch.object(swarm, "merge_local", return_value=(None, "CW-001", ["a.java"])), \
             mock.patch.object(swarm, "fail") as failed, \
             mock.patch.object(swarm, "emit") as emitted:
            swarm.integrate_batch([5])
        self.assertTrue(failed.called)
        emitted.assert_any_call("conflicted", "[]")

    def results(self, tmp, **outcomes):
        for pr, outcome in outcomes.items():
            number = pr.lstrip("pr")
            Path(tmp, number).write_text(outcome)
            if outcome == "merged":
                Path(tmp, f"{number}.bundle").write_text("bundle")

    def test_resolved_branch_is_pushed_and_the_integrator_runs_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.results(tmp, pr5="merged")
            fake = FakeGh(pr_view=self.view())
            with mock.patch.object(swarm, "gh", fake), \
                 mock.patch.object(swarm, "git") as local, \
                 mock.patch.object(swarm, "authed_git") as push, \
                 mock.patch.object(swarm, "fail") as failed:
                self.assertEqual(swarm.resolve_publish(tmp), ["5"])
        self.assertTrue(any(c.args[:1] == ("fetch",) for c in local.call_args_list))
        push.assert_called_once_with("push", "origin", "resolve-5:refs/heads/swarm/CW-001-1")
        failed.assert_not_called()
        self.assertTrue(fake.said("workflow", "run", "swarm-integrator.yml"))
        self.assertTrue(fake.said("pr", "comment", "5"))

    def test_unresolved_conflict_is_failed_back_to_the_workers(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.results(tmp, pr5="conflict")
            with mock.patch.object(swarm, "gh", FakeGh(pr_view=self.view())), \
                 mock.patch.object(swarm, "authed_git") as push, \
                 mock.patch.object(swarm, "fail") as failed:
                self.assertEqual(swarm.resolve_publish(tmp), [])
        push.assert_not_called()
        self.assertEqual(failed.call_args.args[0], 5)

    def test_missing_bundle_is_never_pushed(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "5").write_text("merged")
            with mock.patch.object(swarm, "gh", FakeGh(pr_view=self.view())), \
                 mock.patch.object(swarm, "authed_git") as push, \
                 mock.patch.object(swarm, "fail") as failed:
                self.assertEqual(swarm.resolve_publish(tmp), [])
        push.assert_not_called()
        self.assertTrue(failed.called)

    def test_fork_branches_are_never_pushed(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.results(tmp, pr5="merged")
            with mock.patch.object(swarm, "gh", FakeGh(pr_view=self.view(cross=True))), \
                 mock.patch.object(swarm, "git"), \
                 mock.patch.object(swarm, "authed_git") as push, \
                 mock.patch.object(swarm, "fail") as failed:
                swarm.resolve_publish(tmp)
        push.assert_not_called()
        self.assertTrue(failed.called)

    def test_integrator_workflow_resolves_conflicts_without_a_token_in_the_agent_step(self):
        text = (ROOT / ".github/workflows/swarm-integrator.yml").read_text()
        resolve, publish = text.split("  resolve:")[1].split("  resolve-publish:")
        self.assertIn("agent-run.sh resolve", resolve)
        self.assertIn("swarm.py resolve-publish", publish)
        # The agent job holds no write token; the push job never runs the agent.
        self.assertIn("contents: read", resolve)
        self.assertNotIn("SWARM_TOKEN", resolve)
        self.assertNotIn("agent-run.sh resolve", publish)
        step = resolve.split("scripts/agent-run.sh resolve")[0].rsplit("- name:", 1)[1]
        self.assertNotIn("GH_TOKEN", step)

    def test_swarm_worker_uses_its_token_only_before_the_agent_runs(self):
        text = (ROOT / ".github/workflows/swarm-worker.yml").read_text()
        worker = text.split("  publish:\n", 1)[0]
        self.assertLess(worker.index("agent-run.sh fetch-main"), worker.index("agent-run.sh implement"))
        # After the agent has run, the only step that sees a token is the inline
        # fetch: no workspace script, repository hooks off, token sent to github.com.
        import yaml
        steps = yaml.safe_load(text)["jobs"]["work"]["steps"]
        started = False
        for step in steps:
            if "agent-run.sh implement" in step.get("run", ""):
                started = True
            if started and "github.token" in str(step.get("env", {})):
                self.assertTrue(step["name"].startswith("Fetch main"), step["name"])
                self.assertNotIn("agent-run.sh", step["run"])
                self.assertIn("core.hooksPath=/dev/null", step["run"])
                self.assertIn('fetch -q "https://github.com/$GITHUB_REPOSITORY"', step["run"])


class RepairTests(unittest.TestCase):
    def test_repair_task_carries_the_failing_log(self):
        class Gh(FakeGh):
            def __call__(self, *args, check=True):
                self.calls.append(args)
                if args[:2] == ("run", "view") and "--log-failed" in args:
                    return "\n".join(f"line {i}" for i in range(400)) + "\n```x"
                return json.dumps({"name": "Delivery", "headBranch": "main", "headSha": "a" * 40,
                                   "conclusion": "failure", "url": "u"})
        with mock.patch.object(swarm, "gh", Gh()):
            task = swarm.repair_task(55)
        self.assertEqual((task["id"], task["kind"]), ("FIX-55", "story"))
        self.assertIn("line 399", task["body"])
        self.assertNotIn("line 10\n", task["body"])
        self.assertNotIn("```x", task["body"])

    def test_only_failed_runs_on_main_are_repaired(self):
        for branch, conclusion in (("feature", "failure"), ("main", "success")):
            run = json.dumps({"name": "Delivery", "headBranch": branch, "headSha": "a",
                              "conclusion": conclusion, "url": "u"})
            with mock.patch.object(swarm, "gh", lambda *a, check=True, run=run: run):
                self.assertIsNone(swarm.repair_task(5))

    def test_repairs_go_first_and_fix_prs_for_repairs_are_planned(self):
        repair = {"kind": "story", "id": "FIX-9", "number": 0, "branch": "", "title": "r", "body": "b", "fix": ""}
        plan = swarm.plan_workers(10, SLOTS, TASKS, [], repair)
        self.assertEqual(plan[0]["id"], "FIX-9")
        fixing = [dict(pr(4, "swarm/FIX-9-2", "swarm", "needs-fix"), title="FIX-9: r")]
        plan = swarm.plan_workers(10, SLOTS, TASKS, fixing)
        self.assertEqual((plan[0]["kind"], plan[0]["id"]), ("fix", "FIX-9"))

    def gate(self, prs, now="2026-11-19T00:00:00+00:00"):
        fake = FakeGh(prs=prs)
        with mock.patch.object(swarm, "gh", fake):
            start, _ = swarm.repair_gate(5, datetime.datetime.fromisoformat(now))
        return start, fake

    def test_gate_allows_a_first_repair(self):
        self.assertTrue(self.gate([])[0])

    def test_gate_waits_while_a_repair_is_open(self):
        prs = [{"number": 1, "title": "FIX-1: x", "state": "OPEN", "createdAt": "2026-11-18T23:00:00Z"}]
        self.assertFalse(self.gate(prs)[0])

    def test_gate_gives_up_after_three_recent_repairs_and_opens_an_issue(self):
        prs = [{"number": n, "title": f"FIX-{n}: x", "state": "MERGED", "createdAt": "2026-11-18T22:00:00Z"}
               for n in range(3)]
        start, fake = self.gate(prs)
        self.assertFalse(start)
        self.assertTrue(fake.said("issue", "create"))

    def test_a_red_main_comments_on_the_open_issue_instead_of_opening_another(self):
        prs = [{"number": n, "title": f"FIX-{n}: x", "state": "MERGED", "createdAt": "2026-11-18T22:00:00Z"}
               for n in range(3)]
        title = "Main is red and automatic repair gave up"
        issues = [{"number": 41, "title": title}, {"number": 12, "title": title},
                  {"number": 50, "title": "Another problem"}]
        fake = FakeGh(prs=prs, issues=issues)
        with mock.patch.object(swarm, "gh", fake):
            start, why = swarm.repair_gate("77", now=datetime.datetime(2026, 11, 19, 12, tzinfo=datetime.timezone.utc))
        self.assertFalse(start)
        self.assertFalse(fake.said("issue", "create"))
        comment = next(c for c in fake.calls if c[:2] == ("issue", "comment"))
        self.assertEqual(comment[2], "12")
        body = comment[comment.index("--body") + 1]
        self.assertRegex(body, r"\*\*\d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC\*\*")
        self.assertIn("77", body)

    def test_report_issue_opens_one_when_none_is_open(self):
        fake = FakeGh(issues=[{"number": 3, "title": "Something else"}])
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.report_issue("A problem", "details"), "")
        create = next(c for c in fake.calls if c[:2] == ("issue", "create"))
        body = create[create.index("--body") + 1]
        self.assertTrue(body.startswith(swarm.MARK))
        self.assertIn("details", body)
        self.assertRegex(body, r"\d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC")

    def test_old_repairs_do_not_count(self):
        prs = [{"number": n, "title": f"FIX-{n}: x", "state": "MERGED", "createdAt": "2026-11-10T22:00:00Z"}
               for n in range(5)]
        self.assertTrue(self.gate(prs)[0])

    def test_red_main_admits_only_repair_pull_requests(self):
        prs = [{"number": 1, "title": "CW-001: a", "labels": labels("swarm"), "isCrossRepository": False},
               {"number": 2, "title": "FIX-5: b", "labels": labels("swarm"), "isCrossRepository": False}]
        with mock.patch.object(swarm, "gh", FakeGh(prs=prs)):
            self.assertEqual(swarm.plan_integration(red=True), [2])
            self.assertEqual(swarm.plan_integration(red=False), [1, 2])

    def test_main_is_red_reads_the_latest_finished_run(self):
        for conclusion, expected in (("failure", True), ("success", False), (None, False)):
            payload = json.dumps([{"conclusion": conclusion}] if conclusion else [])
            with mock.patch.object(swarm, "gh", lambda *a, check=True, p=payload: p):
                self.assertEqual(swarm.main_is_red(), expected)


class GitTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                    "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t", "GH_TOKEN": "x"}
        patcher = mock.patch.dict(os.environ, self.env)
        patcher.start()
        self.addCleanup(patcher.stop)
        cwd = os.getcwd()
        self.addCleanup(os.chdir, cwd)
        self.origin, self.repo = self.tmp / "origin.git", self.tmp / "repo"
        self.run_git("init", "-q", "--bare", "-b", "main", str(self.origin), cwd=self.tmp)
        self.run_git("clone", "-q", str(self.origin), str(self.repo), cwd=self.tmp)
        (self.repo / "a.txt").write_text("base\n")
        (self.repo / "TODO.md").write_text(TASKS)
        for args in (["add", "-A"], ["commit", "-qm", "init"], ["push", "-q", "origin", "main"]):
            self.run_git(*args)
        os.chdir(self.repo)

    def run_git(self, *args, cwd=None):
        return subprocess.run(["git", *args], cwd=cwd or self.repo, check=True, capture_output=True,
                              text=True, env=self.env).stdout

    def commit_branch(self, name, filename="f.txt", text="feature\n", pr_ref=None):
        self.run_git("switch", "-q", "-c", name)
        (self.repo / filename).write_text(text)
        self.run_git("add", "-A")
        self.run_git("commit", "-qm", "change")
        if pr_ref:
            self.run_git("push", "-q", "origin", f"HEAD:refs/pull/{pr_ref}/head")
        self.run_git("switch", "-q", "main")

    def worker_dir(self, branch="swarm/CW-001-1", result="ok", checks="passed", extra=None):
        work = self.tmp / "work"
        self.run_git("clone", "-q", str(self.origin), str(work), cwd=self.tmp)
        base = self.run_git("rev-parse", "HEAD", cwd=work).strip()
        (work / "f.txt").write_text("feature\n")
        for name, text in (extra or {}).items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        self.run_git("add", "-A", cwd=work)
        self.run_git("commit", "-qm", "feature", cwd=work)
        out = self.tmp / "result"
        out.mkdir()
        self.run_git("bundle", "create", str(out / "result.bundle"), f"^{base}", "HEAD", cwd=work)
        for name, value in (("result", result), ("commits", "1"), ("checks", checks),
                            ("branch", branch), ("pr-notes.md", "Evidence")):
            (out / name).write_text(value)
        return out

    TASK = {"kind": "story", "id": "CW-001", "number": 0, "slot": 1, "title": "Staff", "branch": ""}

    def test_publish_pushes_the_bundle_and_opens_a_pull_request(self):
        out, fake = self.worker_dir(), FakeGh()
        with mock.patch.object(swarm, "gh", fake):
            swarm.publish(self.TASK, out)
        self.assertIn("refs/heads/swarm/CW-001-1", self.run_git("ls-remote", "--heads", "origin"))
        create = [c for c in fake.calls if c[:2] == ("pr", "create")][0]
        self.assertIn("Task: CW-001", create[create.index("--body") + 1])
        self.assertEqual(create[create.index("--title") + 1], "CW-001: Staff")
        self.assertNotIn("--draft", create)

    def test_failed_worker_still_publishes_its_work_as_a_draft(self):
        out, fake = self.worker_dir(result="timeout", checks="failed"), FakeGh()
        with mock.patch.object(swarm, "gh", fake), mock.patch.object(swarm, "release_claim"):
            swarm.publish(self.TASK, out)
        self.assertIn("refs/heads/swarm/CW-001-1", self.run_git("ls-remote", "--heads", "origin"))
        create = [c for c in fake.calls if c[:2] == ("pr", "create")][0]
        self.assertIn("--draft", create)
        body = create[create.index("--body") + 1]
        self.assertIn("did not finish cleanly", body)
        self.assertIn("agent result: timeout", body)

    def test_unresolved_merge_conflict_publishes_a_draft(self):
        out, fake = self.worker_dir(), FakeGh()
        (out / "merge").write_text("conflict")
        with mock.patch.object(swarm, "gh", fake):
            swarm.publish(self.TASK, out)
        create = [c for c in fake.calls if c[:2] == ("pr", "create")][0]
        self.assertIn("--draft", create)
        self.assertIn("unresolved conflicts", create[create.index("--body") + 1])

    def test_pull_request_failure_after_push_opens_an_issue(self):
        out = self.worker_dir()

        class NoPr(FakeGh):
            def __call__(self, *args, check=True):
                if args[:2] == ("pr", "create"):
                    raise subprocess.CalledProcessError(1, args)
                return super().__call__(*args, check=check)
        fake = NoPr()
        with mock.patch.object(swarm, "gh", fake), mock.patch.object(swarm, "release_claim"):
            swarm.publish(self.TASK, out)
        self.assertIn("refs/heads/swarm/CW-001-1", self.run_git("ls-remote", "--heads", "origin"))
        self.assertTrue(fake.said("issue", "create"))

    def test_publish_blocks_workflow_file_changes_and_releases_claim(self):
        out = self.worker_dir(extra={".github/workflows/x.yml": "name: x\n"})
        with mock.patch.object(swarm, "gh", FakeGh()), \
             mock.patch.object(swarm, "release_claim", return_value=True) as released:
            swarm.publish(self.TASK, out)
        self.assertTrue(released.called)
        self.assertNotIn("refs/heads/swarm/CW-001-1",
                         self.run_git("ls-remote", "--heads", "origin"))

    def test_publish_allows_workflow_changes_with_swarm_token(self):
        out = self.worker_dir(extra={".github/workflows/x.yml": "name: x\n"})
        with mock.patch.dict(os.environ, {"SWARM_WORKFLOW_PUSH": "true"}), \
             mock.patch.object(swarm, "gh", FakeGh()), \
             mock.patch.object(swarm, "release_claim") as released:
            swarm.publish(self.TASK, out)
        self.assertFalse(released.called)
        self.assertIn("refs/heads/swarm/CW-001-1",
                      self.run_git("ls-remote", "--heads", "origin"))

    def test_publish_twice_does_not_reuse_a_stale_incoming_ref(self):
        with mock.patch.object(swarm, "gh", FakeGh()):
            first = self.worker_dir(branch="swarm/CW-001-1")
            swarm.publish(self.TASK, first)
            # A second slot publishes a different bundle into the same trusted
            # checkout. Its own branch is new, so the push is always a
            # fast-forward; only the local swarm-incoming ref is reused.
            shutil.rmtree(self.tmp / "work")
            shutil.rmtree(first)
            second = self.worker_dir(branch="swarm/CW-001-2")
            swarm.publish(self.TASK, second)
        heads = self.run_git("ls-remote", "--heads", "origin")
        self.assertIn("refs/heads/swarm/CW-001-2", heads)

    def test_failed_local_checks_open_a_draft(self):
        out, fake = self.worker_dir(checks="failed"), FakeGh()
        with mock.patch.object(swarm, "gh", fake):
            swarm.publish(self.TASK, out)
        self.assertIn("--draft", [c for c in fake.calls if c[:2] == ("pr", "create")][0])

    def test_publish_refuses_foreign_or_mismatched_branches(self):
        for branch in ("main", "swarm/CW-009-1"):
            out = self.worker_dir(branch=branch)
            shutil.rmtree(self.tmp / "work")
            with mock.patch.object(swarm, "gh", FakeGh()), self.assertRaises(SystemExit):
                swarm.publish(self.TASK, out)
            shutil.rmtree(out)

    def test_missing_artifact_publishes_nothing(self):
        fake = FakeGh()
        with mock.patch.object(swarm, "gh", fake):
            swarm.publish(self.TASK, self.tmp / "none")
        self.assertFalse(fake.said("pr", "create"))

    def test_merge_local_merges_and_reports_the_task(self):
        self.commit_branch("b", pr_ref=9)
        fake = FakeGh(pr_view={"headRefName": "swarm/CW-001-1", "title": "t"})
        with mock.patch.object(swarm, "gh", fake):
            sha, task, files = swarm.merge_local(9)
        self.assertEqual((task, files), ("CW-001", []))
        self.assertTrue((self.repo / "f.txt").exists())
        self.assertEqual(sha, self.run_git("rev-parse", "pr-9").strip())

    def test_merge_local_reports_conflicts_and_leaves_main_clean(self):
        self.commit_branch("b", filename="a.txt", text="from pr\n", pr_ref=9)
        (self.repo / "a.txt").write_text("from main\n")
        self.run_git("commit", "-qam", "main change")
        fake = FakeGh(pr_view={"headRefName": "swarm/CW-001-1", "title": "t"})
        with mock.patch.object(swarm, "gh", fake):
            sha, _, files = swarm.merge_local(9)
        self.assertEqual((sha, files), (None, ["a.txt"]))
        self.assertEqual(self.run_git("status", "--porcelain"), "")

    def test_finalize_records_the_merge_and_publishes_main(self):
        fake = FakeGh(pr_view={"headRefOid": "abc", "body": "Task: CW-001\n\nEvidence"})
        with mock.patch.object(swarm, "gh", fake):
            swarm.finalize(9, "CW-001", "abc")
        tasks = {t["id"]: t for t in swarm.parse_tasks((self.repo / "TODO.md").read_text())}
        self.assertEqual((tasks["CW-001"]["status"], tasks["CW-001"]["pr"]), ("done", "#9"))
        self.assertIn("integrator ran", tasks["CW-001"]["evidence"])
        self.assertIn("Evidence", (self.repo / "docs/swarm/CW-001.md").read_text())
        pushed = self.run_git("--git-dir", str(self.origin), "rev-parse", "main").strip()
        self.assertEqual(self.run_git("rev-parse", "HEAD").strip(), pushed)
        self.assertFalse(fake.said("workflow", "run", "heavy-check.yml"))

    def origin_todo(self):
        return self.run_git("--git-dir", str(self.origin), "show", "main:TODO.md")

    def test_update_todo_commits_and_pushes_the_marks(self):
        pushed = swarm.update_todo(lambda text: swarm.set_task_status(text, "CW-001", "taken", agent="swarm-1"),
                                   "Take 1 task(s) for the swarm")
        self.assertTrue(pushed)
        task = {t["id"]: t for t in swarm.parse_tasks(self.origin_todo())}["CW-001"]
        self.assertEqual((task["status"], task["agent"]), ("taken", "swarm-1"))
        self.assertFalse(swarm.update_todo(lambda text: text, "nothing"))

    def test_update_todo_reapplies_its_edit_when_main_moved(self):
        other = self.tmp / "other"
        self.run_git("clone", "-q", str(self.origin), str(other), cwd=self.tmp)
        done = swarm.set_task_status((other / "TODO.md").read_text(), "CW-002", "done", pr="#5")
        (other / "TODO.md").write_text(done)
        for args in (["add", "-A"], ["commit", "-qm", "someone else"], ["push", "-q", "origin", "main"]):
            self.run_git(*args, cwd=other)
        swarm.update_todo(lambda text: swarm.set_task_status(text, "CW-001", "taken", agent="swarm-2"),
                          "Take 1 task(s) for the swarm")
        tasks = {t["id"]: t for t in swarm.parse_tasks(self.origin_todo())}
        self.assertEqual((tasks["CW-001"]["status"], tasks["CW-002"]["status"]), ("taken", "done"))

    def test_a_worker_that_published_nothing_releases_its_task(self):
        swarm.update_todo(lambda text: swarm.set_task_status(text, "CW-001", "taken", agent="swarm-1"), "take")
        task = {"kind": "story", "id": "CW-001", "slot": 1, "branch": "", "number": 0}
        empty = self.tmp / "empty"
        empty.mkdir()
        with mock.patch.object(swarm, "gh", FakeGh()):
            swarm.publish(task, empty)
        got = {t["id"]: t for t in swarm.parse_tasks(self.origin_todo())}["CW-001"]
        self.assertEqual((got["status"], got["agent"]), ("todo", ""))

    def test_finalize_refuses_a_branch_that_changed_after_testing(self):
        fake = FakeGh(pr_view={"headRefOid": "new", "body": ""})
        with mock.patch.object(swarm, "gh", fake), self.assertRaises(SystemExit):
            swarm.finalize(9, "CW-001", "old")

    def test_finalize_handles_repair_tasks_that_are_not_in_the_file(self):
        fake = FakeGh(pr_view={"headRefOid": "abc", "body": "Task: FIX-5"})
        with mock.patch.object(swarm, "gh", fake):
            swarm.finalize(11, "FIX-5", "abc")
        self.assertIn("FIX-5", (self.repo / "docs/swarm/FIX-5.md").read_text())
        self.assertEqual((self.repo / "TODO.md").read_text().count("[x]"), 1)


class BudgetTests(unittest.TestCase):
    def test_monthly_minutes_sum_only_this_months_runs(self):
        now = datetime.datetime(2026, 11, 19, 12, 0, tzinfo=datetime.timezone.utc)
        runs = [{"createdAt": "2026-11-02T00:00:00Z", "updatedAt": "2026-11-02T00:02:00Z"},
                {"createdAt": "2026-10-30T00:00:00Z", "updatedAt": "2026-10-30T01:00:00Z"},
                {"createdAt": "2026-11-18T00:00:00Z", "updatedAt": "2026-11-18T00:00:20Z"}]
        class Gh(FakeGh):
            def __call__(self, *args, check=True):
                self.calls.append(args)
                if args[:2] == ("run", "list") and "--json" in args:
                    return json.dumps(runs)
                return ""
        with mock.patch.object(swarm, "gh", Gh()):
            minutes = swarm.monthly_minutes(now)
        # 2 min + max(1 min, 20s) = 3 min per workflow, October ignored,
        # five budgeted workflows queried = 15 billed minutes this month.
        self.assertEqual(minutes, 15.0)

    def test_budget_gate_allows_well_under_cap(self):
        with mock.patch.object(swarm, "monthly_minutes", return_value=100):
            allowed, why = swarm.budget_gate(1500)
        self.assertTrue(allowed)
        self.assertIn("starting", why)

    def test_budget_gate_refuses_and_opens_an_issue_near_cap(self):
        class Gh(FakeGh):
            def __call__(self, *args, check=True):
                self.calls.append(args)
                return ""
        with mock.patch.object(swarm, "monthly_minutes", return_value=1400), \
             mock.patch.object(swarm, "gh", Gh()) as fake:
            allowed, why = swarm.budget_gate(1500)
        self.assertFalse(allowed)
        self.assertIn("pausing", why)
        self.assertTrue(fake.said("issue", "create"))


class TwoTierTests(unittest.TestCase):
    def test_run_gate_uses_a_token_free_environment(self):
        with mock.patch.dict(os.environ, {"GH_TOKEN": "secret", "GITHUB_TOKEN": "secret"}), \
             mock.patch.object(swarm.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, "ok", "")
            ok, log = swarm.run_gate("scripts/quick-check.sh")
        self.assertTrue(ok)
        self.assertEqual(log, "ok")
        env = run.call_args.kwargs["env"]
        self.assertNotIn("GH_TOKEN", env)
        self.assertNotIn("GITHUB_TOKEN", env)

    def test_integrate_batch_pushes_once(self):
        self.repo = Path(tempfile.mkdtemp())
        (self.repo / "TODO.md").write_text("# TODO\n")
        self.addCleanup(shutil.rmtree, self.repo)
        original = Path.cwd()
        os.chdir(self.repo)
        self.addCleanup(os.chdir, original)

        class Gh(FakeGh):
            def __init__(self):
                super().__init__(pr_view={"headRefOid": "abc", "body": "Task: CW-001"})
            def __call__(self, *args, check=True):
                if args[:2] == ("pr", "view") and "--json" in args and "headRefOid" in args:
                    self.calls.append(args)
                    return json.dumps({"headRefOid": "abc"})
                if args[:2] == ("pr", "diff"):
                    self.calls.append(args)
                    return "apps/api/A.java\n"
                if args[:2] == ("pr", "view") and "--jq" in args:
                    self.calls.append(args)
                    return ""
                if args[:2] == ("pr", "list"):
                    self.calls.append(args)
                    return json.dumps([])
                return super().__call__(*args, check=check)
        with mock.patch.object(swarm, "gh", Gh()) as fake, \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
             mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
             mock.patch.object(swarm, "git"), mock.patch.object(swarm, "authed_git"):
            count = swarm.integrate_batch([1, 2], "scripts/quick-check.sh")
        self.assertEqual(count, 2)
        pushes = [c for c in fake.calls if c[:2] == ("workflow", "run")
                  and c[2] == "delivery.yml"]
        self.assertEqual(len(pushes), 1)

    def test_integrate_batch_retries_when_main_advanced(self):
        self.repo = Path(tempfile.mkdtemp())
        (self.repo / "TODO.md").write_text("# TODO\n")
        self.addCleanup(shutil.rmtree, self.repo)
        original = Path.cwd()
        os.chdir(self.repo)
        self.addCleanup(os.chdir, original)

        class Gh(FakeGh):
            def __call__(self, *args, check=True):
                if args[:2] == ("pr", "view") and "--jq" not in args:
                    self.calls.append(args)
                    return json.dumps({"headRefOid": "abc", "body": "Task: CW-001"})
                if args[:2] == ("pr", "diff"):
                    self.calls.append(args); return "apps/api/A.java\n"
                if args[:2] == ("pr", "list"):
                    self.calls.append(args); return json.dumps([])
                return super().__call__(*args, check=check)

        push_attempts = {"n": 0}
        def authed(*args, check=True):
            if args[:2] == ("push", "origin"):
                push_attempts["n"] += 1
                if push_attempts["n"] == 1:
                    raise subprocess.CalledProcessError(1, args, "", "fetch first")
            return ""

        with mock.patch.object(swarm, "gh", Gh()), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
             mock.patch.object(swarm, "run_gate", return_value=(True, "ok")) as gate, \
             mock.patch.object(swarm, "git") as gitmock, \
             mock.patch.object(swarm, "authed_git", side_effect=authed) as authedmock:
            count = swarm.integrate_batch([1], "scripts/quick-check.sh")
        self.assertEqual(count, 1)
        self.assertEqual(push_attempts["n"], 2)
        self.assertTrue(any(c.args[:2] == ("fetch", "origin") for c in authedmock.call_args_list))
        self.assertGreaterEqual(gate.call_count, 2)
        self.assertTrue(any(c.args[:3] == ("reset", "--soft", "origin/main")
                            for c in gitmock.call_args_list))

    def test_integrate_batch_restores_trackers_on_retry(self):
        self.repo = Path(tempfile.mkdtemp())
        (self.repo / "TODO.md").write_text("# TODO\n")
        self.addCleanup(shutil.rmtree, self.repo)
        original = Path.cwd()
        os.chdir(self.repo)
        self.addCleanup(os.chdir, original)

        class Gh(FakeGh):
            def __call__(self, *args, check=True):
                if args[:2] == ("pr", "view") and "--jq" not in args:
                    self.calls.append(args)
                    return json.dumps({"headRefOid": "abc", "body": "Task: CW-001"})
                if args[:2] == ("pr", "diff"):
                    self.calls.append(args); return "apps/api/A.java\n"
                if args[:2] == ("pr", "list"):
                    self.calls.append(args); return json.dumps([])
                return super().__call__(*args, check=check)

        def authed(*args, check=True):
            if args[:2] == ("push", "origin"):
                raise subprocess.CalledProcessError(1, args, "", "fetch first")
            return ""

        def gitmock(*args, check=True):
            return ""

        with mock.patch.object(swarm, "gh", Gh()), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
             mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
             mock.patch.object(swarm, "git", side_effect=gitmock) as gitcalls, \
             mock.patch.object(swarm, "authed_git", side_effect=authed):
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.integrate_batch([1], "scripts/quick-check.sh")
        # Reconciliation adopts the fresh trackers rather than rebasing.
        self.assertTrue(any(c.args[:3] == ("checkout", "origin/main", "--")
                            for c in gitcalls.call_args_list))
        self.assertFalse(any(c.args[0] == "rebase" for c in gitcalls.call_args_list))

    def test_guard_still_blocks_module_check_scripts(self):
        fake = FakeGh(diff="apps/staff-web/check.sh\n")
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9), ["apps/staff-web/check.sh"])
        self.assertTrue(fake.said("labels[]=needs-human"))

    def test_efficiency_doc_and_quick_script_exist(self):
        self.assertTrue((ROOT / "docs/swarm/EFFICIENCY.md").exists())
        self.assertTrue((ROOT / "scripts/quick-check.sh").exists())
        self.assertFalse((ROOT / ".github/workflows/heavy-check.yml").exists())


if __name__ == "__main__":
    unittest.main()
