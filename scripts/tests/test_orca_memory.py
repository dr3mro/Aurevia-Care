"""Passive memory provenance: tests are the executable contract for ORCA-029/031."""
import ast
import unittest
from pathlib import Path

from helpers import ROOT, load

memory = load("orca_memory")
core = load("orca_core")


class ProvenanceTests(unittest.TestCase):
    """Provenance derives confidence from the evidence kind and validates inputs."""

    def test_confidence_is_derived_from_each_evidence_kind(self):
        expected = {"tool": 1.0, "reviewer": 0.9, "simulated-user": 0.6,
                    "human": 1.0, "unavailable": 0.0}
        for kind, confidence in expected.items():
            with self.subTest(kind=kind):
                row = memory.provenance("MEMORY.md", 7, kind, "rev-1", True)
                self.assertEqual(row["confidence"], confidence)
                self.assertEqual(row["kind"], kind)

    def test_provenance_returns_a_canonical_row(self):
        row = memory.provenance("BUGS.md", 12, "tool", "rev-9", False)
        self.assertEqual(row, {
            "location": "BUGS.md:12", "path": "BUGS.md", "line": 12,
            "kind": "tool", "confidence": 1.0, "revision": "rev-9", "fact": False,
        })

    def test_unknown_kind_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "provenance.*kind"):
            memory.provenance("MEMORY.md", 1, "guess", "rev-1", True)

    def test_provenance_rejects_invalid_inputs(self):
        cases = (
            (("", 1, "tool", "rev-1", True), "path"),
            ((None, 1, "tool", "rev-1", True), "path"),
            (("MEMORY.md", True, "tool", "rev-1", True), "line"),
            (("MEMORY.md", 0, "tool", "rev-1", True), "line"),
            (("MEMORY.md", "1", "tool", "rev-1", True), "line"),
            (("MEMORY.md", 1, None, "rev-1", True), "kind"),
            (("MEMORY.md", 1, "tool", "", True), "revision"),
            (( "MEMORY.md", 1, "tool", None, True), "revision"),
            (("MEMORY.md", 1, "tool", "rev-1", 1), "fact"),
        )
        for args, keyword in cases:
            with self.subTest(args=args), self.assertRaisesRegex(ValueError, keyword):
                memory.provenance(*args)


