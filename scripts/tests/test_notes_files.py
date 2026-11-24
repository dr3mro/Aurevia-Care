import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class NotesFileTests(unittest.TestCase):
    """The shared docs/pr/notes.md made every parallel run conflict with the next."""

    def tracked(self):
        out = subprocess.run(["git", "ls-files", ".agent"], cwd=ROOT, capture_output=True, text=True)
        return out.stdout.split()

    def test_the_shared_notes_file_is_not_tracked(self):
        self.assertNotIn("docs/pr/notes.md", self.tracked(),
                         "git rm --cached docs/pr/notes.md: notes go to docs/pr/notes/<task-id>-<timestamp>.md")

    def test_only_per_run_notes_are_tracked_under_agent(self):
        for path in self.tracked():
            self.assertTrue(path.startswith("docs/pr/notes/"), path)

    def test_the_worker_never_copies_notes_onto_the_shared_path(self):
        script = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertNotIn('"$out/pr-notes.md"', script)
        self.assertIn("publish-notes.md", script)


if __name__ == "__main__":
    unittest.main()
