"""Sparse checkouts must include the file that swarm commands actually read.

Commit 3a309e1b moved slots.json from .github/swarm to .opencode/config and
updated workflow comments and path triggers, but left the sparse-checkout lists
in swarm-images.yml and swarm.yml naming the removed directory. Every command
that loads slots.json then failed with FileNotFoundError in CI (issue 1589).
These tests bind each checkout path to the path swarm.py really uses so a future
layout move cannot silently break the workflows again.
"""
import re
import unittest
from pathlib import Path

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"


def slots_default():
    """Return the slots.json path scripts/swarm.py uses when --slots is omitted."""
    source = (ROOT / "scripts" / "swarm.py").read_text(encoding="utf-8")
    match = re.search(r'--slots",\s*default="([^"]+)"', source)
    if not match:
        raise AssertionError("scripts/swarm.py must declare a --slots default path")
    return match.group(1)


def checkout_paths(path):
    """Collect sparse-checkout patterns from every step of every job in a workflow."""
    config = yaml.safe_load(path.read_text(encoding="utf-8"))
    paths = []
    for job in config["jobs"].values():
        for step in job.get("steps", []):
            sparse = step.get("with", {}).get("sparse-checkout")
            if isinstance(sparse, str):
                paths.extend(line.strip() for line in sparse.splitlines() if line.strip())
    return paths


def job_checkout_paths(job):
    """Return the sparse-checkout patterns of one job, in step order."""
    paths = []
    for step in job.get("steps", []):
        sparse = step.get("with", {}).get("sparse-checkout")
        if isinstance(sparse, str):
            paths.extend(line.strip() for line in sparse.splitlines() if line.strip())
    return paths


class SwarmSparseCheckoutTest(unittest.TestCase):
    def test_slots_default_path_exists_in_repository(self):
        """The path swarm.py reads is the authoritative, checked-in slots file."""
        self.assertTrue((ROOT / slots_default()).is_file())

    def test_workflows_that_load_slots_check_out_its_directory(self):
        """A sparse checkout without slots.json makes swarm commands fail in CI."""
        slots_dir = str(Path(slots_default()).parent)
        for name in ("swarm-images.yml", "swarm.yml"):
            with self.subTest(workflow=name):
                self.assertIn(slots_dir, checkout_paths(WORKFLOWS / name))

    def test_no_workflow_checks_out_the_removed_directory(self):
        """.github/swarm no longer exists; naming it checks out nothing."""
        for path in sorted(WORKFLOWS.glob("*.yml")):
            with self.subTest(workflow=path.name):
                self.assertNotIn(".github/swarm", checkout_paths(path))

    def test_jobs_with_local_actions_check_out_the_action_directory(self):
        """A sparse checkout must include the local action it uses, per job.

        A checkout in a different job cannot satisfy this: the local action is
        resolved by the runner on the same job that invokes ``uses: ./...``.
        """
        for path in sorted(WORKFLOWS.glob("*.yml")):
            config = yaml.safe_load(path.read_text(encoding="utf-8"))
            for name, job in config["jobs"].items():
                steps = job.get("steps", [])
                sparse_indexes = [i for i, step in enumerate(steps)
                                  if "sparse-checkout" in (step.get("with") or {})]
                if not sparse_indexes:
                    continue
                for i, step in enumerate(steps):
                    uses = str(step.get("uses", ""))
                    if not uses.startswith("./.github/actions/"):
                        continue
                    with self.subTest(workflow=path.name, job=name, action=uses):
                        self.assertTrue((ROOT / ".github" / "actions").is_dir())
                        self.assertIn(".github/actions", job_checkout_paths(job))
                        self.assertLess(min(sparse_indexes), i)

    def test_integrator_reconcile_can_load_its_setup_action(self):
        """The 16:38Z failure was a checkout without .github/actions."""
        config = yaml.safe_load((WORKFLOWS / "swarm-integrator.yml").read_text(encoding="utf-8"))
        reconcile = config["jobs"]["reconcile"]
        self.assertIn(".github/actions", job_checkout_paths(reconcile))
        steps = reconcile["steps"]
        checkout = next(i for i, step in enumerate(steps) if "sparse-checkout" in (step.get("with") or {}))
        setup = next(i for i, step in enumerate(steps)
                     if step.get("uses") == "./.github/actions/setup-tooling-python")
        self.assertLess(checkout, setup)


if __name__ == "__main__":
    unittest.main()
