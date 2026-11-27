import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class NotesFileTests(unittest.TestCase):
    """A shared notes file made every parallel run conflict with the next."""

    def tracked(self, pathspec):
        out = subprocess.run(["git", "ls-files", pathspec], cwd=ROOT, capture_output=True, text=True)
        return out.stdout.split()

    def test_the_shared_notes_file_is_not_tracked(self):
        self.assertNotIn(".agents/notes.md", self.tracked(".agents"),
                         "notes go to .agents/notes/<task-id>-<timestamp>.md, one file per run")
        self.assertNotIn(".agent/pr-notes.md", self.tracked(".agent"),
                         "the old shared notes file must stay out of the repository")

    @staticmethod
    def is_daily_archive(path):
        """Recognise only dated files the notes workflow archives.

        ``archive_day`` stores originals as a dated zip; ``retire_dailies`` moves
        older daily Markdown into the same directory. Both are legitimate, and
        arbitrary zips or stray files are not.
        """
        return re.fullmatch(
            r"\.agents/notes/archive/[0-9]{4}-[0-9]{2}-[0-9]{2}\.(zip|md)", path
        ) is not None

    def test_only_per_run_notes_are_tracked_under_the_new_directory(self):
        for path in self.tracked(".agents/notes"):
            # The daily reconciliation archives originals as a dated zip; those
            # are not per-run notes and must not be held to the .md naming rule.
            if self.is_daily_archive(path):
                continue
            self.assertTrue(path.startswith(".agents/notes/"), path)
            self.assertTrue(path.endswith(".md"), path)

    def test_daily_archives_keep_the_documented_dated_zip_shape(self):
        archives = [
            path for path in self.tracked(".agents/notes")
            if path.startswith(".agents/notes/archive/")
        ]
        self.assertTrue(archives, "the daily notes workflow archives originals")
        for path in archives:
            self.assertTrue(self.is_daily_archive(path), path)
        self.assertFalse(self.is_daily_archive(".agents/notes/archive/2026-11.zip"))
        self.assertFalse(self.is_daily_archive(".agents/notes/archive/notes.txt"))

    def test_the_worker_never_copies_notes_onto_the_shared_path(self):
        script = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertNotIn(".agents/notes.md", script)
        self.assertIn("publish-notes.md", script)

    def test_no_notes_are_tracked_under_the_old_directory(self):
        self.assertEqual(
            self.tracked(".agent/pr-notes"), [],
            ".agent/pr-notes is local scratch; worker notes live in .agents/notes/",
        )

    def test_worker_prompt_points_to_the_new_notes_directory(self):
        script = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertNotIn("create the pr-notes folder", script)
        self.assertIn("(under .agents/notes/; the file is named after your story", script)


if __name__ == "__main__":
    unittest.main()
