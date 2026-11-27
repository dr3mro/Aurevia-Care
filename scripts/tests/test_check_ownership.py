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

import yaml

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


def write_file(root, relative, content):
    """Write a synthetic repository file, creating its parent directories."""
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def write_package(root, relative, scripts):
    """Write a synthetic package manifest with only the supplied scripts."""
    return write_file(root, relative, json.dumps({"scripts": scripts}))


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

    def test_load_manifest_confines_relative_paths_but_trusts_explicit_absolute_paths(self):
        root = temp_root()
        outside = Path(tempfile.mkdtemp())
        write_file(outside, "ownership.json", '{"files": {}}')
        (root / "scripts" / "linked.json").symlink_to(outside / "ownership.json")
        with self.assertRaisesRegex(ValueError, "cannot read"):
            check_ownership.load_manifest(root, "scripts/linked.json")
        write_file(
            root,
            "scripts/large.json",
            "x" * (check_ownership.MAX_SOURCE_BYTES + 1))
        with self.assertRaisesRegex(ValueError, "cannot read"):
            check_ownership.load_manifest(root, "scripts/large.json")
        self.assertEqual(
            check_ownership.load_manifest(root, outside / "ownership.json"),
            {"files": {}})

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
        self.assertEqual(
            check_ownership.parse_text_refs("prefixscripts/tool.py foo/scripts/tool.py"),
            set())
        self.assertEqual(
            check_ownership.parse_text_refs("./scripts/tool.py"),
            {"scripts/tool.py"})
        self.assertEqual(
            check_ownership.normalize_reference("scripts/tool.py:10-12"),
            "scripts/tool.py")
        self.assertEqual(
            check_ownership.parse_python_refs("x = './scripts/tool.py'"),
            {"scripts/tool.py"})
        self.assertEqual(
            check_ownership.parse_shell_refs("bash ./scripts/tool.sh"),
            {"scripts/tool.sh"})

    def test_build_graph_keeps_only_known_targets(self):
        graph = check_ownership.build_graph({"a": {"b", "external"}, "b": set()})
        self.assertEqual(graph, {"a": ["b"], "b": []})

    def test_build_graph_canonicalizes_dot_prefixed_targets_before_cycle_detection(self):
        refs = {
            "scripts/a.py": {"./scripts/b.py"},
            "scripts/b.py": {"scripts/a.py"},
        }
        graph = check_ownership.build_graph(refs)
        self.assertEqual(graph, {
            "scripts/a.py": ["scripts/b.py"],
            "scripts/b.py": ["scripts/a.py"],
        })
        self.assertIn("scripts/b.py -> scripts/a.py", check_ownership.find_cycles(graph))

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


