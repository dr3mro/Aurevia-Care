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
        for name, job in (("swarm-integrator.yml", "integrate"), ("main-repair.yml", "validate")):
            config = yaml.safe_load((ROOT / ".github/workflows" / name).read_text())
            perms = config["jobs"][job]["permissions"]
            self.assertEqual(perms.get("issues"), "write", f"{name} {job}")

    def test_labels_use_rest_not_graphql_pr_edit(self):
        source = (ROOT / "scripts/swarm.py").read_text()
        self.assertNotIn('"pr", "edit"', source)
        self.assertIn('"api", "--method", "POST"', source)

    def test_gh_failure_surfaces_stderr(self):
        with mock.patch.object(swarm.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, "", "permission denied")
            stream = io.StringIO()
            with redirect_stderr(stream), self.assertRaises(subprocess.CalledProcessError):
                swarm.gh("pr", "edit", "30", "--add-label", "needs-fix")
        self.assertIn("permission denied", stream.getvalue())

    def test_integrator_only_runs_for_successful_swarm_waves(self):
        text = (ROOT / ".github/workflows/swarm-integrator.yml").read_text()
        pick = text.split("  pick:\n", 1)[1].split("  integrate:\n", 1)[0]
        self.assertIn("github.event.workflow_run.conclusion == 'success'", pick)

    def test_integrator_gate_is_the_full_check(self):
        text = (ROOT / ".github/workflows/swarm-integrator.yml").read_text()
        self.assertIn("GATE: scripts/check.sh", text)
        self.assertIn("postgres:", text)
        self.assertIn("SMARTCLINIC_PG_ADMIN: postgres", text)
        self.assertNotIn("GATE: scripts/quick-check.sh", text)

    def test_repair_watches_fix_prs_only(self):
        workflows = ROOT / ".github/workflows"
        self.assertFalse((workflows / "main-watchdog.yml").exists())
        self.assertFalse((workflows / "fix-pr-watchdog.yml").exists())
        text = (workflows / "main-repair.yml").read_text()
        self.assertIn("pull_request:", text)
        self.assertNotIn("workflow_run:", text)
        self.assertNotIn("cron:", text)
        self.assertIn("autofix", text)
        self.assertIn("scripts/check.sh", text)
        self.assertIn("swarm-integrator.yml", text)
        self.assertIn("github.event.pull_request.head.ref", text)
        self.assertIn("gh pr ready", text)
        self.assertIn("api", text)

    def test_budget_workflows_include_single_repair_workflow(self):
        self.assertIn("main-repair.yml", swarm.BUDGET_WORKFLOWS)
        self.assertNotIn("fix-pr-watchdog.yml", swarm.BUDGET_WORKFLOWS)
        self.assertNotIn("main-watchdog.yml", swarm.BUDGET_WORKFLOWS)

    def test_worker_has_three_hour_budget_with_restart_slack(self):
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        worker = text.split("  worker:\n", 1)[1].split("  publish:\n", 1)[0]
        self.assertIn("timeout-minutes: 185", worker)
        self.assertIn("AGENT_TIMEOUT: 175m", worker)

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
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        publish = text.split("  publish:\n", 1)[1].split("  next:\n", 1)[0]
        self.assertIn("SWARM_WORKFLOW_PUSH: ${{ secrets.SWARM_TOKEN != '' }}", publish)

    def test_fallback_wave_runs_hourly(self):
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertIn("cron: '7 * * * *'", text)
        self.assertNotIn("cron: '7 */3 * * *'", text)

    def test_wave_defaults_to_ten_workers(self):
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertRegex(text, r"default: '10'")

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
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertIn("OPENCODE_API_KEY_{0}", text)
        self.assertNotIn("OPENCODE_API_KEY:", (ROOT / ".github/agent-image/Dockerfile.slot").read_text())

    def test_swarm_slot_secret_falls_back_to_team_secret(self):
        text = (ROOT / ".github/workflows/swarm.yml").read_text()
        self.assertIn("secrets[format('OPENCODE_API_KEY_{0}', matrix.slot)]", text)
        self.assertIn("secrets.OPENCODE_API_KEY", text)

    def test_worker_script_marks_checkout_safe(self):
        text = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertIn("git config --global --add safe.directory", text)


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
            first = self.worker_dir()
            swarm.publish(self.TASK, first)
            # A second slot publishes from a different bundle into the same
            # trusted checkout; the local ref must be recreated, not reused.
            shutil.rmtree(self.tmp / "work")
            shutil.rmtree(first)
            second = self.worker_dir()
            swarm.publish(self.TASK, second)
        self.assertIn("refs/heads/swarm/CW-001-1", self.run_git("ls-remote", "--heads", "origin"))

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
        self.assertTrue(fake.said("workflow", "run", "heavy-check.yml"))

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
        # six budgeted workflows queried = 18 billed minutes this month.
        self.assertEqual(minutes, 18.0)

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

    def test_integrate_batch_pushes_once_and_dispatches_heavy(self):
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
                  and c[2] == "heavy-check.yml"]
        self.assertEqual(len(pushes), 1)

    def test_guard_still_blocks_module_check_scripts(self):
        fake = FakeGh(diff="apps/staff-web/check.sh\n")
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.guard(9), ["apps/staff-web/check.sh"])
        self.assertTrue(fake.said("labels[]=needs-human"))

    def test_efficiency_doc_and_quick_script_exist(self):
        self.assertTrue((ROOT / "docs/swarm/EFFICIENCY.md").exists())
        self.assertTrue((ROOT / "scripts/quick-check.sh").exists())
        heavy = (ROOT / ".github/workflows/heavy-check.yml").read_text()
        self.assertIn("owasp-nvd", heavy)
        self.assertIn("check.sh", heavy)


if __name__ == "__main__":
    unittest.main()
