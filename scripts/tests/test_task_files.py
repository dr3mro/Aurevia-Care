"""How swarm.py works with the task-file tracker (scripts/taskstore.py)."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")
taskstore = swarm.store()

TEXT = ("# TODO\n\n- [ ] A-01 · First\n  - scope: a\n- [-] A-02 · Second\n  - scope: b\n"
        "- [x] A-03 · Third\n  - scope: c\n")


class Work(unittest.TestCase):
    """Each test gets a scratch repository whose tracker lives only in tasks/."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        original = Path.cwd()
        os.chdir(self.dir)
        self.addCleanup(os.chdir, original)
        taskstore.write(TEXT)
        (Path(taskstore.ROOT) / taskstore.MARK).write_text("one file per task\n")


class ReadWriteTests(Work):
    def test_the_tracker_is_read_from_the_task_files(self):
        self.assertEqual(swarm.todo_read(), TEXT)
        self.assertEqual(swarm.todo_paths(), ["tasks"])

    def test_another_path_is_read_and_written_directly(self):
        Path("other.md").write_text("o\n")
        self.assertEqual(swarm.todo_read("other.md"), "o\n")
        swarm.todo_write("other.md", "p\n")
        self.assertEqual(Path("other.md").read_text(), "p\n")
        self.assertEqual(swarm.todo_paths("other.md"), ["other.md"])

    def test_a_write_updates_only_the_task_file(self):
        swarm.todo_write("", TEXT.replace("[ ] A-01", "[-] A-01"))
        self.assertIn("[-] A-01", Path("tasks/00001000-A-01.md").read_text())
        self.assertIn("[-] A-01", swarm.todo_read())

    def test_tasks_and_config_trackers_are_protected(self):
        self.assertTrue(swarm.is_tracker("tasks/00001000-A-01.md"))
        self.assertTrue(swarm.is_tracker(".opencode/config/MEMORY.md"))
        self.assertFalse(swarm.is_tracker("apps/api/A.java"))


class UpdateTests(Work):
    def test_an_update_commits_only_the_task_files(self):
        with mock.patch.object(swarm, "git") as git, mock.patch.object(swarm, "authed_git"):
            changed = swarm.update_todo(lambda t: t.replace("[ ] A-01", "[-] A-01"), "Take A-01")
        self.assertTrue(changed)
        git.assert_any_call("add", "tasks")
        self.assertIn("[-] A-01", Path("tasks/00001000-A-01.md").read_text())

    def test_an_update_that_changes_nothing_pushes_nothing(self):
        with mock.patch.object(swarm, "git") as git:
            self.assertFalse(swarm.update_todo(lambda t: t, "nothing"))
        git.assert_not_called()

    def test_task_field_reads_from_the_files(self):
        self.assertIn("First", swarm.task_field("A-01", "title"))


class ArchiveTests(Work):
    def test_finished_tasks_move_to_the_done_folder_and_still_satisfy_depends(self):
        self.assertEqual(swarm.archive_todo(), 1)
        self.assertTrue(Path("tasks/done/A-03.md").exists())
        self.assertFalse(list(Path("tasks").glob("0*A-03.md")))
        self.assertNotIn("A-03", swarm.todo_read())
        self.assertIn("A-03", swarm.archived_done_ids())

    def test_nothing_finished_moves_nothing(self):
        taskstore.write("- [ ] A-01 · First\n")
        self.assertEqual(swarm.archive_todo(), 0)


