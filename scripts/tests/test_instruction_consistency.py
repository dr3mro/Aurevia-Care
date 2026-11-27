"""Regression guard for a single authoritative instruction policy.

The instruction corpus must not state two different shell-permission or
task-ownership rules. This test binds the clarified wording to the files that
carry it, so a later edit cannot silently reintroduce the old contradiction.
"""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# (document, phrase that must be present).
REQUIRED_PHRASES = (
    (".opencode/config/README-SETUP.md", "scoped shell commands without prompts"),
    (".opencode/config/README-SETUP.md", "hard denies for force-push"),
    (".opencode/setup/AGENTS.md", "the full JaCoCo/Vitest/Jest gate is run by the trusted integrator"),
    ("tasks/_head.md", "only the orchestrator and the integrator write it"),
)

# (document, phrase that used to contradict the authoritative policy).
FORBIDDEN_PHRASES = (
    (".opencode/config/README-SETUP.md", "including push, asks first"),
    (".opencode/setup/AGENTS.md", "worker must prove 100% coverage but must not run full verify"),
    ("tasks/_head.md", "Every agent, the swarm orchestrator and the integrator read and write only this file"),
)

INCOMPLETE_DOCUMENTS = (
    ".opencode/setup/AGENTS.md",
    ".opencode/agents/orca.md",
    ".opencode/skills/aurevia-delivery/SKILL.md",
    ".opencode/skills/aurevia-scrum-master/SKILL.md",
    ".opencode/config/README-SETUP.md",
    ".opencode/config/MEMORY.md",
    "tasks/_head.md",
    "docs/SWARM.md",
    ".github/workflows/swarm.yml",
    "scripts/agent-run.sh",
    "orca-refactor.md",
    "swarm-bugs.md",
    "docs/orca-refactor/06-supervisor-design.md",
    "docs/orca-refactor/README.md",
    "docs/orca-refactor/16-final-audit.md",
)

RETIRED_STOP_PHRASES = (
    (".opencode/setup/AGENTS.md", "put it back to `[ ]` if you stop"),
    (".opencode/agents/orca.md", "put it back to `[ ]` if you stop"),
    ("tasks/_head.md", "put the mark back to `[ ]`"),
    ("scripts/agent-run.sh", "put the task back to [ ]"),
    ("orca-refactor.md", "if you stop, put it back to `[ ]`"),
)


class InstructionConsistencyTest(unittest.TestCase):
    """Assert that one authoritative policy replaces each retired contradiction."""

    def test_authoritative_phrases_are_present(self):
        """Each clarified rule is still in the instruction document."""
        for document, phrase in REQUIRED_PHRASES:
            with self.subTest(document=document, phrase=phrase):
                text = (ROOT / document).read_text(encoding="utf-8")
                self.assertIn(phrase, text)

    def test_retired_contradictions_are_absent(self):
        """No instruction document restates a superseded policy."""
        for document, phrase in FORBIDDEN_PHRASES:
            with self.subTest(document=document, phrase=phrase):
                text = (ROOT / document).read_text(encoding="utf-8")
                self.assertNotIn(phrase, text)

    def test_incomplete_marker_is_defined_across_agent_and_swarm_instructions(self):
        for document in INCOMPLETE_DOCUMENTS:
            with self.subTest(document=document):
                text = (ROOT / document).read_text(encoding="utf-8")
                self.assertIn("[!]", text)
                self.assertIn("incomplete", text.lower())

    def test_incomplete_scope_dependency_and_reopen_rules_are_explicit(self):
        agents = (ROOT / ".opencode/setup/AGENTS.md").read_text(encoding="utf-8")
        head = (ROOT / "tasks/_head.md").read_text(encoding="utf-8")
        self.assertIn("not open, taken, done, dispatchable, or a satisfied dependency", agents)
        self.assertIn("keeps its `scope:` reserved", agents)
        self.assertIn("only `[x]` satisfies dependencies", head)
        self.assertIn("explicitly reopening the task for another attempt", head)

    def test_stopping_incomplete_work_no_longer_reopens_it_implicitly(self):
        for document, phrase in RETIRED_STOP_PHRASES:
            with self.subTest(document=document, phrase=phrase):
                text = (ROOT / document).read_text(encoding="utf-8")
                self.assertNotIn(phrase, text)


if __name__ == "__main__":
    unittest.main()
