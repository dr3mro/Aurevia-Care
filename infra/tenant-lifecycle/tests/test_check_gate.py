"""The S-G5-08 gate's negative control must fail shut.

``check.sh`` proves the violation fixture is rejected. A weak control that
accepts *any* non-zero status would also accept exit 2 (an unreadable fixture)
or a single irrelevant failed check, silently disabling the safety evidence.
These guard tests keep the shell gate aligned with the breaches the shipped
violation fixture actually demonstrates.
"""
import importlib.util
import json
import re
import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
MODULE_PATH = BASE / "tenant_lifecycle_evidence.py"
SPEC = importlib.util.spec_from_file_location("tenant_lifecycle_evidence", MODULE_PATH)
tlev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tlev)

CHECK = (BASE / "check.sh").read_text(encoding="utf-8")
VIOLATION = json.loads(
    (BASE / "fixtures" / "violation_lifecycle_evidence.json").read_text(encoding="utf-8"))


class NegativeControlTest(unittest.TestCase):
    def test_violation_fixture_has_breaches_to_assert(self):
        expected = set(tlev.evaluate(VIOLATION)["failed"])
        self.assertTrue(expected, "the violation fixture must fail at least one check")

    def test_gate_requires_exact_exit_one_not_any_nonzero(self):
        self.assertIn("set +e", CHECK)
        self.assertIn("status=$?", CHECK)
        self.assertRegex(CHECK, r'"\$status"\s+-ne\s+1')
        self.assertIn("set -e", CHECK)

    def test_gate_records_the_breach_report_instead_of_discarding_it(self):
        self.assertRegex(CHECK, r"report=\$\(python3 [^\n]*violation_lifecycle_evidence\.json")
        self.assertIn('grep -qF "[FAIL] $expected:"', CHECK)

    def test_gate_asserts_every_breach_the_violation_fixture_demonstrates(self):
        expected = set(tlev.evaluate(VIOLATION)["failed"])
        # Fold shell line continuations so a wrapped `for expected in ...` list
        # can be inspected as one token stream.
        flat = re.sub(r"\\\s*\n", " ", CHECK)
        listed = set()
        for line in flat.splitlines():
            if "for expected in" in line:
                listed.update(token.rstrip(";")
                              for token in line.split("for expected in", 1)[1].split())
                break
        self.assertTrue(listed, "check.sh must list the expected breach names")
        self.assertTrue(expected <= listed, f"gate omits breaches: {sorted(expected - listed)}")


if __name__ == "__main__":
    unittest.main()
