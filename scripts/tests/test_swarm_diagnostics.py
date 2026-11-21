"""Structured diagnosis of failed CI logs (OPS-06).

Every log below is invented for this test module. No real token, host, patient
identifier or private repository name appears here.
"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")

# Shaped like a secret so the masking contract is exercised, but not a real one.
TOKEN = "ghp_" + "a" * 30

FIXTURES = {
    "Maven": (
        "[INFO] BUILD FAILURE\n"
        "[INFO] --- maven-surefire-plugin:3.2.5:test (default-test) @ api ---\n"
        "[ERROR] FAIL com.example.OrderTest.rejectsMissingId\n"
        "[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0\n"
        "[ERROR] Re-run Maven using the -e switch.\n"
        "##[error]Process completed with exit code 1."
    ),
    "Vitest": (
        "> vitest run\n"
        " \u2713 src/health.test.ts (2 tests) 12ms\n"
        " FAIL src/app/routes.test.tsx > registers every route\n"
        " AssertionError: expected 27 to be 20\n"
        " Test Files  1 failed (2)\n"
        " Process completed with exit code 1"
    ),
    "Jest": (
        "> jest --ci\n"
        " Test Suites: 1 failed, 1 total\n"
        " Tests:       2 failed, 5 total\n"
        " \u25cf PatientApp > shows released reports\n"
        "   ERROR: expect(received).toBe(expected)\n"
        " Process completed with exit code 1"
    ),
    "SpotBugs": (
        "> spotbugs check\n"
        "[INFO] --- spotbugs-maven-plugin:4.8.3:check (spotbugs) @ api ---\n"
        "BugInstance: src/main/java/com/example/Service.java uses an unread field\n"
        "Total bugs: 2\n"
        "ERROR: SpotBugs found 2 bugs\n"
        "Process completed with exit code 1"
    ),
    "ESLint": (
        "> eslint src\n"
        "src/app.ts\n"
        "  3:10  error  'foo' is defined but never used  @typescript-eslint/no-unused-vars\n"
        " \u2717 1 problem (1 error, 0 warnings)\n"
        "Process completed with exit code 1"
    ),
    "TypeScript": (
        "> tsc --noEmit\n"
        "src/app.ts(12,5): error TS2322: Type 'string' is not assignable to type 'number'.\n"
        "Process completed with exit code 2"
    ),
    "Python unittest": (
        "$ python3 -I -m unittest discover -s scripts/tests -q\n"
        "FAIL: test_swarm_diagnostics.DiagnoseTests.test_tool_detection (unittest)\n"
        "Traceback (most recent call last):\n"
        "AssertionError: expected 5 to equal 7\n"
        "Ran 42 tests in 0.123s"
    ),
    "shell": (
        "$ ./check.sh\n"
        "+ docker compose up -d\n"
        "ERROR: partner endpoint connection refused\n"
        "Process completed with exit code 7"
    ),
}

NAME_EXPECTATIONS = {
    "Maven": "com.example.OrderTest.rejectsMissingId",
    "Vitest": "src/app/routes.test.tsx > registers every route",
    "Jest": "PatientApp > shows released reports",
    "SpotBugs": "src/main/java/com/example/Service.java",
    "ESLint": "src/app.ts",
    "TypeScript": "src/app.ts",
    "Python unittest": "test_swarm_diagnostics.DiagnoseTests.test_tool_detection (unittest)",
    "shell": "./check.sh",
}

CLASS_FIXTURES = {
    "implementation": FIXTURES["Vitest"],
    "infrastructure": FIXTURES["shell"],
    "task-policy": (
        "$ ./check.sh\n"
        "ERROR: protected files changed; a person must decide (needs-human).\n"
        "Process completed with exit code 4"
    ),
    "missing-tests": (
        "[INFO] BUILD FAILURE\n"
        "[ERROR] Coverage for lines (87%) does not meet global threshold (100%)\n"
        "[ERROR] 1 branch not covered in scripts/swarm.py\n"
        "Process completed with exit code 1"
    ),
    "check-parity": (
        "$ ./check.sh\n"
        "ERROR: openapi contract mismatch: route POST /patients missing from the contract\n"
        "Process completed with exit code 1"
    ),
    "unknown": "building\nnothing to report\nno failures here",
}


class RecordingGh:
    """Answer the few queries the reporting helpers make, recording every call."""

    def __init__(self):
        self.calls = []

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("pr", "view"):
            return json.dumps({"labels": []})
        if args[:2] == ("pr", "list"):
            return "[]"
        if args[0] == "api" and "issues?state=open" in args[-1]:
            return "[]"
        if args[:2] == ("issue", "create"):
            return "https://github.com/example/repo/issues/42\n"
        return ""

    def body_of(self, verb):
        call = [c for c in self.calls if c[:2] == verb][-1]
        return call[call.index("--body") + 1]


class DiagnoseTests(unittest.TestCase):
    def test_tool_and_first_error_are_detected_for_each_tool(self):
        for tool, log in FIXTURES.items():
            with self.subTest(tool=tool):
                report = swarm.diagnose(log)
                self.assertEqual(report["tool"], tool)
                self.assertTrue(report["error"])

    def test_names_are_extracted_for_each_tool(self):
        for tool, name in NAME_EXPECTATIONS.items():
            with self.subTest(tool=tool):
                report = swarm.diagnose(FIXTURES[tool])
                self.assertLessEqual(len(report["names"]), 5)
                self.assertIn(name, report["names"])

    def test_problem_classification(self):
        for problem, log in CLASS_FIXTURES.items():
            with self.subTest(problem=problem):
                self.assertEqual(swarm.diagnose(log)["class"], problem)

    def test_exit_code_is_captured_when_present_and_none_when_absent(self):
        self.assertEqual(swarm.diagnose(FIXTURES["shell"])["exit_code"], 7)
        self.assertEqual(swarm.diagnose(FIXTURES["TypeScript"])["exit_code"], 2)
        self.assertIsNone(swarm.diagnose(FIXTURES["Python unittest"])["exit_code"])

    def test_a_secret_is_masked_in_every_string_field(self):
        log = f"FAIL src/foo.test.ts\nERROR: used token {TOKEN}\nProcess completed with exit code 1"
        report = swarm.diagnose(log)
        strings = [report["step"], report["error"], *report["names"]]
        self.assertTrue(strings)
        for value in strings:
            self.assertNotIn(TOKEN, value)
        self.assertNotIn(TOKEN, repr(report))

    def test_an_empty_or_missing_log_has_unknown_tool_and_no_details(self):
        for empty in ("", None):
            with self.subTest(empty=empty):
                report = swarm.diagnose(empty)
                self.assertEqual(report["tool"], "unknown")
                self.assertEqual(report["class"], "unknown")
                self.assertEqual(report["names"], [])
                self.assertEqual(report["step"], "")
                self.assertEqual(report["error"], "")
                self.assertIsNone(report["exit_code"])

    def test_duplicate_names_are_removed_and_only_five_are_kept(self):
        lines = ["FAIL src/test0.ts", "FAIL src/test1.ts", "FAIL src/test0.ts"]
        lines += [f"FAIL src/test{i}.ts" for i in range(2, 8)]
        report = swarm.diagnose("\n".join(lines))
        self.assertEqual(report["names"], [f"src/test{i}.ts" for i in range(5)])

    def test_a_huge_log_stays_bounded(self):
        log = ("\n".join(f"building step {i}" for i in range(5000)) + "\n"
               + ("verbose " * 20000) + "\n"
               + "FAIL src/huge.test.ts\nERROR: done\nProcess completed with exit code 1")
        report = swarm.diagnose(log)
        self.assertEqual(report["exit_code"], 1)
        self.assertLessEqual(len(report["names"]), 5)
        self.assertIn("src/huge.test.ts", report["names"])
        self.assertLessEqual(len(report["error"]), 100)


class DiagnosisBlockTests(unittest.TestCase):
    def test_the_summary_comes_before_the_clipped_raw_tail(self):
        log = FIXTURES["Vitest"] + "\n```evil\n"
        block = swarm.diagnosis(log)
        self.assertIn("**Diagnosis**", block)
        self.assertLess(block.index("**Diagnosis**"), block.index("```text"))
        self.assertIn("tool: Vitest", block)
        self.assertNotIn("```evil", block)

    def test_an_empty_log_has_a_summary_but_no_tail(self):
        block = swarm.diagnosis("")
        self.assertIn("**Diagnosis**", block)
        self.assertNotIn("```text", block)


class ReportingTests(unittest.TestCase):
    def test_fail_puts_the_structured_summary_before_the_raw_tail(self):
        fake = RecordingGh()
        with mock.patch.object(swarm, "gh", fake), \
             mock.patch.object(swarm, "waiting_waves", return_value=1):
            swarm.fail(9, "Integration failed", FIXTURES["Vitest"])
        comment = fake.body_of(("pr", "comment"))
        self.assertLess(comment.index("**Diagnosis**"), comment.index("```text"))
        self.assertIn("tool: Vitest", comment)

    def test_a_stuck_issue_puts_the_structured_summary_before_the_detail(self):
        fake = RecordingGh()
        with mock.patch.object(swarm, "gh", fake):
            swarm.report_pr_stuck(7, "the fix worker failed", "detail text",
                                  log_text=FIXTURES["Vitest"])
        body = fake.body_of(("issue", "create"))
        self.assertLess(body.index("**Diagnosis**"), body.index("```text"))
        self.assertLess(body.index("```text"), body.index("detail text"))
        self.assertIn("tool: Vitest", body)

    def test_a_handoff_puts_the_structured_summary_before_the_log_tail(self):
        directory = Path(tempfile.mkdtemp())
        (directory / "check.log").write_text(FIXTURES["Vitest"])
        fake = RecordingGh()
        with mock.patch.object(swarm, "gh", fake):
            swarm.report_worker_handoff(
                {"id": "CW-001", "kind": "story", "branch": "swarm/CW-001-4"},
                "ok", "failed", "merged", str(directory), "7")
        body = fake.body_of(("issue", "create"))
        self.assertLess(body.index("**Diagnosis**"), body.index("```text"))
        self.assertIn("tool: Vitest", body)


if __name__ == "__main__":
    unittest.main()
