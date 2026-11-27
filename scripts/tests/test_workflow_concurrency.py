"""Required gates queue exact revisions; independent workflows stay per-run."""
import copy
import unittest

import yaml

from helpers import ROOT, load as load_script

WORKFLOWS = ROOT / ".github" / "workflows"
DELIVERY_GROUP = ("${{ github.event_name == 'workflow_dispatch' && "
                  "format('delivery-dispatch-{0}-{1}', github.ref, github.run_id) "
                  "|| github.event.pull_request.head.sha || github.ref }}")
DELIVERY_CANCEL = "${{ github.event_name == 'push' && github.ref_type == 'branch' }}"
PLAN_GROUP = "swarm-plan-${{ github.repository }}"
INTEGRATOR_GROUP = "swarm-integrator-${{ github.repository }}"
SWARM_GROUP = "swarm-${{ github.repository }}-${{ github.run_id }}"

# Operator docs that describe Delivery's triggers must stay in step with the
# workflow; a stale path-filter claim makes reviewers expect a check that never
# runs for the exact SHA branch protection requires.
DELIVERY_DOCS = (
    ROOT / "docs" / "reference" / "testing-guide.md",
    ROOT / "docs" / "swarm" / "EFFICIENCY.md",
)


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def revision_group(pr_sha, ref):
    """Model the Delivery expression: PR head when present, otherwise ref."""
    return pr_sha or ref


