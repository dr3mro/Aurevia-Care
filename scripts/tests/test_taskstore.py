"""scripts/taskstore.py: one file per task, with TODO.md as a generated view."""
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

taskstore = load("taskstore")

TEXT = (
    "# TODO\n\nIntro prose.\n\n## One\n\nSection text.\n\n"
    "- [ ] A-01 · First\n  - scope: a\n  - details:\n    1. do it\n\n## Two\n\n"
    "- [-] A-02 · Second\n  - scope: b\n"
    "- [x] A-03 · Third\n  - scope: c\n"
)


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        original = Path.cwd()
        os.chdir(self.dir)
        self.addCleanup(os.chdir, original)
        Path("TODO.md").write_text(TEXT)

    def names(self):
        return sorted(p.name for p in Path("tasks").glob("*.md") if p.name != "_head.md")


class SplitTests(unittest.TestCase):
    def test_the_pieces_join_back_into_the_text(self):
        head, chunks = taskstore.split(TEXT)
        self.assertEqual(head + "".join(c for _, c in chunks), TEXT)
        self.assertEqual([i for i, _ in chunks], ["A-01", "A-02", "A-03"])

    def test_headings_stay_with_the_entry_above(self):
        _, chunks = taskstore.split(TEXT)
        self.assertIn("## Two", chunks[0][1])

    def test_text_without_entries_is_all_head(self):
        self.assertEqual(taskstore.split("# Only\n"), ("# Only\n", []))

    def test_unsafe_ids_become_safe_file_names(self):
        self.assertEqual(taskstore.safe("A/B C"), "A_B_C")


class SequenceTests(unittest.TestCase):
    def numbers(self, existing, ids):
        return taskstore.sequences(existing, [(i, "") for i in ids])[1]

    def test_existing_numbers_are_kept(self):
        self.assertEqual(self.numbers({("a", 1): 5, ("b", 1): 9}, ["a", "b"]), [5, 9])

    def test_a_new_task_goes_between_neighbours(self):
        self.assertEqual(self.numbers({("a", 1): 1000, ("c", 1): 2000}, ["a", "b", "c"]), [1000, 1500, 2000])

    def test_a_new_task_at_the_end_steps_on(self):
        self.assertEqual(self.numbers({("a", 1): 1000}, ["a", "b"]), [1000, 2000])

    def test_a_new_task_at_the_start_comes_first(self):
        first, second = self.numbers({("a", 1): 1000}, ["n", "a"])
        self.assertLess(first, second)
        self.assertGreater(first, 0)

    def test_a_moved_task_is_renumbered(self):
        numbers = self.numbers({("a", 1): 1000, ("b", 1): 2000}, ["b", "a"])
        self.assertEqual(sorted(numbers), numbers)

    def test_a_full_gap_renumbers_everything(self):
        self.assertEqual(self.numbers({("a", 1): 1, ("c", 1): 2}, ["a", "b", "c"]), [1000, 2000, 3000])

    def test_a_repeated_id_gets_its_own_number(self):
        numbers = self.numbers({("a", 1): 1000, ("a", 2): 2000}, ["a", "a"])
        self.assertEqual(numbers, [1000, 2000])


