"""Required gates queue exact revisions; independent workflows stay per-run."""
import copy
import unittest

import yaml

from helpers import ROOT, load as load_script

WORKFLOWS = ROOT / ".github" / "workflows"
DELIVERY_GROUP = "${{ github.event.pull_request.head.sha || github.ref }}"
PLAN_GROUP = "swarm-plan-${{ github.repository }}"
INTEGRATOR_GROUP = "swarm-integrator-${{ github.repository }}"
SWARM_GROUP = "swarm-${{ github.ref }}"


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

    def test_delivery_queues_each_pr_head_and_ref_without_cancelling(self):
        self.assert_queue(load(WORKFLOWS / "delivery.yml")["concurrency"], DELIVERY_GROUP)
        self.assertEqual(revision_group("head-a", "refs/heads/main"), "head-a")
        self.assertEqual(revision_group("", "refs/heads/main"), "refs/heads/main")
        self.assertNotEqual(revision_group("head-a", "refs/heads/main"),
                            revision_group("head-b", "refs/heads/main"))

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

    def test_swarm_allows_one_wave_per_branch(self):
        self.assert_queue(load(WORKFLOWS / "swarm.yml")["concurrency"], SWARM_GROUP)

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
            "delivery": load(WORKFLOWS / "delivery.yml")["concurrency"],
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

<<<<<<< HEAD
    def test_delivery_documentation_matches_unfiltered_triggers(self):
        # A stale filter claim tells maintainers a check runs that GitHub never creates,
        # so the operator guide must describe the same trigger contract as the workflow.
        guide = " ".join(
            (ROOT / "docs/reference/testing-guide.md").read_text(encoding="utf-8").split()
        )
        self.assertNotIn("`paths-ignore` on `push`", guide)
        self.assertIn("Every push to `main` and every version tag runs it", guide)


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
=======
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
>>>>>>> origin/main
