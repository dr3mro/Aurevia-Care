"""Behaviour tests for the offline ORCA analysis helpers.

All fixtures are synthetic. The module under test never launches an agent and
never writes swarm state, so these tests only exercise pure decisions and local
checkpoint files in temporary directories.
"""

import ast
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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


class ContractMappingTests(unittest.TestCase):
    """Contract mapping is a thin adapter over parsed task fields."""

    def test_new_fields_default_empty_and_remain_valid(self):
        contract = TaskContract("T", "o", ("a",), ("scripts",), "routine")
        self.assertEqual((contract.skills, contract.constraints, contract.tests,
                          contract.definition_of_done, contract.components), ((), (), (), (), ()))
        self.assertEqual(contract.problems(), [])

    def test_contract_from_task_maps_parsed_fields(self):
        task = {"id": "CW-001", "task": "Add feature", "requirements": "OPS-06, ARC-02",
                "depends": ["01200000"], "scope": ["apps/api", "scripts/core.py"],
                "details": "Do it"}
        contract = orca_core.contract_from_task(task, "cross_boundary")
        self.assertEqual(contract.task_id, "CW-001")
        self.assertEqual(contract.objective, "Add feature")
        self.assertEqual(contract.requirements, ("ARC-02", "OPS-06"))
        self.assertEqual(contract.depends, ("01200000",))
        self.assertEqual(contract.scope, ("apps/api", "scripts/core.py"))
        self.assertEqual(contract.assumptions, ())
        self.assertEqual(contract.skills, ())

    def test_contract_from_task_handles_absent_optional_fields(self):
        contract = orca_core.contract_from_task({"id": "T"}, "routine")
        self.assertEqual((contract.objective, contract.acceptance, contract.scope), ("", (), ()))
        self.assertEqual(contract.problems(), ["objective", "acceptance", "scope"])

    def test_contract_from_task_uses_objective_and_filters_empty_requirements(self):
        contract = orca_core.contract_from_task(
            {"id": "T", "objective": "From objective", "requirements": "A,,B"}, "routine")
        self.assertEqual(contract.objective, "From objective")
        self.assertEqual(contract.requirements, ("A", "B"))

    def test_contract_from_task_accepts_list_and_other_requirements(self):
        listed = orca_core.contract_from_task({"id": "T", "requirements": ["B", "A"]}, "routine")
        self.assertEqual(listed.requirements, ("A", "B"))
        other = orca_core.contract_from_task({"id": "T", "requirements": 42}, "routine")
        self.assertEqual(other.requirements, ())

    def test_contract_from_task_rejects_blank_id_and_unknown_risk(self):
        with self.assertRaisesRegex(ValueError, "blank task id"):
            orca_core.contract_from_task({"id": ""}, "routine")
        with self.assertRaisesRegex(ValueError, "unknown risk"):
            orca_core.contract_from_task({"id": "T"}, "nope")


