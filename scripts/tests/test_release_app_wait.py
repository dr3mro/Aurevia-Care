"""Execute the release-app-image gate step against a stubbed GitHub API.

The release must not decide that main is red while its Delivery run is still
in progress. These tests run the exact ``run:`` block of the workflow step with
a stub ``gh`` that returns synthetic runs, and a stub ``sleep`` so the bounded
wait is fast. No network call, credential or patient data is used.
"""
import json
import os
import pathlib
import subprocess
import tempfile
import unittest

import yaml

from helpers import ROOT

SHA = "a" * 40


def gate_step():
    """Return the exact ``run:`` script of the release gate step."""
    doc = yaml.safe_load(
        (ROOT / ".github/workflows/release-app-image.yml").read_text(encoding="utf-8")
    )
    step = next(
        item for item in doc["jobs"]["publish"]["steps"]
        if item.get("name") == "Require a green default-branch run for this commit"
    )
    return step["run"]


def run_record(status="completed", conclusion="success"):
    """A synthetic Delivery run on the exact default-branch commit."""
    return {
        "id": 1,
        "head_sha": SHA,
        "head_branch": "main",
        "event": "push",
        "status": status,
        "conclusion": conclusion,
        "head_repository": {"full_name": "owner/repo"},
    }


class ReleaseAppWaitTest(unittest.TestCase):
    """Wait for an unfinished Delivery run, then check its gate."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = pathlib.Path(self.tmp.name)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        gh = bin_dir / "gh"
        gh.write_text(
            "#!/usr/bin/env bash\n"
            'count="$STUB_STATE/count"\n'
            "n=0\n"
            '[[ -f "$count" ]] && n=$(cat "$count")\n'
            'n=$((n + 1))\n'
            'echo "$n" > "$count"\n'
            'case "$*" in\n'
            '  *"/jobs"*)\n'
            "    printf '%s' '{\"1\": [{\"name\": \"Delivery gate\", "
            '"conclusion": "success"}]}\'\n'
            "    ;;\n"
            "  *)\n"
            '    line=$(sed -n "${n}p" "$STUB_RUNS")\n'
            '    [[ -n "$line" ]] || line=$(tail -n 1 "$STUB_RUNS")\n'
            "    printf '%s' \"$line\"\n"
            "    ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        gh.chmod(0o755)
        sleep = bin_dir / "sleep"
        sleep.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        sleep.chmod(0o755)
        (root / "state").mkdir()
        self.env = dict(os.environ)
        self.env.update(
            PATH=f"{bin_dir}:{self.env['PATH']}",
            STUB_STATE=str(root / "state"),
            STUB_RUNS=str(root / "runs.jsonl"),
            GITHUB_SHA=SHA,
            GITHUB_REPOSITORY="owner/repo",
            DEFAULT_BRANCH="main",
            GH_TOKEN="synthetic-token",
        )

    def set_runs(self, *records):
        (pathlib.Path(self.tmp.name) / "runs.jsonl").write_text(
            "".join(json.dumps({"total_count": len(records), "workflow_runs": [r]}) + "\n"
                    for r in records),
            encoding="utf-8",
        )

    def run_step(self):
        return subprocess.run(
            ["bash", "-c", gate_step()],
            cwd=ROOT,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )

    def call_count(self):
        path = pathlib.Path(self.tmp.name) / "state" / "count"
        return int(path.read_text(encoding="utf-8")) if path.exists() else 0

    def test_waits_for_an_in_progress_delivery_then_accepts_a_green_gate(self):
        self.set_runs(run_record(status="in_progress", conclusion=None), run_record())
        result = self.run_step()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("still running", result.stderr)
        self.assertIn("1 green gate(s)", result.stdout)
        self.assertGreaterEqual(self.call_count(), 3)

    def test_an_unfinished_run_never_becomes_a_green_gate(self):
        self.set_runs(run_record(status="in_progress", conclusion=None))
        result = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertIn("did not finish after 45 attempts", result.stderr)
        self.assertIn("refusing to decide red or green", result.stderr)
        self.assertNotIn("no green gate", result.stderr)
        self.assertGreaterEqual(self.call_count(), 45)
        self.assertEqual(result.stdout, "")

    def test_a_completed_red_run_fails_without_waiting(self):
        self.set_runs(run_record(conclusion="failure"))
        result = self.run_step()
        self.assertEqual(result.returncode, 1)
        self.assertIn("no green gate", result.stderr)
        self.assertNotIn("still running", result.stderr)
        self.assertLessEqual(self.call_count(), 2)


if __name__ == "__main__":
    unittest.main()
