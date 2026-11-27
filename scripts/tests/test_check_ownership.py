"""Behaviour tests for scripts/check-ownership.py using synthetic trees.

No command is executed by the validator and no test touches the network, tokens
or patient data. The real checkout is used only where a layout assertion needs it.
"""

import ast
import importlib.util
import json
import runpy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT

spec = importlib.util.spec_from_file_location(
    "check_ownership", ROOT / "scripts" / "check-ownership.py")
check_ownership = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_ownership)


def temp_root():
    root = Path(tempfile.mkdtemp())
    (root / "project.json").write_text("{}", encoding="utf-8")
    (root / "scripts").mkdir()
    return root


def manifest(files=None, exceptions=None):
    return {"files": files or {}, "exceptions": exceptions or []}


class RootTests(unittest.TestCase):
    def test_resolve_root_from_root_and_script_dir(self):
        self.assertEqual(check_ownership.resolve_root(ROOT), ROOT)
        self.assertEqual(check_ownership.resolve_root(ROOT / "scripts"), ROOT)

    def test_resolve_root_from_unrelated_directory_raises(self):
        with tempfile.TemporaryDirectory() as scratch:
            with self.assertRaisesRegex(ValueError, "no repository root"):
                check_ownership.resolve_root(scratch)

    def test_resolve_root_from_a_nested_marker(self):
        root = temp_root()
        nested = root / "nested"
        nested.mkdir()
        self.assertEqual(check_ownership.resolve_root(nested), root.resolve())

    def test_load_manifest_reads_a_valid_file(self):
        data = check_ownership.load_manifest(ROOT)
        self.assertIn("files", data)

    def test_load_manifest_rejects_missing_and_malformed(self):
        root = temp_root()
        with self.assertRaisesRegex(ValueError, "cannot read"):
            check_ownership.load_manifest(root)
        (root / "scripts" / "ownership.json").write_text("{bad", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "cannot read"):
            check_ownership.load_manifest(root)
        (root / "scripts" / "ownership.json").write_text("[]", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "lacks a files object"):
            check_ownership.load_manifest(root)
        (root / "scripts" / "ownership.json").write_text('{"files": []}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "lacks a files object"):
            check_ownership.load_manifest(root)
        custom = root / "alt.json"
        custom.write_text('{"files": {}}', encoding="utf-8")
        self.assertEqual(check_ownership.load_manifest(root, custom), {"files": {}})

    def test_classify_returns_owner_or_raises_for_undeclared(self):
        data = manifest({"scripts/a.py": "project"})
        self.assertEqual(check_ownership.classify("scripts/a.py", data), "project")
        with self.assertRaisesRegex(ValueError, "undeclared"):
            check_ownership.classify("scripts/b.py", data)

    def test_discover_selects_scripts_and_excludes_tests(self):
        root = temp_root()
        (root / "scripts" / "a.py").write_text("x", encoding="utf-8")
        (root / "scripts" / "b.sh").write_text("x", encoding="utf-8")
        (root / "scripts" / "c.txt").write_text("x", encoding="utf-8")
        (root / "scripts" / "tests").mkdir()
        (root / "scripts" / "tests" / "t.py").write_text("x", encoding="utf-8")
        self.assertEqual(check_ownership.discover(root), ["scripts/a.py", "scripts/b.sh"])


class ReferenceTests(unittest.TestCase):
    def test_parse_python_refs_collects_imports_and_literals(self):
        text = "import os\nfrom pathlib import Path\nfrom . import local\nx = 'scripts/check.sh'\n"
        refs = check_ownership.parse_python_refs(text)
        self.assertIn("os", refs)
        self.assertIn("pathlib", refs)
        self.assertIn("scripts/check.sh", refs)
        self.assertNotIn("local", refs)

    def test_parse_python_refs_handles_syntax_errors(self):
        self.assertEqual(check_ownership.parse_python_refs("def broken(:"), set())

    def test_parse_shell_refs_collects_sourced_and_invoked_paths(self):
        text = 'source scripts/a.sh\n. scripts/b.sh\npython3 -I scripts/c.py\nbash scripts/d.sh\n'
        refs = check_ownership.parse_shell_refs(text)
        self.assertEqual(refs, {"scripts/a.sh", "scripts/b.sh", "scripts/c.py", "scripts/d.sh"})
        self.assertEqual(check_ownership.parse_shell_refs("echo plain"), set())

    def test_parse_text_refs_extracts_repo_paths(self):
        self.assertEqual(check_ownership.parse_text_refs("run scripts/check.sh and docs/x.md"),
                         {"scripts/check.sh", "docs/x.md"})
        self.assertEqual(check_ownership.parse_text_refs("no paths here"), set())

    def test_build_graph_keeps_only_known_targets(self):
        graph = check_ownership.build_graph({"a": {"b", "external"}, "b": set()})
        self.assertEqual(graph, {"a": ["b"], "b": []})

    def test_find_cycles_detects_two_node_self_and_none(self):
        self.assertEqual(check_ownership.find_cycles({"a": ["b"], "b": ["a"]}), ["b -> a"])
        self.assertEqual(check_ownership.find_cycles({"a": ["a"]}), ["a -> a"])
        self.assertEqual(check_ownership.find_cycles({"a": ["b"], "b": []}), [])

    def test_check_references_reports_missing_and_ignores_external(self):
        problems = check_ownership.check_references(ROOT, {"scripts/missing.py", "external"})
        self.assertEqual(problems, ["broken reference: scripts/missing.py"])

    def test_check_references_accepts_a_single_string_value(self):
        refs = {"scripts/a.py": "scripts/missing.py"}
        self.assertEqual(check_ownership.check_references(ROOT, refs),
                         ["broken reference: scripts/missing.py"])

    def test_a_broken_non_script_markdown_link_is_not_reported(self):
        root = temp_root()
        refs = check_ownership.parse_text_refs("see missing.md for details")
        self.assertEqual(check_ownership.check_references(root, refs), [])

    def test_app_local_script_exists_matches_only_real_app_scripts(self):
        root = temp_root()
        self.assertFalse(
            check_ownership.app_local_script_exists(root, "scripts/gen-api-types.sh"))
        target = root / "apps" / "staff-web" / "scripts" / "gen-api-types.sh"
        target.parent.mkdir(parents=True)
        target.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        self.assertTrue(
            check_ownership.app_local_script_exists(root, "scripts/gen-api-types.sh"))
        self.assertFalse(check_ownership.app_local_script_exists(root, "scripts/absent.sh"))

    def test_app_local_script_exists_rejects_traversal_and_directories(self):
        root = temp_root()
        outside = root / "apps" / "staff-web" / "gen-api-types.sh"
        outside.parent.mkdir(parents=True)
        outside.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        self.assertFalse(
            check_ownership.app_local_script_exists(root, "scripts/../gen-api-types.sh"))
        directory = root / "apps" / "staff-web" / "scripts" / "gen-api-types.sh"
        directory.mkdir(parents=True)
        self.assertFalse(
            check_ownership.app_local_script_exists(root, "scripts/gen-api-types.sh"))

    def test_app_local_reference_is_accepted_only_for_documentation(self):
        root = temp_root()
        target = root / "apps" / "staff-web" / "scripts" / "gen-api-types.sh"
        target.parent.mkdir(parents=True)
        target.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        refs = {"docs/record.md": {"scripts/gen-api-types.sh"}}
        self.assertEqual(
            check_ownership.check_references(root, refs, allow_app_local=True), [])
        self.assertEqual(
            check_ownership.check_references(root, refs),
            ["broken reference: scripts/gen-api-types.sh"])


class RuleTests(unittest.TestCase):
    def test_find_duplicates_reports_new_and_accepts_exception(self):
        files = ["scripts/a/tool.py", "scripts/b/tool.py"]
        self.assertEqual(check_ownership.find_duplicates(files, manifest()),
                         ["duplicate basename: tool.py"])
        data = manifest(exceptions=[{"path": "scripts/b/tool.py", "rule": "duplicate"}])
        self.assertEqual(check_ownership.find_duplicates(files, data), [])
        self.assertEqual(check_ownership.find_duplicates(["scripts/a.py"], manifest()), [])

    def test_check_modes_reports_shell_and_python_mistakes(self):
        root = temp_root()
        good = root / "scripts" / "good.sh"
        good.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        good.chmod(0o755)
        bad_shebang = root / "scripts" / "bad.sh"
        bad_shebang.write_text("echo hi\n", encoding="utf-8")
        bad_exec = root / "scripts" / "noexec.sh"
        bad_exec.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        py = root / "scripts" / "tool.py"
        py.write_text("x = 1\n", encoding="utf-8")
        py.chmod(0o755)
        problems = check_ownership.check_modes(
            ["scripts/good.sh", "scripts/bad.sh", "scripts/noexec.sh", "scripts/tool.py"], root)
        self.assertIn("shell script needs shebang and executable bit: scripts/bad.sh", problems)
        self.assertIn("shell script needs shebang and executable bit: scripts/noexec.sh", problems)
        self.assertIn("Python script is executable without shebang: scripts/tool.py", problems)
        self.assertNotIn("scripts/good.sh", " ".join(problems))

    def test_check_test_discovery_reports_cross_directory_duplicates(self):
        root = temp_root()
        tests = root / "scripts" / "tests"
        (tests / "one").mkdir(parents=True)
        (tests / "two").mkdir(parents=True)
        for folder in ("one", "two"):
            (tests / folder / "same.py").write_text("x", encoding="utf-8")
        self.assertIn("duplicate test module: same.py", check_ownership.check_test_discovery(root))

    def test_check_test_discovery_accepts_distinct_modules(self):
        root = temp_root()
        tests = root / "scripts" / "tests"
        tests.mkdir()
        (tests / "a.py").write_text("x", encoding="utf-8")
        self.assertEqual(check_ownership.check_test_discovery(root), [])

    def test_check_gate_paths_reports_missing_and_accepts_present(self):
        root = temp_root()
        self.assertEqual(len(check_ownership.check_gate_paths(root)), 3)
        for name in ("check.sh", "quick-check.sh", "build-and-test.sh"):
            (root / "scripts" / name).write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        self.assertEqual(check_ownership.check_gate_paths(root), [])

    def test_validate_flags_undeclared_scripts(self):
        root = temp_root()
        (root / "scripts" / "new.py").write_text("x = 1\n", encoding="utf-8")
        data = manifest()
        violations = check_ownership.validate(root, data)
        self.assertIn("undeclared script: scripts/new.py", violations)

    def test_exception_violations_are_shrink_only(self):
        data = manifest(exceptions=[{"path": "scripts/swarm.py", "rule": "dynamic-load"}])
        self.assertEqual(check_ownership.exception_violations(data), [])
        data["exceptions"].append({"path": "scripts/new.py", "rule": "duplicate"})
        self.assertIn("new ownership exception: scripts/new.py: duplicate",
                      check_ownership.exception_violations(data))

    def test_dynamic_load_exception_is_required(self):
        root = temp_root()
        (root / "scripts" / "swarm.py").write_text(
            "path = 'scripts/taskstore.py'\n", encoding="utf-8")
        with_exception = manifest(
            files={"scripts/swarm.py": "github", "scripts/taskstore.py": "github"},
            exceptions=[{"path": "scripts/swarm.py", "rule": "dynamic-load"}])
        self.assertNotIn("broken reference: scripts/taskstore.py",
                         check_ownership.validate(root, with_exception))
        without_exception = manifest(files=with_exception["files"])
        self.assertIn("broken reference: scripts/taskstore.py",
                      check_ownership.validate(root, without_exception))

    def test_dynamic_exception_for_an_absent_path_is_harmless(self):
        root = temp_root()
        (root / "scripts" / "tool.py").write_text("x = 1\n", encoding="utf-8")
        data = manifest(
            files={"scripts/tool.py": "project"},
            exceptions=[{"path": "scripts/swarm.py", "rule": "dynamic-load"}])
        violations = check_ownership.validate(root, data)
        self.assertFalse(any("swarm.py" in item for item in violations))

    def test_reference_filters_ignore_self_and_python_to_shell_mentions(self):
        root = temp_root()
        (root / "scripts" / "tool.py").write_text(
            "x = 'scripts/tool.py'\ny = 'scripts/gate.sh'\n", encoding="utf-8")
        data = manifest(files={"scripts/tool.py": "project"})
        self.assertNotIn("scripts/tool.py -> scripts/tool.py", check_ownership.validate(root, data))
        self.assertNotIn("scripts/tool.py -> scripts/gate.sh", check_ownership.validate(root, data))

    def test_validate_accepts_an_app_local_script_reference_in_docs(self):
        root = self._tree_with_gate_scripts()
        target = root / "apps" / "staff-web" / "scripts" / "gen-api-types.sh"
        target.parent.mkdir(parents=True)
        target.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        (root / "docs").mkdir()
        (root / "docs" / "walkthrough.md").write_text(
            "Canonical gate from apps/staff-web: `bash scripts/gen-api-types.sh --check`\n",
            encoding="utf-8")
        self.assertEqual(check_ownership.validate(root, self._gate_manifest(root)), [])

    def test_validate_keeps_workflow_references_root_only(self):
        root = self._tree_with_gate_scripts()
        target = root / "apps" / "staff-web" / "scripts" / "gen-api-types.sh"
        target.parent.mkdir(parents=True)
        target.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        (root / ".github" / "workflows").mkdir(parents=True)
        (root / ".github" / "workflows" / "qa.yml").write_text(
            "      - run: bash scripts/gen-api-types.sh --check\n", encoding="utf-8")
        self.assertEqual(
            check_ownership.validate(root, self._gate_manifest(root)),
            ["broken reference: scripts/gen-api-types.sh"])

    def test_validate_still_flags_a_missing_app_local_script_reference(self):
        root = self._tree_with_gate_scripts()
        (root / "apps" / "staff-web" / "scripts").mkdir(parents=True)
        (root / "docs").mkdir()
        (root / "docs" / "walkthrough.md").write_text(
            "Canonical gate from apps/staff-web: `bash scripts/gen-api-types.sh --check`\n",
            encoding="utf-8")
        self.assertEqual(
            check_ownership.validate(root, self._gate_manifest(root)),
            ["broken reference: scripts/gen-api-types.sh"])

    @staticmethod
    def _tree_with_gate_scripts():
        root = temp_root()
        for name in ("check.sh", "quick-check.sh", "build-and-test.sh"):
            path = root / "scripts" / name
            path.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
            path.chmod(0o755)
        return root

    @staticmethod
    def _gate_manifest(root):
        files = {
            f"scripts/{name}": "project"
            for name in ("check.sh", "quick-check.sh", "build-and-test.sh")
        }
        return manifest(files=files)

    def test_find_cycles_ignores_an_already_visited_child(self):
        graph = {"a": ["c"], "b": ["c"], "c": []}
        self.assertEqual(check_ownership.find_cycles(graph), [])

    def test_test_discovery_flags_duplicate_containing_tests_in_name(self):
        root = temp_root()
        tests = root / "scripts" / "tests"
        (tests / "one").mkdir(parents=True)
        (tests / "two").mkdir(parents=True)
        for folder in ("one", "two"):
            (tests / folder / "tests_copy.py").write_text("x", encoding="utf-8")
        self.assertIn("duplicate test module: tests_copy.py",
                      check_ownership.check_test_discovery(root))

    def test_main_honours_a_non_default_manifest_path(self):
        root = temp_root()
        custom = root / "alt.json"
        custom.write_text('{"files": {}}', encoding="utf-8")
        with mock.patch.object(check_ownership, "resolve_root", return_value=root), \
                mock.patch.object(check_ownership, "validate", return_value=[]) as validate:
            self.assertEqual(check_ownership.main([str(custom)]), 0)
        self.assertEqual(validate.call_args.args[1], {"files": {}})

    def test_validate_accepts_the_current_checked_in_tree(self):
        self.assertEqual(check_ownership.validate(ROOT, check_ownership.load_manifest(ROOT)), [])


class CommandTests(unittest.TestCase):
    def test_main_is_clean_on_the_real_tree(self):
        self.assertEqual(check_ownership.main(["scripts/ownership.json"]), 0)

    def test_main_returns_one_for_violations_and_two_for_bad_input(self):
        with mock.patch.object(check_ownership, "validate", return_value=["problem"]):
            self.assertEqual(check_ownership.main([]), 1)
        with mock.patch.object(check_ownership, "load_manifest", side_effect=ValueError("bad")):
            self.assertEqual(check_ownership.main([]), 2)

    def test_command_line_entry_point_returns_main_status(self):
        script = ROOT / "scripts" / "check-ownership.py"
        with mock.patch.object(sys, "argv", [str(script), "scripts/ownership.json"]), \
                self.assertRaises(SystemExit) as raised:
            runpy.run_path(str(script), run_name="__main__")
        self.assertEqual(raised.exception.code, 0)

    def test_source_has_no_process_or_network_api(self):
        source = (ROOT / "scripts" / "check-ownership.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        names = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    names.add(alias.name.split(".")[0])
            elif isinstance(node, ast.ImportFrom) and node.module:
                names.add(node.module.split(".")[0])
            elif isinstance(node, ast.Attribute):
                names.add(node.attr)
            elif isinstance(node, ast.Name):
                names.add(node.id)
        for forbidden in ("subprocess", "Popen", "system", "popen", "socket", "urllib",
                          "requests", "environ", "getenv"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, names)


if __name__ == "__main__":
    unittest.main()