class StoreTests(Base):
    def test_migrate_moves_every_task_and_keeps_the_view_exact(self):
        self.assertEqual(taskstore.migrate(), 3)
        self.assertTrue(taskstore.active())
        self.assertEqual(self.names(), ["00001000-A-01.md", "00002000-A-02.md", "00003000-A-03.md"])
        self.assertEqual(Path("TODO.md").read_text(), TEXT)
        self.assertEqual(taskstore.read(), TEXT)

    def test_migrate_twice_does_nothing(self):
        taskstore.migrate()
        self.assertEqual(taskstore.migrate(), 0)

    def test_migrate_without_a_todo_file_does_nothing(self):
        Path("TODO.md").unlink()
        self.assertEqual(taskstore.migrate(), 0)

    def test_migrate_refuses_when_the_files_would_not_rebuild_the_text(self):
        with mock.patch.object(taskstore, "joined", return_value="different"):
            with self.assertRaises(SystemExit):
                taskstore.migrate()

    def test_save_rewrites_only_the_changed_task(self):
        taskstore.migrate()
        for path in Path("tasks").glob("0*.md"):
            os.utime(path, (1, 1))
        taskstore.save(TEXT.replace("[-] A-02", "[x] A-02"))
        changed = [p.name for p in Path("tasks").glob("0*.md") if p.stat().st_mtime > 1]
        self.assertEqual(changed, ["00002000-A-02.md"])
        self.assertIn("[x] A-02", Path("TODO.md").read_text())

    def test_a_new_task_is_inserted_without_renaming_others(self):
        taskstore.migrate()
        taskstore.save(TEXT.replace("- [-] A-02", "- [ ] A-01b · New\n  - scope: z\n- [-] A-02"))
        self.assertEqual(self.names(), ["00001000-A-01.md", "00001500-A-01b.md", "00002000-A-02.md",
                                        "00003000-A-03.md"])

    def test_a_removed_task_loses_its_file(self):
        taskstore.migrate()
        taskstore.save(TEXT.replace("- [x] A-03 · Third\n  - scope: c\n", ""))
        self.assertEqual(self.names(), ["00001000-A-01.md", "00002000-A-02.md"])

    def test_a_task_file_without_an_entry_line_keeps_its_name_as_id(self):
        taskstore.migrate()
        Path("tasks/00009000-ODD.md").write_text("stray text\n")
        self.assertEqual(taskstore.first_id(Path("tasks/00009000-ODD.md")), "ODD")

    def test_an_edit_made_to_the_view_alone_is_adopted(self):
        taskstore.migrate()
        Path("TODO.md").write_text(TEXT.replace("[ ] A-01", "[-] A-01"))
        self.assertIn("[-] A-01", taskstore.read())
        self.assertIn("[-] A-01", Path("tasks/00001000-A-01.md").read_text())

    def test_when_both_changed_the_files_win(self):
        taskstore.migrate()
        Path("TODO.md").write_text(TEXT.replace("[ ] A-01", "[-] A-01"))
        Path("tasks/00003000-A-03.md").write_text("- [x] A-03 · Third, edited\n")
        text = taskstore.read()
        self.assertIn("Third, edited", text)
        self.assertIn("[ ] A-01", text)
        self.assertEqual(Path("TODO.md").read_text(), text)

    def test_a_stale_marker_is_refreshed(self):
        taskstore.migrate()
        Path("tasks/.sharded").write_text("old\n")
        self.assertEqual(taskstore.read(), TEXT)
        self.assertEqual(Path("tasks/.sharded").read_text().strip(), taskstore.digest(TEXT))

    def test_a_missing_view_is_regenerated(self):
        taskstore.migrate()
        Path("TODO.md").unlink()
        self.assertEqual(taskstore.read(), TEXT)
        self.assertEqual(Path("TODO.md").read_text(), TEXT)

    def test_a_stale_view_is_regenerated_from_the_files(self):
        taskstore.migrate()
        taskstore.write(TEXT.replace("[ ] A-01", "[-] A-01"))
        self.assertIn("[-] A-01", taskstore.read())
        self.assertIn("[-] A-01", Path("TODO.md").read_text())


class ArchiveTests(Base):
    def test_finished_entries_are_kept_one_file_each(self):
        taskstore.migrate()
        taskstore.archive(["- [x] A-03 · Third\n  - scope: c\n", "- [x] B/9 · Odd id\n"])
        self.assertTrue(Path("tasks/done/A-03.md").exists())
        self.assertTrue(Path("tasks/done/B_9.md").exists())
        self.assertIn("A-03 · Third", taskstore.archived())

    def test_nothing_archived_reads_as_empty(self):
        self.assertEqual(taskstore.archived(), "")


class MainTests(Base):
    def test_migrate_command(self):
        with mock.patch("builtins.print") as shown:
            self.assertEqual(taskstore.main(["migrate"]), 0)
            self.assertEqual(taskstore.main(["migrate"]), 0)
        self.assertIn("moved 3", shown.call_args_list[0].args[0])
        self.assertEqual(shown.call_args_list[1].args[0], "nothing to migrate")

    def test_unknown_command_prints_usage(self):
        with mock.patch("builtins.print"):
            self.assertEqual(taskstore.main(["nope"]), 2)

    def test_arguments_default_to_the_command_line(self):
        with mock.patch.object(sys, "argv", ["taskstore.py", "nope"]), mock.patch("builtins.print"):
            self.assertEqual(taskstore.main(), 2)

    def test_runs_as_a_script(self):
        import runpy
        with mock.patch.object(sys, "argv", ["taskstore.py", "nope"]), mock.patch("builtins.print"):
            with self.assertRaises(SystemExit) as ended:
                runpy.run_path(str(Path(taskstore.__file__)), run_name="__main__")
        self.assertEqual(ended.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
