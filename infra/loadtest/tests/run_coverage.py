"""Run the load-generator tests and CLI entry for stdlib trace coverage.

``unittest.main()`` discovers no tests when a test file is executed directly by
``python -m trace``, so this runner loads the suite by path. It also executes the
generator's ``__main__`` block with ``--help`` so the CLI entry line is covered
in the same trace session without opening a network connection. This runner is
test tooling and is excluded from the coverage report itself.
"""
import contextlib
import io
import runpy
import sys
import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
MODULE = BASE / "load_generator.py"

suite = unittest.defaultTestLoader.discover(str(Path(__file__).resolve().parent),
                                             pattern="test_*.py")
result = unittest.TextTestRunner(verbosity=0).run(suite)
if not result.wasSuccessful():
    sys.exit(1)

sys.argv = [str(MODULE), "--help"]
try:
    with contextlib.redirect_stdout(io.StringIO()):
        runpy.run_path(str(MODULE), run_name="__main__")
except SystemExit as exit_code:
    if exit_code.code not in (0, None):
        sys.exit(exit_code.code)
