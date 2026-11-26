"""Integrator unit tests must never execute Git in the real workspace.

The scenarios below include production rollback paths that call ``git reset``.
Running them with all five subprocess entry points forbidden proves the tests
stay on mocked boundaries; a missing mock fails before any process can start.
A static completeness check enumerates every caller of the Git-boundary helpers
so a new scenario cannot silently fall outside this guard.
"""
import ast
import io
import pathlib
import unittest
from unittest import mock

from helpers import load
import test_swarm
import test_swarm_delivery_state


swarm = load("swarm")

ROOT = pathlib.Path(__file__).resolve().parents[2]
GIT_BOUNDARIES = frozenset({"integrate_batch", "restore_trackers"})
SPAWNING_BOUNDARIES = frozenset({"git", "pr_already_in_main", "merge_local"})


def isolation_nodes(tree, class_node, method_node):
    """Collect a test method's AST and the helpers it depends on.

    Instance helpers and ``setUp`` are followed so isolation established in a
    fixture is visible; module-level helpers such as ``scratch`` are followed
    too. Nodes are returned rather than source text so a docstring or string
    literal cannot be mistaken for a real mock. The invariant is that no caller
    of a Git-boundary helper is omitted from the completeness check just because
    its isolation lives in a helper.
    """
    methods = {n.name: n for n in class_node.body
               if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
    module_functions = {n.name: n for n in tree.body
                        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
    seen = set()
    nodes = []

    def visit(node):
        if id(node) in seen:
            return
        seen.add(id(node))
        nodes.append(node)
        for call in ast.walk(node):
            if not isinstance(call, ast.Call):
                continue
            if (isinstance(call.func, ast.Attribute) and isinstance(call.func.value, ast.Name)
                    and call.func.value.id == "self" and call.func.attr in methods):
                visit(methods[call.func.attr])
            elif isinstance(call.func, ast.Name) and call.func.id in module_functions:
                visit(module_functions[call.func.id])

    visit(method_node)
    if "setUp" in methods:
        visit(methods["setUp"])
    return nodes


def called_name(call):
    """The simple name of a call target, for Name and Attribute calls."""
    if isinstance(call.func, ast.Name):
        return call.func.id
    if isinstance(call.func, ast.Attribute):
        return call.func.attr
    return ""


def patched_boundaries(call):
    """The ``swarm`` names patched by one ``patch.object(swarm, ...)`` call."""
    if not (isinstance(call.func, ast.Attribute) and call.func.attr == "object"):
        return frozenset()
    if not any(isinstance(arg, ast.Name) and arg.id == "swarm" for arg in call.args):
        return frozenset()
    return frozenset(arg.value for arg in call.args
                     if isinstance(arg, ast.Constant)
                     and arg.value in SPAWNING_BOUNDARIES | GIT_BOUNDARIES)


def isolated_from_checkout(nodes):
    """Whether collected nodes keep Git away from the real checkout.

    True when a temporary directory is created and entered, when every boundary
    that spawns a Git process is mocked, or when the boundary helper itself is
    mocked (CLI dispatch tests). Mocking only ``git`` is not enough: a leftover
    ``merge_local`` still spawns ``git merge``. A string literal that resembles
    a patch is never counted as a mock.
    """
    patched = set()
    temporary = False
    chdir = False
    for node in nodes:
        for call in ast.walk(node):
            if not isinstance(call, ast.Call):
                continue
            patched.update(patched_boundaries(call))
            name = called_name(call)
            if name in {"mkdtemp", "TemporaryDirectory"}:
                temporary = True
            elif name == "chdir":
                chdir = True
    return ((temporary and chdir) or SPAWNING_BOUNDARIES.issubset(patched)
            or bool(patched & GIT_BOUNDARIES))


def boundary_callers(tree, class_node):
    """Names that reach a Git boundary directly or through instance/module helpers."""
    helpers = {n.name: n for n in class_node.body
               if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
    helpers.update({n.name: n for n in tree.body
                    if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))})
    direct = {name for name, node in helpers.items()
              if any(isinstance(c, ast.Call) and called_name(c) in GIT_BOUNDARIES
                     for c in ast.walk(node))}
    changed = True
    while changed:
        changed = False
        for name, node in helpers.items():
            if name in direct:
                continue
            if any(isinstance(c, ast.Call)
                   and ((isinstance(c.func, ast.Attribute) and isinstance(c.func.value, ast.Name)
                         and c.func.value.id == "self" and c.func.attr in direct)
                        or (isinstance(c.func, ast.Name) and c.func.id in direct))
                   for c in ast.walk(node)):
                direct.add(name)
                changed = True
    return direct


def main_invokes_integrator(node):
    """Whether a method dispatches the integrator through ``swarm.main``."""
    return any(isinstance(call, ast.Call) and called_name(call) == "main"
               and isinstance(call.func, ast.Attribute)
               and isinstance(call.func.value, ast.Name) and call.func.value.id == "swarm"
               and any(isinstance(arg, ast.Constant) and arg.value == "integrate-batch"
                       for arg in ast.walk(call))
               for call in ast.walk(node))


class WorkspaceGitIsolationTests(unittest.TestCase):
    def nodes_for(self, source):
        """Parse a synthetic test class and return its first test method's nodes."""
        tree = ast.parse(source)
        class_node = next(n for n in tree.body if isinstance(n, ast.ClassDef))
        method = next(n for n in class_node.body
                      if isinstance(n, ast.FunctionDef) and n.name.startswith("test_"))
        return isolation_nodes(tree, class_node, method)

    def test_integrator_scenarios_do_not_start_subprocesses(self):
        cases = [
            test_swarm.ResolveConflictTests(
                "test_conflict_is_handed_to_the_resolver_not_failed"
            ),
            test_swarm.ResolveConflictTests(
                "test_conflict_goes_back_to_the_workers_after_three_tries"
            ),
            test_swarm.ResolveConflictTests(
                "test_a_branch_that_moved_after_planning_is_retested_without_the_debugger"
            ),
            test_swarm.IntegrationTests(
                "test_restore_trackers_preserves_untracked_files"
            ),
            test_swarm.IntegrationTests(
                "test_restore_trackers_restores_absent_untracked_path_from_main"
            ),
            test_swarm.IntegrationTests(
                "test_restore_trackers_removes_only_tracked_paths_missing_from_main"
            ),
            test_swarm.IntegrationTests(
                "test_restore_trackers_refuses_untracked_collision_before_mutation"
            ),
            test_swarm.IntegrationTests(
                "test_restore_trackers_refuses_broken_symlink_collision"
            ),
            test_swarm_delivery_state.IntegratorWiringTest(
                "test_delivery_verdict_blocks_staging"
            ),
            test_swarm_delivery_state.IntegratorWiringTest(
                "test_merge_verdict_allows_staging"
            ),
            test_swarm_delivery_state.IntegratorWiringTest(
                "test_ci_integrator_requires_evidence_without_changing_the_cli_contract"
            ),
            test_swarm_delivery_state.IntegratorWiringTest(
                "test_local_merge_mechanics_caller_has_no_ci_evidence_to_check"
            ),
            test_swarm_delivery_state.IntegratorWiringTest(
                "test_explicit_false_overrides_ci_default"
            ),
        ]
        output = io.StringIO()
        error = AssertionError("integrator unit test started a real subprocess")
        with mock.patch.object(swarm.subprocess, "run", side_effect=error) as run, \
                mock.patch.object(swarm.subprocess, "check_output", side_effect=error) as checked, \
                mock.patch.object(swarm.subprocess, "Popen", side_effect=error) as spawned, \
                mock.patch.object(swarm.subprocess, "call", side_effect=error) as called, \
                mock.patch.object(swarm.subprocess, "check_call", side_effect=error) as check_called:
            result = unittest.TextTestRunner(stream=output).run(unittest.TestSuite(cases))
        self.assertEqual(result.testsRun, 13)
        self.assertEqual(result.skipped, [])
        self.assertEqual(result.expectedFailures, [])
        self.assertTrue(result.wasSuccessful(), output.getvalue())
        run.assert_not_called()
        checked.assert_not_called()
        spawned.assert_not_called()
        called.assert_not_called()
        check_called.assert_not_called()

    def test_every_git_boundary_caller_is_isolated_from_the_real_checkout(self):
        """Every caller of a Git-boundary helper is mocked or in a temp checkout."""
        callers = []
        for path in sorted((ROOT / "scripts/tests").glob("test_*.py")):
            tree = ast.parse(path.read_text())
            for class_node in (n for n in tree.body if isinstance(n, ast.ClassDef)):
                reachable = boundary_callers(tree, class_node)
                for method in (n for n in class_node.body
                               if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
                               and n.name.startswith("test_")):
                    if method.name not in reachable and not main_invokes_integrator(method):
                        continue
                    label = f"{path.name}:{class_node.name}.{method.name}"
                    callers.append((label, isolated_from_checkout(
                        isolation_nodes(tree, class_node, method))))
        self.assertGreater(len(callers), 10, "static scan found too few Git-boundary callers")
        exposed = [label for label, safe in callers if not safe]
        self.assertEqual(exposed, [], f"unisolated Git-boundary callers: {exposed}")

    def test_isolation_classification_covers_mocks_and_temporary_checkouts(self):
        boundaries = ("git", "pr_already_in_main", "merge_local")
        lines = [" " * (8 + 4 * index) + f"with mock.patch.object(swarm, '{name}'):"
                 for index, name in enumerate(boundaries)]
        lines.append(" " * (8 + 4 * len(boundaries)) + "swarm.integrate_batch([7])")
        source = ("class Sample(unittest.TestCase):\n"
                  "    def test_case(self):\n" + "\n".join(lines) + "\n")
        self.assertTrue(isolated_from_checkout(self.nodes_for(source)))
        self.assertTrue(isolated_from_checkout(self.nodes_for('''
class Sample(unittest.TestCase):
    def test_case(self):
        with mock.patch.object(swarm, "integrate_batch"):
            swarm.main(["integrate-batch", "[7]"])
''')))
        self.assertTrue(isolated_from_checkout(self.nodes_for('''
class Sample(unittest.TestCase):
    def test_case(self):
        root = tempfile.mkdtemp()
        os.chdir(root)
''')))
        self.assertFalse(isolated_from_checkout(self.nodes_for('''
class Sample(unittest.TestCase):
    def test_case(self):
        swarm.integrate_batch([7])
''')))
        self.assertFalse(isolated_from_checkout(self.nodes_for('''
class Sample(unittest.TestCase):
    def test_case(self):
        with mock.patch.object(swarm, "git"):
            swarm.integrate_batch([7])
''')))
        self.assertFalse(isolated_from_checkout(self.nodes_for('''
class Sample(unittest.TestCase):
    def test_case(self):
        os.chdir(root)
''')))
        self.assertFalse(isolated_from_checkout(self.nodes_for('''
class Sample(unittest.TestCase):
    def test_case(self):
        tempfile.mkdtemp()
''')))
        # A string resembling a mock is not a mock: the classifier fails closed.
        self.assertFalse(isolated_from_checkout(self.nodes_for('''
class Sample(unittest.TestCase):
    def test_case(self):
        """Use mock.patch.object(swarm, 'git') in the real test."""
        swarm.integrate_batch([7])
''')))

    def test_isolation_source_follows_helpers_setup_and_cycles(self):
        source = '''
import os
import tempfile
def scratch():
    return tempfile.mkdtemp()
class Sample(unittest.TestCase):
    def setUp(self):
        self.dir = scratch()
        os.chdir(self.dir)
    def helper(self):
        self.dir = scratch()
    def cyclic_a(self):
        self.cyclic_b()
    def cyclic_b(self):
        self.cyclic_a()
    def test_case(self):
        self.helper()
        self.cyclic_a()
        swarm.integrate_batch([7])
    def test_cycle_only(self):
        self.cyclic_a()
'''
        tree = ast.parse(source)
        class_node = next(n for n in tree.body if isinstance(n, ast.ClassDef))
        for name in ("test_case", "test_cycle_only"):
            method = next(n for n in class_node.body
                          if isinstance(n, ast.FunctionDef) and n.name == name)
            collected = isolation_nodes(tree, class_node, method)
            self.assertTrue(isolated_from_checkout(collected), name)
        case = next(n for n in class_node.body
                    if isinstance(n, ast.FunctionDef) and n.name == "test_case")
        self.assertIn("mkdtemp", ast.unparse(isolation_nodes(tree, class_node, case)))

    def test_boundary_callers_follow_helpers_and_main_dispatch(self):
        source = '''
class Sample(unittest.TestCase):
    def helper(self):
        swarm.restore_trackers()
    def test_indirect(self):
        self.helper()
    def test_dispatch(self):
        swarm.main(["integrate-batch", "[7]"])
    def test_unrelated(self):
        self.assertEqual(1, 1)
'''
        tree = ast.parse(source)
        class_node = next(n for n in tree.body if isinstance(n, ast.ClassDef))
        self.assertIn("helper", boundary_callers(tree, class_node))
        self.assertIn("test_indirect", boundary_callers(tree, class_node))
        names = {n.name: n for n in class_node.body if isinstance(n, ast.FunctionDef)}
        self.assertTrue(main_invokes_integrator(names["test_dispatch"]))
        self.assertFalse(main_invokes_integrator(names["test_unrelated"]))
        self.assertTrue(any(isinstance(c, ast.Call) and isinstance(c.func, ast.Attribute)
                            and isinstance(c.func.value, ast.Name) and c.func.value.id == "self"
                            and c.func.attr == "helper"
                            for c in ast.walk(names["test_indirect"])))


if __name__ == "__main__":
    unittest.main()
