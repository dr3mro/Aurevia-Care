"""Regression guard for the mandatory independent-review gate.

The quality gate must never offer a switch that skips reviewer verdicts: a
bypass would let a task pass without the GO evidence required by the delivery
workflow. This test checks the gate script itself, not a mock of its steps.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "quality-gate.sh"


class MandatoryReviewGateTest(unittest.TestCase):
    """Assert that the verdict checker runs on every quality-gate invocation."""

    def test_no_review_bypass_remains(self):
        """The retired switch is absent from the gate script."""
        self.assertNotIn("SWARM_REVIEW_GATE", GATE.read_text(encoding="utf-8"))

    def test_the_verdict_checker_is_always_invoked(self):
        """The canonical checker invocation is present with its two arguments."""
        lines = [line for line in GATE.read_text(encoding="utf-8").splitlines()
                 if line.lstrip().startswith("python3 ") and "check-verdicts.py" in line]
        self.assertEqual(len(lines), 1)
        self.assertIn('"$notes" --base "$base"', lines[0])

    def test_the_retired_switch_cannot_skip_the_verdict_check(self):
        """Running the gate with the old bypass set still invokes the verdict checker."""
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            (root / "scripts").mkdir()
            shutil.copy(GATE, root / "scripts" / "quality-gate.sh")
            (root / "bin").mkdir()
            stub = root / "bin" / "python3"
            stub.write_text(
                '#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\n'
                'case "$*" in *check-constraints*) exit 0;; *check-verdicts*) exit 7;; esac\n'
                'exit 0\n',
                encoding="utf-8",
            )
            stub.chmod(0o755)
            env = dict(os.environ)
            env.update(PATH=f"{root / 'bin'}:{env.get('PATH', '')}",
                       CALLS=str(root / "calls.log"), SWARM_REVIEW_GATE="off")
            result = subprocess.run(
                ["bash", str(root / "scripts" / "quality-gate.sh"), "base", "notes.md"],
                cwd=root, env=env, capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 7)
            calls = (root / "calls.log").read_text(encoding="utf-8")
            self.assertIn("check-verdicts.py", calls)
            self.assertIn("check-constraints.py", calls)


if __name__ == "__main__":
    unittest.main()
