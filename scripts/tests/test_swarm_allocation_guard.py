"""Exercise the swarm workflow's answer guards and empty-allocation wave path.

The PO and SM answers are parsed by shell inside YAML ``run`` blocks. Reading
the YAML text alone cannot prove that an explicitly empty ``tasks=`` line is
distinguished from a missing line, so these tests execute the exact shell
blocks CI executes against synthetic files in a temporary directory. No agent,
Docker, GitHub token or network is involved.
"""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

from helpers import ROOT

WORKFLOW = ROOT / ".github" / "workflows" / "swarm.yml"
CANDIDATES = "FU-1747-02,FU-1747-03"
GUARD_STEPS = {
    "SELECTION": ("select", "run"),
    "ALLOCATION": ("allocate", "run"),
    "PLAN-LAUNCH": ("plan", "plan"),
    "PLAN-EMPTY": ("plan", "plan"),
    "PLAN-REASON": ("plan", "plan-reason"),
}


class SwarmAnswerGuardTest(unittest.TestCase):
    """Run the checked-in answer parsers with controlled agent output."""

    text = WORKFLOW.read_text(encoding="utf-8")

    def guard(self, name):
        """Return the shell between exactly one pair of guard markers."""
        config = yaml.safe_load(self.text)
        job_id, step_id = GUARD_STEPS[name]
        step = next(s for s in config["jobs"][job_id]["steps"] if s.get("id") == step_id)
        start, end = f"# {name}-GUARD-BEGIN", f"# {name}-GUARD-END"
        self.assertEqual(step["run"].count(start), 1)
        self.assertEqual(step["run"].count(end), 1)
        block = step["run"].split(start, 1)[1].split(end, 1)[0]
        for forbidden in ("docker", "opencode", "$OPENCODE_API_KEY", "rc=$?", "${{"):
            self.assertNotIn(forbidden, block)
        return "\n".join(line.strip() for line in block.splitlines() if line.strip())

    def run_guard(self, name, answer, candidates=CANDIDATES, open_task=True):
        """Execute one guard in a fake checkout and return its files and status."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".agent").mkdir()
            (root / "tasks").mkdir()
            (root / "tasks" / "taken.md").write_text("- [-] FU-1747-01 · taken\n", encoding="utf-8")
            if open_task:
                (root / "tasks" / "open.md").write_text("- [ ] FU-1747-02 · open\n", encoding="utf-8")
            filename = "allocated-tasks.txt" if name == "ALLOCATION" else "selected-feature.txt"
            if answer is not None:
                (root / ".agent" / filename).write_text(answer, encoding="utf-8")
            output, summary = root / "output.txt", root / "summary.txt"
            output.touch()
            summary.touch()
            env = {
                "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                "LC_ALL": "C",
                "GITHUB_OUTPUT": str(output),
                "GITHUB_STEP_SUMMARY": str(summary),
                "CANDIDATES": candidates,
                "FEATURE": "FU-1747",
                "WORKER_COUNT": "2",
            }
            result = subprocess.run(["bash", "-c", self.guard(name)], cwd=root,
                                    env=env, capture_output=True, text=True, check=False)
            return result, output.read_text(encoding="utf-8"), summary.read_text(encoding="utf-8")

    def test_missing_allocation_line_is_an_error_with_open_tasks(self):
        result, output, _ = self.run_guard("ALLOCATION", "prose only\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::OpenCode returned no tasks=", result.stderr)
        self.assertNotIn("task_ids=", output)
        self.assertNotIn("::notice::", result.stderr)

    def test_missing_allocation_line_is_an_error_without_open_tasks(self):
        result, output, _ = self.run_guard("ALLOCATION", "", open_task=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::OpenCode returned no tasks=", result.stderr)
        self.assertNotIn("task_ids=", output)

    def test_explicit_empty_allocation_notices_success_and_empty_output(self):
        result, output, summary = self.run_guard("ALLOCATION", "tasks=\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("::notice::No non-overlapping task", result.stderr)
        self.assertIn(CANDIDATES, result.stderr)
        self.assertIn("no non-overlapping tasks", summary)
        self.assertIn(CANDIDATES, summary)
        self.assertIn("task_ids=\n", output)
        self.assertIn("allocated=true\n", output)

    def test_empty_allocation_succeeds_without_open_tasks_too(self):
        result, output, _ = self.run_guard("ALLOCATION", "tasks=\n", open_task=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("task_ids=\n", output)

    def test_last_empty_allocation_line_wins_over_earlier_ids(self):
        result, output, _ = self.run_guard("ALLOCATION", "tasks=FU-1747-02\ntasks=\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("task_ids=\n", output)
        self.assertNotIn("task_ids=FU-1747-02", output)

    def test_malformed_allocation_line_is_an_error(self):
        result, output, _ = self.run_guard("ALLOCATION", "tasks=BAD!;x\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::OpenCode returned no tasks=", result.stderr)
        self.assertNotIn("task_ids=", output)

    def test_non_empty_allocation_keeps_ids_without_empty_wave_annotation(self):
        result, output, summary = self.run_guard("ALLOCATION", "tasks=FU-1747-05\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("task_ids=FU-1747-05\n", output)
        self.assertIn("allocated=true\n", output)
        self.assertNotIn("::notice::", result.stderr)
        self.assertNotIn("no non-overlapping tasks", summary)

    def test_missing_selection_lines_are_an_error(self):
        result, output, _ = self.run_guard("SELECTION", "prose only\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::OpenCode returned no feature=/tasks=", result.stderr)
        self.assertNotIn("task_ids=", output)

    def test_partial_selection_lines_are_an_error(self):
        for answer in ("feature=FU-1747\n", "tasks=FU-1747-02\n",
                       "tasks=BAD!\n", "feature=FU-1747\ntasks=BAD!;x\n"):
            with self.subTest(answer=answer):
                result, output, _ = self.run_guard("SELECTION", answer)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("::error::OpenCode returned no feature=/tasks=", result.stderr)
                self.assertNotIn("task_ids=", output)

    def test_explicit_empty_selection_succeeds_with_empty_outputs(self):
        result, output, _ = self.run_guard("SELECTION", "feature=\ntasks=\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("::notice::", result.stderr)
        self.assertIn("feature=\n", output)
        self.assertIn("task_ids=\n", output)

    def test_empty_feature_with_candidates_still_succeeds(self):
        result, output, _ = self.run_guard("SELECTION", "feature=\ntasks=FU-1747-02\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("feature=\n", output)
        self.assertIn("task_ids=FU-1747-02\n", output)

    def test_two_features_with_empty_tasks_keeps_both_answers(self):
        """A comma-separated feature list and an explicit empty task list coexist."""
        result, output, _ = self.run_guard("SELECTION", "feature=FU-1747,PRINT\ntasks=\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("feature=FU-1747,PRINT\n", output)
        self.assertIn("task_ids=\n", output)

    def test_non_empty_selection_keeps_feature_and_ids(self):
        result, output, _ = self.run_guard("SELECTION", "feature=FU-1747\ntasks=FU-1747-02\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("feature=FU-1747\n", output)
        self.assertIn("task_ids=FU-1747-02\n", output)


class SwarmEmptyWaveTest(unittest.TestCase):
    """An explicit empty allocation resolves to zero workers, not PO candidates."""

    text = WORKFLOW.read_text(encoding="utf-8")

    def guard(self, name):
        config = yaml.safe_load(self.text)
        job_id, step_id = GUARD_STEPS[name]
        step = next(s for s in config["jobs"][job_id]["steps"] if s.get("id") == step_id)
        start, end = f"# {name}-GUARD-BEGIN", f"# {name}-GUARD-END"
        self.assertEqual(step["run"].count(start), 1)
        self.assertEqual(step["run"].count(end), 1)
        return step["run"].split(start, 1)[1].split(end, 1)[0].strip()

    def run_plan_guards(self, allocated="", result="success", launch="", fallback="",
                        task=""):
        """Execute the plan step's launch resolution and empty/failure guard."""
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp, "output.txt")
            output.touch()
            env = {
                "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                "LC_ALL": "C",
                "GITHUB_OUTPUT": str(output),
                "ALLOCATION_DONE": allocated,
                "ALLOCATE_RESULT": result,
                "LAUNCH_TASK": launch,
                "FALLBACK_TASK": fallback,
                "INPUT_TASK": task,
            }
            body = (self.guard("PLAN-LAUNCH")
                    + '\necho "resolved=$LAUNCH_TASK" >> "$GITHUB_OUTPUT"\n'
                    + self.guard("PLAN-EMPTY"))
            proc = subprocess.run(["bash", "-c", body], cwd=tmp, env=env,
                                  capture_output=True, text=True, check=False)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            return output.read_text(encoding="utf-8")

    def test_empty_allocation_does_not_fall_back_to_overlapping_candidates(self):
        output = self.run_plan_guards(allocated="true", launch="",
                                       fallback="FU-1747-02")
        self.assertIn("resolved=", output)
        self.assertIn('matrix={"include":[]}', output)
        self.assertIn("count=0\n", output)
        self.assertIn("reason=allocation_empty\n", output)

    def test_real_allocation_is_passed_to_the_planner(self):
        output = self.run_plan_guards(allocated="true", launch="FU-1747-05",
                                       fallback="FU-1747-02")
        self.assertIn("resolved=FU-1747-05", output)
        self.assertNotIn("reason=", output)

    def test_skipped_allocator_may_use_the_po_shortlist(self):
        output = self.run_plan_guards(allocated="", result="skipped",
                                       fallback="FU-1747-02")
        self.assertIn("resolved=FU-1747-02", output)
        self.assertNotIn("reason=", output)

    def test_failed_allocator_fails_closed_without_a_wave(self):
        output = self.run_plan_guards(allocated="", result="failure",
                                       fallback="FU-1747-02")
        self.assertIn("resolved=", output)
        self.assertIn("reason=allocation_failed\n", output)
        self.assertIn("count=0\n", output)

    def test_explicit_dispatch_task_outweighs_an_empty_allocation(self):
        output = self.run_plan_guards(allocated="true", launch="",
                                       fallback="FU-1747-02", task="MANUAL-1")
        self.assertIn("resolved=MANUAL-1", output)
        self.assertNotIn("reason=", output)

    def test_manual_task_survives_a_failed_allocator(self):
        output = self.run_plan_guards(allocated="", result="failure", task="MANUAL-1")
        self.assertIn("resolved=MANUAL-1", output)
        self.assertNotIn("reason=", output)

    def run_plan_reason(self, reason):
        """Execute the wave-summary step with a fixed planner reason."""
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp, "summary.txt")
            summary.touch()
            env = {
                "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                "LC_ALL": "C",
                "GITHUB_STEP_SUMMARY": str(summary),
                "PLAN_REASON": reason,
            }
            proc = subprocess.run(["bash", "-c", self.guard("PLAN-REASON")],
                                  cwd=tmp, env=env, capture_output=True,
                                  text=True, check=False)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            return summary.read_text(encoding="utf-8")

    def test_plan_reason_summary_distinguishes_empty_allocation(self):
        empty = self.run_plan_reason("allocation_empty")
        self.assertIn("no non-overlapping tasks were available; no worker started", empty)
        other = self.run_plan_reason("images_not_ready")
        self.assertIn("Swarm wave skipped: images_not_ready", other)
        self.assertNotIn("no non-overlapping tasks", other)

    def test_worker_and_next_wave_skip_an_empty_wave(self):
        config = yaml.safe_load(self.text)
        for name in ("worker", "loop"):
            with self.subTest(job=name):
                self.assertIn("fromJSON(needs.plan.outputs.count || '0') > 0",
                              config["jobs"][name]["if"])
        self.assertIn("needs.plan.result == 'success'", config["jobs"]["integrate"]["if"])

    def test_plan_wiring_uses_the_allocation_presence_signal(self):
        config = yaml.safe_load(self.text)
        allocate = config["jobs"]["allocate"]
        self.assertIn("needs.select.outputs.feature != ''", allocate["if"])
        self.assertEqual(allocate["outputs"]["allocated"],
                         "${{ steps.run.outputs.allocated }}")
        plan = next(s for s in config["jobs"]["plan"]["steps"] if s.get("id") == "plan")
        self.assertEqual(plan["env"]["LAUNCH_TASK"],
                         "${{ needs.allocate.outputs.task_ids }}")
        self.assertEqual(plan["env"]["ALLOCATION_DONE"],
                         "${{ needs.allocate.outputs.allocated }}")
        self.assertIn('--task "$LAUNCH_TASK"', plan["run"])
        reason = next(s for s in config["jobs"]["plan"]["steps"]
                      if s.get("id") == "plan-reason")
        self.assertIn("allocation_empty", reason["run"])


if __name__ == "__main__":
    unittest.main()