class ContextReferenceTests(unittest.TestCase):
    def test_package_reference_resolves_from_package_directory(self):
        root = temp_root()
        package = "apps/staff-web/package.json"
        write_package(root, package, {"gen:api": "bash scripts/gen-api-types.sh"})
        write_file(root, "apps/staff-web/scripts/gen-api-types.sh", "#!/usr/bin/env bash\n")
        refs = {package: {"scripts/gen-api-types.sh"}}

        self.assertEqual(check_ownership.check_references(root, refs), [])
        self.assertNotIn(
            "broken reference: scripts/gen-api-types.sh",
            check_ownership.validate(root, manifest()))

    def test_package_reference_missing_from_package_and_root_is_reported(self):
        root = temp_root()
        package = "apps/staff-web/package.json"
        write_package(root, package, {"missing": "bash scripts/missing.sh"})
        refs = {package: {"scripts/missing.sh"}}

        self.assertEqual(
            check_ownership.check_references(root, refs),
            ["broken reference: scripts/missing.sh"])
        self.assertIn(
            "broken reference: scripts/missing.sh",
            check_ownership.validate(root, manifest()))

    def test_root_package_resolves_from_root(self):
        root = temp_root()
        write_package(root, "package.json", {"root": "python3 -I scripts/root.py"})
        write_file(root, "scripts/root.py", "value = 1\n")
        refs = {"package.json": {"scripts/root.py"}}

        self.assertEqual(check_ownership.check_references(root, refs), [])
        self.assertNotIn(
            "broken reference: scripts/root.py",
            check_ownership.validate(root, manifest()))

    def test_subpackage_reference_falls_back_to_repo_root(self):
        root = temp_root()
        package = "apps/staff-web/package.json"
        write_package(root, package, {"root": "python3 -I scripts/root.py"})
        write_file(root, "scripts/root.py", "value = 1\n")
        refs = {package: {"scripts/root.py"}}

        self.assertEqual(check_ownership.check_references(root, refs), [])
        self.assertNotIn(
            "broken reference: scripts/root.py",
            check_ownership.validate(root, manifest()))

    def test_same_reference_uses_each_sources_own_directory(self):
        root = temp_root()
        write_package(root, "apps/demo/package.json", {"run": "bash scripts/tool.sh"})
        write_file(root, "apps/demo/scripts/tool.sh", "#!/usr/bin/env bash\n")
        refs = {
            "apps/demo/package.json": {"scripts/tool.sh"},
            "docs/guide.md": {"scripts/tool.sh"},
        }

        self.assertEqual(
            check_ownership.check_references(root, refs),
            ["broken reference: scripts/tool.sh"])

    def test_same_script_in_another_package_does_not_resolve_a_reference(self):
        root = temp_root()
        write_package(root, "apps/missing/package.json", {"run": "bash scripts/tool.sh"})
        write_package(root, "apps/present/package.json", {"run": "bash scripts/tool.sh"})
        write_file(root, "apps/present/scripts/tool.sh", "#!/usr/bin/env bash\n")

        self.assertIn(
            "broken reference: scripts/tool.sh",
            check_ownership.validate(root, manifest()))

    def test_package_discovery_is_bounded_and_ignores_unrelated_json_fields(self):
        root = temp_root()
        write_file(root, "apps/demo/package.json", json.dumps({
            "scripts": {"test": "echo ok"},
            "description": "bash scripts/not-a-script-reference.sh",
        }))
        write_file(root, "apps/bad/package.json", "{broken")
        write_package(root, "apps/non-string/package.json", {"bad": 7})
        write_package(root, "node_modules/demo/package.json", {"bad": "bash scripts/missing.sh"})
        write_package(root, ".workspaces/demo/package.json", {"bad": "bash scripts/missing.sh"})
        (root / "docs" / "folder.md").mkdir(parents=True)

        violations = check_ownership.validate(root, manifest())
        self.assertFalse(any("broken reference" in item for item in violations), violations)
        self.assertIn("apps/bad/package.json: invalid package JSON", violations)
        self.assertIn(
            "apps/non-string/package.json: package script values must be strings",
            violations)

    def test_package_references_read_only_string_script_values(self):
        text = json.dumps({
            "scripts": {"run": "bash scripts/tool.sh", "empty": "", "number": 7},
            "description": "bash scripts/ignored.sh",
        })
        refs, problems = check_ownership.package_references(text)
        self.assertEqual(refs, {"scripts/tool.sh"})
        self.assertEqual(problems, ["package script values must be strings"])
        cases = (
            ("{broken", ["invalid package JSON"]),
            ("[]", ["package JSON must be an object"]),
            ('{"scripts": []}', ["package scripts must be an object"]),
            ('{"scripts": {}}', []),
            ("{}", []),
        )
        for payload, expected in cases:
            with self.subTest(payload=payload):
                self.assertEqual(
                    check_ownership.package_references(payload),
                    (set(), expected))

    def test_workflow_references_use_step_job_and_workflow_directories(self):
        root = temp_root()
        workflow = {
            "defaults": {"run": {"working-directory": "apps/workflow"}},
            "jobs": {
                "inherited": {
                    "steps": [{"run": "python3 -I scripts/workflow.py"}],
                },
                "job-default": {
                    "defaults": {"run": {"working-directory": "apps/job"}},
                    "steps": [
                        {"run": "python3 -I scripts/job.py"},
                        {"run": "python3 -I scripts/root.py"},
                        {
                            "run": "python3 -I scripts/step.py",
                            "working-directory": "apps/step",
                        },
                    ],
                },
            },
        }
        write_file(root, ".github/workflows/context.yml", yaml.safe_dump(workflow))
        for relative in (
            "apps/workflow/scripts/workflow.py",
            "apps/job/scripts/job.py",
            "scripts/root.py",
            "apps/step/scripts/step.py",
        ):
            write_file(root, relative, "value = 1\n")

        entries, problems = check_ownership.workflow_references(
            yaml.safe_dump(workflow), root)
        self.assertEqual(problems, [])
        self.assertEqual(entries["inherited.0"][0], root / "apps/workflow")
        self.assertEqual(entries["job-default.0"][0], root / "apps/job")
        self.assertEqual(entries["job-default.2"][0], root / "apps/step")
        self.assertNotIn(
            "broken reference: scripts/root.py",
            check_ownership.validate(root, manifest()))

    def test_workflow_steps_do_not_share_working_directories(self):
        root = temp_root()
        workflow = {
            "jobs": {
                "build": {
                    "steps": [
                        {
                            "run": "python3 -I scripts/tool.py",
                            "working-directory": "apps/good",
                        },
                        {
                            "run": "python3 -I scripts/tool.py",
                            "working-directory": "apps/bad",
                        },
                    ],
                },
            },
        }
        write_file(root, ".github/workflows/context.yml", yaml.safe_dump(workflow))
        write_file(root, "apps/good/scripts/tool.py", "value = 1\n")

        entries, problems = check_ownership.workflow_references(yaml.safe_dump(workflow), root)
        self.assertEqual(problems, [])
        self.assertEqual(entries["build.0"][0], root / "apps/good")
        self.assertEqual(entries["build.1"][0], root / "apps/bad")
        self.assertIn(
            "broken reference: scripts/tool.py",
            check_ownership.validate(root, manifest()))

    def test_workflow_scanner_skips_wrong_shapes_and_keeps_valid_steps(self):
        root = temp_root()
        workflow = {
            "defaults": [],
            "jobs": {
                "wrong-jobs": None,
                "wrong-steps": {"steps": {}},
                "mixed": {
                    "defaults": [],
                    "steps": [
                        None,
                        {"run": 7},
                        {
                            "run": "python3 -I scripts/root.py",
                            "working-directory": 7,
                            "env": [],
                            "with": [],
                        },
                    ],
                },
            },
        }
        write_file(root, ".github/workflows/context.yml", yaml.safe_dump(workflow))
        write_file(root, ".github/workflows/notes.txt", "scripts/not-a-workflow.py\n")
        (root / ".github" / "workflows" / "directory.yml").mkdir()
        write_file(root, "scripts/root.py", "value = 1\n")

        entries, problems = check_ownership.workflow_references(yaml.safe_dump(workflow), root)
        self.assertEqual(list(entries), ["mixed.2"])
        self.assertEqual(entries["mixed.2"][0], root)
        self.assertEqual(problems, [
            "job mixed defaults must be an object",
            "job mixed step 0 must be an object",
            "job mixed step 1 run must be a string",
            "job mixed step 2 env must be an object",
            "job mixed step 2 with must be an object",
            "job mixed step 2 working-directory must be a string",
            "job wrong-jobs must be an object",
            "job wrong-steps steps must be a list",
            "workflow defaults must be an object",
        ])
        violations = check_ownership.validate(root, manifest())
        self.assertNotIn("broken reference: scripts/root.py", violations)
        self.assertNotIn("broken reference: scripts/not-a-workflow.py", violations)
        self.assertIn(
            ".github/workflows/context.yml: workflow defaults must be an object",
            violations)
        self.assertEqual(
            check_ownership.workflow_references("jobs: [", root),
            ({}, ["invalid workflow YAML"]))
        self.assertEqual(
            check_ownership.workflow_references("[]", root),
            ({}, ["workflow must be an object"]))
        self.assertEqual(
            check_ownership.workflow_references("jobs: []", root),
            ({}, ["workflow jobs must be an object"]))
        self.assertEqual(
            check_ownership.workflow_references(
                "env:\n  CHECK_SCRIPT: scripts/root.py\njobs: []\n", root),
            ({"workflow.env": (root, {"scripts/root.py"})},
             ["workflow jobs must be an object"]))

    def test_non_string_workflow_default_is_reported_once_when_inherited(self):
        root = temp_root()
        workflow = {
            "defaults": {"run": {"working-directory": 7}},
            "jobs": {
                "build": {
                    "steps": [{"run": "python3 -I scripts/root.py"}],
                },
            },
        }
        write_file(root, ".github/workflows/context.yml", yaml.safe_dump(workflow))
        write_file(root, "scripts/root.py", "value = 1\n")

        entries, problems = check_ownership.workflow_references(yaml.safe_dump(workflow), root)
        self.assertEqual(entries["build.0"][0], root)
        self.assertEqual(problems, [
            "workflow defaults.run working-directory must be a string",
        ])
        self.assertNotIn(
            "broken reference: scripts/root.py",
            check_ownership.validate(root, manifest()))

    def test_workflow_dynamic_directory_falls_back_to_root_and_missing_is_reported(self):
        root = temp_root()
        workflow = {
            "jobs": {
                "build": {
                    "steps": [
                        {
                            "run": "python3 -I scripts/root.py",
                            "working-directory": "${{ inputs.directory }}",
                        },
                        {
                            "run": "python3 -I scripts/missing.py",
                            "working-directory": "apps/job",
                        },
                    ],
                },
            },
        }
        write_file(root, ".github/workflows/context.yml", yaml.safe_dump(workflow))
        write_file(root, "scripts/root.py", "value = 1\n")

        entries, problems = check_ownership.workflow_references(yaml.safe_dump(workflow), root)
        self.assertEqual(problems, [])
        self.assertEqual(entries["build.0"][0], root)
        self.assertEqual(check_ownership.working_directory(root, None), root)
        violations = check_ownership.validate(root, manifest())
        self.assertNotIn("broken reference: scripts/root.py", violations)
        self.assertIn("broken reference: scripts/missing.py", violations)


    def test_workflow_job_and_step_metadata_keep_their_reference_context(self):
        root = temp_root()
        workflow = {
            "env": {"WORKFLOW_SCRIPT": "scripts/workflow.py"},
            "jobs": {
                "build": {
                    "env": {"CHECK_SCRIPT": "scripts/job.py"},
                    "steps": [
                        {
                            "run": "python3 -I scripts/run.py",
                            "env": {"STEP_SCRIPT": "scripts/env.py", "PLAIN": "echo ok"},
                            "with": {
                                "script": "scripts/with.py",
                                "message": "echo ok",
                                "number": 7,
                            },
                            "working-directory": "apps/tool",
                        },
                        {
                            "run": "echo ok",
                            "with": {"missing": "scripts/missing.py"},
                            "working-directory": "apps/tool",
                        },
                    ],
                },
            },
        }
        write_file(root, ".github/workflows/context.yml", yaml.safe_dump(workflow))
        for relative in (
            "scripts/workflow.py",
            "scripts/job.py",
            "apps/tool/scripts/run.py",
            "apps/tool/scripts/env.py",
            "apps/tool/scripts/with.py",
        ):
            write_file(root, relative, "value = 1\n")

        entries, problems = check_ownership.workflow_references(yaml.safe_dump(workflow), root)
        self.assertEqual(problems, [])
        self.assertEqual(entries["workflow.env"][0], root)
        self.assertEqual(entries["job.build.env"][0], root)
        self.assertEqual(entries["build.0"][0], root / "apps/tool")
        self.assertEqual(
            entries["build.0"][1],
            {"scripts/run.py", "scripts/env.py", "scripts/with.py"})
        violations = check_ownership.validate(root, manifest())
        self.assertNotIn("broken reference: scripts/job.py", violations)
        self.assertNotIn("broken reference: scripts/with.py", violations)
        self.assertIn("broken reference: scripts/missing.py", violations)

    def test_workflow_env_key_does_not_collide_with_a_job_named_workflow(self):
        root = temp_root()
        workflow = {
            "env": {"TOP_LEVEL": "scripts/top-level.py"},
            "jobs": {
                "workflow": {
                    "env": {"JOB_LEVEL": "scripts/job-level.py"},
                    "steps": [],
                },
            },
        }
        write_file(root, ".github/workflows/context.yml", yaml.safe_dump(workflow))
        write_file(root, "scripts/job-level.py", "value = 1\n")

        entries, problems = check_ownership.workflow_references(yaml.safe_dump(workflow), root)
        self.assertEqual(problems, [])
        self.assertEqual(entries["workflow.env"][1], {"scripts/top-level.py"})
        self.assertEqual(entries["job.workflow.env"][1], {"scripts/job-level.py"})
        violations = check_ownership.validate(root, manifest())
        self.assertNotIn("broken reference: scripts/job-level.py", violations)
        self.assertIn("broken reference: scripts/top-level.py", violations)

    def test_workflow_non_string_defaults_and_env_are_reported(self):
        root = temp_root()
        workflow = {
            "env": [],
            "defaults": {"run": {"working-directory": 7}},
            "jobs": {
                "build": {
                    "env": [],
                    "defaults": {"run": {"working-directory": 8}},
                    "steps": [],
                },
            },
        }

        entries, problems = check_ownership.workflow_references(yaml.safe_dump(workflow), root)
        self.assertEqual(entries, {})
        self.assertEqual(problems, [
            "job build defaults.run working-directory must be a string",
            "job build env must be an object",
            "workflow defaults.run working-directory must be a string",
            "workflow env must be an object",
        ])

    def test_empty_workflow_steps_contribute_no_reference_entries(self):
        root = temp_root()
        workflow = {"jobs": {"empty": {"steps": []}}}
        write_file(root, ".github/workflows/empty.yml", yaml.safe_dump(workflow))

        self.assertEqual(
            check_ownership.workflow_references(yaml.safe_dump(workflow), root),
            ({}, []))
        self.assertEqual(
            check_ownership.workflow_references("jobs: {}", root),
            ({}, []))
        self.assertFalse(
            any("broken reference" in item for item in check_ownership.validate(root, manifest())))

    def test_external_paths_and_symlinks_cannot_satisfy_references(self):
        root = temp_root()
        outside = Path(tempfile.mkdtemp())
        write_file(outside, "scripts/tool.py", "value = 1\n")
        workflow_source = ".github/workflows/context.yml#build.0"
        for base in (outside, root / ".." / outside.name):
            with self.subTest(base=base):
                self.assertEqual(
                    check_ownership.check_references(
                        root,
                        {workflow_source: {"scripts/tool.py"}},
                        base_dirs={workflow_source: (base,)}),
                    ["broken reference: scripts/tool.py"])

        package_source = "apps/demo/package.json"
        local_scripts = root / "apps/demo/scripts"
        local_scripts.parent.mkdir(parents=True)
        local_scripts.symlink_to(outside / "scripts", target_is_directory=True)
        self.assertEqual(
            check_ownership.check_references(
                root, {package_source: {"scripts/tool.py"}}),
            ["broken reference: scripts/tool.py"])

        loop_scripts = root / "apps/loop/scripts"
        loop_scripts.parent.mkdir(parents=True)
        loop_scripts.symlink_to("scripts", target_is_directory=True)
        self.assertEqual(
            check_ownership.check_references(
                root, {"apps/loop/package.json": {"scripts/tool.py"}}),
            ["broken reference: scripts/tool.py"])
        with mock.patch.object(Path, "resolve", side_effect=RuntimeError("symlink loop")):
            self.assertEqual(
                check_ownership.check_references(
                    root, {"apps/loop/package.json": {"scripts/tool.py"}}),
                ["broken reference: scripts/tool.py"])
        with mock.patch.object(Path, "resolve", side_effect=OSError("root failure")):
            self.assertEqual(
                check_ownership.check_references(
                    root, {"apps/loop/package.json": {"scripts/tool.py"}}),
                ["broken reference: scripts/tool.py"])
        self.assertEqual(
            check_ownership.check_references(root, {"scripts/bad\x00.py"}),
            ["broken reference: scripts/bad\x00.py"])
        with mock.patch.object(Path, "exists", side_effect=OSError("stat failure")):
            self.assertEqual(
                check_ownership.check_references(
                    root, {"apps/loop/package.json": {"scripts/tool.py"}}),
                ["broken reference: scripts/tool.py"])

    def test_symlinked_sources_outside_checkout_are_not_read(self):
        root = temp_root()
        outside = Path(tempfile.mkdtemp())
        write_file(outside, "package.json", json.dumps({
            "scripts": {"bad": "bash scripts/missing.sh"},
        }))
        write_file(outside, "workflow.yml", "jobs:\n  build:\n    steps:\n      - run: scripts/missing.py\n")
        write_file(outside, "guide.md", "Run `scripts/missing.py`.\n")
        write_file(outside, "external.py", "value = 1\n")
        links = (
            ("apps/demo/package.json", outside / "package.json"),
            (".github/workflows/external.yml", outside / "workflow.yml"),
            ("docs/external.md", outside / "guide.md"),
            ("scripts/external.py", outside / "external.py"),
        )
        for relative, target in links:
            link = root / relative
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(target)

        violations = check_ownership.validate(root, manifest())
        for relative in ("apps/demo/package.json", ".github/workflows/external.yml",
                         "docs/external.md", "scripts/external.py"):
            self.assertIn(f"source escapes repository: {relative}", violations)
        self.assertFalse(any("broken reference" in item for item in violations), violations)
        self.assertIn("scripts/external.py", check_ownership.discover(root))

    def test_oversized_and_unreadable_sources_fail_without_parsing(self):
        root = temp_root()
        write_file(
            root,
            "apps/demo/package.json",
            "x" * (check_ownership.MAX_SOURCE_BYTES + 1))
        write_file(
            root,
            "scripts/large.py",
            "x" * (check_ownership.MAX_SOURCE_BYTES + 1))
        original_read = Path.read_text
        large_reads = []

        def count_large_reads(path, *args, **kwargs):
            if path.name == "large.py":
                large_reads.append(path)
            return original_read(path, *args, **kwargs)

        with mock.patch.object(Path, "read_text", count_large_reads):
            violations = check_ownership.validate(root, manifest())
        self.assertEqual(large_reads, [])
        self.assertIn("source is too large: apps/demo/package.json", violations)
        self.assertIn("source is too large: scripts/large.py", violations)
        self.assertNotIn("apps/demo/package.json: invalid package JSON", violations)

        source = write_file(root, "package.json", "{}")
        problems = []
        with mock.patch.object(Path, "read_text", side_effect=OSError("read failed")):
            self.assertIsNone(check_ownership._read_source(
                root, source, "package.json", problems))
        self.assertEqual(problems, ["cannot read source: package.json"])
        problems = []
        self.assertIsNone(check_ownership._read_source(
            root, root / "bad\x00.json", "bad.json", problems))
        self.assertEqual(problems, ["cannot read source: bad.json"])

    def test_validate_reports_unreadable_script_without_mode_reread(self):
        root = temp_root()
        write_file(root, "scripts/unreadable.py", "value = 1\n")
        original_read = Path.read_text

        def reject_unreadable(path, *args, **kwargs):
            if path.name == "unreadable.py":
                raise OSError("read failed")
            return original_read(path, *args, **kwargs)

        with mock.patch.object(Path, "read_text", reject_unreadable):
            violations = check_ownership.validate(root, manifest())
        self.assertIn("cannot read source: scripts/unreadable.py", violations)

    def test_dot_prefixed_missing_reference_is_normalized(self):
        root = temp_root()
        self.assertEqual(
            check_ownership.check_references(root, {"./scripts/missing.py"}),
            ["broken reference: scripts/missing.py"])

    def test_markdown_and_yaml_document_references_are_still_checked(self):
        root = temp_root()
        write_file(root, "docs/guide.md", "Run `scripts/markdown.py`.\n")
        write_file(root, "docs/config.yml", "run: scripts/yaml.py\n")
        write_file(root, "docs/notes.txt", "Run `scripts/ignored.py`.\n")

        violations = check_ownership.validate(root, manifest())
        self.assertIn("broken reference: scripts/markdown.py", violations)
        self.assertIn("broken reference: scripts/yaml.py", violations)
        self.assertNotIn("broken reference: scripts/ignored.py", violations)


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

    def test_check_modes_reports_stat_errors_for_supplied_content(self):
        root = temp_root()
        with mock.patch.object(Path, "stat", side_effect=OSError("stat failed")):
            problems = check_ownership.check_modes(
                ["scripts/tool.py"], root, {"scripts/tool.py": "value = 1\n"})
        self.assertEqual(problems, ["cannot inspect script mode: scripts/tool.py"])
        write_file(root, "scripts/tool.py", "value = 1\n")
        with mock.patch.object(Path, "read_text", side_effect=OSError("read failed")):
            problems = check_ownership.check_modes(["scripts/tool.py"], root)
        self.assertEqual(problems, ["cannot read source: scripts/tool.py"])

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

    def test_check_gate_paths_rejects_external_symlinks_and_directories(self):
        root = temp_root()
        outside = Path(tempfile.mkdtemp())
        write_file(outside, "check.sh", "#!/usr/bin/env bash\n")
        for name in ("quick-check.sh", "build-and-test.sh"):
            write_file(root, f"scripts/{name}", "#!/usr/bin/env bash\n")
        (root / "scripts" / "check.sh").symlink_to(outside / "check.sh")
        self.assertEqual(
            check_ownership.check_gate_paths(root),
            ["missing gate script: scripts/check.sh"])
        (root / "scripts" / "check.sh").unlink()
        (root / "scripts" / "check.sh").mkdir()
        self.assertEqual(
            check_ownership.check_gate_paths(root),
            ["missing gate script: scripts/check.sh"])

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
