"""Behaviour tests for the passive ORCA benchmark helpers.

Fixtures are synthetic or read-only repository paths. No timing, tokeniser,
network, Git or agent launch is used, and unavailable metrics stay textual.
"""

import ast
import json
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT

spec = __import__("importlib.util", fromlist=["spec_from_file_location"])
orca_benchmark = None


def load_module():
    """Load the benchmark module by path so the test works under python3 -I."""
    module_spec = spec.spec_from_file_location(
        "orca_benchmark", ROOT / "scripts" / "orca_benchmark.py")
    module = spec.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    return module


class CountTests(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_count_files_handles_empty_and_matching_globs(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            self.assertEqual(self.module.count_files(root, "*.py"), 0)
            (root / "a.py").write_text("x = 1\n", encoding="utf-8")
            (root / "b.py").write_text("x = 2\n", encoding="utf-8")
            self.assertEqual(self.module.count_files(root, "*.py"), 2)

    def test_count_bytes_sums_only_given_paths(self):
        with tempfile.TemporaryDirectory() as scratch:
            paths = []
            for name, text in (("a", "abc"), ("b", "de")):
                path = Path(scratch) / name
                path.write_text(text, encoding="utf-8")
                paths.append(path)
            self.assertEqual(self.module.count_bytes([]), 0)
            self.assertEqual(self.module.count_bytes(paths), 5)

    def test_doc_load_count_counts_a_synthetic_profile(self):
        with tempfile.TemporaryDirectory() as scratch:
            profile = Path(scratch) / "config.json"
            profile.write_text(json.dumps({"instructions": ["a", "b", "c"]}), encoding="utf-8")
            self.assertEqual(self.module.doc_load_count(profile), 3)

    def test_doc_load_count_reads_the_real_five_entry_config(self):
        self.assertEqual(self.module.doc_load_count(ROOT / ".opencode" / "opencode.json"), 5)

    def test_doc_load_count_rejects_missing_and_malformed_profiles(self):
        with tempfile.TemporaryDirectory() as scratch:
            missing = Path(scratch) / "missing.json"
            with self.assertRaisesRegex(ValueError, "cannot read"):
                self.module.doc_load_count(missing)
            bad = Path(scratch) / "bad.json"
            bad.write_text("{bad", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "cannot read"):
                self.module.doc_load_count(bad)

    def test_count_tests_ignores_other_defs_and_counts_classes(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "suite.py"
            path.write_text("def helper(): pass\nclass T:\n def test_a(self): pass\n"
                            "async def test_b(): pass\n", encoding="utf-8")
            self.assertEqual(self.module.count_tests(path), 2)
            empty = Path(scratch) / "empty.py"
            empty.write_text("x = 1\n", encoding="utf-8")
            self.assertEqual(self.module.count_tests(empty), 0)

    def test_count_agents_and_skills(self):
        self.assertEqual(self.module.count_agents(ROOT), 39)
        self.assertEqual(self.module.count_skills(ROOT), 40)
        with tempfile.TemporaryDirectory() as scratch:
            self.assertEqual(self.module.count_agents(Path(scratch)), 0)
            self.assertEqual(self.module.count_skills(Path(scratch)), 0)

    def test_measure_is_deterministic_and_lists_unavailable_metrics(self):
        first = self.module.measure(ROOT)
        second = self.module.measure(ROOT)
        self.assertEqual(first, second)
        self.assertIsInstance(first["agents"], int)
        self.assertIn("tokens", first["unavailable"])
        self.assertNotIn("duration_seconds", first)
        self.assertNotIn("40", str(first["unavailable"]))


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_all_five_evidence_sources_are_available(self):
        self.assertEqual(self.module.EvidenceSource.TOOL, "tool")
        self.assertEqual(self.module.EvidenceSource.REVIEWER, "reviewer")
        self.assertEqual(self.module.EvidenceSource.SIMULATED_USER, "simulated-user")
        self.assertEqual(self.module.EvidenceSource.HUMAN, "human")
        self.assertEqual(self.module.EvidenceSource.UNAVAILABLE, "unavailable")

    def test_ratio_stores_parts_and_handles_zero_denominator(self):
        value = self.module.ratio(2, 4)
        self.assertEqual((value["numerator"], value["denominator"], value["value"]), (2, 4, 0.5))
        zero = self.module.ratio(2, 0)
        self.assertIsNone(zero["value"])
        self.assertEqual(zero["denominator"], 0)

    def test_record_rejects_unknown_sources_and_blank_metrics(self):
        with self.assertRaisesRegex(ValueError, "unknown source"):
            self.module.record("T", "agents", 39, "guess")
        with self.assertRaisesRegex(ValueError, "blank metric"):
            self.module.record("T", "  ", 1, self.module.EvidenceSource.TOOL)

    def test_unavailable_metrics_never_become_numeric(self):
        for metric in ("tokens", "duration_seconds", "agent_invocations", "live_first_attempt"):
            with self.subTest(metric=metric):
                row = self.module.record("T", metric, 42, self.module.EvidenceSource.TOOL)
                self.assertEqual(row["value"], "unavailable")
                self.assertEqual(row["source"], "unavailable")

    def test_target_values_are_labelled_and_never_measurements(self):
        for metric, value in self.module.TARGETS.items():
            with self.subTest(metric=metric):
                row = self.module.record("T", metric, value, self.module.EvidenceSource.TOOL,
                                         kind="target")
                self.assertEqual(row["kind"], "target")
                self.assertEqual(row["value"], value)
                self.assertNotIn(row["value"], self.module.measure(ROOT)["unavailable"])
        with self.assertRaisesRegex(ValueError, "target"):
            self.module.record("T", "context_reduction", 40, self.module.EvidenceSource.TOOL)

    def test_evidence_rows_are_not_averaged_across_sources(self):
        tool = self.module.record("T", "agents", 39, self.module.EvidenceSource.TOOL)
        human = self.module.record("T", "approval", 1, self.module.EvidenceSource.HUMAN)
        self.assertNotEqual(tool["source"], human["source"])
        self.assertNotIn("average", tool)
        self.assertNotIn("average", human)

    def test_first_attempt_is_fail_closed(self):
        module = self.module
        self.assertTrue(module.first_attempt({"acceptance_passed": True, "repair_cycles": 0}))
        self.assertFalse(module.first_attempt({"acceptance_passed": True, "repair_cycles": 1}))
        self.assertFalse(module.first_attempt({"acceptance_passed": False, "repair_cycles": 0}))
        self.assertFalse(module.first_attempt({"repair_cycles": 0}))
        self.assertFalse(module.first_attempt({"acceptance_passed": True}))
        self.assertFalse(module.first_attempt({}))


class BoundaryTests(unittest.TestCase):
    def test_no_production_script_imports_orca_benchmark(self):
        for path in (ROOT / "scripts").glob("*.py"):
            with self.subTest(path=path.name):
                self.assertNotIn("import orca_benchmark", path.read_text(encoding="utf-8"))

    def test_module_has_no_process_network_or_timing_api(self):
        source = (ROOT / "scripts" / "orca_benchmark.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        names = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                names.add(node.module.split(".")[0])
            elif isinstance(node, ast.Attribute):
                names.add(node.attr)
            elif isinstance(node, ast.Name):
                names.add(node.id)
        for forbidden in ("subprocess", "Popen", "socket", "urllib", "requests",
                          "time", "datetime", "tokenizer", "getenv", "environ"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, names)


if __name__ == "__main__":
    unittest.main()
