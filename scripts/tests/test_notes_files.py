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
        self.assertNotIn("docs/pr/notes.md", self.tracked("docs/pr"),
                         "notes go to docs/pr/notes/<task-id>-<timestamp>.md, one file per run")
        self.assertNotIn(".agent/pr-notes.md", self.tracked(".agent"),
                         "the old shared notes file must stay out of the repository")

    def test_only_per_run_notes_are_tracked_under_the_new_directory(self):
        for path in self.tracked("docs/pr/notes"):
            self.assertTrue(path.startswith("docs/pr/notes/"), path)
            self.assertTrue(path.endswith(".md"), path)

    def test_the_worker_never_copies_notes_onto_the_shared_path(self):
        script = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertNotIn("docs/pr/notes.md", script)
        self.assertIn("publish-notes.md", script)

    def test_no_notes_are_tracked_under_the_old_directory(self):
        self.assertEqual(
            self.tracked(".agent/pr-notes"), [],
            ".agent/pr-notes is local scratch; worker notes live in docs/pr/notes/",
        )

    def test_worker_prompt_points_to_the_new_notes_directory(self):
        script = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertNotIn("create the pr-notes folder", script)
        self.assertIn("(under docs/pr/notes/; the file is named after your story", script)


if __name__ == "__main__":
    unittest.main()
