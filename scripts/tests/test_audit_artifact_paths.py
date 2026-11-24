"""Scheduled audit jobs publish their artifacts from .agents/, not the swarm's .agent/."""

import shutil
import unittest

import test_agent_run


class ScheduledAuditPathTests(unittest.TestCase):
    """Exercise the real agent-run.sh fixture without re-running every story test.

    The helper methods are borrowed from AgentRunTests so this file stays small
    and cannot be lost when another session edits the large story test module.
    """

    setUp = test_agent_run.AgentRunTests.setUp
    fixture_environment = test_agent_run.AgentRunTests.fixture_environment
    git = test_agent_run.AgentRunTests.git
    run_cmd = test_agent_run.AgentRunTests.run_cmd
    outputs = test_agent_run.AgentRunTests.outputs

    def test_scheduled_reviews_write_their_artifacts_under_agents(self):
        for command in ("audit", "strategy", "daily-qa", "daily-devsecops"):
            with self.subTest(command=command):
                prompt_log = self.tmp / f"prompt-{command}.log"
                result = self.run_cmd(command, FAKE_MODE="none", PROMPT_LOG=str(prompt_log))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.outputs()["result"], "ok")
                self.assertTrue((self.repo / ".agents").is_dir())
                self.assertFalse((self.repo / ".agent").exists())
                self.assertIn(".agents/", prompt_log.read_text())
                shutil.rmtree(self.repo / ".agents")

    def test_worker_commands_keep_the_agent_scratch_directory(self):
        self.assertEqual(self.run_cmd("implement", FAKE_MODE="none").returncode, 0)
        self.assertTrue((self.repo / ".agent/notes-stamp").exists())
        self.assertFalse((self.repo / ".agents").exists())


if __name__ == "__main__":
    unittest.main()
