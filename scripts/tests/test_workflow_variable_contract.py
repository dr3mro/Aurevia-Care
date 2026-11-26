"""Workflows resolve boolean repository variables through one canonical flags job."""
import unittest

import yaml

from helpers import ROOT, load as load_script

WORKFLOWS = ROOT / ".github" / "workflows"
flags = load_script("swarm_flags")
FLAG_NAMES = tuple(name for name, _, _ in flags.FLAG_SPECS)
BOOLEAN_VARS = tuple(variable for _, variable, _ in flags.FLAG_SPECS)
CONSUMERS = ("swarm.yml", "swarm-worker.yml", "swarm-issue-monitor.yml", "daily-notes.yml")
FLAGS_CALLERS = ("swarm.yml", "swarm-issue-monitor.yml", "daily-notes.yml")


def read(name):
    """Parse a workflow file as YAML, preserving the on-key quirk of PyYAML."""
    return yaml.safe_load((WORKFLOWS / name).read_text(encoding="utf-8"))


def trigger(doc):
    """Return the workflow_call trigger block regardless of the YAML on-key quirk."""
    return doc.get("on") or doc.get(True)


def sites(doc):
    """Yield (label, text) for every job/step condition and run block."""
    for job_id, job in doc["jobs"].items():
        if job.get("if"):
            yield f"jobs.{job_id}.if", str(job["if"])
        for step in job.get("steps", []):
            label = f"jobs.{job_id}.steps[{step.get('id') or step.get('name')}]"
            if step.get("if"):
                yield label + ".if", str(step["if"])
            if step.get("run"):
                yield label + ".run", str(step["run"])


def step(doc, job_id, step_id):
    """Find a step by its id, never by its line number."""
    return next(s for s in doc["jobs"][job_id]["steps"] if s.get("id") == step_id)


class WorkflowVariableContractTest(unittest.TestCase):
    def test_shared_flag_specs_are_complete_and_canonical(self):
        self.assertEqual(FLAG_NAMES, ("enabled", "single_task", "llm_selection", "issues", "notes"))
        self.assertEqual(BOOLEAN_VARS,
                         ("SWARM_ENABLED", "SWARM_SINGLE_TASK", "SWARM_LLM_SELECTION",
                          "SWARM_ISSUES", "SWARM_NOTES"))
        self.assertEqual(tuple(flags.ENABLE_TOKENS), ("1", "true", "yes", "on"))
        self.assertEqual(tuple(flags.DISABLE_TOKENS), ("0", "false", "no", "off"))

    def test_flags_workflow_publishes_every_shared_flag(self):
        doc = read("swarm-flags.yml")
        call = trigger(doc)["workflow_call"]
        self.assertEqual(set(call["outputs"]), set(FLAG_NAMES))
        resolve = doc["jobs"]["resolve"]
        self.assertEqual(resolve["outputs"],
                         {name: "${{ steps.canonical.outputs." + name + " }}" for name in FLAG_NAMES})
        canonical = step(doc, "resolve", "canonical")
        self.assertIn("scripts/swarm_flags.py", canonical["run"])
        self.assertEqual(set(canonical["env"]), set(BOOLEAN_VARS))
        for name, value in canonical["env"].items():
            self.assertEqual(value, "${{ vars." + name + " }}")

    def test_every_direct_boolean_consumer_resolves_flags_first(self):
        for name in FLAGS_CALLERS:
            with self.subTest(workflow=name):
                calls = [job for job in read(name)["jobs"].values()
                         if job.get("uses") == "./.github/workflows/swarm-flags.yml"]
                self.assertEqual(len(calls), 1)

    def test_no_job_or_step_condition_reads_a_raw_boolean_variable(self):
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for label, text in sites(read(path.name)):
                if not label.endswith(".if"):
                    continue
                for name in BOOLEAN_VARS:
                    with self.subTest(workflow=path.name, site=label, variable=name):
                        self.assertNotRegex(text, rf"(?:vars|env)\.{name}\b")
                        self.assertNotIn(f"${{{name}", text)

    def test_select_job_name_does_not_claim_a_scrum_master(self):
        for name in CONSUMERS:
            for job_id, job in read(name)["jobs"].items():
                if "select" in job_id.lower() or "select" in str(job.get("name", "")).lower():
                    with self.subTest(workflow=name, job=job_id):
                        self.assertNotIn("scrum", str(job.get("name", "")).lower())

    def test_select_gate_uses_canonical_flags_with_the_right_polarity(self):
        swarm = read("swarm.yml")
        self.assertEqual(swarm["jobs"]["select"]["name"], "Select task (PO)")
        self.assertEqual(swarm["jobs"]["select"]["needs"], "flags")
        self.assertEqual(
            swarm["jobs"]["select"]["if"],
            "needs.flags.outputs.single_task == 'true' && "
            "needs.flags.outputs.enabled == 'true' && "
            "needs.flags.outputs.llm_selection == 'true'",
        )

    def test_kill_step_fails_closed_on_the_canonical_flag(self):
        kill = step(read("swarm.yml"), "plan", "kill")["run"]
        self.assertIn('if [[ "${SWARM_ENABLED:-false}" != "true" ]]; then', kill)
        for line in ("echo 'enabled=false'", 'matrix={"include":[]}', "echo 'count=0'",
                     "exit 0", "echo 'enabled=true'"):
            self.assertIn(line, kill)
        self.assertNotIn("case ", kill)

    def test_planner_uses_the_canonical_single_task_flag(self):
        plan = step(read("swarm.yml"), "plan", "plan")["run"]
        self.assertIn('if [[ "${SWARM_SINGLE_TASK:-false}" == "true" ]]; then', plan)
        self.assertIn("WORKERS=1", plan)
        self.assertNotIn("case ", plan)

    def test_wait_and_slot_fill_gates_keep_their_polarity(self):
        swarm = read("swarm.yml")
        wait = next(s for s in swarm["jobs"]["loop"]["steps"]
                    if s.get("name") == "Wait for Delivery on main before advancing")
        self.assertEqual(wait["if"], "needs.flags.outputs.single_task == 'true'")
        self.assertIn("flags", swarm["jobs"]["loop"]["needs"])
        worker = read("swarm-worker.yml")
        fill = next(s for s in worker["jobs"]["publish"]["steps"]
                    if s.get("name") == "Fill the free slot")
        self.assertEqual(fill["if"], "always() && inputs.single_task != 'true'")

    def test_worker_receives_the_canonical_single_task_input(self):
        swarm = read("swarm.yml")
        self.assertEqual(swarm["jobs"]["worker"]["with"]["single_task"],
                         "${{ needs.flags.outputs.single_task }}")
        worker = read("swarm-worker.yml")
        inputs = trigger(worker)["workflow_call"]["inputs"]
        self.assertEqual(inputs["single_task"]["type"], "string")
        self.assertEqual(inputs["single_task"]["default"], "false")

    def test_issue_monitor_and_daily_notes_gate_on_canonical_flags(self):
        monitor = read("swarm-issue-monitor.yml")
        self.assertEqual(monitor["jobs"]["select"]["if"], "needs.flags.outputs.issues == 'true'")
        notes = read("daily-notes.yml")
        self.assertEqual(notes["jobs"]["collect"]["if"],
                         "needs.flags.outputs.notes == 'true' && needs.admission.outputs.ready == 'true'")