class SelectionTests(unittest.TestCase):
    """Scoped selection keeps fresh authoritative rows under an explicit budget."""

    @staticmethod
    def row(path, line, kind, revision="rev-1", fact=True, confidence=0.0):
        return {"path": path, "line": line, "kind": kind, "confidence": confidence,
                "revision": revision, "fact": fact, "location": f"{path}:{line}"}

    def test_select_memory_filters_stale_revisions(self):
        fresh = self.row("MEMORY.md", 1, "tool")
        stale = self.row("BUGS.md", 2, "tool", revision="rev-0")
        result = memory.select_memory([fresh, stale], 10, "rev-1")
        self.assertEqual(result, (memory.provenance("MEMORY.md", 1, "tool", "rev-1", True),))

    def test_select_memory_derives_confidence_and_ignores_a_stored_value(self):
        row = self.row("MEMORY.md", 1, "reviewer", confidence=1.0)
        result = memory.select_memory([row], 10, "rev-1")
        self.assertEqual(result[0]["confidence"], 0.9)

    def test_select_memory_is_deterministic_across_every_sort_layer(self):
        rows = [
            self.row("b.md", 1, "simulated-user"),
            self.row("a.md", 2, "tool", fact=False),
            self.row("a.md", 1, "tool", fact=True),
            self.row("c.md", 1, "unavailable"),
        ]
        result = memory.select_memory(rows, 10, "rev-1")
        self.assertEqual([(row["path"], row["line"]) for row in result],
                         [("a.md", 1), ("a.md", 2), ("b.md", 1), ("c.md", 1)])
        self.assertEqual(result, memory.select_memory(list(reversed(rows)), 10, "rev-1"))

    def test_sort_layers_do_not_depend_on_input_order(self):
        rows = [
            self.row("a.md", 1, "tool", fact=False),
            self.row("a.md", 2, "tool", fact=True),
            self.row("a.md", 3, "tool", fact=True),
        ]
        result = memory.select_memory(list(reversed(rows)), 10, "rev-1")
        self.assertEqual([item["line"] for item in result], [2, 3, 1])
        same = [self.row("a.md", 1, "tool"), self.row("a.md", 1, "human")]
        self.assertEqual([item["kind"] for item in
                          memory.select_memory(list(reversed(same)), 10, "rev-1")],
                         ["human", "tool"])

    def test_mandatory_kinds_survive_a_small_budget(self):
        rows = [self.row("tool.md", 1, "tool"), self.row("human.md", 1, "human"),
                self.row("review.md", 1, "reviewer")]
        result = memory.select_memory(rows, 1, "rev-1")
        self.assertEqual([row["kind"] for row in result], ["human", "tool"])

    def test_zero_budget_keeps_only_mandatory_rows(self):
        rows = [self.row("tool.md", 1, "tool"), self.row("review.md", 1, "reviewer")]
        result = memory.select_memory(rows, 0, "rev-1")
        self.assertEqual([row["kind"] for row in result], ["tool"])

    def test_optional_kinds_fill_remaining_slots(self):
        rows = [self.row("tool.md", 1, "tool"), self.row("review.md", 1, "reviewer"),
                self.row("sim.md", 1, "simulated-user"), self.row("none.md", 1, "unavailable")]
        result = memory.select_memory(rows, 3, "rev-1")
        self.assertEqual([row["kind"] for row in result],
                         ["tool", "reviewer", "simulated-user"])

    def test_empty_rows_return_empty_tuple(self):
        self.assertEqual(memory.select_memory((), 5, "rev-1"), ())

    def test_inputs_are_not_mutated(self):
        rows = [self.row("a.md", 1, "tool"), self.row("b.md", 1, "reviewer")]
        snapshot = [dict(row) for row in rows]
        memory.select_memory(rows, 5, "rev-1")
        self.assertEqual(rows, snapshot)

    def test_select_memory_rejects_invalid_inputs(self):
        cases = ((None, 5, "rev-1"), ("a.md", 5, "rev-1"), ((), -1, "rev-1"),
                 ((), 1.5, "rev-1"), ((), 5, ""), ((), 5, None))
        for args in cases:
            with self.subTest(args=args), self.assertRaisesRegex(ValueError, "select_memory"):
                memory.select_memory(*args)

    def test_select_memory_rejects_invalid_rows(self):
        cases = ((["bad"], "row"), (({"path": "a.md"},), "fields"),
                 ((self.row("a.md", 1, "guess"),), "kind"),
                 ((self.row("a.md", 0, "tool"),), "line"),
                 ((self.row(1, 1, "tool"),), "path"),
                 ((self.row("a.md", 1, "tool", fact=1),), "fact"),
                 ((self.row("a.md", 1, "tool", revision="rev-0", fact=1),), "fact"))
        for rows, keyword in cases:
            with self.subTest(rows=rows), self.assertRaisesRegex(ValueError, keyword):
                memory.select_memory(rows, 5, "rev-1")

    def test_freshness_matches_orca_core(self):
        for revision in ("rev-1", "rev-0"):
            with self.subTest(revision=revision):
                row = self.row("a.md", 1, "tool", revision=revision)
                kept = memory.select_memory([row], 5, "rev-1")
                self.assertEqual(bool(kept), core.evidence_fresh(revision, "rev-1"))


class BoundaryTests(unittest.TestCase):
    """Memory analysis stays outside the runtime and storage paths."""

    def test_module_has_no_runtime_io_or_dispatch(self):
        source = (ROOT / "scripts" / "orca_memory.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        calls = set()
        imports = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                if isinstance(node.func, ast.Name):
                    calls.add(node.func.id)
                elif isinstance(node.func, ast.Attribute):
                    calls.add(node.func.attr)
            elif isinstance(node, ast.Import):
                imports.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imports.add(node.module)
        forbidden_calls = {
            "open", "glob", "rglob", "iterdir", "walk", "scandir", "listdir",
            "import_module", "__import__", "dispatch", "spawn", "run_agent",
            "subprocess", "requests", "socket", "git", "Repo", "system", "popen",
            "Popen", "run", "check_output", "check_call", "call", "read_text",
            "write_text", "unlink", "mkdir", "rmdir", "remove", "rename", "replace",
        }
        self.assertFalse(calls & forbidden_calls, calls & forbidden_calls)
        forbidden_imports = {
            "swarm", "route_task", "taskstore", "agent_run", "subprocess", "socket",
            "requests", "urllib", "os", "pathlib", "shutil", "git", "http", "ftplib",
            "urllib3", "importlib",
        }
        self.assertFalse(imports & forbidden_imports, imports & forbidden_imports)
        for token in ("subprocess", "run_agent", "git ", "write_text", "read_text"):
            with self.subTest(token=token):
                self.assertNotIn(token, source)


if __name__ == "__main__":
    unittest.main()
