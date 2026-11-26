"""Delivery must fail closed on Dependabot approval and run canonical coverage gates.

Both regressions were invisible in a green build: Dependabot's approval step
exited zero when the human label was missing, so the next step merged anyway,
and Delivery's frontend job called a policy script whose ``npm test`` has no
coverage threshold while its tooling job ran plain unittest. Synthetic
fixtures prove the detectors distinguish the broken shapes from the fixes;
the real workflows are then asserted so a YAML-only regression fails too.
"""
import shlex
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"
AUTO_MERGE = WORKFLOWS / "dependabot-auto-merge.yml"
DELIVERY = WORKFLOWS / "delivery.yml"
TRUSTED_NVD_EXPRESSION = (
    "${{ (github.event_name == 'push' || "
    "(github.event_name == 'workflow_dispatch' && "
    "github.ref == format('refs/heads/{0}', "
    "github.event.repository.default_branch))) "
    "&& secrets.NVD_API_KEY || '' }}"
)


def steps(text):
    """Parse a synthetic workflow step list into step dictionaries."""
    return yaml.safe_load(text)


def run_text(step):
    """Return a step's ``run:`` block as text, or an empty string."""
    body = step.get("run", "")
    return body if isinstance(body, str) else ""


def nvd_input(event_name, secret, ref="refs/heads/main"):
    """Model the push / default-branch dispatch secret expression for synthetic events."""
    trusted_dispatch = (event_name == "workflow_dispatch"
                        and ref == "refs/heads/main")
    return secret if event_name == "push" or trusted_dispatch else ""