class ClassificationTests(unittest.TestCase):
    """Classification is deterministic from injected scope and routing data."""

    def contract(self, **overrides):
        values = dict(task_id="T", objective="o", acceptance=("a",), scope=("apps/api",),
                      risk="routine", requirements=("OPS-06",))
        values.update(overrides)
        return TaskContract(**values)

    def test_classify_derives_fields_from_injected_data(self):
        result = orca_core.classify(
            self.contract(), ("apps/api", "scripts/core.py"),
            {"apps/api": ("senior-backend", "senior-spring-security"),
             "scripts/core.py": ("senior-python", "senior-backend")},
            ("tenant isolation", "audit"))
        self.assertEqual(result["domain"], ("backend", "tooling"))
        self.assertEqual(result["type"], ("api", "tooling"))
        self.assertEqual(result["components"], ("apps/api", "scripts/core.py"))
        self.assertEqual(result["expertise"], ("senior-backend", "senior-python",
                                               "senior-spring-security"))
        self.assertEqual(result["validation"], ("audit", "tenant isolation"))
        self.assertEqual(result["complexity"], 4)
        self.assertEqual(result["risk"], "routine")
        self.assertTrue(result["security"])
        self.assertFalse(result["clinical"])

    def test_classify_sorts_duplicate_and_unordered_input(self):
        first = orca_core.classify(self.contract(), ("scripts/b", "scripts/a"),
                                   {"scripts/b": ("z",), "scripts/a": ("a", "z")}, ("z", "a"))
        second = orca_core.classify(self.contract(), ("scripts/a", "scripts/b"),
                                    {"scripts/a": ("a", "z"), "scripts/b": ("z",)}, ("a", "z"))
        self.assertEqual(first, second)

    def test_classify_maps_runtime_domains(self):
        mapping = {"apps/staff-web": ("staff-web", "ui"),
                   "apps/patient-mobile": ("patient-mobile", "ui"),
                   "db/migrations": ("database", "migration")}
        for path, expected in mapping.items():
            with self.subTest(path=path):
                result = orca_core.classify(self.contract(scope=(path,)), (path,),
                                            {path: ("senior",)}, ())
                self.assertEqual(result["domain"], (expected[0],))
                self.assertEqual(result["type"], (expected[1],))

    def test_classify_ignores_unrouted_scope_entries(self):
        result = orca_core.classify(self.contract(), ("apps/api", "docs/x"),
                                    {"apps/api": ("senior-backend",)}, ())
        self.assertEqual(result["expertise"], ("senior-backend",))

    def test_classify_security_triggered_by_auth_path(self):
        result = orca_core.classify(self.contract(), ("apps/api/iam/auth",),
                                    {"apps/api/iam/auth": ("senior-backend",)}, ())
        self.assertTrue(result["security"])

    def test_classify_marks_unknown_domain(self):
        result = orca_core.classify(self.contract(scope=("docs/x",)), ("docs/x",),
                                    {"docs/x": ("technical-writer",)}, ())
        self.assertEqual(result["domain"], ("unknown",))
        self.assertEqual(result["type"], ("tooling",))

    def test_classify_clinical_and_security_triggers_both_sides(self):
        clinical = orca_core.classify(self.contract(), ("apps/api",),
                                      {"apps/api": ("physician-reviewer",)}, ("clinical safety",))
        self.assertTrue(clinical["clinical"])
        self.assertFalse(clinical["security"])
        plain = orca_core.classify(self.contract(), ("apps/api",),
                                   {"apps/api": ("senior-backend",)}, ())
        self.assertFalse(plain["clinical"])
        self.assertFalse(plain["security"])

    def test_classify_clinical_via_routed_name_and_security_via_scope(self):
        clinical = orca_core.classify(self.contract(), ("apps/api",),
                                      {"apps/api": ("clinical-assistant",)}, ())
        self.assertTrue(clinical["clinical"])
        security = orca_core.classify(self.contract(), (".github/workflows/a.yml",),
                                      {".github/workflows/a.yml": ("github-workflow",)}, ())
        self.assertTrue(security["security"])
        self.assertFalse(security["clinical"])

    def test_classify_rejects_bad_inputs(self):
        with self.assertRaisesRegex(ValueError, "incomplete contract"):
            orca_core.classify(TaskContract("", "", (), (), "routine"), ("apps/api",),
                               {"apps/api": ("a",)}, ())
        with self.assertRaisesRegex(ValueError, "empty scope"):
            orca_core.classify(self.contract(), (), {"apps/api": ("a",)}, ())
        with self.assertRaisesRegex(ValueError, "empty routed"):
            orca_core.classify(self.contract(), ("apps/api",), {}, ())

    def test_new_fields_do_not_change_readiness_scores(self):
        base = TaskContract("T", "o", ("a",), ("scripts",), "routine")
        full = TaskContract("T", "o", ("a",), ("scripts",), "routine",
                            requirements=("OPS-06",), depends=("1",),
                            assumptions=("synthetic",), skills=("delivery",),
                            constraints=("100%",), tests=("test_a",),
                            definition_of_done=("green",), components=("core",))
        self.assertEqual(orca_core.readiness(base), 65)
        self.assertEqual(orca_core.readiness(TaskContract("T", "o", ("a",), ("scripts",),
                                                          "routine", skills=("delivery",),
                                                          constraints=("100%",), tests=("t",),
                                                          definition_of_done=("green",),
                                                          components=("core",))), 65)
        self.assertEqual(orca_core.readiness(full), 100)
        self.assertEqual(orca_core.readiness(TaskContract("", "", (), (), "routine")), 0)