class WorkflowConcurrencyTest(unittest.TestCase):
    def workflows(self):
        return sorted(WORKFLOWS.glob("*.yml"))

    def assert_queue(self, concurrency, group):
        self.assertEqual(concurrency["group"], group)
        self.assertIs(concurrency["cancel-in-progress"], False)
        self.assertEqual(concurrency["queue"], "max")

    def assert_delivery_concurrency(self, concurrency):
        """Branch pushes cancel their superseded peers; every other event is exact-SHA."""
        self.assertEqual(concurrency["group"], DELIVERY_GROUP)
        self.assertEqual(concurrency["cancel-in-progress"], DELIVERY_CANCEL)
        self.assertNotIn("queue", concurrency)

    def test_delivery_cancels_only_superseded_branch_pushes(self):
        self.assert_delivery_concurrency(load(WORKFLOWS / "delivery.yml")["concurrency"])
        self.assertEqual(revision_group("head-a", "refs/heads/main"), "head-a")
        self.assertEqual(revision_group("", "refs/heads/main"), "refs/heads/main")
        self.assertNotEqual(revision_group("head-a", "refs/heads/main"),
                            revision_group("head-b", "refs/heads/main"))

    def test_delivery_dispatch_on_a_branch_has_its_own_group(self):
        group = load(WORKFLOWS / "delivery.yml")["concurrency"]["group"]
        self.assertEqual(group, DELIVERY_GROUP)
        self.assertIn("github.event_name == 'workflow_dispatch'", group)
        self.assertIn("github.run_id", group)

    def test_delivery_cancellation_expression_is_scoped_to_push_events(self):
        concurrency = load(WORKFLOWS / "delivery.yml")["concurrency"]
        self.assertEqual(concurrency["cancel-in-progress"], DELIVERY_CANCEL)
        # The matrix mirrors the pinned expression: only push to a branch may cancel.
        def cancels(event, ref_type):
            return event == "push" and ref_type == "branch"

        cases = (("push", "branch", True), ("push", "tag", False),
                 ("pull_request", "branch", False), ("merge_group", "branch", False),
                 ("workflow_dispatch", "branch", False))
        for event, ref_type, expected in cases:
            with self.subTest(event=event, ref_type=ref_type):
                self.assertEqual(cancels(event, ref_type), expected)

    def test_delivery_guard_rejects_blanket_cancellation_and_restored_queue(self):
        with self.assertRaises(AssertionError):
            self.assert_delivery_concurrency({"group": DELIVERY_GROUP,
                                              "cancel-in-progress": True})
        with self.assertRaises(AssertionError):
            self.assert_delivery_concurrency({"group": DELIVERY_GROUP,
                                              "cancel-in-progress": DELIVERY_CANCEL,
                                              "queue": "max"})

    def test_other_workflows_run_concurrently_per_run(self):
        for path in self.workflows():
            if path.name in {"delivery.yml", "swarm-integrator.yml", "swarm.yml"}:
                continue
            with self.subTest(workflow=path.name):
                concurrency = load(path)["concurrency"]
                self.assertIn("github.run_id", concurrency["group"])
                self.assertIs(concurrency["cancel-in-progress"], False)
                self.assertNotIn("queue", concurrency)

    def test_swarm_workers_have_a_group_per_slot(self):
        group = load(WORKFLOWS / "swarm-worker.yml")["concurrency"]["group"]
        self.assertIn("github.run_id", group)
        self.assertIn("inputs.slot", group)

    def test_swarm_admits_concurrent_waves_per_run(self):
        concurrency = load(WORKFLOWS / "swarm.yml")["concurrency"]
        self.assertEqual(concurrency["group"], SWARM_GROUP)
        self.assertIs(concurrency["cancel-in-progress"], False)
        self.assertNotIn("queue", concurrency)

    def test_swarm_plan_serializes_all_waves_in_the_repository(self):
        plan = load(WORKFLOWS / "swarm.yml")["jobs"]["plan"]["concurrency"]
        self.assert_queue(plan, PLAN_GROUP)

    def test_integrator_serializes_called_and_dispatched_runs(self):
        workflow = load(WORKFLOWS / "swarm-integrator.yml")
        self.assert_queue(workflow["concurrency"], INTEGRATOR_GROUP)
        self.assertIn("workflow_call", workflow[True])
        self.assertIn("workflow_dispatch", workflow[True])

    def test_serialized_groups_reject_legacy_keys_and_invalid_queue(self):
        blocks = {
            "plan": load(WORKFLOWS / "swarm.yml")["jobs"]["plan"]["concurrency"],
            "integrator": load(WORKFLOWS / "swarm-integrator.yml")["concurrency"],
        }
        for name, concurrency in blocks.items():
            with self.subTest(name=name):
                self.assertNotIn("github.run_id", concurrency["group"])
                self.assertNotIn("github.head_ref", concurrency["group"])
                self.assertEqual(concurrency["queue"], "max")
                self.assertIs(concurrency["cancel-in-progress"], False)

    def test_delivery_reports_a_check_for_every_pull_request(self):
        trigger = load(WORKFLOWS / "delivery.yml")[True]["pull_request"]
        self.assertIsNone(trigger)

    def test_delivery_reports_a_check_for_every_branch_and_tag_push(self):
        trigger = load(WORKFLOWS / "delivery.yml")[True]["push"]
        self.assertIsInstance(trigger, dict)
        self.assertNotIn("paths", trigger)
        self.assertNotIn("paths-ignore", trigger)

    def test_delivery_documentation_matches_unfiltered_triggers(self):
        # A stale filter claim tells maintainers a check runs that GitHub never creates,
        # so the operator guide must describe the same trigger contract as the workflow.
        guide = " ".join(
            (ROOT / "docs/reference/testing-guide.md").read_text(encoding="utf-8").split()
        )
        self.assertNotIn("`paths-ignore` on `push`", guide)
        self.assertIn("Every push to `main` and every version tag runs it", guide)

    def test_operator_docs_do_not_describe_a_filtered_delivery_trigger(self):
        """Stale path-filter claims make operators expect a gate GitHub never reports."""
        docs = (ROOT / "docs" / "reference" / "testing-guide.md",
                ROOT / "docs" / "swarm" / "EFFICIENCY.md")
        for path in docs:
            with self.subTest(path=path.name):
                text = path.read_text(encoding="utf-8")
                self.assertNotIn("paths-ignore", text)
                self.assertNotIn("paths:", text)
                self.assertIn("delivery gate", text.lower())

    def test_operator_docs_describe_the_unfiltered_delivery_trigger(self):
        """The listed operator docs must not imply a filter suppresses the exact-SHA check."""
        for path in DELIVERY_DOCS:
            with self.subTest(doc=path.name):
                text = path.read_text(encoding="utf-8").lower()
                self.assertIn("no path filter", text)
                self.assertNotIn("paths-ignore", text)
                self.assertNotIn("paths:", text)
                self.assertIn("delivery gate", text)


    def test_operator_docs_describe_scoped_superseded_run_cancellation(self):
        """Docs must not promise that no run is ever cancelled on a branch push."""
        for path in DELIVERY_DOCS:
            with self.subTest(doc=path.name):
                text = path.read_text(encoding="utf-8").lower()
                self.assertIn("superseded branch pushes", text)
                self.assertIn("pull request", text)


