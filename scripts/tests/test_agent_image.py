"""Tests that the prepared agent image pins its CLI version reproducibly.

OPS-CW-003-02: a floating `@opencode/cli@latest` makes the worker toolchain
impossible to reproduce from the repository. These checks fail if the pin is
removed or if the workflow and Dockerfile disagree.
"""
import unittest

from helpers import ROOT

DOCKERFILE = ROOT / ".github" / "agent-image" / "Dockerfile"
WORKFLOW = ROOT / ".github" / "workflows" / "agent-image.yml"
PINNED_VERSION = "2.3.2"


class AgentImagePinTest(unittest.TestCase):
    def test_dockerfile_pins_cli_version(self):
        text = DOCKERFILE.read_text(encoding="utf-8")
        self.assertIn(f"ARG OPENCODE_VERSION={PINNED_VERSION}", text)
        self.assertNotIn("ARG OPENCODE_VERSION=latest", text)

    def test_workflow_default_matches_dockerfile_pin(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(f"default: {PINNED_VERSION}", text)
        self.assertNotIn("default: latest", text)

    def test_workflow_fallback_matches_dockerfile_pin(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(f"inputs.opencode_version || '{PINNED_VERSION}'", text)
        self.assertNotIn("inputs.opencode_version || 'latest'", text)

    def test_publish_run_records_version_and_digest(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("imagetools inspect", text)
        self.assertIn("OpenCode CLI version:", text)


if __name__ == "__main__":
    unittest.main()