class DecompositionTests(unittest.TestCase):
    """Plan decomposition is passive, deterministic and bounded."""

    def contract(self, scope=("apps/api", "scripts/tool.py"), acceptance=("a works", "b works")):
        return TaskContract("CW-1", "Add feature", acceptance, scope, "routine",
                            requirements=("OPS-06",), skills=("delivery",))

    def classification(self):
        return {"domain": ("backend", "tooling"), "type": ("api", "tooling"),
                "components": ("apps/api", "scripts/tool.py"),
                "expertise": ("senior-backend", "senior-python"),
                "validation": ("audit",), "complexity": 4, "risk": "routine",
                "clinical": False, "security": False}

    def test_split_plan_pins_the_full_child_shape(self):
        children = orca_core.split_plan(self.contract(), self.classification())
        self.assertEqual(len(children), 3)
        self.assertEqual([c["task_id"] for c in children],
                         ["CW-1-P1", "CW-1-P2", "CW-1-INT"])
        first = children[0]
        self.assertEqual(set(first), {"task_id", "objective", "acceptance", "scope",
                                      "risk", "requirements", "depends", "skills",
                                      "kind", "components", "expertise", "validation"})
        self.assertEqual(first["objective"], "Add feature — apps/api")
        self.assertEqual(first["acceptance"], ("a works",))
        self.assertEqual(first["scope"], ("apps/api",))
        self.assertEqual(first["depends"], ())
        self.assertEqual(first["requirements"], ("OPS-06",))
        self.assertEqual(first["skills"], ("delivery",))
        self.assertEqual(first["kind"], "scope")
        integration = children[-1]
        self.assertEqual(integration["objective"], "Integrate Add feature")
        self.assertEqual(integration["acceptance"], ("a works", "b works"))
        self.assertEqual(integration["scope"], ("apps/api", "scripts/tool.py"))
        self.assertEqual(integration["depends"], ("CW-1-P1", "CW-1-P2"))
        self.assertEqual(integration["kind"], "integration")

    def test_split_plan_returns_none_for_one_scope(self):
        self.assertIsNone(orca_core.split_plan(
            TaskContract("CW-1", "o", ("a",), ("apps/api",), "routine"), self.classification()))

    def test_split_plan_respects_the_cap(self):
        with self.assertRaisesRegex(ValueError, "max_parts"):
            orca_core.split_plan(self.contract(), self.classification(), max_parts=2)
        self.assertEqual(len(orca_core.split_plan(
            self.contract(), self.classification(), max_parts=3)), 3)

    def test_split_plan_rejects_bad_inputs(self):
        with self.assertRaisesRegex(ValueError, "incomplete contract"):
            orca_core.split_plan(TaskContract("", "", (), ("a", "b"), "routine"),
                                 self.classification())
        for bad in ({key: value for key, value in self.classification().items()
                     if key != "expertise"}, {**self.classification(), "risk": 1}):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, "invalid classification"):
                orca_core.split_plan(self.contract(), bad)

    def test_split_plan_rejects_a_non_dict_classification(self):
        with self.assertRaisesRegex(ValueError, "invalid classification"):
            orca_core.split_plan(self.contract(), ["not", "a", "dict"])

    def test_split_plan_keeps_extra_acceptance_on_integration_only(self):
        contract = self.contract(acceptance=("a", "b", "c"))
        children = orca_core.split_plan(contract, self.classification())
        self.assertEqual(children[0]["acceptance"], ("a",))
        self.assertEqual(children[1]["acceptance"], ("b",))
        self.assertEqual(children[-1]["acceptance"], ("a", "b", "c"))

    def test_split_plan_is_deterministic_for_unordered_scope(self):
        reversed_contract = self.contract(scope=("scripts/tool.py", "apps/api"))
        self.assertEqual(orca_core.split_plan(reversed_contract, self.classification()),
                         orca_core.split_plan(self.contract(), self.classification()))

    def test_split_plan_handles_overlapping_scopes_and_empty_acceptance(self):
        contract = TaskContract("CW-1", "o", ("a works",),
                                ("apps/api/a", "apps/api/b"), "routine")
        children = orca_core.split_plan(contract, self.classification())
        self.assertEqual([c["scope"] for c in children[:2]],
                         [("apps/api/a",), ("apps/api/b",)])
        self.assertEqual(children[0]["acceptance"], ("a works",))
        self.assertEqual(children[1]["acceptance"], ())

    def test_split_plan_source_has_no_dispatch_call(self):
        source = Path(orca_core.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        function = next(node for node in tree.body
                        if isinstance(node, ast.FunctionDef) and node.name == "split_plan")
        names = set()
        for node in ast.walk(function):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                names.add(node.func.id)
            elif isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                names.add(node.func.attr)
                if isinstance(node.func.value, ast.Name):
                    names.add(node.func.value.id)
        for token in ("dispatch", "spawn", "run_agent", "subprocess"):
            with self.subTest(token=token):
                self.assertNotIn(token, names)


class IndexTests(unittest.TestCase):
    """The source index is a deterministic, injected-value analysis helper."""

    def test_index_sources_pins_shape_and_digest(self):
        index = orca_core.index_sources({"scripts/a.py": "x = 1\n"},
                                        {"python": lambda text: ("a",)})
        self.assertEqual(list(index), ["scripts/a.py"])
        entry = index["scripts/a.py"]
        self.assertEqual(set(entry), {"hash", "kind", "ids"})
        self.assertEqual(entry["hash"], "e139f73e3432")
        self.assertEqual(len(entry["hash"]), 12)
        self.assertEqual(entry["kind"], "python")
        self.assertEqual(entry["ids"], ("a",))

    def test_index_sorts_ids_and_uses_first_matching_extractor(self):
        extractors = {"b": lambda text: ("z", "a"), "a": lambda text: ("b", "a")}
        index = orca_core.index_sources({"f": "text"}, extractors)
        self.assertEqual(index["f"]["kind"], "a")
        self.assertEqual(index["f"]["ids"], ("a", "b"))

    def test_index_no_match_uses_unknown_and_empty_ids(self):
        index = orca_core.index_sources({"f": "text"}, {"a": lambda text: ()})
        self.assertEqual(index["f"]["kind"], "unknown")
        self.assertEqual(index["f"]["ids"], ())

    def test_index_rejects_bad_inputs(self):
        with self.assertRaisesRegex(ValueError, "files"):
            orca_core.index_sources([], {})
        with self.assertRaisesRegex(ValueError, "path"):
            orca_core.index_sources({1: "text"}, {})
        with self.assertRaisesRegex(ValueError, "text"):
            orca_core.index_sources({"f": 1}, {})
        with self.assertRaisesRegex(ValueError, "extractors"):
            orca_core.index_sources({"f": "text"}, [])
        with self.assertRaisesRegex(ValueError, "callable"):
            orca_core.index_sources({"f": "text"}, {"a": "not callable"})
        def explode(text):
            raise RuntimeError("bad extractor")
        with self.assertRaisesRegex(ValueError, "extractor failed"):
            orca_core.index_sources({"f": "text"}, {"a": explode})

    def test_index_rejects_mixed_keys_and_bad_extractor_ids(self):
        with self.assertRaisesRegex(ValueError, "path must be a string"):
            orca_core.index_sources({1: "x", "a": "y"}, {})
        with self.assertRaisesRegex(ValueError, "extractor name must be a string"):
            orca_core.index_sources({"a": "x"}, {1: lambda text: ()})
        with self.assertRaisesRegex(ValueError, "ids must be strings"):
            orca_core.index_sources({"a": "x"}, {"a": lambda text: (1,)})

    def test_index_accepts_a_generator_extractor_once(self):
        def generator(text):
            return (item for item in ("b", "a", "b"))
        index = orca_core.index_sources({"f": "text"}, {"gen": generator})
        self.assertEqual(index["f"]["kind"], "gen")
        self.assertEqual(index["f"]["ids"], ("a", "b"))

    def test_index_is_deterministic_for_reordered_input(self):
        files = {"b": "two", "a": "one"}
        extractors = {"z": lambda text: ("z",), "a": lambda text: ("a",)}
        first = orca_core.index_sources(files, extractors)
        second = orca_core.index_sources({"a": "one", "b": "two"},
                                         {"a": lambda text: ("a",), "z": lambda text: ("z",)})
        self.assertEqual(first, second)
        self.assertEqual(list(first), ["a", "b"])

    def test_stale_entries_reuses_evidence_fresh(self):
        index = {"a": {"hash": "h1", "kind": "python", "ids": ()}}
        with mock.patch.object(orca_core, "evidence_fresh", return_value=False) as fresh:
            result = orca_core.stale_entries(index, {"a": "h1"})
        fresh.assert_called_once_with("h1", "h1")
        self.assertEqual(result["stale"], ("a",))

    def test_stale_entries_reports_equal_added_removed_and_empty(self):
        index = {"a": {"hash": "h1", "kind": "python", "ids": ()},
                 "gone": {"hash": "g", "kind": "python", "ids": ()}}
        result = orca_core.stale_entries(index, {"a": "h1", "new": "n"})
        self.assertEqual(result, {"stale": (), "added": ("new",), "removed": ("gone",)})
        self.assertEqual(orca_core.stale_entries({}, {}),
                         {"stale": (), "added": (), "removed": ()})
        different = orca_core.stale_entries({"a": {"hash": "old"}}, {"a": "new"})
        self.assertEqual(different["stale"], ("a",))


class ImpactTests(unittest.TestCase):
    """Impact analysis is deterministic and never drops a changed path."""

    def test_impact_outputs_sorted_tuples_and_decisions(self):
        result = orca_core.impact(
            ("apps/api/A.java",),
            {"apps/api/A.java": {"hash": "h", "kind": "backend", "ids": ("A",)}},
            {"apps/api/A.java": ("senior-backend",)})
        self.assertEqual(set(result), {"consumers", "entities", "web", "mobile",
                                       "contracts", "tests", "security", "clinical",
                                       "unmapped", "decisions"})
        self.assertEqual(result["consumers"], ("senior-backend",))
        self.assertEqual(result["decisions"], {"apps/api/A.java": ("consumers",)})
        for key in ("entities", "web", "mobile", "contracts", "tests", "security",
                    "clinical", "unmapped"):
            self.assertEqual(result[key], ())

    def test_impact_maps_each_boundary_and_multiple_categories(self):
        paths = ("apps/staff-web/a.tsx", "apps/patient-mobile/b.ts", "contracts/openapi.yaml",
                 "db/migrations/V1.sql", "scripts/tests/test_a.py", "apps/api/auth/C.java",
                 "clinical/form.md")
        result = orca_core.impact(paths, {}, {})
        self.assertEqual(result["web"], ("apps/staff-web/a.tsx",))
        self.assertEqual(result["mobile"], ("apps/patient-mobile/b.ts",))
        self.assertEqual(result["contracts"], ("contracts/openapi.yaml",))
        self.assertEqual(result["entities"], ("db/migrations/V1.sql",))
        self.assertEqual(result["tests"], ("scripts/tests/test_a.py",))
        self.assertEqual(result["security"], ("apps/api/auth/C.java",))
        self.assertEqual(result["clinical"], ("clinical/form.md",))
        self.assertIn("web", result["decisions"]["apps/staff-web/a.tsx"])

    def test_impact_uses_index_kind_and_exact_reverse_ids(self):
        index = {
            "db/entity.sql": {"hash": "h", "kind": "database", "ids": ()},
            "contracts/spec.yaml": {"hash": "c", "kind": "contract", "ids": ()},
            "src/service.py": {"hash": "s", "kind": "backend",
                               "ids": ("apps/api/A.java", "repo-id")},
        }
        result = orca_core.impact(("db/entity.sql", "contracts/spec.yaml",
                                   "apps/api/A.java"), index, {})
        self.assertEqual(result["entities"], ("db/entity.sql",))
        self.assertEqual(result["contracts"], ("contracts/spec.yaml",))
        self.assertEqual(result["consumers"], ("apps/api/A.java", "repo-id"))

    def test_impact_security_and_clinical_names_and_or_sides(self):
        result = orca_core.impact(
            ("apps/api/plain.java", "apps/api/secure.java", "apps/api/clinical.java"),
            {}, {"apps/api/plain.java": ("senior-backend",),
                 "apps/api/secure.java": ("senior-spring-security",),
                 "apps/api/clinical.java": ("physician-reviewer",)})
        self.assertEqual(result["security"], ("apps/api/secure.java",))
        self.assertEqual(result["clinical"], ("apps/api/clinical.java",))

    def test_impact_unmapped_is_the_complement_and_no_path_is_dropped(self):
        paths = ("docs/x.md", "apps/api/A.java", "docs/x.md")
        result = orca_core.impact(paths, {}, {"apps/api/A.java": ("senior-backend",)})
        self.assertEqual(result["unmapped"], ("docs/x.md",))
        self.assertEqual(set(result["decisions"]), set(paths))
        self.assertEqual(result["decisions"]["docs/x.md"], ("unmapped",))
        self.assertTrue(all(result["decisions"][path] for path in paths))

    def test_impact_covers_each_short_circuit_alternative(self):
        with self.assertRaisesRegex(ValueError, "impact"):
            orca_core.impact(("apps/api/A.java",), {}, {"apps/api/A.java": "senior-backend"})
        kind_contract = orca_core.impact(
            ("spec.yaml",), {"spec.yaml": {"hash": "h", "kind": "contract", "ids": ()}}, {})
        self.assertEqual(kind_contract["contracts"], ("spec.yaml",))
        security_path = orca_core.impact(("apps/api/security/A.java",), {}, {})
        self.assertEqual(security_path["security"], ("apps/api/security/A.java",))
        physician = orca_core.impact(("neutral.txt",), {}, {"neutral.txt": ("physician-reviewer",)})
        clinical = orca_core.impact(("neutral.txt",), {}, {"neutral.txt": ("clinical-reviewer",)})
        self.assertEqual(physician["clinical"], ("neutral.txt",))
        self.assertEqual(clinical["clinical"], ("neutral.txt",))

    def test_impact_accepts_a_generator_and_multiple_categories(self):
        paths = (path for path in ("apps/api/auth/test_service.java",))
        result = orca_core.impact(paths, {}, {"apps/api/auth/test_service.java":
                                              ("senior-spring-security",)})
        path = "apps/api/auth/test_service.java"
        self.assertEqual(result["decisions"][path],
                         ("consumers", "security", "tests"))
        self.assertEqual(result["consumers"], ("senior-spring-security",))
        self.assertEqual(result["security"], (path,))

    def test_impact_empty_and_deterministic(self):
        empty = orca_core.impact((), {}, {})
        self.assertTrue(all(empty[key] == () for key in empty if key != "decisions"))
        self.assertEqual(empty["decisions"], {})
        args = (("apps/api/A.java", "db/migrations/V1.sql"),
                {"apps/api/A.java": {"hash": "h", "kind": "backend", "ids": ()}},
                {"apps/api/A.java": ("senior-backend",)})
        self.assertEqual(orca_core.impact(*args), orca_core.impact(*args))

    def test_impact_rejects_invalid_inputs(self):
        for args in ((None, {}, {}), ("apps/api/A.java", {}, {}),
                     ((1,), {}, {}), ((), [], {}), ((), {}, []),
                     ((), {}, {"path": (1,)})):
            with self.subTest(args=args), self.assertRaisesRegex(ValueError, "impact"):
                orca_core.impact(*args)

    def test_impact_source_has_no_dispatch_call(self):
        source = Path(orca_core.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        function = next(node for node in tree.body
                        if isinstance(node, ast.FunctionDef) and node.name == "impact")
        names = set()
        for node in ast.walk(function):
            if isinstance(node, ast.Call):
                if isinstance(node.func, ast.Name):
                    names.add(node.func.id)
                elif isinstance(node.func, ast.Attribute):
                    names.add(node.func.attr)
        for token in ("dispatch", "spawn", "run_agent", "subprocess"):
            with self.subTest(token=token):
                self.assertNotIn(token, names)


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

    def test_classification_does_not_import_runtime_scripts_or_scan_files(self):
        source = Path(orca_core.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    self.assertNotIn("swarm", alias.name)
                    self.assertNotIn("route_task", alias.name)
            elif isinstance(node, ast.ImportFrom) and node.module:
                self.assertNotIn("swarm", node.module)
                self.assertNotIn("route_task", node.module)
            elif isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                self.assertNotIn(node.func.attr, ("glob", "rglob", "iterdir", "walk",
                                                  "scandir", "listdir", "import_module"))
            elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                self.assertNotIn(node.func.id, ("__import__", "glob", "walk"))

    def test_module_has_no_launch_or_git_api(self):
        text = Path(orca_core.__file__).read_text(encoding="utf-8")
        for token in ("subprocess", "run_agent", "git "):
            with self.subTest(token=token):
                self.assertNotIn(token, text)


class OfflineAnalysisSliceTests(unittest.TestCase):
    """Passive helpers for scope, cycles, consolidation and validation selection."""

    def test_enforce_scope_rejects_non_mapping(self):
        with self.assertRaisesRegex(ValueError, "enforce_scope"):
            orca_core.enforce_scope([], ("a",))

    def test_enforce_scope_rejects_string_allowed(self):
        with self.assertRaisesRegex(ValueError, "enforce_scope"):
            orca_core.enforce_scope({"a": ()}, "a")

    def test_enforce_scope_returns_violations(self):
        self.assertEqual(orca_core.enforce_scope({"b": (), "a": ()}, ("b",)), ("a",))

    def test_enforce_scope_all_allowed_is_empty(self):
        self.assertEqual(orca_core.enforce_scope({"a": ()}, ("a", "b")), ())

    def test_cycle_rejects_non_mapping(self):
        with self.assertRaisesRegex(ValueError, "dependency_cycle"):
            orca_core.dependency_cycle([])

    def test_cycle_self(self):
        self.assertEqual(orca_core.dependency_cycle({"A": ("A",)}), ("A", "A"))

    def test_cycle_two_nodes(self):
        self.assertEqual(orca_core.dependency_cycle({"A": ("B",), "B": ("A",)}),
                         ("A", "B", "A"))

    def test_cycle_dag_is_none(self):
        self.assertIsNone(orca_core.dependency_cycle({"A": ("B",), "B": ()}))

    def test_cycle_is_deterministic(self):
        self.assertEqual(orca_core.dependency_cycle({"B": ("A",), "A": ("B",)}),
                         ("A", "B", "A"))

    def test_consolidate_rejects_invalid(self):
        with self.assertRaisesRegex(ValueError, "consolidate"):
            orca_core.consolidate([{"revision": "r"}], "r")

    def test_consolidate_drops_stale(self):
        rows = [{"task_id": "T", "revision": "old", "confidence": 1.0, "fact": True}]
        self.assertEqual(orca_core.consolidate(rows, "r"), {})

    def test_consolidate_groups_and_sorts(self):
        rows = [{"task_id": "T", "revision": "r", "confidence": c, "fact": f}
                for c, f in ((0.5, True), (0.9, False))]
        out = orca_core.consolidate(rows, "r")["T"]
        self.assertEqual((out[0]["confidence"], out[1]["confidence"]), (0.9, 0.5))
        self.assertEqual((out[0]["fact"], out[1]["fact"]), (False, True))

    def test_consolidate_empty(self):
        self.assertEqual(orca_core.consolidate([], "r"), {})

    def test_validation_bad_inputs(self):
        with self.assertRaisesRegex(ValueError, "select_validation"):
            orca_core.select_validation([], {})

    def test_validation_unknown_signal(self):
        with self.assertRaisesRegex(ValueError, "unknown signal"):
            orca_core.select_validation({}, {"v": "nope"})

    def test_validation_selects_truthy(self):
        result = orca_core.select_validation(
            {"clinical": True, "security": False, "validation": ("audit",)},
            {"z": "validation", "c": "clinical", "s": "security"})
        self.assertEqual(result, ("c", "z"))

    def test_validation_skips_falsy(self):
        catalog = {"r": "risk", "c": "complexity", "v": "validation"}
        self.assertEqual(orca_core.select_validation(
            {"risk": "routine", "complexity": 1, "validation": ()}, catalog), ())

    def test_enforce_scope_accepts_iterable_allowed(self):
        self.assertEqual(orca_core.enforce_scope({"b": ()}, iter(("b",))), ())

    def test_enforce_scope_rejects_bytes_allowed(self):
        with self.assertRaisesRegex(ValueError, "enforce_scope"):
            orca_core.enforce_scope({"a": ()}, b"a")

    def test_cycle_ignores_unknown_dependency(self):
        self.assertIsNone(orca_core.dependency_cycle({"A": ("Z",)}))

    def test_cycle_shared_node_visited_once(self):
        self.assertIsNone(orca_core.dependency_cycle(
            {"A": ("C",), "B": ("C",), "C": ()}))

    def test_cycle_deep_chain_does_not_recurse(self):
        size = 1500
        graph = {f"n{i}": (f"n{i + 1}",) for i in range(size)}
        graph[f"n{size}"] = ()
        self.assertIsNone(orca_core.dependency_cycle(graph))

    def test_consolidate_rejects_non_dict_row(self):
        with self.assertRaisesRegex(ValueError, "consolidate"):
            orca_core.consolidate([["row"]], "r")

    def test_consolidate_rejects_non_bool_fact(self):
        rows = [{"task_id": "T", "revision": "r", "confidence": 1.0, "fact": "yes"}]
        with self.assertRaisesRegex(ValueError, "fact must be a boolean"):
            orca_core.consolidate(rows, "r")

    def test_validation_rejects_non_dict_catalog(self):
        with self.assertRaisesRegex(ValueError, "select_validation"):
            orca_core.select_validation({}, [])

    def test_validation_risk_and_complexity_thresholds(self):
        result = orca_core.select_validation(
            {"risk": "critical", "complexity": 3},
            {"a": "risk", "b": "complexity"})
        self.assertEqual(result, ("a", "b"))

    def test_validation_complexity_rejects_non_integer(self):
        self.assertEqual(orca_core.select_validation(
            {"complexity": "high"}, {"x": "complexity"}), ())


if __name__ == "__main__":
    unittest.main()