# The expected job set comes from the fail-closed checker the gate step runs,
# with an independent literal pin below so a coordinated edit cannot drop a job.
CHECKER = load_script("check-delivery-gate")
REQUIRED_JOBS = frozenset(CHECKER.MANDATORY | set(CHECKER.OPTIONAL))
# An independent pin stops a coordinated checker-and-workflow edit from dropping
# a real verification job while both derived sets still agree.
LITERAL_JOBS = frozenset({"policy", "script-tests", "frontend", "java-verify", "docs",
                          "verify", "release"})


def checker_step(gate):
    """Return the gate step that invokes the fail-closed Delivery checker."""
    steps = gate.get("steps") if isinstance(gate.get("steps"), list) else []
    return next((item for item in steps if isinstance(item, dict)
                 and "check-delivery-gate.py" in str(item.get("run", ""))), None)


def gate_contract(gate):
    """Return the required-check facts from a parsed Delivery ``gate`` job.

    Malformed fields become empty values so a mutation is reported as a
    violation rather than raised; the workflow on disk is never modified.
    """
    step = checker_step(gate)
    command = str(step.get("run", "")) if step else ""
    needs = gate.get("needs")
    return {
        "name": gate.get("name"),
        "condition": gate.get("if"),
        "needs": frozenset(needs) if isinstance(needs, list) else frozenset(),
        "command": command,
        "env": step.get("env", {}) if step and isinstance(step.get("env"), dict) else {},
        "continue_on_error": bool(step.get("continue-on-error")) if step else False,
        "job_continue_on_error": bool(gate.get("continue-on-error")),
        "swallows_exit": "|| true" in command or "exit 0" in command,
    }


def gate_contract_violations(gate):
    """Return every way a Delivery gate job fails to report the required check.

    The expected job set is read from ``check-delivery-gate.py`` so the workflow,
    the checker and this test cannot silently drift into three different sets.
    """
    violations = []
    if gate.get("name") != "Delivery gate":
        violations.append("name")
    if gate.get("if") != "always()":
        violations.append("condition")
    needs = gate.get("needs")
    if not isinstance(needs, list) or frozenset(needs) != REQUIRED_JOBS:
        violations.append("needs")
    contract = gate_contract(gate)
    if "scripts/check-delivery-gate.py" not in contract["command"]:
        violations.append("command")
    if contract["continue_on_error"]:
        violations.append("continue-on-error")
    if contract["job_continue_on_error"]:
        violations.append("job continue-on-error")
    if contract["swallows_exit"]:
        violations.append("swallowed exit")
    for key, expected in (("RESULTS", "${{ toJSON(needs) }}"),
                          ("EVENT_NAME", "${{ github.event_name }}"),
                          ("REF_TYPE", "${{ github.ref_type }}")):
        if contract["env"].get(key) != expected:
            violations.append(key)
    return violations


