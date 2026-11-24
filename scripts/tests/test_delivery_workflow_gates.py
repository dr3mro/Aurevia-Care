"""Delivery must fail closed on Dependabot approval and run canonical coverage gates.

Both regressions were invisible in a green build: Dependabot's approval step
exited zero when the human label was missing, so the next step merged anyway,
and Delivery's frontend job called a policy script whose ``npm test`` has no
coverage threshold while its tooling job ran plain unittest. Synthetic
fixtures prove the detectors distinguish the broken shapes from the fixes;
the real workflows are then asserted so a YAML-only regression fails too.
"""
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"
AUTO_MERGE = WORKFLOWS / "dependabot-auto-merge.yml"
DELIVERY = WORKFLOWS / "delivery.yml"


def steps(text):
    """Parse a synthetic workflow step list into step dictionaries."""
    return yaml.safe_load(text)


def run_text(step):
    """Return a step's ``run:`` block as text, or an empty string."""
    body = step.get("run", "")
    return body if isinstance(body, str) else ""


def commands(step_list):
    """Return every ``run:`` block of a step list as a list of strings."""
    return [run_text(step) for step in step_list]


FAIL_OPEN = """
- name: Require an explicit human approval
  run: |
    if ! gh pr view "$PR_URL" --json labels | grep -qx 'dependency-approved'; then
      echo "needs the dependency-approved label"
      exit 0
    fi
"""
FAIL_CLOSED = FAIL_OPEN.replace("exit 0", "exit 1")
NO_GUARD = """
- name: Enable auto-merge
  run: gh pr merge --auto --squash "$PR_URL"
"""
WITH_GUARD = """
- name: Enable auto-merge
  if: contains(github.event.pull_request.labels.*.name, 'dependency-approved')
  run: gh pr merge --auto --squash "$PR_URL"
"""
PLAIN_FRONTEND = """
- run: bash scripts/frontend-check.sh
"""
FIXED_FRONTEND = """
- run: bash scripts/frontend-check.sh
- run: bash apps/staff-web/check.sh
- run: bash apps/patient-mobile/check.sh
"""
PLAIN_TOOLING = """
- run: python3 -I -m unittest discover -s scripts/tests -q
"""
FIXED_TOOLING = """
- run: bash scripts/python-coverage.sh
"""


def fails_open(step_list):
    """Whether an approval step exits zero when the human label is missing."""
    return any("dependency-approved" in run_text(step) and "exit 0" in run_text(step)
               for step in step_list)


def merges_without_guard(step_list):
    """Whether ``gh pr merge --auto`` can run in a step with no own ``if:``."""
    return any("gh pr merge --auto" in run_text(step) and "if" not in step
               for step in step_list)


def lacks(runs, needle):
    """Whether none of the run commands invokes ``needle``."""
    return not any(needle in run for run in runs)


