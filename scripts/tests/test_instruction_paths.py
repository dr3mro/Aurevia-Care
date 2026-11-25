"""Regression guard for canonical instruction references.

Instruction files point at other Markdown documents by relative path. A bare
name that no longer exists at the repository root silently sends an agent to
the wrong place, so this test binds each documented reference to a file that
is actually present. It is a documentation-path test, not a policy test: the
authoritative policy text lives in the instruction files themselves.
"""

import unittest
from pathlib import Path

from helpers import load

ROOT = Path(__file__).resolve().parents[2]
GEN = load("gen-opencode")

# (document, canonical reference that the document must name).
CANONICAL_REFERENCES = (
    ("README.md", ".opencode/config/PROJECT_V1_SPEC.md"),
    ("README.md", ".opencode/config/README-SETUP.md"),
    ("README.md", ".opencode/setup/AGENTS.md"),
    (".opencode/setup/AGENTS.md", ".opencode/setup/CODING_STYLE.md"),
    (".opencode/setup/AGENTS.md", ".opencode/config/README-SETUP.md"),
    (".opencode/setup/AGENTS.md", ".opencode/config/PROJECT_V1_SPEC.md"),
    (".opencode/setup/AGENTS.md", ".opencode/config/WORKSPACE.md"),
    (".opencode/setup/AGENTS.md", ".opencode/config/MEMORY.md"),
    (".opencode/setup/AGENTS.md", ".opencode/setup/CONSTRAINTS.md"),
    ("tasks/_head.md", ".opencode/config/PROJECT_V1_SPEC.md"),
    ("tasks/_head.md", ".opencode/setup/CONSTRAINTS.md"),
)

# Paths that were documented but do not exist in this layout.
STALE_REFERENCES = (
    ("README.md", "`PROJECT_V1_SPEC.md`"),
    ("README.md", "`README-SETUP.md`"),
    ("README.md", "`AGENTS.md`"),
    (".opencode/setup/AGENTS.md", "`CONSTRAINTS.md`"),
    (".opencode/setup/AGENTS.md", ".agent/"),
    ("tasks/_head.md", "`PROJECT_V1_SPEC.md`"),
    (".opencode/config/WORKSPACE.md", "docs/archive/PROGRESS.md"),
)


class InstructionPathTest(unittest.TestCase):
    """Assert that instruction documents name paths that resolve in the repo."""

    def test_canonical_references_are_named_and_exist(self):
        """Every canonical reference is both cited and present on disk."""
        for document, reference in CANONICAL_REFERENCES:
            with self.subTest(document=document, reference=reference):
                text = (ROOT / document).read_text(encoding="utf-8")
                self.assertIn(reference, text)
                self.assertTrue((ROOT / reference).exists())

    def test_stale_references_are_removed(self):
        """Retired root-level and singular-tree paths are no longer cited."""
        for document, stale in STALE_REFERENCES:
            with self.subTest(document=document, stale=stale):
                text = (ROOT / document).read_text(encoding="utf-8")
                self.assertNotIn(stale, text)


class SetupInstructionCoverageTest(unittest.TestCase):
    """Every Markdown file under .opencode/setup must be loaded by every agent."""

    def test_every_setup_markdown_file_is_natively_loaded(self):
        """The generated instruction list covers all setup documents."""
        setup = ROOT / ".opencode" / "setup"
        expected = {path.relative_to(ROOT).as_posix() for path in setup.glob("*.md")}
        self.assertTrue(expected)
        missing = expected.difference(GEN.INSTRUCTIONS)
        self.assertEqual(missing, set())


if __name__ == "__main__":
    unittest.main()
