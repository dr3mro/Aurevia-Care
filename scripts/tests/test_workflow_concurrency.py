"""Every workflow in the swarm delivery chain must queue, never cancel.

Branch protection checks the exact SHA. If a newer push cancels the older
run, that SHA never receives a verdict and the swarm can merge unverified
code. Queueing per ref keeps every SHA's verdict while serializing expensive
full verification within main, each PR and each tag.
"""
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


class WorkflowConcurrencyTest(unittest.TestCase):
    def workflows(self):
        return sorted(WORKFLOWS.glob("*.yml"))

    def test_every_workflow_declares_a_queueing_concurrency_group(self):
        paths = self.workflows()
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(workflow=path.name):
                config = load(path)
                self.assertIn("concurrency", config)
                concurrency = config["concurrency"]
                self.assertTrue(concurrency.get("group"))
                self.assertIs(concurrency.get("cancel-in-progress"), False)
                # cancel-in-progress:false protects only the running run. Without
                # queue:max GitHub keeps a single pending run and replaces it with
                # each newer trigger, so a frequently dispatched workflow can be
                # starved indefinitely (the 18 unmerged green PRs incident).
                self.assertEqual(concurrency.get("queue"), "max")

    def test_swarm_waves_overlap_with_a_group_per_run(self):
        """Waves run side by side; the plan job and the 20-worker cap keep them safe."""
        concurrency = load(WORKFLOWS / "swarm.yml")["concurrency"]
        self.assertIn("github.run_id", concurrency["group"])

    def test_delivery_is_scoped_per_ref_not_per_sha(self):
        """Per-ref groups serialize; per-SHA groups would run fully in parallel."""
        group = load(WORKFLOWS / "delivery.yml")["concurrency"]["group"]
        self.assertIn("github.ref", group)
        self.assertNotIn("github.sha", group)


if __name__ == "__main__":
    unittest.main()
