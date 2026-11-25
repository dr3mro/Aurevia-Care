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


if __name__ == "__main__":
    unittest.main()