class IssueMonitorWorkflowTest(unittest.TestCase):
    """The tokenless triage job must not dirty the checkout before it starts."""

    def test_triage_inputs_are_collected_outside_the_checkout(self):
        workflow = load(WORKFLOWS / "swarm-issue-monitor.yml")
        steps = workflow["jobs"]["triage"]["steps"]
        download = next(
            step for step in steps
            if step.get("uses", "").startswith("actions/download-artifact@")
        )
        self.assertIn("runner.temp", download["with"]["path"])
        copy = next(
            step for step in steps
            if "Make the issue files" in step.get("name", "")
        )
        self.assertIn('"${RUNNER_TEMP}/monitor-selection/triage-in" "${RUNNER_TEMP}/triage-in"',
                      copy["run"])
        triage = next(
            step for step in steps
            if step.get("name", "").startswith("Triage the issues")
        )
        self.assertEqual(triage["env"]["TRIAGE_IN"], "${{ runner.temp }}/triage-in")
        self.assertNotIn("GH_TOKEN", triage["env"])
class IntegratorRecoveryContractTest(unittest.TestCase):
    """The base-advance retry is bounded, allow-listed and token-isolated."""

    def setUp(self):
        self.workflow = load(WORKFLOWS / "swarm-integrator.yml")

    def triggers(self):
        return self.workflow.get(True) or self.workflow["on"]

    def test_dispatch_inputs_and_publish_retry_outputs_are_declared(self):
        triggers = self.triggers()
        for trigger in ("workflow_dispatch", "workflow_call"):
            inputs = triggers[trigger]["inputs"]
            self.assertEqual(inputs["recovery_attempt"]["default"], "0")
            self.assertEqual(inputs["recovery_prs"]["default"], "")
            self.assertIs(inputs["ignore_red_main"]["default"], False)
        publish = self.workflow["jobs"]["publish"]
        self.assertEqual(
            publish["outputs"]["integration_retry"],
            "${{ steps.push.outputs.integration_retry }}",
        )
        push = next(step for step in publish["steps"] if step.get("id") == "push")
        self.assertIn("--recovery-attempt", push["run"])

    def test_recovery_job_is_signal_gated_and_holds_no_write_token(self):
        resolve = self.workflow["jobs"]["resolve"]
        self.assertIn("needs.publish.outputs.integration_retry != ''", resolve["if"])
        self.assertIn("needs.publish.outputs.recovery_attempt < 1", resolve["if"])
        self.assertEqual(set(resolve["permissions"].values()), {"read"})
        self.assertNotIn("GH_TOKEN: ${{ secrets.SWARM_TOKEN",
                         "\n".join(
                             str(step.get("env", {})) for step in resolve["steps"]
                         ))

    def test_retry_pick_is_allow_listed_and_redispatch_carries_only_ids(self):
        pick = self.workflow["jobs"]["pick"]
        queue = next(step for step in pick["steps"] if step.get("id") == "queue")
        self.assertEqual(queue["env"]["SWARM_RECOVERY_PRS"], "${{ inputs.recovery_prs }}")
        republish = self.workflow["jobs"]["resolve-publish"]
        self.assertIn("publish", republish["needs"])
        redispatch = next(
            step for step in republish["steps"]
            if "Re-dispatch the bounded base-advance retry" in step.get("name", "")
        )
        self.assertIn("--field recovery_attempt", redispatch["run"])
        self.assertIn("--field recovery_prs", redispatch["run"])
        self.assertIn("--field ignore_red_main", redispatch["run"])
        self.assertIn('^[01]$', redispatch["run"])
        self.assertNotIn("--force", redispatch["run"])
        push_step = next(
            step for step in republish["steps"]
            if "Push the resolved branches" in step.get("name", "")
        )
        self.assertIn("--no-dispatch", push_step["env"]["DISPATCH_FLAG"])
        self.assertIn('args+=("$DISPATCH_FLAG")', push_step["run"])


