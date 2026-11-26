"""Hermetic tests for authoritative generated-config comparison.

A cloud worker overlays the CI runtime profile on a skip-worktree config file,
so worktree bytes are not always the committed config. These tests use a fake
``subprocess.run`` and synthetic temporary trees only: no real Git repository,
no network and no credentials.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT, copy_pack, load

gen = load("gen-opencode")
pack = load("check-pack")

NAME = ".opencode/opencode.json"


def completed(args, stdout=""):
    """Build a successful CompletedProcess for the fake git boundary."""
    return subprocess.CompletedProcess(args, 0, stdout=stdout, stderr="")


class GeneratedConfigSourceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        (self.tmp / ".opencode").mkdir()
        (self.tmp / NAME).write_text("worktree bytes\n")

    def test_normal_tracked_file_uses_worktree_bytes(self):
        with mock.patch("subprocess.run", return_value=completed([], f"H {NAME}\0")):
            self.assertEqual(gen.read_generated(self.tmp, NAME), "worktree bytes\n")

    def test_skip_worktree_file_uses_committed_blob(self):
        def fake(args, **_kwargs):
            if "ls-files" in args:
                return completed(args, f"S {NAME}\0")
            self.assertEqual(args[-1], f"HEAD:{NAME}")
            return completed(args, "committed bytes\n")

        with mock.patch("subprocess.run", side_effect=fake):
            self.assertEqual(gen.read_generated(self.tmp, NAME), "committed bytes\n")

    def test_skip_worktree_show_failure_fails_closed(self):
        def fake(args, **_kwargs):
            if "ls-files" in args:
                return completed(args, f"S {NAME}\0")
            raise subprocess.CalledProcessError(128, args, stderr="synthetic-secret-sentinel")

        with mock.patch("subprocess.run", side_effect=fake):
            self.assertIsNone(gen.read_generated(self.tmp, NAME))

    def test_no_git_repository_falls_back_to_worktree_bytes(self):
        with mock.patch("subprocess.run",
                        side_effect=subprocess.CalledProcessError(128, ["git"], stderr="not a repo")):
            self.assertEqual(gen.read_generated(self.tmp, NAME), "worktree bytes\n")

    def test_git_failure_in_a_repository_fails_closed(self):
        (self.tmp / ".git").mkdir()
        with mock.patch("subprocess.run",
                        side_effect=subprocess.CalledProcessError(128, ["git"], stderr="bad index")):
            self.assertIsNone(gen.read_generated(self.tmp, NAME))

    def test_missing_git_binary_falls_back_to_worktree_bytes(self):
        with mock.patch("subprocess.run", side_effect=OSError("git missing")):
            self.assertEqual(gen.read_generated(self.tmp, NAME), "worktree bytes\n")

    def test_unexpected_ls_files_failure_fails_closed(self):
        with mock.patch("subprocess.run",
                        side_effect=subprocess.CalledProcessError(2, ["git"], stderr="bad usage")):
            self.assertIsNone(gen.read_generated(self.tmp, NAME))

    def test_missing_generated_file_returns_none(self):
        (self.tmp / NAME).unlink()
        with mock.patch("subprocess.run", return_value=completed([])):
            self.assertIsNone(gen.read_generated(self.tmp, NAME))

    def test_git_calls_are_read_only_and_sanitized(self):
        seen = []

        def fake(args, **kwargs):
            seen.append((args, kwargs))
            return completed(args, f"H {NAME}\0" if "ls-files" in args else "")

        with mock.patch("subprocess.run", side_effect=fake):
            gen.read_generated(self.tmp, NAME)
        args, kwargs = seen[0]
        self.assertEqual(args, ["git", "-C", str(self.tmp), "ls-files", "-v", "-z", "--", NAME])
        self.assertTrue(kwargs["capture_output"])
        self.assertTrue(kwargs["text"])
        self.assertTrue(kwargs["check"])
        self.assertNotIn("shell", kwargs)
        self.assertEqual(kwargs["env"]["GIT_OPTIONAL_LOCKS"], "0")

    def test_check_flag_uses_committed_blob(self):
        def fake(args, **_kwargs):
            if "ls-files" in args:
                return completed(args, f"S {args[-1]}\0")
            name = args[-1].removeprefix("HEAD:")
            return completed(args, gen.render(ROOT, ci=name.endswith("ci.json")))

        with mock.patch("subprocess.run", side_effect=fake), \
                mock.patch.object(gen, "ROOT", ROOT):
            self.assertEqual(gen.main(["--check"]), 0)

    def test_check_flag_reports_stale_committed_blob(self):
        def fake(args, **_kwargs):
            if "ls-files" in args:
                return completed(args, f"S {args[-1]}\0")
            if args[-1].endswith("ci.json"):
                return completed(args, gen.render(ROOT, ci=True))
            return completed(args, gen.render(ROOT).replace('"skill"', '"edit"', 1))

        with mock.patch("subprocess.run", side_effect=fake), \
                mock.patch.object(gen, "ROOT", ROOT):
            with mock.patch("sys.stderr", new=__import__("io").StringIO()) as err:
                self.assertEqual(gen.main(["--check"]), 1)
            self.assertIn("out of date", err.getvalue())
            self.assertNotIn("synthetic", err.getvalue())


class PackUsesCommittedBlobTests(unittest.TestCase):
    def test_check_pack_accepts_committed_blob_and_rejects_stale_blob(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        expected_local = gen.render(tmp)
        expected_ci = gen.render(tmp, ci=True)
        # The worktree carries the CI profile; HEAD holds the correct local profile.
        (tmp / NAME).write_text(expected_ci)

        def committed(args, **_kwargs):
            if "ls-files" in args:
                return completed(args, f"S {args[-1]}\0")
            name = args[-1].removeprefix("HEAD:")
            return completed(args, expected_ci if name.endswith("ci.json") else expected_local)

        with mock.patch("subprocess.run", side_effect=committed):
            self.assertEqual(pack.check(tmp), [])

        def stale(args, **_kwargs):
            if "ls-files" in args:
                return completed(args, f"S {args[-1]}\0")
            if args[-1].endswith("ci.json"):
                return completed(args, expected_ci)
            return completed(args, expected_local.replace('"skill"', '"edit"', 1))

        with mock.patch("subprocess.run", side_effect=stale):
            errors = pack.check(tmp)
        self.assertTrue(any("stale" in e for e in errors))
