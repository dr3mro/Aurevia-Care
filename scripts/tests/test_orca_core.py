"""Behaviour tests for the offline ORCA analysis helpers.

All fixtures are synthetic. The module under test never launches an agent and
never writes swarm state, so these tests only exercise pure decisions and local
checkpoint files in temporary directories.
"""

import json
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT

import orca_core
from orca_core import (Blackboard, TaskContract, compile_context, evidence_fresh,
                       load_checkpoint, preflight, readiness, save_checkpoint)


class ContractTests(unittest.TestCase):
    """A task contract must name its work, scope and risk before analysis."""

    def contract(self, **overrides):
        values = dict(task_id="ORCA-TEST", objective="Add a feature",
                      acceptance=("a works",), scope=("scripts",), risk="routine",
                      requirements=("OPS-06",), depends=("01200000",),
                      exclusions=("no production deploy",),
                      assumptions=("synthetic fixture",))
        values.update(overrides)
        return TaskContract(**values)

    def test_a_valid_contract_has_no_problems(self):
        self.assertEqual(self.contract().problems(), [])

    def test_each_required_field_is_reported(self):
        for field, value in (("task_id", ""), ("objective", ""),
                             ("acceptance", ()), ("scope", ())):
            with self.subTest(field=field):
                self.assertIn(field, self.contract(**{field: value}).problems())

    def test_an_unknown_risk_is_reported(self):
        self.assertIn("risk", self.contract(risk="unknown").problems())


class ContextTests(unittest.TestCase):
    """Context compilation keeps mandatory rules whole and applies budgets."""

    def test_mandatory_sections_are_never_truncated(self):
        manifest = compile_context(
            TaskContract("T", "o", ("a",), ("scripts",), "routine"),
            {"safety": "safety rules", "execution": "execution rules"}, {})
        self.assertEqual(manifest["content"]["safety"], "safety rules")
        self.assertEqual(manifest["instructions"], ["safety", "execution"])

    def test_optional_sections_respect_the_budget(self):
        manifest = compile_context(
            TaskContract("T", "o", ("a",), ("scripts",), "routine"),
            {"safety": "rules", "reference": "0123456789"},
            {"reference": 4, "default": 2})
        self.assertEqual(manifest["content"]["reference"], "0123")
        self.assertEqual(manifest["references"], ["reference"])

    def test_a_zero_budget_keeps_the_whole_optional_section(self):
        manifest = compile_context(
            TaskContract("T", "o", ("a",), ("scripts",), "routine"),
            {"safety": "rules", "reference": "abcdef"}, {"reference": 0})
        self.assertEqual(manifest["content"]["reference"], "abcdef")

    def test_the_default_budget_applies_to_an_unlisted_section(self):
        manifest = compile_context(
            TaskContract("T", "o", ("a",), ("scripts",), "routine"),
            {"safety": "rules", "reference": "abcdef"}, {"default": 2})
        self.assertEqual(manifest["content"]["reference"], "ab")

    def test_missing_sources_are_skipped(self):
        manifest = compile_context(
            TaskContract("T", "o", ("a",), ("scripts",), "routine"),
            {"safety": "rules", "reference": "abc"}, {"reference": 1})
        self.assertNotIn("execution", manifest["content"])
        self.assertEqual(manifest["instructions"], ["safety"])

    def test_manifest_records_the_task_and_a_stable_hash(self):
        contract = TaskContract("T", "o", ("a",), ("scripts",), "routine")
        sources = {"safety": "rules", "reference": "abc"}
        first = compile_context(contract, sources, {"reference": 2})
        second = compile_context(contract, sources, {"reference": 2})
        self.assertEqual(first["task_id"], "T")
        self.assertEqual(first["hash"], second["hash"])
        self.assertEqual(len(first["hash"]), 12)


