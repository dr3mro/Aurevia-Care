"""Integrator unit tests must never execute Git in the real workspace.

The scenarios below include production rollback paths that call ``git reset``.
Running them with ``subprocess.run`` forbidden proves the tests stay on mocked
boundaries; a missing mock fails before any real process can start.
"""
import io
import unittest
from unittest import mock

from helpers import load
import test_swarm
import test_swarm_delivery_state


swarm = load("swarm")


class WorkspaceGitIsolationTests(unittest.TestCase):
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
                mock.patch.object(swarm.subprocess, "Popen", side_effect=error) as spawned:
            result = unittest.TextTestRunner(stream=output).run(unittest.TestSuite(cases))
        self.assertEqual(result.testsRun, 13)
        self.assertEqual(result.skipped, [])
        self.assertEqual(result.expectedFailures, [])
        self.assertTrue(result.wasSuccessful(), output.getvalue())
        run.assert_not_called()
        checked.assert_not_called()
        spawned.assert_not_called()


if __name__ == "__main__":
    unittest.main()
