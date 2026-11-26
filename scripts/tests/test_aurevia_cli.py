"""Regression tests for the developer CLI's repository and tracker discovery."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT

CLI = ROOT / "scripts/aurevia/aurevia.sh"


class AureviaCliTests(unittest.TestCase):
    def make_repo(self, tasks):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", str(tmp)], check=False))
        (tmp / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        (tmp / "tasks").mkdir()
        for name, body in tasks.items():
            (tmp / "tasks" / name).write_text(body, encoding="utf-8")
        return tmp

    def run_cli(self, repo, command):
        return subprocess.run(["bash", str(CLI), "--repo", str(repo), command],
                              capture_output=True, text=True, check=False)

    def test_todo_counts_task_files_without_a_root_todo_md(self):
        repo = self.make_repo({
            "00001000-OPEN.md": "- [ ] OPEN · work\n",
            "00002000-TAKEN.md": "- [-] TAKEN · work\n  - agent: orca\n",
            "00003000-DONE.md": "- [x] DONE · work\n  - evidence: tool\n",
        })
        result = self.run_cli(repo, "todo")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "open 1 · taken 1 · done 1")
        self.assertFalse((repo / "TODO.md").exists())

    def test_find_repo_accepts_tasks_directory_from_a_clean_checkout(self):
        repo = self.make_repo({"00001000-OPEN.md": "- [ ] OPEN · work\n"})
        result = self.run_cli(repo, "todo")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("open 1", result.stdout)


if __name__ == "__main__":
    unittest.main()
