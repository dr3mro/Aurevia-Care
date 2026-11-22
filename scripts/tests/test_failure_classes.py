"""Classify failed swarm pull requests and name the layer that should catch them.

Every log, task and file name below is invented for this test module: no real
token, host, patient identifier or private repository name appears here. The
fixtures mirror the five classes that `scripts/swarm.py diagnose` reports, and
the assertions also prove that task text and changed files contribute to the
decision instead of being ignored.
"""
import inspect
import unittest

from helpers import load

failure_classes = load("failure_classes")

# Synthetic failure logs, one per class. Each contains only the keywords of its
# own class (apart from the generic build/test noise used by two fixtures).
FIXTURES = {
    "task-policy": (
        "$ ./check.sh\n"
        "ERROR: protected files changed; a person must decide (needs-human).\n"
        "Process completed with exit code 4"
    ),
    "check-parity": (
        "$ ./check.sh\n"
        "ERROR: check parity: openapi contract mismatch; route POST /patients "
        "is missing from the contract locally but fails in Delivery.\n"
        "Process completed with exit code 1"
    ),
    "infrastructure": (
        "$ ./check.sh\n"
        "ERROR: partner endpoint connection refused; HTTP 503 after a 30s timeout.\n"
        "Process completed with exit code 7"
    ),
    "missing-tests": (
        "[INFO] BUILD FAILURE\n"
        "[ERROR] Coverage for lines (87%) does not meet global threshold (100%)\n"
        "[ERROR] 1 branch not covered in scripts/example.py\n"
        "Process completed with exit code 1"
    ),
    "implementation": (
        "[INFO] BUILD FAILURE\n"
        "[ERROR] FAIL com.example.OrderTest.rejectsMissingId\n"
        "[ERROR] AssertionError: expected 5 to equal 7\n"
        "Process completed with exit code 1"
    ),
}

EXPECTED = {
    "task-policy": ("task-policy", "task template"),
    "check-parity": ("check-parity", "integrator-gate"),
    "infrastructure": ("infrastructure", "contract test"),
    "missing-tests": ("missing-tests", "worker-check"),
    "implementation": ("implementation", "unit test"),
}

ALLOWED_CATCH_LAYERS = {
    "worker-check", "integrator-gate", "unit test", "contract test", "task template",
}


class ClassificationTests(unittest.TestCase):
    def test_a_fixture_log_returns_its_class_and_catch_layer(self):
        for problem, log in FIXTURES.items():
            with self.subTest(problem=problem):
                expected_class, expected_layer = EXPECTED[problem]
                self.assertEqual(
                    failure_classes.classify(log, "", []),
                    {"class": expected_class, "catch_layer": expected_layer},
                )

    def test_every_class_has_one_catch_layer_and_no_layer_is_unused(self):
        self.assertEqual(set(failure_classes.CLASSES), set(FIXTURES))
        self.assertEqual(set(failure_classes.CATCH_LAYERS), set(FIXTURES))
        self.assertEqual(set(failure_classes.CATCH_LAYERS.values()), ALLOWED_CATCH_LAYERS)

    def test_task_text_alone_can_decide_the_class(self):
        result = failure_classes.classify(
            "", "The change is out of scope: a person must decide (needs-human).", [])
        self.assertEqual(result, {"class": "task-policy", "catch_layer": "task template"})

    def test_changed_files_alone_can_decide_the_class(self):
        result = failure_classes.classify(
            "", "", ["contracts/openapi/generated/openapi.yaml"])
        self.assertEqual(result, {"class": "check-parity", "catch_layer": "integrator-gate"})

    def test_a_log_without_a_recognised_signal_defaults_to_implementation(self):
        result = failure_classes.classify("building\nnothing failed here", "", [])
        self.assertEqual(result, {"class": "implementation", "catch_layer": "unit test"})

    def test_empty_and_missing_inputs_default_without_raising(self):
        for args in ((), ("", "", []), (None, None, None)):
            with self.subTest(args=args):
                self.assertEqual(
                    failure_classes.classify(*args),
                    {"class": "implementation", "catch_layer": "unit test"},
                )

    def test_all_three_inputs_are_used_in_the_same_decision(self):
        result = failure_classes.classify(
            "AssertionError: expected 7 to equal 5",
            "the check is green locally but fails in Delivery",
            ["contracts/openapi/generated/openapi.yaml"],
        )
        self.assertEqual(result, {"class": "check-parity", "catch_layer": "integrator-gate"})

    def test_policy_signals_win_over_an_ordinary_assertion_failure(self):
        result = failure_classes.classify(
            "AssertionError: protected file changed (needs-human)\nFAIL OrderTest", "", [])
        self.assertEqual(result, {"class": "task-policy", "catch_layer": "task template"})

    def test_parity_signals_win_over_a_coverage_signal(self):
        result = failure_classes.classify(
            "check parity: coverage passed locally but failed in Delivery", "", [])
        self.assertEqual(result, {"class": "check-parity", "catch_layer": "integrator-gate"})

    def test_the_classifier_never_imports_a_network_client(self):
        source = inspect.getsource(failure_classes)
        for forbidden in ("urllib", "socket", "http.client", "requests", "subprocess"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
