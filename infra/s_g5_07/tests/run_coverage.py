"""Run the S-G5-07 unit tests explicitly for stdlib trace coverage runs.

``unittest.main()`` discovers no tests when a test file is executed directly by
``python -m trace``, so this runner loads the suite by path instead. It is not a
production module and is excluded from the coverage report itself.
"""
import sys
import unittest
from pathlib import Path

suite = unittest.defaultTestLoader.discover(str(Path(__file__).resolve().parent),
                                           pattern="test_*.py")
result = unittest.TextTestRunner(verbosity=0).run(suite)
sys.exit(0 if result.wasSuccessful() else 1)