class DeliveryGateContractTest(unittest.TestCase):
    """Branch protection waits for one exact context on every pull-request head."""

    def test_delivery_gate_reports_the_required_context_for_every_head(self):
        gate = load(WORKFLOWS / "delivery.yml")["jobs"]["gate"]
        self.assertEqual(gate_contract_violations(gate), [])
        contract = gate_contract(gate)
        self.assertEqual(contract["name"], "Delivery gate")
        self.assertEqual(contract["condition"], "always()")
        self.assertEqual(contract["needs"], REQUIRED_JOBS)
        self.assertEqual(REQUIRED_JOBS, LITERAL_JOBS)
        self.assertIn("scripts/check-delivery-gate.py", contract["command"])
        self.assertFalse(contract["continue_on_error"])
        self.assertFalse(contract["job_continue_on_error"])
        self.assertFalse(contract["swallows_exit"])
        self.assertEqual(contract["env"], {
            "RESULTS": "${{ toJSON(needs) }}",
            "EVENT_NAME": "${{ github.event_name }}",
            "REF_TYPE": "${{ github.ref_type }}",
        })

    def test_gate_contract_detects_a_mutated_required_check(self):
        # Synthetic in-memory mutations prove the guard can fail: a renamed
        # context or a dropped disposition recreates "Expected — Waiting".
        gate = load(WORKFLOWS / "delivery.yml")["jobs"]["gate"]
        mutations = (
            ("renamed context", lambda g: g.update(name="Delivery"), "name"),
            ("dropped condition", lambda g: g.pop("if"), "condition"),
            ("non-always condition", lambda g: g.update({"if": "success()"}), "condition"),
            ("dropped mandatory job", lambda g: g["needs"].remove("policy"), "needs"),
            ("malformed needs", lambda g: g.update(needs="policy"), "needs"),
            ("malformed steps", lambda g: g.update(steps="checkout"), "command"),
            ("non-dict step", lambda g: g["steps"].__setitem__(
                g["steps"].index(checker_step(g)), "not a dict"), "command"),
            ("wrong checker", lambda g: checker_step(g).update(
                run=checker_step(g)["run"].replace("check-delivery-gate.py", "other.py")),
             "command"),
            ("wrong checker path", lambda g: checker_step(g).update(
                run=checker_step(g)["run"].replace(
                    "scripts/check-delivery-gate.py", "tools/check-delivery-gate.py")),
             "command"),
            ("continue on error", lambda g: checker_step(g).update(
                {"continue-on-error": True}), "continue-on-error"),
            ("job continue on error", lambda g: g.update({"continue-on-error": True}),
             "job continue-on-error"),
            ("swallowed exit", lambda g: checker_step(g).update(
                run=checker_step(g)["run"] + "\n  exit 0"), "swallowed exit"),
            ("swallowed with or true", lambda g: checker_step(g).update(
                run=checker_step(g)["run"] + " || true"), "swallowed exit"),
            ("malformed env", lambda g: checker_step(g).update(env="not a dict"), "RESULTS"),
            ("missing results binding", lambda g: checker_step(g)["env"].pop("RESULTS"),
             "RESULTS"),
            ("wrong event binding", lambda g: checker_step(g)["env"].update(
                EVENT_NAME="${{ github.ref }}"), "EVENT_NAME"),
            ("wrong ref binding", lambda g: checker_step(g)["env"].update(
                REF_TYPE="${{ github.event_name }}"), "REF_TYPE"),
        )
        for label, mutate, expected in mutations:
            with self.subTest(mutation=label):
                changed = copy.deepcopy(gate)
                mutate(changed)
                # Exactly one label proves the mutation caused this violation
                # rather than a guard that appends the label unconditionally.
                self.assertEqual(gate_contract_violations(changed).count(expected), 1, label)


RELEASE_STEP_NAME = "Require a green default-branch run for this commit"
RELEASE_CALLERS = (("delivery.yml", "release"), ("release-app-image.yml", "publish"))


def release_step(jobs):
    """Return the green-run precondition step from a parsed release job."""
    steps = jobs.get("steps", []) if isinstance(jobs, dict) else []
    return next((item for item in steps if isinstance(item, dict)
                 and item.get("name") == RELEASE_STEP_NAME), None)


