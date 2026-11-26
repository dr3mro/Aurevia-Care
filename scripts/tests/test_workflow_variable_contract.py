"""Workflows read boolean repository variables with one shared token contract."""
import json
import re
import unittest

import yaml

from helpers import ROOT, load as load_script

WORKFLOWS = ROOT / ".github" / "workflows"
flags = load_script("swarm_flags")
ENABLE_JSON = '["1","true","yes","on"]'
DISABLE_JSON = '["0","false","no","off"]'
VARS = ("SWARM_ENABLED", "SWARM_SINGLE_TASK", "SWARM_LLM_SELECTION", "SWARM_ISSUES")


def read(name):
    """Parse a workflow file as YAML, preserving the on-key quirk of PyYAML."""
    return yaml.safe_load((WORKFLOWS / name).read_text(encoding="utf-8"))


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


def case_tokens(run):
    """Extract the token alternatives from the first bash case statement."""
    match = re.search(r'case "\$\{[^}]+\}" in\s*\n\s*([^|\n]+(?:\|[^\n]+)?)', run)
    return frozenset(match.group(1).strip().rstrip(")").split("|")) if match else frozenset()


class WorkflowVariableContractTest(unittest.TestCase):
    def test_shared_constants_match_the_workflow_literals(self):
        self.assertEqual(tuple(flags.ENABLE_TOKENS), ("1", "true", "yes", "on"))
        self.assertEqual(tuple(flags.DISABLE_TOKENS), ("0", "false", "no", "off"))
        self.assertEqual(flags.ENABLE_JSON, ENABLE_JSON)
        self.assertEqual(flags.DISABLE_JSON, DISABLE_JSON)
        self.assertEqual(json.loads(ENABLE_JSON), list(flags.ENABLE_TOKENS))
        self.assertEqual(json.loads(DISABLE_JSON), list(flags.DISABLE_TOKENS))

    def test_every_workflow_uses_the_token_contract(self):
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for label, text in sites(read(path.name)):
                if not any(name in text for name in VARS):
                    continue
                with self.subTest(workflow=path.name, site=label):
                    if label.endswith(".run"):
                        self.assertNotRegex(text, r'SWARM_\w+(?:,,\})?\s*(?:==|!=)\s*["\']')
                        self.assertNotIn('=="true"', text)
                    else:
                        self.assertIn("contains(fromJSON(", text)
                        self.assertNotRegex(text, r'(?:vars|env)\.SWARM_\w+\s*(?:==|!=)\s*["\']')
                        for literal in re.findall(r"fromJSON\('([^']+)'\)", text):
                            self.assertIn(json.loads(literal),
                                          (list(flags.ENABLE_TOKENS), list(flags.DISABLE_TOKENS)))

    def test_select_job_name_does_not_claim_a_scrum_master(self):
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for job_id, job in read(path.name)["jobs"].items():
                if "select" in job_id.lower() or "select" in str(job.get("name", "")).lower():
                    with self.subTest(workflow=path.name, job=job_id):
                        self.assertNotIn("scrum", str(job.get("name", "")).lower())

    def test_select_gate_uses_both_token_sets_with_the_right_polarity(self):
        swarm = read("swarm.yml")
        self.assertEqual(swarm["jobs"]["select"]["name"], "Select task (PO)")
        self.assertEqual(
            swarm["jobs"]["select"]["if"],
            f"contains(fromJSON('{ENABLE_JSON}'), vars.SWARM_SINGLE_TASK) && "
            f"!contains(fromJSON('{DISABLE_JSON}'), vars.SWARM_ENABLED) && "
            f"contains(fromJSON('{ENABLE_JSON}'), vars.SWARM_LLM_SELECTION)",
        )

    def test_shell_kill_switch_uses_disable_tokens_and_keeps_its_consequences(self):
        kill = step(read("swarm.yml"), "plan", "kill")["run"]
        self.assertIn('flag="${SWARM_ENABLED:-true}"', kill)
        self.assertIn('case "${flag,,}" in', kill)
        self.assertEqual(case_tokens(kill), frozenset(flags.DISABLE_TOKENS))
        for line in ("echo 'enabled=false'", 'matrix={"include":[]}', "echo 'count=0'",
                     "exit 0", "echo 'enabled=true'"):
            self.assertIn(line, kill)

    def test_planner_single_task_case_uses_enable_tokens_and_limits_workers(self):
        plan = step(read("swarm.yml"), "plan", "plan")["run"]
        self.assertIn('flag="${SWARM_SINGLE_TASK:-false}"', plan)
        self.assertIn('case "${flag,,}" in', plan)
        self.assertEqual(case_tokens(plan), frozenset(flags.ENABLE_TOKENS))
        self.assertLess(plan.index("WORKERS=1"), plan.index(";;"))

    def test_wait_and_slot_fill_gates_keep_their_polarity(self):
        swarm = read("swarm.yml")
        wait = next(s for s in swarm["jobs"]["loop"]["steps"]
                    if s.get("name") == "Wait for Delivery on main before advancing")
        self.assertEqual(wait["if"],
                         f"contains(fromJSON('{ENABLE_JSON}'), vars.SWARM_SINGLE_TASK)")
        worker = read("swarm-worker.yml")
        fill = next(s for s in worker["jobs"]["publish"]["steps"]
                    if s.get("name") == "Fill the free slot")
        self.assertEqual(fill["if"],
                         f"always() && !contains(fromJSON('{ENABLE_JSON}'), env.SWARM_SINGLE_TASK)")

    def test_issue_monitor_negates_disable_tokens(self):
        monitor = read("swarm-issue-monitor.yml")
        self.assertEqual(
            monitor["jobs"]["select"]["if"],
            "${{ !contains(fromJSON('" + DISABLE_JSON + "'), vars.SWARM_ISSUES) }}",
        )
