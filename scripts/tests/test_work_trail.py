"""Work-trail records are one JSON line each and cannot become Actions commands."""
import json
import tempfile
from unittest import mock
import unittest
from pathlib import Path

from helpers import load

trail = load("work_trail")


class WorkTrailTests(unittest.TestCase):
    def test_record_is_one_json_line_with_every_stage_field(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "nested" / "work-trail.jsonl"
            trail.append("brief", "aurevia-task-advisor", "design the fix", "TASK-01", path)
            trail.append("review", "code-reviewer", "review the fix", "GO", path)
            lines = path.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 2)
        first, second = (json.loads(line) for line in lines)
        self.assertEqual(first["step"], "brief")
        self.assertEqual(first["agent"], "aurevia-task-advisor")
        self.assertEqual(first["outputs"], "TASK-01")
        self.assertEqual(second["step"], "review")
        self.assertTrue(first["at"])

    def test_command_markers_are_quoted_and_no_secret_is_emitted(self):
        hostile = "safe\n::error::fake\n  ##[error]fake"
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "work-trail.jsonl"
            trail.append("feature", "aurevia-product-owner", hostile, "feature=MSG", path)
            text = path.read_text(encoding="utf-8")
        record = json.loads(text)
        self.assertNotIn("\n::error::fake", record["instruction"])
        self.assertNotIn("\n  ##[error]fake", record["instruction"])
        for line in text.splitlines():
            self.assertFalse(line.startswith("::") or line.startswith("##["))
        self.assertNotIn("OPENCODE_API_KEY", text)

    def test_sanitize_leaves_ordinary_text_unchanged(self):
        self.assertEqual(trail.sanitize("one\ntwo"), "one\ntwo")
        self.assertEqual(trail.sanitize(""), "")



class WorkTrailCliTests(unittest.TestCase):
    def test_main_writes_a_record_from_argv(self):
        import contextlib
        import io
        import runpy
        import sys
        import tempfile
        from helpers import ROOT
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "trail.jsonl"
            argv = ["work_trail.py", "--stage", "review", "--agent", "qa",
                    "--instruction", "check", "--outputs", "GO", "--path", str(path)]
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(ROOT / "scripts/work_trail.py"), run_name="__main__")
            self.assertEqual(json.loads(path.read_text())["step"], "review")


if __name__ == "__main__":
    unittest.main()