def scalar_paths(value, path=()):
    """Yield every scalar path/value from parsed YAML for structural policy checks."""
    if isinstance(value, dict):
        for key, child in value.items():
            yield from scalar_paths(child, (*path, str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from scalar_paths(child, (*path, str(index)))
    else:
        yield path, value


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
        # Behavior changed on purpose: a stale candidate skips the gate too,
        # and is retried by the trusted publish job without consuming an attempt.
        self.assertEqual(
            gate["if"],
            "steps.candidate.outputs.skip != 'true' && steps.candidate.outputs.stale != 'true'")

    def test_trusted_check_state_is_emitted_and_consumed_by_publication(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-worker.yml").read_text(encoding="utf-8"))
        jobs = workflow["jobs"]
        validate = jobs["validate"]
        self.assertIn("passed", validate["outputs"])
        self.assertIn("skip", validate["outputs"])
        gate = next(s for s in validate["steps"] if s.get("id") == "gate")
        gate_body = run_text(gate)
        self.assertIn('echo "passed=true"', gate_body)
        self.assertIn('echo "passed=false"', gate_body)
        self.assertNotIn("continue-on-error", gate)
        publish = jobs["publish"]
        refuse = next(s for s in publish["steps"] if "Refuse to publish" in s.get("name", ""))
        self.assertEqual(refuse["env"]["SKIP"], "${{ needs.validate.outputs.skip }}")
        self.assertIn('if [[ "$SKIP" == "true" ]]', run_text(refuse))
        self.assertIn('[[ "$PASSED" != "true" || -z "$SHA" ]]', run_text(refuse))
        push = next(s for s in publish["steps"] if "Push the branch" in s.get("name", ""))
        self.assertEqual(push["env"]["VALIDATED_CHECKS"],
                         "${{ needs.validate.outputs.passed }}")
        self.assertIn('--validated-checks "$VALIDATED_CHECKS"', run_text(push))
        self.assertEqual(publish["permissions"], {
            "contents": "write", "pull-requests": "write", "issues": "write",
            "actions": "write",
        })

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

    def test_delivery_nvd_secret_is_limited_to_trusted_push_and_default_branch_dispatch(self):
        workflow = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))
        step = next(s for s in workflow["jobs"]["java-verify"]["steps"]
                    if s.get("uses") == "./.github/actions/java-verify")
        expression = TRUSTED_NVD_EXPRESSION
        self.assertEqual(step["with"]["nvd-api-key"], expression)
        for event in ("pull_request", "merge_group"):
            with self.subTest(event=event):
                self.assertEqual(nvd_input(event, "synthetic-secret"), "")
        self.assertEqual(nvd_input("workflow_dispatch", "synthetic-secret",
                                   "refs/heads/feature"), "")
        self.assertEqual(nvd_input("workflow_dispatch", "synthetic-secret",
                                   "refs/heads/main"), "synthetic-secret")
        self.assertEqual(nvd_input("push", "synthetic-secret"), "synthetic-secret")
        self.assertEqual(nvd_input("push", ""), "")

    def test_repository_nvd_references_have_one_push_guarded_secret_source(self):
        expression = TRUSTED_NVD_EXPRESSION
        secret_refs = []
        nvd_refs = []
        inherited = []
        for path in sorted((ROOT / ".github").rglob("*.yml")):
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            relative = str(path.relative_to(ROOT))
            for scalar_path, value in scalar_paths(document):
                if not isinstance(value, str):
                    continue
                if "secrets.NVD_API_KEY" in value:
                    secret_refs.append((relative, scalar_path, value))
                if "NVD_API_KEY" in value or (scalar_path and scalar_path[-1] == "NVD_API_KEY"):
                    nvd_refs.append((relative, scalar_path, value))
            if isinstance(document, dict) and document.get("secrets") == "inherit":
                inherited.append(relative)
            for job in document.get("jobs", {}).values() if isinstance(document, dict) else []:
                if isinstance(job, dict) and job.get("secrets") == "inherit":
                    inherited.append(relative)
        self.assertEqual(inherited, [])
        self.assertEqual(len(secret_refs), 1)
        relative, scalar_path, value = secret_refs[0]
        self.assertEqual(relative, ".github/workflows/delivery.yml")
        self.assertEqual(scalar_path[:3], ("jobs", "java-verify", "steps"))
        self.assertEqual(scalar_path[-2:], ("with", "nvd-api-key"))
        self.assertEqual(value, expression)
        self.assertEqual(len(nvd_refs), 2)
        action_refs = [item for item in nvd_refs
                       if item[0] == ".github/actions/java-verify/action.yml"]
        delivery_refs = [item for item in nvd_refs
                         if item[0] == ".github/workflows/delivery.yml"]
        self.assertEqual(len(action_refs), 1)
        self.assertEqual(action_refs[0][1][-2:], ("env", "NVD_API_KEY"))
        self.assertEqual(action_refs[0][2], "${{ inputs.nvd-api-key }}")
        self.assertEqual(len(delivery_refs), 1)
        self.assertEqual(delivery_refs[0][1][-2:], ("with", "nvd-api-key"))
        self.assertEqual(delivery_refs[0][2], expression)

    def test_delivery_pins_actionlint_and_only_ignores_the_queue_key(self):
        workflow = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))
        step = next(s for s in workflow["jobs"]["policy"]["steps"]
                    if s.get("name") == "Workflow lint (actionlint)")
        run = run_text(step)
        self.assertIn("actionlint-py==1.7.12.25", run)
        invocation = run[run.index('"$RUNNER_TEMP/actionlint/bin/actionlint"'):]
        tokens = shlex.split(invocation)
        ignores = []
        for index, token in enumerate(tokens):
            if token in {"-ignore", "--ignore"}:
                ignores.append(tokens[index + 1])
            elif token.startswith(("-ignore=", "--ignore=")):
                ignores.append(token.split("=", 1)[1])
        self.assertEqual(ignores, ['unexpected key "queue" for "concurrency" section'])

    def test_delivery_frontend_runs_the_canonical_coverage_entry_points(self):
        workflow = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))
        runs = commands(workflow["jobs"]["frontend"]["steps"])
        self.assertFalse(lacks(runs, "apps/staff-web/check.sh"))
        self.assertFalse(lacks(runs, "apps/patient-mobile/check.sh"))

    def test_delivery_tooling_job_runs_the_coverage_gate(self):
        workflow = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))
        runs = commands(workflow["jobs"]["script-tests"]["steps"])
        self.assertFalse(lacks(runs, "scripts/python-coverage.sh"))