def release_violations(step):
    """Return every way the release precondition could accept the wrong evidence."""
    run = str(step.get("run", "")) if isinstance(step, dict) else ""
    env = step.get("env", {}) if isinstance(step, dict) and isinstance(step.get("env"), dict) else {}
    violations = []
    if "scripts/check-release-base.py" not in run:
        violations.append("helper")
    if "repos/$GITHUB_REPOSITORY/actions/workflows/delivery.yml/runs" not in run:
        violations.append("workflow url")
    if "repos/$GITHUB_REPOSITORY/actions/runs/" not in run:
        violations.append("jobs api")
    if r'--jq "{\"$run_id\": .jobs}"' not in run:
        violations.append("jobs map")
    for token, label in (("-f event=", "event filter"), ("-f status=", "status filter"),
                         (".total_count", "count")):
        if token in run:
            violations.append(label)
    for token, label in (('--sha "$GITHUB_SHA"', "sha"),
                         ('--branch "$DEFAULT_BRANCH"', "branch"),
                         ('--repo "$GITHUB_REPOSITORY"', "repo")):
        if token not in run:
            violations.append(label)
    if "set -euo pipefail" not in run:
        violations.append("pipefail")
    if "|| true" in run or "exit 0" in run:
        violations.append("swallowed exit")
    if isinstance(step, dict) and step.get("continue-on-error"):
        violations.append("continue-on-error")
    if env.get("DEFAULT_BRANCH") != "${{ github.event.repository.default_branch }}":
        violations.append("branch binding")
    return violations


def release_checkout_violations(jobs):
    """Return why the trusted helper would be unavailable or unsafe at release time."""
    steps = jobs.get("steps", []) if isinstance(jobs, dict) else []
    if not isinstance(steps, list):
        return ["steps"]
    checkouts = [index for index, item in enumerate(steps)
                 if isinstance(item, dict)
                 and str(item.get("uses", "")).startswith("actions/checkout@")]
    if len(checkouts) != 1:
        return ["checkout count"]
    helper = next((index for index, item in enumerate(steps)
                   if isinstance(item, dict) and item.get("name") == RELEASE_STEP_NAME), None)
    if helper is None:
        return ["helper"]
    settings = steps[checkouts[0]].get("with", {})
    if not isinstance(settings, dict):
        settings = {}
    violations = []
    if checkouts[0] > helper:
        violations.append("order")
    if settings.get("ref") != "${{ github.event.repository.default_branch }}":
        violations.append("ref")
    if settings.get("persist-credentials") is not False:
        violations.append("credentials")
    return violations


