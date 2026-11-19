"""Run S-G5-08 tests and the CLI entry point for stdlib trace coverage.

``unittest.main()`` discovers no tests when a test file is executed directly by
``python -m trace``, so this runner loads the suite by path. It also executes the
evaluator's ``__main__`` block against a synthetic fixture so the CLI entry line
is covered in the same trace session. This runner is test tooling, not a
production module, and is excluded from the coverage report itself.
"""
import runpy
import sys
import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
MODULE = BASE / "tenant_lifecycle_evidence.py"
FIXTURE = BASE / "fixtures" / "synthetic_lifecycle_evidence.json"

suite = unittest.defaultTestLoader.discover(str(Path(__file__).resolve().parent),
                                            pattern="test_*.py")
result = unittest.TextTestRunner(verbosity=0).run(suite)
if not result.wasSuccessful():
    sys.exit(1)

sys.argv = [str(MODULE), str(FIXTURE)]
try:
    runpy.run_path(str(MODULE), run_name="__main__")
except SystemExit as exit_code:
    if exit_code.code not in (0, None):
        sys.exit(exit_code.code)