class IntegratorPublicationCoverageTest(unittest.TestCase):
    """The publish job must run even when no PR was accepted or conflicted."""

    def test_publish_job_runs_for_every_validation_decision(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-integrator.yml").read_text(encoding="utf-8"))
        self.assertIn("report", workflow["jobs"]["validate"]["outputs"])
        self.assertIn("needs.validate.outputs.report", workflow["jobs"]["publish"]["if"])

    def test_reconciliation_uses_clean_main_least_privilege_and_success_gate(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-integrator.yml").read_text(encoding="utf-8"))
        jobs = workflow["jobs"]
        self.assertFalse(any("reconcile-tasks" in run_text(step) for step in jobs["publish"]["steps"]))
        reconcile = jobs["reconcile"]
        self.assertEqual(reconcile["needs"], "publish")
        self.assertEqual(reconcile["if"], "always() && needs.publish.result == 'success'")
        self.assertEqual(reconcile["permissions"], {"contents": "write", "pull-requests": "read"})
        self.assertEqual(reconcile["env"]["GH_TOKEN"],
                         "${{ secrets.SWARM_TOKEN || github.token }}")
        checkout = next(step for step in reconcile["steps"] if step.get("uses", "").startswith("actions/checkout@"))
        self.assertEqual(checkout["with"]["ref"], "main")
        self.assertIs(checkout["with"]["persist-credentials"], False)
        self.assertFalse(any(step.get("uses", "").startswith("actions/download-artifact@")
                             for step in reconcile["steps"]))
        self.assertFalse(any("integrate-publish" in run_text(step) for step in reconcile["steps"]))
        self.assertTrue(any("reconcile-tasks" in run_text(step) for step in reconcile["steps"]))

    def test_follow_up_jobs_require_successful_publication(self):
        workflow = yaml.safe_load((WORKFLOWS / "swarm-integrator.yml").read_text(encoding="utf-8"))
        jobs = workflow["jobs"]
        self.assertEqual(
            jobs["resolve"]["if"],
            "always() && needs.publish.result == 'success' && "
            "needs.validate.outputs.conflicted != '' && needs.validate.outputs.conflicted != '[]'",
        )
        self.assertEqual(jobs["resolve-publish"]["if"], "always() && needs.resolve.result == 'success'")
        self.assertNotIn("continue-on-error", jobs["publish"])


class ReleasePublicationRetryTest(unittest.TestCase):
    """A release retry verifies provenance labels, never the rebuild-dependent .Id."""

    @staticmethod
    def publish_body():
        workflow = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))
        step = next(
            item for item in workflow["jobs"]["release"]["steps"]
            if item.get("name") == "Publish exact tested image and release"
        )
        return run_text(step)

    def test_published_child_is_verified_by_version_and_revision_labels(self):
        body = self.publish_body()
        self.assertIn("version_label=", body)
        self.assertIn("revision_label=", body)
        self.assertIn('docker image inspect "$child" --format "$version_label"', body)
        self.assertIn('docker image inspect "$child" --format "$revision_label"', body)
        self.assertNotIn('{{.Id}}', body)

    def test_cross_commit_reuse_fails_closed_with_an_actionable_message(self):
        body = self.publish_body()
        self.assertIn("already published for revision", body)
        self.assertIn("use a new version tag", body)

    def test_an_already_published_version_fails_before_any_write(self):
        body = self.publish_body()
        manifest_guard = '$image already exists; version tags are immutable'
        release_guard = 'release $VERSION already exists; version tags are immutable'
        self.assertIn(manifest_guard, body)
        self.assertIn(release_guard, body)
        self.assertIn('docker buildx imagetools inspect "$image"', body)
        self.assertIn('gh release view "$VERSION"', body)
        first_push = body.index("docker push")
        self.assertLess(body.index(manifest_guard), first_push)
        self.assertLess(body.index(release_guard), first_push)

    def test_manifest_uses_tag_refs_and_fails_closed_on_a_transient_pull_error(self):
        body = self.publish_body()
        self.assertIn('docker buildx imagetools create --tag "$image" "${children[@]}"', body)
        self.assertIn("refusing to overwrite", body)
        self.assertNotIn("RepoDigests", body)


class SwarmPlanReasonWiringTest(unittest.TestCase):
    """A skipped wave must expose a safe reason to the developer in the run UI."""

    def test_plan_job_exposes_the_planner_reason_as_a_job_output(self):
        plan = yaml.safe_load((WORKFLOWS / "swarm.yml").read_text(encoding="utf-8"))["jobs"]["plan"]
        self.assertEqual(plan["outputs"]["reason"], "${{ steps.plan.outputs.reason || '' }}")

    def test_images_not_ready_branch_emits_a_fixed_reason(self):
        plan = yaml.safe_load((WORKFLOWS / "swarm.yml").read_text(encoding="utf-8"))["jobs"]["plan"]
        step = next(item for item in plan["steps"] if item.get("id") == "plan")
        body = run_text(step)
        self.assertIn("echo 'reason=images_not_ready'", body)
        self.assertIn('} >> "$GITHUB_OUTPUT"', body)

    def test_plan_job_reports_the_zero_worker_reason_in_the_summary(self):
        plan = yaml.safe_load((WORKFLOWS / "swarm.yml").read_text(encoding="utf-8"))["jobs"]["plan"]
        step = next((item for item in plan["steps"] if item.get("id") == "plan-reason"), None)
        self.assertIsNotNone(step)
        self.assertEqual(step["if"], "always() && steps.plan.outputs.reason != ''")
        self.assertEqual(step["env"]["PLAN_REASON"], "${{ steps.plan.outputs.reason }}")
        body = run_text(step)
        self.assertIn('echo "Swarm wave skipped: $PLAN_REASON" >> "$GITHUB_STEP_SUMMARY"', body)
        self.assertNotIn("${{", body)


if __name__ == "__main__":
    unittest.main()