class ReleaseGreenRunWiringTest(unittest.TestCase):
    """Both publish paths must delegate the exact-SHA decision to a tested helper."""

    def test_both_release_paths_call_the_helper_without_raw_filters(self):
        for filename, job_name in RELEASE_CALLERS:
            with self.subTest(workflow=filename):
                workflow = load(WORKFLOWS / filename)
                step = release_step(workflow["jobs"][job_name])
                self.assertEqual(release_violations(step), [])

    def test_app_image_publish_job_can_read_actions(self):
        workflow = load(WORKFLOWS / "release-app-image.yml")
        permissions = workflow["jobs"]["publish"].get("permissions", {})
        self.assertEqual(permissions.get("actions"), "read")

    def test_both_release_paths_check_out_the_default_branch_before_the_helper(self):
        for filename, job_name in RELEASE_CALLERS:
            with self.subTest(workflow=filename):
                jobs = load(WORKFLOWS / filename)["jobs"][job_name]
                self.assertEqual(release_checkout_violations(jobs), [])

    def test_release_checkout_detector_rejects_each_regression(self):
        jobs = copy.deepcopy(load(WORKFLOWS / "delivery.yml")["jobs"]["release"])

        def checkout_index(value):
            return next(index for index, item in enumerate(value["steps"])
                        if str(item.get("uses", "")).startswith("actions/checkout@"))

        mutations = (
            ("no checkout", lambda j: j["steps"].pop(checkout_index(j)), "checkout count"),
            ("checkout after helper", lambda j: j["steps"].append(
                j["steps"].pop(checkout_index(j))), "order"),
            ("tagged tree instead of default branch", lambda j: j["steps"][checkout_index(j)][
                "with"].update(ref="${{ github.ref }}"), "ref"),
            ("stored credentials", lambda j: j["steps"][checkout_index(j)][
                "with"].update(**{"persist-credentials": True}), "credentials"),
            ("malformed steps", lambda j: j.update(steps="checkout"), "steps"),
            ("renamed precondition", lambda j: j["steps"][next(
                index for index, item in enumerate(j["steps"])
                if item.get("name") == RELEASE_STEP_NAME)].update(name="Other"), "helper"),
        )
        for label, mutate, expected in mutations:
            with self.subTest(mutation=label):
                changed = copy.deepcopy(jobs)
                mutate(changed)
                self.assertEqual(release_checkout_violations(changed).count(expected), 1, label)

    def test_release_wiring_detector_rejects_each_regression(self):
        step = release_step(load(WORKFLOWS / "delivery.yml")["jobs"]["release"])
        mutations = (
            ("raw event filter", lambda s: s.update(
                run=s["run"] + "\n  -f event=push"), "event filter"),
            ("raw status filter", lambda s: s.update(
                run=s["run"] + "\n  -f status=success"), "status filter"),
            ("count instead of records", lambda s: s.update(
                run=s["run"] + "\n  --jq '.total_count'"), "count"),
            ("wrong helper", lambda s: s.update(
                run=s["run"].replace("scripts/check-release-base.py", "tools/other.py")),
             "helper"),
            ("wrong workflow", lambda s: s.update(
                run=s["run"].replace("workflows/delivery.yml/runs", "workflows/other.yml/runs")),
             "workflow url"),
            ("no job evidence", lambda s: s.update(
                run=s["run"].replace("repos/$GITHUB_REPOSITORY/actions/runs/",
                                     "repos/$GITHUB_REPOSITORY/actions/workflow_runs/")),
             "jobs api"),
            ("flat jobs object", lambda s: s.update(run=s["run"].replace(
                r'--jq "{\"$run_id\": .jobs}"',
                r'--jq "{key: \"$run_id\", value: .jobs}"')), "jobs map"),
            ("missing sha", lambda s: s.update(
                run=s["run"].replace('--sha "$GITHUB_SHA"', "")), "sha"),
            ("missing branch", lambda s: s.update(
                run=s["run"].replace('--branch "$DEFAULT_BRANCH"', "")), "branch"),
            ("missing repo", lambda s: s.update(
                run=s["run"].replace('--repo "$GITHUB_REPOSITORY"', "")), "repo"),
            ("no pipefail", lambda s: s.update(
                run=s["run"].replace("set -euo pipefail", "")), "pipefail"),
            ("swallowed exit", lambda s: s.update(run=s["run"] + " || true"),
             "swallowed exit"),
            ("swallowed exit via exit 0", lambda s: s.update(
                run=s["run"] + "\nexit 0"), "swallowed exit"),
            ("continue on error", lambda s: s.update({"continue-on-error": True}),
             "continue-on-error"),
            ("wrong branch binding", lambda s: s["env"].update(
                DEFAULT_BRANCH="${{ github.ref }}"), "branch binding"),
        )
        for label, mutate, expected in mutations:
            with self.subTest(mutation=label):
                changed = copy.deepcopy(step)
                mutate(changed)
                self.assertEqual(release_violations(changed).count(expected), 1, label)

    def test_release_documentation_accepts_dispatch_evidence(self):
        guide = " ".join((ROOT / "docs" / "reference" / "testing-guide.md").read_text(
            encoding="utf-8").split()).lower()
        self.assertIn("workflow_dispatch", guide)
        self.assertNotIn("removes the exact-sha success", guide)
        efficiency = " ".join((ROOT / "docs" / "swarm" / "EFFICIENCY.md").read_text(
            encoding="utf-8").split()).lower()
        self.assertIn("workflow_dispatch", efficiency)