class ReadinessTests(unittest.TestCase):
    """Readiness scores preparation and fails closed on missing acceptance."""

    def test_objective_and_scope_are_scored_independently(self):
        contract = TaskContract("T", "", ("a",), (), "routine")
        self.assertEqual(readiness(contract), 30)

    def test_a_full_contract_scores_100(self):
        contract = TaskContract("T", "o", ("a",), ("scripts",), "routine",
                                requirements=("OPS-06",), depends=("1",),
                                assumptions=("synthetic",))
        self.assertEqual(readiness(contract), 100)

    def test_missing_optional_fields_lower_the_score(self):
        contract = TaskContract("T", "o", ("a",), ("scripts",), "routine")
        self.assertEqual(readiness(contract), 65)

    def test_missing_acceptance_or_bad_risk_fails_the_gate(self):
        for contract in (TaskContract("T", "o", (), ("scripts",), "routine"),
                         TaskContract("T", "o", ("a",), ("scripts",), "nope")):
            with self.subTest(contract=contract):
                self.assertEqual(readiness(contract), 0)


class PreflightTests(unittest.TestCase):
    """Preflight analysis reports each missing input without executing anything."""

    def test_all_present_inputs_pass(self):
        self.assertEqual(preflight(
            TaskContract("T", "o", ("a",), ("scripts",), "routine"),
            {"python3", "opencode"}, {"AGENTS.md", "WORKFLOW.md"},
            {"delivery", "code-reviewer"}), [])

    def test_missing_inputs_are_reported(self):
        problems = preflight(
            TaskContract("", "o", ("a",), ("scripts",), "routine"),
            set(), set(), set())
        self.assertEqual(len(problems), 7)
        self.assertIn("missing task id", problems)
        self.assertTrue(any(name in problem for problem in problems
                            for name in ("python3", "opencode", "AGENTS.md",
                                         "WORKFLOW.md", "delivery", "code-reviewer")))


class BlackboardTests(unittest.TestCase):
    """The blackboard keeps attributed findings and rejects unusable entries."""

    def test_publish_and_filter_entries(self):
        board = Blackboard()
        first = board.publish("T-1", "reviewer-a", "abc", "found a gap", fact=True)
        board.publish("T-2", "reviewer-b", "abc", "other task", fact=False)
        self.assertEqual(first["confidence"], 1.0)
        self.assertEqual(len(board.entries()), 2)
        self.assertEqual(len(board.entries("T-1")), 1)
        self.assertEqual(board.entries("missing"), [])

    def test_blank_evidence_is_rejected(self):
        with self.assertRaises(ValueError):
            Blackboard().publish("T", "agent", "abc", "  ")

    def test_confidence_out_of_range_is_rejected(self):
        for value in (-0.1, 1.1):
            with self.subTest(value=value), self.assertRaises(ValueError):
                Blackboard().publish("T", "agent", "abc", "evidence", confidence=value)


class FreshnessTests(unittest.TestCase):
    """Evidence is fresh only when bound to the current revision."""

    def test_fresh_and_stale_revisions(self):
        self.assertTrue(evidence_fresh("abc", "abc"))
        self.assertFalse(evidence_fresh("abc", "def"))


class CheckpointTests(unittest.TestCase):
    """Checkpoints round-trip through a local JSON file with injected paths."""

    def test_round_trip_creates_parent_and_restores_state(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "nested" / "state.json"
            saved = save_checkpoint(path, {"done": ["1"], "next": "2"})
            self.assertEqual(saved, path)
            self.assertEqual(load_checkpoint(path), {"done": ["1"], "next": "2"})

    def test_a_missing_checkpoint_raises(self):
        with tempfile.TemporaryDirectory() as scratch, self.assertRaises(FileNotFoundError):
            load_checkpoint(Path(scratch) / "missing.json")

    def test_a_corrupt_checkpoint_raises(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "bad.json"
            path.write_text("{not json", encoding="utf-8")
            with self.assertRaises(json.JSONDecodeError):
                load_checkpoint(path)


class BoundaryTests(unittest.TestCase):
    """The analysis library must stay outside the runtime orchestration path."""

    def test_no_production_script_imports_orca_core(self):
        for path in (ROOT / "scripts").glob("*.py"):
            with self.subTest(path=path.name):
                text = path.read_text(encoding="utf-8")
                self.assertNotIn("import orca_core", text)
                self.assertNotIn("from orca_core import", text)

    def test_module_has_no_launch_or_git_api(self):
        text = Path(orca_core.__file__).read_text(encoding="utf-8")
        for token in ("subprocess", "run_agent", "git "):
            with self.subTest(token=token):
                self.assertNotIn(token, text)


if __name__ == "__main__":
    unittest.main()
