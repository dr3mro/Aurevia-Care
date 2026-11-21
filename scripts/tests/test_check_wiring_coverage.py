"""Coverage-only tests for the command-line entry point of scripts/check-wiring.py.

The existing suite drives the gate's public functions directly, which leaves the
``if __name__ == "__main__": sys.exit(main())`` block unexecuted. These tests run that
block in-process so the entry line and its branch are measured. The script's ROOT is
redirected to a temporary fixture, so no real repository file is read or written and
the result does not depend on the state of the working tree.
"""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT, load

wiring = load("check-wiring")
SCRIPT = ROOT / "scripts" / "check-wiring.py"
MAIN_SOURCE = "apps/api/src/main/java/com/synthetic"


class CheckWiringEntryPointTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.root))
        (self.root / "scripts").mkdir()
        (self.root / "db/migrations").mkdir(parents=True)

    def run_entry_point(self, argv=None):
        """Execute check-wiring.py as __main__ against the temporary fixture.

        Compiling the real source with its real path keeps coverage attributed to
        scripts/check-wiring.py; supplying a fake __file__ in the executed namespace
        redirects the ``Path(__file__).resolve().parents[1]`` ROOT constant to the
        temporary fixture. sys.exit is captured as SystemExit and returned.
        """
        code = compile(SCRIPT.read_text(), str(SCRIPT), "exec")
        fake_file = self.root / "scripts" / "check-wiring.py"
        namespace = {"__name__": "__main__", "__file__": str(fake_file)}
        with mock.patch.object(sys, "argv", ["check-wiring.py"] + (argv or [])):
            with self.assertRaises(SystemExit) as raised:
                exec(code, namespace)
        return raised.exception.code

    def add_orphan_service(self):
        folder = self.root / MAIN_SOURCE
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "OrphanService.java").write_text("class OrphanService {}")

    def test_entry_point_exits_zero_on_a_clean_tree(self):
        # Only the __main__ block executes this path; main() itself is covered by the
        # existing suite. A clean fixture has no findings, so the CLI exits 0.
        self.assertEqual(self.run_entry_point(), 0)

    def test_entry_point_exits_one_on_a_new_finding(self):
        self.add_orphan_service()
        self.assertEqual(self.run_entry_point(), 1)


if __name__ == "__main__":
    unittest.main()