class WorkflowGateWiringTest(unittest.TestCase):
    def test_detectors_distinguish_both_regressions_from_their_fixes(self):
        with self.subTest(case="fail-open approval"):
            self.assertTrue(fails_open(steps(FAIL_OPEN)))
            self.assertFalse(fails_open(steps(FAIL_CLOSED)))
        with self.subTest(case="unguarded merge"):
            self.assertTrue(merges_without_guard(steps(NO_GUARD)))
            self.assertFalse(merges_without_guard(steps(WITH_GUARD)))
        with self.subTest(case="plain frontend"):
            plain, fixed = commands(steps(PLAIN_FRONTEND)), commands(steps(FIXED_FRONTEND))
            self.assertTrue(lacks(plain, "apps/staff-web/check.sh"))
            self.assertTrue(lacks(plain, "apps/patient-mobile/check.sh"))
            self.assertFalse(lacks(fixed, "apps/staff-web/check.sh"))
            self.assertFalse(lacks(fixed, "apps/patient-mobile/check.sh"))
        with self.subTest(case="plain tooling"):
            self.assertTrue(lacks(commands(steps(PLAIN_TOOLING)), "python-coverage.sh"))
            self.assertFalse(lacks(commands(steps(FIXED_TOOLING)), "python-coverage.sh"))

    def test_dependabot_approval_fails_closed_and_the_merge_is_guarded(self):
        job = yaml.safe_load(AUTO_MERGE.read_text(encoding="utf-8"))["jobs"]["auto-merge"]
        self.assertEqual(job["if"], "github.actor == 'dependabot[bot]'")
        self.assertFalse(fails_open(job["steps"]))
        self.assertFalse(merges_without_guard(job["steps"]))
        approval = [s for s in job["steps"]
                    if "dependency-approved" in run_text(s) and "grep -qx" in run_text(s)]
        self.assertEqual(len(approval), 1)
        self.assertIn("exit 1", run_text(approval[0]))

    def test_worker_publication_is_bound_to_an_isolated_validation_job(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-worker.yml").read_text(encoding="utf-8"))
        jobs = workflow["jobs"]
        validate = jobs["validate"]
        self.assertEqual(validate["permissions"], {"contents": "read"})
        self.assertNotIn("GH_TOKEN", validate.get("env", {}))
        publish = jobs["publish"]
        self.assertIn("validate", publish["needs"])
        published = "\n".join(run_text(step) for step in publish["steps"])
        self.assertIn("Candidate was not validated", published)
        self.assertIn("VALIDATED_SHA", published)

    def test_worker_validate_skips_a_no_change_result_but_fails_a_missing_bundle(self):
        """A worker with zero commits packs no bundle; validation must not go red."""
        workflow = yaml.safe_load((WORKFLOWS / "swarm-worker.yml").read_text(encoding="utf-8"))
        validate = workflow["jobs"]["validate"]
        candidate = next(s for s in validate["steps"] if s.get("id") == "candidate")
        gate = next(s for s in validate["steps"] if s.get("id") == "gate")
        body = run_text(candidate)
        self.assertIn('if [[ ! -s "$bundle" ]]', body)
        self.assertIn('commits=$(cat "results/worker-$TASK_ID/commits"', body)
        self.assertIn('if [[ "$commits" == "0" || -z "$commits" ]]', body)
        self.assertIn('echo "skip=true" >> "$GITHUB_OUTPUT"', body)
        self.assertIn("exit 1", body)  # commits reported but no bundle stays a hard failure
        self.assertEqual(gate["if"], "steps.candidate.outputs.skip != 'true'")

    def test_integrator_validate_job_has_no_nvd_consumer_key(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-integrator.yml").read_text(encoding="utf-8"))
        job = workflow["jobs"]["validate"]
        self.assertNotIn("NVD_API_KEY", job.get("env", {}))

    def test_swarm_reusable_workflows_receive_explicit_secrets(self):
        caller = (WORKFLOWS / "swarm.yml").read_text(encoding="utf-8")
        self.assertNotIn("secrets: inherit", caller)
        for name in ("swarm-worker.yml", "swarm-integrator.yml"):
            workflow = yaml.safe_load((WORKFLOWS / name).read_text(encoding="utf-8"))
            declared = workflow[True]["workflow_call"].get("secrets", {})
            self.assertIn("SWARM_TOKEN", declared, name)
            # GITHUB_TOKEN is system-reserved and available automatically;
            # declaring it in workflow_call is a GitHub validation error.
            self.assertNotIn("GITHUB_TOKEN", declared, name)

    def test_integrator_validation_has_no_write_token_and_publication_binds_shas(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-integrator.yml").read_text(encoding="utf-8"))
        jobs = workflow["jobs"]
        validate = jobs["validate"]
        self.assertEqual(validate["permissions"], {"contents": "read", "pull-requests": "read",
                                                  "actions": "read"})
        self.assertNotIn("SWARM_TOKEN", validate.get("env", {}).get("GH_TOKEN", ""))
        self.assertNotIn("NVD_API_KEY", validate.get("env", {}))
        validated = "\n".join(run_text(step) for step in validate["steps"])
        self.assertIn("integrate-validate", validated)
        self.assertNotIn("integrate-publish", validated)
        publish = jobs["publish"]
        self.assertIn("validate", publish["needs"])
        published = "\n".join(run_text(step) for step in publish["steps"])
        self.assertIn("integrate-publish", published)
        self.assertNotIn("integrate-validate", published)

    def test_delivery_frontend_runs_the_canonical_coverage_entry_points(self):
        workflow = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))
        runs = commands(workflow["jobs"]["frontend"]["steps"])
        self.assertFalse(lacks(runs, "apps/staff-web/check.sh"))
        self.assertFalse(lacks(runs, "apps/patient-mobile/check.sh"))

    def test_delivery_tooling_job_runs_the_coverage_gate(self):
        workflow = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))
        runs = commands(workflow["jobs"]["script-tests"]["steps"])
        self.assertFalse(lacks(runs, "scripts/python-coverage.sh"))


if __name__ == "__main__":
    unittest.main()


class IntegratorPublicationCoverageTest(unittest.TestCase):
    """The publish job must run even when no PR was accepted or conflicted."""

    def test_publish_job_runs_for_every_validation_decision(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-integrator.yml").read_text(encoding="utf-8"))
        self.assertIn("report", workflow["jobs"]["validate"]["outputs"])
        self.assertIn("needs.validate.outputs.report", workflow["jobs"]["publish"]["if"])