class GitMergeTests(unittest.TestCase):
    """merge_local against a real repository: tracker conflicts are settled, others are not."""

    def git(self, *args):
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=self.dir,
                       check=True, capture_output=True, text=True)

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        original = Path.cwd()
        os.chdir(self.dir)
        self.addCleanup(os.chdir, original)
        self.git("init", "-q", "-b", "main")
        Path(".opencode/config").mkdir(parents=True)
        Path(".opencode/config/MEMORY.md").write_text("one\n")
        Path("code.txt").write_text("one\n")
        self.git("add", ".")
        self.git("commit", "-qm", "base")

    def branch(self, name, files):
        self.git("checkout", "-q", "-b", name)
        for path, text in files.items():
            Path(path).write_text(text)
        self.git("commit", "-qam", name)
        self.git("checkout", "-q", "main")

    def merge(self, branch):
        def fetch(*args, **kwargs):
            if args[0] == "fetch":
                self.git("branch", "-f", "pr-9", branch)
            return ""
        with mock.patch.object(swarm, "gh_json", return_value={"headRefName": "swarm/CW-001-5"}), \
             mock.patch.object(swarm, "authed_git", side_effect=fetch):
            return swarm.merge_local(9)

    def test_a_conflict_only_in_trackers_keeps_mains_version(self):
        tracker = ".opencode/config/MEMORY.md"
        self.branch("pr", {tracker: "worker\n"})
        Path(tracker).write_text("main\n")
        self.git("commit", "-qam", "main edit")
        sha, task, files = self.merge("pr")
        self.assertIsNotNone(sha)
        self.assertEqual(files, [])
        self.assertEqual(Path(tracker).read_text(), "main\n")

    def test_a_conflict_in_code_is_still_reported(self):
        self.branch("pr", {"code.txt": "worker\n"})
        Path("code.txt").write_text("main\n")
        self.git("commit", "-qam", "main edit")
        sha, task, files = self.merge("pr")
        self.assertIsNone(sha)
        self.assertEqual(files, ["code.txt"])

    def test_a_clean_merge_is_unchanged(self):
        self.branch("pr", {"code.txt": "worker\n"})
        sha, task, files = self.merge("pr")
        self.assertTrue(sha)
        self.assertEqual(files, [])

    def test_restoring_trackers_takes_fresh_mains_task_files(self):
        Path("tasks").mkdir()
        Path("tasks/00001000-A.md").write_text("main\n")
        Path("docs/swarm").mkdir(parents=True)
        Path("docs/swarm/n.md").write_text("n\n")
        self.git("add", ".")
        self.git("commit", "-qm", "tasks")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        Path("tasks/00001000-A.md").write_text("local\n")
        Path("tasks/00002000-B.md").write_text("stray\n")
        swarm.restore_trackers()
        self.assertEqual(Path("tasks/00001000-A.md").read_text(), "main\n")
        self.assertFalse(Path("tasks/00002000-B.md").exists())


class CommandTests(Work):
    """The command-line entry points read the task text through the same helpers."""

    def test_plan_workers_reads_the_task_files_and_plans_nothing_when_nothing_is_ready(self):
        with mock.patch.object(swarm, "gh_json", return_value=[]), \
             mock.patch.object(swarm, "live_workers", return_value=[]), \
             mock.patch.object(swarm, "load_slots", return_value=[{"slot": 1}]), \
             mock.patch.object(swarm, "plan_workers", return_value=[]) as planned, \
             mock.patch.object(swarm, "update_todo"), mock.patch.object(swarm, "note_claims"), \
             mock.patch.object(swarm, "emit") as emitted, mock.patch("builtins.print"):
            swarm.main(["plan-workers"])
        self.assertIn("First", planned.call_args.args[2])
        emitted.assert_any_call("count", 0)

    def test_continue_reads_the_task_files(self):
        with mock.patch.object(swarm, "gh_json", return_value=[]), \
             mock.patch.object(swarm, "live_workers", return_value=[]), \
             mock.patch.object(swarm, "continue_swarm", return_value=False) as decided, \
             mock.patch("builtins.print") as shown:
            swarm.main(["continue"])
        self.assertIn("First", decided.call_args.args[0])
        shown.assert_called_with("No further wave to start now.")

    def test_top_up_command_reports_whether_it_started_a_wave(self):
        for started, said in ((True, "Started another wave."), (False, "No room or no ready task.")):
            with self.subTest(started=started), mock.patch.object(swarm, "top_up", return_value=started) as ran, \
                 mock.patch("builtins.print") as shown:
                swarm.main(["top-up", "--id", "CW-001"])
            ran.assert_called_once_with("CW-001")
            shown.assert_called_with(said)
