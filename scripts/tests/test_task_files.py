"""How swarm.py works with tasks stored one file each (scripts/taskstore.py)."""
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
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        original = Path.cwd()
        os.chdir(self.dir)
        self.addCleanup(os.chdir, original)
        Path("TODO.md").write_text(TEXT)


class ReadWriteTests(Work):
    def test_a_plain_file_is_read_and_written_directly(self):
        self.assertEqual(swarm.todo_read(), TEXT)
        swarm.todo_write("TODO.md", "x\n")
        self.assertEqual(Path("TODO.md").read_text(), "x\n")
        self.assertEqual(swarm.todo_paths(), ["TODO.md"])
        self.assertFalse(Path("tasks").exists())

    def test_another_path_is_never_sharded(self):
        taskstore.migrate()
        Path("other.md").write_text("o\n")
        self.assertEqual(swarm.todo_read("other.md"), "o\n")
        swarm.todo_write("other.md", "p\n")
        self.assertEqual(Path("other.md").read_text(), "p\n")
        self.assertEqual(swarm.todo_paths("other.md"), ["other.md"])

    def test_task_files_are_used_once_they_exist(self):
        taskstore.migrate()
        swarm.todo_write("TODO.md", TEXT.replace("[ ] A-01", "[-] A-01"))
        self.assertIn("[-] A-01", Path("tasks/00001000-A-01.md").read_text())
        self.assertIn("[-] A-01", swarm.todo_read())
        self.assertEqual(swarm.todo_paths(), ["TODO.md", "tasks"])

    def test_tasks_are_a_tracker(self):
        self.assertTrue(swarm.is_tracker("tasks/00001000-A-01.md"))
        self.assertTrue(swarm.is_tracker("TODO.md"))
        self.assertFalse(swarm.is_tracker("apps/api/A.java"))


class UpdateTests(Work):
    def test_an_update_stages_the_view_and_the_task_files(self):
        taskstore.migrate()
        with mock.patch.object(swarm, "git") as git, mock.patch.object(swarm, "authed_git"):
            changed = swarm.update_todo(lambda t: t.replace("[ ] A-01", "[-] A-01"), "Take A-01")
        self.assertTrue(changed)
        git.assert_any_call("add", "TODO.md", "tasks")
        self.assertIn("[-] A-01", Path("tasks/00001000-A-01.md").read_text())

    def test_an_update_that_changes_nothing_pushes_nothing(self):
        taskstore.migrate()
        with mock.patch.object(swarm, "git") as git:
            self.assertFalse(swarm.update_todo(lambda t: t, "nothing"))
        git.assert_not_called()

    def test_task_field_reads_from_the_files(self):
        taskstore.migrate()
        self.assertIn("First", swarm.task_field("A-01", "title"))


class ArchiveTests(Work):
    def test_finished_tasks_move_to_the_done_folder_and_still_satisfy_depends(self):
        taskstore.migrate()
        self.assertEqual(swarm.archive_todo(), 1)
        self.assertTrue(Path("tasks/done/A-03.md").exists())
        self.assertFalse(list(Path("tasks").glob("0*A-03.md")))
        self.assertNotIn("A-03", Path("TODO.md").read_text())
        self.assertIn("A-03", swarm.archived_done_ids())

    def test_without_task_files_the_archive_file_is_used(self):
        self.assertEqual(swarm.archive_todo("TODO.md", "archive.md"), 1)
        self.assertIn("A-03", Path("archive.md").read_text())
        self.assertEqual(swarm.archived_done_ids("archive.md"), {"A-03"})

    def test_nothing_finished_moves_nothing(self):
        Path("TODO.md").write_text("- [ ] A-01 · First\n")
        taskstore.migrate()
        self.assertEqual(swarm.archive_todo(), 0)


class MigrateTests(Work):
    def reset_to_main(self, *args, **kwargs):
        """What `git reset --hard origin/main` does here: main still has only the old TODO.md."""
        if args[:2] == ("reset", "-q"):
            shutil.rmtree("tasks", ignore_errors=True)
            Path("TODO.md").write_text(TEXT)

    def run_migrate(self, push=None, **env):
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(swarm, "git", side_effect=self.reset_to_main) as git, \
             mock.patch.object(swarm, "authed_git", side_effect=push or (lambda *a, **k: "")) as authed, \
             mock.patch.object(swarm.time, "sleep"):
            return swarm.migrate_tasks(), git, authed

    def test_moves_the_tasks_and_pushes_once(self):
        moved, git, authed = self.run_migrate()
        self.assertEqual(moved, 3)
        self.assertTrue(taskstore.active())
        git.assert_any_call("add", "TODO.md", "tasks")
        authed.assert_called_once_with("push", "origin", "HEAD:main")

    def test_the_switch_turns_it_off(self):
        moved, git, authed = self.run_migrate(SWARM_TASK_FILES="off")
        self.assertEqual(moved, 0)
        self.assertFalse(taskstore.active())
        authed.assert_not_called()

    def test_already_moved_does_nothing(self):
        taskstore.migrate()
        moved, git, authed = self.run_migrate()
        self.assertEqual(moved, 0)
        authed.assert_not_called()

    def test_no_todo_file_does_nothing(self):
        Path("TODO.md").unlink()
        self.assertEqual(self.run_migrate()[0], 0)

    def test_a_lost_race_is_redone_on_fresh_main(self):
        calls = []

        def push(*args, **kwargs):
            calls.append(args)
            if args[0] == "push" and calls.count(args) == 1:
                raise subprocess.CalledProcessError(1, args, "", "fetch first")
            return ""
        moved, git, authed = self.run_migrate(push=push)
        self.assertEqual(moved, 3)
        git.assert_any_call("reset", "-q", "--hard", "origin/main")

    def test_someone_else_already_moved_the_tasks(self):
        def push(*args, **kwargs):
            if args[0] == "push":
                raise subprocess.CalledProcessError(1, args, "", "fetch first")
            return ""

        def moved_by_someone_else(*args, **kwargs):
            if args[:2] == ("reset", "-q"):
                taskstore.migrate()
        with mock.patch.object(self, "reset_to_main", moved_by_someone_else):
            self.assertEqual(self.run_migrate(push=push)[0], 0)

    def test_the_last_failed_push_is_raised(self):
        def push(*args, **kwargs):
            if args[0] == "push":
                raise subprocess.CalledProcessError(1, args, "", "denied")
            return ""
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_migrate(push=push)

    def test_the_command_reports_how_many_moved(self):
        with mock.patch.object(swarm, "migrate_tasks", return_value=7), \
             mock.patch("sys.argv", ["swarm.py", "migrate-tasks"]), mock.patch("builtins.print") as shown:
            swarm.main()
        shown.assert_called_with("moved 7 task(s)")


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
        Path("TODO.md").write_text("one\n")
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
        self.branch("pr", {"TODO.md": "worker\n"})
        Path("TODO.md").write_text("main\n")
        self.git("commit", "-qam", "main edit")
        sha, task, files = self.merge("pr")
        self.assertIsNotNone(sha)
        self.assertEqual(files, [])
        self.assertEqual(Path("TODO.md").read_text(), "main\n")

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


if __name__ == "__main__":
    unittest.main()
